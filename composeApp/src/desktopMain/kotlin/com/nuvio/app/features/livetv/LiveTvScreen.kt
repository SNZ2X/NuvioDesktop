package com.nuvio.app.features.livetv

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.Chip
import com.nuvio.app.core.ui.LocalVerticalBarPadding
import com.nuvio.app.core.ui.withVerticalBar
import com.nuvio.app.core.ui.NuvioInputField
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.core.ui.NuvioPrimaryButton
import com.nuvio.app.core.ui.NuvioScreen
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.core.ui.NuvioSurfaceCard
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.desktopPageHorizontalPaddingForWidth
import com.nuvio.app.core.ui.nuvio
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_retry
import nuvio.composeapp.generated.resources.iptv_channels_count
import nuvio.composeapp.generated.resources.iptv_clear_filters
import nuvio.composeapp.generated.resources.iptv_empty_no_channels_subtitle
import nuvio.composeapp.generated.resources.iptv_empty_no_channels_title
import nuvio.composeapp.generated.resources.iptv_empty_no_matches_subtitle
import nuvio.composeapp.generated.resources.iptv_empty_no_matches_title
import nuvio.composeapp.generated.resources.iptv_empty_no_sources_subtitle
import nuvio.composeapp.generated.resources.iptv_empty_no_sources_title
import nuvio.composeapp.generated.resources.iptv_error_generic
import nuvio.composeapp.generated.resources.iptv_error_sources_partial
import nuvio.composeapp.generated.resources.iptv_error_title
import nuvio.composeapp.generated.resources.iptv_loading
import nuvio.composeapp.generated.resources.iptv_hidden_groups_title
import nuvio.composeapp.generated.resources.iptv_hidden_groups_subtitle
import nuvio.composeapp.generated.resources.iptv_hidden_groups_action
import nuvio.composeapp.generated.resources.iptv_manage_sources
import nuvio.composeapp.generated.resources.iptv_nav_live_tv
import nuvio.composeapp.generated.resources.iptv_refresh
import nuvio.composeapp.generated.resources.iptv_search_hint
import nuvio.composeapp.generated.resources.iptv_view_channels
import nuvio.composeapp.generated.resources.iptv_view_guide
import org.jetbrains.compose.resources.stringResource

private const val LiveTvClockTickMillis = 30_000L

internal enum class LiveTvBrowseMode { CHANNELS, GUIDE }

@Composable
internal actual fun LiveTvScreen(
    profileId: Int,
    onPlay: (LiveTvPlaybackRequest) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier,
) {
    val repository = remember(profileId) { LiveTvRepository.forProfile(profileId) }
    LiveTvScreenContent(profileId, repository, onPlay, onBack, modifier)
}

@Composable
internal fun LiveTvScreenContent(
    profileId: Int,
    repository: LiveTvRepository,
    onPlay: (LiveTvPlaybackRequest) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    groupPreferences: LiveTvGroupPreferences = remember(profileId) { LiveTvGroupPreferences(profileId) },
) {
    val state by repository.uiState.collectAsStateWithLifecycle()
    val hiddenGroupIds by groupPreferences.hiddenGroupIds.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val tokens = MaterialTheme.nuvio

    var query by rememberSaveable(profileId) { mutableStateOf("") }
    var sourceFilterId by rememberSaveable(profileId) { mutableStateOf<String?>(null) }
    var selectedGroupId by rememberSaveable(profileId) { mutableStateOf<String?>(null) }
    var showGroups by rememberSaveable(profileId) { mutableStateOf(false) }
    var groupSaveFailed by remember { mutableStateOf(false) }
    var modeName by rememberSaveable(profileId) { mutableStateOf(LiveTvBrowseMode.CHANNELS.name) }
    var showSources by rememberSaveable(profileId) { mutableStateOf(false) }
    var actionFailed by remember { mutableStateOf(false) }
    val mode = LiveTvBrowseMode.entries.firstOrNull { it.name == modeName } ?: LiveTvBrowseMode.CHANNELS

    LaunchedEffect(repository) {
        try {
            repository.load()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            actionFailed = true
        }
    }

    val sources = state.sources
    val enabledSources = remember(sources) { sources.filter { it.enabled } }
    val sourceNameById = remember(sources) { sources.associate { it.id to it.name } }
    val enabledSourceIds = remember(enabledSources) { enabledSources.map { it.id }.toSet() }
    // First identify groups from every enabled channel, including hidden and ungrouped ones.
    val enabledChannels = remember(state.channels, enabledSourceIds) {
        state.channels.filter { it.sourceId in enabledSourceIds }
    }
    val groups = remember(enabledChannels) { enabledChannels.liveTvBrowseGroups() }
    val hiddenGroupKeys = remember(groups, hiddenGroupIds) {
        groups.filter { it.id in hiddenGroupIds }.mapTo(HashSet()) { it.sourceId to it.name }
    }
    val visibleChannels = remember(enabledChannels, hiddenGroupKeys) {
        enabledChannels.filterNot { (it.sourceId to it.group) in hiddenGroupKeys }
    }
    val selectedGroup = remember(groups, selectedGroupId, hiddenGroupIds) {
        groups.firstOrNull { it.id == selectedGroupId && it.id !in hiddenGroupIds }
    }
    val effectiveSourceFilter = selectedGroup?.sourceId ?: sourceFilterId?.takeIf { it in enabledSourceIds }
    val filteredChannels = remember(visibleChannels, query, effectiveSourceFilter, selectedGroup) {
        visibleChannels.applyLiveTvFilters(query, effectiveSourceFilter, selectedGroup?.name)
    }
    val filteredIds = remember(filteredChannels) { filteredChannels.map { it.id } }
    val groupsHidden = remember(enabledChannels, visibleChannels, effectiveSourceFilter) {
        enabledChannels.any { effectiveSourceFilter == null || it.sourceId == effectiveSourceFilter } &&
            visibleChannels.none { effectiveSourceFilter == null || it.sourceId == effectiveSourceFilter }
    }
    val hasFilters = query.isNotBlank() || sourceFilterId != null || selectedGroupId != null
    val nowMs = rememberLiveTvNowMillis()

    val clearFilters: () -> Unit = {
        query = ""
        sourceFilterId = null
        selectedGroupId = null
    }
    val refresh: () -> Unit = {
        actionFailed = false
        scope.launch {
            try {
                repository.refresh()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                actionFailed = true
            }
        }
    }
    val partialFailures = state.sourceErrors.keys.count { it in enabledSourceIds }
    val manageGroups = { groupSaveFailed = false; showGroups = true }

    BoxWithConstraints(modifier = modifier.fillMaxSize().background(tokens.colors.background)) {
        val pagePadding = desktopPageHorizontalPaddingForWidth(maxWidth.value)
        val contentPadding = PaddingValues(
            start = pagePadding, end = pagePadding, top = tokens.spacing.screenTop,
        ).withVerticalBar()
        val groupListWidth = if (maxWidth < 840.dp) 210.dp else 260.dp
        CompositionLocalProvider(LocalVerticalBarPadding provides PaddingValues()) {
            Column(Modifier.fillMaxSize().padding(contentPadding)) {
                NuvioScreenHeader(
                    title = stringResource(Res.string.iptv_nav_live_tv),
                    onBack = onBack,
                ) {
                    IconButton(onClick = refresh, enabled = !state.isLoading) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = stringResource(Res.string.iptv_refresh),
                            tint = if (state.isLoading) tokens.colors.textDisabled else tokens.colors.textPrimary,
                        )
                    }
                    IconButton(onClick = { showSources = true }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.PlaylistPlay,
                            contentDescription = stringResource(Res.string.iptv_manage_sources),
                            tint = tokens.colors.textPrimary,
                        )
                    }
                }
                Row(Modifier.fillMaxWidth().weight(1f).padding(top = NuvioTokens.Space.s12)) {
                    LiveTvGroupNavigation(
                        sources = enabledSources,
                        groups = groups,
                        hiddenGroupIds = hiddenGroupIds,
                        selectedSourceId = effectiveSourceFilter,
                        selectedGroupId = selectedGroup?.id,
                        onSelect = { sourceId, groupId ->
                            sourceFilterId = sourceId
                            selectedGroupId = groupId
                        },
                        onManageGroups = manageGroups,
                        modifier = Modifier.width(groupListWidth).fillMaxHeight(),
                    )
                    Spacer(Modifier.width(NuvioTokens.Space.s16))
                    NuvioScreen(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        horizontalPadding = 0.dp,
                        topPadding = 0.dp,
                    ) {
                        item(key = "controls") {
                            Column(
                                Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(tokens.spacing.controlGap),
                            ) {
                                NuvioInputField(
                                    value = query,
                                    onValueChange = { query = it },
                                    placeholder = stringResource(Res.string.iptv_search_hint),
                                    modifier = Modifier.fillMaxWidth().testTag("live-tv-channel-search"),
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8)) {
                                    Chip(
                                        label = stringResource(Res.string.iptv_view_channels),
                                        selected = mode == LiveTvBrowseMode.CHANNELS,
                                        onClick = { modeName = LiveTvBrowseMode.CHANNELS.name },
                                    )
                                    Chip(
                                        label = stringResource(Res.string.iptv_view_guide),
                                        selected = mode == LiveTvBrowseMode.GUIDE,
                                        onClick = { modeName = LiveTvBrowseMode.GUIDE.name },
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12)) {
                                    Text(
                                        text = stringResource(Res.string.iptv_channels_count, filteredChannels.size),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = tokens.colors.textMuted,
                                    )
                                    if (hasFilters) {
                                        LiveTvTextAction(stringResource(Res.string.iptv_clear_filters), clearFilters)
                                    }
                                }
                                if (partialFailures > 0) {
                                    Text(
                                        stringResource(Res.string.iptv_error_sources_partial, partialFailures),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = tokens.colors.warning,
                                    )
                                }
                                if (actionFailed && state.error == null) {
                                    Text(
                                        stringResource(Res.string.iptv_error_generic),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = tokens.colors.danger,
                                    )
                                }
                            }
                        }
                        liveTvChannelFeed(
                            state = state,
                            sourceNameById = sourceNameById,
                            filteredChannels = filteredChannels,
                            filteredIds = filteredIds,
                            mode = mode,
                            nowMs = nowMs,
                            enabledSourceCount = enabledSources.size,
                            hasSources = sources.isNotEmpty(),
                            groupsHidden = groupsHidden,
                            onPlay = onPlay,
                            onRefresh = refresh,
                            onClearFilters = clearFilters,
                            onManageSources = { showSources = true },
                            onManageGroups = manageGroups,
                        )
                    }
                }
            }
        }
    }

    if (showGroups) {
        LiveTvGroupsDialog(
            sources = enabledSources,
            groups = groups,
            hiddenGroupIds = hiddenGroupIds,
            onHiddenGroupIdsChange = { next ->
                try {
                    groupPreferences.setHiddenGroupIds(next)
                    if (selectedGroupId in next) selectedGroupId = null
                    groupSaveFailed = false
                } catch (_: Throwable) {
                    groupSaveFailed = true
                }
            },
            onDismiss = { showGroups = false },
            errorMessage = if (groupSaveFailed) stringResource(Res.string.iptv_error_generic) else null,
        )
    }

    if (showSources) {
        LiveTvSourcesDialog(
            sources = sources,
            sourceErrors = state.sourceErrors,
            onDismiss = { showSources = false },
            onSave = { source -> repository.saveSource(source) },
            onDelete = { sourceId -> repository.removeSource(sourceId) },
            onSetEnabled = { sourceId, enabled -> repository.setSourceEnabled(sourceId, enabled) },
        )
    }
}

private fun LazyListScope.liveTvChannelFeed(
    state: LiveTvState,
    sourceNameById: Map<String, String>,
    filteredChannels: List<LiveTvChannel>,
    filteredIds: List<String>,
    mode: LiveTvBrowseMode,
    nowMs: Long,
    enabledSourceCount: Int,
    hasSources: Boolean,
    groupsHidden: Boolean,
    onPlay: (LiveTvPlaybackRequest) -> Unit,
    onRefresh: () -> Unit,
    onClearFilters: () -> Unit,
    onManageSources: () -> Unit,
    onManageGroups: () -> Unit,
) {
    // A failed refresh keeps the previous channels usable; surface the failure without replacing
    // the list the user is browsing. Per-source failures are already reported above.
    val staleError = state.error
    if (staleError != null && state.channels.isNotEmpty() && state.sourceErrors.isEmpty()) {
        item(key = "stale-error") {
            Text(
                text = localizedLiveTvMessage(staleError)
                    ?: stringResource(Res.string.iptv_error_generic),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.nuvio.colors.warning,
            )
        }
    }

    when {
        state.isLoading && state.channels.isEmpty() -> item(key = "loading") {
            LiveTvNotice(
                title = stringResource(Res.string.iptv_loading),
                message = null,
                loading = true,
            )
        }

        state.error != null && state.channels.isEmpty() -> item(key = "error") {
            LiveTvNotice(
                title = stringResource(Res.string.iptv_error_title),
                message = localizedLiveTvMessage(state.error)
                    ?: stringResource(Res.string.iptv_error_generic),
                actionText = stringResource(Res.string.action_retry),
                onAction = onRefresh,
            )
        }

        state.sources.isEmpty() -> item(key = "no-sources") {
            LiveTvNotice(
                title = stringResource(Res.string.iptv_empty_no_sources_title),
                message = stringResource(Res.string.iptv_empty_no_sources_subtitle),
                actionText = stringResource(Res.string.iptv_manage_sources),
                onAction = onManageSources,
            )
        }

        state.channels.isEmpty() || enabledSourceCount == 0 -> item(key = "no-channels") {
            LiveTvNotice(
                title = stringResource(Res.string.iptv_empty_no_channels_title),
                message = stringResource(Res.string.iptv_empty_no_channels_subtitle),
                actionText = stringResource(
                    if (hasSources && enabledSourceCount == 0) {
                        Res.string.iptv_manage_sources
                    } else {
                        Res.string.iptv_refresh
                    },
                ),
                onAction = if (hasSources && enabledSourceCount == 0) onManageSources else onRefresh,
            )
        }

        groupsHidden -> item(key = "hidden-groups") {
            LiveTvNotice(
                title = stringResource(Res.string.iptv_hidden_groups_title),
                message = stringResource(Res.string.iptv_hidden_groups_subtitle),
                actionText = stringResource(Res.string.iptv_hidden_groups_action),
                onAction = onManageGroups,
            )
        }

        filteredChannels.isEmpty() -> item(key = "no-matches") {
            LiveTvNotice(
                title = stringResource(Res.string.iptv_empty_no_matches_title),
                message = stringResource(Res.string.iptv_empty_no_matches_subtitle),
                actionText = stringResource(Res.string.iptv_clear_filters),
                onAction = onClearFilters,
            )
        }

        mode == LiveTvBrowseMode.GUIDE -> {
            items(
                items = filteredChannels,
                key = { channel -> "guide-${channel.id}" },
            ) { channel ->
                LiveTvGuideCard(
                    channel = channel,
                    sourceName = sourceNameById[channel.sourceId],
                    programmes = state.programmes[channel.id].orEmpty(),
                    nowMs = nowMs,
                    onPlay = { onPlay(LiveTvPlaybackRequest(channel.id, filteredIds)) },
                )
            }
        }

        else -> items(
            items = filteredChannels,
            key = { channel -> "channel-${channel.id}" },
        ) { channel ->
            LiveTvChannelRow(
                channel = channel,
                sourceName = sourceNameById[channel.sourceId],
                programmes = state.programmes[channel.id].orEmpty(),
                nowMs = nowMs,
                onPlay = { onPlay(LiveTvPlaybackRequest(channel.id, filteredIds)) },
            )
        }
    }
}

@Composable
internal fun LiveTvNotice(
    title: String,
    message: String?,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val tokens = MaterialTheme.nuvio
    NuvioSurfaceCard(modifier = modifier.padding(top = tokens.spacing.listGap)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = tokens.colors.textPrimary,
        )
        if (loading) {
            NuvioLoadingIndicator(
                modifier = Modifier.padding(top = NuvioTokens.Space.s12),
                size = tokens.icons.xl,
            )
        }
        if (message != null) {
            Text(
                text = message,
                modifier = Modifier.padding(top = NuvioTokens.Space.s8),
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textSecondary,
                textAlign = TextAlign.Start,
            )
        }
        if (actionText != null && onAction != null) {
            NuvioPrimaryButton(
                text = actionText,
                onClick = onAction,
                modifier = Modifier.padding(top = NuvioTokens.Space.s12),
            )
        }
    }
}

@Composable
internal fun LiveTvTextAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = modifier
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = NuvioTokens.Space.s2),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.nuvio.colors.accent,
    )
}

/** Now/next and guide progress advance on a coarse tick instead of per-frame recomposition. */
@Composable
private fun rememberLiveTvNowMillis(): Long {
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(LiveTvClockTickMillis)
            nowMs = System.currentTimeMillis()
        }
    }
    return nowMs
}
