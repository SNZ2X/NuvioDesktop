package com.nuvio.app.features.livetv

import java.net.URI
import java.security.MessageDigest

/**
 * Stream schemes this feature can open. RTSP/UDP/RTMP are not security risks by
 * themselves and mpv supports them, but Live TV here is an HTTP(S) HLS/TS tuner, so those
 * values are reported as unsupported rather than guessed at.
 */
private val MEDIA_SCHEMES = setOf("http", "https")

/** Fixed parser failure. Messages never contain document content, URLs or credentials. */
internal class LiveTvParseException(message: String) : Exception(message)

/**
 * URL normalization shared by every Live TV parser.
 *
 * Parser output is persisted and handed to the native player, so every accepted stream
 * URL is absolute and scheme-restricted. Relative references are resolved against the
 * playlist/EPG location; anything that does not become a safe HTTP(S) URL is dropped rather
 * than repaired into a plausible-looking value.
 *
 * User-info (Basic Auth embedded in the URL) is preserved: it is needed for playback and
 * the source configuration is encrypted at rest and redacted from logs/messages. It must
 * never be moved into [LiveTvChannel.id] or a label.
 */
internal object LiveTvUrls {
    private const val MAX_URL_LENGTH = 4096
    private val DEFAULT_PORTS = mapOf("http" to 80, "https" to 443)

    /**
     * Resolves a channel stream target. Non-HTTP(S) schemes (`rtsp:`, `udp:`, `file:`,
     * `data:`, `javascript:` …) resolve to null so a channel is never created with a stream
     * this feature cannot play.
     */
    fun resolveMedia(value: String, base: String? = null): String? {
        val resolved = resolveAbsolute(value, base) ?: return null
        val uri = parseUri(resolved) ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme !in MEDIA_SCHEMES) return null
        if (uri.host.isNullOrBlank()) return null
        return normalize(scheme, uri)
    }

    /** Resolves an HTTP(S) reference: playlists, logos and EPG locations. Same policy as media. */
    fun resolveHttp(value: String, base: String? = null): String? = resolveMedia(value, base)

    /** Stable, source-qualified channel identity. No raw URL or credential is published. */
    fun channelId(sourceId: String, identity: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(sourceId.toByteArray(Charsets.UTF_8))
        digest.update(byteArrayOf(0))
        digest.update(identity.toByteArray(Charsets.UTF_8))
        return "lt:" + hex(digest.digest(), 16)
    }

    private fun resolveAbsolute(value: String, base: String?): String? {
        val trimmed = value.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_URL_LENGTH) return null
        if (trimmed.any { it.isWhitespace() || it.isISOControl() }) return null

        if (parseUri(trimmed)?.scheme != null) return trimmed
        // Protocol-relative references inherit the base scheme.
        val parent = base?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val parentUri = parseUri(parent) ?: return null
        return try {
            parentUri.resolve(trimmed).toString()
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun parseUri(value: String): URI? = try {
        URI(value)
    } catch (_: Exception) {
        null
    }

    /**
     * Rebuilds the URL from parsed parts, keeping the raw user-info and query/fragment.
     * A port outside 1..65535 is rejected instead of being replaced by the scheme default.
     */
    private fun normalize(scheme: String, uri: URI): String? {
        val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
        val bareHost = host.removePrefix("[").removeSuffix("]").lowercase()
        if (bareHost.isEmpty()) return null
        val authorityHost = if (bareHost.contains(':')) "[$bareHost]" else bareHost

        val port = uri.port
        if (port != -1 && port !in 1..65535) return null
        val portPart = if (port != -1 && port != DEFAULT_PORTS[scheme]) ":$port" else ""
        val userInfo = uri.rawUserInfo?.takeIf { it.isNotEmpty() }?.let { "$it@" }.orEmpty()
        val path = uri.rawPath.orEmpty()
        val query = uri.rawQuery?.takeIf { it.isNotEmpty() }?.let { "?$it" }.orEmpty()
        val fragment = uri.rawFragment?.takeIf { it.isNotEmpty() }?.let { "#$it" }.orEmpty()
        val rebuilt = "$scheme://$userInfo$authorityHost$portPart$path$query$fragment"
        return rebuilt.takeIf { it.length <= MAX_URL_LENGTH }
    }

    private fun hex(bytes: ByteArray, length: Int): String {
        val builder = StringBuilder(length)
        for (index in 0 until length) {
            val value = bytes[index / 2].toInt() and 0xFF
            val nibble = if (index % 2 == 0) value ushr 4 else value and 0x0F
            builder.append("0123456789abcdef"[nibble])
        }
        return builder.toString()
    }
}
