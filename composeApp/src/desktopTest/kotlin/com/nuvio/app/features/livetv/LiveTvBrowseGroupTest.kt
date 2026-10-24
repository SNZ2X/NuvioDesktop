package com.nuvio.app.features.livetv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LiveTvBrowseGroupTest {
    @Test
    fun identitiesAreSourceQualifiedCountChannelsAndIncludeUngrouped() {
        val channels = listOf(
            LiveTvChannel("a", "first", "One", "https://first.test/1", "News"),
            LiveTvChannel("b", "first", "Two", "https://first.test/2", "News"),
            LiveTvChannel("c", "second", "Three", "https://second.test/1", "News"),
            LiveTvChannel("d", "first", "Four", "https://first.test/3"),
        )
        val groups = channels.liveTvBrowseGroups()
        val first = groups.single { it.sourceId == "first" && it.name == "News" }
        val second = groups.single { it.sourceId == "second" }
        assertNotEquals(first.id, second.id)
        assertEquals(2, first.channelCount)
        assertEquals(1, groups.single { it.name.isEmpty() }.channelCount)
        assertTrue(groups.all { it.id.matches(Regex("[0-9a-f]{64}")) })
        assertEquals(groups, channels.reversed().liveTvBrowseGroups())
    }
}
