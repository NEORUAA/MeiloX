package com.ljyh.mei.di

import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.MediaType.Companion.toMediaType
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
import org.junit.Assert.assertTrue
import org.junit.Test

class NeteaseInterceptorPlaybackResponseTest {
    @Test
    fun smallJsonBodyReadsToEofWithoutExactReadFailure() {
        val bytes = NeteaseInterceptor().readPlaybackHistoryResponseBody(
            response(
                code = 200,
                body = "{\"code\":200}".toResponseBody("application/json".toMediaType()),
            ),
        )

        assertEquals("{\"code\":200}", bytes.toString(Charsets.UTF_8))
    }

    @Test
    fun emptyBodyReadsAsEmpty() {
        val bytes = NeteaseInterceptor().readPlaybackHistoryResponseBody(
            response(
                code = 200,
                body = ByteArray(0).toResponseBody("application/json".toMediaType()),
            ),
        )

        assertTrue(bytes.isEmpty())
    }

    @Test
    fun responseReadLimitPreservesHttpStatusWhenBodyIsTooLarge() {
        val response = response(
            code = 503,
            body = "x".repeat(MAX_PLAYBACK_HISTORY_RESPONSE_BYTES + 1)
                .toResponseBody("text/plain".toMediaType()),
        )

        try {
            NeteaseInterceptor().readPlaybackHistoryResponseBody(response)
        } catch (error: PlaybackResponseBodyException) {
            assertEquals(503, error.httpStatus)
            assertEquals("ResponseBodyTooLarge", error.failureKind)
            assertTrue(error.failureReason.contains("${MAX_PLAYBACK_HISTORY_RESPONSE_BYTES}"))
            return
        }
        throw AssertionError("Expected the oversized playback body to fail")
    }

    @Test
    fun unknownLengthBodyIsBoundedBeforeItCanBeAccepted() {
        val error = expectFailure(
            response(
                code = 429,
                body = UnknownLengthBody(
                    ByteArray(MAX_PLAYBACK_HISTORY_RESPONSE_BYTES + 1) { 'x'.code.toByte() },
                ),
            ),
        )

        assertEquals(429, error.httpStatus)
        assertEquals("ResponseBodyTooLarge", error.failureKind)
    }

    @Test
    fun bodyReadFailurePreservesHttpStatusAndClosesBody() {
        val closed = AtomicBoolean(false)
        val error = expectFailure(
            response(
                code = 403,
                body = FailingBody(closed),
            ),
        )

        assertEquals(403, error.httpStatus)
        assertEquals("ResponseBodyReadFailure", error.failureKind)
        assertTrue(closed.get())
    }

    private fun expectFailure(response: Response): PlaybackResponseBodyException {
        try {
            NeteaseInterceptor().readPlaybackHistoryResponseBody(response)
        } catch (error: PlaybackResponseBodyException) {
            return error
        }
        throw AssertionError("Expected the playback body read to fail")
    }

    private fun response(code: Int, body: okhttp3.ResponseBody): Response =
        Response.Builder()
            .request(Request.Builder().url("https://interface.music.163.com/eapi/feedback/weblog").build())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("synthetic")
            .body(body)
            .build()

    private class UnknownLengthBody(
        private val bytes: ByteArray,
    ) : ResponseBody() {
        override fun contentType() = "application/json".toMediaType()

        override fun contentLength() = -1L

        override fun source(): BufferedSource = Buffer().write(bytes)
    }

    private class FailingBody(
        private val closed: AtomicBoolean,
    ) : ResponseBody() {
        override fun contentType() = "application/json".toMediaType()

        override fun contentLength() = -1L

        override fun source(): BufferedSource = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long {
                throw IOException("synthetic body read failure")
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
