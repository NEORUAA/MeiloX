package com.ljyh.mei.data.session

import java.io.IOException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class SessionCallFactoryTest {
    private val sessions = SessionStore().apply { bind { SessionIdentity(11, true, false) } }
    private val wire = FakeTransport()
    private val request = Request.Builder().url("https://example.invalid/api/test").build()
    private val factory = SessionCallFactory(sessions, wire)

    @Test fun untaggedRequestCapturesItsCreationTimeSession() {
        val owner = sessions.snapshot()
        val call = factory.newCall(request)
        assertEquals(owner, call.request().tag(SessionStamp::class.java))
        assertEquals("fixture", call.execute().use { it.body.string() })
        assertEquals(1, wire.calls.single().executions)
    }

    @Test fun explicitStaleOwnerDoesNotReachTheTransport() {
        val owner = sessions.snapshot()
        sessions.invalidate()
        assertThrows(SessionChangedException::class.java) {
            factory.newCall(request.newBuilder().tag(SessionStamp::class.java, owner).build())
        }
        assertTrue(wire.calls.isEmpty())
    }

    @Test fun queuedCallCannotInheritANewAccountAtDispatch() {
        val call = factory.newCall(request)
        sessions.invalidate()
        assertThrows(SessionChangedException::class.java) { call.execute() }
        assertEquals(0, wire.calls.single().executions)
    }

    @Test fun cloneRetainsTheOriginalOwner() {
        val call = factory.newCall(request)
        val clone = call.clone()
        assertEquals(call.request().tag(SessionStamp::class.java), clone.request().tag(SessionStamp::class.java))
        sessions.invalidate()
        assertThrows(SessionChangedException::class.java) { clone.execute() }
        assertThrows(SessionChangedException::class.java) { call.clone() }
        assertTrue(wire.calls.all { it.executions == 0 })
    }

    @Test fun invalidationCancelsAnEnqueuedWireCall() {
        val call = factory.newCall(request)
        val events = CallbackEvents()
        call.enqueue(events)
        sessions.invalidate()
        assertTrue(wire.calls.single().isCanceled())
        wire.calls.single().deliver()
        assertEquals(0, events.responses)
        assertEquals(1, events.failures.size)
        assertTrue(events.failures.single() is SessionChangedException)
    }

    @Test fun lateBodyCannotBeReadAfterAccountChange() {
        val response = factory.newCall(request).execute()
        sessions.invalidate()
        assertThrows(SessionChangedException::class.java) { response.use { it.body.string() } }
        assertTrue(wire.calls.single().isCanceled())
    }

    @Test fun invalidationDuringWireExecutionRejectsTheResponse() {
        wire.beforeResponse = { sessions.invalidate() }
        assertThrows(SessionChangedException::class.java) { factory.newCall(request).execute() }
        assertTrue(wire.calls.single().isCanceled())
    }

    @Test fun closedBodyDetachesItsInvalidationListener() {
        val response = factory.newCall(request).execute()
        response.close()
        sessions.invalidate()
        assertFalse(wire.calls.single().isCanceled())
    }

    @Test fun fullyConsumedBodyDetachesItsInvalidationListener() {
        factory.newCall(request).execute().use { it.body.string() }
        sessions.invalidate()
        assertFalse(wire.calls.single().isCanceled())
    }

    @Test fun asyncStaleDispatchCallsFailureExactlyOnceWithoutWireExecution() {
        val call = factory.newCall(request)
        sessions.invalidate()
        val events = CallbackEvents()
        call.enqueue(events)
        assertEquals(1, events.failures.size)
        assertEquals(0, events.responses)
        assertEquals(0, wire.calls.single().executions)
        assertThrows(IllegalStateException::class.java) { call.enqueue(events) }
        assertEquals(1, events.failures.size)
    }

    @Test fun cancelBeforeExecutePreventsWireExecution() {
        val call = factory.newCall(request)
        call.cancel()
        assertThrows(IOException::class.java) { call.execute() }
        assertEquals(0, wire.calls.single().executions)
    }

    @Test fun callbackReceivesWrapperAndCallbackExceptionIsNotReportedTwice() {
        val original = factory.newCall(request)
        var failures = 0
        original.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { failures++ }
            override fun onResponse(call: Call, response: Response) {
                assertSame(original, call)
                response.close()
                throw IllegalStateException("synthetic callback failure")
            }
        })
        assertThrows(IllegalStateException::class.java) { wire.calls.single().deliver() }
        assertEquals(0, failures)
    }

    private class CallbackEvents : Callback {
        var responses = 0
        val failures = mutableListOf<IOException>()
        override fun onFailure(call: Call, e: IOException) { failures += e }
        override fun onResponse(call: Call, response: Response) { responses++; response.close() }
    }

    private class FakeTransport : Call.Factory {
        val calls = mutableListOf<FakeCall>()
        var beforeResponse: () -> Unit = {}
        override fun newCall(request: Request): Call = FakeCall(request, { beforeResponse() }).also { calls += it }
    }

    private class FakeCall(
        private val request: Request,
        private val beforeResponse: () -> Unit,
        private val delegate: Call = OkHttpClient().newCall(request),
    ) : Call by delegate {
        var executions = 0
        var callback: Callback? = null
        override fun execute(): Response { executions++; return response() }
        override fun enqueue(responseCallback: Callback) { executions++; callback = responseCallback }
        fun deliver() { requireNotNull(callback).onResponse(this, response()) }
        private fun response(): Response {
            beforeResponse()
            return Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(200).message("synthetic").body("fixture".toResponseBody()).build()
        }
    }
}
