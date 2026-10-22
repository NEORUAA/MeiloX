package com.ljyh.mei.data.network

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.ljyh.mei.data.model.AlbumDetail
import com.ljyh.mei.data.model.requireValidAlbumDetail
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ResponseHandlerTest {
    @Test
    fun gsonConversionFailureBecomesError() = runBlocking {
        val result = safeApiCall {
            Gson().fromJson("null", JsonObject::class.java)
        }

        assertTrue(result is Resource.Error)
        assertTrue((result as Resource.Error).message.contains("JsonObject"))
    }

    @Test
    fun cacheWriteFailureBecomesError() = runBlocking {
        val result = safeApiCall<List<String>> {
            throw IOException("Cache write failed")
        }

        assertEquals(Resource.Error("Cache write failed"), result)
    }

    @Test
    fun successfulRequestRetainsItsValue() = runBlocking {
        val blocks = listOf("daily", "rank")

        val result = safeApiCall { blocks }

        assertTrue(result is Resource.Success)
        assertSame(blocks, (result as Resource.Success).data)
    }

    @Test
    fun malformedOrIncompleteAlbumDetailsBecomeErrors() = runBlocking {
        for (json in listOf(
            "{",
            "null",
            """{"code":404}""",
            """{"code":201,"album":{},"songs":[]}""",
            """{"album":{},"songs":[]}""",
            """{"code":200,"album":null,"songs":[]}""",
            """{"code":200,"songs":[]}""",
            """{"code":200,"album":{},"songs":null}""",
            """{"code":200,"album":{}}""",
        )) {
            val result = safeApiCall {
                Gson().fromJson(json, AlbumDetail::class.java).requireValidAlbumDetail()
            }

            assertTrue("Expected album response error for $json", result is Resource.Error)
        }
    }

    @Test
    fun validAlbumDetailAcceptsAnEmptySongList() = runBlocking {
        val detail = Gson().fromJson(
            """{"code":200,"album":{"id":3000000001},"songs":[]}""",
            AlbumDetail::class.java,
        )

        val result = safeApiCall { detail.requireValidAlbumDetail() }

        assertTrue(result is Resource.Success)
        assertSame(detail, (result as Resource.Success).data)
        assertTrue(result.data.songs.isEmpty())
    }

    @Test
    fun coroutineCancellationEscapesErrorBoundary() = runBlocking {
        val cancellation = CancellationException("Home refresh cancelled")

        try {
            safeApiCall<List<String>> { throw cancellation }
            fail("Cancellation must propagate to the calling coroutine")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
    }
}
