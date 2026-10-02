package com.ljyh.mei.parasite

import com.ljyh.mei.data.session.SessionChangedException
import com.ljyh.mei.data.session.SessionIdentity
import android.graphics.Bitmap
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HostSessionLoginTest {
    @Test fun hostIdentityReadersNeverRunUnderTheSessionMonitor() {
        lateinit var sessions: HostSessionBridge
        sessions = HostSessionBridge().apply {
            bind {
                check(!Thread.holdsLock(sessions))
                SessionIdentity(1, true, false)
            }
        }
        val stamp = sessions.snapshot()
        sessions.withCurrent(stamp) { assertTrue(Thread.holdsLock(sessions)) }
    }

    @Test fun statePublicationAndTransitionDoNotInterleave() {
        val sessions = HostSessionBridge().apply { bind { SessionIdentity(1, true, false) } }
        val stamp = sessions.snapshot()
        val publishing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val invalidated = CountDownLatch(1)
        sessions.onInvalidated { invalidated.countDown() }
        val pool = Executors.newFixedThreadPool(2)
        try {
            val publication = pool.submit {
                sessions.withCurrent(stamp) {
                    publishing.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    sessions.requireCurrent(stamp)
                }
            }
            assertTrue(publishing.await(5, TimeUnit.SECONDS))
            val change = pool.submit { sessions.beginTransition().close() }
            assertEquals(false, invalidated.await(100, TimeUnit.MILLISECONDS))
            release.countDown()
            publication.get(5, TimeUnit.SECONDS)
            change.get(5, TimeUnit.SECONDS)
            assertThrows(SessionChangedException::class.java) {
                sessions.withCurrent(stamp) { error("Stale state must not be published") }
            }
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test fun missingHostLoginFailsClosedAndCanRetryOnceBound() {
        val sessions = HostSessionBridge()
        sessions.bind { SessionIdentity(1, true, false) }
        val login = sessions.newLogin()
        login.start()
        assertEquals(HostLoginStatus.ERROR, login.state.value.status)
        sessions.bindLogin(object : HostLoginBackend<Bitmap> {
            override fun open(emit: (HostLoginState<Bitmap>) -> Unit): Closeable {
                emit(HostLoginState(HostLoginStatus.SUCCESS))
                return Closeable {}
            }
            override fun logout() = Unit
        })
        login.refresh()
        assertEquals(HostLoginStatus.SUCCESS, login.state.value.status)
    }

    @Test fun logoutInvalidatesRequestsEvenWhenOfficialCleanupFails() {
        val sessions = HostSessionBridge()
        sessions.bind { SessionIdentity(1, true, false) }
        sessions.bindLogin(object : HostLoginBackend<Bitmap> {
            override fun open(emit: (HostLoginState<Bitmap>) -> Unit): Closeable = Closeable {}
            override fun logout() {
                assertThrows(SessionChangedException::class.java) { sessions.snapshot() }
                throw IOException("Official logout unavailable")
            }
        })
        val before = sessions.snapshot()
        assertThrows(IOException::class.java) { sessions.logout() }
        assertThrows(SessionChangedException::class.java) { sessions.requireCurrent(before) }
        assertTrue(sessions.snapshot().generation > before.generation)
    }

    @Test fun loginCannotReplaceAnAlreadyBoundOfficialBackend() {
        val sessions = HostSessionBridge()
        val backend = object : HostLoginBackend<Bitmap> {
            override fun open(emit: (HostLoginState<Bitmap>) -> Unit): Closeable = Closeable {}
            override fun logout() = Unit
        }
        sessions.bindLogin(backend)
        assertThrows(IllegalStateException::class.java) { sessions.bindLogin(backend) }
    }

    @Test fun retainedLogoutCannotMutateAReplacementReauthorizedOrRecoveringAccount() {
        for (change in listOf("replacement", "reauthorization", "recovery")) {
            var identity = SessionIdentity(1, true, false)
            val sessions = HostSessionBridge().apply { bind { identity } }
            var logouts = 0
            sessions.bindLogin(object : HostLoginBackend<Bitmap> {
                override fun open(emit: (HostLoginState<Bitmap>) -> Unit): Closeable = Closeable {}
                override fun logout() { logouts++ }
            })
            val rendered = sessions.snapshot()
            when (change) {
                "replacement" -> identity = identity.copy(userId = 2)
                "reauthorization" -> sessions.invalidate()
                else -> sessions.setRecoveryRequired(true)
            }
            val current = sessions.snapshot()
            assertThrows(SessionChangedException::class.java) { sessions.logout(rendered) }
            assertEquals(0, logouts)
            assertEquals(current, sessions.snapshot())
        }
    }

    @Test fun currentRenderedLogoutInvokesTheOfficialBackendOnce() {
        val sessions = HostSessionBridge().apply { bind { SessionIdentity(1, true, false) } }
        var logouts = 0
        sessions.bindLogin(object : HostLoginBackend<Bitmap> {
            override fun open(emit: (HostLoginState<Bitmap>) -> Unit): Closeable = Closeable {}
            override fun logout() { logouts++ }
        })
        val rendered = sessions.snapshot()
        sessions.logout(rendered)
        assertEquals(1, logouts)
        assertEquals(rendered.generation + 2, sessions.snapshot().generation)
    }
}
