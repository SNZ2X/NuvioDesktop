package com.nuvio.app.features.livetv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiveTvUrlsTest {
    @Test
    fun httpUrlsKeepEmbeddedBasicAuthQueryAndNonDefaultPort() {
        assertEquals(
            "http://user:secret@example.com:8080/live/a.m3u8?token=1",
            LiveTvUrls.resolveMedia("http://user:secret@example.com:8080/live/a.m3u8?token=1"),
        )
        // Default ports are normalized away, the rest of the URL is untouched.
        assertEquals(
            "https://example.com/live/a.ts",
            LiveTvUrls.resolveMedia("https://example.com:443/live/a.ts"),
        )
    }

    @Test
    fun relativeReferencesResolveAgainstTheDocument() {
        assertEquals(
            "https://example.com/lists/streams/a.m3u8",
            LiveTvUrls.resolveMedia("streams/a.m3u8", "https://example.com/lists/index.m3u"),
        )
        assertEquals(
            "https://example.com/a.m3u8",
            LiveTvUrls.resolveMedia("/a.m3u8", "https://example.com/lists/index.m3u"),
        )
        assertNull(LiveTvUrls.resolveMedia("a.m3u8", null))
        assertNull(LiveTvUrls.resolveMedia("a.m3u8", "not a url"))
    }

    @Test
    fun onlyHttpSchemesAreMediaAndInvalidPortsReject() {
        assertNull(LiveTvUrls.resolveMedia("rtsp://example.com/live"))
        assertNull(LiveTvUrls.resolveMedia("udp://239.0.0.1:1234"))
        assertNull(LiveTvUrls.resolveMedia("file:///etc/passwd"))
        assertNull(LiveTvUrls.resolveMedia("javascript:alert(1)"))
        assertNull(LiveTvUrls.resolveMedia("data:text/plain;base64,QQ=="))
        assertNull(LiveTvUrls.resolveMedia("http://example.com:0/live.m3u8"))
        assertNull(LiveTvUrls.resolveMedia("http://example.com:70000/live.m3u8"))
        assertNull(LiveTvUrls.resolveMedia("http:///live.m3u8"))
        assertNull(LiveTvUrls.resolveMedia("http://example.com/li ve.m3u8"))
    }

    @Test
    fun resolveHttpRejectsLocalPaths() {
        assertNull(LiveTvUrls.resolveHttp("/home/user/epg.xml"))
        assertEquals(
            "https://example.com/epg.xml",
            LiveTvUrls.resolveHttp("epg.xml", "https://example.com/list.m3u"),
        )
        assertEquals(
            "https://example.com/epg.xml",
            LiveTvUrls.resolveHttp("https://example.com/epg.xml"),
        )
    }

    @Test
    fun channelIdsAreStableSourceQualifiedAndOpaque() {
        val first = LiveTvUrls.channelId("source-a", "http://user:secret@example.com/live")
        val again = LiveTvUrls.channelId("source-a", "http://user:secret@example.com/live")
        val otherSource = LiveTvUrls.channelId("source-b", "http://user:secret@example.com/live")

        assertEquals(first, again)
        assertNotEquals(first, otherSource)
        assertFalse(first.contains("example.com"))
        assertFalse(first.contains("secret"))
        assertTrue(first.startsWith("lt:"))
    }
}
