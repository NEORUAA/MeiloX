package com.ljyh.mei.di

import java.io.IOException
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class PlaybackResponseBodyTest {
    @Test
    fun readsShortAndEmptyBodiesToEof() {
        for (value in listOf("", "{\"code\":200}")) {
            assertEquals(value, readBoundedPlaybackResponseBody(value.toResponseBody(), 200).decodeToString())
        }
    }

    @Test
    fun acceptsExactlyTheLimit() {
        val bytes = ByteArray(MAX_PLAYBACK_HISTORY_RESPONSE_BYTES) { 42 }
        assertArrayEquals(bytes, readBoundedPlaybackResponseBody(bytes.toResponseBody(), 200))
    }

    @Test
    fun rejectsKnownOversizedBodiesWithOriginalStatus() {
        val error = assertThrows(PlaybackResponseBodyException::class.java) {
            readBoundedPlaybackResponseBody(ByteArray(MAX_PLAYBACK_HISTORY_RESPONSE_BYTES + 1).toResponseBody(), 503)
        }
        assertEquals(503, error.httpStatus)
        assertEquals("ResponseBodyTooLarge", error.failureKind)
    }

    @Test
    fun boundsUnknownLengthAndClosesBody() {
        val body = TestBody(Buffer().write(ByteArray(MAX_PLAYBACK_HISTORY_RESPONSE_BYTES + 1)))
        val error = assertThrows(PlaybackResponseBodyException::class.java) {
            readBoundedPlaybackResponseBody(body, 429)
        }
        assertEquals(429, error.httpStatus)
        assertEquals("ResponseBodyTooLarge", error.failureKind)
        assertTrue(body.closed)
    }

    @Test
    fun preservesReadFailureAndClosesBody() {
        val cause = IOException("synthetic read failure")
        val body = TestBody(object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long = throw cause
            override fun timeout() = Timeout.NONE
            override fun close() = Unit
        }.buffer())
        val error = assertThrows(PlaybackResponseBodyException::class.java) {
            readBoundedPlaybackResponseBody(body, 403)
        }
        assertEquals(403, error.httpStatus)
        assertEquals("ResponseBodyReadFailure", error.failureKind)
        assertEquals(cause, error.cause)
        assertTrue(body.closed)
    }

    private class TestBody(private val source: BufferedSource) : ResponseBody() {
        var closed = false
        override fun contentType() = null
        override fun contentLength() = -1L
        override fun source() = source
        override fun close() {
            closed = true
            super.close()
        }
    }
}
