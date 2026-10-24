package com.nuvio.app.features.livetv

import androidx.compose.runtime.Composable
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.iptv_error_generic
import nuvio.composeapp.generated.resources.iptv_notice_guide_failed
import nuvio.composeapp.generated.resources.iptv_notice_source_too_large
import nuvio.composeapp.generated.resources.iptv_notice_account_rejected
import nuvio.composeapp.generated.resources.iptv_notice_saved_sources_unreadable
import nuvio.composeapp.generated.resources.iptv_notice_source_refresh_failed
import org.jetbrains.compose.resources.stringResource

/** Render known failures through resources; provider exception text never becomes UI copy. */
@Composable
internal fun localizedLiveTvMessage(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    val resource = when (raw) {
        LiveTvRepositoryMessages.LoadFailed, LiveTvRepositoryMessages.CorruptStorage ->
            Res.string.iptv_notice_saved_sources_unreadable
        LiveTvRepositoryMessages.SourceFailed -> Res.string.iptv_notice_source_refresh_failed
        LiveTvRepositoryMessages.EpgFailed -> Res.string.iptv_notice_guide_failed
        LiveTvRepositoryMessages.SourceTooLarge -> Res.string.iptv_notice_source_too_large
        LiveTvRepositoryMessages.AccountRejected -> Res.string.iptv_notice_account_rejected
        else -> Res.string.iptv_error_generic
    }
    return stringResource(resource)
}
