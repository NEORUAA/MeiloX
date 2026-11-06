package com.ljyh.mei.data.repository

import com.ljyh.mei.source.netease.repository.*

import com.google.gson.JsonParser
import com.ljyh.mei.data.model.toMediaMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
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

    @Test
    fun programPagesCountFilteredRowsAndUseRawCountForContinuation() {
        val page = parsePodcastProgramPage(
            JsonParser.parseString(
                """{"count":14,"programs":[{"id":10},null,{}, {"id":11}]}""",
            ).asJsonObject,
            offset = 10,
        )

        assertEquals(listOf(10L, 11L), page.programs.map { it.id })
        assertEquals(4, page.fetchedCount)
        assertEquals(14, page.totalCount)
        assertFalse(page.hasMore)
    }

    @Test
    fun subscriptionPagesCountFilteredRowsWithoutLosingContinuation() {
        val page = parseSubscribedPodcastPage(
            JsonParser.parseString(
                """{"total":10,"djRadios":[{"id":1},null,{}, {"id":0}]}""",
            ).asJsonObject,
            offset = 0,
        )

        assertEquals(listOf(1L), page.podcasts.map { it.id })
        assertEquals(4, page.fetchedCount)
        assertTrue(page.hasMore)
    }

    @Test
    fun fullyFilteredPagesAdvanceWhileEmptyContinuationPagesFail() {
        val programs = parsePodcastProgramPage(
            JsonParser.parseString("""{"more":true,"programs":[null,{}]}""").asJsonObject,
            offset = 0,
        )
        val subscriptions = parseSubscribedPodcastPage(
            JsonParser.parseString("""{"hasMore":true,"djRadios":[null,{}]}""").asJsonObject,
            offset = 0,
        )
        assertTrue(programs.programs.isEmpty())
        assertTrue(subscriptions.podcasts.isEmpty())
        assertEquals(2, programs.fetchedCount)
        assertEquals(2, subscriptions.fetchedCount)
        assertTrue(programs.hasMore)
        assertTrue(subscriptions.hasMore)

        assertThrows(IllegalStateException::class.java) {
            parsePodcastProgramPage(
                JsonParser.parseString("""{"more":true,"programs":[]}""").asJsonObject, 2,
            )
        }
        assertThrows(IllegalStateException::class.java) {
            parseSubscribedPodcastPage(
                JsonParser.parseString("""{"hasMore":true,"djRadios":[]}""").asJsonObject, 2,
            )
        }
        assertFalse(parsePodcastProgramPage(
            JsonParser.parseString("""{"more":false,"programs":[]}""").asJsonObject, 2,
        ).hasMore)
        assertFalse(parseSubscribedPodcastPage(
            JsonParser.parseString("""{"hasMore":false,"djRadios":[]}""").asJsonObject, 2,
        ).hasMore)
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
