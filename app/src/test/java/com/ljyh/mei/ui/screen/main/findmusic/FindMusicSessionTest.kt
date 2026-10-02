package com.ljyh.mei.ui.screen.main.findmusic

import androidx.lifecycle.ViewModelStore
import com.ljyh.mei.data.model.weapi.HighQualityPlaylistResult
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.data.repository.HighQualityPlaylistSource
import com.ljyh.mei.data.session.SessionIdentity
import com.ljyh.mei.data.session.SessionStamp
import com.ljyh.mei.data.session.SessionStore
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
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FindMusicSessionTest {
    private var identity = SessionIdentity(17, true, false)
    private val sessions = SessionStore().apply { bind { identity } }
    private data class Request(val category: String, val limit: Int, val owner: SessionStamp)
    private val requests = mutableListOf<Request>()
    private var read: suspend (String, Int, SessionStamp) -> Resource<HighQualityPlaylistResult> = { _, _, _ -> success(requests.size) }
    private val source = object : HighQualityPlaylistSource {
        override suspend fun getHighQualityPlaylist(cat: String, limit: Int, session: SessionStamp): Resource<HighQualityPlaylistResult> {
            requests += Request(cat, limit, session)
            return read(cat, limit, session)
        }
    }

    private fun withModel(modelSessions: SessionStore = sessions, check: suspend TestScope.(FindMusicViewModel, ViewModelStore) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val model = FindMusicViewModel(source, modelSessions)
            store.put("discovery", model)
            check(model, store)
        } finally {
            store.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    @Test fun pendingAnonymousRecoveryBlocksReadsAndResumesTheLatestCategoryAndLimit() {
        identity = SessionIdentity(0, false, false)
        sessions.setRecoveryRequired(true)
        withModel { model, _ ->
            val owner = sessions.snapshot()
            model.onCategorySelected("ACG")
            model.loadCategoryData("ACG", 57, true)
            runCurrent()
            assertTrue(requests.isEmpty())
            assertTrue(model.highQualityPlaylist.value is Resource.Error)
            sessions.setRecoveryRequired(false)
            runCurrent()
            assertEquals(owner, sessions.snapshot())
            assertEquals(listOf(Request("ACG", 57, owner)), requests)
        }
    }

    @Test fun accountChangeImmediatelyRetiresCachedDataAndReloadsTheSelectedCategory() = withModel { model, _ ->
        runCurrent()
        model.onCategorySelected("ACG")
        runCurrent()
        assertEquals(2, resultMarker(model))
        identity = SessionIdentity(18, true, false)
        sessions.invalidate()
        assertTrue(model.highQualityPlaylist.value is Resource.Loading)
        runCurrent()
        assertEquals(3, resultMarker(model))
        assertEquals(Request("ACG", 30, sessions.snapshot()), requests.last())
        model.onCategorySelected("全部")
        runCurrent()
        assertEquals(4, requests.size)
        assertEquals(4, resultMarker(model))
    }

    @Test fun recoveryImmediatelyRejectsCachedCategoriesAndResumesWithoutChangingTheStamp() = withModel { model, _ ->
        runCurrent()
        model.onCategorySelected("ACG")
        runCurrent()
        val owner = sessions.snapshot()
        sessions.setRecoveryRequired(true)
        model.onCategorySelected("全部")
        assertTrue(model.highQualityPlaylist.value is Resource.Error)
        runCurrent()
        assertEquals(2, requests.size)
        sessions.setRecoveryRequired(false)
        runCurrent()
        assertEquals(owner, sessions.snapshot())
        assertEquals(3, requests.size)
        assertEquals(Request("全部", 30, owner), requests.last())
        assertEquals(3, resultMarker(model))
    }

    @Test fun recoveryBetweenReservationAndDispatchCannotRequestAnyCategory() = withModel { model, _ ->
        model.loadCategoryData("ACG", 57, true)
        sessions.setRecoveryRequired(true)
        runCurrent()
        assertTrue(requests.isEmpty())
        assertTrue(model.highQualityPlaylist.value is Resource.Error)
    }

    @Test fun lateNonCooperativeResponsesCannotPopulateTheNextAccountOrItsCache() {
        val late = CompletableDeferred<Resource<HighQualityPlaylistResult>>()
        read = { _, _, owner -> if (owner.identity.userId == 17L) withContext(NonCancellable) { late.await() } else success(18) }
        withModel { model, _ ->
            try {
                runCurrent()
                identity = SessionIdentity(18, true, false)
                sessions.invalidate()
                runCurrent()
            } finally {
                late.complete(success(17))
                runCurrent()
            }
            assertEquals(18, resultMarker(model))
            model.onCategorySelected("全部")
            runCurrent()
            assertEquals(18, resultMarker(model))
            assertEquals(2, requests.size)
        }
    }

    @Test fun recoveryRetiresLateFailuresAndBlocksExplicitForcedRetries() {
        val late = CompletableDeferred<Resource<HighQualityPlaylistResult>>()
        read = { _, _, _ -> withContext(NonCancellable) { late.await() } }
        withModel { model, _ ->
            try {
                runCurrent()
                sessions.setRecoveryRequired(true)
                runCurrent()
                model.loadCategoryData("ACG", 57, true)
                runCurrent()
            } finally {
                late.complete(Resource.Error("Retired failure"))
                runCurrent()
            }
            assertEquals(1, requests.size)
            assertEquals(Resource.Error("Official session recovery is required"), model.highQualityPlaylist.value)
            read = { _, _, _ -> success(18) }
            sessions.setRecoveryRequired(false)
            runCurrent()
            assertEquals(18, resultMarker(model))
            assertEquals(Request("ACG", 57, sessions.snapshot()), requests.last())
        }
    }

    @Test fun clearingTheViewModelRejectsANonCooperativeResult() {
        val late = CompletableDeferred<Resource<HighQualityPlaylistResult>>()
        read = { _, _, _ -> withContext(NonCancellable) { late.await() } }
        withModel { model, store ->
            try { runCurrent(); store.clear() }
            finally { late.complete(success(17)); runCurrent() }
            assertTrue(model.highQualityPlaylist.value is Resource.Loading)
        }
    }

    @Test fun readyGuestCacheAliasAndForcedRefreshKeepOriginalSemantics() {
        identity = SessionIdentity(0, false, true)
        withModel { model, _ ->
            runCurrent()
            assertEquals(Request("全部", 30, sessions.snapshot()), requests.single())
            model.onCategorySelected("全部")
            runCurrent()
            assertEquals(1, requests.size)
            model.loadCategoryData("全部", 57, true)
            runCurrent()
            assertEquals(2, resultMarker(model))
            assertEquals(57, requests.last().limit)
            model.onCategorySelected("排行榜")
            runCurrent()
            assertEquals("榜单", model.selectedCategory.value)
            assertEquals("榜单", requests.last().category)
        }
    }

    @Test fun readyNetworkFailuresAreRetryableWithoutCachingTheFailure() = withModel { model, _ ->
        read = { _, _, _ -> Resource.Error("Offline") }
        runCurrent()
        assertEquals(Resource.Error("Offline"), model.highQualityPlaylist.value)
        read = { _, _, _ -> success(2) }
        model.loadCategoryData("全部")
        runCurrent()
        assertEquals(2, requests.size)
        assertEquals(2, resultMarker(model))
    }

    @Test fun unboundStartupRetainsTheLatestCategoryUntilTheBackendPublishesAnIdentity() {
        val unbound = SessionStore()
        withModel(unbound) { model, _ ->
            model.onCategorySelected("ACG")
            runCurrent()
            assertTrue(requests.isEmpty())
            assertTrue(model.highQualityPlaylist.value is Resource.Loading)
            unbound.bind { SessionIdentity(19, true, false) }
            runCurrent()
            assertEquals(listOf(Request("ACG", 30, unbound.snapshot())), requests)
        }
    }

    @Test fun heldSameAccountReauthorizationBlocksReadsAndResumesTheLatestIntent() = withModel { model, _ ->
        runCurrent()
        val owner = sessions.snapshot()
        val transition = sessions.beginTransition()
        try {
            assertTrue(model.highQualityPlaylist.value is Resource.Loading)
            model.onCategorySelected("ACG")
            model.loadCategoryData("ACG", 57, true)
            runCurrent()
            assertEquals(1, requests.size)
        } finally { transition.close() }
        runCurrent()
        assertEquals(owner.identity, sessions.snapshot().identity)
        assertNotEquals(owner, sessions.snapshot())
        assertEquals(Request("ACG", 57, sessions.snapshot()), requests.last())
        assertEquals(2, requests.size)
    }

    companion object {
        private fun success(marker: Int) = Resource.Success(HighQualityPlaylistResult(200, 0, false, emptyList(), marker))
        private fun resultMarker(model: FindMusicViewModel) = (model.highQualityPlaylist.value as Resource.Success).data.total
    }
}
