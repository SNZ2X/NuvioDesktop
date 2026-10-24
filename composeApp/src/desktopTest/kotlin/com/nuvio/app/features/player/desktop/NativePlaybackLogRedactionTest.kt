package com.nuvio.app.features.player.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class NativePlaybackLogRedactionTest {
    @Test
    fun rootQueryCredentialsAndUserInfoAreRemoved() {
        val result = "Failed https://user:password@provider.test?token=secret#private".redactPlaybackUrlsForLog()
        assertEquals("Failed https://provider.test/[redacted]", result)
        listOf("user", "password", "secret", "private", "token").forEach { assertFalse(result.contains(it)) }
    }

    @Test
    fun credentialPathsAndMultipleUrlsAreRemoved() {
        assertEquals(
            "http://provider.test/[redacted] https://other.test/[redacted]",
            "http://provider.test/live/user/password/123.ts https://other.test/list?token=secret".redactPlaybackUrlsForLog(),
        )
    }
}
