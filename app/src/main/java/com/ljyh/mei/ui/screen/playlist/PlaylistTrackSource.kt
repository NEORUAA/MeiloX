package com.ljyh.mei.ui.screen.playlist

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.PlaylistDetail
import com.ljyh.mei.data.model.api.GetSongDetails
import com.ljyh.mei.data.model.toMediaMetadata
import com.ljyh.mei.data.network.api.ApiService
import kotlinx.coroutines.CancellationException

class PlaylistTrackSource internal constructor(
    private val loadTracks: suspend (List<String>) -> List<PlaylistDetail.Playlist.Track>,
    firstData: List<PlaylistDetail.Playlist.Track>,
    private val ids: List<String>
) : PagingSource<Int, MediaMetadata>() {
    constructor(
        apiService: ApiService,
        firstData: List<PlaylistDetail.Playlist.Track>,
        ids: List<String>,
    ) : this(
        loadTracks = { requested ->
            val response = apiService.getSongDetail(GetSongDetails(requested.joinToString(",")))
            check(response.code == 200) { "Song details request failed (${response.code})" }
            response.songs
        },
        firstData = firstData,
        ids = ids,
    )
    private val cachedTracks = firstData.associateBy { it.id.toString() }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, MediaMetadata> {
        val pageSize = params.loadSize
        val offset = (params.key ?: 0).coerceIn(0, ids.size)

        return try {
            val end = (offset.toLong() + pageSize).coerceAtMost(ids.size.toLong()).toInt()
            val pageIds = ids.subList(offset, end)
            val tracksById = cachedTracks.toMutableMap()
            val missingIds = pageIds.filterNot(tracksById::containsKey).distinct()
            if (missingIds.isNotEmpty()) {
                loadTracks(missingIds).forEach { tracksById[it.id.toString()] = it }
            }
            // Missing or out-of-order responses must not shift the canonical playlist cursor.
            val mapped = pageIds.mapNotNull(tracksById::get).map { it.toMediaMetadata() }
            LoadResult.Page(
                data = mapped,
                prevKey = null,
                nextKey = end.takeIf { it < ids.size },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }

    override fun getRefreshKey(state: PagingState<Int, MediaMetadata>): Int? = null
}
