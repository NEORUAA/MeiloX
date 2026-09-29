package com.ljyh.mei.parasite

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class HostAuthorizationGuardTest {
    private class Fixture(pending: Boolean = false) {
        var identity = HostSessionIdentity(1, true, false)
        val sessions = HostSessionBridge().apply { bind { identity } }
        var persisted = pending
        val queued = mutableListOf<() -> Unit>()
        val guard = HostAuthorizationGuard(sessions, pending, { persisted = it }, { synchronized(queued) { queued += it } })
        fun verify(): Boolean = guard.verifyProfile({ guard.mutate { true } }, { it })
        fun drain() {
            while (true) {
                val task = synchronized(queued) { if (queued.isEmpty()) null else queued.removeAt(0) } ?: return
                task()
            }
        }
        fun blocked() { assertThrows(HostSessionChangedException::class.java) { sessions.snapshot() } }
    }

    @Test fun waitingAttemptBlocksBusinessAndCancelWithoutWritesRestoresSession() {
        val f = Fixture()
        val before = f.sessions.snapshot()
        val attempt = f.guard.begin()
        assertTrue(f.persisted)
        f.blocked()
        f.guard.retire(attempt)
        assertFalse(f.persisted)
        assertEquals(before.identity, f.sessions.snapshot().identity)
        assertNotEquals(before.generation, f.sessions.snapshot().generation)
    }

    @Test fun canceledNetworkWorkCannotWriteAfterAnotherAttemptBegins() {
        val f = Fixture()
        val old = f.guard.begin()
        f.guard.run(old) {
            f.guard.retire(old)
            f.guard.begin()
            assertThrows(IOException::class.java) { f.guard.mutate(cookie = true) { fail("Stale cookie write") } }
            assertThrows(IOException::class.java) { f.guard.mutate { fail("Stale profile write") } }
        }
        f.blocked()
    }

    @Test fun cancelAfterCookieWriteRecoversOnlyThroughTheOfficialProfile() {
        val f = Fixture()
        f.guard.setRecovery { f.verify() }
        val attempt = f.guard.begin()
        f.guard.run(attempt) { f.guard.mutate(cookie = true) { } }
        f.guard.retire(attempt)
        f.blocked()
        assertTrue(f.persisted)
        assertTrue(f.sessions.recoveryRequired.value)
        f.drain()
        assertFalse(f.persisted)
        assertFalse(f.sessions.recoveryRequired.value)
        assertTrue(f.sessions.snapshot().identity.authenticated)
    }

    @Test fun unsuccessfulRecoveryStaysClosedWithoutAnAutomaticRetryLoop() {
        val f = Fixture(pending = true)
        var reads = 0
        f.guard.setRecovery { reads++; false }
        f.drain()
        assertEquals(1, reads)
        assertTrue(f.persisted)
        assertTrue(f.sessions.recoveryRequired.value)
        f.blocked()
        val retry = f.guard.begin()
        f.guard.run(retry) { f.verify() }
        assertTrue(f.guard.complete(retry))
        assertFalse(f.persisted)
    }

    @Test fun pendingMarkerRestoresFenceBeforeTheFirstRecoveryRequest() {
        val f = Fixture(pending = true)
        f.blocked()
        f.guard.setRecovery { f.verify() }
        f.blocked()
        f.drain()
        assertFalse(f.persisted)
        assertEquals(f.identity, f.sessions.snapshot().identity)
    }

    @Test fun newAttemptSupersedesQueuedRecoveryWithoutStartingIt() {
        val f = Fixture(pending = true)
        var reads = 0
        f.guard.setRecovery { reads++; f.verify() }
        val current = f.guard.begin()
        f.drain()
        assertEquals(0, reads)
        f.guard.run(current) { f.verify() }
        assertTrue(f.guard.complete(current))
    }

    @Test fun oldRecoveryCannotPublishAfterAReplacementAttempt() {
        val f = Fixture(pending = true)
        lateinit var replacement: HostAuthorizationGuard.Attempt
        f.guard.setRecovery {
            replacement = f.guard.begin()
            assertThrows(IOException::class.java) { f.verify() }
            false
        }
        f.drain()
        assertTrue(f.guard.isActive(replacement))
        f.guard.run(replacement) { f.verify() }
        assertTrue(f.guard.complete(replacement))
    }

    @Test fun credentialsChangingDuringProfileReadCannotBeConfirmed() {
        val f = Fixture()
        val attempt = f.guard.begin()
        f.guard.run(attempt) {
            f.guard.verifyProfile({ f.guard.mutate(cookie = true) { true } }, { it })
        }
        assertFalse(f.guard.complete(attempt))
        f.blocked()
        f.guard.run(attempt) { f.verify() }
        assertTrue(f.guard.complete(attempt))
    }

    @Test fun externalProfileMutationInvalidatesPriorConfirmation() {
        val f = Fixture()
        val attempt = f.guard.begin()
        f.guard.run(attempt) { f.verify() }
        f.guard.mutate { }
        assertFalse(f.guard.complete(attempt))
        f.guard.run(attempt) { f.verify() }
        assertTrue(f.guard.complete(attempt))
    }

    @Test fun writeAlreadyEnteredAtCancellationMustDrainBeforeRecoveryOrReplacement() {
        val f = Fixture()
        f.guard.setRecovery { f.verify() }
        val attempt = f.guard.begin()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val pending = pool.submit {
                f.guard.run(attempt) {
                    f.guard.mutate(cookie = true) {
                        entered.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                        // Nested writes are part of the already-entered host operation.
                        f.guard.mutate { }
                    }
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            f.guard.retire(attempt)
            f.blocked()
            assertThrows(IllegalStateException::class.java) { f.guard.begin() }
            assertTrue(f.queued.isEmpty())
            release.countDown()
            pending.get(5, TimeUnit.SECONDS)
            f.drain()
            assertFalse(f.persisted)
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test fun failedPersistenceNeverPublishesAnUnverifiedSession() {
        val sessions = HostSessionBridge().apply { bind { HostSessionIdentity(1, true, false) } }
        var failWrite = true
        val guard = HostAuthorizationGuard(sessions, false, { check(!failWrite) }, { it() })
        assertThrows(IllegalStateException::class.java) { guard.begin() }
        assertThrows(HostSessionChangedException::class.java) { sessions.snapshot() }
        failWrite = false
        val attempt = guard.begin()
        guard.run(attempt) { guard.verifyProfile({ true }, { it }) }
        failWrite = true
        assertFalse(guard.complete(attempt))
        assertThrows(HostSessionChangedException::class.java) { sessions.snapshot() }
        failWrite = false
        assertTrue(guard.complete(attempt))
    }

    @Test fun unrelatedHostOperationsStillRunOutsideAnOwnedAttempt() {
        val f = Fixture()
        assertEquals(42, f.guard.mutate(cookie = true) { 42 })
        assertEquals(24, f.guard.verifyProfile({ 24 }, { false }))
        assertFalse(f.persisted)
        assertTrue(f.sessions.snapshot().identity.authenticated)
    }

    @Test fun mutationDuringProfileValidationCannotWinThePublicationRace() {
        val f = Fixture()
        val attempt = f.guard.begin()
        f.guard.run(attempt) {
            f.guard.verifyProfile({ true }, { f.guard.mutate { }; true })
        }
        assertFalse(f.guard.complete(attempt))
        f.blocked()
    }

    @Test fun failedProfileParsingRestoresThreadScopeAndRetainsRecoveryMarker() {
        val f = Fixture()
        f.guard.setRecovery { f.verify() }
        val attempt = f.guard.begin()
        assertThrows(IOException::class.java) {
            f.guard.run(attempt) { f.guard.mutate { throw IOException("Fixture parser failure") } }
        }
        f.guard.retire(attempt)
        f.drain()
        assertFalse(f.persisted)
        assertEquals(7, f.guard.mutate { 7 })
    }

    @Test fun rejectedOfficialProfileDoesNotReleaseTheFence() {
        val f = Fixture()
        val attempt = f.guard.begin()
        f.guard.run(attempt) { f.guard.verifyProfile({ false }, { it }) }
        assertFalse(f.guard.complete(attempt))
        f.blocked()
        assertTrue(f.persisted)
    }

    @Test fun failedRevalidationCannotReuseAnOlderSuccessfulProfile() {
        val f = Fixture()
        val attempt = f.guard.begin()
        f.guard.run(attempt) {
            f.verify()
            f.guard.verifyProfile({ false }, { it })
        }
        assertFalse(f.guard.complete(attempt))
    }

    @Test fun failedRecoveryDispatchLeavesALoginRetryAvailable() {
        val sessions = HostSessionBridge().apply { bind { HostSessionIdentity(1, true, false) } }
        val guard = HostAuthorizationGuard(sessions, true, {}, { throw IllegalStateException("Fixture scheduler stopped") })
        guard.setRecovery { false }
        assertTrue(sessions.recoveryRequired.value)
        val attempt = guard.begin()
        guard.run(attempt) { guard.verifyProfile({ true }, { it }) }
        assertTrue(guard.complete(attempt))
        assertFalse(sessions.recoveryRequired.value)
    }
}
