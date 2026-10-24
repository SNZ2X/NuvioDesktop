package com.nuvio.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.nuvio.app.core.ui.isFullscreenActionActive
import com.nuvio.app.core.ui.toggleFullscreenAction
import com.nuvio.app.features.livetv.LiveTvLaunch
import com.nuvio.app.features.livetv.LiveTvLaunchStore
import com.nuvio.app.features.livetv.LiveTvPlayerScreen
import com.nuvio.app.features.livetv.LiveTvScreen
import com.nuvio.app.features.player.PlayerReleaseBeforeBack
import com.nuvio.app.navigation.LiveTvPlayerRoute
import com.nuvio.app.navigation.LiveTvRoute
import com.nuvio.app.navigation.NuvioNavigator

@Composable
internal fun LiveTvDestination(route: LiveTvRoute, navController: NuvioNavigator) {
    val onBack = rememberGuardedPopBackStack(navController, route)
    LiveTvScreen(
        profileId = route.profileId,
        onPlay = { request ->
            val launchId = LiveTvLaunchStore.put(LiveTvLaunch(route.profileId, request))
            navController.navigate(LiveTvPlayerRoute(launchId))
        },
        onBack = onBack,
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
internal fun LiveTvPlayerDestination(
    route: LiveTvPlayerRoute,
    navController: NuvioNavigator,
    onSystemBackHandlerChanged: (handler: (() -> Unit)?) -> Unit,
) {
    val launch = remember(route.launchId) { LiveTvLaunchStore.get(route.launchId) }
    val popBack = rememberGuardedPopBackStack(navController, route)
    if (launch == null) {
        LaunchedEffect(route.launchId) { popBack() }
        Box(Modifier.fillMaxSize())
        return
    }
    val currentFullscreen = isFullscreenActionActive()
    val initialFullscreen = remember { currentFullscreen }
    val onBackBase = rememberGuardedPlayerPopBackStack(navController, route)
    val onBack = remember(onBackBase, currentFullscreen, initialFullscreen) {
        { releaseBeforeBack: PlayerReleaseBeforeBack ->
            if (currentFullscreen != initialFullscreen) toggleFullscreenAction()
            onBackBase(releaseBeforeBack)
        }
    }
    LiveTvPlayerScreen(
        profileId = launch.profileId,
        request = launch.request,
        onBack = onBack,
        onSystemBackHandlerChanged = onSystemBackHandlerChanged,
        modifier = Modifier.fillMaxSize(),
    )
}
