package com.nuvio.app.features.livetv

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.nuvio.app.features.player.PlayerBackRequest

// IPTV is reachable only on desktop; these actuals keep shared navigation portable.
@Composable
internal actual fun LiveTvScreen(
    profileId: Int,
    onPlay: (LiveTvPlaybackRequest) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier,
) = Unit

@Composable
internal actual fun LiveTvPlayerScreen(
    profileId: Int,
    request: LiveTvPlaybackRequest,
    onBack: PlayerBackRequest,
    onSystemBackHandlerChanged: (handler: (() -> Unit)?) -> Unit,
    modifier: Modifier,
) = Unit
