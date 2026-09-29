package com.ljyh.mei.ui.screen.artist

import androidx.lifecycle.ViewModelStore
import com.ljyh.mei.data.model.api.AllArtistSongs
import com.ljyh.mei.data.model.api.ArtistAlbum
import com.ljyh.mei.data.model.api.ArtistDetail
import com.ljyh.mei.data.model.api.ArtistSong
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.data.repository.ArtistSource
import com.ljyh.mei.data.repository.artistAlbums
import com.ljyh.mei.data.repository.artistDetail
import com.ljyh.mei.data.repository.artistHotSongs
import com.ljyh.mei.data.repository.artistTrack
import com.ljyh.mei.parasite.HostSessionBridge
import com.ljyh.mei.parasite.HostSessionIdentity
import com.ljyh.mei.parasite.HostSessionStamp
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
class ArtistSessionTest {
    private var identity = HostSessionIdentity(1, true, false)
    private val sessions = HostSessionBridge().apply { bind { identity } }
    private val source = Source()
    private class Source : ArtistSource {
        val reads = mutableListOf<Triple<String, String, HostSessionStamp>>()
        val pages = mutableListOf<Triple<String, Int, HostSessionStamp>>()
        val writes = mutableListOf<Triple<String, Boolean, HostSessionStamp>>()
        var detail: suspend (String) -> Resource<ArtistDetail> = { Resource.Success(artistDetail(it)) }
        var collection: suspend (String) -> Resource<Boolean> = { Resource.Success(false) }
        var update: suspend () -> Resource<Unit> = { Resource.Success(Unit) }
        var page: suspend (String, Int) -> Resource<AllArtistSongs> = { _, offset ->
            Resource.Success(AllArtistSongs(200, listOf(artistTrack(offset + 100L)), offset == 0))
        }
        override suspend fun detail(id: String, owner: HostSessionStamp): Resource<ArtistDetail> {
            reads += Triple("detail", id, owner)
            return detail(id)
        }
        override suspend fun albums(id: String, owner: HostSessionStamp): Resource<ArtistAlbum> {
            reads += Triple("albums", id, owner)
            return Resource.Success(artistAlbums(id))
        }
        override suspend fun hotSongs(id: String, owner: HostSessionStamp): Resource<ArtistSong> {
            reads += Triple("hot", id, owner)
            return Resource.Success(artistHotSongs(id))
        }
        override suspend fun followed(id: String, owner: HostSessionStamp): Resource<Boolean> {
            reads += Triple("followed", id, owner)
            return collection(id)
        }
        override suspend fun follow(id: String, followed: Boolean, owner: HostSessionStamp): Resource<Unit> {
            writes += Triple(id, followed, owner)
            return update()
        }
        override suspend fun songs(id: String, offset: Int, owner: HostSessionStamp): Resource<AllArtistSongs> {
            pages += Triple(id, offset, owner)
            return page(id, offset)
        }
    }

    private fun checkModels(block: suspend TestScope.(ArtistViewModel, ArtistSongsViewModel, ViewModelStore) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val artist = ArtistViewModel(source, sessions)
            val songs = ArtistSongsViewModel(source, sessions)
            store.put("artist", artist)
            store.put("songs", songs)
            runCurrent()
            block(artist, songs, store)
        } finally {
            store.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    @Test fun headerAndCollectionReadTheSameOwnerWithoutUsingUserFollowed() = checkModels { artist, _, _ ->
        artist.load("10")
        runCurrent()
        assertTrue(artist.state.value.detail is Resource.Success)
        assertEquals(false, artist.state.value.followed)
        assertEquals(4, source.reads.size)
        assertTrue(source.reads.all { it.third == sessions.snapshot() })
    }

    @Test fun switchingArtistsRejectsLateNonCooperativeHeadersAndOldActions() = checkModels { artist, _, _ ->
        val pending = CompletableDeferred<Resource<ArtistDetail>>()
        source.detail = { if (it == "10") withContext(NonCancellable) { pending.await() } else Resource.Success(artistDetail(it)) }
        artist.load("10")
        runCurrent()
        val stale = artist.state.value
        artist.load("20")
        runCurrent()
        pending.complete(Resource.Success(artistDetail("10")))
        runCurrent()
        assertEquals("20", artist.state.value.id)
        assertEquals(20, (artist.state.value.detail as Resource.Success).data.data!!.artist!!.id)
        artist.toggleFollow(stale)
        var navigated = false
        artist.withCurrent(stale) { navigated = true }
        runCurrent()
        assertFalse(navigated)
        assertTrue(source.writes.isEmpty())
    }

    @Test fun failedSectionsDoNotDiscardOtherSections() = checkModels { artist, _, _ ->
        source.detail = { Resource.Error("Offline") }
        artist.load("10")
        runCurrent()
        assertTrue(artist.state.value.detail is Resource.Error)
        assertTrue(artist.state.value.albums is Resource.Success)
        assertTrue(artist.state.value.songs is Resource.Success)
        source.detail = { Resource.Success(artistDetail(it)) }
        artist.load("10")
        runCurrent()
        assertTrue(artist.state.value.detail is Resource.Success)
    }

    @Test fun unknownFollowStateRetriesReadWithoutWriting() = checkModels { artist, _, _ ->
        source.collection = { Resource.Error("Offline") }
        artist.load("10")
        runCurrent()
        assertNull(artist.state.value.followed)
        source.collection = { Resource.Success(true) }
        artist.toggleFollow(artist.state.value)
        runCurrent()
        assertEquals(true, artist.state.value.followed)
        assertTrue(source.writes.isEmpty())
    }

    @Test fun guestsCannotWriteCollections() = checkModels { artist, _, _ ->
        identity = HostSessionIdentity(0, false, true)
        sessions.invalidate()
        artist.load("10")
        runCurrent()
        artist.toggleFollow(artist.state.value)
        runCurrent()
        assertTrue(artist.state.value.mutation is Resource.Error)
        assertTrue(source.writes.isEmpty())
    }

    @Test fun pendingMutationIsSerializedAndRefreshWaitsForIt() = checkModels { artist, _, _ ->
        val pending = CompletableDeferred<Resource<Unit>>()
        source.update = { pending.await() }
        artist.load("10")
        runCurrent()
        val before = artist.state.value
        artist.toggleFollow(before)
        artist.toggleFollow(before)
        artist.load("10")
        runCurrent()
        assertEquals(false, artist.state.value.followed)
        assertEquals(1, source.writes.size)
        assertEquals(4, source.reads.size)
        source.collection = { Resource.Success(true) }
        pending.complete(Resource.Success(Unit))
        runCurrent()
        assertEquals(true, artist.state.value.followed)
        assertEquals(8, source.reads.size)
    }

    @Test fun failedMutationDoesNotFlipAndNextClickOnlyReconciles() = checkModels { artist, _, _ ->
        source.update = { Resource.Error("Unknown outcome") }
        artist.load("10")
        runCurrent()
        artist.toggleFollow(artist.state.value)
        runCurrent()
        assertNull(artist.state.value.followed)
        assertTrue(artist.state.value.mutation is Resource.Error)
        artist.toggleFollow(artist.state.value)
        runCurrent()
        assertEquals(false, artist.state.value.followed)
        assertEquals(1, source.writes.size)
    }

    @Test fun oldMutationCannotFollowAnotherArtist() = checkModels { artist, _, _ ->
        val pending = CompletableDeferred<Resource<Unit>>()
        source.update = { withContext(NonCancellable) { pending.await() } }
        artist.load("10")
        runCurrent()
        artist.toggleFollow(artist.state.value)
        runCurrent()
        artist.load("20")
        runCurrent()
        pending.complete(Resource.Success(Unit))
        runCurrent()
        assertEquals("20", artist.state.value.id)
        assertEquals(false, artist.state.value.followed)
        assertNull(artist.state.value.mutation)
    }

    @Test fun overlappingPagesPreserveOrderAndAdvanceByRawRows() = checkModels { _, songs, _ ->
        source.page = { _, offset -> Resource.Success(AllArtistSongs(200,
            (if (offset == 0) listOf(100L, 101L) else listOf(101L, 102L)).map(::artistTrack), offset == 0)) }
        songs.open("10")
        runCurrent()
        songs.loadMore("10")
        songs.loadMore("10")
        runCurrent()
        assertEquals(listOf(100L, 101L, 102L), songs.state.value.songs.map { it.id })
        assertEquals(4, songs.state.value.offset)
        assertFalse(songs.state.value.hasMore)
        assertEquals(listOf(0, 2), source.pages.map { it.second })
        songs.loadMore("10")
        assertEquals(2, source.pages.size)
    }

    @Test fun appendErrorsKeepTheCursorAndExplicitRetryUsesIt() = checkModels { _, songs, _ ->
        songs.open("10")
        runCurrent()
        source.page = { _, _ -> Resource.Error("Offline") }
        songs.loadMore("10")
        runCurrent()
        assertEquals(1, songs.state.value.offset)
        assertEquals(listOf(100L), songs.state.value.songs.map { it.id })
        assertNotNull(songs.state.value.error)
        source.page = { _, _ -> Resource.Success(AllArtistSongs(200, listOf(artistTrack(101)), false)) }
        songs.loadMore("10")
        runCurrent()
        assertEquals(listOf(0, 1, 1), source.pages.map { it.second })
        assertEquals(listOf(100L, 101L), songs.state.value.songs.map { it.id })
    }

    @Test fun nonAdvancingPagesFailRatherThanEndingOrSpinning() = checkModels { _, songs, _ ->
        songs.open("10")
        runCurrent()
        for (rows in listOf(emptyList(), listOf(artistTrack(100)))) {
            source.page = { _, _ -> Resource.Success(AllArtistSongs(200, rows, true)) }
            songs.loadMore("10")
            runCurrent()
            assertNotNull(songs.state.value.error)
            assertEquals(1, songs.state.value.offset)
            assertTrue(songs.state.value.hasMore)
        }
    }

    @Test fun emptyFinalPageIsValid() = checkModels { _, songs, _ ->
        source.page = { _, _ -> Resource.Success(AllArtistSongs(200, emptyList(), false)) }
        songs.open("10")
        runCurrent()
        assertTrue(songs.state.value.songs.isEmpty())
        assertFalse(songs.state.value.hasMore)
        assertNull(songs.state.value.error)
    }

    @Test fun artistReplacementCancelsOldPagesAndRejectsOldQueueActions() = checkModels { _, songs, _ ->
        val pending = CompletableDeferred<Resource<AllArtistSongs>>()
        source.page = { id, _ -> if (id == "10") withContext(NonCancellable) { pending.await() }
            else Resource.Success(AllArtistSongs(200, listOf(artistTrack(200)), false)) }
        songs.open("10")
        runCurrent()
        val stale = songs.state.value
        songs.open("20")
        runCurrent()
        pending.complete(Resource.Success(AllArtistSongs(200, listOf(artistTrack(100)), false)))
        runCurrent()
        assertEquals(listOf(200L), songs.state.value.songs.map { it.id })
        var played = false
        songs.withCurrent(stale) { played = true }
        assertFalse(played)
    }

    @Test fun accountChangesAndSameAccountReauthorizationClearBothPagesImmediately() = checkModels { artist, songs, _ ->
        artist.load("10")
        songs.open("10")
        runCurrent()
        repeat(2) { index ->
            val stale = artist.state.value
            if (index == 0) identity = HostSessionIdentity(2, true, false)
            sessions.invalidate()
            assertNull(artist.state.value.session)
            assertNull(artist.state.value.followed)
            assertTrue(songs.state.value.songs.isEmpty())
            artist.toggleFollow(stale)
            runCurrent()
            assertEquals(sessions.snapshot(), artist.state.value.session)
            assertEquals(sessions.snapshot(), songs.state.value.session)
            assertEquals(0, source.pages.last().second)
        }
        assertTrue(source.writes.isEmpty())
    }

    @Test fun recoveryStopsAndThenReloadsTheRequestedArtist() = checkModels { artist, songs, _ ->
        artist.load("10")
        songs.open("10")
        runCurrent()
        sessions.setRecoveryRequired(true)
        var navigated = false
        artist.withCurrent(artist.state.value) { navigated = true }
        songs.withCurrent(songs.state.value) { navigated = true }
        assertFalse(navigated)
        runCurrent()
        assertNull(artist.state.value.session)
        assertTrue(songs.state.value.songs.isEmpty())
        assertNotNull(songs.state.value.error)
        sessions.setRecoveryRequired(false)
        runCurrent()
        assertTrue(artist.state.value.detail is Resource.Success)
        assertEquals(listOf(100L), songs.state.value.songs.map { it.id })
    }
}
