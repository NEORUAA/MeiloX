package com.ljyh.mei.standalone

import com.ljyh.mei.data.session.SessionChangedException
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class StandaloneSessionStoreTest {
    @Test fun savedAccountStaysPrivateUntilItsCookieIsReverified() = runTest {
        val disk = MemoryPersistence(StoredAccount("cookie-A", 11))
        val sessions = StandaloneSessionStore(disk)
        assertEquals("cookie-A", sessions.initialize())
        val owner = sessions.snapshot()
        assertEquals(0, owner.identity.userId)
        assertFalse(owner.identity.authenticated)
        assertEquals("", sessions.credentials(owner).musicU)
        assertTrue(sessions.recoveryRequired.value)
        assertEquals(0, disk.writes)
    }

    @Test fun unknownLegacyIdentityIsNotPublishedWithCredentials() = runTest {
        val sessions = StandaloneSessionStore(MemoryPersistence(StoredAccount("legacy-cookie", 0)))
        assertEquals("legacy-cookie", sessions.initialize())
        val owner = sessions.snapshot()
        assertFalse(owner.identity.authenticated)
        assertEquals("", sessions.credentials(owner).musicU)
        assertTrue(sessions.recoveryRequired.value)
    }

    @Test fun malformedPersistedCookieCannotBecomeAuthenticated() = runTest {
        for (cookie in listOf("", "MUSIC_U=value", "x;y", "x\ny", "x y", "非ascii")) {
            val sessions = StandaloneSessionStore(MemoryPersistence(StoredAccount(cookie, 11)))
            assertNull(sessions.initialize())
            assertFalse(sessions.snapshot().identity.authenticated)
        }
    }

    @Test fun candidateRemainsPrivateUntilVerificationSucceeds() = runTest {
        val disk = MemoryPersistence(StoredAccount("cookie-A", 11))
        val sessions = verifiedSessions(disk)
        val before = sessions.snapshot()
        val verified = CompletableDeferred<Unit>()
        val controller = StandaloneAccountController(sessions) { cookie, owner ->
            assertEquals("cookie-B", cookie)
            assertEquals(before, owner)
            verified.await()
            StoredAccount(cookie, 22)
        }
        val login = async { controller.login("cookie-B") }
        runCurrent()
        assertEquals(before, sessions.snapshot())
        assertEquals("cookie-A", sessions.credentials(before).musicU)
        assertEquals(0, disk.writes)
        verified.complete(Unit)
        assertTrue(login.await())
        assertEquals(22, sessions.snapshot().identity.userId)
        assertEquals("cookie-B", disk.account.musicU)
        assertThrows(SessionChangedException::class.java) { sessions.requireCurrent(before) }
    }

    @Test fun failedVerificationLeavesCurrentCredentialsAndIdentityUntouched() = runTest {
        val disk = MemoryPersistence(StoredAccount("cookie-A", 11))
        val sessions = verifiedSessions(disk)
        val owner = sessions.snapshot()
        val controller = StandaloneAccountController(sessions) { _, _ -> throw IOException("synthetic") }
        assertFalse(controller.login("bad-cookie"))
        assertEquals(owner, sessions.snapshot())
        assertEquals(0, disk.writes)
    }

    @Test fun latestLoginAttemptWinsEvenIfOlderVerificationFinishesLast() = runTest {
        val sessions = StandaloneSessionStore(MemoryPersistence(StoredAccount("", 0))).also { it.initialize() }
        val old = sessions.beginLogin()
        val recent = sessions.beginLogin()
        expectSessionChanged { sessions.commitLogin(old, StoredAccount("old", 11)) }
        sessions.commitLogin(recent, StoredAccount("recent", 22))
        assertEquals(22, sessions.snapshot().identity.userId)
    }

    @Test fun logoutPreventsLateVerificationFromRestoringTheAccount() = runTest {
        val disk = MemoryPersistence(StoredAccount("cookie-A", 11))
        val sessions = verifiedSessions(disk)
        val old = sessions.beginLogin()
        var clearedWeb = false
        sessions.logout { clearedWeb = true }
        expectSessionChanged { sessions.commitLogin(old, StoredAccount("late-cookie", 22)) }
        assertTrue(clearedWeb)
        assertFalse(sessions.snapshot().identity.authenticated)
        assertEquals("", disk.account.musicU)
    }

    @Test fun sameAccountReauthorizationInvalidatesExistingRequests() = runTest {
        val sessions = verifiedSessions(MemoryPersistence(StoredAccount("cookie-A", 11)))
        val owner = sessions.snapshot()
        sessions.commitLogin(sessions.beginLogin(), StoredAccount("renewed", 11))
        assertEquals(owner.identity, sessions.snapshot().identity)
        assertNotEquals(owner.generation, sessions.snapshot().generation)
        assertEquals("renewed", sessions.credentials(sessions.snapshot()).musicU)
    }

    @Test fun persistenceFailureDoesNotPublishCandidateCredentials() = runTest {
        val disk = MemoryPersistence(StoredAccount("cookie-A", 11))
        val sessions = verifiedSessions(disk)
        disk.failWrites = true
        try {
            sessions.commitLogin(sessions.beginLogin(), StoredAccount("cookie-B", 22))
            fail("Expected persistence failure")
        } catch (_: IOException) { }
        assertEquals(11, sessions.snapshot().identity.userId)
        assertEquals("cookie-A", sessions.credentials(sessions.snapshot()).musicU)
    }

    @Test fun publicationIsUnavailableWhileDurableCommitIsInFlight() = runTest {
        val written = CompletableDeferred<Unit>()
        val disk = MemoryPersistence(StoredAccount("cookie-A", 11))
        val sessions = verifiedSessions(disk)
        disk.beforeWrite = { written.await() }
        val attempt = sessions.beginLogin()
        val login = async { sessions.commitLogin(attempt, StoredAccount("cookie-B", 22)) }
        runCurrent()
        assertThrows(SessionChangedException::class.java) { sessions.snapshot() }
        written.complete(Unit)
        login.await()
        assertEquals(22, sessions.snapshot().identity.userId)
    }

    @Test fun canceledVerificationDoesNotWriteCredentials() = runTest {
        val disk = MemoryPersistence(StoredAccount("cookie-A", 11))
        val sessions = verifiedSessions(disk)
        val owner = sessions.snapshot()
        val controller = StandaloneAccountController(sessions) { cookie, _ ->
            CompletableDeferred<Unit>().await()
            StoredAccount(cookie, 22)
        }
        val login = async { controller.login("cookie-B") }
        runCurrent()
        login.cancel()
        login.join()
        assertEquals(owner, sessions.snapshot())
        assertEquals(0, disk.writes)
    }

    @Test fun credentialsNeverAppearInDiagnosticStrings() {
        assertFalse(StoredAccount("secret-cookie", 11).toString().contains("secret-cookie"))
        assertFalse(StandaloneCredentials("secret-cookie").toString().contains("secret-cookie"))
    }

    private suspend fun expectSessionChanged(block: suspend () -> Unit) {
        try { block() } catch (_: SessionChangedException) { return }
        fail("Expected a stale login to be rejected")
    }

    private suspend fun verifiedSessions(disk: MemoryPersistence): StandaloneSessionStore {
        val saved = disk.account
        val sessions = StandaloneSessionStore(disk).also { it.initialize() }
        if (isValidMusicU(saved.musicU) && saved.userId > 0) {
            sessions.commitLogin(sessions.beginLogin(), saved)
            disk.writes = 0
        }
        return sessions
    }

    private class MemoryPersistence(var account: StoredAccount) : StandaloneAccountPersistence {
        var writes = 0
        var failWrites = false
        var beforeWrite: suspend () -> Unit = {}
        override suspend fun read() = account
        override suspend fun write(account: StoredAccount) {
            beforeWrite()
            if (failWrites) throw IOException("synthetic write failure")
            writes++
            this.account = account
        }
    }
}
