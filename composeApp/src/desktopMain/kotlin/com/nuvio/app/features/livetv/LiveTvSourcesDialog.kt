package com.nuvio.app.features.livetv

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.LocalAwtWindow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.Chip
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogSurface
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_cancel
import nuvio.composeapp.generated.resources.action_close
import nuvio.composeapp.generated.resources.action_delete
import nuvio.composeapp.generated.resources.iptv_add_source
import nuvio.composeapp.generated.resources.iptv_choose_epg_file
import nuvio.composeapp.generated.resources.iptv_choose_file
import nuvio.composeapp.generated.resources.iptv_edit_source
import nuvio.composeapp.generated.resources.iptv_epg_hint
import nuvio.composeapp.generated.resources.iptv_error_epg_invalid
import nuvio.composeapp.generated.resources.iptv_error_file_required
import nuvio.composeapp.generated.resources.iptv_error_generic
import nuvio.composeapp.generated.resources.iptv_error_host_required
import nuvio.composeapp.generated.resources.iptv_error_name_required
import nuvio.composeapp.generated.resources.iptv_error_password_required
import nuvio.composeapp.generated.resources.iptv_error_url_required
import nuvio.composeapp.generated.resources.iptv_error_username_required
import nuvio.composeapp.generated.resources.iptv_field_epg
import nuvio.composeapp.generated.resources.iptv_field_password
import nuvio.composeapp.generated.resources.iptv_field_playlist_file
import nuvio.composeapp.generated.resources.iptv_field_playlist_url
import nuvio.composeapp.generated.resources.iptv_field_source_name
import nuvio.composeapp.generated.resources.iptv_field_username
import nuvio.composeapp.generated.resources.iptv_field_xtream_host
import nuvio.composeapp.generated.resources.iptv_save
import nuvio.composeapp.generated.resources.iptv_saving
import nuvio.composeapp.generated.resources.iptv_source_delete
import nuvio.composeapp.generated.resources.iptv_source_disabled
import nuvio.composeapp.generated.resources.iptv_source_edit
import nuvio.composeapp.generated.resources.iptv_source_enabled
import nuvio.composeapp.generated.resources.iptv_source_kind_m3u_file
import nuvio.composeapp.generated.resources.iptv_source_kind_m3u_url
import nuvio.composeapp.generated.resources.iptv_source_kind_xtream
import nuvio.composeapp.generated.resources.iptv_source_remove_message
import nuvio.composeapp.generated.resources.iptv_source_remove_title
import nuvio.composeapp.generated.resources.iptv_sources_empty
import nuvio.composeapp.generated.resources.iptv_source_last_error
import nuvio.composeapp.generated.resources.iptv_sources_title
import org.jetbrains.compose.resources.stringResource

private val SourceListMaxHeight = 240.dp
private val SourceEditorMaxHeight = 520.dp
private val SourceEditorScrollMaxHeight = 420.dp

@Composable
internal fun LiveTvSourcesDialog(
    sources: List<LiveTvSource>,
    sourceErrors: Map<String, String>,
    onDismiss: () -> Unit,
    onSave: suspend (LiveTvSource) -> Unit,
    onDelete: suspend (String) -> Unit,
    onSetEnabled: suspend (String, Boolean) -> Unit,
) {
    var draft by remember { mutableStateOf<LiveTvSourceDraft?>(null) }
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }
    var fieldError by remember { mutableStateOf<LiveTvSourceFieldError?>(null) }
    var failureText by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val genericFailure = stringResource(Res.string.iptv_error_generic)

    fun runOperation(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        failureText = null
        scope.launch {
            try {
                block()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                failureText = genericFailure
            } finally {
                busy = false
            }
        }
    }

    val activeDraft = draft
    val deleteId = pendingDeleteId
    if (activeDraft != null) {
        LiveTvSourceEditor(
            draft = activeDraft,
            isNew = sources.none { it.id == activeDraft.id },
            fieldError = fieldError,
            failureText = failureText,
            busy = busy,
            onDraftChange = {
                draft = it
                if (fieldError != null) fieldError = null
            },
            onDismiss = {
                draft = null
                fieldError = null
                failureText = null
            },
            onSave = {
                when (val validation = validateLiveTvSourceDraft(activeDraft)) {
                    is LiveTvSourceValidation.Invalid -> fieldError = validation.field
                    is LiveTvSourceValidation.Valid -> runOperation {
                        onSave(validation.source)
                        draft = null
                    }
                }
            },
        )
    } else if (deleteId == null) {
        DialogSurface(
            onDismissRequest = { if (!busy) onDismiss() },
            title = stringResource(Res.string.iptv_sources_title),
        ) {
            if (sources.isEmpty()) {
                Text(
                    text = stringResource(Res.string.iptv_sources_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.nuvio.colors.textSecondary,
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = SourceListMaxHeight),
                    verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
                ) {
                    items(sources, key = { it.id }) { source ->
                        LiveTvSourceRow(
                            source = source,
                            sourceError = localizedLiveTvMessage(sourceErrors[source.id]),
                            busy = busy,
                            onEdit = {
                                fieldError = null
                                failureText = null
                                draft = source.toDraft()
                            },
                            onToggle = { enabled ->
                                runOperation { onSetEnabled(source.id, enabled) }
                            },
                            onDelete = { pendingDeleteId = source.id },
                        )
                    }
                }
            }
            if (failureText != null) {
                Text(
                    text = failureText.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.nuvio.colors.danger,
                )
            }
            DialogButtons {
                DialogButton(
                    text = stringResource(Res.string.action_close),
                    onClick = onDismiss,
                    enabled = !busy,
                )
                DialogButton(
                    text = stringResource(Res.string.iptv_add_source),
                    onClick = {
                        fieldError = null
                        failureText = null
                        draft = LiveTvSourceDraft(id = newLiveTvSourceId())
                    },
                    style = DialogButtonStyle.Primary,
                    enabled = !busy,
                )
            }
        }
    }

    if (deleteId != null) {
        DialogSurface(
            onDismissRequest = { if (!busy) pendingDeleteId = null },
            title = stringResource(Res.string.iptv_source_remove_title),
            message = stringResource(Res.string.iptv_source_remove_message),
        ) {
            DialogButtons {
                DialogButton(
                    text = stringResource(Res.string.action_cancel),
                    onClick = { pendingDeleteId = null },
                    enabled = !busy,
                )
                DialogButton(
                    text = stringResource(Res.string.action_delete),
                    onClick = {
                        runOperation {
                            onDelete(deleteId)
                            pendingDeleteId = null
                            draft = null
                        }
                    },
                    style = DialogButtonStyle.Destructive,
                    loading = busy,
                )
            }
        }
    }
}

@Composable
private fun LiveTvSourceRow(
    source: LiveTvSource,
    sourceError: String?,
    busy: Boolean,
    onEdit: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val editLabel = stringResource(Res.string.iptv_source_edit)
    val deleteLabel = stringResource(Res.string.iptv_source_delete)
    val enabledLabel = if (source.enabled) {
        stringResource(Res.string.iptv_source_enabled)
    } else {
        stringResource(Res.string.iptv_source_disabled)
    }
    val kindLabel = source.kind.displayLabel()

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = tokens.colors.surfaceCard,
        shape = tokens.shapes.compactCard,
        border = BorderStroke(tokens.borders.hairline, tokens.colors.borderSubtle),
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = NuvioTokens.Space.s10,
                vertical = NuvioTokens.Space.s8,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s6),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable(enabled = !busy, role = Role.Button, onClick = onEdit),
                verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s2),
            ) {
                Text(
                    text = source.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = tokens.colors.textPrimary,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "$kindLabel · $enabledLabel",
                    style = MaterialTheme.typography.labelSmall,
                    color = tokens.colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (sourceError != null) {
                    Text(
                        text = stringResource(Res.string.iptv_source_last_error, sourceError),
                        style = MaterialTheme.typography.labelSmall,
                        color = tokens.colors.warning,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = onEdit, enabled = !busy) {
                Icon(
                    imageVector = Icons.Rounded.Edit,
                    contentDescription = editLabel,
                    tint = tokens.colors.textMuted,
                )
            }
            IconButton(onClick = onDelete, enabled = !busy) {
                Icon(
                    imageVector = Icons.Rounded.Delete,
                    contentDescription = deleteLabel,
                    tint = tokens.colors.textMuted,
                )
            }
            Switch(
                checked = source.enabled,
                onCheckedChange = onToggle,
                enabled = !busy,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = tokens.colors.onAccent,
                    checkedTrackColor = tokens.colors.accent,
                    uncheckedThumbColor = tokens.colors.textMuted,
                    uncheckedTrackColor = tokens.colors.borderDefault,
                ),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalComposeUiApi::class)
@Composable
private fun LiveTvSourceEditor(
    draft: LiveTvSourceDraft,
    isNew: Boolean,
    fieldError: LiveTvSourceFieldError?,
    failureText: String?,
    busy: Boolean,
    onDraftChange: (LiveTvSourceDraft) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val window = LocalAwtWindow.current
    val playlistFileLabel = stringResource(Res.string.iptv_field_playlist_file)
    val epgLabel = stringResource(Res.string.iptv_field_epg)

    DialogSurface(
        onDismissRequest = { if (!busy) onDismiss() },
        title = stringResource(
            if (isNew) Res.string.iptv_add_source else Res.string.iptv_edit_source,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = SourceEditorMaxHeight),
            verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = SourceEditorScrollMaxHeight)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
            ) {
                LiveTvField(
                    value = draft.name,
                    onValueChange = { onDraftChange(draft.copy(name = it)) },
                    label = stringResource(Res.string.iptv_field_source_name),
                    enabled = !busy,
                    isError = fieldError == LiveTvSourceFieldError.NAME,
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
                    verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
                ) {
                    LiveTvSourceKind.entries.forEach { kind ->
                        Chip(
                            label = kind.displayLabel(),
                            selected = draft.kind == kind,
                            enabled = !busy,
                            onClick = { onDraftChange(draft.copy(kind = kind)) },
                        )
                    }
                }

                when (draft.kind) {
                    LiveTvSourceKind.M3U_URL -> LiveTvField(
                        value = draft.playlistUrl,
                        onValueChange = { onDraftChange(draft.copy(playlistUrl = it)) },
                        label = stringResource(Res.string.iptv_field_playlist_url),
                        placeholder = "https://example.com/playlist.m3u",
                        enabled = !busy,
                        isError = fieldError == LiveTvSourceFieldError.PLAYLIST_URL,
                        keyboardType = KeyboardType.Uri,
                    )

                    LiveTvSourceKind.M3U_FILE -> {
                        LiveTvField(
                            value = draft.playlistFile,
                            onValueChange = { onDraftChange(draft.copy(playlistFile = it)) },
                            label = playlistFileLabel,
                            enabled = !busy,
                            isError = fieldError == LiveTvSourceFieldError.PLAYLIST_FILE,
                        )
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                            DialogButton(
                                text = stringResource(Res.string.iptv_choose_file),
                                enabled = !busy,
                                onClick = {
                                    chooseLiveTvFile(
                                        parent = window,
                                        title = playlistFileLabel,
                                    )?.let { path -> onDraftChange(draft.copy(playlistFile = path)) }
                                },
                            )
                        }
                    }

                    LiveTvSourceKind.XTREAM -> {
                        LiveTvField(
                            value = draft.host,
                            onValueChange = { onDraftChange(draft.copy(host = it)) },
                            label = stringResource(Res.string.iptv_field_xtream_host),
                            placeholder = "http://example.com:8080",
                            enabled = !busy,
                            isError = fieldError == LiveTvSourceFieldError.HOST,
                            keyboardType = KeyboardType.Uri,
                        )
                        LiveTvField(
                            value = draft.username,
                            onValueChange = { onDraftChange(draft.copy(username = it)) },
                            label = stringResource(Res.string.iptv_field_username),
                            enabled = !busy,
                            isError = fieldError == LiveTvSourceFieldError.USERNAME,
                        )
                        LiveTvField(
                            value = draft.password,
                            onValueChange = { onDraftChange(draft.copy(password = it)) },
                            label = stringResource(Res.string.iptv_field_password),
                            enabled = !busy,
                            isError = fieldError == LiveTvSourceFieldError.PASSWORD,
                            secret = true,
                        )
                    }
                }

                LiveTvField(
                    value = draft.epg,
                    onValueChange = { onDraftChange(draft.copy(epg = it)) },
                    label = epgLabel,
                    placeholder = "https://example.com/guide.xml",
                    enabled = !busy,
                    isError = fieldError == LiveTvSourceFieldError.EPG,
                    keyboardType = KeyboardType.Uri,
                )
                Text(
                    text = stringResource(Res.string.iptv_epg_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = tokens.colors.textMuted,
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                    DialogButton(
                        text = stringResource(Res.string.iptv_choose_epg_file),
                        enabled = !busy,
                        onClick = {
                            chooseLiveTvFile(
                                parent = window,
                                title = epgLabel,
                            )?.let { path -> onDraftChange(draft.copy(epg = path)) }
                        },
                    )
                }

                val errorMessage = fieldError?.message()
                if (errorMessage != null) {
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.colors.danger,
                    )
                }
                if (failureText != null) {
                    Text(
                        text = failureText,
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.colors.danger,
                    )
                }
            }

            DialogButtons {
                DialogButton(
                    text = stringResource(Res.string.action_cancel),
                    onClick = onDismiss,
                    enabled = !busy,
                )
                DialogButton(
                    text = if (busy) {
                        stringResource(Res.string.iptv_saving)
                    } else {
                        stringResource(Res.string.iptv_save)
                    },
                    onClick = onSave,
                    style = DialogButtonStyle.Primary,
                    enabled = !busy,
                    loading = busy,
                )
            }
        }
    }
}

@Composable
private fun LiveTvField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    enabled: Boolean,
    isError: Boolean = false,
    placeholder: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    secret: Boolean = false,
) {
    var visible by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        isError = isError,
        singleLine = true,
        shape = MaterialTheme.nuvio.shapes.compactCard,
        label = { Text(label) },
        placeholder = if (placeholder != null) {
            { Text(placeholder) }
        } else {
            null
        },
        visualTransformation = if (secret && !visible) {
            PasswordVisualTransformation()
        } else {
            VisualTransformation.None
        },
        keyboardOptions = KeyboardOptions(
            keyboardType = if (secret) KeyboardType.Password else keyboardType,
            imeAction = ImeAction.Next,
        ),
        keyboardActions = KeyboardActions.Default,
        trailingIcon = if (secret) {
            {
                IconButton(onClick = { visible = !visible }) {
                    Icon(
                        imageVector = if (visible) {
                            Icons.Rounded.VisibilityOff
                        } else {
                            Icons.Rounded.Visibility
                        },
                        contentDescription = null,
                        tint = MaterialTheme.nuvio.colors.textMuted,
                    )
                }
            }
        } else {
            null
        },
        colors = liveTvFieldColors(),
    )
}

@Composable
private fun liveTvFieldColors(): TextFieldColors {
    val tokens = MaterialTheme.nuvio
    return OutlinedTextFieldDefaults.colors(
        focusedBorderColor = tokens.colors.borderFocus,
        unfocusedBorderColor = tokens.colors.borderDefault,
        errorBorderColor = tokens.colors.danger,
        focusedContainerColor = tokens.colors.surfaceCard,
        unfocusedContainerColor = tokens.colors.surfaceCard,
        disabledContainerColor = tokens.colors.surface,
        focusedTextColor = tokens.colors.textPrimary,
        unfocusedTextColor = tokens.colors.textPrimary,
        cursorColor = tokens.colors.accent,
    )
}

@Composable
private fun LiveTvSourceFieldError.message(): String = when (this) {
    LiveTvSourceFieldError.NAME -> stringResource(Res.string.iptv_error_name_required)
    LiveTvSourceFieldError.PLAYLIST_URL -> stringResource(Res.string.iptv_error_url_required)
    LiveTvSourceFieldError.PLAYLIST_FILE -> stringResource(Res.string.iptv_error_file_required)
    LiveTvSourceFieldError.HOST -> stringResource(Res.string.iptv_error_host_required)
    LiveTvSourceFieldError.USERNAME -> stringResource(Res.string.iptv_error_username_required)
    LiveTvSourceFieldError.PASSWORD -> stringResource(Res.string.iptv_error_password_required)
    LiveTvSourceFieldError.EPG -> stringResource(Res.string.iptv_error_epg_invalid)
}

@Composable
private fun LiveTvSourceKind.displayLabel(): String = when (this) {
    LiveTvSourceKind.M3U_URL -> stringResource(Res.string.iptv_source_kind_m3u_url)
    LiveTvSourceKind.M3U_FILE -> stringResource(Res.string.iptv_source_kind_m3u_file)
    LiveTvSourceKind.XTREAM -> stringResource(Res.string.iptv_source_kind_xtream)
}
