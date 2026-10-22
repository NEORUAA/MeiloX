package com.ljyh.mei.ui.screen.main.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.room.Playlist
import com.ljyh.mei.data.model.toMiniPlaylistDetail
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.data.repository.LibraryAccount
import com.ljyh.mei.data.repository.LibraryCollectionPage
import com.ljyh.mei.data.repository.LibraryCollectionsRepository
import com.ljyh.mei.data.repository.PlaylistRepository
import com.ljyh.mei.di.repository.LocalPlaylistRepository
import com.ljyh.mei.ui.model.Album
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val collections: LibraryCollectionsRepository,
    private val localPlaylistRepository: LocalPlaylistRepository,
    private val playlistRepository: PlaylistRepository,
) : ViewModel() {
    private val _accountId = MutableStateFlow<String?>(null)
    val accountId = _accountId.asStateFlow()
    private val _playlists = MutableStateFlow(LibraryCollectionState<Playlist>())
    val playlists = _playlists.asStateFlow()
    private val _albums = MutableStateFlow(LibraryCollectionState<Album>())
    val albums = _albums.asStateFlow()
    private val _likedSongs = MutableStateFlow<List<MediaMetadata>>(emptyList())
    val likedSongs = _likedSongs.asStateFlow()
    private val _likedSongsLoading = MutableStateFlow(true)
    val likedSongsLoading = _likedSongsLoading.asStateFlow()
    private val _likedSongsError = MutableStateFlow<String?>(null)
    val likedSongsError = _likedSongsError.asStateFlow()

    private var activeAccount: LibraryAccount? = null
    private var playlistJob: Job? = null
    private var albumJob: Job? = null
    private var likedSongsJob: Job? = null
    private var likedPlaylistId: Long? = null

    init {
        viewModelScope.launch {
            collections.accounts.collect { account ->
                playlistJob?.cancel()
                albumJob?.cancel()
                likedSongsJob?.cancel()
                activeAccount = account
                _accountId.value = account?.userId
                _playlists.value = LibraryCollectionState(isLoading = account != null)
                _albums.value = LibraryCollectionState(isLoading = account != null)
                _likedSongs.value = emptyList()
                _likedSongsError.value = null
                _likedSongsLoading.value = account != null
                likedPlaylistId = null
                if (account != null) {
                    loadPlaylists(reset = true)
                    loadAlbums(reset = true)
                } else {
                    collections.restoreAccountIfNeeded()
                }
            }
        }
        viewModelScope.launch {
            collections.albumChanges.collect { refreshAlbums() }
        }
        viewModelScope.launch {
            playlistRepository.collectionChanges.collect { refreshPlaylists() }
        }
    }

    fun refreshPlaylists() = loadPlaylists(reset = true)
    fun refreshAlbums() = loadAlbums(reset = true)

    fun loadMorePlaylists() {
        val state = _playlists.value
        if (!state.isLoading && !state.isLoadingMore && state.hasMore) loadPlaylists(reset = false)
    }

    fun loadMoreAlbums() {
        val state = _albums.value
        if (!state.isLoading && !state.isLoadingMore && state.hasMore) loadAlbums(reset = false)
    }

    private fun loadPlaylists(reset: Boolean) {
        val account = activeAccount ?: return
        if (reset) playlistJob?.cancel() else if (playlistJob?.isActive == true) return
        val existing = _playlists.value
        val previous = if (reset) LibraryCollectionState() else existing
        _playlists.value = existing.copy(isLoading = reset, isLoadingMore = !reset, error = null)
        playlistJob = viewModelScope.launch {
            try {
                val page = reloadCollection(existing.nextOffset, reset) { collections.playlists(account, it) }
                val likedId = page.items.firstOrNull {
                    it.specialType == 5 && it.creator.userId.toString() == account.userId
                }?.id
                val mapped = page.items.filterNot { it.specialType == 5 }.map { item ->
                    val existing = localPlaylistRepository.getPlaylist(item.id.toString())
                    Playlist(
                        id = item.id.toString(), title = item.name, cover = item.coverImgUrl,
                        author = item.creator.userId.toString(), authorName = item.creator.nickname,
                        authorAvatar = item.creator.avatarUrl, count = item.trackCount,
                        playCount = item.playCount, lastPlayTime = existing?.lastPlayTime ?: 0L,
                        localPlayCount = existing?.localPlayCount ?: 0,
                    )
                }
                if (activeAccount != account) return@launch
                localPlaylistRepository.mergeRemotePlaylists(mapped)
                if (activeAccount != account) return@launch
                _playlists.value = previous.append(
                    LibraryCollectionPage(mapped, page.nextOffset, page.hasMore), Playlist::id,
                )
                if (likedId != null && (likedPlaylistId != likedId || reset)) {
                    likedPlaylistId = likedId
                    loadLikedSongs(likedId)
                } else if (!page.hasMore && likedPlaylistId == null) {
                    _likedSongsLoading.value = false
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (activeAccount == account) {
                    _playlists.value = existing.failed(error.message ?: "Unable to load playlists", retryFromStart = reset)
                    if (likedPlaylistId == null) {
                        _likedSongsLoading.value = false
                        _likedSongsError.value = error.message ?: "Unable to load songs"
                    }
                }
            }
        }
    }

    private fun loadAlbums(reset: Boolean) {
        val account = activeAccount ?: return
        if (reset) albumJob?.cancel() else if (albumJob?.isActive == true) return
        val existing = _albums.value
        val previous = if (reset) LibraryCollectionState() else existing
        _albums.value = existing.copy(isLoading = reset, isLoadingMore = !reset, error = null)
        albumJob = viewModelScope.launch {
            try {
                val page = reloadCollection(existing.nextOffset, reset) { collections.albums(account, it) }
                if (activeAccount == account) _albums.value = previous.append(page, Album::id)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (activeAccount == account) _albums.value = existing.failed(error.message ?: "Unable to load albums", retryFromStart = reset)
            }
        }
    }

    fun retryLikedSongs() {
        likedPlaylistId?.let(::loadLikedSongs) ?: refreshPlaylists()
    }

    private suspend fun <T> reloadCollection(
        currentOffset: Int,
        reset: Boolean,
        fetch: suspend (Int) -> LibraryCollectionPage<T>,
    ): LibraryCollectionPage<T> {
        var page = fetch(if (reset) 0 else currentOffset)
        val items = page.items.toMutableList()
        // Preserve loaded depth when a collection mutation refreshes an existing page.
        while (reset && page.hasMore && page.nextOffset < currentOffset) {
            val next = fetch(page.nextOffset)
            items += next.items
            if (next.nextOffset <= page.nextOffset) return next.copy(items = items, hasMore = false)
            page = next
        }
        return page.copy(items = items)
    }

    private fun loadLikedSongs(playlistId: Long) {
        val account = activeAccount ?: return
        likedSongsJob?.cancel()
        likedSongsJob = viewModelScope.launch {
            _likedSongsLoading.value = true
            _likedSongsError.value = null
            try {
                when (val result = playlistRepository.getPlaylistDetail(playlistId.toString())) {
                    is Resource.Success -> {
                        val tracks = try {
                            playlistRepository.getCompletePlaylistTracks(result.data)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            result.data.toMiniPlaylistDetail().tracks
                        }
                        if (activeAccount == account) _likedSongs.value = tracks
                    }
                    is Resource.Error -> if (activeAccount == account) _likedSongsError.value = result.message
                    Resource.Loading -> Unit
                }
            } finally {
                if (activeAccount == account) _likedSongsLoading.value = false
            }
        }
    }
}
