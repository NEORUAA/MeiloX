package com.ljyh.mei.ui.screen.main.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.room.Playlist
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.data.repository.LibraryAccount
import com.ljyh.mei.data.repository.LibraryCollectionPage
import com.ljyh.mei.data.repository.LibraryCollectionsRepository
import com.ljyh.mei.data.repository.PlayerRepository
import com.ljyh.mei.data.repository.PlaylistRepository
import com.ljyh.mei.di.repository.LocalPlaylistRepository
import com.ljyh.mei.ui.model.Album
import com.ljyh.mei.ui.navigation.LibraryPage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModel @Inject constructor(
    private val collections: LibraryCollectionsRepository,
    private val localPlaylistRepository: LocalPlaylistRepository,
    private val playlistRepository: PlaylistRepository,
    private val playerRepository: PlayerRepository,
) : ViewModel() {
    private val _accountId = MutableStateFlow<String?>(null)
    val accountId = _accountId.asStateFlow()
    private val _playlists = MutableStateFlow(LibraryCollectionState<Playlist>())
    val playlists = _playlists.asStateFlow()
    private val _albums = MutableStateFlow(LibraryCollectionState<Album>())
    val albums = _albums.asStateFlow()
    private val _likedSongs = MutableStateFlow<LibrarySongs?>(null)
    private val likedSongQuery = MutableStateFlow("")
    val likedSongIds = _likedSongs.map { it?.ids.orEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val likedPlaylistId = _likedSongs.map { it?.playlistId }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val _likedSongsLoading = MutableStateFlow(true)
    val likedSongsLoading = _likedSongsLoading.asStateFlow()
    private val _likedSongsError = MutableStateFlow<String?>(null)
    val likedSongsError = _likedSongsError.asStateFlow()
    private val _likedSongSearchLoading = MutableStateFlow(false)
    val likedSongSearchLoading = _likedSongSearchLoading.asStateFlow()
    val likedSongPages = combine(_likedSongs, likedSongQuery) { songs, query -> songs to query }
        .flatMapLatest { (songs, query) ->
            when {
                songs == null -> flowOf(PagingData.empty<MediaMetadata>())
                query.isBlank() -> songs.pages
                else -> songs.search(query)
                    .onStart { _likedSongSearchLoading.value = true }
                    .onCompletion {
                        if (_likedSongs.value === songs && likedSongQuery.value == query) {
                            _likedSongSearchLoading.value = false
                        }
                    }
                    .catch { error ->
                        if (_likedSongs.value === songs && likedSongQuery.value == query) {
                            _likedSongsError.value = error.message ?: "Unable to search songs"
                        }
                    }
            }
        }.cachedIn(viewModelScope)

    private var activeAccount: LibraryAccount? = null
    private var playlistJob: Job? = null
    private var albumJob: Job? = null
    private var likedSongsJob: Job? = null
    private var likedSongsRevision = 0L
    private var selectedPage: LibraryPage? = null
    private var playlistsDirty = true
    private var albumsDirty = true
    private var likedSongsDirty = true

    init {
        viewModelScope.launch {
            collections.accounts.collect { account ->
                playlistJob?.cancel()
                albumJob?.cancel()
                likedSongsRevision++
                likedSongsJob?.cancel()
                activeAccount = account
                _accountId.value = account?.userId
                _playlists.value = LibraryCollectionState(isLoading = account != null)
                _albums.value = LibraryCollectionState(isLoading = account != null)
                _likedSongs.value?.close()
                _likedSongs.value = null
                likedSongQuery.value = ""
                _likedSongSearchLoading.value = false
                _likedSongsError.value = null
                _likedSongsLoading.value = account != null
                playlistsDirty = true
                albumsDirty = true
                likedSongsDirty = true
                if (account != null) {
                    loadSelectedPage()
                } else {
                    collections.restoreAccountIfNeeded()
                }
            }
        }
        viewModelScope.launch {
            collections.albumChanges.collect {
                albumsDirty = true
                if (selectedPage == LibraryPage.Albums) refreshAlbums()
                else albumJob?.cancel()
            }
        }
        viewModelScope.launch {
            playlistRepository.collectionChanges.collect {
                playlistsDirty = true
                likedSongsDirty = true
                if (selectedPage == LibraryPage.Playlists) refreshPlaylists()
                else playlistJob?.cancel()
                if (selectedPage == LibraryPage.Songs) loadLikedSongs()
                else {
                    likedSongsRevision++
                    likedSongsJob?.cancel()
                }
            }
        }
        viewModelScope.launch {
            playerRepository.likeChanges.collect {
                likedSongsDirty = true
                if (selectedPage == LibraryPage.Songs) loadLikedSongs()
                else {
                    likedSongsRevision++
                    likedSongsJob?.cancel()
                }
            }
        }
    }

    fun selectPage(page: LibraryPage) {
        selectedPage = page
        if (page != LibraryPage.Songs) setLikedSongQuery("")
        loadSelectedPage()
    }

    fun leavePage() {
        selectedPage = null
        setLikedSongQuery("")
    }

    private fun loadSelectedPage() {
        when (selectedPage) {
            LibraryPage.Songs -> if (likedSongsDirty && likedSongsJob?.isActive != true) loadLikedSongs()
            LibraryPage.Playlists -> if (playlistsDirty && playlistJob?.isActive != true) refreshPlaylists()
            LibraryPage.Albums -> if (albumsDirty && albumJob?.isActive != true) refreshAlbums()
            else -> Unit
        }
    }

    fun setLikedSongQuery(query: String) {
        val trimmed = query.trim()
        if (likedSongQuery.value == trimmed) return
        if (_likedSongs.value != null) _likedSongsError.value = null
        _likedSongSearchLoading.value = trimmed.isNotEmpty() && _likedSongs.value != null
        likedSongQuery.value = trimmed
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
                currentCoroutineContext().ensureActive()
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
                currentCoroutineContext().ensureActive()
                if (activeAccount != account) return@launch
                _playlists.value = previous.append(
                    LibraryCollectionPage(mapped, page.nextOffset, page.hasMore), Playlist::id,
                )
                playlistsDirty = false
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (activeAccount == account) {
                    _playlists.value = existing.failed(error.message ?: "Unable to load playlists", retryFromStart = reset)
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
                currentCoroutineContext().ensureActive()
                if (activeAccount == account) {
                    _albums.value = previous.append(page, Album::id)
                    albumsDirty = false
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (activeAccount == account) _albums.value = existing.failed(error.message ?: "Unable to load albums", retryFromStart = reset)
            }
        }
    }

    fun retryLikedSongs() {
        loadLikedSongs()
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

    private fun loadLikedSongs() {
        val account = activeAccount ?: return
        val revision = ++likedSongsRevision
        likedSongsJob?.cancel()
        fun isCurrent() = activeAccount == account && likedSongsRevision == revision
        likedSongsJob = viewModelScope.launch {
            _likedSongsLoading.value = true
            _likedSongsError.value = null
            try {
                val playlistId = collections.likedPlaylistId(account)
                if (!isCurrent()) return@launch
                if (playlistId == null) {
                    _likedSongs.value?.close()
                    _likedSongs.value = null
                    _likedSongSearchLoading.value = false
                    likedSongsDirty = false
                    return@launch
                }
                when (val result = playlistRepository.getPlaylistDetail(
                    playlistId.toString(), account.sessionFingerprint, trackLimit = 60,
                )) {
                    is Resource.Success -> {
                        if (isCurrent()) {
                            check(result.data.code == 200) { "Unable to load songs (${result.data.code})" }
                            val playlist = result.data.playlist
                            _likedSongs.value?.close()
                            _likedSongs.value = LibrarySongs(
                                playlistId = playlistId,
                                ids = playlist.trackIds.map { it.id }.distinct(),
                                initialTracks = playlist.tracks,
                                scope = viewModelScope,
                                loadTracks = { ids -> playlistRepository.getPlaylistSongDetails(ids, account.sessionFingerprint) },
                            )
                            likedSongsDirty = false
                        }
                    }
                    is Resource.Error -> if (isCurrent()) _likedSongsError.value = result.message
                    Resource.Loading -> Unit
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (isCurrent()) _likedSongsError.value = error.message ?: "Unable to load songs"
            } finally {
                if (isCurrent()) _likedSongsLoading.value = false
            }
        }
    }
}
