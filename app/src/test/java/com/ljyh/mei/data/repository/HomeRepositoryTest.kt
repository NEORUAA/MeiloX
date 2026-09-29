package com.ljyh.mei.data.repository

import com.google.gson.Gson
import com.ljyh.mei.data.model.eapi.HomePageResourceShow.Data.Block
import com.ljyh.mei.data.network.Resource
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

@OptIn(ExperimentalCoroutinesApi::class)
class HomeRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private var identity = SessionIdentity(1, true, false)
    private val sessions = SessionStore().apply { bind { identity } }
    private val timestamp = Instant.parse("2026-09-29T04:00:00Z").toEpochMilli()

    private fun blocks(name: String) = listOf(Gson().fromJson("""{"positionCode":"$name"}""", Block::class.java))
    private fun Resource<List<Block>>.name() = (this as Resource.Success).data.single().positionCode

    @Test fun currentAccountCacheSurvivesRepositoryRecreation() = runTest {
        val directory = temporary.newFolder()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val first = HomeRepository(directory, sessions, { blocks("first") }, { timestamp }, dispatcher)
        assertEquals("first", first.getHomePageResourceShow(sessions.snapshot()).name())
        val next = HomeRepository(directory, sessions, { error("Unexpected network request") }, { timestamp }, dispatcher)
        assertEquals("first", next.getHomePageResourceShow(sessions.snapshot()).name())
        assertEquals(listOf("user_1.json"), directory.list()!!.toList())
    }

    @Test fun accountsAndAnonymousSessionsNeverSharePersonalizedCache() = runTest {
        var loads = 0
        val repository = HomeRepository(temporary.newFolder(), sessions, { blocks("load-${++loads}") }, { timestamp }, StandardTestDispatcher(testScheduler))
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
        val repository = HomeRepository(directory, sessions, { blocks("official") }, { timestamp }, StandardTestDispatcher(testScheduler))
        assertEquals("official", repository.getHomePageResourceShow(sessions.snapshot()).name())
    }

    @Test fun dailyBoundaryAndExplicitRefreshFetchFreshContent() = runTest {
        var clock = timestamp
        var loads = 0
        val refreshFlags = mutableListOf<Boolean>()
        val repository = HomeRepository(temporary.newFolder(), sessions, {
            refreshFlags += it
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
        val repository = HomeRepository(directory, sessions, { loads++; emptyList() }, { timestamp }, StandardTestDispatcher(testScheduler))
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
        val repository = HomeRepository(temporary.newFolder(), sessions, {
            if (fail) throw IOException("Offline") else blocks("cached")
        }, { timestamp }, StandardTestDispatcher(testScheduler))
        val stamp = sessions.snapshot()
        repository.getHomePageResourceShow(stamp)
        fail = true
        assertEquals(Resource.Error("Offline"), repository.getHomePageResourceShow(stamp, refresh = true))
        assertEquals("cached", repository.getHomePageResourceShow(stamp).name())
    }

    @Test fun unwritableCacheDoesNotHideSuccessfulNetworkResponse() = runTest {
        val repository = HomeRepository(temporary.newFile(), sessions, { blocks("network") }, { timestamp }, StandardTestDispatcher(testScheduler))
        assertEquals("network", repository.getHomePageResourceShow(sessions.snapshot()).name())
    }

    @Test fun sameAccountReauthorizationRejectsOldResponseBeforeWritingCache() = runTest {
        val directory = temporary.newFolder()
        val pending = CompletableDeferred<List<Block>>()
        val repository = HomeRepository(directory, sessions, { pending.await() }, { timestamp }, StandardTestDispatcher(testScheduler))
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
        val repository = HomeRepository(directory, sessions, {
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
        val repository = HomeRepository(directory, sessions, { blocks("late") }, {
            sessions.invalidate()
            timestamp
        }, StandardTestDispatcher(testScheduler))
        val error = runCatching { repository.getHomePageResourceShow(sessions.snapshot()) }.exceptionOrNull()
        assertTrue(error is SessionChangedException)
        assertTrue(directory.list()!!.isEmpty())
    }
}
