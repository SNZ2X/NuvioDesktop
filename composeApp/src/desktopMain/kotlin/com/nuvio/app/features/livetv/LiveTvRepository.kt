package com.nuvio.app.features.livetv

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicLong

/** Fixed user-facing failures. No path, credential or playlist URL ever reaches the UI. */
internal object LiveTvRepositoryMessages {
    const val LoadFailed = "Live TV sources could not be loaded."
    const val CorruptStorage = "Saved Live TV sources could not be read."
    const val TooManySources = "Too many Live TV sources."
    const val InvalidSource = "This source is not valid."
    const val SourceFailed = "This source could not be refreshed."
    const val SourceTooLarge = "This source exceeds the supported size limit."
    const val AccountRejected = "The provider rejected this account."
    const val EpgFailed = "Guide data could not be loaded."
}

/**
 * Profile-scoped Live TV state: persisted source configuration plus the last usable
 * channels and guide.
 *
 * Invariants:
 * - All operations are serialized through one [Mutex]; the published state is the only
 *   authority for source order and for the channels a failed fetch must preserve.
 * - A failed source fetch keeps that source's previous channels and programmes and is
 *   reported through [LiveTvState.sourceErrors]. Guide failures keep freshly fetched
 *   channels; the guide error is reported separately and never discards them.
 * - Disabled or deleted sources never keep channels.
 * - [clearAll] bumps a process-wide generation under a monitor that also guards every
 *   store write and every state publish. Work started before a wipe therefore either
 *   finishes before [clearAll] returns or is discarded; it can never republish wiped
 *   sources or write them back to disk.
 * - Storage corruption is reported and blocks writes, so a failed read can never
 *   silently replace saved credentials with an empty or partial source list.
 */
internal class LiveTvRepository(
    private val store: LiveTvSourceStore,
    private val loader: LiveTvDocumentLoader,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutex = Mutex()
    private val generation: Long
    private val _uiState = MutableStateFlow(LiveTvState())
    val uiState: StateFlow<LiveTvState> = _uiState.asStateFlow()

    /** Source configuration was read from the store at least once. */
    private var initialized = false

    /** A full refresh completed, so load() has no remaining work. */
    private var dataLoaded = false

    @Volatile
    private var cleared = false

    init {
        // Every instance, including ones constructed directly (tests, alternate loaders),
        // must be reachable by clearAll. A weak set keeps that true without leaking
        // abandoned repositories; registration shares the monitor that gates publishing.
        generation = synchronized(LiveTvGeneration.Monitor) {
            register(this)
            LiveTvGeneration.current()
        }
    }

    suspend fun load() {
        mutex.withLock {
            ensureAlive()
            if (initialized && dataLoaded) return
            beginOperation()
            try {
                if (!initialized) {
                    if (!reloadSources()) return
                    initialized = true
                }
                if (dataLoaded) return
                refreshSources(uiState.value.sources, only = null)
                dataLoaded = true
            } finally {
                endOperation()
            }
        }
    }

    suspend fun refresh() {
        mutex.withLock {
            ensureAlive()
            beginOperation()
            try {
                // A previous read failure is retried here too: the store may have been
                // repaired, and refresh must never overwrite credentials it could not read.
                if (!initialized) {
                    if (!reloadSources()) return
                    initialized = true
                }
                refreshSources(uiState.value.sources, only = null)
                dataLoaded = true
            } finally {
                endOperation()
            }
        }
    }

    suspend fun saveSource(source: LiveTvSource) {
        val normalized = normalize(source)
        mutex.withLock {
            ensureAlive()
            ensureLoaded()
            val sources = uiState.value.sources.toMutableList()
            val index = sources.indexOfFirst { it.id == normalized.id }
            if (index >= 0) {
                sources[index] = normalized
            } else {
                if (sources.size >= LiveTvMaxSources) {
                    throw LiveTvStorageException(LiveTvRepositoryMessages.TooManySources)
                }
                sources += normalized
            }
            beginOperation()
            try {
                persist(sources)
                publish { it.copy(sources = sources, error = null) }
                if (normalized.enabled) {
                    refreshSources(sources, only = setOf(normalized.id))
                } else {
                    dropSourceData(normalized.id)
                }
            } finally {
                endOperation()
            }
        }
    }

    suspend fun removeSource(sourceId: String) {
        mutex.withLock {
            ensureAlive()
            ensureLoaded()
            val sources = uiState.value.sources.filterNot { it.id == sourceId }
            persist(sources)
            publish { it.copy(sources = sources, error = null) }
            dropSourceData(sourceId)
        }
    }

    suspend fun setSourceEnabled(sourceId: String, enabled: Boolean) {
        mutex.withLock {
            ensureAlive()
            ensureLoaded()
            val current = uiState.value.sources
            if (current.none { it.id == sourceId }) return
            val sources = current.map { source ->
                if (source.id == sourceId) source.copy(enabled = enabled) else source
            }
            persist(sources)
            publish { it.copy(sources = sources, error = null) }
            if (!enabled) {
                dropSourceData(sourceId)
            } else {
                beginOperation()
                try {
                    refreshSources(sources, only = setOf(sourceId))
                } finally {
                    endOperation()
                }
            }
        }
    }

    /** Called from [clearAll] while the process-wide wipe monitor is held. */
    internal fun invalidate() {
        cleared = true
        initialized = false
        dataLoaded = false
        synchronized(LiveTvGeneration.Monitor) {
            // The generation bump already blocks publishes; this reset is what removes the
            // credentials a still-composed screen would otherwise keep displaying.
            _uiState.value = LiveTvState()
        }
        // Keep the same monitor → store lock order as persist().
        (store as? LiveTvClearableSourceStore)?.clear()
    }

    /**
     * Mutations must never persist a state that was never read back: saving before a
     * successful load would replace the whole saved source list (and its credentials) with
     * whatever this instance happens to hold. A failed read therefore blocks the write.
     */
    private suspend fun ensureLoaded() {
        if (initialized) return
        if (!reloadSources()) throw LiveTvStorageException(LiveTvRepositoryMessages.CorruptStorage)
        initialized = true
    }

    /** Reads persisted sources into state. Returns false when the store could not be read. */
    private suspend fun reloadSources(): Boolean {
        val sources = try {
            withContext(dispatcher) { store.load() }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            publish { it.copy(sources = emptyList(), error = safeMessage(error)) }
            return false
        }
        publish { it.copy(sources = sources, error = null) }
        return true
    }

    private suspend fun refreshSources(allSources: List<LiveTvSource>, only: Set<String>?) {
        val previous = uiState.value
        val previousChannels = previous.channels.groupBy { it.sourceId }
        val previousProgrammes = previous.programmes
        val nextChannels = ArrayList<LiveTvChannel>()
        val nextErrors = LinkedHashMap<String, String>()
        if (only != null) {
            // A targeted refresh neither clears nor re-evaluates other enabled sources; their
            // last failure stays visible until a full refresh actually retries them.
            val enabledIds = allSources.filter { it.enabled }.mapTo(HashSet()) { it.id }
            previous.sourceErrors.forEach { (sourceId, message) ->
                if (sourceId !in only && sourceId in enabledIds) nextErrors[sourceId] = message
            }
        }
        val fetchedProgrammes = LinkedHashMap<String, List<LiveTvProgramme>>()
        val replacedChannelIds = HashSet<String>()

        for (source in allSources) {
            if (!source.enabled) continue
            ensureAlive()
            if (only != null && source.id !in only) {
                previousChannels[source.id]?.let(nextChannels::addAll)
                continue
            }

            var failureMessage = LiveTvRepositoryMessages.SourceFailed
            val result = try {
                withContext(dispatcher) { fetchSource(source) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                // Keep actionable known reasons without exposing provider bodies or URLs.
                if (error is LiveTvLoadException) {
                    failureMessage = when (error.message) {
                        LIVE_TV_TOO_LARGE -> LiveTvRepositoryMessages.SourceTooLarge
                        XTREAM_AUTH_FAILED -> LiveTvRepositoryMessages.AccountRejected
                        else -> LiveTvRepositoryMessages.SourceFailed
                    }
                }
                null
            }

            if (result == null) {
                nextErrors[source.id] = failureMessage
                // Keep the last usable channels (and their guide) for this source.
                previousChannels[source.id]?.let(nextChannels::addAll)
                continue
            }

            // A guide-only failure keeps this source's previous programmes: the channels are
            // fresh but their guide was not replaced, so dropping the entries would lose data.
            if (result.error == null) previousChannels[source.id]?.forEach { replacedChannelIds += it.id }
            nextChannels += result.channels
            fetchedProgrammes.putAll(result.programmes)
            result.error?.let { nextErrors[source.id] = it }
        }

        val keepIds = nextChannels.mapTo(HashSet()) { it.id }
        val programmes = LinkedHashMap<String, List<LiveTvProgramme>>()
        previousProgrammes.forEach { (channelId, entries) ->
            if (channelId in keepIds && channelId !in replacedChannelIds) programmes[channelId] = entries
        }
        fetchedProgrammes.forEach { (channelId, entries) ->
            if (channelId in keepIds) programmes[channelId] = entries
        }

        publish {
            it.copy(
                channels = nextChannels,
                programmes = programmes,
                sourceErrors = nextErrors,
                error = null,
            )
        }
    }

    private suspend fun fetchSource(source: LiveTvSource): SourceFetch {
        val (content, baseLocation) = fetchContent(source)
        if (content.channels.isEmpty()) return SourceFetch(content.channels, emptyMap())

        val locations = epgLocations(source, content, baseLocation)
        if (locations.isEmpty()) return SourceFetch(content.channels, emptyMap())

        val now = System.currentTimeMillis()
        val merged = LinkedHashMap<String, MutableList<LiveTvProgramme>>()
        for (location in locations) {
            ensureAlive()
            val parsed = try {
                val document = loader.load(
                    location.value,
                    LiveTvLimits.XMLTV_BYTES,
                    allowLocalFiles = location.local,
                )
                ByteArrayInputStream(document.bytes).use { stream ->
                    XmltvParser.parse(stream, content.channels, now)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                // The playlist succeeded: publish its channels and report only the guide gap.
                return SourceFetch(
                    channels = content.channels,
                    programmes = merged.mapValues { (_, entries) -> entries.sortedBy { it.startEpochMs } },
                    error = LiveTvRepositoryMessages.EpgFailed,
                )
            }
            parsed.forEach { (channelId, programmes) ->
                merged.getOrPut(channelId) { mutableListOf() } += programmes
            }
        }
        val programmes = merged.mapValues { (_, entries) ->
            entries.distinctBy { it.startEpochMs to it.title }.sortedBy { it.startEpochMs }
        }
        return SourceFetch(content.channels, programmes)
    }

    private suspend fun fetchContent(source: LiveTvSource): Pair<LiveTvSourceContent, String?> = when (source.kind) {
        LiveTvSourceKind.M3U_URL -> {
            val document = loader.load(source.location, LiveTvLimits.PLAYLIST_BYTES)
            M3uParser.parse(
                text = document.bytes.toString(Charsets.UTF_8),
                sourceId = source.id,
                baseUrl = document.location,
            ) to document.location
        }

        LiveTvSourceKind.M3U_FILE -> {
            val document = loader.load(source.location, LiveTvLimits.PLAYLIST_BYTES, allowLocalFiles = true)
            // Relative stream references in a local file have no fetchable base: this layer
            // never turns a filesystem path into a URL.
            M3uParser.parse(
                text = document.bytes.toString(Charsets.UTF_8),
                sourceId = source.id,
                baseUrl = null,
            ) to null
        }

        LiveTvSourceKind.XTREAM -> XtreamClient(loader).load(source) to source.location
    }

    /**
     * An explicitly configured guide location may be a local XMLTV file. Guide locations
     * discovered inside a remote playlist are restricted to http(s), so a playlist can
     * never make the loader read a local file.
     */
    private fun epgLocations(
        source: LiveTvSource,
        content: LiveTvSourceContent,
        baseLocation: String?,
    ): List<EpgLocation> {
        val explicit = source.epgUrl.trim()
        if (explicit.isNotEmpty()) {
            // Configuration wins: a chosen guide replaces whatever the playlist advertises,
            // so a broken advertised guide can never fail a source the user fixed.
            return listOf(EpgLocation(explicit, local = !isHttpUrl(explicit)))
        }
        val result = LinkedHashMap<String, EpgLocation>()
        content.epgUrls.forEach { candidate ->
            val resolved = LiveTvUrls.resolveHttp(candidate, baseLocation) ?: return@forEach
            result.putIfAbsent(resolved, EpgLocation(resolved, local = false))
        }
        return result.values.take(MaxEpgLocations)
    }

    private suspend fun persist(sources: List<LiveTvSource>) {
        withContext(dispatcher) {
            // The monitor is held only around the blocking write, never across a suspension
            // point, so clearAll either waits for this write or prevents it entirely.
            synchronized(LiveTvGeneration.Monitor) {
                ensureAliveLocked()
                store.save(sources)
            }
        }
    }

    private fun dropSourceData(sourceId: String) {
        publish {
            val remaining = it.channels.filterNot { channel -> channel.sourceId == sourceId }
            val ids = remaining.mapTo(HashSet()) { it.id }
            it.copy(
                channels = remaining,
                programmes = it.programmes.filterKeys { channelId -> channelId in ids },
                sourceErrors = it.sourceErrors - sourceId,
                error = null,
            )
        }
    }

    private fun normalize(source: LiveTvSource): LiveTvSource {
        val id = source.id.trim()
        if (id.isEmpty() || id.length > MaxIdLength) {
            throw LiveTvStorageException(LiveTvRepositoryMessages.InvalidSource)
        }
        val xtream = source.kind == LiveTvSourceKind.XTREAM
        return source.copy(
            id = id,
            name = source.name.trim(),
            location = source.location.trim(),
            username = if (xtream) source.username.trim() else "",
            password = if (xtream) source.password else "",
            epgUrl = source.epgUrl.trim(),
        )
    }

    private fun beginOperation() {
        publish { it.copy(isLoading = true, error = null) }
    }

    private fun endOperation() {
        if (cleared || generation != LiveTvGeneration.current()) return
        synchronized(LiveTvGeneration.Monitor) {
            if (cleared || generation != LiveTvGeneration.current()) return
            _uiState.value = _uiState.value.copy(isLoading = false)
        }
    }

    private fun publish(transform: (LiveTvState) -> LiveTvState) {
        synchronized(LiveTvGeneration.Monitor) {
            ensureAliveLocked()
            _uiState.value = transform(_uiState.value)
        }
    }

    private fun ensureAlive() {
        if (cleared || generation != LiveTvGeneration.current()) throw clearedCancellation()
    }

    private fun ensureAliveLocked() {
        if (generation != LiveTvGeneration.current()) throw clearedCancellation()
    }

    private fun clearedCancellation(): CancellationException =
        CancellationException("Live TV data was cleared for this profile.")

    private fun safeMessage(error: Throwable): String = when (error) {
        is LiveTvStorageException -> error.message ?: LiveTvRepositoryMessages.LoadFailed
        else -> LiveTvRepositoryMessages.LoadFailed
    }

    private data class SourceFetch(
        val channels: List<LiveTvChannel>,
        val programmes: Map<String, List<LiveTvProgramme>>,
        val error: String? = null,
    )

    private data class EpgLocation(val value: String, val local: Boolean)

    private fun isHttpUrl(value: String): Boolean {
        val lower = value.trim().lowercase()
        return lower.startsWith("http://") || lower.startsWith("https://")
    }

    companion object {
        private const val MaxIdLength = 128
        private const val MaxEpgLocations = 4

        private val repositories = HashMap<Int, LiveTvRepository>()

        /**
         * Every live instance, weakly referenced so abandoned repositories (a screen that was
         * re-keyed, a test) do not leak. Guarded by [LiveTvGeneration.Monitor] together with
         * every publish/write and the profile map, so construction, lookup and wipe share one
         * lock order and a wipe can never race a registration.
         */
        private val instances = ArrayList<WeakReference<LiveTvRepository>>()

        private fun register(repository: LiveTvRepository) {
            instances.removeAll { it.get() == null }
            instances += WeakReference(repository)
        }

        private fun liveInstances(): List<LiveTvRepository> =
            instances.mapNotNull { it.get() }.also { instances.clear() }

        fun forProfile(profileId: Int): LiveTvRepository = synchronized(LiveTvGeneration.Monitor) {
            repositories[profileId] ?: LiveTvRepository(
                store = EncryptedLiveTvSourceStore(profileId),
                loader = LiveTvHttpLoader(),
            ).also { repositories[profileId] = it }
        }

        /**
         * Invalidates every profile repository before account storage is wiped. Blocks until
         * in-flight writes settle, drops all live instances, deletes their keys/ciphertext,
         * and rejects later publishes or writes from work that was still pending.
         */
        fun clearAll() {
            // Keep deletion in the same critical section as creation and writes: a new
            // repository must not write a key that an old instance is about to delete.
            synchronized(LiveTvGeneration.Monitor) {
                LiveTvGeneration.bump()
                repositories.clear()
                liveInstances().forEach(LiveTvRepository::invalidate)
            }
        }
    }
}

/**
 * Process-wide wipe generation. [LiveTvGeneration.Monitor] is the single lock shared by
 * every publish, every store write, repository construction and [LiveTvRepository.clearAll],
 * so a wipe can never interleave with an operation that would resurrect credentials.
 */
private object LiveTvGeneration {
    val Monitor = Any()
    private val current = AtomicLong(0)

    fun current(): Long = current.get()

    fun bump(): Long = current.incrementAndGet()
}
