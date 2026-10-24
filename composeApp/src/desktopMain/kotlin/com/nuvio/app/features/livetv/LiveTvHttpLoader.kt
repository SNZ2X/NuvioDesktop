package com.nuvio.app.features.livetv

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.zip.GZIPInputStream
import kotlin.time.Duration.Companion.seconds

/** Source limits shared by the network, repository and parser slices. */
internal object LiveTvLimits {
    const val PLAYLIST_BYTES: Int = 16 * 1024 * 1024
    const val XMLTV_BYTES: Int = 64 * 1024 * 1024
    // Large Xtream catalogs can exceed the smaller channel-playlist budget.
    const val JSON_BYTES: Int = 64 * 1024 * 1024
}

/** Fixed, credential-free failure text. Never build a message from a source location. */
internal class LiveTvLoadException(message: String) : Exception(message)

internal const val LIVE_TV_HTTP_FAILED: String = "HTTP request failed"
internal const val LIVE_TV_HTTP_TIMEOUT: String = "HTTP request timed out"
internal const val LIVE_TV_TOO_LARGE: String = "Response exceeds the size limit"
internal const val LIVE_TV_LOCAL_NOT_ALLOWED: String = "Local files are not allowed"
internal const val LIVE_TV_LOCAL_MISSING: String = "Local file is unavailable"
internal const val LIVE_TV_LOCAL_UNREADABLE: String = "Local file could not be read"
internal const val LIVE_TV_SCHEME_UNSUPPORTED: String = "Only HTTP(S) sources are supported"
internal const val LIVE_TV_TOO_MANY_REDIRECTS: String = "Too many redirects"

private const val MAX_REDIRECTS = 5
private const val READ_BUFFER_BYTES = 16 * 1024

/** Whole-fetch budget, including every redirect hop. Per-request deadlines are also finite. */
private val FETCH_BUDGET = 180.seconds

/**
 * The single process-wide default client.
 *
 * Loaders created without an explicit client share this instance, so per-profile
 * repositories and `clearAll` never accumulate CIO engines or threads. It is never
 * closed: it lives for the process lifetime and holds no source credentials.
 * Injected clients stay owned by the caller.
 */
private val sharedDefaultClient: HttpClient by lazy { createDefaultClient() }

internal fun configuredDefaultClient(): HttpClient = sharedDefaultClient

private fun createDefaultClient(): HttpClient = HttpClient(CIO) {
    followRedirects = false
    expectSuccess = false
    install(HttpTimeout) {
        // Large XMLTV bodies are legitimate; connect/socket timeouts still bound stalls.
        requestTimeoutMillis = 120_000
        connectTimeoutMillis = 15_000
        socketTimeoutMillis = 30_000
    }
}

/**
 * Fetches playlists/EPG/Xtream JSON with a hard byte cap.
 *
 * Redirects are resolved here (never by the engine) so the count and final location are
 * known, a redirect can never downgrade to a non-HTTP scheme, and local files are only
 * read when the caller explicitly opted in. Both the compressed stream and its gzip
 * decoding are bounded; gzip is detected from the magic bytes.
 */
internal class LiveTvHttpLoader(
    private val client: HttpClient = configuredDefaultClient(),
) : LiveTvDocumentLoader {
    override suspend fun load(
        location: String,
        maxBytes: Int,
        allowLocalFiles: Boolean,
    ): LiveTvDocument {
        if (maxBytes <= 0) throw LiveTvLoadException(LIVE_TV_TOO_LARGE)
        val trimmed = location.trim()

        return when (schemeOf(trimmed)) {
            "http", "https" -> fetchRemote(trimmed, maxBytes)
            null, "file" -> {
                if (!allowLocalFiles) throw LiveTvLoadException(LIVE_TV_LOCAL_NOT_ALLOWED)
                readLocal(trimmed, maxBytes)
            }

            else -> throw LiveTvLoadException(LIVE_TV_SCHEME_UNSUPPORTED)
        }
    }

    private suspend fun fetchRemote(startUrl: String, maxBytes: Int): LiveTvDocument =
        withContext(Dispatchers.IO) {
            withTimeoutOrNull(FETCH_BUDGET) { fetchFollowingRedirects(startUrl, maxBytes) }
                ?: throw LiveTvLoadException(LIVE_TV_HTTP_TIMEOUT)
        }

    private suspend fun fetchFollowingRedirects(
        startUrl: String,
        maxBytes: Int,
    ): LiveTvDocument {
        var url = startUrl
        var redirects = 0
        while (true) {
            val next: String
            try {
                val outcome = client.prepareGet(url) {
                    header(HttpHeaders.Accept, "*/*")
                    header(HttpHeaders.AcceptEncoding, "gzip")
                }.execute { response ->
                    val status = response.status.value
                    if (status in 300..399) {
                        val target = response.headers[HttpHeaders.Location]
                        if (target.isNullOrBlank()) throw LiveTvLoadException("HTTP $status response")
                        FetchOutcome.Redirect(resolveRedirect(url, target))
                    } else {
                        if (status !in 200..299) throw LiveTvLoadException("HTTP $status response")
                        val raw = readBounded(response.bodyAsChannel(), maxBytes)
                        FetchOutcome.Body(
                            bytes = decodeGzip(raw, maxBytes),
                            location = url,
                        )
                    }
                }
                when (outcome) {
                    is FetchOutcome.Body -> return LiveTvDocument(outcome.bytes, outcome.location)
                    is FetchOutcome.Redirect -> next = outcome.target
                }
            } catch (failure: LiveTvLoadException) {
                throw failure
            } catch (timeout: HttpRequestTimeoutException) {
                throw LiveTvLoadException(LIVE_TV_HTTP_TIMEOUT)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (io: IOException) {
                throw LiveTvLoadException(LIVE_TV_HTTP_FAILED)
            } catch (_: Exception) {
                throw LiveTvLoadException(LIVE_TV_HTTP_FAILED)
            }

            redirects += 1
            if (redirects > MAX_REDIRECTS) throw LiveTvLoadException(LIVE_TV_TOO_MANY_REDIRECTS)
            url = next
        }
    }

    private fun readLocal(location: String, maxBytes: Int): LiveTvDocument {
        val path = localPath(location) ?: throw LiveTvLoadException(LIVE_TV_SCHEME_UNSUPPORTED)
        if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
            throw LiveTvLoadException(LIVE_TV_LOCAL_MISSING)
        }
        val raw = try {
            Files.newInputStream(path).use { readBounded(it, maxBytes) }
        } catch (failure: LiveTvLoadException) {
            throw failure
        } catch (_: IOException) {
            throw LiveTvLoadException(LIVE_TV_LOCAL_UNREADABLE)
        }
        val bytes = try {
            decodeGzip(raw, maxBytes)
        } catch (failure: LiveTvLoadException) {
            throw failure
        } catch (_: IOException) {
            throw LiveTvLoadException(LIVE_TV_LOCAL_UNREADABLE)
        }
        return LiveTvDocument(bytes, path.toAbsolutePath().normalize().toString())
    }
}

private sealed interface FetchOutcome {
    data class Body(val bytes: ByteArray, val location: String) : FetchOutcome
    data class Redirect(val target: String) : FetchOutcome
}

private fun schemeOf(location: String): String? {
    if (location.isEmpty()) return null
    // A Windows drive path is not a URI scheme.
    if (location.length >= 2 && location[1] == ':' && location[0].isLetter()) return null
    return try {
        URI(location).scheme?.lowercase()
    } catch (_: Exception) {
        null
    }
}

private fun resolveRedirect(current: String, target: String): String {
    val resolved = try {
        URI(current).resolve(target.trim()).toString()
    } catch (_: Exception) {
        throw LiveTvLoadException(LIVE_TV_SCHEME_UNSUPPORTED)
    }
    if (schemeOf(resolved) !in setOf("http", "https")) {
        throw LiveTvLoadException(LIVE_TV_SCHEME_UNSUPPORTED)
    }
    return resolved
}

private fun localPath(location: String): Path? {
    if (location.isEmpty()) return null
    if (location.length >= 2 && location[1] == ':' && location[0].isLetter()) {
        return runCatching { Paths.get(location) }.getOrNull()
    }
    return when {
        location.startsWith("file:", ignoreCase = true) -> {
            runCatching { Paths.get(URI(location)) }.getOrNull()
                // "file:///tmp/a b" is not a valid URI; fall back to the literal path.
                ?: runCatching { Paths.get(location.substring("file:".length)) }.getOrNull()
        }

        schemeOf(location) == null -> runCatching { Paths.get(location) }.getOrNull()
        else -> null
    }
}

/** Streams a channel into memory while refusing to exceed [maxBytes]. */
private suspend fun readBounded(channel: ByteReadChannel, maxBytes: Int): ByteArray {
    val sink = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
    val buffer = ByteArray(READ_BUFFER_BYTES)
    var total = 0
    while (true) {
        val read = channel.readAvailable(buffer, 0, buffer.size)
        if (read == -1) break
        if (read == 0) continue
        total += read
        if (total > maxBytes) throw LiveTvLoadException(LIVE_TV_TOO_LARGE)
        sink.write(buffer, 0, read)
    }
    return sink.toByteArray()
}

/** Same bound for explicit local files, which never materialise a whole oversized file. */
private fun readBounded(input: InputStream, maxBytes: Int): ByteArray {
    val sink = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
    val buffer = ByteArray(READ_BUFFER_BYTES)
    var total = 0
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        total += read
        if (total > maxBytes) throw LiveTvLoadException(LIVE_TV_TOO_LARGE)
        sink.write(buffer, 0, read)
    }
    return sink.toByteArray()
}

private fun isGzip(bytes: ByteArray): Boolean =
    bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()

/**
 * Decodes gzip payloads by magic bytes, so a header claiming gzip on an already-decoded
 * body is accepted as-is, and the decoded size is bounded.
 */
private fun decodeGzip(raw: ByteArray, maxBytes: Int): ByteArray {
    if (!isGzip(raw)) return raw
    val sink = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
    val buffer = ByteArray(READ_BUFFER_BYTES)
    var total = 0
    GZIPInputStream(ByteArrayInputStream(raw)).use { input ->
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) throw LiveTvLoadException(LIVE_TV_TOO_LARGE)
            sink.write(buffer, 0, read)
        }
    }
    return sink.toByteArray()
}
