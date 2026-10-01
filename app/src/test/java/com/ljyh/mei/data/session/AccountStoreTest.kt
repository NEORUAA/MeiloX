package com.ljyh.mei.data.session




import com.ljyh.mei.data.model.melox.AccountProfile
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccountStoreTest {
    private fun profile(id: Long, name: String = "Account $id") =
        AccountProfile(id, name, null, null, null, null, null, null, null, null)

    @Test fun recoveryWithoutAGenerationChangeClearsProfileAndDefersReads() = runTest {
        val sessions = SessionStore().apply { bind { SessionIdentity(1, true, false) } }
        var loads = 0
        val store = AccountStore(sessions, { loads++; profile(1) }, backgroundScope)
        try {
            runCurrent()
            val owner = sessions.snapshot()
            sessions.setRecoveryRequired(true)
            runCurrent()
            assertEquals(owner, sessions.snapshot())
            assertEquals(owner, store.state.value.session)
            assertNull(store.state.value.profile)
            assertTrue(store.state.value.recoveryRequired)
            assertFalse(store.state.value.authenticated)
            assertThrows(IOException::class.java) { store.requireAuthenticated() }
            store.refresh()
            runCurrent()
            assertEquals(1, loads)
            sessions.setRecoveryRequired(false)
            runCurrent()
            assertEquals(profile(1), store.state.value.profile)
            assertEquals(2, loads)
        } finally { store.close() }
    }

    @Test fun recoveryRaisedByTheResponseCannotPublishTheProfile() = runTest {
        val sessions = SessionStore().apply { bind { SessionIdentity(1, true, false) } }
        val store = AccountStore(sessions, {
            sessions.setRecoveryRequired(true)
            profile(1)
        }, backgroundScope)
        try {
            runCurrent()
            assertNull(store.state.value.profile)
            assertTrue(store.state.value.recoveryRequired)
            assertFalse(store.state.value.loading)
        } finally { store.close() }
    }

    @Test fun profileReadsCarryTheStoreSnapshotThroughAccountReplacement() = runTest {
        var identity = SessionIdentity(1, true, false)
        val sessions = SessionStore().apply { bind { identity } }
        val owners = mutableListOf<SessionStamp>()
        val store = AccountStore(sessions, { owner ->
            owners += owner
            profile(owner.identity.userId)
        }, backgroundScope)
        try {
            runCurrent()
            val first = sessions.snapshot()
            sessions.beginTransition().use { identity = SessionIdentity(2, true, false) }
            runCurrent()
            assertEquals(listOf(first, sessions.snapshot()), owners)
            assertEquals(profile(2), store.state.value.profile)
        } finally { store.close() }
    }

    @Test fun unresolvedAuthorizationClearsProfileAndKeepsSignInAvailable() = runTest {
        val sessions = SessionStore().apply { bind { SessionIdentity(1, true, false) } }
        val store = AccountStore(sessions, { profile(1) }, backgroundScope)
        runCurrent()
        val transition = sessions.beginTransition()
        sessions.setRecoveryRequired(true)
        runCurrent()
        assertNull(store.state.value.profile)
        assertTrue(store.state.value.recoveryRequired)
        assertFalse(store.state.value.loading)
        sessions.setRecoveryRequired(false)
        transition.close()
        runCurrent()
        assertEquals(profile(1), store.state.value.profile)
        assertFalse(store.state.value.recoveryRequired)
        store.close()
    }

    @Test fun bindingWakesAnAlreadyCreatedAccountStore() = runTest {
        val sessions = SessionStore()
        val store = AccountStore(sessions, { profile(1) }, backgroundScope)
        runCurrent()
        assertNull(store.state.value.session)
        sessions.bind { SessionIdentity(1, true, false) }
        runCurrent()
        assertEquals("1", store.state.value.userId)
        assertEquals(profile(1), store.state.value.profile)
        assertFalse(store.state.value.loading)
        store.close()
    }

    @Test fun guestSessionsNeverFetchAnAccountProfile() = runTest {
        val sessions = SessionStore().apply { bind { SessionIdentity(0, false, true) } }
        var fetched = false
        val store = AccountStore(sessions, { fetched = true; profile(1) }, backgroundScope)
        runCurrent()
        assertFalse(fetched)
        assertFalse(store.state.value.authenticated)
        assertEquals("", store.state.value.userId)
        assertFalse(store.state.value.loading)
        assertThrows(IOException::class.java) { store.requireAuthenticated() }
        store.close()
    }

    @Test fun canceledOldAccountCannotPublishAfterTheIdentityChanges() = runTest {
        var identity = SessionIdentity(1, true, false)
        val sessions = SessionStore().apply { bind { identity } }
        val old = CompletableDeferred<AccountProfile>()
        var requests = 0
        val store = AccountStore(sessions, {
            if (requests++ == 0) withContext(NonCancellable) { old.await() } else profile(2)
        }, backgroundScope)
        val published = mutableListOf<Long>()
        backgroundScope.launch { store.state.collect { it.profile?.id?.let(published::add) } }
        runCurrent()
        sessions.beginTransition().use {
            identity = SessionIdentity(2, true, false)
            assertNull(store.state.value.session)
            runCurrent()
            assertNull(store.state.value.profile)
        }
        old.complete(profile(1))
        runCurrent()
        assertEquals(profile(2), store.state.value.profile)
        assertFalse(1L in published)
        store.close()
    }

    @Test fun sameAccountReauthorizationDiscardsTheOldGeneration() = runTest {
        val sessions = SessionStore().apply { bind { SessionIdentity(1, true, false) } }
        val old = CompletableDeferred<AccountProfile>()
        var requests = 0
        val store = AccountStore(sessions, {
            if (requests++ == 0) withContext(NonCancellable) { old.await() } else profile(1, "new")
        }, backgroundScope)
        runCurrent()
        val before = sessions.snapshot()
        sessions.invalidate()
        assertNull(store.state.value.profile)
        old.complete(profile(1, "old"))
        runCurrent()
        assertEquals("new", store.state.value.profile?.nickname)
        assertTrue(store.state.value.session!!.generation > before.generation)
        store.close()
    }

    @Test fun manualRefreshCancelsSameSessionWorkThatIgnoresCancellation() = runTest {
        val sessions = SessionStore().apply { bind { SessionIdentity(1, true, false) } }
        val old = CompletableDeferred<AccountProfile>()
        var requests = 0
        val store = AccountStore(sessions, {
            if (requests++ == 0) withContext(NonCancellable) { old.await() } else profile(1, "new")
        }, backgroundScope)
        val published = mutableListOf<String>()
        backgroundScope.launch { store.state.collect { it.profile?.nickname?.let(published::add) } }
        runCurrent()
        store.refresh()
        runCurrent()
        old.complete(profile(1, "old"))
        runCurrent()
        assertEquals("new", store.state.value.profile?.nickname)
        assertFalse("old" in published)
        store.close()
    }

    @Test fun wrongAccountResponsesFailClosedAndRetryWithoutCredentialStorage() = runTest {
        val sessions = SessionStore().apply { bind { SessionIdentity(1, true, false) } }
        var result = profile(2)
        val store = AccountStore(sessions, { result }, backgroundScope)
        runCurrent()
        assertTrue(store.state.value.authenticated)
        assertTrue(store.state.value.profileUnavailable)
        assertNull(store.state.value.profile)
        result = profile(1)
        store.refresh()
        runCurrent()
        assertEquals(result, store.state.value.profile)
        assertFalse(store.state.value.profileUnavailable)
        store.close()
    }

    @Test fun logoutClearsPublicProfileAndNeverLoadsAnonymousProfile() = runTest {
        var identity = SessionIdentity(1, true, false)
        val sessions = SessionStore().apply { bind { identity } }
        var loads = 0
        val store = AccountStore(sessions, { loads++; profile(1) }, backgroundScope)
        runCurrent()
        sessions.beginTransition().use { identity = SessionIdentity(0, false, true) }
        assertNull(store.state.value.profile)
        runCurrent()
        assertEquals(1, loads)
        assertEquals("", store.state.value.userId)
        assertFalse(store.state.value.authenticated)
        store.close()
    }

    @Test fun delayedOldInvalidationCannotEraseAFreshProfile() = runTest {
        val sessions = SessionStore().apply { bind { SessionIdentity(1, true, false) } }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        sessions.onInvalidated { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        var result = profile(1, "old")
        val store = AccountStore(sessions, { result }, backgroundScope)
        val executor = Executors.newSingleThreadExecutor()
        try {
            runCurrent()
            val invalidating = executor.submit { sessions.invalidate() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            result = profile(1, "new")
            store.refresh()
            runCurrent()
            assertEquals("new", store.state.value.profile?.nickname)
            release.countDown()
            invalidating.get(5, TimeUnit.SECONDS)
            assertEquals("new", store.state.value.profile?.nickname)
        } finally {
            release.countDown()
            executor.shutdownNow()
            store.close()
        }
    }

    @Test fun delayedInvalidationCannotHideAnAvailableRecoveryEntry() = runTest {
        val sessions = SessionStore().apply { bind { SessionIdentity(1, true, false) } }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        sessions.onInvalidated { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        val store = AccountStore(sessions, { profile(1) }, backgroundScope)
        val executor = Executors.newSingleThreadExecutor()
        try {
            runCurrent()
            val transition = executor.submit<java.io.Closeable> { sessions.beginTransition() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            sessions.setRecoveryRequired(true)
            runCurrent()
            assertTrue(store.state.value.recoveryRequired)
            release.countDown()
            val handle = transition.get(5, TimeUnit.SECONDS)
            assertTrue(store.state.value.recoveryRequired)
            assertFalse(store.state.value.loading)
            handle.close()
        } finally {
            release.countDown()
            executor.shutdownNow()
            store.close()
        }
    }

    @Test fun closingReleasesStateAndDiscardsNonCooperativeResults() = runTest {
        val sessions = SessionStore().apply { bind { SessionIdentity(1, true, false) } }
        val pending = CompletableDeferred<AccountProfile>()
        val store = AccountStore(sessions, { withContext(NonCancellable) { pending.await() } }, backgroundScope)
        runCurrent()
        store.close()
        pending.complete(profile(1))
        runCurrent()
        assertEquals(AccountState(), store.state.value)
    }
}
