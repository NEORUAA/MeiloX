package com.ljyh.mei.parasite

import com.google.gson.Gson
import com.ljyh.mei.data.model.api.GetUserPhotoAlbum
import com.ljyh.mei.data.model.api.GetUserPlaylist
import com.ljyh.mei.data.network.api.ApiService
import com.ljyh.mei.data.network.api.MeloXDirectService
import com.ljyh.mei.data.repository.submitPlaybackHistoryLog
import com.ljyh.mei.data.repository.diagnosticSummary
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.EventListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class HostCallFactoryTest {
    @Test fun convertsTypedJsonWithoutLosingNestedValuesOrIntegerPrecision() {
        val backend = Backend()
        val call = factory(backend).newCall(post("""{"id":9223372036854775806,"flag":true,"text":"a+b&c","ids":[1,2],"object":{"x":1},"nil":null}"""))
        call.execute().use {
            assertEquals("official-json", it.header("X-MeiloX-Transport"))
            assertNotNull(it.request.tag(HostSessionStamp::class.java))
            assertEquals("{\"code\":200}", it.body!!.string())
        }
        assertEquals("search/get", backend.path)
        assertEquals(mapOf("id" to "9223372036854775806", "flag" to "true", "text" to "a+b&c",
            "ids" to "[1,2]", "object" to "{\"x\":1}", "nil" to "null"), backend.parameters)
        assertThrows(IllegalStateException::class.java) { call.execute() }
    }

    @Test fun convertsGetQueriesAndAcceptsEmptyPostBodies() {
        val backend = Backend()
        val factory = factory(backend)
        factory.newCall(Request.Builder().url("https://interface.music.163.com/api/music/audio/match?rawdata=a%2Bb%2Fc%3D&duration=5").build()).execute().close()
        assertEquals(mapOf("rawdata" to "a+b/c=", "duration" to "5"), backend.parameters)
        factory.newCall(Request.Builder().url("https://music.163.com/api/nuser/account/get").post(ByteArray(0).toRequestBody()).build()).execute().close()
        assertTrue(backend.parameters.isEmpty())
        listOf("weapi", "eapi").forEach { prefix ->
            factory.newCall(Request.Builder().url("https://music.163.com/$prefix/subcount").build()).execute().close()
            assertEquals("subcount", backend.path)
        }
    }

    @Test fun maintainsCallTagsAndListenerLifetimeWithoutInventingNetworkEvents() {
        val call = factory(Backend()).newCall(post().newBuilder().tag(String::class.java, "seed").build())
        assertEquals("seed", call.tag(String::class))
        assertEquals(42, call.tag(Int::class) { 42 })
        assertEquals(42, call.tag(Int::class.java))
        val copy = call.clone()
        assertNull(copy.tag(Int::class))
        assertEquals("seed", copy.tag(String::class))
        val events = mutableListOf<String>()
        call.addEventListener(object : EventListener() {
            override fun callStart(call: Call) { events += "start" }
            override fun callEnd(call: Call) { events += "end" }
        })
        call.execute().use { it.body.string() }
        copy.execute().close()
        assertEquals(listOf("start", "end"), events)
    }

    @Test fun callbackExceptionsDoNotTriggerAnotherCallback() {
        val failures = AtomicInteger()
        val call = factory(Backend()).newCall(post())
        assertThrows(RejectedExecutionException::class.java) {
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { failures.incrementAndGet() }
                override fun onResponse(call: Call, response: Response) {
                    response.close()
                    throw RejectedExecutionException("callback")
                }
            })
        }
        assertEquals(0, failures.get())
    }

    @Test fun rejectsUnknownOriginsCredentialsUploadsAndAmbiguousParameters() {
        val factory = factory(Backend())
        listOf("https://example.org/api/test", "http://music.163.com/api/test", "https://music.163.com:8443/api/test",
            "https://user@music.163.com/api/test", "https://music.163.com/api/test#part",
            "https://music.163.com/unknown/test", "https://music.163.com/api/a%2Fb", "https://music.163.com/api/test?a=1&a=2").forEach {
            assertThrows(IllegalArgumentException::class.java) { factory.newCall(Request.Builder().url(it).build()) }
        }
        listOf("Cookie", "Authorization").forEach {
            assertThrows(IllegalArgumentException::class.java) { factory.newCall(post().newBuilder().header(it, "test").build()) }
        }
        assertThrows(IllegalArgumentException::class.java) { factory.newCall(post("[]")) }
        assertThrows(IllegalArgumentException::class.java) { factory.newCall(post("{\"header\":{}}")) }
        assertThrows(IllegalArgumentException::class.java) {
            factory.newCall(post("{\"a\":1}").newBuilder().url("https://music.163.com/api/test?a=2").build())
        }
        assertThrows(IllegalArgumentException::class.java) {
            factory.newCall(post().newBuilder().post("file".toRequestBody("application/octet-stream".toMediaType())).build())
        }
    }

    @Test fun cancellationBeforeDispatchAndClonesPreserveOwnership() {
        val backend = Backend()
        val bridge = bridge(backend)
        val factory = HostCallFactory(bridge, Executor { it.run() })
        val original = factory.newCall(post())
        val copy = original.clone()
        original.cancel()
        assertThrows(IOException::class.java) { original.execute() }
        assertEquals(0, backend.executions.get())
        bridge.sessions.invalidate()
        assertThrows(HostSessionChangedException::class.java) { copy.execute() }
        factory.newCall(post()).execute().close()
        assertEquals(1, backend.executions.get())
    }

    @Test fun doesNotReadAResponseFromAnOldSession() {
        val backend = Backend()
        val bridge = bridge(backend)
        val response = HostCallFactory(bridge, Executor { it.run() }).newCall(post()).execute()
        bridge.sessions.invalidate()
        response.use { assertThrows(HostSessionChangedException::class.java) { it.body!!.string() } }
    }

    @Test fun queuedCancellationDeliversExactlyOneFailure() {
        val backend = Backend()
        var queued: Runnable? = null
        val factory = HostCallFactory(bridge(backend), Executor { queued = it })
        val callback = ResultCallback()
        val call = factory.newCall(post())
        call.enqueue(callback)
        call.cancel()
        queued!!.run()
        assertEquals(1, callback.failures)
        assertEquals(0, callback.responses)
        assertEquals(0, backend.executions.get())
    }

    @Test fun reportsQueueRejectionAndBackendFailuresOnce() {
        val backend = Backend()
        val rejected = ResultCallback()
        HostCallFactory(bridge(backend), Executor { throw RejectedExecutionException() })
            .newCall(post()).enqueue(rejected)
        assertEquals(1, rejected.failures)
        val failure = ResultCallback()
        backend.action = { throw IOException("test") }
        factory(backend).newCall(post()).enqueue(failure)
        assertEquals(1, failure.failures)
        assertEquals(1, backend.closed.get())
    }

    @Test fun timeoutCancelsTheHostAndReleasesResources() {
        val backend = Backend()
        val canceled = CountDownLatch(1)
        backend.action = { check(canceled.await(5, TimeUnit.SECONDS)); "{}" }
        backend.onCancel = { canceled.countDown() }
        val call = factory(backend).newCall(post())
        call.timeout().timeout(30, TimeUnit.MILLISECONDS)
        assertThrows(InterruptedIOException::class.java) { call.execute() }
        assertTrue(call.isCanceled())
        assertEquals(1, backend.closed.get())
    }

    @Test fun coroutineCancellationReachesTheHost() = runBlocking {
        val backend = Backend()
        val started = CountDownLatch(1)
        val canceled = CountDownLatch(1)
        val closed = CountDownLatch(1)
        backend.action = { started.countDown(); check(canceled.await(5, TimeUnit.SECONDS)); "{}" }
        backend.onCancel = { canceled.countDown() }
        backend.onClose = { closed.countDown() }
        val service = retrofit(HostCallFactory(bridge(backend))).create(MeloXDirectService::class.java)
        val job = launch(Dispatchers.Default) { service.post("/api/search/get") }
        try {
            assertTrue(withContext(Dispatchers.IO) { started.await(5, TimeUnit.SECONDS) })
            job.cancelAndJoin()
            assertTrue(withContext(Dispatchers.IO) { closed.await(5, TimeUnit.SECONDS) })
            assertEquals(0L, canceled.count)
        } finally { canceled.countDown(); job.cancelAndJoin() }
    }

    @Test fun typedRetrofitAndPlaybackDiagnosticsUseTheHostEnvelope() = runBlocking {
        val backend = Backend()
        val service = retrofit(factory(backend)).create(MeloXDirectService::class.java)
        assertEquals(200, service.post("/api/search/get", mapOf("limit" to 1))["code"].asInt)
        val result = submitPlaybackHistoryLog(service, "startplay", mapOf("id" to 1L))
        assertTrue(result.hostAccepted && result.businessAccepted)
        assertFalse(result.httpAccepted)
        assertNull(result.httpStatus)
        assertEquals("feedback/weblog", backend.path)
        val photoBody = Gson().toJsonTree(GetUserPhotoAlbum("1")).asJsonObject
        assertFalse(photoBody.has("header") || photoBody.has("e_r"))
    }

    @Test fun convertsTheExistingTypedPlaylistService() = runBlocking {
        val backend = Backend().apply { action = { "{\"code\":200,\"more\":false,\"playlist\":[]}" } }
        val service = retrofit(factory(backend)).create(ApiService::class.java)
        assertEquals(200, service.getUserPlaylist(GetUserPlaylist("1", limit = "1")).code)
        assertEquals("user/playlist", backend.path)
        assertEquals("1", backend.parameters["uid"])
    }

    @Test fun syntheticEnvelopeDoesNotTurnABusinessRejectionIntoSuccess() = runBlocking {
        val backend = Backend().apply { action = { "{\"code\":500}" } }
        val service = retrofit(factory(backend)).create(MeloXDirectService::class.java)
        val result = submitPlaybackHistoryLog(service, "play", mapOf("id" to 1L))
        assertTrue(result.hostAccepted)
        assertFalse(result.httpAccepted || result.businessAccepted)
        assertNull(result.httpStatus)
        assertTrue(result.diagnosticSummary().contains("business response rejected"))
    }

    private fun bridge(backend: Backend) = HostRequestBridge(HostSessionBridge()).apply { bind(backend) }
    private fun factory(backend: Backend) = HostCallFactory(bridge(backend), Executor { it.run() })
    private fun retrofit(factory: HostCallFactory) = Retrofit.Builder().baseUrl("https://music.163.com")
        .callFactory(factory).addConverterFactory(GsonConverterFactory.create()).build()
    private fun post(json: String = "{}") = Request.Builder().url("https://music.163.com/api/search/get/")
        .post(json.toRequestBody("application/json".toMediaType())).build()

    private class ResultCallback : Callback {
        var failures = 0
        var responses = 0
        override fun onFailure(call: Call, e: IOException) { failures++ }
        override fun onResponse(call: Call, response: Response) { responses++; response.close() }
    }

    private class Backend : HostRequestBackend {
        var path = ""
        var parameters = emptyMap<String, String>()
        var action: () -> String = { "{\"code\":200}" }
        var onCancel: () -> Unit = {}
        var onClose: () -> Unit = {}
        val executions = AtomicInteger()
        val closed = AtomicInteger()
        override fun sessionIdentity() = HostSessionIdentity(1, true, false)
        override fun open(path: String, parameters: Map<String, String>): HostPendingRequest {
            this.path = path
            this.parameters = parameters
            return object : HostPendingRequest {
                override fun execute(): String { executions.incrementAndGet(); return action() }
                override fun cancel() = onCancel()
                override fun close() { closed.incrementAndGet(); onClose() }
            }
        }
    }
}
