package com.ljyh.mei.playback

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.ljyh.mei.data.model.api.RandomPlaylistData
import com.ljyh.mei.data.model.api.RandomPlaylistResponse
import com.ljyh.mei.data.model.api.RandomPlaylistSong
import com.ljyh.mei.source.api.SourceErrorCode
import com.ljyh.mei.source.api.SourceException
import com.ljyh.mei.source.api.Track
import com.ljyh.mei.source.api.TrackRef
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class CloudShuffleTest {
    @Test
    fun sourceShuffleKeepsCompleteOrderAndMetadataBeyondTheFirstPage() = runBlocking {
        val ids = (1..2501).map { "song-$it" }
        val result = JsonObject().apply {
            add("order", JsonArray().apply { ids.forEach { add(it) }; add(ids.first()) })
            add("tracks", JsonArray().apply {
                ids.take(1001).forEach { id -> add(JsonObject().apply { addProperty("trackId", id) }) }
            })
            add("unavailable", JsonArray().apply { add(ids.last()) })
        }
        val page = parseSourceShufflePage(result, "fixture") { value ->
            Track(TrackRef("fixture", value.get("trackId").asString), "Fixture", emptyList())
        }
        assertEquals(ids.map { TrackRef("fixture", it) }, page.order)
        assertEquals(ids.take(1001), page.tracks.map { it.ref.trackId })
        assertEquals(setOf(TrackRef("fixture", ids.last())), page.unavailable)
    }

    @Test
    fun sourceShuffleRejectsMalformedIdentityBeforeQueueMutation() = runBlocking {
        listOf("null", "42", "\"\"", "\"   \"", "{}").forEach { id ->
            val result = JsonParser.parseString("""{"order":[$id],"tracks":[],"unavailable":[]}""").asJsonObject
            try {
                parseSourceShufflePage(result, "fixture") { error("Unexpected metadata") }
                fail("Malformed shuffle identity must fail")
            } catch (error: SourceException) {
                assertEquals(SourceErrorCode.INVALID_RESPONSE, error.code)
            }
        }
    }

    @Test
    fun validCloudResultKeepsExactOrder() = runBlocking {
        val data = RandomPlaylistData(songIds = listOf(3L, 1L, 2L).map { RandomPlaylistSong(it) })
        val result = fetchCloudShuffleOrNull { RandomPlaylistResponse(200, data) }
        assertSame(data, result)
        assertEquals(listOf(3L, 1L, 2L), result!!.songIds!!.map { it.id })
    }

    @Test
    fun networkFailureFallsBackWithoutRetrying() = runBlocking {
        var calls = 0
        val result = fetchCloudShuffleOrNull {
            calls++
            throw IOException("offline")
        }
        assertNull(result)
        assertEquals(1, calls)
    }

    @Test
    fun businessFailureFallsBackEvenWithSongIds() = runBlocking {
        assertNull(fetchCloudShuffleOrNull {
            RandomPlaylistResponse(403, RandomPlaylistData(songIds = listOf(RandomPlaylistSong(1))))
        })
    }

    @Test
    fun missingAndEmptyOrdersFallBack() = runBlocking {
        listOf(null, RandomPlaylistData(), RandomPlaylistData(songIds = emptyList())).forEach { data ->
            assertNull(fetchCloudShuffleOrNull { RandomPlaylistResponse(200, data) })
        }
    }

    @Test
    fun invalidIdsRejectTheWholeResultBeforeQueueMutation() = runBlocking {
        listOf(0L, -1L).forEach { id ->
            assertNull(fetchCloudShuffleOrNull {
                RandomPlaylistResponse(200, RandomPlaylistData(songIds = listOf(RandomPlaylistSong(1), RandomPlaylistSong(id))))
            })
        }
    }

    @Test
    fun malformedNullableJsonElementsFallBack() = runBlocking {
        listOf(
            """{"code":200,"data":{"songIds":[null]}}""",
            """{"code":200,"data":{"songIds":[{"id":1}],"songData":[null]}}""",
            """{"code":200,"data":{"songIds":[{"id":1}],"privileges":[null]}}""",
        ).forEach { json ->
            assertNull(fetchCloudShuffleOrNull { Gson().fromJson(json, RandomPlaylistResponse::class.java) })
        }
    }

    @Test
    fun requestTimeoutFallsBack() = runBlocking {
        assertNull(fetchCloudShuffleOrNull(timeoutMs = 20L) {
            CompletableDeferred<RandomPlaylistResponse>().await()
        })
    }

    @Test(expected = CancellationException::class)
    fun cancellationIsNotConvertedIntoFallback() = runBlocking {
        fetchCloudShuffleOrNull { throw CancellationException("cloud shuffle disabled") }
        fail("Cancellation must propagate")
    }
}
