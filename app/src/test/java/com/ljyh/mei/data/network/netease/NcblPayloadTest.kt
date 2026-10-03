package com.ljyh.mei.data.network.netease

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class NcblPayloadTest {
    @Test
    fun startUsesMobilePlvFieldsAndKeepsFullTrackLengthSeparateFromPlayedTime() {
        val startedAtMs = 1_790_006_390_987L
        val session = session(startedAtMs = startedAtMs, durationMs = 241_999L)

        val event = NcblPayload.start(session, eventTimeMs = startedAtMs)
        val (recordTime, action, fields) = decodeRecord(event.record)

        assertEquals(startedAtMs / 1_000L, recordTime)
        assertEquals("_plv", action)
        assertEquals(123456L, fields.get("id").asLong)
        assertEquals("song", fields.get("type").asString)
        assertEquals("track", fields.get("source").asString)
        assertEquals("456", fields.get("sourceId").asString)
        assertEquals(startedAtMs / 1_000L, fields.get("startlogtime").asLong)
        assertEquals(startedAtMs, fields.get("logtime").asLong)
        assertEquals(0L, fields.get("time").asLong)
        assertEquals(0L, fields.get("realtime").asLong)
        assertEquals(241L, fields.get("resource_time").asLong)
        assertEquals("Track title", fields.get("songName").asString)
        assertEquals("Artist One, Artist Two", fields.get("artistName").asString)
        assertFalse(fields.has("end"))
        assertFalse(fields.has("progressTime"))
        assertFalse(fields.has("bitrate"))
        assertFalse(fields.has("fee"))
        assertFalse(fields.has("wifi"))
        assertFalse(event.record.last().toInt().toChar().isWhitespace())
    }

    @Test
    fun completionUsesActualElapsedSecondsAndTheCapturedEndEventTimeAcrossMidnight() {
        val start = Instant.parse("2026-09-24T23:59:59.987+08:00").toEpochMilli()
        val end = Instant.parse("2026-09-25T00:00:10.321+08:00").toEpochMilli()
        val session = session(startedAtMs = start, durationMs = 241_999L)

        val startEvent = NcblPayload.start(session, eventTimeMs = start)
        val completion = NcblPayload.end(
            session = session,
            playedDurationMs = 8_500L,
            eventTimeMs = end,
            endReason = "ui",
        )
        val (recordTime, action, fields) = decodeRecord(completion.record)
        val startFields = decodeRecord(startEvent.record).third

        assertEquals("_pld", action)
        assertEquals(end / 1_000L, recordTime)
        assertEquals(start / 1_000L, fields.get("startlogtime").asLong)
        assertEquals(end, fields.get("logtime").asLong)
        assertEquals(8L, fields.get("time").asLong)
        assertEquals(8L, fields.get("realtime").asLong)
        assertEquals(241L, fields.get("resource_time").asLong)
        assertEquals("ui", fields.get("end").asString)
        assertEquals(startFields.get("_sessid").asString, fields.get("_sessid").asString)
        assertTrue(start / 1_000L < end / 1_000L)
    }

    private fun session(startedAtMs: Long, durationMs: Long?) = NcblSessionContext(
        credentials = NcblCredentials("token-not-for-logs", "DEVICE123"),
        device = NcblDeviceInfo(
            deviceId = "DEVICE123",
            osVersion = "16",
            model = "Pixel 10 Pro",
            brand = "Google",
            processName = "com.neoruaa.meilox",
            buildType = "debug",
            pid = 42,
            buildId = "AP4A",
        ),
        profile = NcblClientProfile.Android,
        song = NcblSongInfo(123456L, "Track title", "Artist One, Artist Two", durationMs),
        source = "track",
        sourceId = "456",
        startedAtMs = startedAtMs,
        buildVersion = (startedAtMs / 1_000L).toString(),
        sessionId = "captured-session-id",
    )

    private fun decodeRecord(record: ByteArray): Triple<Long, String, com.google.gson.JsonObject> {
        val text = record.toString(Charsets.UTF_8)
        val first = text.indexOf('\u0001')
        val second = text.indexOf('\u0001', first + 1)
        return Triple(
            text.substring(0, first).toLong(),
            text.substring(first + 1, second),
            JsonParser.parseString(text.substring(second + 1)).asJsonObject,
        )
    }
}
