package com.ljyh.mei.ui.screen.main.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ljyh.mei.data.model.AlbumPhoto
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.UserAlbumList
import com.ljyh.mei.data.model.room.AccountPlaylist
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.data.repository.AccountLibraryRepository
import com.ljyh.mei.data.repository.AccountLibrarySource
import com.ljyh.mei.parasite.HostAccountStore
import com.ljyh.mei.parasite.HostSessionChangedException
import com.ljyh.mei.parasite.HostSessionStamp
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope

data class LibraryUiState(
    val session: HostSessionStamp? = null,
    val playlists: List<AccountPlaylist> = emptyList(),
    val playlistsLoading: Boolean = false,
    val playlistsError: String? = null,
    val albums: Resource<UserAlbumList> = Resource.Loading,
    val photos: Resource<AlbumPhoto> = Resource.Loading,
    val likedSongs: List<MediaMetadata> = emptyList(),
    val likedSongsLoading: Boolean = false,
    val likedSongsError: String? = null,
) {
    val userId: String get() = session?.identity?.takeIf { it.authenticated }?.userId?.toString().orEmpty()
}

class LibraryViewModel internal constructor(
    private val source: AccountLibrarySource,
    private val accounts: HostAccountStore,
) : ViewModel() {
    @Inject constructor(repository: AccountLibraryRepository, accounts: HostAccountStore) : this(
        repository as AccountLibrarySource, accounts,
    )

    private val mutableState = MutableStateFlow(LibraryUiState())
    val state = mutableState.asStateFlow()
    private val version = AtomicLong()
    private val stateLock = Any()
    private var refreshJob: Job? = null
    private val invalidation = accounts.sessions.onInvalidated { revision ->
        synchronized(stateLock) {
            if ((state.value.session?.generation ?: -1) < revision) {
                version.incrementAndGet()
                mutableState.value = LibraryUiState()
            }
        }
    }

    init {
        viewModelScope.launch {
            accounts.state.map { it.session }.distinctUntilChanged().collect { refresh() }
        }
        viewModelScope.launch {
            source.albumChanges.collect { stamp ->
                if (state.value.session == stamp && runCatching { accounts.sessions.requireCurrent(stamp) }.isSuccess) refresh()
            }
        }
    }

    fun refresh() {
        refreshJob?.cancel()
        val stamp = runCatching { accounts.requireAuthenticated() }.getOrNull()
        if (stamp == null) {
            version.incrementAndGet()
            mutableState.value = LibraryUiState()
            return
        }
        val requestVersion = runCatching {
            accounts.sessions.withCurrent(stamp) {
                synchronized(stateLock) {
                    mutableState.update {
                        if (it.session == stamp) it.copy(playlistsLoading = true, playlistsError = null, albums = Resource.Loading)
                        else LibraryUiState(session = stamp, playlistsLoading = true)
                    }
                    version.incrementAndGet()
                }
            }
        }.getOrElse { return }
        refreshJob = viewModelScope.launch {
            try {
                supervisorScope {
                    var likedEntry: AccountPlaylist? = null
                    var likedJob: Job? = null
                    var likedVersion = 0L
                    var receivedPlaylists = false
                    fun acceptPlaylists(entries: List<AccountPlaylist>) {
                        publish(stamp, requestVersion) { it.copy(playlists = entries) }
                        val nextEntry = entries.firstOrNull { it.isLiked && it.playlist.author == stamp.identity.userId.toString() }
                        val nextId = nextEntry?.playlist?.id
                        if (!receivedPlaylists || nextEntry != likedEntry) {
                            receivedPlaylists = true
                            likedEntry = nextEntry
                            val loadVersion = ++likedVersion
                            likedJob?.cancel()
                            publish(stamp, requestVersion) {
                                it.copy(likedSongs = emptyList(), likedSongsLoading = nextId != null, likedSongsError = null)
                            }
                            if (nextId != null) likedJob = launch {
                                request(stamp, requestVersion, { source.likedSongs(nextId, stamp) }) { current, result ->
                                    if (likedVersion != loadVersion) current else when (result) {
                                        is Resource.Success -> current.copy(likedSongs = result.data, likedSongsLoading = false)
                                        is Resource.Error -> current.copy(likedSongsLoading = false, likedSongsError = result.message)
                                        Resource.Loading -> current
                                    }
                                }
                            }
                        }
                    }
                    launch {
                        try {
                            source.playlists(stamp.identity.userId.toString()).collect { acceptPlaylists(it) }
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: HostSessionChangedException) {
                        } catch (error: Exception) {
                            runCatching { publish(stamp, requestVersion) { it.copy(playlistsError = error.message) } }
                        }
                    }
                    launch {
                        request(stamp, requestVersion, {
                            val result = source.sync(stamp)
                            if (result is Resource.Success) {
                                acceptPlaylists(source.playlists(stamp.identity.userId.toString()).first())
                            }
                            result
                        }) { current, result ->
                            current.copy(playlistsLoading = false, playlistsError = (result as? Resource.Error)?.message)
                        }
                    }
                    launch {
                        request(stamp, requestVersion, source::albums) { current, result -> current.copy(albums = result) }
                    }
                    launch {
                        request(stamp, requestVersion, { source.photos(stamp.identity.userId.toString()) }) { current, result ->
                            current.copy(photos = result)
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: HostSessionChangedException) {
            }
        }
    }

    private suspend fun <T> request(
        stamp: HostSessionStamp,
        requestVersion: Long,
        load: suspend () -> Resource<T>,
        apply: (LibraryUiState, Resource<T>) -> LibraryUiState,
    ) {
        try {
            accounts.sessions.requireCurrent(stamp)
            val result = load()
            currentCoroutineContext().ensureActive()
            publish(stamp, requestVersion) { apply(it, result) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: HostSessionChangedException) {
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            runCatching { publish(stamp, requestVersion) { apply(it, Resource.Error(error.message ?: "Library request failed")) } }
        }
    }

    private fun publish(stamp: HostSessionStamp, requestVersion: Long, update: (LibraryUiState) -> LibraryUiState) {
        accounts.sessions.withCurrent(stamp) {
            synchronized(stateLock) {
                if (version.get() == requestVersion) mutableState.update(update)
            }
        }
    }

    override fun onCleared() {
        invalidation.close()
        version.incrementAndGet()
        super.onCleared()
    }
}
