package com.ljyh.mei.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.JsonSyntaxException
import com.ljyh.mei.AppContext
import com.ljyh.mei.constants.LastHomePageTime
import com.ljyh.mei.data.model.eapi.HomePageResourceShow
import com.ljyh.mei.data.model.weapi.GetHomePageResourceShow
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.data.network.api.ApiService
import com.ljyh.mei.data.network.api.EApiService
import com.ljyh.mei.utils.dataStore
import java.io.File
import java.io.IOException
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.startCoroutine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeRepositoryInstrumentedTest {
    @Test
    fun reportedConversionFailureReturnsErrorWithoutReplacingSavedHomePage() = runBlocking {
        assertRefreshFailurePreservesSavedHomePage(
            JsonSyntaxException(
                "Expected a com.google.gson.JsonObject but was com.google.gson.JsonNull; " +
                    "at path $.data.blocks[2].dslData",
            ),
        )
    }

    @Test
    fun networkIoFailureReturnsErrorWithoutReplacingSavedHomePage() = runBlocking {
        assertRefreshFailurePreservesSavedHomePage(IOException("Home refresh connection failed"))
    }

    @Test
    fun cancellationCancelsRefreshWithoutReplacingSavedHomePage() = runBlocking {
        val before = readSavedHomePage()
        val calls = AtomicInteger()
        val cancellation = CancellationException("Home refresh cancelled")
        val repository = repositoryThrowing(cancellation, calls)

        supervisorScope {
            val refresh = async { repository.getHomePageResourceShow(refresh = true) }

            try {
                refresh.await()
                fail("Cancellation must propagate from the repository")
            } catch (actual: CancellationException) {
                assertEquals(cancellation.message, actual.message)
            }
            assertTrue("The refresh job must remain cancelled", refresh.isCancelled)
        }

        assertEquals("The refresh must call EApiService", 1, calls.get())
        assertSavedHomePageUnchanged(before)
    }

    private suspend fun assertRefreshFailurePreservesSavedHomePage(failure: Exception) {
        val before = readSavedHomePage()
        val calls = AtomicInteger()
        val repository = repositoryThrowing(failure, calls)

        val result = repository.getHomePageResourceShow(refresh = true)

        assertEquals(Resource.Error(failure.message!!), result)
        assertEquals("The refresh must call EApiService", 1, calls.get())
        assertSavedHomePageUnchanged(before)
    }

    private fun repositoryThrowing(failure: Exception, calls: AtomicInteger): HomeRepository {
        val eApiService = Proxy.newProxyInstance(
            EApiService::class.java.classLoader,
            arrayOf(EApiService::class.java),
        ) { _, method, arguments ->
            check(method.name == "getHomePageResourceShow") {
                "Unexpected EApiService call: ${method.name}"
            }
            val request = arguments!![0] as GetHomePageResourceShow
            assertEquals("true", request.refresh)
            calls.incrementAndGet()

            // Resume a suspend failure so checked IOExceptions are not wrapped by Java Proxy.
            @Suppress("UNCHECKED_CAST")
            val continuation = arguments.last() as Continuation<HomePageResourceShow>
            val failedRequest: suspend () -> HomePageResourceShow = { throw failure }
            failedRequest.startCoroutine(continuation)
            COROUTINE_SUSPENDED
        } as EApiService
        val apiService = Proxy.newProxyInstance(
            ApiService::class.java.classLoader,
            arrayOf(ApiService::class.java),
        ) { _, method, _ ->
            error("Home refresh must not call ApiService: ${method.name}")
        } as ApiService

        return HomeRepository(eApiService, apiService)
    }

    private suspend fun readSavedHomePage(): SavedHomePage {
        val context = AppContext.instance
        val cacheFile = File(context.filesDir, "home_page_data_1.json")
        return SavedHomePage(
            cacheBytes = cacheFile.takeIf { it.exists() }?.readBytes(),
            lastFetchTime = context.dataStore.data.first()[LastHomePageTime],
        )
    }

    private suspend fun assertSavedHomePageUnchanged(before: SavedHomePage) {
        val after = readSavedHomePage()
        assertArrayEquals("Existing home cache must remain unchanged", before.cacheBytes, after.cacheBytes)
        assertEquals("LastHomePageTime must remain unchanged", before.lastFetchTime, after.lastFetchTime)
    }

    private data class SavedHomePage(val cacheBytes: ByteArray?, val lastFetchTime: Long?)
}
