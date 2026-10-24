package com.nuvio.app.features.livetv

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.core.ui.NuvioPrimaryButton
import com.nuvio.app.core.ui.NuvioScreen
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.core.ui.NuvioSurfaceCard
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.player.PlayerBackRequest
import com.nuvio.app.features.player.PlayerScreen
import kotlinx.coroutines.CancellationException
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.iptv_channel_unavailable_back
import nuvio.composeapp.generated.resources.iptv_channel_unavailable_message
import nuvio.composeapp.generated.resources.iptv_channel_unavailable_title
import nuvio.composeapp.generated.resources.iptv_nav_live_tv
import org.jetbrains.compose.resources.stringResource
import java.util.UUID

/**
 * Desktop live tuner surface.
 *
 * The route carries only opaque channel IDs; this wrapper resolves the live channel from the
 * same profile-scoped repository the browse screen uses, then launches the shared player with
 * the explicit live policy. A channel change re-keys the player so playback state is rebuilt
 * from scratch, which is what a tuner needs and what keeps VOD resume bookkeeping out of it.
 * The parent's [onBack] release handshake is forwarded untouched.
 */
@Composable
internal actual fun LiveTvPlayerScreen(
    profileId: Int,
    request: LiveTvPlaybackRequest,
    onBack: PlayerBackRequest,
    onSystemBackHandlerChanged: (handler: (() -> Unit)?) -> Unit,
    modifier: Modifier,
) {
    val repository = remember(profileId) { LiveTvRepository.forProfile(profileId) }
    val state by repository.uiState.collectAsStateWithLifecycle()
    var loadFinished by remember(repository) { mutableStateOf(false) }

    LaunchedEffect(repository) {
        try {
            repository.load()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            // The repository publishes the failure through uiState; keep the tuner mounted so a
            // later refresh can still resolve the channel.
        } finally {
            loadFinished = true
        }
    }

    val enabledSourceIds = remember(state.sources) {
        state.sources.filter { it.enabled }.map { it.id }.toSet()
    }
    val playableChannels = remember(state.channels, enabledSourceIds) {
        state.channels.filter { it.sourceId in enabledSourceIds }
    }
    val playableIds = remember(playableChannels) { playableChannels.map { it.id }.toSet() }
    val playlist = remember(request, playableIds) { liveTvPlaybackChannelIds(request, playableIds) }
    val channelById = remember(playableChannels) { playableChannels.associateBy { it.id } }
    val sourceNameById = remember(state.sources) { state.sources.associate { it.id to it.name } }

    var currentChannelId by rememberSaveable(profileId, request.channelId) {
        mutableStateOf(request.channelId)
    }
    val channel = channelById[currentChannelId]
    val switchChannel: (Boolean) -> Unit = { forward ->
        // `playlist` already contains only playable, enabled channel IDs.
        neighbouringLiveTvChannelId(playlist, currentChannelId, forward)?.let { currentChannelId = it }
    }

    if (channel == null) {
        if (state.isLoading || !loadFinished) {
            Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                NuvioLoadingIndicator(size = MaterialTheme.nuvio.icons.xl)
            }
        } else {
            LiveTvChannelUnavailable(
                modifier = modifier,
                onBack = onBack,
                onSystemBackHandlerChanged = onSystemBackHandlerChanged,
            )
        }
        return
    }

    val fallbackProviderName = stringResource(Res.string.iptv_nav_live_tv)
    val providerName = sourceNameById[channel.sourceId]?.takeIf { it.isNotBlank() } ?: fallbackProviderName

    // Opaque per-session parent identity: a live channel must never collide with a VOD meta id,
    // and no channel/source identifier is written into the player's metadata keys. One identity
    // covers the whole tuner session. Each channel gets a fresh runtime after the previous
    // controller's release handshake, including channels sharing a URL but using other headers.
    val sessionMetaId = remember(profileId, request.channelId) { "livetv-${UUID.randomUUID()}" }
    val canSwitch = playlist.size > 1 && currentChannelId in playlist
    key(channel.id) {
        PlayerScreen(
            profileId = profileId,
            title = channel.name,
            sourceUrl = channel.streamUrl,
            sourceHeaders = channel.headers,
            providerName = providerName,
            streamTitle = channel.name,
            streamSubtitle = channel.group.takeIf { it.isNotBlank() },
            onBack = onBack,
            onSystemBackHandlerChanged = onSystemBackHandlerChanged,
            modifier = modifier.fillMaxSize(),
            logo = channel.logoUrl,
            contentType = "tv",
            parentMetaId = sessionMetaId,
            parentMetaType = "tv",
            isLive = true,
            onPreviousChannel = if (canSwitch) ({ switchChannel(false) }) else null,
            onNextChannel = if (canSwitch) ({ switchChannel(true) }) else null,
        )
    }
}

/**
 * The requested channel vanished (source removed/disabled, or the playlist refreshed without it).
 * No player exists, so the release handshake completes immediately and the route pops normally.
 */
@Composable
private fun LiveTvChannelUnavailable(
    modifier: Modifier,
    onBack: PlayerBackRequest,
    onSystemBackHandlerChanged: (handler: (() -> Unit)?) -> Unit,
) {
    val navigateBack: () -> Unit = remember(onBack) {
        { onBack { onReleased, _ -> onReleased() } }
    }
    DisposableEffect(onSystemBackHandlerChanged, navigateBack) {
        onSystemBackHandlerChanged(navigateBack)
        onDispose { onSystemBackHandlerChanged(null) }
    }

    val tokens = MaterialTheme.nuvio
    NuvioScreen(modifier = modifier.fillMaxSize()) {
        stickyHeader {
            NuvioScreenHeader(
                title = stringResource(Res.string.iptv_nav_live_tv),
                onBack = navigateBack,
            )
        }
        item {
            NuvioSurfaceCard {
                Text(
                    text = stringResource(Res.string.iptv_channel_unavailable_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = tokens.colors.textPrimary,
                )
                Text(
                    text = stringResource(Res.string.iptv_channel_unavailable_message),
                    modifier = Modifier.padding(top = NuvioTokens.Space.s8),
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.colors.textSecondary,
                )
            }
        }
        item {
            NuvioPrimaryButton(
                text = stringResource(Res.string.iptv_channel_unavailable_back),
                onClick = navigateBack,
            )
        }
    }
}
