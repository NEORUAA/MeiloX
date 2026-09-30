package com.ljyh.mei.ui.screen.playlist

import androidx.lifecycle.ViewModelStore
import com.google.gson.Gson
import com.ljyh.mei.data.model.PlaylistDetail
import com.ljyh.mei.data.model.api.BaseMessageResponse
import com.ljyh.mei.data.model.api.CreatePlaylistResult
import com.ljyh.mei.data.model.api.ManipulateTrackResult
import com.ljyh.mei.data.model.room.AccountPlaylist
import com.ljyh.mei.data.model.room.Playlist
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.data.network.api.ApiService
import com.ljyh.mei.data.repository.PlaylistCollectionBackend
import com.ljyh.mei.data.network.api.WeApiService
import com.ljyh.mei.data.repository.AccountLibrarySource
import com.ljyh.mei.data.repository.PlaylistMutationSource
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistActionsTest {
    private var identity = SessionIdentity(1, true, false)
    private val sessions = SessionStore().apply { bind { identity } }
    private val entries = MutableStateFlow(listOf(entry("10", "1"), entry("20", "2")))
    private val reads = mutableListOf<String>()
    private val syncs = mutableListOf<SessionStamp>()
    private val calls = mutableListOf<Pair<String, SessionStamp>>()
    private var sync: suspend () -> Resource<Unit> = { Resource.Success(Unit) }
    private var manipulate: suspend () -> Resource<ManipulateTrackResult> = { Resource.Success(ManipulateTrackResult(200)) }
    private var create: suspend () -> Resource<CreatePlaylistResult> = { Resource.Success(CreatePlaylistResult(200, null, 30)) }
    private var delete: suspend () -> Resource<BaseMessageResponse> = { Resource.Success(BaseMessageResponse(200, "", "", "")) }

    private inline fun <reified T> unused(): T = Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
        if (method.name == "touchAccountPlaylist") Unit else error("Unexpected ${method.name}")
    } as T

    private fun checkModel(check: suspend TestScope.(PlaylistViewModel, ViewModelStore) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val api = unused<ApiService>()
            val repository = PlaylistRepository(api, unused<WeApiService>(), unused<PlaylistCollectionBackend>(), sessions,
                unused<com.ljyh.mei.data.repository.CatalogCollectionBackend>(), unused<com.ljyh.mei.data.repository.PlaylistTracksBackend>(), unused<com.ljyh.mei.playback.DownloadSourceBackend>())
            val source = object : PlaylistMutationSource {
                override suspend fun manipulateTrack(op: String, pid: String, trackIds: String, session: SessionStamp): Resource<ManipulateTrackResult> {
                    calls += "$op:$pid:$trackIds" to session
                    return manipulate()
                }
                override suspend fun createPlaylist(name: String, privacy: Boolean, type: String, session: SessionStamp): Resource<CreatePlaylistResult> {
                    calls += "create:$name:$privacy:$type" to session
                    return create()
                }
                override suspend fun deletePlaylist(id: String, session: SessionStamp): Resource<BaseMessageResponse> {
                    calls += "delete:$id" to session
                    return delete()
                }
            }
            val library = object : AccountLibrarySource {
                override val collectionChanges = emptyFlow<SessionStamp>()
                override fun playlists(accountId: String) = entries.also { reads += accountId }
                override suspend fun sync(stamp: SessionStamp): Resource<Unit> {
                    syncs += stamp
                    return sync()
                }
                override suspend fun albums() = error("Unused albums")
                override suspend fun photos(accountId: String) = error("Unused photos")
                override suspend fun likedSongs(playlistId: String, stamp: SessionStamp) = error("Unused likes")
            }
            val pages = object : com.ljyh.mei.data.repository.PlaylistPageSource by repository {
                override suspend fun getPlaylistDetail(id: String, session: SessionStamp?): Resource<PlaylistDetail> = Resource.Success(
                    Gson().fromJson("""{"code":200,"playlist":{"id":$id,"creator":{"userId":1},"tracks":[],"trackIds":[],"subscribed":false}}""", PlaylistDetail::class.java))
            }
            val model = PlaylistViewModel(pages, source, repository,
                LocalPlaylistRepository(unused<PlaylistDao>()), api, sessions, library)
            store.put("actions", model)
            runCurrent()
            check(model, store)
        } finally {
            store.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    @Test fun pickerUsesAccountMembershipAndLoadsAllCreatedRows() = checkModel { model, _ ->
        entries.value = (1..150).map { entry(it.toString(), "1") } + entry("999", "2")
        model.getAllMePlaylist(sessions.snapshot())
        runCurrent()
        assertEquals(150, model.picker.value.playlists.size)
        assertEquals(listOf("1", "1"), reads)
        assertFalse(model.picker.value.loading)
        assertNull(model.picker.value.error)
    }

    @Test fun failedRefreshKeepsOnlyCurrentAccountCacheAndCanRetry() = checkModel { model, _ ->
        sync = { Resource.Error("Offline") }
        val owner = sessions.snapshot()
        model.getAllMePlaylist(owner)
        runCurrent()
        assertEquals(listOf("10"), model.picker.value.playlists.map { it.id })
        assertEquals("Offline", model.picker.value.error)
        sync = { entries.value = listOf(entry("30", "1")); Resource.Success(Unit) }
        model.getAllMePlaylist(owner)
        runCurrent()
        assertEquals(listOf("30"), model.picker.value.playlists.map { it.id })
        assertNull(model.picker.value.error)
    }

    @Test fun guestCannotReadPickerOrCreate() = checkModel { model, _ ->
        identity = SessionIdentity(0, false, true)
        sessions.invalidate()
        runCurrent()
        val owner = sessions.snapshot()
        model.getAllMePlaylist(owner)
        var created: Boolean? = null
        model.createPlaylist("Test", true, owner) { created = it }
        runCurrent()
        assertTrue(reads.isEmpty())
        assertTrue(calls.isEmpty())
        assertEquals(false, created)
        assertNotNull(model.picker.value.error)
    }

    @Test fun invalidationClearsPickerSynchronouslyAndRejectsLateRefresh() = checkModel { model, _ ->
        val late = CompletableDeferred<Resource<Unit>>()
        sync = { withContext(NonCancellable) { late.await() } }
        model.getAllMePlaylist(sessions.snapshot())
        runCurrent()
        identity = SessionIdentity(2, true, false)
        sessions.invalidate()
        assertTrue(model.picker.value.playlists.isEmpty())
        assertNull(model.actionSession.value)
        runCurrent()
        late.complete(Resource.Success(Unit))
        runCurrent()
        assertTrue(model.picker.value.playlists.isEmpty())
        assertEquals(sessions.snapshot(), model.actionSession.value)
    }

    @Test fun closingPickerRejectsLateLoadAndReopeningStartsFresh() = checkModel { model, _ ->
        val late = CompletableDeferred<Resource<Unit>>()
        sync = { withContext(NonCancellable) { late.await() } }
        val owner = sessions.snapshot()
        model.getAllMePlaylist(owner)
        runCurrent()
        model.stopPlaylistPicker()
        sync = { Resource.Success(Unit) }
        entries.value = listOf(entry("30", "1"))
        model.getAllMePlaylist(owner)
        runCurrent()
        late.complete(Resource.Error("Old error"))
        runCurrent()
        assertEquals(listOf("30"), model.picker.value.playlists.map { it.id })
        assertNull(model.picker.value.error)
    }

    @Test fun selectionCannotDispatchForAnOldOwnerOrAnotherCreatorsPlaylist() = checkModel { model, _ ->
        val owner = sessions.snapshot()
        model.getAllMePlaylist(owner)
        runCurrent()
        var result: PlaylistTrackAddOutcome? = null
        model.addSongToPlaylist("20", "1", owner) { result = it }
        runCurrent()
        assertEquals(PlaylistTrackAddOutcome.Failed, result)
        sessions.invalidate()
        runCurrent()
        model.addSongToPlaylist("10", "1", owner) { fail("Obsolete callback") }
        runCurrent()
        assertTrue(calls.isEmpty())
    }

    @Test fun acceptedAddUsesCapturedSessionAndRefreshesLibraryWithoutCountGuessing() = checkModel { model, _ ->
        val owner = sessions.snapshot()
        model.getAllMePlaylist(owner)
        runCurrent()
        model.stopPlaylistPicker()
        sync = { Resource.Error("Refresh failed") }
        var outcome: PlaylistTrackAddOutcome? = null
        model.addSongToPlaylist("10", "1", owner) { outcome = it }
        runCurrent()
        assertEquals(PlaylistTrackAddOutcome.Added, outcome)
        assertEquals(listOf("add:10:1" to owner), calls)
        assertEquals(listOf(owner, owner), syncs)
    }

    @Test fun duplicateClicksDispatchOnceAndBusinessFailureDoesNotRefreshLibrary() = checkModel { model, _ ->
        val owner = sessions.snapshot()
        model.getAllMePlaylist(owner)
        runCurrent()
        val pending = CompletableDeferred<Resource<ManipulateTrackResult>>()
        manipulate = { pending.await() }
        val outcomes = mutableListOf<PlaylistTrackAddOutcome>()
        repeat(2) { model.addSongToPlaylist("10", "1", owner) { outcomes += it } }
        runCurrent()
        assertEquals(1, calls.size)
        pending.complete(Resource.Success(ManipulateTrackResult(502)))
        runCurrent()
        assertEquals(listOf(PlaylistTrackAddOutcome.AlreadyExists), outcomes)
        assertEquals(1, syncs.size)
    }

    @Test fun lateMutationCannotPublishOrCallBackAfterSameAccountReauthorization() = checkModel { model, _ ->
        val pending = CompletableDeferred<Resource<ManipulateTrackResult>>()
        manipulate = { withContext(NonCancellable) { pending.await() } }
        val owner = sessions.snapshot()
        model.getAllMePlaylist(owner)
        runCurrent()
        model.addSongToPlaylist("10", "1", owner) { fail("Obsolete mutation callback") }
        runCurrent()
        sessions.invalidate()
        runCurrent()
        pending.complete(Resource.Success(ManipulateTrackResult(200)))
        runCurrent()
        assertTrue(model.manipulateTracks.value is Resource.Loading)
        assertEquals(1, syncs.size)
    }

    @Test fun deleteSongRequiresCurrentOwnedDetailsAndDoesNotModifyReplacement() = checkModel { model, _ ->
        val owner = sessions.snapshot()
        model.getPlaylistDetail("10")
        runCurrent()
        val pending = CompletableDeferred<Resource<ManipulateTrackResult>>()
        manipulate = { pending.await() }
        model.deleteSongFromPlaylist("10", "1", owner) { model.markTrackRemoved(1) }
        runCurrent()
        model.getPlaylistDetail("30")
        runCurrent()
        pending.complete(Resource.Success(ManipulateTrackResult(200)))
        runCurrent()
        assertTrue(model.removedTrackIds.value.isEmpty())
        model.deleteSongFromPlaylist("10", "1", owner)
        runCurrent()
        assertEquals(1, calls.size)
    }

    @Test fun failedDeleteCannotRemoveRows() = checkModel { model, _ ->
        model.getPlaylistDetail("10")
        runCurrent()
        manipulate = { Resource.Success(ManipulateTrackResult(500)) }
        var removed = true
        model.deleteSongFromPlaylist("10", "1", sessions.snapshot()) { removed = it }
        runCurrent()
        assertFalse(removed)
        assertTrue(syncs.isEmpty())
    }

    @Test fun createUsesPrivacyAndOwnerAndReportsAcceptanceDespiteRefreshFailure() = checkModel { model, _ ->
        sync = { Resource.Error("Offline") }
        val owner = sessions.snapshot()
        var created = false
        model.createPlaylist("Test", true, owner) { created = it }
        runCurrent()
        assertTrue(created)
        assertEquals(listOf("create:Test:true:NORMAL" to owner), calls)
        assertEquals(listOf(owner), syncs)
    }

    @Test fun deletePlaylistRejectsLikedAndForeignRowsWithoutDeletingLocalData() = checkModel { model, _ ->
        val owner = sessions.snapshot()
        entries.value = listOf(entry("10", "1", true), entry("20", "2"), entry("30", "1"))
        for (id in listOf("10", "20", "99")) {
            model.deletePlaylist(id, owner)
            runCurrent()
        }
        assertTrue(calls.isEmpty())
        delete = { Resource.Error("Rejected") }
        var deleted = true
        model.deletePlaylist("30", owner) { deleted = it }
        runCurrent()
        assertFalse(deleted)
        assertEquals(listOf("delete:30" to owner), calls)
        assertEquals(3, entries.value.size)
        assertTrue(syncs.isEmpty())
    }

    @Test fun clearingModelDropsLateCreationCallback() = checkModel { model, store ->
        val pending = CompletableDeferred<Resource<CreatePlaylistResult>>()
        create = { withContext(NonCancellable) { pending.await() } }
        model.createPlaylist("Test", true, sessions.snapshot()) { fail("Disposed callback") }
        runCurrent()
        store.clear()
        pending.complete(Resource.Success(CreatePlaylistResult(200, null, 30)))
        runCurrent()
        assertTrue(syncs.isEmpty())
    }

    @Test fun addResultUsesOfficialCodesAndSurfacesOfflineIds() {
        assertEquals(PlaylistTrackAddOutcome.Added, Resource.Success(ManipulateTrackResult(200, count = 0)).toPlaylistTrackAddOutcome())
        assertEquals(PlaylistTrackAddOutcome.AlreadyExists, Resource.Success(ManipulateTrackResult(502)).toPlaylistTrackAddOutcome())
        assertEquals(PlaylistTrackAddOutcome.PartiallyAdded, Resource.Success(ManipulateTrackResult(200, offlineIds = listOf(1))).toPlaylistTrackAddOutcome())
        assertEquals(PlaylistTrackAddOutcome.Failed, Resource.Success(ManipulateTrackResult(512)).toPlaylistTrackAddOutcome())
    }

    private fun entry(id: String, author: String, liked: Boolean = false) = AccountPlaylist(
        Playlist(id, "Playlist $id", "", author, "Creator", "", 0), liked)
}
