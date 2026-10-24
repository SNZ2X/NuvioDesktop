package com.nuvio.app.features.livetv

import kotlinx.serialization.Serializable

@Serializable
internal enum class LiveTvSourceKind { M3U_URL, M3U_FILE, XTREAM }

/** This entire record is private source configuration and must be encrypted at rest. */
@Serializable
internal data class LiveTvSource(
    val id: String,
    val name: String,
    val kind: LiveTvSourceKind,
    val location: String,
    val username: String = "",
    val password: String = "",
    val epgUrl: String = "",
    val enabled: Boolean = true,
) {
    override fun toString(): String = "LiveTvSource(id=$id, name=$name, kind=$kind, enabled=$enabled)"
}

internal data class LiveTvChannel(
    val id: String,
    val sourceId: String,
    val name: String,
    val streamUrl: String,
    val group: String = "",
    val logoUrl: String? = null,
    val epgId: String? = null,
    val headers: Map<String, String> = emptyMap(),
) {
    override fun toString(): String = "LiveTvChannel(id=$id, sourceId=$sourceId, name=$name)"
}

internal data class LiveTvProgramme(
    val title: String,
    val startEpochMs: Long,
    val endEpochMs: Long,
    val description: String = "",
)

internal data class LiveTvState(
    val sources: List<LiveTvSource> = emptyList(),
    val channels: List<LiveTvChannel> = emptyList(),
    /** Programmes indexed by the opaque, source-qualified channel ID. */
    val programmes: Map<String, List<LiveTvProgramme>> = emptyMap(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val sourceErrors: Map<String, String> = emptyMap(),
)

internal data class LiveTvSourceContent(
    val channels: List<LiveTvChannel>,
    val epgUrls: List<String> = emptyList(),
)
