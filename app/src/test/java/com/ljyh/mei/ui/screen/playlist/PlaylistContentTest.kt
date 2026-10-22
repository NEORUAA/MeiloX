package com.ljyh.mei.ui.screen.playlist

import androidx.paging.PagingSource
import com.google.gson.Gson
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.PlaylistDetail
import com.ljyh.mei.data.model.PlaylistOrderChangedException
import com.ljyh.mei.data.model.toMediaMetadata
import com.ljyh.mei.data.model.validatePlaylistTrackOrder
import com.ljyh.mei.data.model.api.UpdatePlaylistTrackOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PlaylistContentTest {
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
