package com.nuvio.app.features.livetv

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.nuvio.app.features.player.PlayerBackRequest

/** Only opaque channel IDs cross the navigation boundary; source credentials stay on desktop. */
internal data class LiveTvPlaybackRequest(
    val channelId: String,
    val channelIds: List<String> = emptyList(),
)

internal data class LiveTvLaunch(val profileId: Int, val request: LiveTvPlaybackRequest)

/** Keeps large browse contexts out of serialized navigation state. */
internal object LiveTvLaunchStore {
    private var nextId = 1L
    private val launches = mutableMapOf<Long, LiveTvLaunch>()

    fun put(launch: LiveTvLaunch): Long = nextId++.also { launches[it] = launch }
    fun get(id: Long): LiveTvLaunch? = launches[id]
    fun remove(id: Long) { launches.remove(id) }
    fun clear() { launches.clear() }
}

@Composable
internal expect fun LiveTvScreen(
    profileId: Int,
    onPlay: (LiveTvPlaybackRequest) -> Unit,
    modifier: Modifier = Modifier,
    topChromePadding: Dp? = null,
)

@Composable
internal expect fun LiveTvPlayerScreen(
    profileId: Int,
    request: LiveTvPlaybackRequest,
    onBack: PlayerBackRequest,
    onSystemBackHandlerChanged: (handler: (() -> Unit)?) -> Unit,
    modifier: Modifier = Modifier,
)
