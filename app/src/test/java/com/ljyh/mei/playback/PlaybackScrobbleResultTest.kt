package com.ljyh.mei.playback

import com.ljyh.mei.data.repository.PlaybackLogResponse
import com.ljyh.mei.data.repository.PlaybackScrobbleResult
import com.ljyh.mei.data.repository.PLAYBACK_HISTORY_DIAGNOSTIC_ENDPOINT
import com.ljyh.mei.data.repository.failureSummary
import com.ljyh.mei.data.repository.parsePlaybackHistoryBody
import com.ljyh.mei.data.repository.sanitizePlaybackDiagnosticText
import com.ljyh.mei.di.MAX_PLAYBACK_HISTORY_RESPONSE_BYTES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackScrobbleResultTest {
    @Test
    fun bothBusinessResponsesAreRequiredForAcceptance() {
        assertTrue(PlaybackScrobbleResult(response(200), response(200)).accepted)
        assertFalse(PlaybackScrobbleResult(response(500), response(200)).accepted)
        assertFalse(PlaybackScrobbleResult(response(200), response(500)).accepted)
    }

    @Test
    fun httpStatusDoesNotBecomeBusinessCode() {
        val response = response(
            httpAccepted = false,
            httpStatus = 503,
            code = null,
            exceptionType = "HttpException",
            failureReason = "HTTP status 503",
        )

        assertFalse(response.businessAccepted)
        assertEquals(503, response.httpStatus)
        assertNull(response.code)
    }

    @Test
    fun networkFailureSummaryContainsBothActionsAndRedactsSecrets() {
        val response = response(
            httpAccepted = false,
            exceptionType = "ConnectException",
            failureReason =
                "Failed to connect to clientlog.music.163.com/0.0.0.0:443?MUSIC_U=secret-cookie",
        )

        val summary = PlaybackScrobbleResult(response, response).failureSummary()

        assertTrue(summary.contains("startplay"))
        assertTrue(summary.contains("play"))
        assertTrue(summary.contains("endpoint=$PLAYBACK_HISTORY_DIAGNOSTIC_ENDPOINT"))
        assertTrue(summary.contains("ConnectException"))
        assertTrue(summary.contains("0.0.0.0:443"))
        assertFalse(summary.contains("secret-cookie"))
        assertFalse(summary.contains("MUSIC_U=secret-cookie"))
    }

    @Test
    fun parsedErrorResponseKeepsBusinessCodeAndSafeMessage() {
        val parsed = parsePlaybackHistoryBody(
            "{\"code\":-460,\"msg\":\"MUSIC_U=secret-cookie\"}",
        )

        assertEquals(-460, parsed.code)
        assertEquals("MUSIC_U=<redacted>", parsed.message)
    }

    @Test
    fun diagnosticSanitizerBoundsUntrustedText() {
        val sanitized = sanitizePlaybackDiagnosticText(
            "header=secret body={\"MUSIC_U\":\"cookie\"}" + "x".repeat(500),
        )

        assertTrue(sanitized!!.length <= 240)
        assertFalse(sanitized.contains("secret"))
        assertFalse(sanitized.contains("cookie"))
    }

    @Test
    fun diagnosticSanitizerRedactsBearerAndSecurityFields() {
        val sanitized = sanitizePlaybackDiagnosticText(
            "Authorization: Basic auth-secret\n" +
                "Cookie: MUSIC_U=music-u-secret; MUSIC_A=music-a-secret\n" +
                "Set-Cookie: __csrf=csrf-secret; Path=/\n" +
                "checkToken=check-token-secret",
        )

        assertFalse(sanitized!!.contains("auth-secret"))
        assertFalse(sanitized.contains("music-u-secret"))
        assertFalse(sanitized.contains("csrf-secret"))
        assertFalse(sanitized.contains("music-a-secret"))
        assertFalse(sanitized.contains("check-token-secret"))
        assertTrue(sanitized.contains("Authorization=<redacted>"))
        assertTrue(sanitized.contains("Cookie=<redacted>"))
        assertTrue(sanitized.contains("Set-Cookie=<redacted>"))
    }

    @Test
    fun diagnosticSanitizerRedactsQuotedKeyFragments() {
        val sanitized = sanitizePlaybackDiagnosticText(
            "MUSIC_U=plain-secret " +
                "\"MUSIC_U\":\"quoted-secret\" " +
                "\"Authorization\":\"Bearer auth-secret\" " +
                "\"Set-Cookie\":\"cookie-secret; Path=/\"",
        )

        assertFalse(sanitized!!.contains("plain-secret"))
        assertFalse(sanitized.contains("quoted-secret"))
        assertFalse(sanitized.contains("auth-secret"))
        assertFalse(sanitized.contains("cookie-secret"))
    }

    @Test
    fun oversizedBodyDoesNotAcceptAValidPrefix() {
        val parsed = parsePlaybackHistoryBody(
            "{\"code\":200}" + "x".repeat(MAX_PLAYBACK_HISTORY_RESPONSE_BYTES),
        )

        assertNull(parsed.code)
        assertEquals("ResponseBodyTooLarge", parsed.exceptionType)
    }

    @Test
    fun diagnosticSanitizerKeepsSafeNetworkContextWithoutRawPayloadOrStack() {
        val sanitized = sanitizePlaybackDiagnosticText(
            "connectreason=timeout ip=203.0.113.7 code=-460 " +
                "body={\"secret\":\"raw-body\"} stacktrace=raw-stack",
        )

        assertTrue(sanitized!!.contains("connectreason=timeout"))
        assertTrue(sanitized.contains("ip=203.0.113.7"))
        assertTrue(sanitized.contains("code=-460"))
        assertFalse(sanitized.contains("raw-body"))
        assertFalse(sanitized.contains("raw-stack"))
        assertFalse(sanitized.contains("{\"secret\""))
    }

    private fun response(
        code: Int? = 200,
        httpAccepted: Boolean = true,
        httpStatus: Int? = null,
        exceptionType: String? = null,
        failureReason: String? = null,
    ) = PlaybackLogResponse(
        httpAccepted = httpAccepted,
        code = code,
        message = null,
        httpStatus = httpStatus,
        exceptionType = exceptionType,
        failureReason = failureReason,
        endpoint = PLAYBACK_HISTORY_DIAGNOSTIC_ENDPOINT,
    )
}
