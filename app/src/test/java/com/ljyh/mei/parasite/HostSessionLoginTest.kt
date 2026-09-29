package com.ljyh.mei.parasite

import android.graphics.Bitmap
import java.io.Closeable
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HostSessionLoginTest {
    @Test fun missingHostLoginFailsClosedAndCanRetryOnceBound() {
        val sessions = HostSessionBridge()
        sessions.bind { HostSessionIdentity(1, true, false) }
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
        sessions.bind { HostSessionIdentity(1, true, false) }
        sessions.bindLogin(object : HostLoginBackend<Bitmap> {
            override fun open(emit: (HostLoginState<Bitmap>) -> Unit): Closeable = Closeable {}
            override fun logout() {
                assertThrows(HostSessionChangedException::class.java) { sessions.snapshot() }
                throw IOException("Official logout unavailable")
            }
        })
        val before = sessions.snapshot()
        assertThrows(IOException::class.java) { sessions.logout() }
        assertThrows(HostSessionChangedException::class.java) { sessions.requireCurrent(before) }
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
}
