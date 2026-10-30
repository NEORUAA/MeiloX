package com.ljyh.mei.ui.screen.main.library

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.PlaylistDetail
import com.ljyh.mei.data.model.toMediaMetadata
import com.ljyh.mei.data.repository.collectPlaylistTracksIncrementally
import com.ljyh.mei.ui.screen.playlist.PlaylistTrackSource
import com.ljyh.mei.ui.screen.playlist.matchesPlaylistSearch
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/** A single account/playlist generation shared by browsing, search, and playback. */
internal class LibrarySongs(
    val playlistId: Long,
    val ids: List<Long>,
    initialTracks: List<PlaylistDetail.Playlist.Track>,
    scope: CoroutineScope,
    private val loadTracks: suspend (List<String>) -> List<PlaylistDetail.Playlist.Track>,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val collectionScope = CoroutineScope(scope.coroutineContext + Job(scope.coroutineContext[Job]))
    private val lock = Any()
    private val tracks = initialTracks.associateBy { it.id }.toMutableMap()

    private fun cachedTracks() = synchronized(lock) { tracks.values.toList() }

    private suspend fun load(ids: List<String>): List<PlaylistDetail.Playlist.Track> {
        val missing = synchronized(lock) { ids.filterNot { it.toLong() in tracks } }
        val loaded = if (missing.isEmpty()) emptyList() else loadTracks(missing)
        currentCoroutineContext().ensureActive()
        return synchronized(lock) {
            loaded.forEach { tracks[it.id] = it }
            ids.mapNotNull { tracks[it.toLong()] }
        }
    }

    val pages: Flow<PagingData<MediaMetadata>> = Pager(
        PagingConfig(pageSize = 20, initialLoadSize = 60, enablePlaceholders = false),
    ) {
        PlaylistTrackSource(::load, cachedTracks(), ids.map(Long::toString))
    }.flow.cachedIn(collectionScope)

    fun close() = collectionScope.cancel()

    /** Full-library detail requests only run while an explicit search is being collected. */
    fun search(query: String): Flow<PagingData<MediaMetadata>> = flow {
        delay(300)
        collectPlaylistTracksIncrementally(
            orderedIds = ids,
            initialTracks = cachedTracks(),
            key = { it.id },
            loadBatch = { requested -> load(requested.map(Long::toString)) },
            onSnapshot = { snapshot ->
                emit(PagingData.from(snapshot.map { it.toMediaMetadata() }.filter {
                    it.matchesPlaylistSearch(query) || it.tns?.contains(query, ignoreCase = true) == true
                }))
            },
        )
    }.flowOn(ioDispatcher)
}
