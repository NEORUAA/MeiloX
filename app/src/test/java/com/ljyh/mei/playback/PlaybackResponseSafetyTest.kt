package com.ljyh.mei.playback

import com.google.gson.JsonObject
import com.ljyh.mei.data.network.api.MeloXDirectService
import com.ljyh.mei.data.repository.submitPlaybackHistoryLog
import com.ljyh.mei.di.MAX_PLAYBACK_HISTORY_RESPONSE_BYTES
import com.ljyh.mei.di.PlaybackResponseBodyException
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class PlaybackResponseSafetyTest {
    @Test
    fun smallSuccessfulJsonRemainsAccepted() = runBlocking {
        val response = submitPlaybackHistoryLog(
            service = responseService(Response.success("{\"code\":200}".jsonBody())),
            action = "play",
            fields = mapOf("id" to "1"),
        )

        assertTrue(response.httpAccepted)
        assertTrue(response.businessAccepted)
    }

    @Test
    fun valid200PrefixWithPaddingCannotBeAccepted() = runBlocking {
        val response = submitPlaybackHistoryLog(
            service = responseService(
                Response.success(
                    ("{\"code\":200}" + "x".repeat(MAX_PLAYBACK_HISTORY_RESPONSE_BYTES))
                        .jsonBody(),
                ),
            ),
            action = "play",
            fields = mapOf("id" to "1"),
        )

        assertTrue(response.httpAccepted)
        assertNull(response.code)
        assertFalse(response.businessAccepted)
    }

    @Test
    fun http200ReadFailureKeepsHttpAcceptedButNotBusinessAccepted() = runBlocking {
        val closed = AtomicBoolean(false)
        val response = submitPlaybackHistoryLog(
            service = responseService(Response.success(FailingBody(closed))),
            action = "play",
            fields = mapOf("id" to "1"),
        )

        assertTrue(response.httpAccepted)
        assertNull(response.code)
        assertFalse(response.businessAccepted)
        assertTrue(closed.get())
    }

    @Test
    fun http403ReadFailureKeepsStatusSeparateFromBusinessCode() = runBlocking {
        val closed = AtomicBoolean(false)
        val response = submitPlaybackHistoryLog(
            service = responseService(Response.error(403, FailingBody(closed))),
            action = "play",
            fields = mapOf("id" to "1"),
        )

        assertFalse(response.httpAccepted)
        assertFalse(response.businessAccepted)
        assertTrue(response.httpStatus == 403)
        assertNull(response.code)
        assertTrue(closed.get())
    }

    @Test
    fun typedHttp200BodyFailureKeepsHttpAcceptedButNotBusinessAccepted() = runBlocking {
        val response = submitPlaybackHistoryLog(
            service = throwingService(
                PlaybackResponseBodyException(
                    httpStatus = 200,
                    failureKind = "ResponseBodyReadFailure",
                    failureReason = "response body read failed",
                ),
            ),
            action = "play",
            fields = mapOf("id" to "1"),
        )

        assertTrue(response.httpAccepted)
        assertTrue(response.httpStatus == 200)
        assertNull(response.code)
        assertFalse(response.businessAccepted)
    }

    @Test
    fun typedHttp200OversizeKeepsHttpAcceptedButNotBusinessAccepted() = runBlocking {
        val response = submitPlaybackHistoryLog(
            service = throwingService(
                PlaybackResponseBodyException(
                    httpStatus = 200,
                    failureKind = "ResponseBodyTooLarge",
                    failureReason = "response body exceeds ${MAX_PLAYBACK_HISTORY_RESPONSE_BYTES} bytes",
                ),
            ),
            action = "play",
            fields = mapOf("id" to "1"),
        )

        assertTrue(response.httpAccepted)
        assertTrue(response.httpStatus == 200)
        assertNull(response.code)
        assertFalse(response.businessAccepted)
    }

    private fun responseService(response: Response<okhttp3.ResponseBody>): MeloXDirectService =
        object : MeloXDirectService {
            override suspend fun post(
                path: String,
                body: Map<String, @JvmSuppressWildcards Any>,
                headers: Map<String, String>,
            ): JsonObject = error("unused")

            override suspend fun postPlaybackRaw(
                path: String,
                body: Map<String, @JvmSuppressWildcards Any>,
                headers: Map<String, String>,
            ): Response<okhttp3.ResponseBody> = response
        }

    private fun throwingService(failure: Exception): MeloXDirectService =
        object : MeloXDirectService {
            override suspend fun post(
                path: String,
                body: Map<String, @JvmSuppressWildcards Any>,
                headers: Map<String, String>,
            ): JsonObject = error("unused")

            override suspend fun postPlaybackRaw(
                path: String,
                body: Map<String, @JvmSuppressWildcards Any>,
                headers: Map<String, String>,
            ): Response<okhttp3.ResponseBody> = throw failure
        }

    private fun String.jsonBody(): ResponseBody = toResponseBody("application/json".toMediaType())

    private class FailingBody(
        private val closed: AtomicBoolean,
    ) : ResponseBody() {
        override fun contentType() = "application/json".toMediaType()

        override fun contentLength() = -1L

        override fun source(): BufferedSource = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long {
                throw IOException("synthetic playback body read failure")
            }

            override fun timeout() = Timeout.NONE

            override fun close() {
                closed.set(true)
            }
        }.buffer()

        override fun close() {
            closed.set(true)
        }
    }
}
