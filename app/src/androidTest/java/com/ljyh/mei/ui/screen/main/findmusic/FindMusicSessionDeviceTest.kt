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

/** Actual Android discovery consumer with private sessions and a socket-free source. */
@OptIn(ExperimentalCoroutinesApi::class)
class FindMusicSessionDeviceTest {
    private var identity = SessionIdentity(17, true, false)
    private val sessions = SessionStore().apply { bind { identity } }
    private data class Request(val category: String, val limit: Int, val owner: SessionStamp)
    private val requests = mutableListOf<Request>()
    private var read: suspend (SessionStamp) -> Resource<HighQualityPlaylistResult> = { success(it.identity.userId.toInt()) }
    private val source = object : HighQualityPlaylistSource {
        override suspend fun getHighQualityPlaylist(cat: String, limit: Int, session: SessionStamp): Resource<HighQualityPlaylistResult> {
            requests += Request(cat, limit, session)
            return read(session)
        }
    }

    private fun withModel(check: suspend TestScope.(FindMusicViewModel) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val model = FindMusicViewModel(source, sessions)
            store.put("discovery", model)
            check(model)
        } finally {
            store.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    @Test fun pendingRecoveryRejectsCachedCategoriesAndResumesTheLatestIntentUnderTheSameStamp() {
        identity = SessionIdentity(0, false, false)
        sessions.setRecoveryRequired(true)
        withModel { model ->
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
            model.onCategorySelected("排行榜")
            runCurrent()
            assertEquals("榜单", model.selectedCategory.value)
            assertEquals(Request("榜单", 30, owner), requests.last())
            sessions.setRecoveryRequired(true)
            model.onCategorySelected("ACG")
            assertTrue(model.highQualityPlaylist.value is Resource.Error)
            model.loadCategoryData("ACG", 57, true)
            runCurrent()
            assertEquals(2, requests.size)
            sessions.setRecoveryRequired(false)
            runCurrent()
            assertEquals(owner, sessions.snapshot())
            assertEquals(3, requests.size)
            assertEquals(Request("ACG", 57, owner), requests.last())
        }
    }

    @Test fun accountChangeRetiresNonCooperativeResultsAndQueuedRecoveryRequests() {
        val late = CompletableDeferred<Resource<HighQualityPlaylistResult>>()
        read = { owner -> if (owner.identity.userId == 17L) withContext(NonCancellable) { late.await() } else success(18) }
        withModel { model ->
            try {
                runCurrent()
                identity = SessionIdentity(18, true, false)
                sessions.invalidate()
                assertTrue(model.highQualityPlaylist.value is Resource.Loading)
                runCurrent()
            } finally {
                late.complete(success(17))
                runCurrent()
            }
            assertEquals(18, (model.highQualityPlaylist.value as Resource.Success).data.total)
            model.onCategorySelected("全部")
            runCurrent()
            assertEquals(2, requests.size)
            assertEquals(18, (model.highQualityPlaylist.value as Resource.Success).data.total)
            model.loadCategoryData("ACG", 57, true)
            sessions.setRecoveryRequired(true)
            runCurrent()
            assertEquals(2, requests.size)
            assertTrue(model.highQualityPlaylist.value is Resource.Error)
        }
    }

    companion object {
        private fun success(marker: Int) = Resource.Success(HighQualityPlaylistResult(200, 0, false, emptyList(), marker))
    }
}
