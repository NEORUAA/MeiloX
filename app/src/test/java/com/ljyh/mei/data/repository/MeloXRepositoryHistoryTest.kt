package com.ljyh.mei.data.repository

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MeloXRepositoryHistoryTest {
    @Test
    fun recentHistoryReaderNormalizesSecondsToMilliseconds() {
        val song = parseRecentHistorySong(
            JsonParser.parseString(
                """
                {
                  "playTime": "1712345678",
                  "data": {"id": "1", "name": "Song", "ar": [], "dt": "180000"}
                }
                """.trimIndent(),
            ),
        )

        assertEquals(1_712_345_678_000L, song?.playedAt)
    }

    @Test
    fun recentHistoryReaderKeepsMillisecondTimestamp() {
        val song = parseRecentHistorySong(
            JsonParser.parseString(
                """
                {
                  "playTime": 1712345678000,
                  "data": {"id": 2, "name": "Song", "ar": [], "dt": 180000}
                }
                """.trimIndent(),
            ),
        )

        assertEquals(1_712_345_678_000L, song?.playedAt)
    }

    @Test
    fun missingOrZeroCloudTimestampRemainsUnknown() {
        val missing = parseRecentHistorySong(
            JsonParser.parseString(
                """
                {"data": {"id": 3, "name": "Song", "ar": [], "dt": 180000}}
                """.trimIndent(),
            ),
        )
        val zero = parseRecentHistorySong(
            JsonParser.parseString(
                """
                {"playTime": 0, "data": {"id": 4, "name": "Song", "ar": [], "dt": 180000}}
                """.trimIndent(),
            ),
        )

        assertNull(missing?.playedAt)
        assertNull(zero?.playedAt)
    }
}
