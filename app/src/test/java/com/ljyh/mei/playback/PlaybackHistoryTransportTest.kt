package com.ljyh.mei.playback

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.ljyh.mei.data.network.api.MeloXDirectService
import com.ljyh.mei.data.repository.PLAYBACK_HISTORY_DIAGNOSTIC_ENDPOINT
import com.ljyh.mei.data.repository.playbackHistoryPlayFields
import com.ljyh.mei.data.repository.submitPlaybackHistoryLog
import com.ljyh.mei.data.repository.submitPlaybackHistoryStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

class PlaybackHistoryTransportTest {
    @Test
    fun playbackStartUsesRelativeRouteAndWaitsForStartplayBeforePlay() = runBlocking {
        val transport = MemoryTransport(
            outcomes = listOf(
                TransportOutcome.Reply(200, "{\"code\":200}"),
                TransportOutcome.Reply(200, "{\"code\":200}"),
            ),
            blockFirstResponse = true,
        )
        val service = serviceWith(transport)
        val actions = mutableListOf<String>()
        val fields = mutableListOf<Map<String, Any>>()
        val job = async(Dispatchers.IO) {
            submitPlaybackHistoryStart(
                songId = 123456L,
                sourceId = 0L,
                source = "unknown-source",
            ) { action, payload ->
                synchronized(actions) {
                    actions += action
                    fields += payload
                }
                submitPlaybackHistoryLog(service, action, payload)
            }
        }

        assertTrue(transport.firstRequestStarted.await(2, TimeUnit.SECONDS))
        assertEquals(1, transport.requests.size)
        synchronized(actions) {
            assertEquals(listOf("startplay"), actions)
            assertEquals(1, fields.size)
            assertEquals("123456", fields.single()["sourceId"])
        }

        val firstPayload = playbackPayload(transport.requests.single())
        assertEquals("startplay", firstPayload["action"].asString)
        val firstFields = firstPayload["json"].asJsonObject
        assertEquals("123456", firstFields["id"].asString)
        assertEquals("123456", firstFields["sourceId"].asString)
        assertEquals("track", firstFields["source"].asString)
        assertEquals("track", firstFields["sourcetype"].asString)
        assertEquals("id=123456", firstFields["content"].asString)
        assertFalse(transport.requests.any { it.url.host != "127.0.0.1" })
        assertFalse(transport.requests.any { it.url.toString().contains("clientlog") })
        assertFalse(transport.requests.any { it.url.toString().contains("0.0.0.0") })
        assertFalse(transport.requests.any { it.url.toString() == PLAYBACK_HISTORY_DIAGNOSTIC_ENDPOINT })

        transport.releaseFirstResponse()
        val result = job.await()

        assertTrue(result.accepted)
        assertEquals(2, transport.requests.size)
        synchronized(actions) {
            assertEquals(listOf("startplay", "play"), actions)
            assertEquals(2, fields.size)
        }
        val playPayload = playbackPayload(transport.requests[1])
        assertEquals("play", playPayload["action"].asString)
        val playFields = playPayload["json"].asJsonObject
        assertEquals("123456", playFields["id"].asString)
        assertEquals("123456", playFields["sourceId"].asString)
        assertEquals("track", playFields["source"].asString)
        assertEquals("0", playFields["time"].asString)
        assertEquals("id=123456", playFields["content"].asString)
        assertEquals("/api/feedback/weblog", transport.requests[0].url.encodedPath)
        assertEquals("/api/feedback/weblog", transport.requests[1].url.encodedPath)
    }

    @Test
    fun durationUpdateReusesPlayPayloadWithFinalActualPlayingTime() = runBlocking {
        val transport = MemoryTransport(
            outcomes = listOf(TransportOutcome.Reply(200, "{\"code\":200}")),
        )
        val fields = playbackHistoryPlayFields(
            songId = 123456L,
            sourceId = 789L,
            source = "album",
            timeSeconds = 37L,
        )

        val response = submitPlaybackHistoryLog(
            service = serviceWith(transport),
            action = "play",
            fields = fields,
        )

        assertTrue(response.businessAccepted)
        assertEquals(1, transport.requests.size)
        val payload = playbackPayload(transport.requests.single())
        assertEquals("play", payload["action"].asString)
        assertEquals("37", payload["json"].asJsonObject["time"].asString)
        assertEquals("playend", payload["json"].asJsonObject["end"].asString)
        assertEquals("/api/feedback/weblog", transport.requests.single().url.encodedPath)
    }

    @Test
    fun networkFailureHasNullHttpStatusAndNoBusinessCode() = runBlocking {
        val transport = MemoryTransport(
            outcomes = listOf(
                TransportOutcome.Failure(IOException("loopback transport failure")),
            ),
        )

        val response = submitPlaybackHistoryLog(
            service = serviceWith(transport),
            action = "play",
            fields = mapOf("id" to "1"),
        )

        assertFalse(response.httpAccepted)
        assertNull(response.httpStatus)
        assertNull(response.code)
        assertFalse(response.businessAccepted)
    }

    @Test
    fun http403BodyCodeIsKeptSeparateFromHttpStatus() = runBlocking {
        val transport = MemoryTransport(
            outcomes = listOf(
                TransportOutcome.Reply(
                    code = 403,
                    body = "{\"code\":301,\"msg\":\"forbidden\"}",
                    message = "Forbidden",
                ),
            ),
        )

        val response = submitPlaybackHistoryLog(
            service = serviceWith(transport),
            action = "play",
            fields = mapOf("id" to "1"),
        )

        assertFalse(response.httpAccepted)
        assertEquals(403, response.httpStatus)
        assertEquals(301, response.code)
        assertEquals("forbidden", response.message)
        assertFalse(response.businessAccepted)
    }

    @Test
    fun malformedSuccessfulBodyIsHttpAcceptedButNotBusinessAccepted() = runBlocking {
        val transport = MemoryTransport(
            outcomes = listOf(
                TransportOutcome.Reply(200, "not-json"),
            ),
        )

        val response = submitPlaybackHistoryLog(
            service = serviceWith(transport),
            action = "play",
            fields = mapOf("id" to "1"),
        )

        assertTrue(response.httpAccepted)
        assertEquals(200, response.httpStatus)
        assertNull(response.code)
        assertFalse(response.businessAccepted)
    }

    @Test
    fun streamingBodyReadFailurePreservesHttpStatusAndClosesBody() = runBlocking {
        val bodyClosed = CountDownLatch(1)
        val body = ThrowingResponseBody(bodyClosed)
        val transport = MemoryTransport(
            outcomes = listOf(
                TransportOutcome.BodyReply(code = 200, body = body),
            ),
        )

        val response = submitPlaybackHistoryLog(
            service = serviceWith(transport),
            action = "play",
            fields = mapOf("id" to "1"),
        )

        assertTrue(response.httpAccepted)
        assertEquals(200, response.httpStatus)
        assertNull(response.code)
        assertEquals("IOException", response.exceptionType)
        assertTrue(response.failureReason.orEmpty().contains("response body read failed"))
        assertTrue(response.failureReason.orEmpty().contains("synthetic body read"))
        assertFalse(response.businessAccepted)
        assertTrue(bodyClosed.await(2, TimeUnit.SECONDS))
    }

    @Test
    fun cancellationOfPlaybackLogIsPropagated() = runBlocking {
        val transport = MemoryTransport(
            outcomes = listOf(
                TransportOutcome.Reply(200, "{\"code\":200}"),
            ),
            blockFirstResponse = true,
        )
        val job = async(Dispatchers.IO) {
            submitPlaybackHistoryLog(
                service = serviceWith(transport),
                action = "play",
                fields = mapOf("id" to "1"),
            )
        }

        assertTrue(transport.firstRequestStarted.await(2, TimeUnit.SECONDS))
        job.cancel()
        assertTrue(transport.cancellationObserved.await(2, TimeUnit.SECONDS))
        var propagated = false
        try {
            job.await()
        } catch (_: CancellationException) {
            propagated = true
        } finally {
            transport.releaseFirstResponse()
        }
        job.join()
        assertTrue(propagated)
    }

    @Test
    fun startOrPlayBusinessFailureMakesWholeScrobbleFalse() = runBlocking {
        val startFailureTransport = MemoryTransport(
            outcomes = listOf(
                TransportOutcome.Reply(200, "{\"code\":500}"),
                TransportOutcome.Reply(200, "{\"code\":200}"),
            ),
        )
        val startFailure = submitPlaybackStart(serviceWith(startFailureTransport))
        assertFalse(startFailure.start.businessAccepted)
        assertTrue(startFailure.play.businessAccepted)
        assertFalse(startFailure.accepted)

        val playFailureTransport = MemoryTransport(
            outcomes = listOf(
                TransportOutcome.Reply(200, "{\"code\":200}"),
                TransportOutcome.Reply(200, "{\"code\":500}"),
            ),
        )
        val playFailure = submitPlaybackStart(serviceWith(playFailureTransport))
        assertTrue(playFailure.start.businessAccepted)
        assertFalse(playFailure.play.businessAccepted)
        assertFalse(playFailure.accepted)
    }

    private suspend fun submitPlaybackStart(service: MeloXDirectService) =
        submitPlaybackHistoryStart(
            songId = 123456L,
            sourceId = 789L,
            source = "album",
        ) { action, fields ->
            submitPlaybackHistoryLog(service, action, fields)
        }

    private fun serviceWith(transport: MemoryTransport): MeloXDirectService =
        Retrofit.Builder()
            .baseUrl("http://127.0.0.1/")
            .addConverterFactory(GsonConverterFactory.create())
            .client(
                OkHttpClient.Builder()
                    .addInterceptor(transport)
                    .build(),
            )
            .build()
            .create(MeloXDirectService::class.java)

    private fun playbackPayload(request: Request): JsonObject {
        val buffer = Buffer()
        checkNotNull(request.body).writeTo(buffer)
        val requestJson = JsonParser.parseString(buffer.readUtf8()).asJsonObject
        return JsonParser.parseString(requestJson["logs"].asString)
            .asJsonArray
            .single()
            .asJsonObject
    }

    private sealed interface TransportOutcome {
        data class Reply(
            val code: Int,
            val body: String,
            val message: String = "OK",
        ) : TransportOutcome

        data class BodyReply(
            val code: Int,
            val body: ResponseBody,
            val message: String = "OK",
        ) : TransportOutcome

        data class Failure(val error: IOException) : TransportOutcome
    }

    private class MemoryTransport(
        outcomes: List<TransportOutcome>,
        private val blockFirstResponse: Boolean = false,
    ) : Interceptor {
        private val outcomes = ArrayDeque(outcomes)
        val requests = CopyOnWriteArrayList<Request>()
        val firstRequestStarted = CountDownLatch(1)
        val firstResponseReleased = CountDownLatch(1)
        val cancellationObserved = CountDownLatch(1)

        fun releaseFirstResponse() = firstResponseReleased.countDown()

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            requests += request
            firstRequestStarted.countDown()
            if (blockFirstResponse && requests.size == 1) {
                while (!firstResponseReleased.await(10, TimeUnit.MILLISECONDS)) {
                    if (chain.call().isCanceled()) {
                        cancellationObserved.countDown()
                        throw IOException("call canceled")
                    }
                }
            }

            val outcome = synchronized(outcomes) {
                check(!outcomes.isEmpty()) { "No memory response queued for ${request.url}" }
                outcomes.removeFirst()
            }
            return when (outcome) {
                is TransportOutcome.Failure -> throw outcome.error
                is TransportOutcome.Reply -> Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(outcome.code)
                    .message(outcome.message)
                    .body(outcome.body.toResponseBody("application/json".toMediaType()))
                    .build()
                is TransportOutcome.BodyReply -> Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(outcome.code)
                    .message(outcome.message)
                    .body(outcome.body)
                    .build()
            }
        }
    }

    private class ThrowingResponseBody(
        private val bodyClosed: CountDownLatch,
    ) : ResponseBody() {
        override fun contentType() = "application/json".toMediaType()

        override fun contentLength() = -1L

        override fun source(): BufferedSource = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long {
                throw IOException("synthetic body read")
            }

            override fun timeout(): Timeout = Timeout.NONE

            override fun close() {
                bodyClosed.countDown()
            }
        }.buffer()

        override fun close() {
            bodyClosed.countDown()
        }
    }
}
