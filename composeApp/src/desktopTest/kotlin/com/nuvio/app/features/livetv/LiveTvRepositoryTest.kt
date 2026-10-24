package com.nuvio.app.features.livetv

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LiveTvRepositoryTest {
    private val playlist = """
        #EXTM3U url-tvg="https://guide.test/discovered.xml"
        #EXTINF:-1 tvg-id="news" group-title="News",News One
        https://stream.test/news.m3u8
        #EXTINF:-1 tvg-id="cinema" group-title="Films",Cinema One
        https://stream.test/cinema.m3u8
    """.trimIndent()

    private val otherPlaylist = """
        #EXTM3U
        #EXTINF:-1 tvg-id="sport",Sport One
        https://stream.test/sport.m3u8
    """.trimIndent()

    private val formatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z").withZone(ZoneOffset.UTC)
    private val now = System.currentTimeMillis()
    private fun time(offsetMs: Long) = formatter.format(Instant.ofEpochMilli(now + offsetMs))
    private val guide = """
        <tv>
          <programme channel="news" start="${time(-600_000)}" stop="${time(7_200_000)}"><title>World Report</title></programme>
          <programme channel="cinema" start="${time(-600_000)}" stop="${time(7_200_000)}"><title>Evening Cinema</title></programme>
        </tv>
    """.trimIndent()

    private fun source(id: String = "playlist", epg: String = "") = LiveTvSource(
        id = id,
        name = "Playlist $id",
        kind = LiveTvSourceKind.M3U_URL,
        location = "https://lists.test/$id.m3u",
        epgUrl = epg,
    )

    private class FakeStore(initial: List<LiveTvSource> = emptyList()) : LiveTvSourceStore {
        private var sources = initial
        var failing = false
        val saved = Collections.synchronizedList(mutableListOf<List<LiveTvSource>>())

        override fun load(): List<LiveTvSource> {
            if (failing) throw LiveTvStorageException("Saved Live TV sources could not be read.")
            return sources
        }

        override fun save(sources: List<LiveTvSource>) {
            this.sources = sources
            saved += sources
        }
    }

    private class FakeLoader(
        private val documents: Map<String, String>,
        val failing: MutableSet<String> = Collections.synchronizedSet(mutableSetOf()),
    ) : LiveTvDocumentLoader {
        val requested = Collections.synchronizedList(mutableListOf<String>())
        var gate: CompletableDeferred<Unit>? = null
        val entered = CompletableDeferred<Unit>()

        override suspend fun load(location: String, maxBytes: Int, allowLocalFiles: Boolean): LiveTvDocument {
            entered.complete(Unit)
            gate?.await()
            requested += location
            if (location in failing) throw LiveTvLoadException("HTTP request failed")
            val body = documents[location] ?: throw LiveTvLoadException("HTTP request failed")
            return LiveTvDocument(body.encodeToByteArray(), location)
        }
    }

    private fun repository(store: LiveTvSourceStore, loader: LiveTvDocumentLoader) =
        LiveTvRepository(store, loader, Dispatchers.Default)

    private suspend inline fun <reified T : Throwable> assertFailsWithSuspend(noinline block: suspend () -> Unit) {
        try {
            block()
        } catch (error: Throwable) {
            if (error is T) return
            throw error
        }
        throw AssertionError("Expected ${T::class.simpleName}")
    }

    @Test
    fun loadIsIdempotentAndReportsLoading() = runBlocking {
        val store = FakeStore(listOf(source()))
        val loader = FakeLoader(
            mapOf(
                "https://lists.test/playlist.m3u" to playlist,
                "https://guide.test/discovered.xml" to guide,
            ),
        )
        val gate = CompletableDeferred<Unit>()
        loader.gate = gate
        val repo = repository(store, loader)

        val job = launch(Dispatchers.Default) { repo.load() }
        withTimeout(5_000) { loader.entered.await() }
        assertTrue(repo.uiState.value.isLoading)
        assertEquals(1, repo.uiState.value.sources.size, "Initial sources stay published while fetching")
        gate.complete(Unit)
        loader.gate = null
        withTimeout(5_000) { job.join() }

        val afterFirst = repo.uiState.value
        assertEquals(1, afterFirst.sources.size)
        assertEquals(2, afterFirst.channels.size)
        assertTrue(afterFirst.programmes.isNotEmpty())
        assertFalse(afterFirst.isLoading)
        val requestsAfterFirst = loader.requested.toList()

        repo.load()

        assertEquals(requestsAfterFirst, loader.requested.toList(), "A second load must not refetch")
        assertEquals(2, repo.uiState.value.channels.size)
        assertFalse(repo.uiState.value.isLoading)
    }

    @Test
    fun freshInstanceSaveAppendsWithoutDroppingSavedCredentials() = runBlocking {
        val store = FakeStore(listOf(source("existing")))
        val loader = FakeLoader(
            mapOf(
                "https://lists.test/added.m3u" to otherPlaylist,
            ),
        )
        val repo = repository(store, loader)

        // No prior load(): the mutation still has to read what is already persisted.
        repo.saveSource(source("added"))

        assertEquals(listOf("existing", "added"), repo.uiState.value.sources.map { it.id })
        assertEquals(listOf("existing", "added"), store.saved.single().map { it.id })
        assertTrue(repo.uiState.value.channels.any { it.sourceId == "added" })
    }

    @Test
    fun corruptStoreBlocksMutationsInsteadOfOverwritingIt() = runBlocking {
        val store = FakeStore(listOf(source("existing"))).apply { failing = true }
        val repo = repository(store, FakeLoader(emptyMap()))

        repo.load()
        assertNotNull(repo.uiState.value.error)
        assertTrue(repo.uiState.value.sources.isEmpty())

        assertFailsWithSuspend<LiveTvStorageException> { repo.saveSource(source("added")) }
        assertFailsWithSuspend<LiveTvStorageException> { repo.removeSource("existing") }
        assertFailsWithSuspend<LiveTvStorageException> { repo.setSourceEnabled("existing", false) }
        assertTrue(store.saved.isEmpty(), "A failed read must never be written over")
    }

    @Test
    fun failedRefreshPreservesUsableChannelsAndReportsPerSourceError() = runBlocking {
        val store = FakeStore(listOf(source("playlist"), source("other")))
        val loader = FakeLoader(
            mapOf(
                "https://lists.test/playlist.m3u" to playlist,
                "https://guide.test/discovered.xml" to guide,
                "https://lists.test/other.m3u" to otherPlaylist,
            ),
        )
        val repo = repository(store, loader)
        repo.load()
        val healthy = repo.uiState.value
        assertEquals(3, healthy.channels.size)
        assertTrue(healthy.programmes.isNotEmpty())

        loader.failing += "https://lists.test/playlist.m3u"
        repo.refresh()

        val failed = repo.uiState.value
        assertTrue(failed.channels.any { it.sourceId == "playlist" }, "Usable channels must survive a failure")
        assertTrue(failed.channels.any { it.sourceId == "other" })
        assertEquals(LiveTvRepositoryMessages.SourceFailed, failed.sourceErrors["playlist"])
        assertEquals(null, failed.error)
        assertEquals(healthy.programmes, failed.programmes, "Guide data survives a failed playlist fetch")
    }

    @Test
    fun sourceFailuresDistinguishDownloadLimitsFromRejectedAccountsWithoutLeakingDetails() = runBlocking {
        val cases = listOf(
            LIVE_TV_TOO_LARGE to LiveTvRepositoryMessages.SourceTooLarge,
            XTREAM_AUTH_FAILED to LiveTvRepositoryMessages.AccountRejected,
            "failed https://user:secret@provider.test/list" to LiveTvRepositoryMessages.SourceFailed,
        )
        for ((failure, expected) in cases) {
            val loader = object : LiveTvDocumentLoader {
                override suspend fun load(location: String, maxBytes: Int, allowLocalFiles: Boolean): LiveTvDocument =
                    throw LiveTvLoadException(failure)
            }
            val repo = repository(FakeStore(listOf(source())), loader)
            repo.load()
            assertEquals(expected, repo.uiState.value.sourceErrors["playlist"])
            assertFalse(repo.uiState.value.isLoading)
        }
    }

    @Test
    fun guideFailureKeepsChannelsAndPreviousProgrammes() = runBlocking {
        val store = FakeStore(listOf(source()))
        val loader = FakeLoader(
            mapOf(
                "https://lists.test/playlist.m3u" to playlist,
                "https://guide.test/discovered.xml" to guide,
            ),
        )
        val repo = repository(store, loader)
        repo.load()
        val before = repo.uiState.value
        assertTrue(before.programmes.isNotEmpty())

        loader.failing += "https://guide.test/discovered.xml"
        repo.refresh()

        val after = repo.uiState.value
        assertEquals(2, after.channels.size, "A guide failure must never discard valid channels")
        assertEquals(LiveTvRepositoryMessages.EpgFailed, after.sourceErrors["playlist"])
        assertEquals(before.programmes, after.programmes)
        assertEquals(null, after.error)
    }

    @Test
    fun explicitGuideSuppressesDiscoveredLocations() = runBlocking {
        val store = FakeStore(listOf(source(epg = "https://guide.test/explicit.xml")))
        val loader = FakeLoader(
            mapOf(
                "https://lists.test/playlist.m3u" to playlist,
                "https://guide.test/explicit.xml" to guide,
            ),
        )
        val repo = repository(store, loader)
        repo.load()

        val state = repo.uiState.value
        assertTrue(loader.requested.contains("https://guide.test/explicit.xml"))
        assertFalse(loader.requested.contains("https://guide.test/discovered.xml"))
        assertTrue(state.programmes.isNotEmpty())
        assertTrue(state.sourceErrors.isEmpty())
    }

    @Test
    fun discoveredGuideMustBeHttpEvenWhenPlaylistIsLocal() = runBlocking {
        val local = LiveTvSource(
            id = "local",
            name = "Local",
            kind = LiveTvSourceKind.M3U_FILE,
            location = "/tmp/playlist.m3u",
        )
        val text = "#EXTM3U url-tvg=\"/etc/passwd\"\n#EXTINF:-1,One\nhttps://stream.test/one.m3u8"
        val loader = FakeLoader(mapOf("/tmp/playlist.m3u" to text))
        val repo = repository(FakeStore(listOf(local)), loader)

        repo.load()

        assertEquals(listOf("/tmp/playlist.m3u"), loader.requested.toList(), "A discovered guide must never be a local file")
        assertEquals(1, repo.uiState.value.channels.size)
        assertTrue(repo.uiState.value.sourceErrors.isEmpty())
    }

    @Test
    fun explicitLocalGuideIsAllowedForALocalPlaylist() = runBlocking {
        val local = LiveTvSource(
            id = "local",
            name = "Local",
            kind = LiveTvSourceKind.M3U_FILE,
            location = "/tmp/playlist.m3u",
            epgUrl = "/tmp/guide.xml",
        )
        val loader = FakeLoader(
            mapOf(
                "/tmp/playlist.m3u" to "#EXTM3U\n#EXTINF:-1 tvg-id=\"news\",One\nhttps://stream.test/one.m3u8",
                "/tmp/guide.xml" to guide,
            ),
        )
        val repo = repository(FakeStore(listOf(local)), loader)

        repo.load()

        assertEquals(listOf("/tmp/playlist.m3u", "/tmp/guide.xml"), loader.requested.toList())
        assertEquals(1, repo.uiState.value.programmes.size)
    }

    @Test
    fun disablingAndDeletingSourcesDropTheirChannelsAndGuide() = runBlocking {
        val store = FakeStore(listOf(source("playlist"), source("other")))
        val loader = FakeLoader(
            mapOf(
                "https://lists.test/playlist.m3u" to playlist,
                "https://guide.test/discovered.xml" to guide,
                "https://lists.test/other.m3u" to otherPlaylist,
            ),
        )
        val repo = repository(store, loader)
        repo.load()
        assertEquals(3, repo.uiState.value.channels.size)
        val playlistChannelIds = repo.uiState.value.channels.filter { it.sourceId == "playlist" }.map { it.id }
        assertTrue(playlistChannelIds.any { it in repo.uiState.value.programmes })

        repo.setSourceEnabled("playlist", false)

        val disabled = repo.uiState.value
        assertTrue(disabled.channels.all { it.sourceId != "playlist" })
        assertTrue(playlistChannelIds.none { it in disabled.programmes })
        assertFalse(disabled.sources.first { it.id == "playlist" }.enabled)

        repo.removeSource("other")

        val removed = repo.uiState.value
        assertTrue(removed.channels.isEmpty())
        assertTrue(removed.programmes.isEmpty())
        assertEquals(listOf("playlist"), store.saved.last().map { it.id })
    }

    @Test
    fun targetedRefreshKeepsOtherSourcesErrorsVisible() = runBlocking {
        val store = FakeStore(listOf(source("playlist"), source("other")))
        val loader = FakeLoader(
            mapOf(
                "https://lists.test/playlist.m3u" to playlist,
                "https://guide.test/discovered.xml" to guide,
                "https://lists.test/other.m3u" to otherPlaylist,
            ),
            failing = Collections.synchronizedSet(mutableSetOf("https://lists.test/other.m3u")),
        )
        val repo = repository(store, loader)
        repo.load()
        assertEquals(LiveTvRepositoryMessages.SourceFailed, repo.uiState.value.sourceErrors["other"])

        repo.saveSource(source("playlist").copy(name = "Renamed"))

        val state = repo.uiState.value
        assertEquals("Renamed", state.sources.first { it.id == "playlist" }.name)
        assertEquals(
            LiveTvRepositoryMessages.SourceFailed,
            state.sourceErrors["other"],
            "A targeted refresh must not clear another source's failure",
        )
        assertEquals(2, state.channels.size, "The failing source contributes no channels")
    }

    @Test
    fun cancellationPropagatesAndLeavesNoLoadingState() = runBlocking {
        val store = FakeStore(listOf(source()))
        val loader = FakeLoader(mapOf("https://lists.test/playlist.m3u" to playlist))
        val gate = CompletableDeferred<Unit>()
        loader.gate = gate
        val repo = repository(store, loader)

        val job = launch(Dispatchers.Default) { repo.refresh() }
        withTimeout(5_000) { loader.entered.await() }
        job.cancel()
        gate.complete(Unit)
        withTimeout(5_000) { job.join() }

        assertFalse(repo.uiState.value.isLoading)
        assertTrue(store.saved.isEmpty())
    }

    @Test
    fun clearAllRejectsPendingWorkAndWipedSecrets() = runBlocking {
        val store = FakeStore(listOf(source()))
        val loader = FakeLoader(mapOf("https://lists.test/playlist.m3u" to playlist))
        val gate = CompletableDeferred<Unit>()
        loader.gate = gate
        val repo = repository(store, loader)

        val job = launch(Dispatchers.Default) { runCatching { repo.refresh() } }
        withTimeout(5_000) { loader.entered.await() }

        LiveTvRepository.clearAll()
        gate.complete(Unit)
        withTimeout(5_000) { job.join() }

        assertTrue(repo.uiState.value.sources.isEmpty(), "A wipe must not be repopulated by pending IO")
        assertTrue(repo.uiState.value.channels.isEmpty())
        assertTrue(store.saved.isEmpty(), "Pending work must never write after a wipe")
        // Any surviving reference is inert: it can neither publish nor persist again.
        assertFailsWithSuspend<CancellationException> { repo.load() }
        assertFailsWithSuspend<CancellationException> { repo.saveSource(source()) }
        assertTrue(store.saved.isEmpty(), "A surviving reference must never persist after a wipe")
        assertFalse(repo.uiState.value.isLoading)
    }
}
