package com.ljyh.mei.ui.screen.main.library

import androidx.lifecycle.ViewModelStore
import com.ljyh.mei.data.model.AlbumPhoto
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.UserAlbumList
import com.ljyh.mei.data.model.melox.AccountProfile
import com.ljyh.mei.data.model.room.AccountPlaylist
import com.ljyh.mei.data.model.room.Playlist
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.data.repository.AccountLibrarySource
import com.ljyh.mei.parasite.HostAccountStore
import com.ljyh.mei.parasite.HostSessionBridge
import com.ljyh.mei.parasite.HostSessionIdentity
import com.ljyh.mei.parasite.HostSessionStamp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {
    private var identity = HostSessionIdentity(1, true, false)
    private val sessions = HostSessionBridge().apply { bind { identity } }
    private val source = FakeSource()

    private fun playlist(id: String, liked: Boolean = false) =
        AccountPlaylist(Playlist(id, "Playlist $id", "", "1", "Creator", "", 1), liked)
    private fun song(id: Long) = MediaMetadata(id, "Song $id", "", emptyList(), 1000, MediaMetadata.Album(1, "Album"))

    private class FakeSource : AccountLibrarySource {
        override val albumChanges = kotlinx.coroutines.flow.MutableSharedFlow<HostSessionStamp>(extraBufferCapacity = 1)
        val cached = mutableMapOf<String, MutableStateFlow<List<AccountPlaylist>>>()
        val syncCalls = mutableListOf<String>()
        var sync: suspend (HostSessionStamp) -> Resource<Unit> = { Resource.Success(Unit) }
        var albums: suspend () -> Resource<UserAlbumList> = { Resource.Success(UserAlbumList(emptyList(), 0, false, 0, 200)) }
        var photos: suspend (String) -> Resource<AlbumPhoto> = {
            Resource.Success(AlbumPhoto(200, AlbumPhoto.Data(AlbumPhoto.Data.Page("", false, 0), emptyList(), 0), ""))
        }
        var liked: suspend (String) -> Resource<List<MediaMetadata>> = { Resource.Success(emptyList()) }
        override fun playlists(accountId: String) = cached.getOrPut(accountId) { MutableStateFlow(emptyList()) }
        override suspend fun sync(stamp: HostSessionStamp): Resource<Unit> {
            syncCalls += stamp.identity.userId.toString()
            return sync.invoke(stamp)
        }
        override suspend fun albums() = albums.invoke()
        override suspend fun photos(accountId: String) = photos.invoke(accountId)
        override suspend fun likedSongs(playlistId: String) = liked.invoke(playlistId)
    }

    @Test fun acceptedAlbumChangesRefreshOnlyTheirCurrentAccountLibrary() {
        var albumRequests = 0
        source.albums = { albumRequests++; Resource.Success(UserAlbumList(emptyList(), 0, false, 0, 200)) }
        checkModel { model, _ ->
            runCurrent()
            val old = sessions.snapshot()
            val before = albumRequests
            source.albumChanges.emit(old)
            runCurrent()
            assertEquals(before + 1, albumRequests)
            sessions.beginTransition().use { identity = HostSessionIdentity(2, true, false) }
            runCurrent()
            val changed = albumRequests
            source.albumChanges.emit(old)
            runCurrent()
            assertEquals(changed, albumRequests)
            assertEquals("2", model.state.value.userId)
        }
    }

    private fun checkModel(check: suspend TestScope.(LibraryViewModel, ViewModelStore) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val accounts = HostAccountStore(sessions, {
            AccountProfile(identity.userId, "Account", null, null, null, null, null, null, null, null)
        }, backgroundScope)
        val store = ViewModelStore()
        try {
            val model = LibraryViewModel(source, accounts)
            store.put("library", model)
            check(model, store)
        } finally {
            store.clear()
            accounts.close()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    @Test fun officialSessionLoadsCachedPlaylistsAndLikedSongsWithoutPreferenceCredentials() {
        source.playlists("1").value = listOf(playlist("liked", true), playlist("collected"))
        source.liked = { Resource.Success(listOf(song(1))) }
        checkModel { model, _ ->
            runCurrent()
            assertEquals("1", model.state.value.userId)
            assertEquals(2, model.state.value.playlists.size)
            assertEquals(listOf(song(1)), model.state.value.likedSongs)
            assertFalse(model.state.value.playlistsLoading)
            assertFalse(model.state.value.likedSongsLoading)
        }
    }

    @Test fun accountChangesClearAllOldPresentationBeforeTheNextCoroutineRuns() {
        source.playlists("1").value = listOf(playlist("old", true))
        source.playlists("2").value = listOf(playlist("new"))
        source.liked = { Resource.Success(listOf(song(1))) }
        checkModel { model, _ ->
            runCurrent()
            sessions.beginTransition().use {
                identity = HostSessionIdentity(2, true, false)
                assertEquals(LibraryUiState(), model.state.value)
                runCurrent()
            }
            runCurrent()
            assertEquals("2", model.state.value.userId)
            assertEquals(listOf("new"), model.state.value.playlists.map { it.playlist.id })
            assertTrue(model.state.value.likedSongs.isEmpty())
        }
    }

    @Test fun logoutNeverSynchronizesAnAnonymousAccount() {
        source.playlists("1").value = listOf(playlist("private"))
        checkModel { model, _ ->
            runCurrent()
            val requests = source.syncCalls.size
            sessions.beginTransition().use { identity = HostSessionIdentity(0, false, true) }
            assertEquals(LibraryUiState(), model.state.value)
            runCurrent()
            assertEquals(LibraryUiState(), model.state.value)
            assertEquals(requests, source.syncCalls.size)
        }
    }

    @Test fun oldNonCooperativeLikedResultCannotReplaceAnotherAccountsSongs() {
        val old = CompletableDeferred<Resource<List<MediaMetadata>>>()
        source.playlists("1").value = listOf(playlist("old", true))
        source.playlists("2").value = listOf(playlist("new", true))
        source.liked = { if (it == "old") withContext(NonCancellable) { old.await() } else Resource.Success(listOf(song(2))) }
        checkModel { model, _ ->
            try {
                runCurrent()
                sessions.beginTransition().use { identity = HostSessionIdentity(2, true, false) }
                runCurrent()
                assertEquals(listOf(song(2)), model.state.value.likedSongs)
            } finally {
                old.complete(Resource.Success(listOf(song(1))))
                runCurrent()
            }
            assertEquals(listOf(song(2)), model.state.value.likedSongs)
        }
    }

    @Test fun sameAccountRefreshRejectsOldAlbumResults() {
        val old = CompletableDeferred<Resource<UserAlbumList>>()
        var loads = 0
        source.albums = {
            if (loads++ == 0) withContext(NonCancellable) { old.await() }
            else Resource.Success(UserAlbumList(emptyList(), 2, false, 0, 200))
        }
        checkModel { model, _ ->
            try {
                runCurrent()
                model.refresh()
                runCurrent()
                assertEquals(2, (model.state.value.albums as Resource.Success).data.count)
            } finally {
                old.complete(Resource.Success(UserAlbumList(emptyList(), 1, false, 0, 200)))
                runCurrent()
            }
            assertEquals(2, (model.state.value.albums as Resource.Success).data.count)
        }
    }

    @Test fun failedSyncRetainsOnlyTheCurrentAccountsCacheAndCanRetry() {
        source.playlists("1").value = listOf(playlist("cached"))
        source.sync = { Resource.Error("Offline") }
        checkModel { model, _ ->
            runCurrent()
            assertEquals("Offline", model.state.value.playlistsError)
            assertEquals(listOf("cached"), model.state.value.playlists.map { it.playlist.id })
            assertFalse(model.state.value.playlistsLoading)
            source.sync = { Resource.Success(Unit) }
            model.refresh()
            runCurrent()
            assertEquals(null, model.state.value.playlistsError)
        }
    }

    @Test fun removingTheLikedMembershipClearsItsSongsDuringRefresh() {
        source.playlists("1").value = listOf(playlist("liked", true))
        source.liked = { Resource.Success(listOf(song(1))) }
        checkModel { model, _ ->
            runCurrent()
            assertEquals(1, model.state.value.likedSongs.size)
            source.playlists("1").value = emptyList()
            model.refresh()
            runCurrent()
            assertTrue(model.state.value.likedSongs.isEmpty())
            assertFalse(model.state.value.likedSongsLoading)
        }
    }

    @Test fun sameAccountReauthorizationReloadsPrivateData() {
        checkModel { model, _ ->
            runCurrent()
            val requests = source.syncCalls.size
            val generation = model.state.value.session!!.generation
            sessions.invalidate()
            assertEquals(LibraryUiState(), model.state.value)
            runCurrent()
            assertTrue(source.syncCalls.size > requests)
            assertTrue(model.state.value.session!!.generation > generation)
        }
    }

    @Test fun clearingTheViewModelDiscardsLatePhotoResults() {
        val pending = CompletableDeferred<Resource<AlbumPhoto>>()
        source.photos = { withContext(NonCancellable) { pending.await() } }
        checkModel { model, store ->
            runCurrent()
            store.clear()
            val before = model.state.value
            pending.complete(Resource.Error("Late failure"))
            runCurrent()
            assertEquals(before, model.state.value)
        }
    }
}
