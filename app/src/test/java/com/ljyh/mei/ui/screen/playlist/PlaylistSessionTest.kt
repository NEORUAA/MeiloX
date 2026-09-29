package com.ljyh.mei.ui.screen.playlist

import androidx.lifecycle.ViewModelStore
import com.google.gson.Gson
import com.ljyh.mei.data.model.PlaylistDetail
import com.ljyh.mei.data.model.api.BaseResponse
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.data.network.api.ApiService
import com.ljyh.mei.data.repository.PlaylistCollectionBackend
import com.ljyh.mei.data.network.api.WeApiService
import com.ljyh.mei.data.repository.AccountLibrarySource
import com.ljyh.mei.data.repository.PlaylistPageSource
import com.ljyh.mei.data.repository.PlaylistRepository
import com.ljyh.mei.di.dao.PlaylistDao
import com.ljyh.mei.di.repository.LocalPlaylistRepository
import com.ljyh.mei.data.session.SessionStore
import com.ljyh.mei.data.session.SessionIdentity
import com.ljyh.mei.data.session.SessionStamp
import java.lang.reflect.Proxy
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
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistSessionTest {
    private var identity = SessionIdentity(1, true, false)
    private val sessions = SessionStore().apply { bind { identity } }
    private val source = Source()
    private val unexpected = mutableListOf<String>()
    private val touches = mutableListOf<Triple<String, String, () -> Unit>>()
    private inline fun <reified T> unused(): T = Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
        if (method.name == "touchAccountPlaylist") {
            @Suppress("UNCHECKED_CAST")
            touches += Triple(args[0] as String, args[1] as String, args[3] as () -> Unit)
            touches.last().third()
            Unit
        } else {
            unexpected += method.name
            error("Unused dependency")
        }
    } as T

    private class Source : PlaylistPageSource {
        val dailyReads = mutableListOf<SessionStamp>()
        var daily: suspend () -> Resource<com.ljyh.mei.data.model.weapi.EveryDaySongs> = { Resource.Success(dailySongs(1)) }
        override suspend fun getEveryDayRecommendSongs(session: SessionStamp): Resource<com.ljyh.mei.data.model.weapi.EveryDaySongs> {
            dailyReads += session
            return daily()
        }
        val reads = mutableListOf<Pair<String, SessionStamp?>>()
        val writes = mutableListOf<Pair<String, Boolean>>()
        var read: suspend (String) -> Resource<PlaylistDetail> = { Resource.Success(detail(it)) }
        var write: suspend () -> Resource<BaseResponse> = { Resource.Error("Rejected") }
        override suspend fun getPlaylistDetail(id: String, session: SessionStamp?): Resource<PlaylistDetail> {
            reads += id to session
            return read(id)
        }
        override suspend fun getPlaylistTrackDetails(ids: List<String>, session: SessionStamp?) = ids.map { playlistTrack(it.toInt()) }
        override suspend fun subscribePlaylist(id: String, session: SessionStamp?): Resource<BaseResponse> {
            writes += id to true
            return write()
        }
        override suspend fun unSubscribePlaylist(id: String, session: SessionStamp?): Resource<BaseResponse> {
            writes += id to false
            return write()
        }
    }

    @Test fun dailyRecommendationsCaptureOwnerAndRejectObsoletePlaybackSnapshots() = checkModel { model, _ ->
        model.getEveryDayRecommendSongs()
        runCurrent()
        val owner = model.dailySession.value
        val result = model.everyDay.value
        assertEquals(listOf(sessions.snapshot()), source.dailyReads)
        assertEquals(listOf(1L), model.dailyTracks(owner, result).map { it.id })
        assertNull(model.dailyTracks(owner, result).single().tns)
        source.daily = { Resource.Success(dailySongs(2)) }
        model.getEveryDayRecommendSongs()
        assertTrue(model.dailyTracks(owner, result).isEmpty())
        runCurrent()
        assertEquals(listOf(2L), model.dailyTracks(model.dailySession.value, model.everyDay.value).map { it.id })
    }

    @Test fun emptyDailyRecommendationsAndFailedReadsCanBeRetriedWithoutAnEmptyQueue() = checkModel { model, _ ->
        source.daily = { Resource.Error("Offline") }
        model.getEveryDayRecommendSongs()
        runCurrent()
        assertTrue(model.everyDay.value is Resource.Error)
        assertTrue(model.dailyTracks(model.dailySession.value, model.everyDay.value).isEmpty())
        source.daily = { Resource.Success(dailySongs()) }
        model.getEveryDayRecommendSongs()
        runCurrent()
        assertTrue(model.everyDay.value is Resource.Success)
        assertTrue(model.dailyTracks(model.dailySession.value, model.everyDay.value).isEmpty())
    }

    @Test fun dailySessionInvalidationClearsImmediatelyAndRejectsLateSongs() = checkModel { model, _ ->
        val late = CompletableDeferred<Resource<com.ljyh.mei.data.model.weapi.EveryDaySongs>>()
        source.daily = { withContext(NonCancellable) { late.await() } }
        model.getEveryDayRecommendSongs()
        runCurrent()
        source.daily = { Resource.Success(dailySongs(2)) }
        sessions.beginTransition().use { identity = SessionIdentity(2, true, false) }
        assertNull(model.dailySession.value)
        assertTrue(model.everyDay.value is Resource.Loading)
        runCurrent()
        late.complete(Resource.Success(dailySongs(1)))
        runCurrent()
        assertEquals(listOf(2L), model.dailyTracks(model.dailySession.value, model.everyDay.value).map { it.id })
        val reads = source.dailyReads.size
        sessions.invalidate()
        runCurrent()
        assertEquals(reads + 1, source.dailyReads.size)
    }

    @Test fun leavingDailyPageRejectsLateSongsAndDoesNotReloadUntilReentered() = checkModel { model, _ ->
        val late = CompletableDeferred<Resource<com.ljyh.mei.data.model.weapi.EveryDaySongs>>()
        source.daily = { withContext(NonCancellable) { late.await() } }
        model.getEveryDayRecommendSongs()
        runCurrent()
        model.stopDailyRecommendations()
        sessions.invalidate()
        runCurrent()
        late.complete(Resource.Success(dailySongs(1)))
        runCurrent()
        assertTrue(model.everyDay.value is Resource.Loading)
        assertEquals(1, source.dailyReads.size)
        source.daily = { Resource.Success(dailySongs(2)) }
        model.getEveryDayRecommendSongs()
        runCurrent()
        assertEquals(listOf(2L), model.dailyTracks(model.dailySession.value, model.everyDay.value).map { it.id })
    }

    @Test fun guestDailyPageNeverRequestsPrivateRecommendations() = checkModel { model, _ ->
        identity = SessionIdentity(0, false, true)
        sessions.invalidate()
        runCurrent()
        model.getEveryDayRecommendSongs()
        runCurrent()
        assertTrue(source.dailyReads.isEmpty())
        assertTrue(model.everyDay.value is Resource.Error)
    }

    private fun checkModel(check: suspend TestScope.(PlaylistViewModel, ViewModelStore) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val api = unused<ApiService>()
            val collections = unused<PlaylistCollectionBackend>()
            val weapi = unused<WeApiService>()
            val remote = PlaylistRepository(api, weapi, collections, sessions)
            val local = LocalPlaylistRepository(unused<PlaylistDao>())
            val model = PlaylistViewModel(source, remote, remote, local, api, sessions,
                object : AccountLibrarySource {
                    override val collectionChanges = emptyFlow<SessionStamp>()
                    override fun playlists(accountId: String) = emptyFlow<List<com.ljyh.mei.data.model.room.AccountPlaylist>>()
                    override suspend fun sync(stamp: SessionStamp): Resource<Unit> {
                        sessions.requireCurrent(stamp)
                        return Resource.Error("Library refresh failed")
                    }
                    override suspend fun albums() = error("Unused albums")
                    override suspend fun photos(accountId: String) = error("Unused photos")
                    override suspend fun likedSongs(playlistId: String, stamp: SessionStamp) = error("Unused liked songs")
                })
            store.put("playlist", model)
            runCurrent()
            check(model, store)
        } finally {
            store.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    @Test fun detailUsesOfficialOwnerInsteadOfPreferencesOrLocalHistory() = checkModel { model, _ ->
        model.getPlaylistDetail("10")
        runCurrent()
        assertEquals("1", model.userId)
        assertEquals(sessions.snapshot(), model.detailSession.value)
        assertEquals(false, model.collected.value)
        assertEquals(listOf("10" to sessions.snapshot()), source.reads)
        assertTrue(unexpected.isEmpty())
    }

    @Test fun accountChangeClearsDetailsSynchronouslyAndReloads() = checkModel { model, _ ->
        model.getPlaylistDetail("10")
        runCurrent()
        val previous = model.playlistDetail.value
        val owner = sessions.snapshot()
        identity = SessionIdentity(2, true, false)
        sessions.invalidate()
        assertNull(model.detailSession.value)
        assertTrue(model.playlistDetail.value is Resource.Loading)
        assertTrue(runCatching { model.requireDetail(owner, previous) }.isFailure)
        runCurrent()
        assertEquals("2", model.userId)
        assertEquals(sessions.snapshot(), source.reads.last().second)
    }

    @Test fun canceledOldPlaylistCannotPublishAfterNavigation() {
        val late = CompletableDeferred<Resource<PlaylistDetail>>()
        source.read = { id -> if (id == "10") withContext(NonCancellable) { late.await() } else Resource.Success(detail(id)) }
        checkModel { model, _ ->
            model.getPlaylistDetail("10")
            runCurrent()
            model.getPlaylistDetail("20")
            runCurrent()
            late.complete(Resource.Success(detail("10")))
            runCurrent()
            assertEquals(20L, (model.playlistDetail.value as Resource.Success).data.playlist.Id)
        }
    }

    @Test fun refreshingSamePlaylistRejectsPreviouslyCapturedDetail() = checkModel { model, _ ->
        model.getPlaylistDetail("10")
        runCurrent()
        val owner = sessions.snapshot()
        val previous = model.playlistDetail.value
        model.requireDetail(owner, previous)
        model.getPlaylistDetail("10")
        assertTrue(runCatching { model.requireDetail(owner, previous) }.isFailure)
        runCurrent()
        assertEquals(owner, model.detailSession.value)
        assertTrue(runCatching { model.requireDetail(owner, previous) }.isFailure)
        model.requireDetail(owner, model.playlistDetail.value)
    }

    @Test fun sameAccountReauthorizationRejectsOldResults() {
        val late = CompletableDeferred<Resource<PlaylistDetail>>()
        var reads = 0
        source.read = { id -> if (reads++ == 0) withContext(NonCancellable) { late.await() } else Resource.Success(detail(id, true)) }
        checkModel { model, _ ->
            model.getPlaylistDetail("10")
            runCurrent()
            sessions.invalidate()
            runCurrent()
            late.complete(Resource.Success(detail("10", false)))
            runCurrent()
            assertEquals(true, model.collected.value)
        }
    }

    @Test fun collectionFailureRollsBackAndNeverDeletesLocalRows() = checkModel { model, _ ->
        source.read = { Resource.Success(detail(it, true)) }
        model.getPlaylistDetail("10")
        runCurrent()
        model.unsubscribePlaylist("10")
        assertEquals(false, model.collected.value)
        runCurrent()
        assertEquals(true, model.collected.value)
        assertTrue(model.unSubscribePlaylist.value is Resource.Error)
        assertTrue(unexpected.isEmpty())
    }

    @Test fun duplicateWritesAreSuppressedAndRefreshWaitsForSettlement() {
        val write = CompletableDeferred<Resource<BaseResponse>>()
        source.write = { write.await() }
        checkModel { model, _ ->
            model.getPlaylistDetail("10")
            runCurrent()
            model.subscribePlaylist("10")
            model.unsubscribePlaylist("10")
            model.getPlaylistDetail("10")
            runCurrent()
            assertEquals(1, source.writes.size)
            assertEquals(1, source.reads.size)
            write.complete(Resource.Error("Rejected"))
            runCurrent()
            assertEquals(2, source.reads.size)
            assertEquals(false, model.collected.value)
        }
    }

    @Test fun guestAndCreatorCannotDispatchCollectionWrites() = checkModel { model, _ ->
        identity = SessionIdentity(0, false, true)
        model.getPlaylistDetail("10")
        runCurrent()
        model.subscribePlaylist("10")
        runCurrent()
        assertTrue(model.subscribePlaylist.value is Resource.Error)
        identity = SessionIdentity(99, true, false)
        sessions.invalidate()
        runCurrent()
        model.subscribePlaylist("10")
        runCurrent()
        assertTrue(source.writes.isEmpty())
    }

    @Test fun lateCollectionCannotRollbackAnotherPageOrAccount() {
        val write = CompletableDeferred<Resource<BaseResponse>>()
        source.write = { withContext(NonCancellable) { write.await() } }
        checkModel { model, _ ->
            model.getPlaylistDetail("10")
            runCurrent()
            model.subscribePlaylist("10")
            runCurrent()
            identity = SessionIdentity(2, true, false)
            sessions.invalidate()
            source.read = { Resource.Success(detail(it, true)) }
            model.getPlaylistDetail("20")
            runCurrent()
            write.complete(Resource.Error("Late rejection"))
            runCurrent()
            assertEquals(true, model.collected.value)
            assertTrue(model.subscribePlaylist.value is Resource.Loading)
            assertTrue(unexpected.isEmpty())
        }
    }

    @Test fun failedRecoveryAndInvalidDetailsExposeRetryableErrors() = checkModel { model, _ ->
        model.getPlaylistDetail("10")
        runCurrent()
        val transition = sessions.beginTransition()
        sessions.setRecoveryRequired(true)
        runCurrent()
        assertTrue(model.playlistDetail.value is Resource.Error)
        source.read = { Resource.Success(detail("20")) }
        sessions.setRecoveryRequired(false)
        transition.close()
        runCurrent()
        assertTrue(model.playlistDetail.value is Resource.Error)
        source.read = { Resource.Success(detail(it)) }
        model.getPlaylistDetail("10")
        runCurrent()
        assertTrue(model.playlistDetail.value is Resource.Success)
    }

    @Test fun clearingModelRejectsLateReads() {
        val late = CompletableDeferred<Resource<PlaylistDetail>>()
        source.read = { withContext(NonCancellable) { late.await() } }
        checkModel { model, store ->
            model.getPlaylistDetail("10")
            runCurrent()
            store.clear()
            late.complete(Resource.Success(detail("10")))
            runCurrent()
            assertTrue(model.playlistDetail.value is Resource.Loading)
        }
    }

    @Test fun accessHistoryCarriesTheCapturedAccountAndRejectsValidationAfterCompletion() = checkModel { model, _ ->
        model.getPlaylistDetail("10")
        runCurrent()
        val touch = touches.single()
        assertEquals("1", touch.first)
        assertEquals("10", touch.second)
        sessions.invalidate()
        assertTrue(runCatching { touch.third() }.isFailure)
    }

    @Test fun acceptedCollectionIsNotRolledBackByLibraryRefreshFailure() = checkModel { model, _ ->
        source.write = { Resource.Success(BaseResponse(200)) }
        model.getPlaylistDetail("10")
        runCurrent()
        model.subscribePlaylist("10")
        runCurrent()
        assertEquals(true, model.collected.value)
        val result = model.subscribePlaylist.value
        assertTrue(result is Resource.Success)
        model.consumeCollectionResult(result, true)
        assertTrue(model.subscribePlaylist.value is Resource.Loading)
        assertEquals(true, model.collected.value)
    }

    companion object {
        fun dailySongs(vararg ids: Int): com.ljyh.mei.data.model.weapi.EveryDaySongs = Gson().fromJson(
            """{"code":200,"data":{"dailySongs":[${ids.joinToString(",") {
                """{"id":$it,"name":"Song $it","al":{"id":1,"name":"Album","picUrl":""},"ar":[],"dt":1000,"tns":[]}"""
            }}]}}""", com.ljyh.mei.data.model.weapi.EveryDaySongs::class.java,
        )
        fun detail(id: String, subscribed: Boolean = false): PlaylistDetail = Gson().fromJson(
            """{"code":200,"playlist":{"id":$id,"name":"Playlist","subscribed":$subscribed,"creator":{"userId":99},"tracks":[],"trackIds":[]}}""",
            PlaylistDetail::class.java,
        )
    }
}
