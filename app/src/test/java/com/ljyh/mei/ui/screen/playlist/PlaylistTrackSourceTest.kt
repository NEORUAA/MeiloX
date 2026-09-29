package com.ljyh.mei.ui.screen.playlist

import androidx.paging.PagingSource
import com.google.gson.Gson
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.PlaylistDetail
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

internal fun playlistTrack(id: Int): PlaylistDetail.Playlist.Track = Gson().fromJson(
    """{"id":$id,"name":"Track $id","ar":[{"id":1,"name":"Artist"}],"al":{"id":2,"name":"Album","picUrl":""},"dt":1000}""",
    PlaylistDetail.Playlist.Track::class.java,
)

class PlaylistTrackSourceTest {
    private fun params(offset: Int? = null, size: Int = 2): PagingSource.LoadParams<Int> =
        if (offset == null) PagingSource.LoadParams.Refresh(null, size, false)
        else PagingSource.LoadParams.Append(offset, size, false)
    private fun page(result: PagingSource.LoadResult<Int, MediaMetadata>) = result as PagingSource.LoadResult.Page

    @Test fun alignsSparseInitialTracksByIdAndAdvancesByRequestedIds() = runBlocking {
        val requests = mutableListOf<List<String>>()
        val source = PlaylistTrackSource(listOf(playlistTrack(4), playlistTrack(2)), listOf("1", "2", "3", "4"), {
            requests += it
            emptyList()
        }, {})
        val first = page(source.load(params()))
        assertEquals(listOf(2L), first.data.map { it.id })
        assertEquals(2, first.nextKey)
        val second = page(source.load(params(first.nextKey)))
        assertEquals(listOf(4L), second.data.map { it.id })
        assertNull(second.nextKey)
        assertEquals(listOf(listOf("1"), listOf("3")), requests)
    }

    @Test fun emptyPageDoesNotSkipRemainingIds() = runBlocking {
        val source = PlaylistTrackSource(emptyList(), listOf("1", "2", "3"), { ids ->
            ids.filter { it == "3" }.map { playlistTrack(it.toInt()) }
        }, {})
        val first = page(source.load(params()))
        assertTrue(first.data.isEmpty())
        assertEquals(2, first.nextKey)
        assertEquals(listOf(3L), page(source.load(params(first.nextKey))).data.map { it.id })
    }

    @Test fun reordersResponsesAndDeduplicatesIdsWithoutAcceptingExtraSongs() = runBlocking {
        val source = PlaylistTrackSource(emptyList(), listOf("1", "1", "2"), {
            listOf(playlistTrack(2), playlistTrack(9), playlistTrack(1), playlistTrack(1))
        }, {})
        val result = page(source.load(params()))
        assertEquals(listOf(1L, 2L), result.data.map { it.id })
        assertNull(result.nextKey)
    }

    @Test fun searchLoadsBeyondInitialTracksInBoundedBatches() = runBlocking {
        val requests = mutableListOf<List<String>>()
        val source = PlaylistTrackSource(listOf(playlistTrack(1)), (1..405).map(Int::toString), {
            requests += it
            it.reversed().map { id -> playlistTrack(id.toInt()) }
        }, {}, "Track 405")
        assertEquals(listOf(405L), page(source.load(params())).data.map { it.id })
        assertEquals(listOf(200, 200, 4), requests.map { it.size })
        source.load(params())
        assertEquals(3, requests.size)
    }

    @Test fun partialSearchIsAnErrorAndRetryDoesNotReuseAnIncompleteSnapshot() = runBlocking {
        var complete = false
        val source = PlaylistTrackSource(emptyList(), listOf("1", "2"), {
            if (complete) listOf(playlistTrack(1), playlistTrack(2)) else listOf(playlistTrack(1))
        }, {}, "Track")
        assertTrue(source.load(params()) is PagingSource.LoadResult.Error)
        complete = true
        assertEquals(2, page(source.load(params())).data.size)
    }

    @Test fun businessFailureRetainsRetryAtTheSameCursor() = runBlocking {
        var fail = true
        val source = PlaylistTrackSource(emptyList(), listOf("1", "2", "3"), {
            if (fail) error("Official failure")
            it.map { id -> playlistTrack(id.toInt()) }
        }, {})
        assertTrue(source.load(params(2)) is PagingSource.LoadResult.Error)
        fail = false
        assertEquals(listOf(3L), page(source.load(params(2))).data.map { it.id })
    }

    @Test fun changedOwnerRejectsLateResults() = runBlocking {
        var valid = true
        val source = PlaylistTrackSource(emptyList(), listOf("1"), {
            valid = false
            listOf(playlistTrack(1))
        }, { check(valid) })
        assertTrue(source.load(params()) is PagingSource.LoadResult.Error)
    }

    @Test fun canceledLoadsRemainCanceled() = runBlocking {
        val source = PlaylistTrackSource(emptyList(), listOf("1"), { throw CancellationException() }, {})
        assertTrue(runCatching { source.load(params()) }.exceptionOrNull() is CancellationException)
    }

    @Test fun initialOnlyAndEmptyPlaylistsDoNotDispatch() = runBlocking {
        val populated = PlaylistTrackSource(listOf(playlistTrack(1)), listOf("1"), { error("Unexpected request") }, {})
        assertEquals(1, page(populated.load(params())).data.size)
        val empty = PlaylistTrackSource(emptyList(), emptyList(), { error("Unexpected request") }, {})
        assertTrue(page(empty.load(params())).data.isEmpty())
    }
}
