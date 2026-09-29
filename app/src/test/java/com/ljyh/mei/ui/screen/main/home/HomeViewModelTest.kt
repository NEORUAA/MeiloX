package com.ljyh.mei.ui.screen.main.home

import androidx.lifecycle.ViewModelStore
import com.google.gson.Gson
import com.ljyh.mei.data.model.eapi.HomePageResourceShow.Data.Block
import com.ljyh.mei.data.model.room.CacheColor
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.data.repository.HomeRepository
import com.ljyh.mei.di.dao.ColorDao
import com.ljyh.mei.di.repository.ColorRepository
import com.ljyh.mei.parasite.HostSessionBridge
import com.ljyh.mei.parasite.HostSessionIdentity
import java.io.IOException
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    @get:Rule val temporary = TemporaryFolder()
    private var identity = HostSessionIdentity(1, true, false)
    private val sessions = HostSessionBridge()
    private val colors = ColorRepository(object : ColorDao {
        override fun getColor(url: String): CacheColor? = null
        override suspend fun insertColor(color: CacheColor) = Unit
    })

    private fun blocks(name: String) = listOf(Gson().fromJson("""{"positionCode":"$name"}""", Block::class.java))
    private fun HomeViewModel.name() = (homePageResourceShow.value as Resource.Success).data.single().positionCode

    private fun checkModel(
        fetch: suspend (Boolean) -> List<Block>,
        check: suspend TestScope.(HomeViewModel, ViewModelStore) -> Unit,
    ) = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val store = ViewModelStore()
        try {
            val repository = HomeRepository(temporary.newFolder(), sessions, fetch,
                { Instant.parse("2026-09-29T04:00:00Z").toEpochMilli() }, dispatcher)
            val model = HomeViewModel(repository, colors, sessions)
            store.put("home", model)
            check(model, store)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test fun bindingStartsTheHomeRequestWithoutAStoredUid() = checkModel({ blocks("guest") }) { model, _ ->
        runCurrent()
        assertEquals(Resource.Loading, model.homePageResourceShow.value)
        identity = HostSessionIdentity(0, false, true)
        sessions.bind { identity }
        runCurrent()
        assertEquals("guest", model.name())
    }

    @Test fun switchingAccountsClearsContentImmediatelyAndLoadsTheNewAccount() = checkModel({
        blocks("account-${identity.userId}")
    }) { model, _ ->
        sessions.bind { identity }
        runCurrent()
        assertEquals("account-1", model.name())
        sessions.beginTransition().use {
            identity = HostSessionIdentity(2, true, false)
            assertEquals(Resource.Loading, model.homePageResourceShow.value)
            runCurrent()
            assertEquals(Resource.Loading, model.homePageResourceShow.value)
        }
        runCurrent()
        assertEquals("account-2", model.name())
        sessions.beginTransition().use { identity = HostSessionIdentity(0, false, true) }
        assertEquals(Resource.Loading, model.homePageResourceShow.value)
        runCurrent()
        assertEquals("account-0", model.name())
    }

    @Test fun nonCooperativeOldAccountResponseCannotReplaceNewContent() {
        val old = CompletableDeferred<List<Block>>()
        var requests = 0
        checkModel({
            if (requests++ == 0) withContext(NonCancellable) { old.await() } else blocks("new")
        }) { model, _ ->
            sessions.bind { identity }
            runCurrent()
            sessions.beginTransition().use { identity = HostSessionIdentity(2, true, false) }
            runCurrent()
            assertEquals("new", model.name())
            old.complete(blocks("old"))
            runCurrent()
            assertEquals("new", model.name())
        }
    }

    @Test fun sameAccountRefreshCancelsAnOlderNonCooperativeRequest() {
        val old = CompletableDeferred<List<Block>>()
        var requests = 0
        checkModel({
            if (requests++ == 0) withContext(NonCancellable) { old.await() } else blocks("refreshed")
        }) { model, _ ->
            sessions.bind { identity }
            runCurrent()
            model.homePageResourceShow(refresh = true)
            runCurrent()
            assertEquals("refreshed", model.name())
            old.complete(blocks("old"))
            runCurrent()
            assertEquals("refreshed", model.name())
        }
    }

    @Test fun recoveryFailureShowsAnErrorAndResolutionReloadsTheFeed() = checkModel({ blocks("restored") }) { model, _ ->
        sessions.bind { identity }
        runCurrent()
        val transition = sessions.beginTransition()
        sessions.setRecoveryRequired(true)
        runCurrent()
        assertTrue(model.homePageResourceShow.value is Resource.Error)
        sessions.setRecoveryRequired(false)
        transition.close()
        runCurrent()
        assertEquals("restored", model.name())
    }

    @Test fun networkFailureCanBeRetried() {
        var failed = true
        checkModel({ if (failed) throw IOException("Offline") else blocks("retried") }) { model, _ ->
            sessions.bind { identity }
            runCurrent()
            assertEquals(Resource.Error("Offline"), model.homePageResourceShow.value)
            failed = false
            model.homePageResourceShow(refresh = true)
            runCurrent()
            assertEquals("retried", model.name())
        }
    }

    @Test fun clearingTheViewModelDiscardsUnfinishedResponses() {
        val pending = CompletableDeferred<List<Block>>()
        checkModel({ withContext(NonCancellable) { pending.await() } }) { model, store ->
            sessions.bind { identity }
            runCurrent()
            store.clear()
            pending.complete(blocks("late"))
            runCurrent()
            assertEquals(Resource.Loading, model.homePageResourceShow.value)
        }
    }

    @Test fun delayedInvalidationCannotEraseANewerSessionPublication() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        sessions.onInvalidated { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        val executor = Executors.newSingleThreadExecutor()
        try {
            var result = "old"
            checkModel({ blocks(result) }) { model, _ ->
                sessions.bind { identity }
                runCurrent()
                val invalidating = executor.submit { sessions.invalidate() }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                result = "new"
                model.homePageResourceShow(refresh = true)
                runCurrent()
                assertEquals("new", model.name())
                release.countDown()
                invalidating.get(5, TimeUnit.SECONDS)
                assertEquals("new", model.name())
            }
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun delayedInvalidationPreservesTheRecoveryErrorInsteadOfLeavingASpinner() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        sessions.onInvalidated { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        val executor = Executors.newSingleThreadExecutor()
        try {
            checkModel({ blocks("home") }) { model, _ ->
                sessions.bind { identity }
                runCurrent()
                val invalidating = executor.submit<java.io.Closeable> { sessions.beginTransition() }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                sessions.setRecoveryRequired(true)
                runCurrent()
                assertTrue(model.homePageResourceShow.value is Resource.Error)
                release.countDown()
                val transition = invalidating.get(5, TimeUnit.SECONDS)
                assertTrue(model.homePageResourceShow.value is Resource.Error)
                transition.close()
            }
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }
}
