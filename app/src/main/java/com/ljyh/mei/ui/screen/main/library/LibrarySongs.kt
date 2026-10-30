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
    previous: LibrarySongs? = null,
) {
    private val collectionScope = CoroutineScope(scope.coroutineContext + Job(scope.coroutineContext[Job]))
    private val lock = Any()
    private val tracks = mutableMapOf<Long, PlaylistDetail.Playlist.Track>()
    private var loadedCount = 0

    init {
        val retained = previous?.takeIf { it.playlistId == playlistId }?.retainedState()
        val currentIds = ids.toHashSet()
        (retained?.first.orEmpty() + initialTracks).forEach {
            if (it.id in currentIds) tracks[it.id] = it
        }
        loadedCount = (retained?.second ?: 0).coerceAtMost(ids.size)
    }

    private fun cachedTracks() = synchronized(lock) { tracks.values.toList() }
    private fun retainedState() = synchronized(lock) { tracks.values.toList() to loadedCount }

    private suspend fun load(ids: List<String>): List<PlaylistDetail.Playlist.Track> {
        val missing = synchronized(lock) { ids.filterNot { it.toLong() in tracks } }
        for (batch in missing.chunked(200)) {
            val loaded = loadTracks(batch)
            currentCoroutineContext().ensureActive()
            synchronized(lock) { loaded.forEach { tracks[it.id] = it } }
        }
        currentCoroutineContext().ensureActive()
        return synchronized(lock) {
            ids.mapNotNull { tracks[it.toLong()] }
        }
    }

    val pages: Flow<PagingData<MediaMetadata>> = Pager(
        PagingConfig(pageSize = 20, initialLoadSize = maxOf(60, loadedCount), enablePlaceholders = false),
    ) {
        PlaylistTrackSource(::load, cachedTracks(), ids.map(Long::toString)) { end ->
            synchronized(lock) { loadedCount = maxOf(loadedCount, end) }
        }
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
