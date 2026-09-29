package com.ljyh.mei.parasite

import com.ljyh.mei.data.session.SessionChangedException
import com.ljyh.mei.data.session.SessionIdentity
import org.junit.Assert.*
import org.junit.Test

class PlaybackReportOwnershipTest {
    private var identity = SessionIdentity(10, true, false)
    private val sessions = HostSessionBridge().apply { bind { identity } }
    private val bridge = HostPlaybackReportBridge(sessions)
    private var now = 0L
    private val ownership = PlaybackReportOwnership(bridge) { now }
    private val fields = mapOf<String, Any>("id" to 123L, "type" to "song", "time" to 20L)
    private fun marked(action: String = "play"): MutableMap<String, Any?> =
        ownership.mark(action, fields, sessions.snapshot()).toMutableMap()

    @Test fun markerIsConsumedOnceAndNeverRemainsInSerializedFields() {
        val marked = marked()
        val replay = marked.toMutableMap()
        assertEquals(sessions.snapshot(), ownership.consume("play", marked))
        assertEquals(fields, marked)
        assertNull(ownership.consume("play", replay))
        assertFalse(replay.containsKey(PlaybackReportOwnership.MARKER))
    }

    @Test fun officialUnownedEventsAndFabricatedTokensCannotDuplicatePlayback() {
        assertNull(ownership.consume("play", fields.toMutableMap()))
        val unknown = (fields + (PlaybackReportOwnership.MARKER to "unknown")).toMutableMap<String, Any?>()
        assertNull(ownership.consume("play", unknown))
        assertEquals(fields, unknown)
    }

    @Test fun tokensCannotBeReusedForADifferentAction() {
        val marked = marked("startplay")
        assertNull(ownership.consume("play", marked))
        assertEquals(fields, marked)
    }

    @Test fun sameAccountReauthorizationDropsAlreadyEnqueuedEvents() {
        val old = marked()
        sessions.invalidate()
        assertNull(ownership.consume("play", old))
        assertEquals(fields, old)
        assertNotNull(ownership.consume("play", marked()))
    }

    @Test fun delayedInvalidationCannotEraseANewerGenerationsEvent() {
        val localSessions = HostSessionBridge().apply { bind { identity } }
        val localBridge = HostPlaybackReportBridge(localSessions)
        lateinit var localOwnership: PlaybackReportOwnership
        var fresh: MutableMap<String, Any?>? = null
        localSessions.onInvalidated { revision ->
            if (revision == 1L) {
                localSessions.invalidate()
                fresh = localOwnership.mark("play", fields, localSessions.snapshot()).toMutableMap()
            }
        }
        localOwnership = PlaybackReportOwnership(localBridge) { now }
        val old = localOwnership.mark("play", fields, localSessions.snapshot()).toMutableMap<String, Any?>()
        localSessions.invalidate()
        assertNull(localOwnership.consume("play", old))
        assertEquals(localSessions.snapshot(), localOwnership.consume("play", checkNotNull(fresh)))
        assertEquals(fields, fresh)
    }

    @Test fun identityChangesAreCheckedEvenWithoutAnInvalidationCallback() {
        val old = marked()
        identity = SessionIdentity(20, true, false)
        assertNull(ownership.consume("play", old))
        assertNotNull(ownership.consume("play", marked()))
    }

    @Test fun recoveryGuestsAndTransitionsCannotEnqueueOrConsumeEvents() {
        val old = marked()
        sessions.setRecoveryRequired(true)
        assertNull(ownership.consume("play", old))
        assertTrue(runCatching { marked() }.exceptionOrNull() is SessionChangedException)
        sessions.setRecoveryRequired(false)
        sessions.beginTransition().use {
            assertTrue(runCatching { marked() }.exceptionOrNull() is SessionChangedException)
        }
        identity = SessionIdentity(0, false, true)
        assertTrue(runCatching { marked() }.exceptionOrNull() is SessionChangedException)
    }

    @Test fun abandonedSdkEntriesExpireWithoutExposingTheirMarker() {
        val old = marked()
        now = 300_000
        assertNull(ownership.consume("play", old))
        assertEquals(fields, old)
    }

    @Test fun pendingRegistryIsBoundedWhenTheHostSdkDropsEvents() {
        val oldest = marked()
        repeat(256) { now++; marked() }
        assertNull(ownership.consume("play", oldest))
        assertNotNull(ownership.consume("play", marked()))
    }
}
