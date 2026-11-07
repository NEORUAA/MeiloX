package com.ljyh.mei.ui.screen.account

import com.google.gson.JsonParser
import com.ljyh.mei.source.api.SourceErrorCode
import com.ljyh.mei.source.api.SourceException
import org.junit.Assert.*
import org.junit.Test

class PcQrLoginTest {
    @Test fun securityPreparationAndConfirmationGateCameraAnalysis() {
        assertFalse(PcQrLoginUiState(PcQrLoginPhase.Preparing).isAnalyzing)
        assertTrue(PcQrLoginUiState().isAnalyzing)
        assertFalse(PcQrLoginUiState(PcQrLoginPhase.MarkingScanned).isAnalyzing)
        assertTrue(PcQrLoginUiState(PcQrLoginPhase.AwaitingConfirmation).isDialogVisible)
        assertTrue(PcQrLoginUiState(PcQrLoginPhase.Authorizing).isDialogVisible)
    }

    @Test fun decoderPreservesAllBoundedCandidatesWithoutAssumingASourceUrlFormat() {
        val custom = "other-source:login?key=opaque"
        val web = "https://example.com/login?key=value"
        assertEquals(listOf(custom, web), decodedQrCandidates(listOf("", "  ", " $custom ", custom, web)))
        assertTrue(decodedQrCandidates(emptyList()).isEmpty())
        assertTrue(decodedQrCandidates(listOf("x".repeat(8193))).isEmpty())
        assertEquals(8, decodedQrCandidates((1..20).map { "opaque:$it" }).size)
    }

    @Test fun sourceOwnsQrRecognitionAndTheHostValidatesOnlyTheNormalizedReceipt() {
        val raw = "other-source:login?key=opaque"
        val parsed = sourcePcQrPayload(JsonParser.parseString("""{"key":"opaque-key","clientTraceId":"trace","rawValue":"untrusted replacement"}"""), raw)
        assertEquals("opaque-key", parsed?.key)
        assertEquals("trace", parsed?.clientTraceId)
        assertEquals(raw, parsed?.rawValue)
        assertNull(sourcePcQrPayload(JsonParser.parseString("null"), raw))
        assertNull(sourcePcQrPayload(JsonParser.parseString("""{"key":"key"}"""), raw)?.clientTraceId)
        for (receipt in listOf("false", "[]", "{}", """{"key":1}""", """{"key":" "}""", """{"key":"key","clientTraceId":false}""")) {
            try { sourcePcQrPayload(JsonParser.parseString(receipt), raw); fail("Expected a malformed source receipt") }
            catch (error: SourceException) { assertEquals(SourceErrorCode.INVALID_RESPONSE, error.code) }
        }
        try { sourcePcQrPayload(JsonParser.parseString("""{"key":"${"x".repeat(2049)}"}"""), raw); fail("Expected bounded opaque identifiers") }
        catch (error: SourceException) { assertEquals(SourceErrorCode.INVALID_RESPONSE, error.code) }
    }

}
