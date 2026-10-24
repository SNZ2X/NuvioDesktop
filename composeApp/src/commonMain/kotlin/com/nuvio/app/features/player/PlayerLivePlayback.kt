package com.nuvio.app.features.player

import com.nuvio.app.features.watchprogress.WatchProgressPlaybackSession
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Everything a native Live TV session must never touch lives behind these gates.
 *
 * Live playback is a continuous tuner feed: there is no resume point, no finite duration and no
 * watched state to advance. Keeping the checks here (instead of scattering conditionals) makes the
 * VOD policy (`isLive = false`) behave exactly as before while every live entry point can be
 * exercised by tests.
 */
internal val PlayerScreenRuntime.shouldPersistPlaybackProgress: Boolean
    get() = !isLive

internal val PlayerScreenRuntime.shouldScrobblePlayback: Boolean
    get() = !isLive

internal val PlayerScreenRuntime.shouldSeekPlayback: Boolean
    get() = !isLive

internal val PlayerScreenRuntime.shouldReadResumePosition: Boolean
    get() = !isLive

internal val PlayerScreenRuntime.shouldLoadVodMetadata: Boolean
    get() = !isLive

/** Upper bound for the parent to deliver the next channel after a live release completed. */
internal const val PlayerLiveChannelSwitchGuardMs = 3_000L

/** Stable live error text. Provider exceptions and credentialed URLs never reach the UI. */
internal const val PlayerLivePlaybackErrorFallback = "This live channel could not be played."

internal val PlayerScreenRuntime.canRetryLivePlayback: Boolean
    get() = isLive && !liveReleaseFailed && !isLiveChannelSwitching && errorMessage != null

internal fun PlayerScreenRuntime.livePlaybackErrorText(): String =
    if (liveReleaseFailed) {
        liveReleaseErrorMessage.ifBlank {
            "The player could not finish shutting down. Try Back again, or restart Nuvio."
        }
    } else {
        livePlaybackErrorMessage.ifBlank { PlayerLivePlaybackErrorFallback }
    }

internal fun PlayerScreenRuntime.applyReleaseFailure(message: String) {
    if (isLive) liveReleaseFailed = true
    applyPlaybackError(message)
}

/**
 * Resolves the message rendered by the playback error overlay. Live sessions always substitute a
 * fixed string: Xtream stream URLs embed credentials, and native/provider errors may echo the URL.
 */
internal fun PlayerScreenRuntime.displayPlaybackError(rawMessage: String?): String? {
    if (rawMessage == null) return null
    return if (isLive) livePlaybackErrorText() else rawMessage
}

internal fun PlayerScreenRuntime.applyPlaybackError(rawMessage: String?) {
    errorMessage = displayPlaybackError(rawMessage)
}

/**
 * Watch-progress writes go through this seam so live gating can be verified without touching the
 * persisted repository. Production always uses [Default].
 */
internal interface PlayerProgressWriter {
    fun persist(
        session: WatchProgressPlaybackSession,
        snapshot: PlayerPlaybackSnapshot,
        syncRemote: Boolean = true,
    )

    fun flush(
        session: WatchProgressPlaybackSession,
        snapshot: PlayerPlaybackSnapshot,
    )

    data object Default : PlayerProgressWriter {
        override fun persist(
            session: WatchProgressPlaybackSession,
            snapshot: PlayerPlaybackSnapshot,
            syncRemote: Boolean,
        ) {
            WatchProgressRepository.upsertPlaybackProgress(
                session = session,
                snapshot = snapshot,
                syncRemote = syncRemote,
            )
        }

        override fun flush(
            session: WatchProgressPlaybackSession,
            snapshot: PlayerPlaybackSnapshot,
        ) {
            WatchProgressRepository.flushPlaybackProgress(
                session = session,
                snapshot = snapshot,
            )
        }
    }
}

internal sealed interface PlayerInitialSeekDecision {
    /** Live playback, or a VOD session with nothing to resume: mark the seek as handled. */
    data object Suppressed : PlayerInitialSeekDecision

    /** A resume fraction is known but the media duration is not yet: keep waiting. */
    data object Waiting : PlayerInitialSeekDecision

    data class Seek(val positionMs: Long) : PlayerInitialSeekDecision
}

/** Resolves the initial resume seek for the platform surface. Live never resumes. */
internal fun PlayerScreenRuntime.initialSeekDecision(): PlayerInitialSeekDecision {
    if (!shouldReadResumePosition) return PlayerInitialSeekDecision.Suppressed
    val progressFraction = activeInitialProgressFraction
        ?.takeIf { it > 0f }
        ?.coerceIn(0f, 1f)
    return when {
        activeInitialPositionMs > 0L -> PlayerInitialSeekDecision.Seek(activeInitialPositionMs)
        progressFraction != null && playbackSnapshot.durationMs > 0L -> {
            PlayerInitialSeekDecision.Seek(
                (playbackSnapshot.durationMs.toDouble() * progressFraction.toDouble()).toLong(),
            )
        }
        progressFraction != null -> PlayerInitialSeekDecision.Waiting
        else -> PlayerInitialSeekDecision.Suppressed
    }
}

internal enum class PlayerLiveChannelDirection {
    Previous,
    Next,
}

/**
 * Switches live channels only after the native tuner released its handle.
 *
 * The parent owns which channel is next; this runtime only guarantees ordering (release completes
 * before the callback runs), single-flight switching (rapid clicks are ignored), and that a failed
 * release keeps the current channel visible with an error instead of a half-torn-down surface.
 */
internal fun PlayerScreenRuntime.switchLiveChannel(direction: PlayerLiveChannelDirection) {
    if (!isLive) return
    val callback = when (direction) {
        PlayerLiveChannelDirection.Previous -> onPreviousChannel
        PlayerLiveChannelDirection.Next -> onNextChannel
    } ?: return
    if (isLiveChannelSwitching || liveChannelSwitcher.isSwitching) return

    val controller = playerLifecycleController ?: playerController
    isLiveChannelSwitching = true
    val accepted = liveChannelSwitcher.request(
        PlayerLiveChannelSwitchRequest(
            direction = direction,
            release = { onReleased, onReleaseFailed ->
                if (controller == null) {
                    onReleased()
                } else {
                    controller.releaseBeforeNavigation(onReleased, onReleaseFailed)
                }
            },
            beforeSwitch = {
                // The tuner handle is gone at this point. Drop the retained controller so the
                // surface cannot re-attach the old channel while the parent swaps sources.
                if (playerLifecycleController === controller) playerLifecycleController = null
                if (playerController === controller) playerController = null
                playerControllerSourceUrl = null
                playbackSnapshot = PlayerPlaybackSnapshot()
                playbackSnapshotKey = null
                initialLoadCompleted = false
                shouldPlay = true
                isScrubbingTimeline = false
                scrubbingPositionMs = null
                pendingSeekTarget = null
                errorMessage = null
                isLiveSurfaceReleased = true
                liveReleaseFailed = false
            },
            switchTo = {
                // The surface stays unmounted until the parent supplies the replacement channel,
                // so the released tuner can never re-attach the channel we just left. The guard is
                // armed even if the parent callback throws, otherwise the surface would stay
                // unmounted with a terminal controller forever.
                try {
                    callback()
                } finally {
                    armLiveChannelSwitchGuard()
                }
            },
            onFailure = { message ->
                isLiveChannelSwitching = false
                isLiveSurfaceReleased = false
                applyReleaseFailure(message)
                controlsVisible = true
            },
        ),
    )
    if (!accepted) isLiveChannelSwitching = false
}

/**
 * Adopts a replacement live source supplied by the parent through recomposition.
 *
 * `PlayerScreenArgs` is refreshed on every recomposition, but the runtime's active source fields
 * are intentionally one-shot for VOD. Live sessions are tuners, so a changed URL in the same
 * composition must retarget the surface without resume state or a stale error.
 */
internal fun PlayerScreenRuntime.syncLiveSourceFromArgs() {
    if (!isLive) return
    val next = args
    if (next.sourceUrl == activeSourceUrl) return

    liveChannelSwitchGuardJob?.cancel()
    liveChannelSwitchGuardJob = null
    isLiveChannelSwitching = false
    isLiveSurfaceReleased = false
    liveReleaseFailed = false
    liveSurfaceGeneration += 1

    activeSourceUrl = next.sourceUrl
    activeSourceAudioUrl = next.sourceAudioUrl
    activeSourceHeaders = sanitizePlaybackHeaders(next.sourceHeaders)
    activeSourceResponseHeaders = sanitizePlaybackResponseHeaders(next.sourceResponseHeaders)
    activeStreamType = next.streamType
    activeSourceIdentityKey = next.sourceUrl.trim().takeIf { it.isNotBlank() }?.let { "url:$it" }
    activeSourceFilename = next.sourceFilename
    activeStreamTitle = next.streamTitle
    activeStreamSubtitle = next.streamSubtitle
    activeProviderName = next.providerName
    activeProviderAddonId = next.providerAddonId
    activeStream = next.stream
    currentStreamBingeGroup = next.initialBingeGroup
    currentStreamFilename = next.stream?.behaviorHints?.filename
    currentStreamInfoHash = next.stream?.infoHash
    activeInitialPositionMs = 0L
    activeInitialProgressFraction = null
    initialSeekApplied = true
    shouldPlay = true
    initialLoadCompleted = false
    playbackSnapshot = PlayerPlaybackSnapshot()
    playbackSnapshotKey = null
    playerControllerSourceUrl = null
    isScrubbingTimeline = false
    scrubbingPositionMs = null
    errorMessage = null
}

/**
 * Clears the switching gate when the parent never delivers a new live URL (an ignored request or
 * a callback that only reported a state it already showed). Without this the surface would stay
 * unmounted forever after a release that produced no follow-up source.
 */
internal fun PlayerScreenRuntime.armLiveChannelSwitchGuard() {
    if (!isLiveChannelSwitching) return
    val hostScope = runCatching { scope }.getOrNull() ?: return
    val expectedSourceUrl = activeSourceUrl
    liveChannelSwitchGuardJob?.cancel()
    liveChannelSwitchGuardJob = hostScope.launch {
        delay(PlayerLiveChannelSwitchGuardMs)
        if (!isLiveChannelSwitching || activeSourceUrl != expectedSourceUrl) return@launch
        // The parent accepted the request but produced no new channel. Stay released and tell the
        // viewer, instead of silently replaying the channel they just left; Retry remounts it.
        isLiveChannelSwitching = false
        errorMessage = livePlaybackErrorText()
        controlsVisible = true
    }
}

/**
 * Live retry. A failed channel usually comes from a tuner that owns no handle yet, so remount the
 * surface for the current channel on a fresh controller; that also covers the case where the
 * previous controller was already released by a channel switch.
 */
internal fun PlayerScreenRuntime.retryLivePlayback() {
    // A release failure may mean teardown is still in flight. Keep the native host mounted and
    // use the existing Back/release handshake again; recreating it here could race teardown.
    if (!isLive || liveReleaseFailed) return
    errorMessage = null
    controlsVisible = !playerControlsLocked
    if (isLiveSurfaceReleased || playerController == null) {
        isLiveSurfaceReleased = false
        liveSurfaceGeneration += 1
        return
    }
    playerController?.retry()
}

internal data class PlayerLiveChannelSwitchRequest(
    val direction: PlayerLiveChannelDirection,
    val release: (onReleased: () -> Unit, onReleaseFailed: (String) -> Unit) -> Unit,
    /** Runs only after the native player released its handle. */
    val beforeSwitch: () -> Unit,
    val switchTo: () -> Unit,
    val onFailure: (String) -> Unit,
)

/**
 * Serializes live channel changes behind the native release handshake.
 *
 * A switch is only accepted when none is in flight, the tuner is released before
 * [PlayerLiveChannelSwitchRequest.beforeSwitch] and [PlayerLiveChannelSwitchRequest.switchTo] run,
 * and every attempt owns exactly one terminal callback. A stale or duplicate release callback
 * therefore cannot switch channels twice or after a failure.
 */
internal class PlayerLiveChannelSwitcher {
    private var nextAttemptId: Long = 0L
    private var activeAttemptId: Long? = null

    val isSwitching: Boolean
        get() = activeAttemptId != null

    /** Returns false only when a switch was already in flight and the request was ignored. */
    fun request(request: PlayerLiveChannelSwitchRequest): Boolean {
        if (activeAttemptId != null) return false
        val attemptId = ++nextAttemptId
        activeAttemptId = attemptId

        fun claimAttempt(): Boolean {
            if (activeAttemptId != attemptId) return false
            activeAttemptId = null
            return true
        }

        try {
            request.release(
                {
                    if (!claimAttempt()) return@release
                    try {
                        request.beforeSwitch()
                        request.switchTo()
                    } catch (_: Throwable) {
                        // The tuner is already released; never let a callback failure wedge switching.
                    }
                },
                { message ->
                    if (!claimAttempt()) return@release
                    try {
                        request.onFailure(message)
                    } catch (_: Throwable) {
                        // Same fail-safe as the success path.
                    }
                },
            )
        } catch (_: Throwable) {
            if (claimAttempt()) {
                runCatching { request.onFailure(PlayerLivePlaybackErrorFallback) }
            }
        }
        return true
    }
}
