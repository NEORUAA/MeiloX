package com.ljyh.mei.ui.screen.account

import com.ljyh.mei.standalone.StandaloneAccountController
import com.ljyh.mei.standalone.StandaloneAccountPersistence
import com.ljyh.mei.standalone.StandaloneSessionStore
import com.ljyh.mei.standalone.StoredAccount
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class WebCookieLoginPollerTest {
    @Test fun initiallyUnavailableOwnerCanBeCapturedAfterDurableTransitionForWebLogin() = runTest {
        val disk = MemoryPersistence()
        val sessions = StandaloneSessionStore(disk).also { it.initialize() }
        val pendingWrite = CompletableDeferred<Unit>()
        disk.beforeWrite = { pendingWrite.await() }
        val transition = async {
            sessions.commitLogin(sessions.beginLogin(), StoredAccount("external-cookie", 33))
        }
        runCurrent()
        val attempted = mutableListOf<String>()
        val controller = StandaloneAccountController(sessions) { cookie, _ ->
            attempted += cookie
            StoredAccount(cookie, 11)
        }
        val viewModel = NeteaseLoginViewModel(controller, sessions)
        val poller = WebCookieLoginPoller(isOwnerCurrent = viewModel::isLoginOwnerCurrent)
        val web = async { poller.poll({ "web-cookie" }, viewModel::completeLogin) }
        try {
            assertNull(viewModel.isLoginOwnerCurrent())
            runCurrent()
            assertTrue(attempted.isEmpty())
            pendingWrite.complete(Unit)
            transition.await()
            advanceTimeBy(500)
            runCurrent()
            assertTrue(web.await())
            assertEquals(listOf("web-cookie"), attempted)
            assertEquals(11L, sessions.snapshot().identity.userId)
            assertEquals(listOf("external-cookie", "web-cookie"), disk.writtenCookies)
            assertEquals(false, viewModel.isLoginOwnerCurrent())
            assertFalse(viewModel.completeLogin("another-cookie"))
            assertEquals(listOf("web-cookie"), attempted)
        } finally {
            pendingWrite.complete(Unit)
            transition.join()
            web.cancel()
            web.join()
        }
    }

    @Test fun initiallyUnavailableOwnerCanBeCapturedAfterDurableTransitionForManualLogin() = runTest {
        val disk = MemoryPersistence()
        val sessions = StandaloneSessionStore(disk).also { it.initialize() }
        val pendingWrite = CompletableDeferred<Unit>()
        disk.beforeWrite = { pendingWrite.await() }
        val transition = async {
            sessions.commitLogin(sessions.beginLogin(), StoredAccount("external-cookie", 33))
        }
        runCurrent()
        val attempted = mutableListOf<String>()
        val controller = StandaloneAccountController(sessions) { cookie, _ ->
            attempted += cookie
            StoredAccount(cookie, 11)
        }
        val viewModel = NeteaseLoginViewModel(controller, sessions)
        val poller = WebCookieLoginPoller(isOwnerCurrent = viewModel::isLoginOwnerCurrent)
        try {
            assertNull(viewModel.isLoginOwnerCurrent())
            assertFalse(poller.loginManually("manual-cookie", viewModel::loginWithCookie))
            assertTrue(attempted.isEmpty())
            pendingWrite.complete(Unit)
            transition.await()
            assertTrue(poller.loginManually("manual-cookie", viewModel::loginWithCookie))
            assertEquals(listOf("manual-cookie"), attempted)
            assertEquals(11L, sessions.snapshot().identity.userId)
            assertEquals(listOf("external-cookie", "manual-cookie"), disk.writtenCookies)
            assertEquals(false, viewModel.isLoginOwnerCurrent())
            assertFalse(viewModel.loginWithCookie("another-cookie"))
            assertEquals(listOf("manual-cookie"), attempted)
        } finally {
            pendingWrite.complete(Unit)
            transition.join()
        }
    }

    @Test fun cancellationWhileInitialOwnerIsUnavailableStopsWithoutReadingOrVerifyingCookie() = runTest {
        val poller = WebCookieLoginPoller(isOwnerCurrent = { null })
        var reads = 0
        var verifications = 0
        val web = async { poller.poll({ reads++; "web-cookie" }) { verifications++; true } }
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(0, reads)
        assertEquals(0, verifications)
        web.cancel()
        runCurrent()
        web.join()
        assertTrue(web.isCancelled)
    }

    @Test fun webRetryRetiresAfterExternalAccountReplacement() = runTest {
        assertWebRetryRetires("replacement")
    }

    @Test fun webRetryRetiresAfterSameAccountReauthorization() = runTest {
        assertWebRetryRetires("reauthorization")
    }

    @Test fun webRetryRetiresAfterExplicitLogout() = runTest {
        assertWebRetryRetires("logout")
    }

    @Test fun retainedManualCandidateCannotReplaceAnExternalAccount() = runTest {
        assertManualLoginRetires("replacement")
    }

    @Test fun retainedManualCandidateCannotReplaceSameAccountReauthorization() = runTest {
        assertManualLoginRetires("reauthorization")
    }

    @Test fun retainedManualCandidateCannotUndoExplicitLogout() = runTest {
        assertManualLoginRetires("logout")
    }

    @Test fun controllerOwnerFenceRejectsChangeAfterPollersLastPrecheck() = runTest {
        val disk = MemoryPersistence()
        val sessions = StandaloneSessionStore(disk).also { it.initialize() }
        val attempted = mutableListOf<String>()
        val controller = StandaloneAccountController(sessions) { cookie, _ ->
            attempted += cookie
            StoredAccount(cookie, if (cookie == "external-cookie") 33 else 22)
        }
        val viewModel = NeteaseLoginViewModel(controller, sessions)
        val poller = WebCookieLoginPoller(isOwnerCurrent = viewModel::isLoginOwnerCurrent)
        val web = async {
            poller.poll({ "web-cookie" }) { cookie ->
                assertTrue(controller.login("external-cookie"))
                viewModel.completeLogin(cookie)
            }
        }
        runCurrent()
        val externalOwner = sessions.snapshot()
        advanceTimeBy(500)
        runCurrent()
        assertFalse(web.await())
        assertEquals(listOf("external-cookie"), attempted)
        assertEquals(listOf("external-cookie"), disk.writtenCookies)
        assertEquals(externalOwner, sessions.snapshot())
    }

    @Test fun pageOwnedLoginReturnsSuccessAfterItsOwnCommitChangesOwner() = runTest {
        val disk = MemoryPersistence()
        val sessions = StandaloneSessionStore(disk).also { it.initialize() }
        val owner = sessions.snapshot()
        val controller = StandaloneAccountController(sessions) { cookie, _ -> StoredAccount(cookie, 11) }
        val viewModel = NeteaseLoginViewModel(controller, sessions)
        val poller = WebCookieLoginPoller(isOwnerCurrent = viewModel::isLoginOwnerCurrent)
        assertTrue(poller.poll({ "web-cookie" }, viewModel::completeLogin))
        assertEquals(false, viewModel.isLoginOwnerCurrent())
        assertFalse(poller.poll({ "another-cookie" }, viewModel::completeLogin))
        assertTrue(owner != sessions.snapshot())
        assertEquals(listOf("web-cookie"), disk.writtenCookies)
    }

    @Test fun pageOwnedLoginCanRecoverAPrivatePersistedSession() = runTest {
        val disk = MemoryPersistence(StoredAccount("saved-cookie", 11))
        val sessions = StandaloneSessionStore(disk).also { it.initialize() }
        assertTrue(sessions.recoveryRequired.value)
        val controller = StandaloneAccountController(sessions) { cookie, _ -> StoredAccount(cookie, 11) }
        val viewModel = NeteaseLoginViewModel(controller, sessions)
        assertTrue(viewModel.loginWithCookie("renewed-cookie"))
        assertFalse(sessions.recoveryRequired.value)
        assertEquals(11L, sessions.snapshot().identity.userId)
        assertEquals(listOf("renewed-cookie"), disk.writtenCookies)
    }

    @Test fun sameCookieRecoversAfterControllerTransportFailure() = runTest {
        val disk = MemoryPersistence()
        val sessions = StandaloneSessionStore(disk).also { it.initialize() }
        val owner = sessions.snapshot()
        var calls = 0
        val controller = StandaloneAccountController(sessions) { cookie, _ ->
            if (++calls == 1) throw IOException("Synthetic transport failure")
            StoredAccount(cookie, 11)
        }
        val poller = WebCookieLoginPoller()
        val result = async { poller.poll({ "cookie-A" }, controller::login) }
        runCurrent()
        assertEquals(owner, sessions.snapshot())
        assertTrue(disk.writtenCookies.isEmpty())
        advanceTimeBy(5_000)
        runCurrent()
        assertTrue(result.await())
        assertEquals(2, calls)
        assertEquals(11L, sessions.snapshot().identity.userId)
        assertEquals(listOf("cookie-A"), disk.writtenCookies)
    }

    @Test fun sameCookieRetriesAfterFailureWithBackoff() = runTest {
        val poller = WebCookieLoginPoller()
        var calls = 0
        val result = async { poller.poll({ "cookie-A" }) { ++calls == 2 } }
        runCurrent()
        assertEquals(1, calls)
        advanceTimeBy(4_999)
        runCurrent()
        assertEquals(1, calls)
        advanceTimeBy(1)
        runCurrent()
        assertTrue(result.await())
        assertEquals(2, calls)
    }

    @Test fun pendingVerificationIsNotRepeatedAndBackoffStartsAfterItReturns() = runTest {
        val pending = CompletableDeferred<Boolean>()
        val poller = WebCookieLoginPoller()
        var calls = 0
        val result = async {
            poller.poll({ "cookie-A" }) { if (++calls == 1) pending.await() else true }
        }
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, calls)
        pending.complete(false)
        runCurrent()
        advanceTimeBy(4_999)
        runCurrent()
        assertEquals(1, calls)
        advanceTimeBy(1)
        runCurrent()
        assertTrue(result.await())
        assertEquals(2, calls)
    }

    @Test fun changedCookieDoesNotInheritPreviousCookiesBackoff() = runTest {
        var cookie = "cookie-A"
        val attempted = mutableListOf<String>()
        val poller = WebCookieLoginPoller()
        val result = async {
            poller.poll({ cookie }) {
                attempted += it
                it == "cookie-B"
            }
        }
        runCurrent()
        cookie = "cookie-B"
        advanceTimeBy(500)
        runCurrent()
        assertTrue(result.await())
        assertEquals(listOf("cookie-A", "cookie-B"), attempted)
    }

    @Test fun missingOrBlankCookieIsNeverVerified() = runTest {
        var cookie: String? = null
        var calls = 0
        val poller = WebCookieLoginPoller()
        val result = async { poller.poll({ cookie }) { calls++; true } }
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        cookie = " "
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(0, calls)
        cookie = "cookie-A"
        advanceTimeBy(500)
        runCurrent()
        assertTrue(result.await())
        assertEquals(1, calls)
    }

    @Test fun successfulVerificationStopsFurtherPollingAndEffectRestarts() = runTest {
        val poller = WebCookieLoginPoller()
        var calls = 0
        assertTrue(poller.poll({ "cookie-A" }) { calls++; true })
        advanceTimeBy(60_000)
        assertFalse(poller.poll({ "cookie-B" }) { calls++; true })
        assertEquals(1, calls)
    }

    @Test fun cancellationPropagatesFromVerification() = runTest {
        val poller = WebCookieLoginPoller()
        try {
            poller.poll({ "cookie-A" }) { throw CancellationException("Synthetic cancellation") }
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
    }

    @Test fun compositionCancellationImmediatelyCancelsPendingVerification() = runTest {
        val pending = CompletableDeferred<Boolean>()
        val poller = WebCookieLoginPoller()
        var cancelled = false
        val result = async {
            poller.poll({ "cookie-A" }) {
                try {
                    pending.await()
                } finally {
                    cancelled = true
                }
            }
        }
        runCurrent()
        result.cancel()
        runCurrent()
        result.join()
        assertTrue(cancelled)
        assertTrue(result.isCancelled)
        assertFalse(pending.isCompleted)
    }

    @Test fun cancellationIsNotConvertedToFalseEvenIfVerifierSwallowsIt() = runTest {
        val pending = CompletableDeferred<Boolean>()
        val poller = WebCookieLoginPoller()
        val result = async {
            poller.poll({ "cookie-A" }) {
                try {
                    pending.await()
                } catch (_: CancellationException) {
                    false
                }
            }
        }
        runCurrent()
        result.cancel()
        runCurrent()
        try {
            result.await()
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
        assertTrue(result.isCancelled)
    }

    @Test fun manualLoginCancelsWebVerificationAndOwnsSessionPublication() = runTest {
        val disk = MemoryPersistence()
        val sessions = StandaloneSessionStore(disk).also { it.initialize() }
        val pendingWeb = CompletableDeferred<Unit>()
        val attempted = mutableListOf<String>()
        val controller = StandaloneAccountController(sessions) { cookie, _ ->
            attempted += cookie
            if (cookie == "web-cookie") pendingWeb.await()
            StoredAccount(cookie, if (cookie == "web-cookie") 11 else 22)
        }
        val poller = WebCookieLoginPoller()
        val web = async { poller.poll({ "web-cookie" }, controller::login) }
        runCurrent()
        poller.pauseForManualLogin()
        val manual = async { poller.loginManually("manual-cookie", controller::login) }
        runCurrent()
        web.join()
        assertTrue(web.isCancelled)
        assertTrue(manual.await())
        assertEquals(listOf("web-cookie", "manual-cookie"), attempted)
        assertEquals(listOf("manual-cookie"), disk.writtenCookies)
        assertEquals(22L, sessions.snapshot().identity.userId)
        poller.resumeWebLogin()
        assertFalse(poller.poll({ "web-cookie" }, controller::login))
        assertEquals(2, attempted.size)
    }

    @Test fun lateCancelledWebSuccessCannotCommitOrOverrideManualSuccess() = runTest {
        val disk = MemoryPersistence()
        val sessions = StandaloneSessionStore(disk).also { it.initialize() }
        val pendingWeb = CompletableDeferred<Unit>()
        val attempted = mutableListOf<String>()
        val controller = StandaloneAccountController(sessions) { cookie, _ ->
            attempted += cookie
            if (cookie == "web-cookie") withContext(NonCancellable) { pendingWeb.await() }
            StoredAccount(cookie, if (cookie == "web-cookie") 11 else 22)
        }
        val poller = WebCookieLoginPoller()
        val web = async { poller.poll({ "web-cookie" }, controller::login) }
        runCurrent()
        poller.pauseForManualLogin()
        val manual = async { poller.loginManually("manual-cookie", controller::login) }
        runCurrent()
        assertFalse(manual.isCompleted)
        assertEquals(listOf("web-cookie"), attempted)
        pendingWeb.complete(Unit)
        runCurrent()
        web.join()
        assertTrue(web.isCancelled)
        assertTrue(manual.await())
        assertEquals(listOf("manual-cookie"), disk.writtenCookies)
        assertEquals(22L, sessions.snapshot().identity.userId)
    }

    @Test fun cancellationAfterWebDurableCommitStartsCannotRebaseManualCandidate() = runTest {
        val disk = MemoryPersistence()
        val sessions = StandaloneSessionStore(disk).also { it.initialize() }
        val pendingWrite = CompletableDeferred<Unit>()
        val enteredWrite = CompletableDeferred<Unit>()
        disk.beforeWrite = {
            enteredWrite.complete(Unit)
            pendingWrite.await()
        }
        val attempted = mutableListOf<String>()
        val controller = StandaloneAccountController(sessions) { cookie, _ ->
            attempted += cookie
            StoredAccount(cookie, if (cookie == "web-cookie") 11 else 22)
        }
        val viewModel = NeteaseLoginViewModel(controller, sessions)
        val poller = WebCookieLoginPoller(isOwnerCurrent = viewModel::isLoginOwnerCurrent)
        val web = async { poller.poll({ "web-cookie" }, viewModel::completeLogin) }
        runCurrent()
        assertTrue(enteredWrite.isCompleted)
        poller.pauseForManualLogin()
        val manual = async { poller.loginManually("manual-cookie", viewModel::loginWithCookie) }
        try {
            runCurrent()
            assertFalse(manual.isCompleted)
            pendingWrite.complete(Unit)
            runCurrent()
            web.join()
            assertTrue(web.isCancelled)
            assertFalse(manual.await())
            assertEquals(listOf("web-cookie"), attempted)
            assertEquals(listOf("web-cookie"), disk.writtenCookies)
            assertEquals(11L, sessions.snapshot().identity.userId)
            assertFalse(viewModel.loginWithCookie("manual-cookie"))
            assertEquals(listOf("web-cookie"), attempted)
        } finally {
            pendingWrite.complete(Unit)
            web.cancel()
            web.join()
            manual.cancel()
            manual.join()
        }
    }

    @Test fun failedManualLoginKeepsWebPausedUntilSheetDismissal() = runTest {
        val poller = WebCookieLoginPoller()
        var webCalls = 0
        poller.pauseForManualLogin()
        assertFalse(poller.loginManually("manual-cookie") { false })
        advanceTimeBy(60_000)
        assertFalse(poller.poll({ "web-cookie" }) { webCalls++; true })
        assertEquals(0, webCalls)
        poller.resumeWebLogin()
        assertTrue(poller.poll({ "web-cookie" }) { webCalls++; true })
        assertEquals(1, webCalls)
    }

    @Test fun openingManualSheetDuringWebBackoffPreventsTheNextWebAttempt() = runTest {
        val poller = WebCookieLoginPoller()
        var calls = 0
        val web = async { poller.poll({ "web-cookie" }) { calls++; false } }
        runCurrent()
        poller.pauseForManualLogin()
        runCurrent()
        web.join()
        advanceTimeBy(60_000)
        runCurrent()
        assertTrue(web.isCancelled)
        assertEquals(1, calls)
        assertTrue(poller.loginManually("manual-cookie") { true })
        poller.resumeWebLogin()
        assertFalse(poller.poll({ "web-cookie" }) { calls++; false })
        assertEquals(1, calls)
    }

    @Test fun cookieHeaderExtractionKeepsOnlyNonblankMusicU() {
        assertNull(musicUFromCookieHeader(""))
        assertNull(musicUFromCookieHeader("MUSIC_U= ; other=value"))
        assertNull(musicUFromCookieHeader("other=value; NOT_MUSIC_U=wrong"))
        assertEquals("cookie-A", musicUFromCookieHeader("other=value; MUSIC_U=cookie-A; end=value"))
    }

    private suspend fun TestScope.assertWebRetryRetires(change: String) {
        val disk = MemoryPersistence()
        val sessions = StandaloneSessionStore(disk).also { it.initialize() }
        sessions.commitLogin(sessions.beginLogin(), StoredAccount("existing-cookie", 11))
        disk.writtenCookies.clear()
        var webCalls = 0
        val controller = StandaloneAccountController(sessions) { cookie, _ ->
            if (cookie == "web-cookie" && ++webCalls == 1) {
                throw IOException("Synthetic transport failure")
            }
            StoredAccount(cookie, if (cookie == "web-cookie") 22 else if (change == "reauthorization") 11 else 33)
        }
        val viewModel = NeteaseLoginViewModel(controller, sessions)
        val poller = WebCookieLoginPoller(isOwnerCurrent = viewModel::isLoginOwnerCurrent)
        val web = async { poller.poll({ "web-cookie" }, viewModel::completeLogin) }
        try {
            runCurrent()
            assertEquals(1, webCalls)
            if (change == "logout") sessions.logout { }
            else assertTrue(controller.login("external-cookie"))
            val externalOwner = sessions.snapshot()
            val externalWrites = disk.writtenCookies.toList()
            advanceTimeBy(5_000)
            runCurrent()
            assertEquals("Retired page must not verify its Cookie again", 1, webCalls)
            assertEquals(externalOwner, sessions.snapshot())
            assertEquals(externalWrites, disk.writtenCookies)
            assertTrue(web.isCompleted)
            assertFalse(web.await())
        } finally {
            web.cancel()
            web.join()
        }
    }

    private suspend fun assertManualLoginRetires(change: String) {
        val disk = MemoryPersistence()
        val sessions = StandaloneSessionStore(disk).also { it.initialize() }
        sessions.commitLogin(sessions.beginLogin(), StoredAccount("existing-cookie", 11))
        disk.writtenCookies.clear()
        val attempted = mutableListOf<String>()
        val controller = StandaloneAccountController(sessions) { cookie, _ ->
            attempted += cookie
            StoredAccount(cookie, if (change == "reauthorization") 11 else 33)
        }
        val viewModel = NeteaseLoginViewModel(controller, sessions)
        val poller = WebCookieLoginPoller(isOwnerCurrent = viewModel::isLoginOwnerCurrent)
        poller.pauseForManualLogin()
        if (change == "logout") sessions.logout { }
        else assertTrue(controller.login("external-cookie"))
        val externalOwner = sessions.snapshot()
        val externalWrites = disk.writtenCookies.toList()
        assertFalse(poller.loginManually("manual-cookie", viewModel::loginWithCookie))
        assertFalse(viewModel.loginWithCookie("manual-cookie"))
        assertEquals(if (change == "logout") emptyList<String>() else listOf("external-cookie"), attempted)
        assertEquals(externalOwner, sessions.snapshot())
        assertEquals(externalWrites, disk.writtenCookies)
    }

    private class MemoryPersistence(private var account: StoredAccount = StoredAccount("", 0)) : StandaloneAccountPersistence {
        val writtenCookies = mutableListOf<String>()
        var beforeWrite: (suspend () -> Unit)? = null

        override suspend fun read(): StoredAccount = account

        override suspend fun write(account: StoredAccount) {
            beforeWrite?.invoke()
            this.account = account
            writtenCookies += account.musicU
        }
    }
}
