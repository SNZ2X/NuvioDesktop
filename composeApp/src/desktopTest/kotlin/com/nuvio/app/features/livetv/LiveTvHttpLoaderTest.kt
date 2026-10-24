package com.nuvio.app.features.livetv

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiveTvHttpLoaderTest {
    private fun client(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): HttpClient = HttpClient(MockEngine { request -> handler(request) }) {
        followRedirects = false
        expectSuccess = false
    }

    private fun gzip(text: String): ByteArray {
        val sink = ByteArrayOutputStream()
        GZIPOutputStream(sink).use { it.write(text.encodeToByteArray()) }
        return sink.toByteArray()
    }

    @Test
    fun fetchReturnsBodyAndFinalLocation() = runTest {
        val http = client {
            respond("hello playlist", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "audio/mpegurl"))
        }
        val document = LiveTvHttpLoader(http).load("https://panel.example/list.m3u", LiveTvLimits.PLAYLIST_BYTES)

        assertEquals("hello playlist", document.bytes.decodeToString())
        assertEquals("https://panel.example/list.m3u", document.location)
    }

    @Test
    fun gzipBodyIsDecodedByMagicBytes() = runTest {
        val http = client {
            respond(
                ByteReadChannel(gzip("#EXTM3U\nchannel")),
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentEncoding, "gzip"),
            )
        }

        val document = LiveTvHttpLoader(http).load("https://panel.example/epg.xml.gz", LiveTvLimits.XMLTV_BYTES)

        assertEquals("#EXTM3U\nchannel", document.bytes.decodeToString())
    }

    @Test
    fun boundedCompressedAndDecodedSizesAreEnforced() = runTest {
        val compressedTooLarge = client { respond(ByteReadChannel(ByteArray(200) { 'a'.code.toByte() })) }
        assertFailsWith<LiveTvLoadException> {
            LiveTvHttpLoader(compressedTooLarge).load("https://panel.example/list.m3u", 100)
        }.also { assertEquals(LIVE_TV_TOO_LARGE, it.message) }

        val bomb = gzip("a".repeat(200_000))
        val decodedTooLarge = client { respond(ByteReadChannel(bomb)) }
        assertFailsWith<LiveTvLoadException> {
            LiveTvHttpLoader(decodedTooLarge).load("https://panel.example/epg.xml.gz", 1024)
        }.also { assertEquals(LIVE_TV_TOO_LARGE, it.message) }
    }

    @Test
    fun redirectsAreFollowedAndCapped() = runTest {
        val http = client { request ->
            val hop = request.url.encodedPath.removePrefix("/hop").toIntOrNull()
            if (hop == null) {
                respond("done", HttpStatusCode.OK)
            } else {
                respond(
                    ByteReadChannel(ByteArray(0)),
                    HttpStatusCode.Found,
                    headersOf(HttpHeaders.Location, "/hop${hop + 1}"),
                )
            }
        }
        // Five redirects are allowed, the sixth is refused.
        assertFailsWith<LiveTvLoadException> {
            LiveTvHttpLoader(http).load("https://panel.example/hop9", 1024)
        }.also { assertEquals(LIVE_TV_TOO_MANY_REDIRECTS, it.message) }

        var hops = 0
        val withinCap = client { request ->
            if (request.url.encodedPath == "/start") {
                hops += 1
                respond(
                    ByteReadChannel(ByteArray(0)),
                    HttpStatusCode.MovedPermanently,
                    headersOf(HttpHeaders.Location, "https://mirror.example/list.m3u"),
                )
            } else {
                respond("body", HttpStatusCode.OK)
            }
        }
        val document = LiveTvHttpLoader(withinCap).load("https://panel.example/start", 1024)
        assertEquals("https://mirror.example/list.m3u", document.location)
        assertEquals(1, hops)
    }

    @Test
    fun redirectToLocalFileIsRefusedEvenWhenFilesAreAllowed() = runTest {
        val http = client {
            respond(
                ByteReadChannel(ByteArray(0)),
                HttpStatusCode.Found,
                headersOf(HttpHeaders.Location, "file:///etc/passwd"),
            )
        }

        assertFailsWith<LiveTvLoadException> {
            LiveTvHttpLoader(http).load("https://panel.example/list.m3u", 1024, allowLocalFiles = true)
        }.also { assertEquals(LIVE_TV_SCHEME_UNSUPPORTED, it.message) }
    }

    @Test
    fun plainLocationWithoutLocalPermissionIsRefused() = runTest {
        val loader = LiveTvHttpLoader(client { error("no request expected") })

        assertFailsWith<LiveTvLoadException> {
            loader.load("/tmp/playlist.m3u", LiveTvLimits.PLAYLIST_BYTES)
        }.also { assertEquals(LIVE_TV_LOCAL_NOT_ALLOWED, it.message) }
    }

    @Test
    fun explicitLocalFilesAreReadWhenAllowedAndGzipIsDecoded() = runTest {
        val plain = Files.createTempFile("nuvio-live", ".m3u")
        val gz = Files.createTempFile("nuvio-live", ".xml.gz")
        try {
            Files.write(plain, "#EXTM3U\nlocal".encodeToByteArray())
            Files.write(gz, gzip("<tv>guide</tv>"))

            val loader = LiveTvHttpLoader(client { error("no request expected") })
            val fromPath = loader.load(plain.toString(), LiveTvLimits.PLAYLIST_BYTES, allowLocalFiles = true)
            assertEquals("#EXTM3U\nlocal", fromPath.bytes.decodeToString())
            assertEquals(plain.toAbsolutePath().normalize().toString(), fromPath.location)

            val fromUri = loader.load("file://${gz.toAbsolutePath()}", LiveTvLimits.XMLTV_BYTES, allowLocalFiles = true)
            assertEquals("<tv>guide</tv>", fromUri.bytes.decodeToString())
        } finally {
            Files.deleteIfExists(plain)
            Files.deleteIfExists(gz)
        }
    }

    @Test
    fun missingLocalFileReportsAFixedMessage() = runTest {
        val loader = LiveTvHttpLoader(client { error("no request expected") })

        assertFailsWith<LiveTvLoadException> {
            loader.load("/definitely/not/here/list.m3u", 1024, allowLocalFiles = true)
        }.also { assertEquals(LIVE_TV_LOCAL_MISSING, it.message) }
    }

    @Test
    fun httpFailuresDoNotLeakCredentialsOrUrls() = runTest {
        val secretUrl = "https://panel.example/get.php?username=alice&password=SuperSecret"
        val http = client { respond("denied", HttpStatusCode.Forbidden) }

        val failure = assertFailsWith<LiveTvLoadException> {
            LiveTvHttpLoader(http).load(secretUrl, LiveTvLimits.PLAYLIST_BYTES)
        }

        assertEquals("HTTP 403 response", failure.message)
        assertFalse(failure.message.orEmpty().contains("SuperSecret"))
        assertFalse(failure.message.orEmpty().contains("alice"))
        assertFalse(failure.message.orEmpty().contains("panel.example"))
    }

    @Test
    fun redirectWithoutLocationAndTransportFailuresAreFixedMessages() = runTest {
        val noLocation = client { respond(ByteReadChannel(ByteArray(0)), HttpStatusCode.Found) }
        assertFailsWith<LiveTvLoadException> {
            LiveTvHttpLoader(noLocation).load("https://panel.example/x", 1024)
        }.also { assertFalse(it.message.orEmpty().contains("panel.example")) }

        val broken = client { throw IllegalStateException("boom on https://panel.example/secret") }
        assertFailsWith<LiveTvLoadException> {
            LiveTvHttpLoader(broken).load("https://panel.example/x", 1024)
        }.also {
            assertEquals(LIVE_TV_HTTP_FAILED, it.message)
            assertFalse(it.message.orEmpty().contains("secret"))
        }
    }

    @Test
    fun nonHttpSchemesAreRefused() = runTest {
        val loader = LiveTvHttpLoader(client { error("no request expected") })

        assertFailsWith<LiveTvLoadException> {
            loader.load("ftp://panel.example/list.m3u", 1024, allowLocalFiles = true)
        }.also { assertEquals(LIVE_TV_SCHEME_UNSUPPORTED, it.message) }
    }

    @Test
    fun bytesAreExactAndIndependentOfChunking() = runTest {
        val payload = ByteArray(70_000) { (it % 251).toByte() }
        val http = client { respond(ByteReadChannel(payload), HttpStatusCode.OK) }

        val document = LiveTvHttpLoader(http).load("https://panel.example/big.bin", LiveTvLimits.PLAYLIST_BYTES)
        assertContentEquals(payload, document.bytes)
        assertTrue(document.bytes.size <= LiveTvLimits.PLAYLIST_BYTES)
    }
}
