package com.ljyh.mei.data.repository

import com.ljyh.mei.source.netease.repository.*

import com.google.gson.JsonParser
import com.ljyh.mei.data.model.melox.SongWikiMemoryKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class MeloXRepositoryHistoryTest {
    @Test
    fun listenTogetherRejectionPrefersReadableCopywritingAndFallsBackToStatus() {
        fun parse(json: String) = parseListenTogetherRoomCheck(JsonParser.parseString(json).asJsonObject)

        assertEquals(false to "Room is full", parse("""{"joinable":false,"status":"FULL","copywriting":"Room is full"}"""))
        for (copywriting in listOf("null", "\"\"", "\"   \"")) {
            assertEquals(false to "FULL", parse("""{"joinable":false,"status":"FULL","copywriting":$copywriting}"""))
        }
        assertEquals(true to "NORMAL", parse("""{"joinable":true,"status":"NORMAL","copywriting":"Unused message"}"""))
        assertEquals(false to null, parseListenTogetherRoomCheck(null))
    }

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

    @Test
    fun rankingKeepsPersonalPlayCountsSeparateFromScoresAndMissingData() {
        val records = JsonParser.parseString(
            """
            [
              {"song": {"id": 1}, "playCount": "8", "score": 100},
              {"song": {"id": 2}, "score": 75},
              {"song": {"id": 3}, "playCount": 0, "score": 50},
              {"song": {"id": 4}, "playCount": -1, "score": 25}
            ]
            """.trimIndent(),
        ).asJsonArray.mapNotNull(::parseUserPlayRecord)

        assertEquals(listOf(8, null, 0, null), records.map { it.playCount })
        assertEquals(listOf(100, 75, 50, 25), records.map { it.score })
    }

    @Test
    fun rankingUsesTheRequestedPeriodWithoutCombiningCounts() {
        val response = JsonParser.parseString(
            """
            {
              "code": 200,
              "weekData": [{"song": {"id": 1}, "playCount": 2}],
              "allData": [{"song": {"id": 1}, "playCount": 8}]
            }
            """.trimIndent(),
        ).asJsonObject

        assertEquals(2, parseUserPlayRecords(response, allTime = false).single().playCount)
        assertEquals(8, parseUserPlayRecords(response, allTime = true).single().playCount)
    }

    @Test
    fun failedOrMissingRankingDataDoesNotBecomeAnEmptyListeningHistory() {
        listOf(
            """{"code": 403, "allData": []}""",
            """{"code": 200}""",
            """{"allData": []}""",
        ).forEach { json ->
            assertThrows(IllegalStateException::class.java) {
                parseUserPlayRecords(JsonParser.parseString(json).asJsonObject, allTime = true)
            }
        }
        assertEquals(
            emptyList<Any>(),
            parseUserPlayRecords(JsonParser.parseString("""{"code": 200, "allData": []}""").asJsonObject, allTime = true),
        )
    }

    @Test
    fun songWikiDistinguishesValidEmptyDataFromFailedOrMalformedResponses() {
        listOf(
            """{"code": 200, "data": {"blocks": []}}""",
            """{"code": 200, "data": {}}""",
            """{"code": 200, "data": null}""",
        ).forEach { json ->
            assertEquals(emptyList<Any>(), parseSongWikiBlocks(JsonParser.parseString(json).asJsonObject))
        }
        listOf(
            """{"data": {"blocks": []}}""",
            """{"code": 403, "data": null}""",
            """{"code": 200, "data": []}""",
            """{"code": 200, "data": {"blocks": "invalid"}}""",
        ).forEach { json ->
            assertThrows(IllegalStateException::class.java) {
                parseSongWikiBlocks(JsonParser.parseString(json).asJsonObject)
            }
        }
    }

    @Test
    fun personalSongStatisticsUseOnlyTheMemoryBlockAndKeepFirstListenDate() {
        val blocks = JsonParser.parseString(
            """
            [
              {
                "code": "SONG_PLAY_ABOUT_MUSIC_MEMORY",
                "creatives": [{"resources": [
                  {
                    "resourceType": "FIRST_LISTEN",
                    "resourceExt": {"musicFirstListenDto": {"date": "2026.10.01"}}
                  },
                  {
                    "resourceType": "TOTAL_PLAY",
                    "resourceExtInfo": {
                      "playCount": 999999,
                      "musicTotalPlayDto": {"playCount": "8", "duration": "24"}
                    }
                  }
                ]}]
              },
              {
                "code": "SONG_PLAY_ABOUT_RELATED_PLAYLIST",
                "creatives": [{"resources": [{
                  "resourceType": "TOTAL_PLAY",
                  "resourceExt": {"musicTotalPlayDto": {"playCount": 999999}}
                }]}]
              }
            ]
            """.trimIndent(),
        ).asJsonArray.map { it.asJsonObject }

        val memories = parseSongListeningMemories(blocks)

        assertEquals(2, memories.size)
        assertEquals(SongWikiMemoryKind.FirstListen, memories[0].kind)
        assertEquals("2026.10.01", memories[0].date)
        assertEquals(SongWikiMemoryKind.TotalPlay, memories[1].kind)
        assertEquals(8L, memories[1].playCount)
        assertEquals(24L, memories[1].durationMinutes)
    }

    @Test
    fun missingPersonalSongStatisticsStayUnknownInsteadOfBecomingZero() {
        val blocks = JsonParser.parseString(
            """
            [{
              "code": "SONG_PLAY_ABOUT_MUSIC_MEMORY",
              "creatives": [{"resources": [
                {"resourceType": "FIRST_LISTEN", "resourceExt": {"musicFirstListenDto": {"date": ""}}},
                {"resourceType": "TOTAL_PLAY", "resourceExt": {"musicTotalPlayDto": {}}},
                {"resourceType": "TOTAL_PLAY", "resourceExt": {"musicTotalPlayDto": {"playCount": -1, "duration": -1}}},
                {"resourceType": "TOTAL_PLAY", "resourceExt": {"musicTotalPlayDto": {"playCount": 0}}}
              ]}]
            }]
            """.trimIndent(),
        ).asJsonArray.map { it.asJsonObject }

        val memories = parseSongListeningMemories(blocks)

        assertEquals(1, memories.size)
        assertEquals(0L, memories.single().playCount)
        assertNull(memories.single().durationMinutes)
    }
}
