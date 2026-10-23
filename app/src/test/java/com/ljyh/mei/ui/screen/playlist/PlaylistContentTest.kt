package com.ljyh.mei.ui.screen.playlist

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingDataEvent
import androidx.paging.PagingDataPresenter
import androidx.paging.PagingSource
import androidx.paging.cachedIn
import com.google.gson.Gson
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.PlaylistDetail
import com.ljyh.mei.data.model.PlaylistOrderChangedException
import com.ljyh.mei.data.model.toMediaMetadata
import com.ljyh.mei.data.model.validatePlaylistTrackOrder
import com.ljyh.mei.data.model.api.UpdatePlaylistTrackOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistContentTest {
    @Test
    fun removingTracksRetainsLoadedPagesAndFiltersLaterAppendsWithoutRestartingThePager() = runTest {
        var sourceCount = 0
        val requests = mutableListOf<List<String>>()
        val removedIds = MutableStateFlow(emptySet<Long>())
        val snapshots = mutableListOf<List<Long>>()
        val presenter = object : PagingDataPresenter<MediaMetadata>(StandardTestDispatcher(testScheduler)) {
            override suspend fun presentPagingDataEvent(event: PagingDataEvent<MediaMetadata>) {
                snapshots += snapshot().items.map { it.id }
            }
        }
        val tracks = Pager(PagingConfig(pageSize = 20, initialLoadSize = 20, enablePlaceholders = false)) {
            sourceCount++
            PlaylistTrackSource(
                loadTracks = { ids -> requests += ids; ids.map { track(it.toLong()) } },
                firstData = emptyList(),
                ids = (1..120).map(Int::toString),
            )
        }.flow.cachedIn(backgroundScope).excludeRemovedPlaylistTracks(removedIds)
        backgroundScope.launch { tracks.collectLatest(presenter::collectFrom) }
        runCurrent()
        repeat(3) {
            presenter[presenter.size - 1]
            runCurrent()
        }
        val loadedIds = presenter.snapshot().items.map { it.id }
        assertEquals((1L..80L).toList(), loadedIds)
        val requestsBeforeRemoval = requests.toList()

        removedIds.value = setOf(60L, 90L)
        runCurrent()
        removedIds.value += 5L
        runCurrent()

        assertEquals(loadedIds.filterNot { it in removedIds.value }, presenter.snapshot().items.map { it.id })
        assertEquals(requestsBeforeRemoval, requests)
        assertEquals(1, sourceCount)
        assertTrue(snapshots.all { it.isNotEmpty() })

        presenter[presenter.size - 1]
        runCurrent()
        assertEquals((1L..100L).filterNot { it in removedIds.value }, presenter.snapshot().items.map { it.id })
        assertEquals(1, sourceCount)
    }

    @Test
    fun removingSearchResultsReusesTheCompletedSearchIncludingWhenTheLastResultIsDeleted() = runTest {
        var searches = 0
        val removedIds = MutableStateFlow(setOf(1L))
        val presenter = object : PagingDataPresenter<MediaMetadata>(StandardTestDispatcher(testScheduler)) {
            override suspend fun presentPagingDataEvent(event: PagingDataEvent<MediaMetadata>) = Unit
        }
        val tracks = flow {
            searches++
            emit(PagingData.from((1L..3L).map { track(it).toMediaMetadata() }))
        }.cachedIn(backgroundScope).excludeRemovedPlaylistTracks(removedIds)
        backgroundScope.launch { tracks.collectLatest(presenter::collectFrom) }
        runCurrent()
        assertEquals(listOf(2L, 3L), presenter.snapshot().items.map { it.id })

        removedIds.value += 2L
        runCurrent()
        assertEquals(listOf(3L), presenter.snapshot().items.map { it.id })
        removedIds.value += 3L
        runCurrent()
        assertTrue(presenter.snapshot().items.isEmpty())
        assertEquals(1, searches)
    }

    @Test
    fun orderEditorPreservesUnavailableTracksAndCanonicalPositions() {
        val items = playlistOrderItems(listOf(3, 1, 2), listOf(track(2).toMediaMetadata(), track(3).toMediaMetadata()))
        assertEquals(listOf(3L, 1L, 2L), items.map { it.id })
        assertNull(items[1].track)
        val moved = items.toMutableList().apply { add(0, removeAt(1)) }
        validatePlaylistTrackOrder(items.map { it.id }, moved.map { it.id }, items.map { it.id })
        assertEquals(listOf(1L, 3L, 2L), moved.map { it.id })
        assertNull(moved.first().track)
    }

    @Test
    fun orderUpdatesRejectDroppedDuplicatedAndConcurrentlyChangedIds() {
        val original = listOf(1L, 2L, 3L)
        for (invalid in listOf(listOf(2L, 1L), listOf(2L, 1L, 1L), listOf(2L, 1L, 4L))) {
            assertTrue(runCatching { validatePlaylistTrackOrder(original, invalid, original) }.exceptionOrNull() is IllegalArgumentException)
        }
        assertTrue(runCatching {
            validatePlaylistTrackOrder(original, original.reversed(), original + 4L)
        }.exceptionOrNull() is PlaylistOrderChangedException)
        assertTrue(runCatching {
            validatePlaylistTrackOrder(original, original.reversed(), listOf(2L, 1L, 3L))
        }.exceptionOrNull() is PlaylistOrderChangedException)
    }

    @Test
    fun orderRequestIncludesEveryOrderedIdAsAString() {
        val request = UpdatePlaylistTrackOrder("42", listOf(3, 1, 2))
        val payload = Gson().toJsonTree(request).asJsonObject
        assertEquals("42", payload["pid"].asString)
        assertEquals("update", payload["op"].asString)
        assertEquals("[\"3\",\"1\",\"2\"]", payload["trackIds"].asString)
    }

    @Test
    fun cachedAndFetchedTracksFollowCanonicalIdsWithoutRefetchingCachedRows() = runBlocking {
        val requests = mutableListOf<List<String>>()
        val source = PlaylistTrackSource(
            loadTracks = { ids -> requests += ids; listOf(track(4), track(2)) },
            firstData = listOf(track(3), track(1)),
            ids = listOf("1", "2", "3", "4"),
        )
        val page = source.page(0, 3)
        assertEquals(listOf(1L, 2L, 3L), page.data.map { it.id })
        assertEquals(listOf(listOf("2")), requests)
        assertEquals(3, page.nextKey)
        assertEquals(listOf(4L), source.page(3, 3).data.map { it.id })
    }

    @Test
    fun missingTracksAndEntireMissingPagesStillAdvanceByRequestedIds() = runBlocking {
        val source = PlaylistTrackSource(
            loadTracks = { ids -> ids.filter { it == "2" || it == "5" }.map { track(it.toLong()) } },
            firstData = emptyList(), ids = (1..6).map(Int::toString),
        )
        val first = source.page(0, 2)
        assertEquals(listOf(2L), first.data.map { it.id })
        assertEquals(2, first.nextKey)
        val empty = source.page(first.nextKey!!, 2)
        assertTrue(empty.data.isEmpty())
        assertEquals(4, empty.nextKey)
        val last = source.page(empty.nextKey!!, 2)
        assertEquals(listOf(5L), last.data.map { it.id })
        assertNull(last.nextKey)
    }

    @Test
    fun cancellationPropagatesInsteadOfBecomingARetryablePage() = runBlocking {
        val source = PlaylistTrackSource(
            loadTracks = { throw CancellationException("cancelled") },
            firstData = emptyList(), ids = listOf("1"),
        )
        try {
            source.page(0, 1)
            fail("Expected cancellation")
        } catch (_: CancellationException) {
            // Expected: leaving the screen cancels the original request.
        }
    }

    private suspend fun PlaylistTrackSource.page(offset: Int, size: Int) =
        load(PagingSource.LoadParams.Refresh(offset, size, false)) as PagingSource.LoadResult.Page<Int, MediaMetadata>

    private fun track(id: Long): PlaylistDetail.Playlist.Track = Gson().fromJson(
        """{"id":$id,"name":"Song $id","dt":180000,"ar":[],"al":{"id":1,"name":"Album","picUrl":""}}""",
        PlaylistDetail.Playlist.Track::class.java,
    )
}
