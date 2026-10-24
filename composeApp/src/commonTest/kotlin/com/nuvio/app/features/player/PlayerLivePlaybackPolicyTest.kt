package com.nuvio.app.features.player

import androidx.compose.ui.Modifier
import com.nuvio.app.features.watchprogress.WatchProgressPlaybackSession
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Live/VOD playback policy. The gates under test are the ones the player runtime actually calls;
 * each live assertion is paired with a VOD assertion so a gate that simply returns "false" for
 * everything cannot pass.
 */
class PlayerLivePlaybackPolicyTest {

    private fun PlayerScreenRuntime.setPlaying(positionMs: Long = 42_000L, durationMs: Long = 3_600_000L) {
        updatePlaybackSnapshot(
            PlayerPlaybackSnapshot(
                isLoading = false,
                isPlaying = true,
                positionMs = positionMs,
                durationMs = durationMs,
                bufferedPositionMs = positionMs + 5_000L,
            ),
        )
        initialLoadCompleted = true
    }

    @Test
    fun liveSessionSuppressesResumeSeeksWhileVodStillResumes() {
        assertTrue(PlayerScreenRuntime(liveArgs(initialPositionMs = 900_000L)).initialSeekDecision() is PlayerInitialSeekDecision.Suppressed)
        assertTrue(PlayerScreenRuntime(liveArgs(initialProgressFraction = 0.5f)).initialSeekDecision() is PlayerInitialSeekDecision.Suppressed)

        val vod = PlayerScreenRuntime(vodArgs(initialPositionMs = 900_000L))
        assertEquals(PlayerInitialSeekDecision.Seek(900_000L), vod.initialSeekDecision())

        val vodFraction = PlayerScreenRuntime(vodArgs()).apply {
            playbackSnapshot = PlayerPlaybackSnapshot(isLoading = false, durationMs = 100_000L)
            activeInitialProgressFraction = 0.25f
        }
        assertEquals(PlayerInitialSeekDecision.Seek(25_000L), vodFraction.initialSeekDecision())

        // A live session is marked applied up front, so the platform effect never seeks at all.
        assertTrue(PlayerScreenRuntime(liveArgs(initialPositionMs = 900_000L)).initialSeekApplied)
        assertFalse(PlayerScreenRuntime(vodArgs(initialPositionMs = 900_000L)).initialSeekApplied)
    }

    @Test
    fun liveSessionNeverWritesOrFlushesWatchProgress() = runTest {
        val writer = RecordingProgressWriter()
        val live = PlayerScreenRuntime(liveArgs()).apply {
            scope = backgroundScope
            progressWriter = writer
            setPlaying()
        }
        live.flushWatchProgress()
        live.persistPlaybackProgressTick()
        live.scheduleProgressSyncAfterSeek()

        assertTrue(writer.writes.isEmpty(), "live flush/tick/seek-sync must not persist progress")
        assertTrue(writer.flushes.isEmpty(), "live flush must not touch the progress repository")

        val vod = PlayerScreenRuntime(vodArgs()).apply {
            scope = backgroundScope
            progressWriter = writer
            setPlaying()
        }
        vod.flushWatchProgress()
        vod.persistPlaybackProgressTick()

        assertEquals(1, writer.flushes.size)
        assertEquals(1, writer.writes.size)
    }

    @Test
    fun liveSessionNeverStartsOrStopsScrobbles() = runTest {
        val live = PlayerScreenRuntime(liveArgs()).apply {
            scope = backgroundScope
            setPlaying()
        }
        live.emitTrackingScrobbleStart()
        live.emitTrackingScrobblePause()
        live.emitTrackingScrobbleStop()
        live.emitStopScrobbleForCurrentProgress()

        assertFalse(live.hasRequestedScrobbleStartForCurrentItem)
        assertFalse(live.hasSentCompletionScrobbleForCurrentItem)
        assertNull(live.currentTrackingMedia)
        assertEquals(0L, live.scrobbleStartRequestGeneration)
    }

    @Test
    fun allSeekEntryPointsAreBlockedForLiveAndPreservedForVod() = runTest {
        val liveController = RecordingController()
        val live = PlayerScreenRuntime(liveArgs()).apply {
            scope = backgroundScope
            progressWriter = RecordingProgressWriter()
            playerController = liveController
            setPlaying()
        }
        live.seekBy(10_000L)
        live.seekFromNativeControls(-10_000L)
        live.finishTimelineSeek(120_000L)
        live.handleDoubleTapSeek(PlayerSeekDirection.Forward)
        live.prepareDoubleTapSeekForNativeFallback(PlayerSeekDirection.Backward)

        assertTrue(liveController.seeks.isEmpty())
        assertNull(live.lastManualSkipSeekPositions)
        assertNull(live.pendingSeekTarget)
        assertNull(live.accumulatedSeekState)

        val vodController = RecordingController()
        val vodWriter = RecordingProgressWriter()
        val vod = PlayerScreenRuntime(vodArgs()).apply {
            scope = backgroundScope
            progressWriter = vodWriter
            playerController = vodController
            setPlaying()
        }
        vod.seekBy(10_000L)
        vod.finishTimelineSeek(120_000L)
        vod.handleDoubleTapSeek(PlayerSeekDirection.Forward)

        // VOD still reaches the controller for every seek path: relative, absolute timeline and
        // the accumulated double-tap target.
        assertEquals(3, vodController.seeks.size)
        assertEquals(10_000L, vodController.seeks[0])
        assertEquals(120_000L, vodController.seeks[1])
        assertTrue(vodController.seeks[2] >= 52_000L, "double-tap forwards from the live position")
    }

    @Test
    fun liveRetryRemountsAReleasedTunerInsteadOfReusingATerminalController() = runTest {
        val controller = RecordingController()
        val runtime = PlayerScreenRuntime(liveArgs()).apply {
            scope = backgroundScope
            playerController = controller
            playerLifecycleController = controller
            errorMessage = "boom"
            isLiveSurfaceReleased = true
        }

        runtime.retryLivePlayback()

        assertNull(runtime.errorMessage)
        assertFalse(runtime.isLiveSurfaceReleased)
        assertEquals(1, runtime.liveSurfaceGeneration)
        assertEquals(0, controller.retryCount, "a released controller is terminal")

        val activeRuntime = PlayerScreenRuntime(liveArgs()).apply {
            scope = backgroundScope
            playerController = controller
            errorMessage = "boom"
        }
        activeRuntime.retryLivePlayback()
        assertEquals(1, controller.retryCount)
    }

    @Test
    fun liveErrorsAreReplacedWithAFixedMessageAndVodKeepsDetail() {
        val live = PlayerScreenRuntime(liveArgs())
        live.livePlaybackErrorMessage = "This live channel could not be played."
        val credentialed = "http://user:secret@example.test/live/user/secret/1234.ts"
        assertEquals("This live channel could not be played.", live.displayPlaybackError(credentialed))
        assertEquals("This live channel could not be played.", live.displayPlaybackError("mpv: failed to open $credentialed"))

        live.applyPlaybackError(credentialed)
        assertEquals("This live channel could not be played.", live.errorMessage)

        val vod = PlayerScreenRuntime(vodArgs())
        assertEquals(credentialed, vod.displayPlaybackError(credentialed))
        vod.applyPlaybackError("playback failed")
        assertEquals("playback failed", vod.errorMessage)
    }

    @Test
    fun liveErrorFallsBackWhenTheLocalizedStringIsUnset() {
        val live = PlayerScreenRuntime(liveArgs())
        assertEquals(PlayerLivePlaybackErrorFallback, live.livePlaybackErrorText())
    }

    @Test
    fun liveNeverRefreshesCredentialedStreamsAndNeverMutatesTheStreamCache() {
        val live = PlayerScreenRuntime(liveArgs())
        assertFalse(live.tryRefreshCredentialedSourceAfterError("expired"))
        assertNull(live.credentialRefreshJob)
    }

    @Test
    fun liveIsNotTreatedAsASeriesEvenWhenMetadataClaimsSeries() {
        val live = PlayerScreenRuntime(liveArgs().copy(parentMetaType = "series"))
        assertTrue(live.isLive)
        assertFalse(live.isSeries)
        assertFalse(live.shouldLoadVodMetadata)

        val vod = PlayerScreenRuntime(vodArgs().copy(parentMetaType = "series"))
        assertTrue(vod.isSeries)
    }

    @Test
    fun liveChannelSwitchReleasesTheTunerBeforeInvokingTheCallback() = runTest {
        val events = mutableListOf<String>()
        var completeRelease: (() -> Unit)? = null
        val controller = RecordingController(
            release = { onReleased, _ ->
                events += "release"
                completeRelease = onReleased
            },
        )
        val runtime = PlayerScreenRuntime(
            liveArgs().copy(onNextChannel = { events += "switch" }),
        ).apply {
            scope = backgroundScope
            playerController = controller
            playerLifecycleController = controller
            isLiveChannelSwitching = false
        }

        runtime.switchLiveChannel(PlayerLiveChannelDirection.Next)

        assertEquals(listOf("release"), events)
        assertTrue(runtime.isLiveChannelSwitching)
        assertEquals(controller, runtime.playerController, "no callback may run before release completes")

        completeRelease?.invoke()
        assertEquals(listOf("release", "switch"), events)
        assertTrue(runtime.isLiveChannelSwitching, "surface must stay released until the new channel arrives")
        assertEquals(1, controller.releaseCount)
    }

    @Test
    fun rapidChannelClicksAreCollapsedIntoOneRelease() = runTest {
        val events = mutableListOf<String>()
        val controller = RecordingController(release = { onReleased, _ ->
            events += "release"
            onReleased()
        })
        val runtime = PlayerScreenRuntime(
            liveArgs().copy(
                onNextChannel = { events += "next" },
                onPreviousChannel = { events += "previous" },
            ),
        ).apply {
            scope = backgroundScope
            playerController = controller
            playerLifecycleController = controller
        }

        runtime.switchLiveChannel(PlayerLiveChannelDirection.Next)
        runtime.switchLiveChannel(PlayerLiveChannelDirection.Next)
        runtime.switchLiveChannel(PlayerLiveChannelDirection.Previous)

        assertEquals(1, controller.releaseCount)
        assertEquals(listOf("release", "next"), events)
    }

    @Test
    fun failedLiveReleaseKeepsTheCurrentChannelAndSurfacesAnError() = runTest {
        val events = mutableListOf<String>()
        val controller = RecordingController(release = { _, onFailed ->
            events += "release"
            onFailed("Native player shutdown failed.")
        })
        val runtime = PlayerScreenRuntime(
            liveArgs().copy(onNextChannel = { events += "next" }),
        ).apply {
            scope = backgroundScope
            playerController = controller
            playerLifecycleController = controller
        }

        runtime.switchLiveChannel(PlayerLiveChannelDirection.Next)

        assertEquals(listOf("release"), events)
        assertFalse(runtime.isLiveChannelSwitching)
        assertNotNull(runtime.errorMessage)
        assertEquals(controller, runtime.playerLifecycleController)
        assertTrue(runtime.liveReleaseFailed)
        assertFalse(runtime.canRetryLivePlayback, "a failed release keeps the native host mounted")
        val releaseError = runtime.errorMessage
        runtime.retryLivePlayback()
        assertEquals(0, controller.retryCount, "the terminal controller cannot replay")
        assertEquals(0, runtime.liveSurfaceGeneration, "remounting could race native teardown")
        assertEquals(releaseError, runtime.errorMessage)
        assertTrue(releaseError.orEmpty().contains("Back"))
    }

    @Test
    fun liveChannelSwitchWithoutCallbacksIsANoOp() = runTest {
        val controller = RecordingController()
        val runtime = PlayerScreenRuntime(liveArgs()).apply {
            scope = backgroundScope
            playerController = controller
            playerLifecycleController = controller
        }

        runtime.switchLiveChannel(PlayerLiveChannelDirection.Previous)
        runtime.switchLiveChannel(PlayerLiveChannelDirection.Next)

        assertEquals(0, controller.releaseCount)
        assertFalse(runtime.isLiveChannelSwitching)
    }

    @Test
    fun vodNeverSwitchesChannelsThroughTheLivePath() = runTest {
        val controller = RecordingController()
        val runtime = PlayerScreenRuntime(
            vodArgs().copy(onNextChannel = { error("VOD must not switch live channels") }),
        ).apply {
            scope = backgroundScope
            playerController = controller
            playerLifecycleController = controller
        }

        runtime.switchLiveChannel(PlayerLiveChannelDirection.Next)

        assertEquals(0, controller.releaseCount)
        assertFalse(runtime.isLiveChannelSwitching)
    }

    @Test
    fun aSecondReleaseAttemptIsRejectedWhileTheFirstIsInFlight() {
        val switcher = PlayerLiveChannelSwitcher()
        var releases = 0
        val first = switcher.request(
            PlayerLiveChannelSwitchRequest(
                direction = PlayerLiveChannelDirection.Next,
                release = { _, _ -> releases += 1 },
                beforeSwitch = {},
                switchTo = {},
                onFailure = {},
            ),
        )
        val second = switcher.request(
            PlayerLiveChannelSwitchRequest(
                direction = PlayerLiveChannelDirection.Previous,
                release = { _, _ -> releases += 1 },
                beforeSwitch = {},
                switchTo = {},
                onFailure = {},
            ),
        )

        assertTrue(first)
        assertFalse(second)
        assertEquals(1, releases)
        assertTrue(switcher.isSwitching)
    }

    @Test
    fun duplicateAndLateReleaseCallbacksAreIgnored() {
        val switcher = PlayerLiveChannelSwitcher()
        val switches = mutableListOf<String>()
        val failures = mutableListOf<String>()
        var complete: (() -> Unit)? = null
        var fail: ((String) -> Unit)? = null

        switcher.request(
            PlayerLiveChannelSwitchRequest(
                direction = PlayerLiveChannelDirection.Next,
                release = { onReleased, onFailed ->
                    complete = onReleased
                    fail = onFailed
                },
                beforeSwitch = { switches += "before" },
                switchTo = { switches += "switch" },
                onFailure = { failures += it },
            ),
        )

        complete?.invoke()
        complete?.invoke()
        fail?.invoke("late failure")

        assertEquals(listOf("before", "switch"), switches)
        assertTrue(failures.isEmpty())
        assertFalse(switcher.isSwitching)
    }

    @Test
    fun aFailureCallbackAndASuccessCallbackCannotBothRun() {
        val switcher = PlayerLiveChannelSwitcher()
        val events = mutableListOf<String>()
        var complete: (() -> Unit)? = null
        var fail: ((String) -> Unit)? = null

        switcher.request(
            PlayerLiveChannelSwitchRequest(
                direction = PlayerLiveChannelDirection.Previous,
                release = { onReleased, onFailed ->
                    complete = onReleased
                    fail = onFailed
                },
                beforeSwitch = {},
                switchTo = { events += "switch" },
                onFailure = { events += "failure:$it" },
            ),
        )

        fail?.invoke("release failed")
        complete?.invoke()

        assertEquals(listOf("failure:release failed"), events)
    }

    @Test
    fun aThrowingReleaseIsContainedAndAllowsTheNextAttempt() {
        val switcher = PlayerLiveChannelSwitcher()
        var attempts = 0

        repeat(2) {
            switcher.request(
                PlayerLiveChannelSwitchRequest(
                    direction = PlayerLiveChannelDirection.Next,
                    release = { onReleased, _ ->
                        attempts += 1
                        if (attempts == 1) error("release threw")
                        onReleased()
                    },
                    beforeSwitch = {},
                    switchTo = {},
                    onFailure = {},
                ),
            )
        }

        assertEquals(2, attempts)
        assertFalse(switcher.isSwitching)
    }

    @Test
    fun throwingNativeReleaseRestoresRuntimeSwitchControls() = runTest {
        var switches = 0
        val controller = RecordingController(release = { _, _ -> error("native release failed") })
        val runtime = PlayerScreenRuntime(liveArgs().copy(onNextChannel = { switches += 1 })).apply {
            scope = backgroundScope
            playerController = controller
            playerLifecycleController = controller
        }

        runtime.switchLiveChannel(PlayerLiveChannelDirection.Next)

        assertFalse(runtime.isLiveChannelSwitching)
        assertFalse(runtime.isLiveSurfaceReleased)
        assertEquals(0, switches)
        assertEquals(controller, runtime.playerLifecycleController)
        assertNotNull(runtime.errorMessage)
    }

    @Test
    fun liveSourceAdoptionRetargetsWithoutResumeOrStaleProgress() {
        val runtime = PlayerScreenRuntime(liveArgs(initialPositionMs = 900_000L)).apply {
            setPlaying(positionMs = 500_000L)
            isLiveChannelSwitching = true
            errorMessage = "old failure"
        }

        runtime.args = liveArgs().copy(
            sourceUrl = "https://iptv.test/live/user/pass/2.ts",
            streamTitle = "Channel 2",
            sourceHeaders = mapOf("Referer" to "https://iptv.test/"),
        )
        runtime.syncLiveSourceFromArgs()

        assertEquals("https://iptv.test/live/user/pass/2.ts", runtime.activeSourceUrl)
        assertEquals("Channel 2", runtime.activeStreamTitle)
        assertEquals(mapOf("Referer" to "https://iptv.test/"), runtime.activeSourceHeaders)
        assertEquals(0L, runtime.activeInitialPositionMs)
        assertNull(runtime.activeInitialProgressFraction)
        assertTrue(runtime.initialSeekApplied)
        assertTrue(runtime.playbackSnapshot.isLoading)
        assertFalse(runtime.isLiveChannelSwitching)
        assertNull(runtime.errorMessage)
        assertEquals(1, runtime.liveSurfaceGeneration)
    }

    @Test
    fun liveSourceAdoptionIsInertForVodAndForUnchangedSources() {
        val vod = PlayerScreenRuntime(vodArgs(initialPositionMs = 900_000L))
        vod.args = vodArgs().copy(sourceUrl = "https://example.test/other.mp4")
        vod.syncLiveSourceFromArgs()
        assertEquals("https://iptv.test/live/user/pass/1.ts", vod.activeSourceUrl)
        assertEquals(0, vod.liveSurfaceGeneration)

        val live = PlayerScreenRuntime(liveArgs())
        live.syncLiveSourceFromArgs()
        assertEquals(0, live.liveSurfaceGeneration)
    }

    private class RecordingProgressWriter : PlayerProgressWriter {
        val writes = mutableListOf<PlayerPlaybackSnapshot>()
        val flushes = mutableListOf<PlayerPlaybackSnapshot>()

        override fun persist(
            session: WatchProgressPlaybackSession,
            snapshot: PlayerPlaybackSnapshot,
            syncRemote: Boolean,
        ) {
            writes += snapshot
        }

        override fun flush(
            session: WatchProgressPlaybackSession,
            snapshot: PlayerPlaybackSnapshot,
        ) {
            flushes += snapshot
        }
    }

    private class RecordingController(
        private val release: ((onReleased: () -> Unit, onReleasedFailed: (String) -> Unit) -> Unit)? = null,
    ) : PlayerEngineController {
        val seeks = mutableListOf<Long>()
        var releaseCount = 0
            private set
        var retryCount = 0
            private set

        override fun play() = Unit
        override fun pause() = Unit
        override fun seekTo(positionMs: Long) {
            seeks += positionMs
        }

        override fun seekBy(offsetMs: Long) {
            seeks += offsetMs
        }

        override fun retry() {
            retryCount += 1
        }
        override fun setPlaybackSpeed(speed: Float) = Unit
        override fun getAudioTracks(): List<AudioTrack> = emptyList()
        override fun getSubtitleTracks(): List<SubtitleTrack> = emptyList()
        override fun applyAudioLanguagePreferences(languages: List<String>) = Unit
        override fun selectAudioTrack(index: Int) = Unit
        override fun selectSubtitleTrack(index: Int) = Unit
        override fun setSubtitleUri(url: String) = Unit
        override fun clearExternalSubtitle() = Unit
        override fun clearExternalSubtitleAndSelect(trackIndex: Int) = Unit

        override fun releaseBeforeNavigation(
            onReleased: () -> Unit,
            onReleaseFailed: (String) -> Unit,
        ) {
            releaseCount += 1
            val handler = release
            if (handler == null) onReleased() else handler(onReleased, onReleaseFailed)
        }
    }

    private fun liveArgs(
        initialPositionMs: Long = 0L,
        initialProgressFraction: Float? = null,
    ): PlayerScreenArgs = baseArgs(initialPositionMs, initialProgressFraction).copy(isLive = true)

    private fun vodArgs(
        initialPositionMs: Long = 0L,
        initialProgressFraction: Float? = null,
    ): PlayerScreenArgs = baseArgs(initialPositionMs, initialProgressFraction)

    private fun baseArgs(
        initialPositionMs: Long,
        initialProgressFraction: Float?,
    ): PlayerScreenArgs = PlayerScreenArgs(
        profileId = 1,
        title = "Channel",
        sourceUrl = "https://iptv.test/live/user/pass/1.ts",
        sourceAudioUrl = null,
        sourceHeaders = emptyMap(),
        sourceResponseHeaders = emptyMap(),
        streamType = null,
        providerName = "Provider",
        streamTitle = "Channel",
        streamSubtitle = null,
        initialBingeGroup = null,
        pauseDescription = null,
        onBack = {},
        onOpenInExternalPlayer = null,
        onOpenExternalUrl = null,
        modifier = Modifier,
        logo = null,
        poster = null,
        background = null,
        seasonNumber = null,
        episodeNumber = null,
        episodeTitle = null,
        episodeThumbnail = null,
        contentType = "movie",
        videoId = null,
        parentMetaId = "channel-1",
        parentMetaType = "movie",
        providerAddonId = null,
        torrentInfoHash = null,
        torrentFileIdx = null,
        torrentFilename = null,
        torrentTrackers = emptyList(),
        initialPositionMs = initialPositionMs,
        initialProgressFraction = initialProgressFraction,
    )
}
