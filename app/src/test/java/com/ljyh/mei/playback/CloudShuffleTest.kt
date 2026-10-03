package com.ljyh.mei.playback

import com.google.gson.Gson
import com.ljyh.mei.data.model.api.RandomPlaylistData
import com.ljyh.mei.data.model.api.RandomPlaylistResponse
import com.ljyh.mei.data.model.api.RandomPlaylistSong
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
