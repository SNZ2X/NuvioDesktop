package com.nuvio.app.features.livetv

import java.io.InputStream

/** A bounded decoded document and its final location after redirects. */
internal class LiveTvDocument(val bytes: ByteArray, val location: String)

internal interface LiveTvDocumentLoader {
    suspend fun load(
        location: String,
        maxBytes: Int,
        allowLocalFiles: Boolean = false,
    ): LiveTvDocument

    /** Consume large guides without requiring a complete in-memory document. */
    suspend fun <T : Any> readStream(
        location: String,
        maxBytes: Int,
        allowLocalFiles: Boolean = false,
        consume: (InputStream) -> T,
    ): T = load(location, maxBytes, allowLocalFiles).bytes.inputStream().use(consume)
}

internal interface LiveTvSourceStore {
    fun load(): List<LiveTvSource>
    fun save(sources: List<LiveTvSource>)
}
