package com.nuvio.app.features.livetv

import java.net.URI
import java.nio.file.Paths
import java.util.UUID

/** Fields the source editor can report back before a source is persisted. */
internal enum class LiveTvSourceFieldError {
    NAME,
    PLAYLIST_URL,
    PLAYLIST_FILE,
    HOST,
    USERNAME,
    PASSWORD,
    EPG,
}

/**
 * Editable source state. Playlist locations are kept apart per kind so switching
 * the kind never leaks a host into a file field or the other way around.
 */
internal data class LiveTvSourceDraft(
    val id: String,
    val name: String = "",
    val kind: LiveTvSourceKind = LiveTvSourceKind.M3U_URL,
    val playlistUrl: String = "",
    val playlistFile: String = "",
    val host: String = "",
    val username: String = "",
    val password: String = "",
    val epg: String = "",
    val enabled: Boolean = true,
)

internal sealed interface LiveTvSourceValidation {
    data class Valid(val source: LiveTvSource) : LiveTvSourceValidation
    data class Invalid(val field: LiveTvSourceFieldError) : LiveTvSourceValidation
}

/** Source IDs are opaque; the dialog owns generation so the repository only stores them. */
internal fun newLiveTvSourceId(): String = UUID.randomUUID().toString()

internal fun LiveTvSource.toDraft(): LiveTvSourceDraft = LiveTvSourceDraft(
    id = id,
    name = name,
    kind = kind,
    playlistUrl = location.takeIf { kind == LiveTvSourceKind.M3U_URL }.orEmpty(),
    playlistFile = location.takeIf { kind == LiveTvSourceKind.M3U_FILE }.orEmpty(),
    host = location.takeIf { kind == LiveTvSourceKind.XTREAM }.orEmpty(),
    username = username,
    password = password,
    epg = epgUrl,
    enabled = enabled,
)

internal fun validateLiveTvSourceDraft(draft: LiveTvSourceDraft): LiveTvSourceValidation {
    if (draft.name.isBlank()) return LiveTvSourceValidation.Invalid(LiveTvSourceFieldError.NAME)

    val location = when (draft.kind) {
        LiveTvSourceKind.M3U_URL -> {
            val url = draft.playlistUrl.trim()
            if (!isLiveTvHttpUrl(url)) {
                return LiveTvSourceValidation.Invalid(LiveTvSourceFieldError.PLAYLIST_URL)
            }
            url
        }

        LiveTvSourceKind.M3U_FILE -> {
            val path = draft.playlistFile.trim()
            if (path.isEmpty()) {
                return LiveTvSourceValidation.Invalid(LiveTvSourceFieldError.PLAYLIST_FILE)
            }
            path
        }

        LiveTvSourceKind.XTREAM -> {
            val host = draft.host.trim()
            if (!isLiveTvHttpUrl(host)) {
                return LiveTvSourceValidation.Invalid(LiveTvSourceFieldError.HOST)
            }
            host
        }
    }

    if (draft.kind == LiveTvSourceKind.XTREAM) {
        if (draft.username.isBlank()) {
            return LiveTvSourceValidation.Invalid(LiveTvSourceFieldError.USERNAME)
        }
        if (draft.password.isBlank()) {
            return LiveTvSourceValidation.Invalid(LiveTvSourceFieldError.PASSWORD)
        }
    }

    val epg = draft.epg.trim()
    if (epg.isNotEmpty() && !isLiveTvHttpUrl(epg) && !looksLikeLocalLiveTvFile(epg)) {
        return LiveTvSourceValidation.Invalid(LiveTvSourceFieldError.EPG)
    }

    val xtream = draft.kind == LiveTvSourceKind.XTREAM
    return LiveTvSourceValidation.Valid(
        LiveTvSource(
            id = draft.id,
            name = draft.name.trim(),
            kind = draft.kind,
            location = location,
            username = if (xtream) draft.username.trim() else "",
            password = if (xtream) draft.password else "",
            epgUrl = epg,
            enabled = draft.enabled,
        ),
    )
}

private fun isLiveTvHttpUrl(value: String): Boolean = runCatching {
    val uri = URI(value.trim())
    uri.scheme?.lowercase() in setOf("http", "https") &&
        !uri.host.isNullOrBlank() && (uri.port == -1 || uri.port in 1..65535)
}.getOrDefault(false)

private fun looksLikeLocalLiveTvFile(value: String): Boolean = runCatching {
    val path = value.trim()
    if (path.isEmpty()) return@runCatching false
    val windowsPath = Regex("^[A-Za-z]:[/\\\\].*").matches(path)
    val scheme = if (windowsPath) null else Regex("^([A-Za-z][A-Za-z0-9+.-]*):")
        .find(path)?.groupValues?.get(1)
    if (scheme != null) {
        return@runCatching scheme.equals("file", ignoreCase = true) &&
            !URI(path).path.isNullOrBlank()
    }
    Paths.get(path) // Validate the local path without requiring the file to exist yet.
    windowsPath || path.endsWith(".xml", ignoreCase = true) ||
        path.endsWith(".xml.gz", ignoreCase = true) || path.contains('/') || path.contains('\\')
}.getOrDefault(false)

/** Name search plus optional source and group filters. Repository order is preserved. */
internal fun List<LiveTvChannel>.applyLiveTvFilters(
    query: String,
    sourceId: String?,
    group: String?,
): List<LiveTvChannel> {
    val needle = query.trim().lowercase()
    if (needle.isEmpty() && sourceId == null && group == null) return this
    return filter { channel ->
        (sourceId == null || channel.sourceId == sourceId) &&
            (group == null || channel.group == group) &&
            (needle.isEmpty() || channel.name.lowercase().contains(needle))
    }
}

internal fun List<LiveTvChannel>.liveTvGroups(): List<String> =
    map { it.group }
        .filter { it.isNotBlank() }
        .distinct()
        .sortedBy { it.lowercase() }

internal fun List<LiveTvProgramme>.sortedLiveTvProgrammes(): List<LiveTvProgramme> =
    sortedWith(compareBy({ it.startEpochMs }, { it.endEpochMs }))

internal fun List<LiveTvProgramme>.nowPlaying(nowMs: Long): LiveTvProgramme? =
    sortedLiveTvProgrammes().firstOrNull { nowMs >= it.startEpochMs && nowMs < it.endEpochMs }

internal fun List<LiveTvProgramme>.upcomingProgrammes(nowMs: Long): List<LiveTvProgramme> =
    sortedLiveTvProgrammes().filter { it.startEpochMs > nowMs && it.endEpochMs > it.startEpochMs }

/** One sort per channel row: the current programme and the first still-upcoming one. */
internal data class LiveTvNowNext(
    val current: LiveTvProgramme?,
    val next: LiveTvProgramme?,
)

internal fun List<LiveTvProgramme>.nowAndNext(nowMs: Long): LiveTvNowNext {
    var current: LiveTvProgramme? = null
    var next: LiveTvProgramme? = null
    sortedLiveTvProgrammes().forEach { programme ->
        if (programme.endEpochMs <= programme.startEpochMs) return@forEach
        if (current == null && nowMs >= programme.startEpochMs && nowMs < programme.endEpochMs) {
            current = programme
        } else if (next == null && programme.startEpochMs > nowMs) {
            next = programme
        }
    }
    return LiveTvNowNext(current = current, next = next)
}

/** Current programme first, then everything still upcoming, capped for a compact guide row. */
internal fun List<LiveTvProgramme>.guideWindow(nowMs: Long, limit: Int): List<LiveTvProgramme> {
    if (limit <= 0) return emptyList()
    return (listOfNotNull(nowPlaying(nowMs)) + upcomingProgrammes(nowMs)).take(limit)
}

internal fun LiveTvProgramme.progressAt(nowMs: Long): Float {
    val duration = endEpochMs - startEpochMs
    if (duration <= 0L) return 0f
    return ((nowMs - startEpochMs).toFloat() / duration.toFloat()).coerceIn(0f, 1f)
}

/**
 * Only available, enabled channels take part in playback cycling, so a stale browse
 * context can never resurrect a deleted or disabled source.
 */
internal fun liveTvPlaybackChannelIds(
    request: LiveTvPlaybackRequest,
    availableChannelIds: Set<String>,
): List<String> {
    val context = request.channelIds.filter { it in availableChannelIds }.distinct()
    return when {
        request.channelId in context -> context
        request.channelId in availableChannelIds -> listOf(request.channelId) + context
        else -> context
    }
}

/** Wrap-around neighbour inside the resolved playlist; null when cycling is not possible. */
internal fun neighbouringLiveTvChannelId(
    playlist: List<String>,
    currentChannelId: String,
    forward: Boolean,
): String? {
    if (playlist.size < 2) return null
    val index = playlist.indexOf(currentChannelId)
    if (index < 0) return null
    return playlist[if (forward) (index + 1) % playlist.size else (index - 1 + playlist.size) % playlist.size]
}

private val liveTvUrlPattern = Regex("[a-zA-Z][a-zA-Z0-9+.\\-]*://\\S+")

/**
 * Repository and network messages may quote a playlist or Xtream URL that carries
 * credentials. Replace every link before the text reaches a label or error surface.
 */
internal fun sanitizedLiveTvMessage(raw: String?, linkPlaceholder: String): String? =
    raw?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.replace(liveTvUrlPattern, linkPlaceholder)
