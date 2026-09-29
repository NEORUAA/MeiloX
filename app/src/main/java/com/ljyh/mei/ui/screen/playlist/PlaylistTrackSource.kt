package com.ljyh.mei.ui.screen.playlist

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.PlaylistDetail
import com.ljyh.mei.data.model.toMediaMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class PlaylistTrackSource(
    firstData: List<PlaylistDetail.Playlist.Track>,
    ids: List<String>,
    private val loadTracks: suspend (List<String>) -> List<PlaylistDetail.Playlist.Track>,
    private val validate: () -> Unit,
    private val query: String = "",
) : PagingSource<Int, MediaMetadata>() {
    private val ids = ids.distinct()
    private val initialTracks = firstData.associateBy { it.id.toString() }
    private var searchResults: List<MediaMetadata>? = null

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, MediaMetadata> = try {
        validate()
        currentCoroutineContext().ensureActive()
        val offset = params.key ?: 0
        if (query.isNotBlank()) {
            val results = searchResults ?: read(ids, complete = true)
                .filter { it.matchesPlaylistSearch(query) }
                .also { searchResults = it }
            val end = (offset + params.loadSize).coerceAtMost(results.size)
            LoadResult.Page(results.subList(offset.coerceAtMost(end), end), null, end.takeIf { it < results.size })
        } else {
            val end = (offset + params.loadSize).coerceAtMost(ids.size)
            val tracks = read(ids.subList(offset.coerceAtMost(end), end), complete = false)
            // Missing songs do not change the position in the authoritative ID list.
            LoadResult.Page(tracks, null, end.takeIf { it < ids.size })
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        LoadResult.Error(error)
    }

    private suspend fun read(requested: List<String>, complete: Boolean): List<MediaMetadata> {
        val tracks = initialTracks.toMutableMap()
        requested.filterNot(tracks::containsKey).chunked(200).forEach { batch ->
            currentCoroutineContext().ensureActive()
            validate()
            val response = loadTracks(batch)
            currentCoroutineContext().ensureActive()
            validate()
            response.filter { it.id.toString() in batch }.forEach { tracks[it.id.toString()] = it }
        }
        currentCoroutineContext().ensureActive()
        validate()
        if (complete) check(requested.all(tracks::containsKey)) { "Incomplete official playlist search" }
        return requested.mapNotNull(tracks::get).map { it.toMediaMetadata() }
    }

    override fun getRefreshKey(state: PagingState<Int, MediaMetadata>): Int? = null
}
