package com.nuvio.app.features.livetv

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class XtreamClientTest {
    private val source = LiveTvSource(
        id = "source-xtream",
        name = "Panel",
        kind = LiveTvSourceKind.XTREAM,
        location = "https://panel.example:8443/prefix/",
        username = "al ice",
        password = "p@ss/w+rd",
    )

    private class Panel {
        val requests = mutableListOf<HttpRequestData>()

        var auth: String = """{"user_info":{"auth":1,"status":"Active"}}"""
        var categories: String = """[{"category_id":"1","category_name":"News"}]"""
        var streams: String =
            """[
              {"stream_id":10,"name":"News HD","category_id":"1","stream_icon":"https://img.example/10.png","epg_channel_id":"news.uk"},
              {"stream_id":"11","name":"Sports","category_id":2,"stream_icon":null,"epg_channel_id":null},
              {"name":"no id"},
              {"stream_id":12},
              {"stream_id":13,"name":"Direct","category_id":"1","direct_source":"https://cdn.example/13.m3u8"},
              {"stream_id":14,"name":"Unsafe direct","direct_source":"file:///etc/passwd"}
            ]"""
        var authStatus: HttpStatusCode = HttpStatusCode.OK
        var failOnStreams: Boolean = false

        fun client(): HttpClient {
            val engine = MockEngine { request ->
                requests += request
                val action = request.url.parameters["action"]
                when {
                    failOnStreams && action == "get_live_streams" ->
                        respond("nope", HttpStatusCode.InternalServerError)

                    action == "get_live_categories" -> respondJson(categories)
                    action == "get_live_streams" -> respondJson(streams)
                    else -> respondJson(auth, authStatus)
                }
            }
            return HttpClient(engine) {
                followRedirects = false
                expectSuccess = false
            }
        }

        private fun io.ktor.client.engine.mock.MockRequestHandleScope.respondJson(
            body: String,
            status: HttpStatusCode = HttpStatusCode.OK,
        ) = respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
    }

    private fun clientFor(panel: Panel) = XtreamClient(LiveTvHttpLoader(panel.client()))

    @Test
    fun requestsAuthCategoriesAndLiveStreamsWithEncodedCredentials() = runTest {
        val panel = Panel()
        val content = clientFor(panel).load(source)

        assertEquals(3, panel.requests.size)
        assertNull(panel.requests[0].url.parameters["action"])
        assertEquals(
            listOf("get_live_categories", "get_live_streams"),
            panel.requests.drop(1).map { it.url.parameters["action"] },
        )

        panel.requests.forEach { request ->
            assertEquals("panel.example", request.url.host)
            assertEquals(8443, request.url.port)
            assertEquals("/prefix/player_api.php", request.url.encodedPath)
            assertEquals("al ice", request.url.parameters["username"])
            assertEquals("p@ss/w+rd", request.url.parameters["password"])
        }

        assertEquals(4, content.channels.size)
        assertEquals("https://panel.example:8443/prefix/xmltv.php?username=al%20ice&password=p%40ss%2Fw%2Brd", content.epgUrls.single())
    }

    @Test
    fun mapsNamesGroupsLogosEpgIdsAndConstructsTsStreams() = runTest {
        val panel = Panel()
        val content = clientFor(panel).load(source)
        val channels = content.channels.associateBy { it.name }

        val news = channels.getValue("News HD")
        assertEquals("source-xtream", news.sourceId)
        assertEquals("https://panel.example:8443/prefix/live/al%20ice/p%40ss%2Fw%2Brd/10.ts", news.streamUrl)
        assertEquals("News", news.group)
        assertEquals("https://img.example/10.png", news.logoUrl)
        assertEquals("news.uk", news.epgId)
        assertTrue(news.id.isNotBlank())
        assertFalse(news.id.contains("http"))

        val sports = channels.getValue("Sports")
        assertEquals("", sports.group)
        assertNull(sports.logoUrl)
        assertNull(sports.epgId)
        assertTrue(sports.streamUrl.endsWith("/11.ts"))
    }

    @Test
    fun repeatedStreamsAcrossCategoriesHaveOneStableBrowseIdentity() = runTest {
        val panel = Panel().apply {
            categories = """[{"category_id":"1","category_name":"News"},{"category_id":"2","category_name":"All"}]"""
            streams = """[
                {"stream_id":42,"name":"News HD","category_id":"1"},
                {"stream_id":"42","name":"News HD","category_id":"2"}
            ]"""
        }

        val channel = clientFor(panel).load(source).channels.single()
        assertEquals("News", channel.group, "the first category wins consistently")
        panel.streams = """[{"stream_id":42,"name":"News HD","category_id":"1"}]"""
        assertEquals(channel.id, clientFor(panel).load(source).channels.single().id)
    }

    @Test
    fun compressedCatalogAbovePlaylistLimitStillImportsChannels() = runTest {
        // Large panels can return >16 MiB of JSON even when their gzip transfer is small.
        val catalog = "[" + " ".repeat(LiveTvLimits.PLAYLIST_BYTES) +
            """{"stream_id":7,"name":"Large panel channel"}]"""
        val gzip = ByteArrayOutputStream().also { output ->
            GZIPOutputStream(output).use { it.write(catalog.encodeToByteArray()) }
        }.toByteArray()
        val http = HttpClient(MockEngine { request ->
            when (request.url.parameters["action"]) {
                "get_live_streams" -> respond(
                    ByteReadChannel(gzip),
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentEncoding, "gzip"),
                )
                "get_live_categories" -> respond("[]")
                else -> respond("""{"user_info":{"auth":1,"status":"Active"}}""")
            }
        }) { followRedirects = false }
        try {
            val channel = XtreamClient(LiveTvHttpLoader(http)).load(source).channels.single()
            assertEquals("Large panel channel", channel.name)
        } finally {
            http.close()
        }
    }

    @Test
    fun directSourceIsOnlyUsedForSafeHttpUrls() = runTest {
        val channels = clientFor(Panel()).load(source).channels.associateBy { it.name }

        assertEquals("https://cdn.example/13.m3u8", channels.getValue("Direct").streamUrl)
        assertEquals(
            "https://panel.example:8443/prefix/live/al%20ice/p%40ss%2Fw%2Brd/14.ts",
            channels.getValue("Unsafe direct").streamUrl,
        )
    }

    @Test
    fun formatFallsBackToTsAndStripsDotPrefix() = runTest {
        val panel = Panel()
        panel.streams = """[
            {"stream_id":1,"name":"M3u","container_extension":".M3U8"},
            {"stream_id":2,"name":"Odd","container_extension":"m3u 8"},
            {"stream_id":3,"name":"Missing"}
        ]"""
        val channels = clientFor(panel).load(source).channels.associateBy { it.name }

        assertTrue(channels.getValue("M3u").streamUrl.endsWith("/1.m3u8"))
        assertTrue(channels.getValue("Odd").streamUrl.endsWith("/2.ts"))
        assertTrue(channels.getValue("Missing").streamUrl.endsWith("/3.ts"))
    }

    @Test
    fun rejectedAccountFailsWithAFixedCredentialFreeMessage() = runTest {
        val panel = Panel()
        panel.auth = """{"user_info":{"auth":0,"status":"Expired"}}"""

        val failure = assertFailsWith<LiveTvLoadException> { clientFor(panel).load(source) }
        assertEquals(XTREAM_AUTH_FAILED, failure.message)
        assertFalse(failure.message.orEmpty().contains("al ice"))
        assertFalse(failure.message.orEmpty().contains("p@ss"))
        assertEquals(1, panel.requests.size)
    }

    @Test
    fun authFalseRejectsEvenWhenStatusIsActive() = runTest {
        val panel = Panel()
        panel.auth = """{"user_info":{"auth":0,"status":"Active"}}"""

        assertFailsWith<LiveTvLoadException> { clientFor(panel).load(source) }
            .also { assertEquals(XTREAM_AUTH_FAILED, it.message) }
        assertEquals(1, panel.requests.size)
    }

    @Test
    fun activeAuthWithInactiveStatusRejects() = runTest {
        val panel = Panel()
        panel.auth = """{"user_info":{"auth":1,"status":"Expired"}}"""

        assertFailsWith<LiveTvLoadException> { clientFor(panel).load(source) }
            .also { assertEquals(XTREAM_AUTH_FAILED, it.message) }
        assertEquals(1, panel.requests.size)
    }

    @Test
    fun statusIsAFallbackOnlyWhenAuthIsAbsent() = runTest {
        val panel = Panel()
        panel.auth = """{"user_info":{"status":"active"}}"""
        assertEquals(4, clientFor(panel).load(source).channels.size)

        // auth without a status field is accepted on its own.
        val noStatus = Panel()
        noStatus.auth = """{"user_info":{"auth":true}}"""
        assertEquals(4, clientFor(noStatus).load(source).channels.size)
    }

    @Test
    fun missingUserInfoIsRejected() = runTest {
        val panel = Panel()
        panel.auth = """{"user_info":null}"""

        assertFailsWith<LiveTvLoadException> { clientFor(panel).load(source) }
            .also { assertEquals(XTREAM_AUTH_FAILED, it.message) }
    }

    @Test
    fun invalidJsonAndHttpFailuresAreFixedMessages() = runTest {
        val badJson = Panel()
        badJson.categories = "not json"
        assertFailsWith<LiveTvLoadException> { clientFor(badJson).load(source) }
            .also { assertEquals(XTREAM_RESPONSE_INVALID, it.message) }

        val serverError = Panel().apply { failOnStreams = true }
        assertFailsWith<LiveTvLoadException> { clientFor(serverError).load(source) }
            .also {
                assertTrue(it.message.orEmpty().startsWith("HTTP 500"))
                assertFalse(it.message.orEmpty().contains("al ice"))
            }
    }

    @Test
    fun ipv6HostBracketsAreNormalizedOnce() = runTest {
        val panel = Panel()
        // java.net.URI reports IPv6 literals already bracketed; the base must not double them.
        val content = clientFor(panel).load(source.copy(location = "http://[::1]:8080/panel"))

        assertEquals(8080, panel.requests.first().url.port)
        assertEquals("/panel/player_api.php", panel.requests.first().url.encodedPath)
        assertFalse(panel.requests.first().url.host.contains("[["))
        assertFalse(panel.requests.first().url.host.contains("]]"))
        assertEquals(
            "http://[::1]:8080/panel/xmltv.php?username=al%20ice&password=p%40ss%2Fw%2Brd",
            content.epgUrls.single(),
        )
        assertTrue(content.channels.first { it.name == "News HD" }.streamUrl.startsWith("http://[::1]:8080/panel/live/"))
    }

    @Test
    fun invalidHostIsRejectedBeforeAnyRequest() = runTest {
        val panel = Panel()
        val failure = assertFailsWith<LiveTvLoadException> {
            clientFor(panel).load(source.copy(location = "panel.example:8080"))
        }
        assertEquals(XTREAM_SOURCE_INVALID, failure.message)
        assertTrue(panel.requests.isEmpty())
    }

    @Test
    fun discoveredXmltvUrlIsAlwaysReported() = runTest {
        val panel = Panel()
        val content = clientFor(panel).load(source.copy(epgUrl = "https://epg.example/guide.xml"))
        assertEquals(
            "https://panel.example:8443/prefix/xmltv.php?username=al%20ice&password=p%40ss%2Fw%2Brd",
            content.epgUrls.single(),
        )
    }
}
