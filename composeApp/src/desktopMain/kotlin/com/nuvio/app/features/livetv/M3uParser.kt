package com.nuvio.app.features.livetv

internal const val LIVE_TV_PLAYLIST_INVALID: String = "Playlist is not a valid M3U channel list"
internal const val LIVE_TV_PLAYLIST_HLS: String = "Playlist is an HLS stream manifest, not a channel list"

/**
 * M3U/M3U8 channel-list parser.
 *
 * The parser is forgiving about provider noise (missing titles, stray comments,
 * EXTVLCOPT/KODIPROP lines) but strict about the document itself: a response that is not an
 * `#EXTM3U` playlist, or that is an HLS media/master manifest, is reported as a fixed parse
 * failure so the repository can tell an HTML login page or a stream manifest apart from a
 * genuinely empty but valid playlist.
 */
internal object M3uParser {
    private const val MAX_ENTRIES = 50_000
    private const val MAX_LINE_LENGTH = 16_384
    private const val MAX_NAME_LENGTH = 256
    private const val MAX_GROUP_LENGTH = 128
    private const val MAX_EPG_ID_LENGTH = 128
    private const val MAX_HEADER_VALUE_LENGTH = 1_024
    private const val MAX_HEADERS = 8
    private const val MAX_EPG_URLS = 8

    /** Tags that only appear in an HLS media or master manifest, never in a channel list. */
    private val HLS_TAGS = listOf(
        "#EXT-X-TARGETDURATION",
        "#EXT-X-MEDIA-SEQUENCE",
        "#EXT-X-PLAYLIST-TYPE",
        "#EXT-X-ENDLIST",
        "#EXT-X-STREAM-INF",
        "#EXT-X-MEDIA:",
        "#EXT-X-KEY",
        "#EXT-X-MAP",
        "#EXT-X-VERSION",
        "#EXT-X-PROGRAM-DATE-TIME",
        "#EXT-X-DISCONTINUITY",
    )

    fun parse(text: String, sourceId: String, baseUrl: String? = null): LiveTvSourceContent {
        val body = text.removePrefix("\uFEFF")
        val epgUrls = LinkedHashSet<String>()
        val channels = ArrayList<LiveTvChannel>()
        val seenIds = HashSet<String>()

        var pending: PendingEntry? = null
        var pendingGroup = ""
        var pendingHeaders = LinkedHashMap<String, String>()
        var firstLine = true

        for (rawLine in splitLines(body)) {
            if (rawLine.length > MAX_LINE_LENGTH) continue
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            val upper = line.uppercase()
            val isHeader = isM3uHeader(upper)
            if (firstLine) {
                firstLine = false
                when {
                    HLS_TAGS.any { upper.startsWith(it) } -> throw LiveTvParseException(LIVE_TV_PLAYLIST_HLS)
                    !isHeader -> throw LiveTvParseException(LIVE_TV_PLAYLIST_INVALID)
                }
            }

            if (isHeader) {
                parseEpgUrls(line.substring("#EXTM3U".length), baseUrl, epgUrls)
                continue
            }
            if (HLS_TAGS.any { upper.startsWith(it) }) {
                throw LiveTvParseException(LIVE_TV_PLAYLIST_HLS)
            }
            if (!line.startsWith("#")) {
                val entry = pending
                if (entry != null) {
                    buildChannel(
                        entry = entry,
                        urlLine = line,
                        group = pendingGroup,
                        optionHeaders = pendingHeaders,
                        sourceId = sourceId,
                        baseUrl = baseUrl,
                        seenIds = seenIds,
                    )?.let { channels.add(it) }
                    if (channels.size >= MAX_ENTRIES) break
                }
                pending = null
                pendingGroup = ""
                pendingHeaders = LinkedHashMap()
                continue
            }

            when {
                upper.startsWith("#EXTINF:") -> {
                    pending = parseExtInf(line.substring("#EXTINF:".length))
                    pendingGroup = ""
                    pendingHeaders = LinkedHashMap()
                }

                upper.startsWith("#EXTGRP:") -> {
                    if (pending != null) {
                        pendingGroup = line.substring("#EXTGRP:".length)
                            .trim()
                            .take(MAX_GROUP_LENGTH)
                    }
                }

                upper.startsWith("#EXTVLCOPT:") -> {
                    if (pending != null) {
                        addVlcOption(line.substring("#EXTVLCOPT:".length), pendingHeaders)
                    }
                }
            }
        }

        if (firstLine) throw LiveTvParseException(LIVE_TV_PLAYLIST_INVALID)
        return LiveTvSourceContent(channels = channels, epgUrls = epgUrls.toList())
    }

    /** `#EXTM3U` must be a whole token; `#EXTM3U8` or `#EXTM3UX` is a different tag. */
    private fun isM3uHeader(upperLine: String): Boolean {
        if (!upperLine.startsWith("#EXTM3U")) return false
        val rest = upperLine.substring("#EXTM3U".length)
        return rest.isEmpty() || rest[0].isWhitespace()
    }

    private class PendingEntry(
        val title: String,
        val attributes: Map<String, String>,
    )

    private fun buildChannel(
        entry: PendingEntry,
        urlLine: String,
        group: String,
        optionHeaders: Map<String, String>,
        sourceId: String,
        baseUrl: String?,
        seenIds: MutableSet<String>,
    ): LiveTvChannel? {
        val attributes = entry.attributes
        val tvgName = attributes["tvg-name"].orEmpty().trim()
        val name = entry.title.trim().ifEmpty { tvgName }
            .takeIf { it.isNotEmpty() && it.length <= MAX_NAME_LENGTH }
            ?: return null

        val (rawUrl, suffixHeaders) = splitUrlHeaders(urlLine)
        val streamUrl = LiveTvUrls.resolveMedia(rawUrl, baseUrl) ?: return null

        val tvgId = attributes["tvg-id"].orEmpty().trim().take(MAX_EPG_ID_LENGTH)
        val groupName = attributes["group-title"].orEmpty().trim().ifEmpty { group.trim() }
            .take(MAX_GROUP_LENGTH)
        val logo = LiveTvUrls.resolveHttp(attributes["tvg-logo"].orEmpty(), baseUrl)
        val headers = LinkedHashMap<String, String>(optionHeaders).apply { putAll(suffixHeaders) }
        val epgId = tvgId.takeIf { it.isNotEmpty() }

        // Identity is derived from stable provider facts only, never from playlist position,
        // so reordering a provider's list keeps channel IDs (and stored EPG/prefs) valid.
        val identity = listOf("m3u", epgId.orEmpty(), name, streamUrl).joinToString("\u0000")
        val id = LiveTvUrls.channelId(sourceId = sourceId, identity = identity)
        if (!seenIds.add(id)) return null

        return LiveTvChannel(
            id = id,
            sourceId = sourceId,
            name = name,
            streamUrl = streamUrl,
            group = groupName,
            logoUrl = logo,
            epgId = epgId,
            headers = headers,
        )
    }

    /**
     * Splits `#EXTINF` payload into duration, quoted attributes and the comma-separated
     * title. Corner case that matters in practice: attributes may be quoted with `"` or `'`,
     * and an unquoted value ends at whitespace or the comma, never by swallowing the next
     * `key=value` pair into the value.
     */
    private fun parseExtInf(payload: String): PendingEntry? {
        var index = 0
        while (index < payload.length) {
            val char = payload[index]
            if (char == ' ' || char == '\t' || char == ',') break
            index++
        }
        // Only a numeric duration may be skipped; `#EXTINF:tvg-id=...` providers exist.
        val durationToken = payload.substring(0, index)
        if (durationToken.any { !(it.isDigit() || it == '.' || it == '-' || it == '+') }) {
            index = 0
        }
        val attributes = LinkedHashMap<String, String>()
        val titleStart = scanAttributes(payload, index, attributes)
        val title = if (titleStart >= 0) payload.substring(titleStart).trim() else ""
        if (attributes.isEmpty() && title.isEmpty()) return null
        return PendingEntry(title = title, attributes = attributes)
    }

    /**
     * Parses `key="value"`, `key='value'` and `key=value` pairs starting at [start].
     * Returns the index just after the title-separating comma, or -1 when no comma remains.
     */
    private fun scanAttributes(
        text: String,
        start: Int,
        attributes: MutableMap<String, String>,
    ): Int {
        var index = start
        while (index < text.length) {
            while (index < text.length && text[index].isWhitespace()) index++
            if (index >= text.length) return -1
            if (text[index] == ',') return index + 1

            val keyStart = index
            while (index < text.length) {
                val char = text[index]
                if (char == '=' || char == ',' || char.isWhitespace()) break
                index++
            }
            val key = text.substring(keyStart, index).lowercase()
            while (index < text.length && text[index].isWhitespace()) index++
            if (index >= text.length || text[index] != '=') {
                // Bare token (for example the duration marker) with no attributes.
                if (index < text.length && text[index] == ',') return index + 1
                continue
            }
            index++
            while (index < text.length && text[index].isWhitespace()) index++

            val value: String
            if (index < text.length && (text[index] == '"' || text[index] == '\'')) {
                val quote = text[index]
                index++
                val valueStart = index
                while (index < text.length && text[index] != quote) index++
                value = text.substring(valueStart, index)
                if (index < text.length) index++
            } else {
                val valueStart = index
                while (index < text.length && !text[index].isWhitespace() && text[index] != ',') index++
                value = text.substring(valueStart, index)
            }
            if (key.isNotEmpty() && !attributes.containsKey(key)) attributes[key] = value
        }
        return -1
    }

    private val EPG_ATTRIBUTE =
        Regex("(?i)(?:x-tvg-url|url-tvg|tvg-url)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s]+))")

    /** `#EXTM3U x-tvg-url="..." url-tvg="..."`; EPG links are resolved and deduplicated. */
    private fun parseEpgUrls(payload: String, baseUrl: String?, into: MutableSet<String>) {
        if (payload.isBlank()) return
        for (match in EPG_ATTRIBUTE.findAll(payload)) {
            val declared = match.groups.drop(1)
                .mapNotNull { it?.value }
                .firstOrNull { it.isNotEmpty() }
                ?: continue
            for (candidate in declared.split(',')) {
                if (into.size >= MAX_EPG_URLS) return
                val resolved = LiveTvUrls.resolveHttp(candidate.trim(), baseUrl) ?: continue
                into.add(resolved)
            }
        }
    }

    private fun addVlcOption(option: String, into: MutableMap<String, String>) {
        if (into.size >= MAX_HEADERS) return
        val separator = option.indexOf('=')
        if (separator <= 0) return
        val key = option.substring(0, separator).trim().lowercase()
        val headerName = VLC_OPTION_HEADERS[key] ?: return
        val value = sanitizeHeaderValue(decodePercent(option.substring(separator + 1))) ?: return
        into[headerName] = value
    }

    /** Splits the `stream|Header=Value&Header=Value` convention; keeps URL untouched otherwise. */
    private fun splitUrlHeaders(value: String): Pair<String, Map<String, String>> {
        val trimmed = value.trim()
        val pipe = trimmed.indexOf('|')
        if (pipe <= 0) return trimmed to emptyMap()
        val url = trimmed.substring(0, pipe).trim()
        val suffix = trimmed.substring(pipe + 1)
        if (url.isEmpty() || suffix.isEmpty()) return trimmed to emptyMap()

        val headers = LinkedHashMap<String, String>()
        for (pair in suffix.split('&')) {
            if (headers.size >= MAX_HEADERS) break
            val separator = pair.indexOf('=')
            if (separator <= 0) continue
            val name = pair.substring(0, separator).trim()
            val headerName = HEADER_NAMES[name.lowercase()] ?: continue
            val headerValue = sanitizeHeaderValue(decodePercent(pair.substring(separator + 1))) ?: continue
            headers[headerName] = headerValue
        }
        return url to headers
    }

    private fun sanitizeHeaderValue(raw: String): String? {
        val value = raw.trim()
        if (value.isEmpty() || value.length > MAX_HEADER_VALUE_LENGTH) return null
        if (value.any { it == '\r' || it == '\n' || it.isISOControl() }) return null
        return value
    }

    /** Percent-decodes `%XX` for header suffix values; `+` stays literal. */
    private fun decodePercent(value: String): String {
        if (!value.contains('%')) return value
        val bytes = java.io.ByteArrayOutputStream(value.length)
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (char == '%' && index + 2 < value.length) {
                val high = hexDigit(value[index + 1])
                val low = hexDigit(value[index + 2])
                if (high >= 0 && low >= 0) {
                    bytes.write((high shl 4) or low)
                    index += 3
                    continue
                }
            }
            val codePoint = value.codePointAt(index)
            bytes.write(String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8))
            index += Character.charCount(codePoint)
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }

    private fun hexDigit(char: Char): Int = when (char) {
        in '0'..'9' -> char - '0'
        in 'a'..'f' -> char - 'a' + 10
        in 'A'..'F' -> char - 'A' + 10
        else -> -1
    }

    private fun splitLines(text: String): Sequence<String> = sequence {
        var start = 0
        var index = 0
        while (index < text.length) {
            val char = text[index]
            if (char == '\n' || char == '\r') {
                yield(text.substring(start, index))
                if (char == '\r' && index + 1 < text.length && text[index + 1] == '\n') index++
                start = index + 1
            }
            index++
        }
        if (start < text.length) yield(text.substring(start))
    }

    /** `#EXTVLCOPT:http-user-agent=...` names accepted by the player/loader. */
    private val VLC_OPTION_HEADERS = mapOf(
        "http-user-agent" to "User-Agent",
        "http-referrer" to "Referer",
        "http-referer" to "Referer",
        "http-origin" to "Origin",
        "http-cookie" to "Cookie",
    )

    private val HEADER_NAMES = mapOf(
        "user-agent" to "User-Agent",
        "referer" to "Referer",
        "referrer" to "Referer",
        "origin" to "Origin",
        "cookie" to "Cookie",
    )
}
