package com.nuvio.app.core.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun NuvioModeChip(
    label: String,
    selected: Boolean,
    loading: Boolean = false,
    error: Boolean = false,
    onClick: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .selectable(selected = selected, role = Role.Tab, onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = if (selected) colorScheme.primaryContainer else colorScheme.surfaceContainerLow,
        border = if (selected) BorderStroke(1.dp, colorScheme.primary.copy(alpha = 0.45f)) else null,
    ) {
        Text(
            text = label,
            modifier = Modifier
                .padding(horizontal = 14.dp, vertical = 8.dp)
                .shimmer(loading),
            style = MaterialTheme.typography.labelMedium,
            color = when {
                error -> colorScheme.error
                selected -> colorScheme.onPrimaryContainer
                else -> colorScheme.onSurfaceVariant
            },
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
