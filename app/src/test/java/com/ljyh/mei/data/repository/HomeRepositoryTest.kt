package com.ljyh.mei.data.repository

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import com.ljyh.mei.constants.CookieKey
import com.ljyh.mei.constants.NeteaseSessionTypeKey
import com.ljyh.mei.constants.NeteaseUrsAppIdKey
import com.ljyh.mei.constants.UserIdKey
import com.ljyh.mei.data.model.eapi.HomePageResourceShow
import com.ljyh.mei.data.model.eapi.HomePageResourceShow.Data.Block
import com.ljyh.mei.data.network.NeteaseSessionType
import com.ljyh.mei.data.network.Resource
import java.io.File
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HomeRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val gson = Gson()
    private val now = Instant.parse("2026-10-15T04:00:00Z").toEpochMilli()

    @Test
    fun radarArtworkUsesOnlyTheCurrentOwnersDailyCacheWithoutFetching() = runBlocking {
        val blocks = listOf(gson.fromJson(
            """{"positionCode":"PAGE_RECOMMEND_RADAR","dslData":{"template":{"blockResource":{"resources":[
                {"resourceId":"5300458264","coverImg":"https://p1.music.126.net/uUUN57qTiveMY442WVWK2g==/109951168539760536.jpg?imageView=1%7Cwatermark&type=1&image=caption%7Cwatermark&type=1&image=dim"},
                {"resourceId":"5320167908","coverImg":"https://p1.music.126.net/2XJqd79V8LfCdW0TAGXhIQ==/109951164787608780.jpg"}
            ]}}}}""", Block::class.java,
        ))
        var requests = 0
        val fixture = Fixture { requests++; blocks }
        assertEquals(null, fixture.repository.cachedRadarCover("5300458264"))
        assertEquals(0, requests)
        fixture.repository.getHomePageResourceShow()
        assertEquals("https://p1.music.126.net/uUUN57qTiveMY442WVWK2g==/109951168539760536.jpg?imageView=1",
            fixture.repository.cachedRadarCover("5300458264"))
        assertEquals("https://p1.music.126.net/2XJqd79V8LfCdW0TAGXhIQ==/109951164787608780.jpg",
            fixture.repository.cachedRadarCover("5320167908"))
        assertEquals(null, fixture.repository.cachedRadarCover("8402996200"))
        assertEquals(1, requests)
        fixture.preferences.value = account("42")
        assertEquals(null, fixture.repository.cachedRadarCover("5300458264"))
        fixture.preferences.value = account()
        val expired = HomeRepository(fixture.file, fixture.preferences, { error("Unexpected fetch") }, { now + 24 * 60 * 60 * 1000 })
        assertEquals(null, expired.cachedRadarCover("5300458264"))
    }

    @Test
    fun completedFeedIsCachedForTheOwnerAndExplicitRefreshReplacesIt() = runBlocking {
        val refreshes = mutableListOf<Boolean>()
        val fixture = Fixture { refresh ->
            refreshes += refresh
            blocks("PAGE_${refreshes.size}")
        }

        assertEquals(listOf("PAGE_1"), fixture.repository.getHomePageResourceShow().positions())
        assertEquals(listOf("PAGE_1"), fixture.repository.getHomePageResourceShow().positions())
        assertEquals(listOf("PAGE_2"), fixture.repository.getHomePageResourceShow(refresh = true).positions())
        assertEquals(listOf(false, true), refreshes)
        val cached = JsonParser.parseString(fixture.file.readText()).asJsonObject
        assertEquals(2, cached["version"].asInt)
        assertEquals("Mobile", cached.getAsJsonObject("owner")["sessionType"].asString)
        assertEquals(now, cached["fetchedAt"].asLong)
        assertFalse(fixture.file.readText().contains("private-session-cookie"))
        assertEquals(listOf(fixture.file.name), fixture.file.parentFile.list()!!.toList())
    }

    @Test
    fun legacyMalformedAndIncompatibleCachesRefetchWithoutChangingBytesOnFailure() = runBlocking {
        val invalidCaches = listOf(
            gson.toJson(blocks("LEGACY")),
            "{broken",
            "null",
            "{}",
            cacheJson(version = 1),
            cacheJson(owner = HomePageOwner("17", NeteaseSessionType.Web)),
            cacheJson(owner = HomePageOwner("42", NeteaseSessionType.Mobile)),
            cacheJson(fetchedAt = now - 24 * 60 * 60 * 1000),
            cacheJson(fetchedAt = now + 1),
        )
        for (cached in invalidCaches) {
            var calls = 0
            val fixture = Fixture { calls++; throw IOException("offline") }
            fixture.file.writeText(cached)
            val before = fixture.file.readBytes()

            assertEquals(Resource.Error("offline"), fixture.repository.getHomePageResourceShow())
            assertEquals(1, calls)
            assertArrayEquals(before, fixture.file.readBytes())
        }
    }

    @Test
    fun sameAccountWebToMobileAndAccountChangesLoadTheirOwnFeed() = runBlocking {
        var calls = 0
        val fixture = Fixture { blocks("PAGE_${++calls}") }
        fixture.preferences.value = account(type = NeteaseSessionType.Web)
        assertEquals(listOf("PAGE_1"), fixture.repository.getHomePageResourceShow().positions())

        // Older mobile sessions identify their origin through URS_APPID.
        fixture.preferences.value = mutablePreferencesOf(
            CookieKey to "private-session-cookie", UserIdKey to "17", NeteaseUrsAppIdKey to "mobile-app",
        )
        assertEquals(listOf("PAGE_2"), fixture.repository.getHomePageResourceShow().positions())
        assertEquals(listOf("PAGE_2"), fixture.repository.getHomePageResourceShow().positions())
        fixture.preferences.value = account(userId = "42")
        assertEquals(listOf("PAGE_3"), fixture.repository.getHomePageResourceShow().positions())
        assertEquals(3, calls)
    }

    @Test
    fun failedAndCancelledRefreshesPreserveTheCompletedCache() = runBlocking {
        var failure: Exception? = null
        val fixture = Fixture { failure?.let { throw it }; blocks("COMPLETE") }
        fixture.repository.getHomePageResourceShow()
        val before = fixture.file.readBytes()
        for (error in listOf(IOException("offline"), JsonSyntaxException("bad page"), CancellationException("cancelled"))) {
            failure = error
            try {
                val result = fixture.repository.getHomePageResourceShow(refresh = true)
                if (error is CancellationException) fail("Cancellation must propagate")
                assertEquals(Resource.Error(error.message!!), result)
            } catch (actual: CancellationException) {
                assertTrue(error is CancellationException)
                assertEquals(error.message, actual.message)
            }
            assertArrayEquals(before, fixture.file.readBytes())
        }
    }

    @Test
    fun lateResponseForThePreviousOwnerCannotReplaceTheCache() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val fixture = Fixture {
            started.complete(Unit)
            withContext(NonCancellable) { release.await() }
            blocks("OLD_RESPONSE")
        }
        fixture.file.writeText(cacheJson())
        val before = fixture.file.readBytes()

        supervisorScope {
            val request = async { fixture.repository.getHomePageResourceShow(refresh = true) }
            started.await()
            fixture.preferences.value = account(type = NeteaseSessionType.Web)
            release.complete(Unit)
            try {
                request.await()
                fail("A response for the previous owner must be cancelled")
            } catch (_: CancellationException) {
                assertTrue(request.isCancelled)
            }
        }
        assertArrayEquals(before, fixture.file.readBytes())
    }

    @Test
    fun cacheRemainsFreshAcrossMidnightUntilTheSixAmBoundary() = runBlocking {
        val earlyMorning = Instant.parse("2026-10-15T21:00:00Z").toEpochMilli()
        val fixture = Fixture { throw AssertionError("The current daily cache should be reused") }
        fixture.file.writeText(cacheJson(fetchedAt = now))
        val repository = HomeRepository(fixture.file, fixture.preferences, { error("Unexpected fetch") }, { earlyMorning })

        assertEquals(listOf("COMPLETE"), repository.getHomePageResourceShow().positions())
    }

    private inner class Fixture(fetch: suspend (Boolean) -> List<Block>) {
        val file = File(temporary.newFolder(), "home_page_data_1.json")
        val preferences = MutableStateFlow<Preferences>(account())
        val repository = HomeRepository(file, preferences, fetch, { now })
    }

    private fun account(userId: String = "17", type: NeteaseSessionType = NeteaseSessionType.Mobile): Preferences =
        mutablePreferencesOf(CookieKey to "private-session-cookie", UserIdKey to userId, NeteaseSessionTypeKey to type.name)

    private fun cacheJson(
        version: Int = 2,
        owner: HomePageOwner = HomePageOwner("17", NeteaseSessionType.Mobile),
        fetchedAt: Long = now,
    ): String = gson.toJson(mapOf("version" to version, "owner" to owner, "fetchedAt" to fetchedAt, "blocks" to blocks("COMPLETE")))

    private fun blocks(vararg positions: String): List<Block> = gson.fromJson(
        gson.toJson(mapOf("code" to 200, "data" to mapOf("blocks" to positions.map { mapOf("positionCode" to it) }))),
        HomePageResourceShow::class.java,
    ).data.blocks

    private fun Resource<List<Block>>.positions(): List<String> {
        assertTrue("Expected a successful home feed, got $this", this is Resource.Success)
        return (this as Resource.Success).data.map { it.positionCode }
    }
}
