package com.ljyh.mei.parasite

import org.junit.Assert.*
import org.junit.Test

class TvPlaylistParametersTest {
    @Test fun onlySubscriptionObtainsAFreshHostToken() {
        var calls = 0
        val path = "multi/terminal/playlist/subscribe"
        val parameters = mapOf("id" to "10")
        repeat(2) { index ->
            val adapted = tvPlaylistRequestParameters(path, parameters) { calls++; "test-$calls" }
            assertEquals("test-${index + 1}", adapted["checkToken"])
            assertEquals("10", adapted["id"])
        }
        assertEquals(mapOf("id" to "10"), parameters)
        listOf("multi/terminal/playlist/unsubscribe", "album/sub", "v6/playlist/detail").forEach {
            assertSame(parameters, tvPlaylistRequestParameters(it, parameters) { error("Unexpected token") })
        }
    }

    @Test fun rejectsCallerSecurityOverridesWithoutInvokingHost() {
        listOf("multi/terminal/playlist/subscribe", "multi/terminal/playlist/unsubscribe").forEach { path ->
            assertThrows(IllegalArgumentException::class.java) {
                tvPlaylistRequestParameters(path, mapOf("CheckToken" to "test")) { error("Must not generate") }
            }
        }
    }

    @Test fun preservesTheOfficialDisabledSecurityResult() {
        assertEquals("", tvPlaylistRequestParameters("multi/terminal/playlist/subscribe", mapOf("id" to "10")) { "" }["checkToken"])
    }

    @Test fun generationFailureDoesNotFallBackToAModuleToken() {
        assertThrows(IllegalStateException::class.java) {
            tvPlaylistRequestParameters("multi/terminal/playlist/subscribe", mapOf("id" to "10")) { error("Unavailable") }
        }
    }
}
