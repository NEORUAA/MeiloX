package com.ljyh.mei.parasite

import org.junit.Assert.*
import org.junit.Test

class TvPlaylistParametersTest {
    @Test fun onlySubscriptionObtainsAFreshHostToken() {
        var calls = 0
        val path = "multi/terminal/playlist/subscribe"
        val parameters = mapOf("id" to "10")
        repeat(2) { index ->
            val adapted = tvMutationRequestParameters(path, parameters) { calls++; "test-$calls" }
            assertEquals("test-${index + 1}", adapted["checkToken"])
            assertEquals("10", adapted["id"])
        }
        assertEquals(mapOf("id" to "10"), parameters)
        listOf("multi/terminal/playlist/unsubscribe", "album/sub", "v6/playlist/detail").forEach {
            assertSame(parameters, tvMutationRequestParameters(it, parameters) { error("Unexpected token") })
        }
    }

    @Test fun rejectsCallerSecurityOverridesWithoutInvokingHost() {
        listOf("multi/terminal/playlist/subscribe", "multi/terminal/playlist/unsubscribe", "v1/playlist/manipulate/tracks", "playlist/create", "song/like").forEach { path ->
            assertThrows(IllegalArgumentException::class.java) {
                tvMutationRequestParameters(path, mapOf("CheckToken" to "test")) { error("Must not generate") }
            }
        }
    }

    @Test fun creationAndTrackChangesGenerateSecurityParametersInsideTheHost() {
        var generated = 0
        listOf("playlist/create", "v1/playlist/manipulate/tracks").forEach { path ->
            val adapted = tvMutationRequestParameters(path, mapOf("name" to "Test")) { generated++; "test-only" }
            assertEquals(mapOf("name" to "Test", "checkToken" to "test-only"), adapted)
        }
        assertEquals(2, generated)
    }

    @Test fun preservesTheOfficialDisabledSecurityResult() {
        assertEquals("", tvMutationRequestParameters("multi/terminal/playlist/subscribe", mapOf("id" to "10")) { "" }["checkToken"])
    }

    @Test fun generationFailureDoesNotFallBackToAModuleToken() {
        assertThrows(IllegalStateException::class.java) {
            tvMutationRequestParameters("multi/terminal/playlist/subscribe", mapOf("id" to "10")) { error("Unavailable") }
        }
    }

    @Test fun ordinaryLikesUseHostRefererAndTokenWithoutFmParameters() {
        val parameters = mapOf("trackId" to "10", "like" to "true", "userid" to "0")
        val result = tvMutationRequestParameters("song/like", parameters, { "host-only" }) { "host-token" }
        assertEquals(parameters + mapOf("rqRefer" to "host-only", "checkToken" to "host-token"), result)
        assertEquals(parameters + ("checkToken" to ""), tvMutationRequestParameters("song/like", parameters) { "" })
        assertThrows(IllegalArgumentException::class.java) {
            tvMutationRequestParameters("song/like", parameters + ("RQREFER" to "spoofed")) { error("Must not generate") }
        }
    }
}
