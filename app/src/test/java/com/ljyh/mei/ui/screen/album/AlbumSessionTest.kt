package com.ljyh.mei.ui.screen.album

import androidx.lifecycle.ViewModelStore
import com.google.gson.Gson
import com.ljyh.mei.constants.MusicQuality
import com.ljyh.mei.data.model.AlbumDetail
import com.ljyh.mei.data.model.SongUrl
import com.ljyh.mei.data.model.api.BaseResponse
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.data.repository.AlbumDetailSource
import com.ljyh.mei.parasite.HostSessionBridge
import com.ljyh.mei.parasite.HostSessionIdentity
import com.ljyh.mei.parasite.HostSessionStamp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
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
class AlbumSessionTest {
    private var identity = HostSessionIdentity(1, true, false)
    private val sessions = HostSessionBridge().apply { bind { identity } }
    private val source = Source()
    private val notifications = mutableListOf<HostSessionStamp>()
    private var notify: (HostSessionStamp) -> Unit = { notifications += it }

    private class Source : AlbumDetailSource {
        val reads = mutableListOf<Pair<HostSessionStamp, String>>()
        val collectionReads = mutableListOf<Pair<HostSessionStamp, String>>()
        val mutations = mutableListOf<Triple<HostSessionStamp, String, Boolean>>()
        val urlOwners = mutableListOf<HostSessionStamp?>()
        var detail: suspend (String, HostSessionStamp) -> Resource<AlbumDetail> = { id, _ -> Resource.Success(album(id)) }
        var collection: suspend (String, HostSessionStamp) -> Resource<Boolean> = { _, _ -> Resource.Success(false) }
        var mutate: suspend (String, Boolean, HostSessionStamp) -> Resource<BaseResponse> = { _, _, _ -> Resource.Success(BaseResponse(200)) }
        var urls: suspend () -> Resource<SongUrl> = { Resource.Success(SongUrl(200, emptyList())) }
        override suspend fun getAlbumDetail(id: String, session: HostSessionStamp): Resource<AlbumDetail> {
            reads += session to id
            return detail(id, session)
        }
        override suspend fun getAlbumCollection(id: String, session: HostSessionStamp): Resource<Boolean> {
            collectionReads += session to id
            return collection(id, session)
        }
        override suspend fun setAlbumCollection(id: String, collected: Boolean, session: HostSessionStamp): Resource<BaseResponse> {
            mutations += Triple(session, id, collected)
            return mutate(id, collected, session)
        }
        override suspend fun getSongUrlV1(ids: List<String>, quality: MusicQuality, session: HostSessionStamp?): Resource<SongUrl> {
            urlOwners += session
            return urls()
        }
    }

    private fun checkModel(check: suspend TestScope.(AlbumDetailViewModel, ViewModelStore) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val model = AlbumDetailViewModel(source, sessions) { notify(it) }
            store.put("album", model)
            runCurrent()
            check(model, store)
        } finally {
            store.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    @Test fun readsCollectionFromTheSameOfficialSessionAsTheAlbum() = checkModel { model, _ ->
        source.collection = { _, _ -> Resource.Success(true) }
        model.getAlbumDetail("10")
        runCurrent()
        assertTrue(model.state.value.detail is Resource.Success)
        assertEquals(true, model.state.value.collected)
        assertEquals(source.reads, source.collectionReads)
        assertEquals(sessions.snapshot(), model.state.value.session)
    }

    @Test fun guestsReadAlbumsButCannotSubmitCollectionWrites() {
        identity = HostSessionIdentity(0, false, true)
        checkModel { model, _ ->
            model.getAlbumDetail("10")
            runCurrent()
            assertTrue(model.state.value.detail is Resource.Success)
            assertEquals(false, model.state.value.collected)
            assertTrue(source.collectionReads.isEmpty())
            model.toggleCollection()
            runCurrent()
            assertTrue(model.state.value.mutation is Resource.Error)
            assertTrue(source.mutations.isEmpty())
        }
    }

    @Test fun failedCollectionReadIsNotDisplayedAsUncollectedAndCanRetry() = checkModel { model, _ ->
        source.collection = { _, _ -> Resource.Error("Offline") }
        model.getAlbumDetail("10")
        runCurrent()
        assertEquals(Resource.Error("Offline"), model.state.value.detail)
        assertNull(model.state.value.collected)
        model.toggleCollection()
        assertTrue(source.mutations.isEmpty())
        source.collection = { _, _ -> Resource.Success(true) }
        model.getAlbumDetail("10")
        runCurrent()
        assertEquals(true, model.state.value.collected)
    }

    @Test fun lateAlbumResultCannotReplaceAnotherAlbum() {
        val old = CompletableDeferred<AlbumDetail>()
        source.detail = { id, _ -> Resource.Success(if (id == "10") withContext(NonCancellable) { old.await() } else album(id)) }
        checkModel { model, _ ->
            try {
                model.getAlbumDetail("10")
                runCurrent()
                model.getAlbumDetail("20")
                runCurrent()
            } finally { old.complete(album("10")); runCurrent() }
            assertEquals(20L, (model.state.value.detail as Resource.Success).data.album.id)
        }
    }

    @Test fun reauthorizationClearsPresentationBeforeReloading() = checkModel { model, _ ->
        model.getAlbumDetail("10")
        runCurrent()
        val old = model.state.value.session
        sessions.invalidate()
        assertNull(model.state.value.session)
        assertNull(model.state.value.collected)
        assertEquals(Resource.Loading, model.state.value.detail)
        runCurrent()
        assertNotEquals(old, model.state.value.session)
        assertEquals(2, source.reads.size)
    }

    @Test fun optimisticMutationRunsOnceAndPublishesOneLibraryNotification() {
        val pending = CompletableDeferred<BaseResponse>()
        source.mutate = { _, _, _ -> Resource.Success(pending.await()) }
        checkModel { model, _ ->
            model.getAlbumDetail("10")
            runCurrent()
            model.toggleCollection()
            model.toggleCollection()
            assertEquals(true, model.state.value.collected)
            runCurrent()
            model.toggleCollection()
            assertEquals(1, source.mutations.size)
            pending.complete(BaseResponse(200))
            runCurrent()
            assertFalse(model.state.value.changingCollection)
            assertEquals(listOf(sessions.snapshot()), notifications)
            val event = model.state.value.mutation!!
            model.consumeMutation(event)
            assertNull(model.state.value.mutation)
        }
    }

    @Test fun rejectedBusinessCodeRollsBackInsteadOfUpdatingLocalCollection() = checkModel { model, _ ->
        source.mutate = { _, _, _ -> Resource.Success(BaseResponse(301)) }
        model.getAlbumDetail("10")
        runCurrent()
        model.toggleCollection()
        runCurrent()
        assertEquals(false, model.state.value.collected)
        assertTrue(model.state.value.mutation is Resource.Error)
        assertTrue(notifications.isEmpty())
    }

    @Test fun failedUnsubscribeRetainsThePreviouslyCollectedState() = checkModel { model, _ ->
        source.collection = { _, _ -> Resource.Success(true) }
        source.mutate = { _, _, _ -> Resource.Error("Offline") }
        model.getAlbumDetail("10")
        runCurrent()
        model.toggleCollection()
        assertEquals(false, model.state.value.collected)
        runCurrent()
        assertEquals(true, model.state.value.collected)
        assertTrue(notifications.isEmpty())
    }

    @Test fun refreshWaitsForTheInFlightWriteBeforeReadingCollectionAgain() {
        var collected = false
        val pending = CompletableDeferred<Unit>()
        source.collection = { _, _ -> Resource.Success(collected) }
        source.mutate = { _, value, _ -> pending.await(); collected = value; Resource.Success(BaseResponse(200)) }
        checkModel { model, _ ->
            model.getAlbumDetail("10")
            runCurrent()
            model.toggleCollection()
            runCurrent()
            model.getAlbumDetail("10")
            model.getAlbumDetail("10")
            runCurrent()
            assertEquals(1, source.reads.size)
            pending.complete(Unit)
            runCurrent()
            assertEquals(2, source.reads.size)
            assertEquals(true, model.state.value.collected)
            assertTrue(model.state.value.detail is Resource.Success)
        }
    }

    @Test fun accountSwitchRejectsLateMutationAndDoesNotNotifyTheNewLibrary() {
        val old = CompletableDeferred<BaseResponse>()
        source.mutate = { _, _, _ -> withContext(NonCancellable) { Resource.Success(old.await()) } }
        checkModel { model, _ ->
            try {
                model.getAlbumDetail("10")
                runCurrent()
                model.toggleCollection()
                runCurrent()
                sessions.beginTransition().use { identity = HostSessionIdentity(2, true, false) }
                runCurrent()
                assertEquals(false, model.state.value.collected)
            } finally { old.complete(BaseResponse(200)); runCurrent() }
            assertTrue(notifications.isEmpty())
            assertNull(model.state.value.mutation)
            assertEquals(2L, model.state.value.session!!.identity.userId)
        }
    }

    @Test fun lateMutationFailureCannotRollBackAnotherAlbum() {
        val old = CompletableDeferred<Unit>()
        source.collection = { id, _ -> Resource.Success(id == "20") }
        source.mutate = { _, _, _ -> withContext(NonCancellable) { old.await(); Resource.Error("Offline") } }
        checkModel { model, _ ->
            try {
                model.getAlbumDetail("10")
                runCurrent()
                model.toggleCollection()
                runCurrent()
                model.getAlbumDetail("20")
                runCurrent()
            } finally { old.complete(Unit); runCurrent() }
            assertEquals("20", model.state.value.id)
            assertEquals(true, model.state.value.collected)
            assertNull(model.state.value.mutation)
        }
    }

    @Test fun collectionNotificationFailureDoesNotUndoAnAcceptedWrite() = checkModel { model, _ ->
        notify = { error("Observer failed") }
        model.getAlbumDetail("10")
        runCurrent()
        model.toggleCollection()
        runCurrent()
        assertEquals(true, model.state.value.collected)
        assertTrue(model.state.value.mutation is Resource.Success)
    }

    @Test fun failedRecoveryShowsErrorAndReloadsWhenRecovered() = checkModel { model, _ ->
        model.getAlbumDetail("10")
        runCurrent()
        val transition = sessions.beginTransition()
        sessions.setRecoveryRequired(true)
        runCurrent()
        assertTrue(model.state.value.detail is Resource.Error)
        sessions.setRecoveryRequired(false)
        transition.close()
        runCurrent()
        assertTrue(model.state.value.detail is Resource.Success)
    }

    @Test fun staleUrlResolutionCannotBeReturnedForANewSession() {
        val pending = CompletableDeferred<Unit>()
        source.urls = { withContext(NonCancellable) { pending.await(); Resource.Success(SongUrl(200, emptyList())) } }
        checkModel { model, _ ->
            model.getAlbumDetail("10")
            runCurrent()
            val owner = sessions.snapshot()
            val result = async { runCatching { model.resolveSongUrls(listOf("1"), MusicQuality.STANDARD, owner, "10") } }
            runCurrent()
            sessions.invalidate()
            runCurrent()
            pending.complete(Unit)
            runCurrent()
            assertTrue(result.await().isFailure)
            assertEquals(listOf(owner), source.urlOwners)
        }
    }

    @Test fun clearedModelCannotPublishACompletedMutation() {
        val pending = CompletableDeferred<BaseResponse>()
        source.mutate = { _, _, _ -> withContext(NonCancellable) { Resource.Success(pending.await()) } }
        checkModel { model, store ->
            model.getAlbumDetail("10")
            runCurrent()
            model.toggleCollection()
            runCurrent()
            store.clear()
            pending.complete(BaseResponse(200))
            runCurrent()
            assertTrue(notifications.isEmpty())
            assertNull(model.state.value.mutation)
        }
    }

    private companion object {
        fun album(id: String): AlbumDetail = Gson().fromJson(
            """{"code":200,"album":{"id":$id},"songs":[]}""", AlbumDetail::class.java,
        )
    }
}
