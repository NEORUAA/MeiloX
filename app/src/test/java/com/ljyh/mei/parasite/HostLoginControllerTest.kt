package com.ljyh.mei.parasite

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostLoginControllerTest {
    private class Backend {
        val events = mutableListOf<(HostLoginState<String>) -> Unit>()
        val closed = mutableListOf<Int>()
        var authenticated = true
        var onOpen: ((HostLoginState<String>) -> Unit) -> Unit = {}
        val controller = HostLoginController<String>(open = { emit ->
            val id = events.size
            events += emit
            onOpen(emit)
            Closeable { closed += id }
        }, authenticated = { authenticated })
        fun emit(index: Int, status: HostLoginStatus, qr: String? = null) = events[index](HostLoginState(status, qr))
    }

    @Test fun startsOnceAndPublishesQrWithoutCredentials() {
        val backend = Backend()
        backend.controller.start()
        backend.controller.start()
        assertEquals(1, backend.events.size)
        assertEquals(HostLoginStatus.LOADING, backend.controller.state.value.status)
        backend.emit(0, HostLoginStatus.WAITING, "qr")
        assertEquals("qr", backend.controller.state.value.qr)
    }

    @Test fun refreshClosesOldAttemptAndRejectsEveryOldEvent() {
        val backend = Backend()
        backend.controller.start()
        backend.emit(0, HostLoginStatus.WAITING, "old")
        backend.controller.refresh()
        assertEquals(listOf(0), backend.closed)
        assertNull(backend.controller.state.value.qr)
        HostLoginStatus.entries.forEach { backend.emit(0, it, "stale") }
        assertEquals(HostLoginStatus.LOADING, backend.controller.state.value.status)
        backend.emit(1, HostLoginStatus.WAITING, "new")
        assertEquals("new", backend.controller.state.value.qr)
    }

    @Test fun closeReleasesQrAndAllowsFreshForegroundAttempt() {
        val backend = Backend()
        backend.controller.start()
        backend.emit(0, HostLoginStatus.WAITING, "qr")
        backend.controller.close()
        backend.controller.close()
        backend.emit(0, HostLoginStatus.SUCCESS)
        assertEquals(listOf(0), backend.closed)
        assertEquals(HostLoginState<String>(), backend.controller.state.value)
        backend.controller.start()
        assertEquals(2, backend.events.size)
    }

    @Test fun expiryAndErrorAreTerminalUntilRefreshed() {
        listOf(HostLoginStatus.EXPIRED, HostLoginStatus.ERROR).forEach { status ->
            val backend = Backend()
            backend.controller.start()
            backend.emit(0, status)
            backend.emit(0, HostLoginStatus.WAITING, "late")
            assertEquals(status, backend.controller.state.value.status)
            assertEquals(listOf(0), backend.closed)
            backend.controller.refresh()
            assertEquals(HostLoginStatus.LOADING, backend.controller.state.value.status)
        }
    }

    @Test fun authenticationSuccessRequiresTheOfficialSession() {
        val backend = Backend()
        backend.authenticated = false
        backend.controller.start()
        backend.emit(0, HostLoginStatus.SUCCESS)
        assertEquals(HostLoginStatus.ERROR, backend.controller.state.value.status)
        assertEquals(listOf(0), backend.closed)
    }

    @Test fun successSurvivesStopAndDoesNotStartAnotherAttempt() {
        val backend = Backend()
        backend.controller.start()
        backend.emit(0, HostLoginStatus.SUCCESS)
        backend.controller.close()
        backend.controller.start()
        assertEquals(HostLoginStatus.SUCCESS, backend.controller.state.value.status)
        assertEquals(1, backend.events.size)
        assertEquals(listOf(0), backend.closed)
    }

    @Test fun synchronousTerminalObserverClosesHandleReturnedLater() {
        val backend = Backend()
        backend.onOpen = { it(HostLoginState(HostLoginStatus.SUCCESS)) }
        backend.controller.start()
        assertEquals(HostLoginStatus.SUCCESS, backend.controller.state.value.status)
        assertEquals(listOf(0), backend.closed)
        backend.controller.close()
        assertEquals(listOf(0), backend.closed)
    }

    @Test fun synchronousWaitingObserverRetainsLiveHandle() {
        val backend = Backend()
        backend.onOpen = { it(HostLoginState(HostLoginStatus.WAITING, "qr")) }
        backend.controller.start()
        assertEquals("qr", backend.controller.state.value.qr)
        assertTrue(backend.closed.isEmpty())
        backend.controller.close()
        assertEquals(listOf(0), backend.closed)
    }

    @Test fun constructionFailureIsRetryableAndDoesNotPublishExceptionData() {
        val backend = Backend()
        backend.onOpen = { throw IOException("sensitive host error") }
        backend.controller.start()
        assertEquals(HostLoginState<String>(HostLoginStatus.ERROR), backend.controller.state.value)
        backend.onOpen = {}
        backend.controller.refresh()
        backend.emit(1, HostLoginStatus.WAITING, "qr")
        assertEquals("qr", backend.controller.state.value.qr)
    }

    @Test fun callbacksOnOtherThreadsCannotPublish() {
        val backend = Backend()
        backend.controller.start()
        val error = AtomicReference<Throwable>()
        Thread {
            try { backend.emit(0, HostLoginStatus.SUCCESS) } catch (caught: Throwable) { error.set(caught) }
        }.apply { start(); join(2000) }
        assertTrue(error.get() is IllegalStateException)
        assertEquals(HostLoginStatus.LOADING, backend.controller.state.value.status)
    }
}
