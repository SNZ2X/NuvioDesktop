package com.nuvio.app.features.livetv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class M3uParserTest {
    @Test
    fun readsBomCrlfQuotedMetadataAndCommaTitles() {
        val playlist = buildString {
            append("\uFEFF#EXTM3U x-tvg-url=\"https://epg.example/guide.xml.gz,extra.xml\"\r\n")
            append("#EXTINF:-1 tvg-id=\"bbc1.uk\" tvg-name=\"BBC One\" tvg-logo=\"https://cdn.example/bbc.png\" group-title=\"News, UK\",BBC One HD, London\r\n")
            append("#EXTVLCOPT:http-user-agent=ProviderPlayer/1.0\r\n")
            append("#EXTVLCOPT:http-referrer=https://portal.example/\r\n")
            append("#EXTGRP:Sports\r\n")
            append("https://stream.example/bbc.m3u8|User-Agent=Suffix%2F2&Referer=https%3A%2F%2Fexample.com%2F\r\n")
            append("\r\n")
            append("#EXTINF:-1,Radio Only\r\n")
            append("https://stream.example/radio.ts\r\n")
        }

        val content = M3uParser.parse(
            playlist,
            sourceId = "source-1",
            baseUrl = "https://epg.example/list.m3u",
        )

        assertEquals(listOf("https://epg.example/guide.xml.gz", "https://epg.example/extra.xml"), content.epgUrls)
        assertEquals(2, content.channels.size)

        val channel = content.channels.first()
        assertEquals("BBC One HD, London", channel.name)
        assertEquals("bbc1.uk", channel.epgId)
        assertEquals("News, UK", channel.group)
        assertEquals("https://cdn.example/bbc.png", channel.logoUrl)
        // The URL-suffix header wins over EXTVLCOPT: it is the more specific declaration.
        assertEquals("Suffix/2", channel.headers["User-Agent"])
        assertEquals("https://example.com/", channel.headers["Referer"])
        assertEquals("source-1", channel.sourceId)

        val second = content.channels.last()
        assertEquals("Radio Only", second.name)
        assertEquals("", second.group)
        assertNull(second.epgId)
        assertTrue(second.headers.isEmpty())
    }

    @Test
    fun extgrpAndExtvlcoptApplyOnlyToTheirOwnEntry() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1 group-title="",One
            #EXTGRP:Group A
            #EXTVLCOPT:http-user-agent=UA-A
            https://example.com/one.ts
            #EXTINF:-1,Two
            https://example.com/two.ts
        """.trimIndent()

        val channels = M3uParser.parse(playlist, "s").channels

        assertEquals("Group A", channels[0].group)
        assertEquals("UA-A", channels[0].headers["User-Agent"])
        assertEquals("", channels[1].group)
        assertTrue(channels[1].headers.isEmpty())
    }

    @Test
    fun resolvesRelativeStreamsAgainstThePlaylistLocation() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1,Relative
            streams/one.m3u8
            #EXTINF:-1,Local file
            file:///etc/passwd
            #EXTINF:-1,Script
            javascript:alert(1)
        """.trimIndent()

        val withBase = M3uParser.parse(playlist, sourceId = "s", baseUrl = "https://host.example/lists/index.m3u")
        assertEquals(listOf("Relative"), withBase.channels.map { it.name })
        assertEquals("https://host.example/lists/streams/one.m3u8", withBase.channels.single().streamUrl)

        // Without a base there is nothing to resolve the relative entry against.
        assertTrue(M3uParser.parse(playlist, sourceId = "s").channels.isEmpty())
    }

    @Test
    fun idsStayStableWhenTheProviderReordersOrRepeatsEntries() {
        val first = """
            #EXTM3U
            #EXTINF:-1 tvg-id="a",Alpha
            https://example.com/a.m3u8
            #EXTINF:-1 tvg-id="b",Beta
            https://example.com/b.m3u8
            #EXTINF:-1 tvg-id="a",Alpha
            https://example.com/a.m3u8
        """.trimIndent()
        val reordered = """
            #EXTM3U
            #EXTINF:-1 tvg-id="b",Beta
            https://example.com/b.m3u8
            #EXTINF:-1 tvg-id="a",Alpha
            https://example.com/a.m3u8
        """.trimIndent()

        val forward = M3uParser.parse(first, "s").channels
        val backward = M3uParser.parse(reordered, "s").channels

        // The exact duplicate is dropped instead of receiving an occurrence-suffixed ID.
        assertEquals(2, forward.size)
        assertEquals(forward.associate { it.name to it.id }, backward.associate { it.name to it.id })

        val otherSource = M3uParser.parse(first, "other").channels
        assertTrue(forward.none { channel -> otherSource.any { it.id == channel.id } })
    }

    @Test
    fun nonPlaylistAndHlsManifestsFailWithFixedErrors() {
        val html = assertFailsWith<LiveTvParseException> {
            M3uParser.parse("<html><body>Login required</body></html>", "s")
        }
        assertEquals(LIVE_TV_PLAYLIST_INVALID, html.message)

        val media = assertFailsWith<LiveTvParseException> {
            M3uParser.parse(
                """
                    #EXTM3U
                    #EXT-X-VERSION:3
                    #EXT-X-TARGETDURATION:6
                    #EXTINF:6.0,
                    segment1.ts
                """.trimIndent(),
                "s",
            )
        }
        assertEquals(LIVE_TV_PLAYLIST_HLS, media.message)

        val master = assertFailsWith<LiveTvParseException> {
            M3uParser.parse(
                """
                    #EXTM3U
                    #EXT-X-STREAM-INF:BANDWIDTH=800000
                    low/index.m3u8
                """.trimIndent(),
                "s",
            )
        }
        assertEquals(LIVE_TV_PLAYLIST_HLS, master.message)
    }

    @Test
    fun validEmptyPlaylistIsNotAnError() {
        val content = M3uParser.parse("#EXTM3U\n", "s")
        assertTrue(content.channels.isEmpty())
        assertTrue(content.epgUrls.isEmpty())
    }

    @Test
    fun unquotedMetadataDoesNotSwallowLaterAttributes() {
        val content = M3uParser.parse(
            """
                #EXTM3U
                #EXTINF:-1 tvg-id=ident tvg-name=Short group-title=Kids,Display
                https://example.com/kids.ts
            """.trimIndent(),
            "s",
        )

        val channel = content.channels.single()
        assertEquals("ident", channel.epgId)
        assertEquals("Display", channel.name)
        assertEquals("Kids", channel.group)
    }

    @Test
    fun singleQuotedMetadataAndCommaInsideValueAreSupported() {
        val content = M3uParser.parse(
            """
                #EXTM3U
                #EXTINF:-1 tvg-id='x' tvg-name='A, B' group-title='Films, HD',Comma, Title
                https://example.com/film.ts
            """.trimIndent(),
            "s",
        )

        val channel = content.channels.single()
        assertEquals("x", channel.epgId)
        assertEquals("Comma, Title", channel.name)
        assertEquals("Films, HD", channel.group)
    }

    @Test
    fun headerValuesCannotInjectLines() {
        val content = M3uParser.parse(
            "#EXTM3U\n#EXTINF:-1,Bad\nhttps://example.com/a.ts|User-Agent=ok%0d%0aX-Evil=1&Cookie=%0a\n",
            "s",
        )
        assertTrue(content.channels.single().headers.isEmpty())

        val cookies = M3uParser.parse(
            "#EXTM3U\n#EXTINF:-1,Cookie\n#EXTVLCOPT:http-cookie=session=abc\nhttps://example.com/b.ts\n",
            "s",
        )
        assertEquals("session=abc", cookies.channels.single().headers["Cookie"])
    }

    @Test
    fun channelsWithoutUsableNameOrStreamAreSkipped() {
        val content = M3uParser.parse(
            """
                #EXTM3U
                #EXTINF:-1,
                https://example.com/unnamed.ts
                #EXTINF:-1 tvg-name="From Attribute"
                https://example.com/named.ts
                #EXTINF:-1,No URL
                #EXTINF:-1,Unsafe
                rtsp://example.com/live
            """.trimIndent(),
            "s",
        )

        assertEquals(listOf("From Attribute"), content.channels.map { it.name })
        assertFalse(content.channels.any { it.streamUrl.startsWith("rtsp") })
    }
}
