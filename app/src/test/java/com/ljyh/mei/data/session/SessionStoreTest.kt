package com.ljyh.mei.data.session

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class SessionStoreTest {
    private val user = SessionIdentity(1, true, false)

    @Test fun unboundStoreIsNotAnAnonymousSessionAndCannotBeRebound() {
        val sessions = SessionStore()
        assertEquals(-1L, sessions.changes.value)
        assertThrows(IOException::class.java) { sessions.snapshot() }
        sessions.bind { user }
        assertEquals(SessionStamp(0, user), sessions.snapshot())
        assertEquals(0L, sessions.changes.value)
        assertThrows(IllegalStateException::class.java) { sessions.bind { user } }
    }

    @Test fun nestedTransitionsFenceReadsUntilEveryLeaseClosesExactlyOnce() {
        val sessions = SessionStore().apply { bind { user } }
        val initial = sessions.snapshot()
        val first = sessions.beginTransition()
        val second = sessions.beginTransition()
        first.close()
        assertThrows(SessionChangedException::class.java) { sessions.snapshot() }
        first.close()
        assertThrows(SessionChangedException::class.java) { sessions.snapshot() }
        second.close()
        val current = sessions.snapshot()
        assertEquals(4L, current.generation)
        assertThrows(SessionChangedException::class.java) { sessions.requireCurrent(initial) }
        second.close()
        assertEquals(current, sessions.snapshot())
    }

    @Test fun identityChangesAndSameAccountReauthorizationRejectOldResults() {
        var identity = user
        val sessions = SessionStore().apply { bind { identity } }
        val first = sessions.snapshot()
        identity = user.copy(userId = 2)
        assertThrows(SessionChangedException::class.java) { sessions.requireCurrent(first) }
        val second = sessions.snapshot()
        sessions.invalidate()
        assertThrows(SessionChangedException::class.java) { sessions.withCurrent(second) { fail("Stale publication") } }
        assertEquals(second.identity, sessions.snapshot().identity)
        assertTrue(second.generation < sessions.snapshot().generation)
    }

    @Test fun backendIdentityReaderRunsOutsideThePublicationMonitor() {
        val sessions = SessionStore()
        sessions.bind {
            assertFalse(Thread.holdsLock(sessions))
            user
        }
        sessions.withCurrent(sessions.snapshot()) { assertTrue(Thread.holdsLock(sessions)) }
    }

    @Test fun failingOrClosedListenersCannotSuppressOtherInvalidations() {
        val sessions = SessionStore().apply { bind { user } }
        var delivered = 0
        sessions.onInvalidated { error("Observer failure") }
        val handle = sessions.onInvalidated { delivered++ }
        sessions.invalidate()
        assertEquals(1, delivered)
        handle.close()
        sessions.invalidate()
        assertEquals(1, delivered)
        assertEquals(2L, sessions.changes.value)
    }

    @Test fun generationChangesDuringTheBackendReadCannotEscapeAsACurrentStamp() {
        val sessions = SessionStore()
        var reads = 0
        sessions.bind {
            if (reads++ == 0) sessions.invalidate()
            user
        }
        assertEquals(SessionStamp(1, user), sessions.snapshot())
        assertEquals(2, reads)
    }
}
