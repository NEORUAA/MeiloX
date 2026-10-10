package com.ljyh.mei.data.repository

import com.google.gson.JsonParser
import com.ljyh.mei.data.model.toMediaMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PodcastProgramDetailTest {
    @Test
    fun dailyRecommendationUsesAudioSongIdInsteadOfProgramOrRadioId() {
        val program = parsePodcastProgramDetail(response(), requestedId = 123)
        val metadata = program.toMediaMetadata()

        assertEquals(123L, program.id)
        assertEquals(456L, metadata.id)
        assertEquals(789L, metadata.album.id)
        assertEquals("Daily episode", metadata.title)
        assertEquals("Daily podcast", metadata.album.title)
        assertEquals("Host", metadata.artists.single().name)
        assertEquals(180_000L, metadata.duration)
        assertTrue(metadata.isPodcast)
    }

    @Test(expected = IllegalStateException::class)
    fun missingEpisodeFailsBeforeReplacingThePlaybackQueue() {
        parsePodcastProgramDetail(JsonParser.parseString("""{"code":200}""").asJsonObject, 123)
    }

    @Test(expected = IllegalStateException::class)
    fun mismatchedEpisodeFailsBeforeReplacingThePlaybackQueue() {
        parsePodcastProgramDetail(response(), requestedId = 999)
    }

    @Test(expected = IllegalStateException::class)
    fun missingAudioSongFailsInsteadOfUsingEpisodeIdAsSongId() {
        val response = response()
        response.getAsJsonObject("program").remove("mainSong")
        parsePodcastProgramDetail(response, requestedId = 123)
    }

    @Test(expected = IllegalStateException::class)
    fun invalidAudioSongFailsBeforeReplacingThePlaybackQueue() {
        val response = response()
        response.getAsJsonObject("program").getAsJsonObject("mainSong").addProperty("id", 0)
        parsePodcastProgramDetail(response, requestedId = 123)
    }

    private fun response() = JsonParser.parseString(
        """
        {
          "code": 200,
          "program": {
            "id": 123,
            "name": "Daily episode",
            "duration": 180000,
            "coverUrl": "https://example.com/episode.jpg",
            "mainSong": {"id": 456},
            "radio": {"id": 789, "name": "Daily podcast"},
            "dj": {"userId": 321, "nickname": "Host"}
          }
        }
        """.trimIndent(),
    ).asJsonObject
}
