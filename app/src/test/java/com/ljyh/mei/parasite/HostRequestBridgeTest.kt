package com.ljyh.mei.parasite

import com.ljyh.mei.data.session.SessionChangedException
import com.ljyh.mei.data.session.SessionIdentity
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HostRequestBridgeTest {
    @Test fun freezesParametersAndReturnsTheOwningSession() {
        val backend = Backend()
        val bridge = bridge(backend)
        val parameters = mutableMapOf("limit" to "1")
        val call = bridge.newCall("user/playlist", parameters)
        parameters["limit"] = "10"
        val response = call.execute()
        assertEquals("1", backend.parameters["limit"])
        assertEquals(backend.identity, response.session.identity)
        assertEquals("{}", response.body)
        assertEquals(1, backend.closed.get())
        assertThrows(IllegalStateException::class.java) { call.execute() }
    }

    @Test fun rejectsUnboundTransportAndAuthenticationOverrides() {
        assertThrows(IOException::class.java) { HostRequestBridge(HostSessionBridge()).newCall("search/get") }
        val backend = Backend()
        val bridge = bridge(backend)
        listOf("/api/test", "https://host/test", "../test", "a/../b", "a//b", "a?uid=1").forEach {
            assertThrows(IllegalArgumentException::class.java) { bridge.newCall(it) }
        }
        listOf("Cookie", "MUSIC_U", "MUSIC_A", "__csrf", "csrf_token", "Authorization", "header").forEach {
            assertThrows(IllegalArgumentException::class.java) { bridge.newCall("search/get", mapOf(it to "test")) }
        }
        assertEquals(0, backend.opened.get())
    }

    @Test fun cancelsBeforeOpeningTheHostRequest() {
        val backend = Backend()
        val call = bridge(backend).newCall("search/get")
        call.cancel()
        assertThrows(IOException::class.java) { call.execute() }
        assertTrue(call.isCanceled && call.isExecuted)
        assertEquals(0, backend.opened.get())
    }

    @Test fun cancelsDuringHostRequestCreationWithoutExecutingIt() {
        val backend = Backend()
        val call = bridge(backend).newCall("search/get")
        backend.onOpen = { call.cancel() }
        assertThrows(IOException::class.java) { call.execute() }
        assertEquals(1, backend.canceled.get())
        assertEquals(0, backend.executed.get())
        assertEquals(1, backend.closed.get())
    }

    @Test fun rejectsAnAccountChangeBeforeDispatchAndOnReturn() {
        val backend = Backend()
        val bridge = bridge(backend)
        val first = bridge.newCall("search/get")
        backend.identity = SessionIdentity(2, true, false)
        assertThrows(SessionChangedException::class.java) { first.execute() }
        assertEquals(0, backend.opened.get())
        val next = bridge.newCall("search/get")
        backend.onExecute = { backend.identity = SessionIdentity(3, true, false); "{}" }
        assertThrows(SessionChangedException::class.java) { next.execute() }
        assertEquals(1, backend.closed.get())
    }

    @Test fun reauthorizationInvalidatesSameAccountRequestsAndTheirCopies() {
        val backend = Backend()
        val bridge = bridge(backend)
        val old = bridge.newCall("search/get")
        bridge.sessions.beginTransition().use {
            assertThrows(SessionChangedException::class.java) { bridge.newCall("search/get") }
        }
        assertThrows(SessionChangedException::class.java) { old.copy().execute() }
        assertEquals("{}", bridge.newCall("search/get").execute().body)
    }

    @Test fun nestedTransitionsRemainBlockedUntilAllOwnersClose() {
        val bridge = bridge(Backend())
        val first = bridge.sessions.beginTransition()
        val second = bridge.sessions.beginTransition()
        first.close()
        first.close()
        assertThrows(SessionChangedException::class.java) { bridge.sessions.snapshot() }
        second.close()
        assertTrue(bridge.sessions.snapshot().identity.authenticated)
    }

    @Test fun cancelsInFlightTransportWhenTheSessionIsInvalidated() {
        val backend = Backend()
        val bridge = bridge(backend)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        backend.onExecute = {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            "{}"
        }
        backend.onCancel = { release.countDown() }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val call = bridge.newCall("search/get")
            val result = executor.submit<HostResponse> { call.execute() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            bridge.sessions.invalidate()
            val error = assertThrows(ExecutionException::class.java) { result.get(5, TimeUnit.SECONDS) }
            assertTrue(error.cause is IOException)
            assertTrue(call.isCanceled)
            assertTrue(backend.canceled.get() > 0)
            assertEquals(1, backend.closed.get())
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun preservesFailuresAndClosesHostResources() {
        val backend = Backend()
        val failure = IOException("test failure")
        backend.onExecute = { throw failure }
        assertSame(failure, assertThrows(IOException::class.java) { bridge(backend).newCall("search/get").execute() })
        assertEquals(1, backend.closed.get())
    }

    @Test fun delayedInvalidationDoesNotCancelNewGenerationRequests() {
        val sessions = HostSessionBridge()
        val publishing = CountDownLatch(1)
        val publish = CountDownLatch(1)
        sessions.onInvalidated {
            publishing.countDown()
            check(publish.await(5, TimeUnit.SECONDS))
        }
        val backend = Backend()
        val bridge = HostRequestBridge(sessions).apply { bind(backend) }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        backend.onExecute = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); "{}" }
        val executor = Executors.newFixedThreadPool(2)
        try {
            val invalidation = executor.submit { sessions.invalidate() }
            assertTrue(publishing.await(5, TimeUnit.SECONDS))
            val call = bridge.newCall("search/get")
            val result = executor.submit<HostResponse> { call.execute() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            publish.countDown()
            invalidation.get(5, TimeUnit.SECONDS)
            assertEquals(false, call.isCanceled)
            release.countDown()
            assertEquals("{}", result.get(5, TimeUnit.SECONDS).body)
        } finally {
            publish.countDown()
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun generationNotificationsNeverRegress() {
        val sessions = HostSessionBridge()
        val publishing = CountDownLatch(1)
        val release = CountDownLatch(1)
        sessions.onInvalidated { revision ->
            if (revision == 1L) {
                publishing.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
        }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val first = executor.submit { sessions.invalidate() }
            assertTrue(publishing.await(5, TimeUnit.SECONDS))
            sessions.invalidate()
            assertEquals(2L, sessions.changes.value)
            release.countDown()
            first.get(5, TimeUnit.SECONDS)
            assertEquals(2L, sessions.changes.value)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun permitsAnonymousReadsButRejectsAnonymousToAuthenticatedResults() {
        val backend = Backend().apply { identity = SessionIdentity(0, false, true) }
        val bridge = bridge(backend)
        assertTrue(bridge.newCall("search/get").execute().session.identity.anonymous)
        backend.onExecute = { backend.identity = SessionIdentity(1, true, false); "{}" }
        assertThrows(SessionChangedException::class.java) { bridge.newCall("search/get").execute() }
    }

    private fun bridge(backend: Backend) = HostRequestBridge(HostSessionBridge()).apply { bind(backend) }

    private class Backend : HostRequestBackend {
        @Volatile var identity = SessionIdentity(1, true, false)
        var parameters: Map<String, String> = emptyMap()
        var onOpen: () -> Unit = {}
        var onExecute: () -> String = { "{}" }
        var onCancel: () -> Unit = {}
        val opened = AtomicInteger()
        val executed = AtomicInteger()
        val canceled = AtomicInteger()
        val closed = AtomicInteger()
        override fun sessionIdentity() = identity
        override fun open(path: String, parameters: Map<String, String>): HostPendingRequest {
            opened.incrementAndGet()
            this.parameters = parameters
            onOpen()
            return object : HostPendingRequest {
                override fun execute(): String { executed.incrementAndGet(); return onExecute() }
                override fun cancel() { canceled.incrementAndGet(); onCancel() }
                override fun close() { closed.incrementAndGet() }
            }
        }
    }
}
