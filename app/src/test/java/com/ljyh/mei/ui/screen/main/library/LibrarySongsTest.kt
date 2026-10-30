package com.ljyh.mei.ui.screen.main.library

import androidx.paging.PagingDataEvent
import androidx.paging.PagingDataPresenter
import com.google.gson.Gson
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.PlaylistDetail
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibrarySongsTest {
    @Test
    fun fourThousandSongsOnlyLoadTheViewportAndKeepEveryPlaybackId() = runTest {
        val requests = mutableListOf<List<String>>()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val songs = LibrarySongs(
            playlistId = 42, ids = (1L..4_005L).toList(), initialTracks = (1L..60L).map(::track),
            scope = backgroundScope, ioDispatcher = dispatcher,
            loadTracks = { ids -> requests += ids; ids.reversed().map { track(it.toLong()) } },
        )
        val presenter = presenter(dispatcher)
        backgroundScope.launch { songs.pages.collectLatest(presenter::collectFrom) }
        runCurrent()
        assertEquals((1L..60L).toList(), presenter.snapshot().items.map { it.id })
        assertTrue(requests.isEmpty())
        advanceTimeBy(60_000)
        runCurrent()
        assertTrue(requests.isEmpty())

        presenter[59]
        runCurrent()
        assertEquals(listOf((61L..80L).map(Long::toString)), requests)
        assertEquals((1L..80L).toList(), presenter.snapshot().items.map { it.id })
        assertEquals(4_005, songs.ids.size)
        assertEquals(4_005L, songs.ids.last())
    }

    @Test
    fun explicitSearchFindsTheLastSongAndReusesFetchedDetails() = runTest {
        val requests = mutableListOf<List<String>>()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val songs = LibrarySongs(
            playlistId = 42, ids = (1L..4_005L).toList(), initialTracks = (1L..60L).map(::track),
            scope = backgroundScope, ioDispatcher = dispatcher,
            loadTracks = { ids -> requests += ids; ids.map { track(it.toLong()) } },
        )
        val results = presenter(dispatcher)
        val search = backgroundScope.launch { songs.search("Song 4005").collectLatest(results::collectFrom) }
        runCurrent()
        assertTrue(requests.isEmpty())
        advanceTimeBy(300)
        runCurrent()
        assertEquals(listOf(4_005L), results.snapshot().items.map { it.id })
        assertEquals(20, requests.size)
        assertTrue(requests.all { it.size <= 200 })
        search.cancel()

        val browsing = presenter(dispatcher)
        backgroundScope.launch { songs.pages.collectLatest(browsing::collectFrom) }
        runCurrent()
        browsing[59]
        runCurrent()
        assertEquals((1L..80L).toList(), browsing.snapshot().items.map { it.id })
        assertEquals(20, requests.size)

        backgroundScope.launch { songs.search("Song 4004").collectLatest(results::collectFrom) }
        runCurrent()
        advanceTimeBy(300)
        runCurrent()
        assertEquals(listOf(4_004L), results.snapshot().items.map { it.id })
        assertEquals(20, requests.size)
    }

    @Test
    fun cancelledSearchCannotContinueFetchingOrPublishALateBatch() = runTest {
        val response = CompletableDeferred<Unit>()
        val dispatcher = StandardTestDispatcher(testScheduler)
        var requests = 0
        val songs = LibrarySongs(
            playlistId = 42, ids = (1L..4_005L).toList(), initialTracks = (1L..60L).map(::track),
            scope = backgroundScope, ioDispatcher = dispatcher,
            loadTracks = { ids ->
                requests++
                withContext(NonCancellable) { response.await() }
                ids.map { track(it.toLong()) }
            },
        )
        val results = presenter(dispatcher)
        val search = backgroundScope.launch { songs.search("Song 61").collectLatest(results::collectFrom) }
        runCurrent()
        advanceTimeBy(300)
        runCurrent()
        assertEquals(1, requests)
        search.cancel()
        response.complete(Unit)
        runCurrent()
        assertEquals(1, requests)
        assertTrue(results.snapshot().items.isEmpty())
    }

    @Test
    fun replacingAnAccountOrPlaylistCancelsItsPendingPage() = runTest {
        val response = CompletableDeferred<Unit>()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val songs = LibrarySongs(
            playlistId = 42, ids = (1L..4_005L).toList(), initialTracks = (1L..60L).map(::track),
            scope = backgroundScope, ioDispatcher = dispatcher,
            loadTracks = { ids ->
                withContext(NonCancellable) { response.await() }
                ids.map { track(it.toLong()) }
            },
        )
        val presenter = presenter(dispatcher)
        backgroundScope.launch { songs.pages.collectLatest(presenter::collectFrom) }
        runCurrent()
        presenter[59]
        runCurrent()
        songs.close()
        response.complete(Unit)
        runCurrent()
        assertEquals((1L..60L).toList(), presenter.snapshot().items.map { it.id })
    }

    @Test
    fun failedAppendRetainsTheFirstPageAndRetriesTheSameCanonicalIds() = runTest {
        val requests = mutableListOf<List<String>>()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val songs = LibrarySongs(
            playlistId = 42, ids = (1L..4_005L).toList(), initialTracks = (1L..60L).map(::track),
            scope = backgroundScope, ioDispatcher = dispatcher,
            loadTracks = { ids ->
                requests += ids
                if (requests.size == 1) throw IOException("Unavailable")
                ids.reversed().map { track(it.toLong()) }
            },
        )
        val presenter = presenter(dispatcher)
        backgroundScope.launch { songs.pages.collectLatest(presenter::collectFrom) }
        runCurrent()
        presenter[59]
        runCurrent()
        assertEquals((1L..60L).toList(), presenter.snapshot().items.map { it.id })
        presenter.retry()
        runCurrent()
        assertEquals(listOf((61L..80L).map(Long::toString), (61L..80L).map(Long::toString)), requests)
        assertEquals((1L..80L).toList(), presenter.snapshot().items.map { it.id })
    }

    @Test
    fun refreshRetainsBrowseDepthAndDetailsInTheNewPlaylistOrder() = runTest {
        val requests = mutableListOf<List<String>>()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val ids = (1L..4_005L).toList()
        val songs = LibrarySongs(
            playlistId = 42, ids = ids, initialTracks = ids.take(60).map(::track),
            scope = backgroundScope, ioDispatcher = dispatcher,
            loadTracks = { requested -> requests += requested; requested.map { track(it.toLong()) } },
        )
        val browsing = presenter(dispatcher)
        backgroundScope.launch { songs.pages.collectLatest(browsing::collectFrom) }
        runCurrent()
        repeat(7) {
            browsing[browsing.snapshot().size - 1]
            runCurrent()
        }
        assertEquals(200, browsing.snapshot().size)
        songs.close()
        requests.clear()

        val refreshedIds = listOf(200L) + ids.filterNot { it == 100L || it == 200L }
        val refreshed = LibrarySongs(
            playlistId = 42, ids = refreshedIds,
            initialTracks = refreshedIds.take(60).map { track(it, "Fresh $it") },
            scope = backgroundScope, ioDispatcher = dispatcher, previous = songs,
            loadTracks = { requested -> requests += requested; requested.map { track(it.toLong()) } },
        )
        val results = presenter(dispatcher)
        backgroundScope.launch { refreshed.pages.collectLatest(results::collectFrom) }
        runCurrent()
        assertEquals(refreshedIds.take(200), results.snapshot().items.map { it.id })
        assertEquals("Fresh 200", results.snapshot().items.first().title)
        assertEquals("Song 199", results.snapshot().items.first { it.id == 199L }.title)
        assertEquals(listOf(listOf("201")), requests)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(listOf(listOf("201")), requests)
    }

    @Test
    fun refreshKeepsSearchCacheWithoutExpandingBrowseDepthOrSharingAnotherPlaylist() = runTest {
        val requests = mutableListOf<List<String>>()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val ids = (1L..4_005L).toList()
        fun songs(playlistId: Long = 42, previous: LibrarySongs? = null) = LibrarySongs(
            playlistId = playlistId, ids = ids, initialTracks = ids.take(60).map(::track),
            scope = backgroundScope, ioDispatcher = dispatcher, previous = previous,
            loadTracks = { requested -> requests += requested; requested.map { track(it.toLong()) } },
        )
        val original = songs()
        val searchResults = presenter(dispatcher)
        val search = backgroundScope.launch {
            original.search("Song 4005").collectLatest(searchResults::collectFrom)
        }
        advanceTimeBy(300)
        runCurrent()
        assertEquals(listOf(4_005L), searchResults.snapshot().items.map { it.id })
        search.cancel()
        val browsing = presenter(dispatcher)
        backgroundScope.launch { original.pages.collectLatest(browsing::collectFrom) }
        runCurrent()
        browsing[59]
        runCurrent()
        assertEquals(80, browsing.snapshot().size)
        original.close()
        requests.clear()

        val refreshed = songs(previous = original)
        val results = presenter(dispatcher)
        backgroundScope.launch { refreshed.pages.collectLatest(results::collectFrom) }
        runCurrent()
        assertEquals(ids.take(80), results.snapshot().items.map { it.id })
        results[79]
        runCurrent()
        assertEquals(ids.take(100), results.snapshot().items.map { it.id })
        assertTrue(requests.isEmpty())

        val otherPlaylist = songs(playlistId = 43, previous = original)
        val otherResults = presenter(dispatcher)
        backgroundScope.launch { otherPlaylist.pages.collectLatest(otherResults::collectFrom) }
        runCurrent()
        assertEquals(ids.take(60), otherResults.snapshot().items.map { it.id })
        otherResults[59]
        runCurrent()
        assertEquals(listOf((61L..80L).map(Long::toString)), requests)
    }

    @Test
    fun refreshBatchesNewDetailsWhenRestoringADeeplyBrowsedPlaylist() = runTest {
        val requests = mutableListOf<List<String>>()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val songs = LibrarySongs(
            playlistId = 42, ids = (1L..4_005L).toList(), initialTracks = (1L..60L).map(::track),
            scope = backgroundScope, ioDispatcher = dispatcher,
            loadTracks = { ids -> ids.map { track(it.toLong()) } },
        )
        val browsing = presenter(dispatcher)
        backgroundScope.launch { songs.pages.collectLatest(browsing::collectFrom) }
        runCurrent()
        repeat(17) {
            browsing[browsing.snapshot().size - 1]
            runCurrent()
        }
        assertEquals(400, browsing.snapshot().size)
        songs.close()

        val newIds = (1_001L..5_005L).toList()
        val refreshed = LibrarySongs(
            playlistId = 42, ids = newIds, initialTracks = newIds.take(60).map(::track),
            scope = backgroundScope, ioDispatcher = dispatcher, previous = songs,
            loadTracks = { ids -> requests += ids; ids.map { track(it.toLong()) } },
        )
        val results = presenter(dispatcher)
        backgroundScope.launch { refreshed.pages.collectLatest(results::collectFrom) }
        runCurrent()
        assertEquals(newIds.take(400), results.snapshot().items.map { it.id })
        assertEquals(listOf(200, 140), requests.map { it.size })
    }

    private fun presenter(dispatcher: TestDispatcher) =
        object : PagingDataPresenter<MediaMetadata>(dispatcher) {
            override suspend fun presentPagingDataEvent(event: PagingDataEvent<MediaMetadata>) = Unit
        }

    private fun track(id: Long) = track(id, "Song $id")

    private fun track(id: Long, name: String): PlaylistDetail.Playlist.Track = Gson().fromJson(
        """{"id":$id,"name":"$name","dt":180000,"ar":[],"al":{"id":1,"name":"Album","picUrl":""}}""",
        PlaylistDetail.Playlist.Track::class.java,
    )
}
