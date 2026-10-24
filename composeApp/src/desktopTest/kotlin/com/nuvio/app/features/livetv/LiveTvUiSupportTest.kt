package com.nuvio.app.features.livetv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiveTvUiSupportTest {
    private val hidden = "[link hidden]"

    @Test
    fun urlDraftTrimsAndPersistsOnlyThePlaylistLocation() {
        val validation = validateLiveTvSourceDraft(
            LiveTvSourceDraft(
                id = "source-1",
                name = "  Home playlist  ",
                kind = LiveTvSourceKind.M3U_URL,
                playlistUrl = " https://example.com/list.m3u ",
            ),
        )

        val source = (validation as LiveTvSourceValidation.Valid).source
        assertEquals("Home playlist", source.name)
        assertEquals("https://example.com/list.m3u", source.location)
        assertEquals("", source.username)
        assertEquals("", source.password)
    }

    @Test
    fun sourceKindRequiresItsOwnLocationAndXtreamCredentials() {
        val base = LiveTvSourceDraft(id = "id", name = "Source")

        assertEquals(
            LiveTvSourceValidation.Invalid(LiveTvSourceFieldError.PLAYLIST_URL),
            validateLiveTvSourceDraft(base.copy(playlistUrl = "ftp://host/list.m3u")),
        )
        assertEquals(
            LiveTvSourceValidation.Invalid(LiveTvSourceFieldError.PLAYLIST_FILE),
            validateLiveTvSourceDraft(base.copy(kind = LiveTvSourceKind.M3U_FILE, playlistFile = "   ")),
        )
        assertEquals(
            LiveTvSourceValidation.Invalid(LiveTvSourceFieldError.HOST),
            validateLiveTvSourceDraft(base.copy(kind = LiveTvSourceKind.XTREAM, host = "host:8080")),
        )
        assertEquals(
            LiveTvSourceValidation.Invalid(LiveTvSourceFieldError.USERNAME),
            validateLiveTvSourceDraft(
                base.copy(kind = LiveTvSourceKind.XTREAM, host = "http://host:8080", password = "p"),
            ),
        )
        assertEquals(
            LiveTvSourceValidation.Invalid(LiveTvSourceFieldError.PASSWORD),
            validateLiveTvSourceDraft(
                base.copy(kind = LiveTvSourceKind.XTREAM, host = "http://host:8080", username = "u"),
            ),
        )
    }

    @Test
    fun switchingKindToPlaylistDropsStaleXtreamCredentials() {
        val validation = validateLiveTvSourceDraft(
            LiveTvSourceDraft(
                id = "id",
                name = "Source",
                kind = LiveTvSourceKind.M3U_URL,
                playlistUrl = "https://example.com/list.m3u",
                username = "leftover",
                password = "leftover",
            ),
        )

        val source = (validation as LiveTvSourceValidation.Valid).source
        assertEquals("", source.username)
        assertEquals("", source.password)
    }

    @Test
    fun localGuideFileAndInvalidGuideUrlAreDistinguished() {
        val base = LiveTvSourceDraft(id = "id", name = "Source", playlistUrl = "https://example.com/list.m3u")

        assertTrue(validateLiveTvSourceDraft(base.copy(epg = "/tmp/guide.xml.gz")) is LiveTvSourceValidation.Valid)
        assertTrue(validateLiveTvSourceDraft(base.copy(epg = "/tmp/My guide.xml.gz")) is LiveTvSourceValidation.Valid)
        assertTrue(validateLiveTvSourceDraft(base.copy(epg = "C:\\My guides\\guide.xml")) is LiveTvSourceValidation.Valid)
        assertTrue(validateLiveTvSourceDraft(base.copy(epg = "https://example.com/guide.xml")) is LiveTvSourceValidation.Valid)
        assertEquals(
            LiveTvSourceValidation.Invalid(LiveTvSourceFieldError.EPG),
            validateLiveTvSourceDraft(base.copy(epg = "not a link")),
        )
        assertEquals(
            LiveTvSourceValidation.Invalid(LiveTvSourceFieldError.EPG),
            validateLiveTvSourceDraft(base.copy(epg = "ftp://example.com/guide.xml")),
        )
        assertEquals(
            LiveTvSourceValidation.Invalid(LiveTvSourceFieldError.PLAYLIST_URL),
            validateLiveTvSourceDraft(base.copy(playlistUrl = "https://example.com:bad/list.m3u")),
        )
    }

    @Test
    fun draftRoundTripPreservesEveryEditableField() {
        val source = LiveTvSource(
            id = "id",
            name = "Xtream",
            kind = LiveTvSourceKind.XTREAM,
            location = "http://host:8080",
            username = "user",
            password = "pass",
            epgUrl = "https://example.com/guide.xml",
            enabled = false,
        )

        val draft = source.toDraft()
        assertEquals("http://host:8080", draft.host)
        assertEquals("https://example.com/guide.xml", draft.epg)
        assertEquals(false, draft.enabled)

        val roundTrip = (validateLiveTvSourceDraft(draft) as LiveTvSourceValidation.Valid).source
        assertEquals(source, roundTrip)
    }

    @Test
    fun filtersCombineSearchSourceAndGroup() {
        val channels = listOf(
            LiveTvChannel(id = "a", sourceId = "s1", name = "Alpha News", streamUrl = "u", group = "News"),
            LiveTvChannel(id = "b", sourceId = "s1", name = "Beta Sport", streamUrl = "u", group = "Sport"),
            LiveTvChannel(id = "c", sourceId = "s2", name = "Gamma News", streamUrl = "u", group = "News"),
            LiveTvChannel(id = "d", sourceId = "s2", name = "Delta", streamUrl = "u"),
        )

        assertEquals(listOf("a", "c"), channels.applyLiveTvFilters("news", null, null).map { it.id })
        assertEquals(listOf("c", "d"), channels.applyLiveTvFilters("", "s2", null).map { it.id })
        assertEquals(listOf("a", "c"), channels.applyLiveTvFilters("", null, "News").map { it.id })
        assertTrue(channels.applyLiveTvFilters("delta", "s1", null).isEmpty())
        assertEquals(4, channels.applyLiveTvFilters("   ", null, null).size)
        assertEquals(listOf("News", "Sport"), channels.liveTvGroups())
    }

    @Test
    fun guideWindowKeepsCurrentThenUpcomingInStartOrder() {
        val now = 1_700_000_000_000L
        val programmes = listOf(
            LiveTvProgramme("ended", now - 7_200_000, now - 3_600_000),
            LiveTvProgramme("later", now + 7_200_000, now + 10_800_000),
            LiveTvProgramme("current", now - 1_800_000, now + 1_800_000),
            LiveTvProgramme("next", now + 1_800_000, now + 7_200_000),
        )

        assertEquals(listOf("current", "next", "later"), programmes.guideWindow(now, 6).map { it.title })
        assertEquals(listOf("current"), programmes.guideWindow(now, 1).map { it.title })
        assertTrue(programmes.guideWindow(now, 0).isEmpty())
        assertEquals("current", programmes.nowPlaying(now)?.title)
        assertEquals(listOf("next", "later"), programmes.upcomingProgrammes(now).map { it.title })
    }

    @Test
    fun nowAndNextSkipsFinishedProgrammesAndIgnoresEmptyRanges() {
        val now = 1_000_000L
        val programmes = listOf(
            LiveTvProgramme("broken", now + 10, now + 10),
            LiveTvProgramme("current", now - 1_000, now + 1_000),
            LiveTvProgramme("next", now + 1_000, now + 5_000),
        )

        val nowNext = programmes.nowAndNext(now)
        assertEquals("current", nowNext.current?.title)
        assertEquals("next", nowNext.next?.title)
        assertNull(emptyList<LiveTvProgramme>().nowAndNext(now).current)
    }

    @Test
    fun progressIsClampedToTheProgrammeBounds() {
        val programme = LiveTvProgramme("p", 1_000L, 2_000L)

        assertEquals(0f, programme.progressAt(500L))
        assertEquals(0.5f, programme.progressAt(1_500L))
        assertEquals(1f, programme.progressAt(5_000L))
        assertEquals(0f, LiveTvProgramme("zero", 10L, 10L).progressAt(10L))
    }

    @Test
    fun playbackContextDropsUnavailableChannelsAndKeepsRequestedOne() {
        val available = setOf("a", "b", "c")

        assertEquals(
            listOf("a", "b"),
            liveTvPlaybackChannelIds(LiveTvPlaybackRequest("b", listOf("a", "b", "gone", "a")), available),
        )
        assertEquals(
            listOf("a", "b"),
            liveTvPlaybackChannelIds(LiveTvPlaybackRequest("gone", listOf("a", "b")), available),
        )
        assertEquals(
            listOf("b"),
            liveTvPlaybackChannelIds(LiveTvPlaybackRequest("b"), available),
        )
        assertTrue(
            liveTvPlaybackChannelIds(LiveTvPlaybackRequest("x", listOf("x")), available).isEmpty(),
        )
    }

    @Test
    fun cyclingWrapsAndStopsForUnavailableOrSingleChannelContexts() {
        val playlist = listOf("a", "b", "c")

        assertEquals("c", neighbouringLiveTvChannelId(playlist, "a", forward = false))
        assertEquals("a", neighbouringLiveTvChannelId(playlist, "c", forward = true))
        assertEquals("c", neighbouringLiveTvChannelId(playlist, "b", forward = true))
        assertNull(neighbouringLiveTvChannelId(listOf("a"), "a", forward = true))
        assertNull(neighbouringLiveTvChannelId(playlist, "missing", forward = true))
    }

    @Test
    fun credentialedLinksAreStrippedFromMessages() {
        assertEquals(
            "Could not load [link hidden]",
            sanitizedLiveTvMessage("Could not load http://user:pass@host/live/u/p/1.ts", hidden),
        )
        assertEquals(
            "[link hidden] failed, retry [link hidden]",
            sanitizedLiveTvMessage("https://host/a.m3u failed, retry http://h/b.m3u", hidden),
        )
        assertNull(sanitizedLiveTvMessage("   ", hidden))
        assertNull(sanitizedLiveTvMessage(null, hidden))
    }

    @Test
    fun generatedSourceIdsAreUniqueUuids() {
        val first = newLiveTvSourceId()
        val second = newLiveTvSourceId()

        assertTrue(first != second)
        assertTrue(runCatching { java.util.UUID.fromString(first) }.isSuccess)
        assertTrue(runCatching { java.util.UUID.fromString(second) }.isSuccess)
    }
}
