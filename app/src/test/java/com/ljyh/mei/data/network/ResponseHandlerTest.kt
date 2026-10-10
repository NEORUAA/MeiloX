package com.ljyh.mei.data.network

import com.google.gson.Gson
import com.google.gson.JsonObject
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
