package com.ljyh.mei.ui.screen.album

import androidx.lifecycle.ViewModelStore
import com.google.gson.Gson
import com.ljyh.mei.constants.MusicQuality
import com.ljyh.mei.data.model.AlbumDetail
import com.ljyh.mei.data.model.DownloadSources
import com.ljyh.mei.data.model.api.BaseResponse
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.data.repository.AlbumDetailSource
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

/** Actual Android ViewModels with private sessions and a closed, synthetic album source. */
@OptIn(ExperimentalCoroutinesApi::class)
class AlbumRecoveryDeviceTest {
    private val sessions = SessionStore().apply { bind { SessionIdentity(17, true, false) } }
    private val reads = mutableListOf<String>()
    private var collectionReads = 0
    private var writes = 0
    private var downloads = 0
    private var notifications = 0
    private var mutate: suspend () -> BaseResponse = { BaseResponse(200) }
    private val source = object : AlbumDetailSource {
        override suspend fun getAlbumDetail(id: String, session: SessionStamp): Resource<AlbumDetail> {
            reads += id
            return Resource.Success(Gson().fromJson("""{"code":200,"album":{"id":$id},"songs":[]}""", AlbumDetail::class.java))
        }

        override suspend fun getAlbumCollection(id: String, session: SessionStamp): Resource<Boolean> {
            collectionReads++
            return Resource.Success(false)
        }

        override suspend fun setAlbumCollection(id: String, collected: Boolean, session: SessionStamp): Resource<BaseResponse> {
            writes++
            return Resource.Success(mutate())
        }

        override suspend fun getDownloadSources(ids: List<String>, quality: MusicQuality, session: SessionStamp): Resource<DownloadSources> {
            downloads++
            return Resource.Success(DownloadSources(emptyList()))
        }
    }

    private fun withModel(check: suspend TestScope.(AlbumDetailViewModel) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val model = AlbumDetailViewModel(source, sessions) { notifications++ }
            store.put("album", model)
            runCurrent()
            check(model)
        } finally {
            store.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    @Test fun pendingRecoveryBlocksReadsAndResumesTheLatestAlbumUnderTheSameStamp() {
        sessions.setRecoveryRequired(true)
        withModel { model ->
            val owner = sessions.snapshot()
            model.getAlbumDetail("10")
            model.getAlbumDetail("20")
            runCurrent()
            assertTrue(reads.isEmpty())
            assertEquals(0, collectionReads)
            assertNull(model.state.value.session)
            assertTrue(model.state.value.detail is Resource.Error)
            sessions.setRecoveryRequired(false)
            runCurrent()
            assertEquals(owner, sessions.snapshot())
            assertEquals(listOf("20"), reads)
            assertEquals(1, collectionReads)
            assertEquals(20L, (model.state.value.detail as Resource.Success).data.album.id)
        }
    }

    @Test fun recoveryRejectsActionsAndRetiresANonCooperativeWriteWithoutLibraryNotification() {
        val completion = CompletableDeferred<BaseResponse>()
        mutate = { withContext(NonCancellable) { completion.await() } }
        withModel { model ->
            try {
                model.getAlbumDetail("10")
                runCurrent()
                val owner = sessions.snapshot()
                model.toggleCollection()
                runCurrent()
                assertEquals(1, writes)
                sessions.setRecoveryRequired(true)
                assertTrue(runCatching { model.requireCurrent(owner, "10") }.isFailure)
                assertTrue(runCatching { model.resolveDownloadSources(listOf("1"), MusicQuality.STANDARD, owner, "10") }.isFailure)
                model.toggleCollection()
                runCurrent()
            } finally {
                completion.complete(BaseResponse(200))
                runCurrent()
            }
            assertEquals(1, writes)
            assertEquals(0, downloads)
            assertEquals(0, notifications)
            assertNull(model.state.value.session)
            assertNull(model.state.value.collected)
            assertNull(model.state.value.mutation)
            assertFalse(model.state.value.changingCollection)
            assertTrue(model.state.value.detail is Resource.Error)
        }
    }
}
