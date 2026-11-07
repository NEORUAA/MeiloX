package com.ljyh.mei.ui.screen.playlist

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.PlaylistDetail
import com.ljyh.mei.data.model.toMediaMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class PlaylistTrackSource internal constructor(
    private val loadTracks: suspend (List<String>) -> List<PlaylistDetail.Playlist.Track>,
    firstData: List<PlaylistDetail.Playlist.Track>,
    private val ids: List<String>,
    private val onPageLoaded: (Int) -> Unit = {},
) : PagingSource<Int, MediaMetadata>() {
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
            onPageLoaded(end)
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

/** Publish immutable ordered snapshots before waiting for the next song-detail batch. */
internal suspend fun <T> collectPlaylistTracksIncrementally(
    orderedIds: List<Long>,
    initialTracks: List<T>,
    retainedTracks: List<T> = emptyList(),
    key: (T) -> Long,
    loadBatch: suspend (List<Long>) -> List<T>,
    onSnapshot: suspend (List<T>) -> Unit,
): List<T> {
    val ids = orderedIds.distinct()
    // The refreshed ID list is authoritative; fresh details replace retained metadata.
    val tracksById = (retainedTracks + initialTracks).associateBy(key).toMutableMap()
    val missingBatches = ids.filterNot(tracksById::containsKey).chunked(200)
    var snapshot = ids.mapNotNull(tracksById::get)
    if (snapshot.isNotEmpty() || missingBatches.isEmpty()) {
        currentCoroutineContext().ensureActive()
        onSnapshot(snapshot)
    }
    missingBatches.forEachIndexed { index, batch ->
        currentCoroutineContext().ensureActive()
        val tracks = loadBatch(batch)
        currentCoroutineContext().ensureActive()
        tracks.forEach { tracksById[key(it)] = it }
        snapshot = ids.mapNotNull(tracksById::get)
        if (snapshot.isNotEmpty() || index == missingBatches.lastIndex) onSnapshot(snapshot)
    }
    return snapshot
}
