package com.nuvio.app.features.livetv

import java.security.MessageDigest

/** Groups are source-qualified, including the empty-name (ungrouped) bucket. */
internal data class LiveTvBrowseGroup(
    val id: String,
    val sourceId: String,
    val name: String,
    val channelCount: Int,
)

/** Hash once per group, rather than per channel or search keystroke. */
internal fun List<LiveTvChannel>.liveTvBrowseGroups(): List<LiveTvBrowseGroup> =
    groupingBy { it.sourceId to it.group }.eachCount().map { (key, count) ->
        val (sourceId, name) = key
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(sourceId.encodeToByteArray())
        digest.update(0.toByte())
        digest.update(name.encodeToByteArray())
        val id = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        LiveTvBrowseGroup(id, sourceId, name, count)
    }.sortedWith(compareBy({ it.sourceId }, { it.name.lowercase() }))
