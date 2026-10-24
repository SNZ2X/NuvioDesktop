package com.nuvio.app.features.livetv

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.format.formatLocalTime
import com.nuvio.app.core.ui.NuvioAsyncImage
import com.nuvio.app.core.ui.NuvioProgressBar
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.iptv_guide_empty
import nuvio.composeapp.generated.resources.iptv_next
import nuvio.composeapp.generated.resources.iptv_no_now_next
import nuvio.composeapp.generated.resources.iptv_now
import nuvio.composeapp.generated.resources.iptv_play_channel
import nuvio.composeapp.generated.resources.iptv_programme_untitled
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun LiveTvChannelRow(
    channel: LiveTvChannel,
    sourceName: String?,
    programmes: List<LiveTvProgramme>,
    nowMs: Long,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val nowNext = programmes.nowAndNext(nowMs)
    val current = nowNext.current
    val next = nowNext.next
    val description = stringResource(Res.string.iptv_play_channel, channel.name)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(tokens.shapes.compactCard)
            .clickable(onClickLabel = description, role = Role.Button, onClick = onPlay),
        color = tokens.colors.surfaceCard,
        shape = tokens.shapes.compactCard,
        border = BorderStroke(tokens.borders.hairline, tokens.colors.borderSubtle),
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = NuvioTokens.Space.s12,
                vertical = NuvioTokens.Space.s10,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
        ) {
            LiveTvChannelLogo(channel = channel)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s2),
            ) {
                Text(
                    text = channel.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = tokens.colors.textPrimary,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                LiveTvNowNextLines(
                    current = current,
                    next = next,
                    nowMs = nowMs,
                )
                val groupLabel = listOfNotNull(
                    sourceName?.takeIf { it.isNotBlank() },
                    channel.group.takeIf { it.isNotBlank() },
                ).joinToString(" · ")
                if (groupLabel.isNotEmpty()) {
                    Text(
                        text = groupLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = tokens.colors.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(
                imageVector = Icons.Rounded.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(tokens.icons.md),
                tint = tokens.colors.textPrimary,
            )
        }
    }
}

@Composable
private fun LiveTvNowNextLines(
    current: LiveTvProgramme?,
    next: LiveTvProgramme?,
    nowMs: Long,
) {
    val tokens = MaterialTheme.nuvio
    if (current == null && next == null) {
        Text(
            text = stringResource(Res.string.iptv_no_now_next),
            style = MaterialTheme.typography.bodySmall,
            color = tokens.colors.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        return
    }
    if (current != null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(Res.string.iptv_now),
                style = MaterialTheme.typography.labelSmall,
                color = tokens.colors.textMuted,
            )
            Spacer(modifier = Modifier.width(NuvioTokens.Space.s6))
            Text(
                text = current.title.ifBlank { stringResource(Res.string.iptv_programme_untitled) },
                modifier = Modifier.weight(1f, fill = false),
                style = MaterialTheme.typography.bodySmall,
                color = tokens.colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        NuvioProgressBar(
            progress = current.progressAt(nowMs),
            modifier = Modifier.padding(top = NuvioTokens.Space.s4),
            height = NuvioTokens.Space.s2,
        )
    }
    if (next != null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(Res.string.iptv_next),
                style = MaterialTheme.typography.labelSmall,
                color = tokens.colors.textMuted,
            )
            Spacer(modifier = Modifier.width(NuvioTokens.Space.s6))
            Text(
                text = formatLocalTime(next.startEpochMs),
                style = MaterialTheme.typography.labelSmall,
                color = tokens.colors.textMuted,
            )
            Spacer(modifier = Modifier.width(NuvioTokens.Space.s6))
            Text(
                text = next.title.ifBlank { stringResource(Res.string.iptv_programme_untitled) },
                modifier = Modifier.weight(1f, fill = false),
                style = MaterialTheme.typography.bodySmall,
                color = tokens.colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun LiveTvChannelLogo(
    channel: LiveTvChannel,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    Box(
        modifier = modifier
            .size(tokens.icons.xxl + NuvioTokens.Space.s8)
            .clip(tokens.shapes.compactCard),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.Tv,
            contentDescription = null,
            modifier = Modifier.size(tokens.icons.md),
            tint = tokens.colors.textMuted,
        )
        channel.logoUrl?.takeIf { it.isNotBlank() }?.let { logo ->
            NuvioAsyncImage(
                model = logo,
                contentDescription = null,
                modifier = Modifier.size(tokens.icons.xxl + NuvioTokens.Space.s8),
                contentScale = androidx.compose.ui.layout.ContentScale.Fit,
            )
        }
    }
}

@Composable
internal fun LiveTvGuideCard(
    channel: LiveTvChannel,
    sourceName: String?,
    programmes: List<LiveTvProgramme>,
    nowMs: Long,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val window = programmes.guideWindow(nowMs, LiveTvGuideWindowLimit)
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = tokens.colors.surfaceCard,
        shape = tokens.shapes.compactCard,
        border = BorderStroke(tokens.borders.hairline, tokens.colors.borderSubtle),
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = NuvioTokens.Space.s12,
                vertical = NuvioTokens.Space.s10,
            ),
            verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s6),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button, onClick = onPlay),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
            ) {
                LiveTvChannelLogo(channel = channel)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = channel.name,
                        style = MaterialTheme.typography.bodyLarge,
                        color = tokens.colors.textPrimary,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val subtitle = listOfNotNull(
                        sourceName?.takeIf { it.isNotBlank() },
                        channel.group.takeIf { it.isNotBlank() },
                    ).joinToString(" · ")
                    if (subtitle.isNotEmpty()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = tokens.colors.textMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = stringResource(Res.string.iptv_play_channel, channel.name),
                    modifier = Modifier.size(tokens.icons.md),
                    tint = tokens.colors.textPrimary,
                )
            }
            if (window.isEmpty()) {
                Text(
                    text = stringResource(Res.string.iptv_guide_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.textMuted,
                )
            } else {
                window.forEach { programme ->
                    LiveTvProgrammeRow(programme = programme, nowMs = nowMs)
                }
            }
        }
    }
}

@Composable
private fun LiveTvProgrammeRow(
    programme: LiveTvProgramme,
    nowMs: Long,
) {
    val tokens = MaterialTheme.nuvio
    val isCurrent = nowMs >= programme.startEpochMs && nowMs < programme.endEpochMs
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = NuvioTokens.Space.s24),
        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s10),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = formatLocalTime(programme.startEpochMs),
            modifier = Modifier.width(LiveTvGuideTimeWidth),
            style = MaterialTheme.typography.labelSmall,
            color = if (isCurrent) tokens.colors.accentStrong else tokens.colors.textMuted,
            maxLines = 1,
        )
        Text(
            text = programme.title.ifBlank { stringResource(Res.string.iptv_programme_untitled) },
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = if (isCurrent) tokens.colors.textPrimary else tokens.colors.textSecondary,
            fontWeight = if (isCurrent) FontWeight.SemiBold else null,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = formatLocalTime(programme.endEpochMs),
            style = MaterialTheme.typography.labelSmall,
            color = tokens.colors.textMuted,
            maxLines = 1,
        )
    }
    if (isCurrent) {
        NuvioProgressBar(
            progress = programme.progressAt(nowMs),
            modifier = Modifier.padding(bottom = NuvioTokens.Space.s2),
            height = NuvioTokens.Space.s2,
        )
    }
}

private val LiveTvGuideTimeWidth = 56.dp
private const val LiveTvGuideWindowLimit = 6
