package com.nuvio.app.features.livetv

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URI
import java.nio.charset.StandardCharsets

internal const val XTREAM_AUTH_FAILED: String = "Xtream account was rejected"
internal const val XTREAM_RESPONSE_INVALID: String = "Xtream response is invalid"
internal const val XTREAM_SOURCE_INVALID: String = "Xtream host is invalid"

/**
 * Loads live channels from an Xtream player_api panel.
 *
 * Requests run through the shared bounded [LiveTvDocumentLoader], so credentials never
 * appear in thrown messages. Only live categories/streams are ingested; VOD is ignored.
 * The panel's XMLTV endpoint is advertised as a discovered EPG location.
 */
internal class XtreamClient(
    private val loader: LiveTvDocumentLoader,
    private val maxBytes: Int = LiveTvLimits.JSON_BYTES,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun load(source: LiveTvSource): LiveTvSourceContent {
        val base = xtreamBase(source.location) ?: throw LiveTvLoadException(XTREAM_SOURCE_INVALID)
        if (source.username.isEmpty() || source.password.isEmpty()) {
            throw LiveTvLoadException(XTREAM_AUTH_FAILED)
        }

        val auth = fetchJson(
            base = base,
            username = source.username,
            password = source.password,
            action = null,
        )
        if (!isAuthenticated(auth)) throw LiveTvLoadException(XTREAM_AUTH_FAILED)

        val categories = fetchJson(base, source.username, source.password, "get_live_categories")
        val streams = fetchJson(base, source.username, source.password, "get_live_streams")
        if (streams !is JsonArray) throw LiveTvLoadException(XTREAM_RESPONSE_INVALID)

        val categoryNames = categoryNames(categories)
        val channels = streams.mapNotNull { element ->
            toChannel(source, base, element, categoryNames)
        }.distinctBy { it.id } // Panels may repeat a stream in multiple categories.
        return LiveTvSourceContent(
            channels = channels,
            // The panel's own guide is always advertised; a source's explicit EPG stays
            // separate configuration the repository may prefer.
            epgUrls = listOf(xmltvUrl(base, source.username, source.password)),
        )
    }

    private suspend fun fetchJson(
        base: String,
        username: String,
        password: String,
        action: String?,
    ): JsonElement {
        val url = playerApiUrl(base, username, password, action)
        val document = loader.load(url, maxBytes, allowLocalFiles = false)
        return try {
            json.parseToJsonElement(document.bytes.decodeToString())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            throw LiveTvLoadException(XTREAM_RESPONSE_INVALID)
        }
    }

    /**
     * `auth` is authoritative whenever the panel sends it: an explicit false value rejects
     * even with `status: Active`. A present status must itself be active, so `auth: 1` with
     * an expired account is rejected. Status alone is only a fallback when `auth` is absent.
     */
    private fun isAuthenticated(element: JsonElement): Boolean {
        val root = element as? JsonObject ?: return false
        val user = root["user_info"] as? JsonObject ?: return false
        if (user.truthy("auth") == false) return false
        val status = user.text("status")
        if (status != null) return status.equals("Active", ignoreCase = true)
        return user.truthy("auth") == true
    }

    private fun categoryNames(element: JsonElement): Map<String, String> {
        val array = element as? JsonArray ?: throw LiveTvLoadException(XTREAM_RESPONSE_INVALID)
        val names = LinkedHashMap<String, String>()
        array.forEach { entry ->
            val category = entry as? JsonObject ?: return@forEach
            val id = category.text("category_id") ?: return@forEach
            names[id] = category.text("category_name").orEmpty()
        }
        return names
    }

    private fun toChannel(
        source: LiveTvSource,
        base: String,
        element: JsonElement,
        categoryNames: Map<String, String>,
    ): LiveTvChannel? {
        val stream = element as? JsonObject ?: return null
        val streamId = stream.text("stream_id") ?: return null
        if (streamId.isBlank()) return null
        val name = stream.text("name")?.trim()?.takeIf { it.isNotEmpty() } ?: return null

        val categoryId = stream.text("category_id").orEmpty()
        val direct = stream.text("direct_source")
            ?.trim()
            ?.takeIf { isHttpUrl(it) }
            ?: directSourceFromElement(stream["direct_source"])
        val streamUrl = direct ?: liveStreamUrl(
            base = base,
            username = source.username,
            password = source.password,
            streamId = streamId,
            extension = streamFormat(stream),
        )

        return LiveTvChannel(
            id = LiveTvUrls.channelId(source.id, "xtream:$streamId"),
            sourceId = source.id,
            name = name,
            streamUrl = streamUrl,
            group = categoryNames[categoryId].orEmpty(),
            logoUrl = stream.text("stream_icon")?.trim()?.takeIf { isHttpUrl(it) },
            epgId = stream.text("epg_channel_id")?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    /** Some panels wrap a URL in a `{"url": "..."}` object; only HTTP(S) is accepted. */
    private fun directSourceFromElement(element: JsonElement?): String? {
        val objectValue = element as? JsonObject ?: return null
        return objectValue.text("url")?.trim()?.takeIf { isHttpUrl(it) }
    }

    private fun streamFormat(stream: JsonObject): String {
        val raw = stream.text("container_extension")
            ?: stream.text("format")
            ?: return DEFAULT_XTREAM_FORMAT
        val cleaned = raw.trim().trimStart('.').lowercase()
        return cleaned.takeIf { token -> token.isNotEmpty() && token.length <= 8 && token.all { it.isLetterOrDigit() } }
            ?: DEFAULT_XTREAM_FORMAT
    }
}

private const val DEFAULT_XTREAM_FORMAT = "ts"

/** Base origin plus the configured path prefix; never carries credentials of its own. */
private fun xtreamBase(location: String): String? {
    val uri = try {
        URI(location.trim())
    } catch (_: Exception) {
        return null
    }
    val scheme = uri.scheme?.lowercase() ?: return null
    if (scheme != "http" && scheme != "https") return null
    val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
    // java.net.URI already reports IPv6 literals bracketed; normalize once to avoid [[::1]].
    val bareHost = host.removePrefix("[").removeSuffix("]")
    if (bareHost.isEmpty()) return null
    val authority = if (bareHost.contains(':')) "[$bareHost]" else bareHost
    val port = if (uri.port in 1..65535) ":${uri.port}" else ""
    val path = uri.rawPath.orEmpty().trimEnd('/')
    return "$scheme://$authority$port$path"
}

private fun playerApiUrl(
    base: String,
    username: String,
    password: String,
    action: String?,
): String {
    val query = buildString {
        append("username=").append(encodeComponent(username))
        append("&password=").append(encodeComponent(password))
        if (action != null) append("&action=").append(encodeComponent(action))
    }
    return "$base/player_api.php?$query"
}

private fun xmltvUrl(base: String, username: String, password: String): String =
    "$base/xmltv.php?username=${encodeComponent(username)}&password=${encodeComponent(password)}"

private fun liveStreamUrl(
    base: String,
    username: String,
    password: String,
    streamId: String,
    extension: String,
): String = buildString {
    append(base)
    append("/live/")
    append(encodeComponent(username))
    append('/')
    append(encodeComponent(password))
    append('/')
    append(encodeComponent(streamId))
    append('.')
    append(extension)
}

/** RFC 3986 unreserved characters are kept; everything else is encoded exactly once. */
private fun encodeComponent(value: String): String {
    val bytes = value.toByteArray(StandardCharsets.UTF_8)
    val builder = StringBuilder(bytes.size)
    for (byte in bytes) {
        val code = byte.toInt() and 0xFF
        val char = code.toChar()
        if (char.isLetterOrDigit() && code < 0x80 || char in "-._~") {
            builder.append(char)
        } else {
            builder.append('%')
            builder.append(HEX[code ushr 4])
            builder.append(HEX[code and 0x0F])
        }
    }
    return builder.toString()
}

private val HEX = "0123456789ABCDEF".toCharArray()

private fun isHttpUrl(value: String): Boolean = try {
    val uri = URI(value)
    uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank()
} catch (_: Exception) {
    false
}

/** Reads a JSON scalar as text; numbers and booleans stay lossless, null is absent. */
private fun JsonObject.text(key: String): String? = scalarText(this[key])

private fun scalarText(element: JsonElement?): String? = when (element) {
    null, is JsonNull -> null
    is JsonPrimitive -> element.content.takeIf { it.isNotEmpty() }
    else -> null
}

private fun JsonObject.truthy(key: String): Boolean? {
    val element = this[key] as? JsonPrimitive ?: return null
    if (element is JsonNull) return null
    return when (element.content.lowercase()) {
        "1", "true", "active" -> true
        "0", "false", "disabled", "expired" -> false
        else -> null
    }
}
