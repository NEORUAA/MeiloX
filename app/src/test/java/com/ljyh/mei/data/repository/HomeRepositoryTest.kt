package com.ljyh.mei.data.repository

import com.google.gson.Gson
import com.ljyh.mei.data.model.eapi.HomePageResourceShow.Data.Block
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.data.network.api.EApiService
import com.ljyh.mei.data.session.SessionStamp
import com.ljyh.mei.data.session.SessionStore
import com.ljyh.mei.data.session.SessionChangedException
import com.ljyh.mei.data.session.SessionIdentity
import java.io.File
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import retrofit2.http.Tag

@OptIn(ExperimentalCoroutinesApi::class)
class HomeRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private var identity = SessionIdentity(1, true, false)
    private val sessions = SessionStore().apply { bind { identity } }
    private val timestamp = Instant.parse("2026-09-29T04:00:00Z").toEpochMilli()

    private fun blocks(name: String) = listOf(Gson().fromJson("""{"positionCode":"$name"}""", Block::class.java))
    private fun Resource<List<Block>>.name() = (this as Resource.Success).data.single().positionCode

    @Test fun homeRetrofitRouteRequiresAnExplicitSessionTag() {
        val method = EApiService::class.java.methods.single { it.name == "getHomePageResourceShow" }
        assertTrue("Home requests must carry the triggering session through Retrofit",
            method.parameterTypes.indices.any { index ->
                method.parameterTypes[index] == SessionStamp::class.java &&
                    method.parameterAnnotations[index].any { it is Tag }
            })
    }

    @Test fun currentAccountCacheSurvivesRepositoryRecreation() = runTest {
        val directory = temporary.newFolder()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val first = HomeRepository(directory, sessions, { _, _ -> blocks("first") }, { timestamp }, dispatcher)
        assertEquals("first", first.getHomePageResourceShow(sessions.snapshot()).name())
        val next = HomeRepository(directory, sessions, { _, _ -> error("Unexpected network request") }, { timestamp }, dispatcher)
        assertEquals("first", next.getHomePageResourceShow(sessions.snapshot()).name())
        assertEquals(listOf("user_1.json"), directory.list()!!.toList())
    }

    @Test fun accountsAndAnonymousSessionsNeverSharePersonalizedCache() = runTest {
        var loads = 0
        val repository = HomeRepository(temporary.newFolder(), sessions, { _, _ -> blocks("load-${++loads}") }, { timestamp }, StandardTestDispatcher(testScheduler))
        assertEquals("load-1", repository.getHomePageResourceShow(sessions.snapshot()).name())
        sessions.beginTransition().use { identity = SessionIdentity(2, true, false) }
        assertEquals("load-2", repository.getHomePageResourceShow(sessions.snapshot()).name())
        sessions.beginTransition().use { identity = SessionIdentity(1, false, true) }
        assertEquals("load-3", repository.getHomePageResourceShow(sessions.snapshot()).name())
        sessions.beginTransition().use { identity = SessionIdentity(1, true, false) }
        assertEquals("load-1", repository.getHomePageResourceShow(sessions.snapshot()).name())
        assertEquals(3, loads)
    }

    @Test fun legacySharedCacheIsNotImported() = runTest {
        val directory = temporary.newFolder()
        File(directory, "home_page_data_1.json").writeText(Gson().toJson(blocks("legacy")))
        val repository = HomeRepository(directory, sessions, { _, _ -> blocks("official") }, { timestamp }, StandardTestDispatcher(testScheduler))
        assertEquals("official", repository.getHomePageResourceShow(sessions.snapshot()).name())
    }

    @Test fun dailyBoundaryAndExplicitRefreshFetchFreshContent() = runTest {
        var clock = timestamp
        var loads = 0
        val refreshFlags = mutableListOf<Boolean>()
        val repository = HomeRepository(temporary.newFolder(), sessions, { refresh, _ ->
            refreshFlags += refresh
            blocks("load-${++loads}")
        }, { clock }, StandardTestDispatcher(testScheduler))
        val stamp = sessions.snapshot()
        assertEquals("load-1", repository.getHomePageResourceShow(stamp).name())
        assertEquals("load-1", repository.getHomePageResourceShow(stamp).name())
        assertEquals("load-2", repository.getHomePageResourceShow(stamp, refresh = true).name())
        clock += 86_400_000L
        assertEquals("load-3", repository.getHomePageResourceShow(stamp).name())
        assertEquals(listOf(false, true, false), refreshFlags)
    }

    @Test fun malformedOrFutureCacheIsRefetchedAndEmptyResponsesAreCacheable() = runTest {
        val directory = temporary.newFolder()
        var loads = 0
        val repository = HomeRepository(directory, sessions, { _, _ -> loads++; emptyList() }, { timestamp }, StandardTestDispatcher(testScheduler))
        val file = File(directory, "user_1.json")
        for (invalid in listOf("{", "null", "{}", "{\"fetchedAt\":${timestamp + 1},\"blocks\":[]}",
            "{\"fetchedAt\":$timestamp,\"blocks\":[null]}", "{\"fetchedAt\":$timestamp,\"blocks\":[{}]}")) {
            file.writeText(invalid)
            assertEquals(Resource.Success(emptyList<Block>()), repository.getHomePageResourceShow(sessions.snapshot()))
        }
        assertEquals(6, loads)
        repository.getHomePageResourceShow(sessions.snapshot())
        assertEquals(6, loads)
    }

    @Test fun failedRefreshDoesNotDestroyTheExistingAccountCache() = runTest {
        var fail = false
        val repository = HomeRepository(temporary.newFolder(), sessions, { _, _ ->
            if (fail) throw IOException("Offline") else blocks("cached")
        }, { timestamp }, StandardTestDispatcher(testScheduler))
        val stamp = sessions.snapshot()
        repository.getHomePageResourceShow(stamp)
        fail = true
        assertEquals(Resource.Error("Offline"), repository.getHomePageResourceShow(stamp, refresh = true))
        assertEquals("cached", repository.getHomePageResourceShow(stamp).name())
    }

    @Test fun unwritableCacheDoesNotHideSuccessfulNetworkResponse() = runTest {
        val repository = HomeRepository(temporary.newFile(), sessions, { _, _ -> blocks("network") }, { timestamp }, StandardTestDispatcher(testScheduler))
        assertEquals("network", repository.getHomePageResourceShow(sessions.snapshot()).name())
    }

    @Test fun sameAccountReauthorizationRejectsOldResponseBeforeWritingCache() = runTest {
        val directory = temporary.newFolder()
        val pending = CompletableDeferred<List<Block>>()
        val repository = HomeRepository(directory, sessions, { _, _ -> pending.await() }, { timestamp }, StandardTestDispatcher(testScheduler))
        val request = async { runCatching { repository.getHomePageResourceShow(sessions.snapshot()) } }
        runCurrent()
        sessions.invalidate()
        pending.complete(blocks("old"))
        assertTrue(request.await().exceptionOrNull() is SessionChangedException)
        assertTrue(directory.list()!!.isEmpty())
    }

    @Test fun cancellationIsNotAnErrorResponseAndDoesNotPopulateCache() = runTest {
        val directory = temporary.newFolder()
        val pending = CompletableDeferred<List<Block>>()
        var published = false
        val repository = HomeRepository(directory, sessions, { _, _ ->
            withContext(NonCancellable) { pending.await() }
        }, { timestamp }, StandardTestDispatcher(testScheduler))
        val request = launch { repository.getHomePageResourceShow(sessions.snapshot()); published = true }
        runCurrent()
        request.cancel()
        pending.complete(blocks("late"))
        request.cancelAndJoin()
        assertFalse(published)
        assertTrue(directory.list()!!.isEmpty())
    }

    @Test fun invalidationDuringCachePreparationLeavesNoPartialOrOldSessionFile() = runTest {
        val directory = temporary.newFolder()
        val repository = HomeRepository(directory, sessions, { _, _ -> blocks("late") }, {
            sessions.invalidate()
            timestamp
        }, StandardTestDispatcher(testScheduler))
        val error = runCatching { repository.getHomePageResourceShow(sessions.snapshot()) }.exceptionOrNull()
        assertTrue(error is SessionChangedException)
        assertTrue(directory.list()!!.isEmpty())
    }

    @Test fun initialRefreshAndAnonymousFetchesCarryTheirTriggeringOwners() = runTest {
        val requests = mutableListOf<Pair<Boolean, SessionStamp>>()
        val repository = HomeRepository(temporary.newFolder(), sessions, { refresh, owner ->
            requests += refresh to owner
            blocks("account-${owner.identity.userId}")
        }, { timestamp }, StandardTestDispatcher(testScheduler))
        val initial = sessions.snapshot()
        repository.getHomePageResourceShow(initial)
        repository.getHomePageResourceShow(initial)
        repository.getHomePageResourceShow(initial, refresh = true)
        sessions.beginTransition().use { identity = SessionIdentity(0, false, true) }
        val anonymous = sessions.snapshot()
        repository.getHomePageResourceShow(anonymous)
        assertEquals(listOf(false to initial, true to initial, false to anonymous), requests)
    }

    @Test fun recoveryBlocksCachedAndFreshReadsUntilTheSessionIsReady() = runTest {
        var loads = 0
        val repository = HomeRepository(temporary.newFolder(), sessions, { _, _ ->
            loads++
            blocks("cached")
        }, { timestamp }, StandardTestDispatcher(testScheduler))
        val owner = sessions.snapshot()
        repository.getHomePageResourceShow(owner)
        sessions.setRecoveryRequired(true)
        for (refresh in listOf(false, true)) {
            assertTrue(runCatching { repository.getHomePageResourceShow(owner, refresh) }.isFailure)
        }
        assertEquals(1, loads)
        sessions.setRecoveryRequired(false)
        assertEquals("cached", repository.getHomePageResourceShow(owner).name())
    }

    @Test fun recoveryDuringTheResponseCannotPublishOrPopulateCache() = runTest {
        val directory = temporary.newFolder()
        var failRecovery = true
        val repository = HomeRepository(directory, sessions, { _, _ ->
            if (failRecovery) sessions.setRecoveryRequired(true)
            blocks("reply")
        }, { timestamp }, StandardTestDispatcher(testScheduler))
        val owner = sessions.snapshot()
        assertTrue(runCatching { repository.getHomePageResourceShow(owner) }.isFailure)
        assertTrue(directory.list()!!.isEmpty())
        sessions.setRecoveryRequired(false)
        failRecovery = false
        assertEquals("reply", repository.getHomePageResourceShow(owner).name())
    }
}
