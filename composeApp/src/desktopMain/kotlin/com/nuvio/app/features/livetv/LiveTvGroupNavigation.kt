package com.nuvio.app.features.livetv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.*
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.pluralStringResource

/** Independent, bounded list: sources select a playlist, chevrons only expand/collapse it. */
@Composable
internal fun LiveTvGroupNavigation(
    sources: List<LiveTvSource>,
    groups: List<LiveTvBrowseGroup>,
    hiddenGroupIds: Set<String>,
    selectedSourceId: String?,
    selectedGroupId: String?,
    onSelect: (sourceId: String?, groupId: String?) -> Unit,
    onManageGroups: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    var query by remember { mutableStateOf("") }
    var collapsedSources by remember { mutableStateOf(emptySet<String>()) }
    val visibleGroups = remember(groups, hiddenGroupIds) { groups.filterNot { it.id in hiddenGroupIds } }
    val groupsBySource = remember(visibleGroups) { visibleGroups.groupBy { it.sourceId } }
    val needle = query.trim()
    val ungrouped = stringResource(Res.string.iptv_groups_ungrouped)
    val listState = rememberLazyListState()
    Column(
        modifier.testTag("live-tv-groups-panel").clip(tokens.shapes.card)
            .background(tokens.colors.surface).padding(NuvioTokens.Space.s16),
        verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(Res.string.iptv_groups_title),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                color = tokens.colors.textPrimary,
            )
            IconButton(onClick = onManageGroups, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Rounded.Visibility, stringResource(Res.string.iptv_groups_manage), tint = tokens.colors.textSecondary)
            }
        }
        NuvioInputField(query, { query = it }, stringResource(Res.string.iptv_groups_search))
        Box(Modifier.fillMaxWidth().weight(1f).testTag("live-tv-groups-list")) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(end = NuvioTokens.Space.s8),
                verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s4),
            ) {
                item(key = "all-channels") {
                    LiveTvGroupRow(
                        label = stringResource(Res.string.iptv_groups_all_channels),
                        count = visibleGroups.sumOf { it.channelCount },
                        selected = selectedSourceId == null && selectedGroupId == null,
                        onClick = { onSelect(null, null) },
                    )
                }
                sources.forEach { source ->
                    val sourceGroups = groupsBySource[source.id].orEmpty()
                    val matching = if (needle.isBlank() || source.name.contains(needle, true)) sourceGroups
                        else sourceGroups.filter { it.name.ifEmpty { ungrouped }.contains(needle, true) }
                    if (needle.isNotBlank() && matching.isEmpty() && !source.name.contains(needle, true)) return@forEach
                    val expanded = needle.isNotBlank() || source.id !in collapsedSources
                    item(key = "source-${source.id}") {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                enabled = needle.isBlank(),
                                onClick = {
                                    collapsedSources = if (source.id in collapsedSources) collapsedSources - source.id
                                        else collapsedSources + source.id
                                },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    if (expanded) Icons.Rounded.ExpandMore else Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                                    stringResource(
                                        if (expanded) Res.string.iptv_groups_collapse_source else Res.string.iptv_groups_expand_source,
                                        source.name,
                                    ),
                                    tint = tokens.colors.textMuted,
                                )
                            }
                            LiveTvGroupRow(
                                label = source.name,
                                count = sourceGroups.sumOf { it.channelCount },
                                selected = selectedSourceId == source.id && selectedGroupId == null,
                                onClick = { onSelect(source.id, null) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    if (expanded) {
                        items(matching, key = { "group-${it.id}" }) { group ->
                            LiveTvGroupRow(
                                label = group.name.ifEmpty { stringResource(Res.string.iptv_groups_ungrouped) },
                                count = group.channelCount,
                                selected = selectedGroupId == group.id,
                                onClick = { onSelect(group.sourceId, group.id) },
                                modifier = Modifier.padding(start = NuvioTokens.Space.s16),
                            )
                        }
                    }
                }
            }
            NuvioDesktopVerticalScrollbar(
                state = listState,
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = NuvioTokens.Space.s8, vertical = NuvioTokens.Space.s8)
                .testTag("live-tv-groups-footer"),
        ) {
            DialogButton(
                text = stringResource(Res.string.iptv_groups_manage),
                onClick = onManageGroups,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun LiveTvGroupRow(
    label: String,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val background = when {
        selected -> tokens.colors.overlaySelected
        hovered -> tokens.colors.overlayHover
        else -> Color.Transparent
    }
    Row(
        modifier.fillMaxWidth()
            .background(background, tokens.shapes.compactCard)
            .border(tokens.borders.thin, if (focused) tokens.colors.focusRing else Color.Transparent, tokens.shapes.compactCard)
            .hoverable(interaction)
            .selectable(selected, interaction, indication = null, role = Role.Tab, onClick = onClick)
            .padding(horizontal = NuvioTokens.Space.s10, vertical = NuvioTokens.Space.s12),
        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) tokens.colors.accent else tokens.colors.textSecondary,
        )
        Text(count.toString(), style = MaterialTheme.typography.labelSmall, color = tokens.colors.textMuted)
    }
}

/** Always shows hidden groups too. Bulk changes affect only the currently listed search results. */
@Composable
internal fun LiveTvGroupsDialog(
    sources: List<LiveTvSource>,
    groups: List<LiveTvBrowseGroup>,
    hiddenGroupIds: Set<String>,
    onHiddenGroupIdsChange: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
    errorMessage: String? = null,
) {
    val tokens = MaterialTheme.nuvio
    var query by remember { mutableStateOf("") }
    val sourceNames = remember(sources) { sources.associate { it.id to it.name } }
    val ungrouped = stringResource(Res.string.iptv_groups_ungrouped)
    val listed = remember(groups, query, sourceNames, ungrouped) {
        val needle = query.trim()
        groups.filter {
            needle.isEmpty() || it.name.ifEmpty { ungrouped }.contains(needle, true) || sourceNames[it.sourceId].orEmpty().contains(needle, true)
        }
    }
    val listedIds = remember(listed) { listed.mapTo(HashSet()) { it.id } }
    val listState = rememberLazyListState()
    DialogSurface(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.iptv_groups_manage),
        message = stringResource(Res.string.iptv_groups_hint),
        modifier = Modifier.testTag("live-tv-group-dialog"),
    ) {
        NuvioInputField(query, { query = it }, stringResource(Res.string.iptv_groups_search))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s16)) {
            DialogButton(
                text = stringResource(Res.string.iptv_groups_show_listed),
                onClick = { onHiddenGroupIdsChange(hiddenGroupIds - listedIds) },
                modifier = Modifier.weight(1f),
                enabled = listed.isNotEmpty(),
            )
            DialogButton(
                text = stringResource(Res.string.iptv_groups_hide_listed),
                onClick = { onHiddenGroupIdsChange(hiddenGroupIds + listedIds) },
                modifier = Modifier.weight(1f),
                enabled = listed.isNotEmpty(),
            )
        }
        if (listed.isEmpty()) {
            Text(stringResource(Res.string.iptv_groups_empty), color = tokens.colors.textMuted)
        } else {
            Box(Modifier.fillMaxWidth().heightIn(max = 340.dp)) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().padding(end = NuvioTokens.Space.s12),
                    verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s4),
                ) {
                    items(listed, key = { it.id }) { group ->
                        val visible = group.id !in hiddenGroupIds
                        Row(
                            Modifier.fillMaxWidth()
                                .toggleable(visible, role = Role.Checkbox) {
                                    onHiddenGroupIdsChange(if (it) hiddenGroupIds - group.id else hiddenGroupIds + group.id)
                                }
                                .padding(vertical = NuvioTokens.Space.s4),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = visible,
                                onCheckedChange = null,
                                colors = CheckboxDefaults.colors(
                                    checkedColor = tokens.colors.accent,
                                    checkmarkColor = tokens.colors.onAccent,
                                ),
                            )
                            Column(Modifier.weight(1f).padding(start = NuvioTokens.Space.s12)) {
                                Text(
                                    group.name.ifEmpty { stringResource(Res.string.iptv_groups_ungrouped) },
                                    color = tokens.colors.textPrimary,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    sourceNames[group.sourceId].orEmpty() + " · " + pluralStringResource(Res.plurals.iptv_groups_channel_count, group.channelCount, group.channelCount),
                                    color = tokens.colors.textMuted,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
                NuvioDesktopVerticalScrollbar(state = listState, modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight())
            }
        }
        if (errorMessage != null) {
            Text(errorMessage, color = tokens.colors.danger, style = MaterialTheme.typography.bodySmall)
        }
        DialogButtons { DialogButton(stringResource(Res.string.action_close), onDismiss) }
    }
}
