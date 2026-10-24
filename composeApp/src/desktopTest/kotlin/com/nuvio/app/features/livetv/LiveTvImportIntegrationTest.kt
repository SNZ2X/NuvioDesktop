package com.nuvio.app.features.livetv

import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Collections
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Exercises the real CIO transport, parsers, encrypted store and repository together. */
class LiveTvImportIntegrationTest {
    @Test
    fun importsRedirectedM3uGzipGuideAndXtreamThenRestoresLocalSources() = runBlocking {
        val root = Files.createTempDirectory("nuvio-iptv-import")
        val queries = Collections.synchronizedList(mutableListOf<String>())
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val base = "http://127.0.0.1:${server.address.port}"
        val formatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z").withZone(ZoneOffset.UTC)
        val now = System.currentTimeMillis()
        fun time(offset: Long) = formatter.format(Instant.ofEpochMilli(now + offset))
        val guide = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE tv SYSTEM "$base/xmltv.dtd">
            <tv>
              <programme channel="news" start="${time(-600_000)}" stop="${time(1_200_000)}"><title>World Report</title></programme>
              <programme channel="cinema" start="${time(-600_000)}" stop="${time(3_600_000)}"><title>Evening Cinema</title></programme>
            </tv>
        """.trimIndent()
        val compressedGuide = ByteArrayOutputStream().also { output ->
            GZIPOutputStream(output).use { it.write(guide.encodeToByteArray()) }
        }.toByteArray()
        val playlist = """
            #EXTM3U url-tvg="../guide.xml.gz"
            #EXTINF:-1 tvg-id="news" group-title="News",News One
            #EXTVLCOPT:http-user-agent=DemoAgent
            ../live/news.m3u8
            #EXTINF:-1 tvg-id="cinema" group-title="Films",Cinema One
            ../live/cinema.m3u8
        """.trimIndent()
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            val query = exchange.requestURI.rawQuery.orEmpty()
            val text = when (path) {
                "/playlist" -> {
                    exchange.responseHeaders.add("Location", "/lists/main.m3u")
                    exchange.sendResponseHeaders(302, -1)
                    exchange.close()
                    return@createContext
                }
                "/lists/main.m3u" -> playlist
                "/panel/player_api.php" -> {
                    queries.add(query)
                    when {
                        query.contains("action=get_live_categories") -> """[{"category_id":3,"category_name":"News"}]"""
                        query.contains("action=get_live_streams") -> """[{"stream_id":7,"name":"Xtream News","category_id":3,"epg_channel_id":"news"}]"""
                        else -> """{"user_info":{"auth":1,"status":"Active"}}"""
                    }
                }
                "/panel/xmltv.php" -> guide
                "/guide.xml.gz" -> null
                else -> {
                    exchange.sendResponseHeaders(404, -1)
                    exchange.close()
                    return@createContext
                }
            }
            val body = text?.encodeToByteArray() ?: compressedGuide
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        val client = HttpClient(CIO) { followRedirects = false }
        try {
            withTimeout(30_000) {
                val store = EncryptedLiveTvSourceStore(1, root)
                val loader = LiveTvHttpLoader(client)
                val repository = LiveTvRepository(store, loader)
                repository.saveSource(LiveTvSource("playlist", "Playlist", LiveTvSourceKind.M3U_URL, "$base/playlist"))
                repository.saveSource(
                    LiveTvSource("xtream", "Panel", LiveTvSourceKind.XTREAM, "$base/panel", "user name", "pass&word"),
                )

                val state = repository.uiState.value
                assertEquals(3, state.channels.size)
                assertTrue(state.sourceErrors.isEmpty(), "The local provider fixtures must import without errors")
                val news = state.channels.filter { it.epgId == "news" }
                assertEquals(2, news.map { it.id }.distinct().size)
                news.forEach { assertEquals("World Report", state.programmes[it.id]?.firstOrNull()?.title) }
                assertEquals("DemoAgent", state.channels.single { it.name == "News One" }.headers["User-Agent"])
                assertTrue(queries.isNotEmpty())
                queries.forEach { query ->
                    val decoded = URLDecoder.decode(query, Charsets.UTF_8)
                    assertTrue(decoded.contains("username=user name"))
                    assertTrue(decoded.contains("password=pass&word"))
                    assertTrue(query.contains("password=pass%26word"))
                }

                val restored = LiveTvRepository(EncryptedLiveTvSourceStore(1, root), loader)
                restored.load()
                assertEquals(2, restored.uiState.value.sources.size)
                assertEquals(3, restored.uiState.value.channels.size)
                restored.setSourceEnabled("playlist", false)
                assertTrue(restored.uiState.value.channels.all { it.sourceId == "xtream" })
                restored.removeSource("xtream")
                assertTrue(restored.uiState.value.channels.isEmpty())
            }
        } finally {
            client.close()
            server.stop(0)
            root.toFile().deleteRecursively()
        }
    }
}
