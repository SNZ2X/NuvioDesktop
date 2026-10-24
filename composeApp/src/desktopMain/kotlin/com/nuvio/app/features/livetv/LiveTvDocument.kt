package com.nuvio.app.features.livetv

/** A bounded decoded document and its final location after redirects. */
internal class LiveTvDocument(val bytes: ByteArray, val location: String)

internal interface LiveTvDocumentLoader {
    suspend fun load(
        location: String,
        maxBytes: Int,
        allowLocalFiles: Boolean = false,
    ): LiveTvDocument
}

internal interface LiveTvSourceStore {
    fun load(): List<LiveTvSource>
    fun save(sources: List<LiveTvSource>)
}
