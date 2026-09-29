package com.ljyh.mei.ui.screen.playlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import com.ljyh.mei.constants.MusicQuality
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.PlaylistDetail
import com.ljyh.mei.data.model.Tracks
import com.ljyh.mei.data.model.api.BaseMessageResponse
import com.ljyh.mei.data.model.api.BaseResponse
import com.ljyh.mei.data.model.api.CreatePlaylistResult
import com.ljyh.mei.data.model.api.GetSongDetails
import com.ljyh.mei.data.model.api.ManipulateTrackResult
import com.ljyh.mei.data.model.room.Like
import com.ljyh.mei.data.model.room.Playlist
import com.ljyh.mei.data.model.toMediaMetadata
import com.ljyh.mei.data.model.weapi.EveryDaySongs
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.data.network.api.ApiService
import com.ljyh.mei.data.repository.PlaylistRepository
import com.ljyh.mei.data.repository.PlaylistMutationSource
import com.ljyh.mei.di.repository.LikeRepository
import com.ljyh.mei.parasite.HostSessionBridge
import com.ljyh.mei.parasite.HostSessionStamp
import com.ljyh.mei.parasite.HostSessionChangedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

internal fun MediaMetadata.matchesPlaylistSearch(query: String): Boolean {
    val keyword = query.trim()
    return title.contains(keyword, ignoreCase = true) ||
        album.title.contains(keyword, ignoreCase = true) ||
        artists.any { artist ->
            artist.name.contains(keyword, ignoreCase = true) ||
                artist.alias.orEmpty().any { it.contains(keyword, ignoreCase = true) }
        }
}

data class PlaylistPickerState(
    val owner: HostSessionStamp? = null,
    val playlists: List<Playlist> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

class PlaylistViewModel internal constructor(
    private val pageSource: com.ljyh.mei.data.repository.PlaylistPageSource,
    private val mutations: PlaylistMutationSource,
    private val repository: PlaylistRepository,
    private val likeRepository: LikeRepository,
    private val localPlaylistRepository: com.ljyh.mei.di.repository.LocalPlaylistRepository,
    val apiService: ApiService,
    private val sessions: HostSessionBridge,
    private val library: com.ljyh.mei.data.repository.AccountLibrarySource,
) : ViewModel() {
    @Inject constructor(
        repository: PlaylistRepository, likeRepository: LikeRepository,
        localPlaylistRepository: com.ljyh.mei.di.repository.LocalPlaylistRepository, apiService: ApiService,
        sessions: HostSessionBridge, library: com.ljyh.mei.data.repository.AccountLibraryRepository,
    ) : this(repository, repository, repository, likeRepository, localPlaylistRepository, apiService, sessions, library)
    val userId: String get() = runCatching { sessions.snapshot().identity.takeIf { it.authenticated }?.userId?.toString().orEmpty() }.getOrDefault("")
    private val _playlistDetail = MutableStateFlow<Resource<PlaylistDetail>>(Resource.Loading)
    val playlistDetail: StateFlow<Resource<PlaylistDetail>> = _playlistDetail
    private val _detailSession = MutableStateFlow<HostSessionStamp?>(null)
    val detailSession = _detailSession.asStateFlow()
    private val _collected = MutableStateFlow<Boolean?>(null)
    val collected = _collected.asStateFlow()
    private val detailLock = Any()
    private var detailVersion = 0L
    private var loadedId: String? = null
    private var requestedId: String? = null
    private var detailJob: Job? = null
    private var collectionJob: Job? = null
    private var changingCollection = false
    private var refreshAfterCollection = false

    private val _removedTrackIds = MutableStateFlow<Set<Long>>(emptySet())
    val removedTrackIds: StateFlow<Set<Long>> = _removedTrackIds

    private val _manipulateTracks =
        MutableStateFlow<Resource<ManipulateTrackResult>>(Resource.Loading)
    val manipulateTracks: StateFlow<Resource<ManipulateTrackResult>> = _manipulateTracks


    private val _actionSession = MutableStateFlow(runCatching { sessions.snapshot() }.getOrNull())
    val actionSession = _actionSession.asStateFlow()
    private val _picker = MutableStateFlow(PlaylistPickerState())
    val picker = _picker.asStateFlow()
    private var pickerJob: Job? = null
    private var pickerVersion = 0L
    private var mutationJob: Job? = null
    private var actionVersion = 0L
    private var mutationRunning = false


    private val _everyDay = MutableStateFlow<Resource<EveryDaySongs>>(Resource.Loading)
    val everyDay: StateFlow<Resource<EveryDaySongs>> = _everyDay

    // 创建歌单状态
    private val _createPlaylist = MutableStateFlow<Resource<CreatePlaylistResult>>(Resource.Loading)
    val createPlaylist: StateFlow<Resource<CreatePlaylistResult>> = _createPlaylist

    // 收藏/取消收藏歌单状态
    private val _subscribePlaylist = MutableStateFlow<Resource<BaseResponse>>(Resource.Loading)
    val subscribePlaylist: StateFlow<Resource<BaseResponse>> = _subscribePlaylist

    private val _unSubscribePlaylist = MutableStateFlow<Resource<BaseResponse>>(Resource.Loading)
    val unSubscribePlaylist: StateFlow<Resource<BaseResponse>> = _unSubscribePlaylist

    // 删除歌单状态
    private val _deletePlaylist = MutableStateFlow<Resource<BaseMessageResponse>>(Resource.Loading)
    val deletePlaylist: StateFlow<Resource<BaseMessageResponse>> = _deletePlaylist

    private val invalidation = sessions.onInvalidated { revision ->
        synchronized(detailLock) {
            if ((_detailSession.value?.generation ?: -1) < revision) clearDetail()
            if ((_actionSession.value?.generation ?: -1) < revision) clearActions()
        }
    }

    init {
        viewModelScope.launch {
            combine(sessions.changes, sessions.recoveryRequired) { _, _ -> Unit }.collect {
                val stamp = runCatching { sessions.snapshot() }.getOrNull()
                if (stamp == null) {
                    detailJob?.cancel()
                    collectionJob?.cancel()
                    synchronized(detailLock) { clearDetail() }
                } else if (_detailSession.value != stamp) requestedId?.let(::getPlaylistDetail)
                if (_actionSession.value != stamp) {
                    pickerJob?.cancel()
                    mutationJob?.cancel()
                    synchronized(detailLock) {
                        clearActions()
                        _actionSession.value = stamp
                    }
                }
            }
        }
    }

    private fun clearActions() {
        actionVersion++
        pickerVersion++
        mutationRunning = false
        _actionSession.value = null
        _picker.value = PlaylistPickerState()
        _manipulateTracks.value = Resource.Loading
        _createPlaylist.value = Resource.Loading
        _deletePlaylist.value = Resource.Loading
    }

    fun captureActionSession(): HostSessionStamp? = runCatching { sessions.snapshot() }.getOrNull()

    private fun clearDetail() {
        detailVersion++
        changingCollection = false
        refreshAfterCollection = false
        _detailSession.value = null
        _playlistDetail.value = if (sessions.recoveryRequired.value) Resource.Error("Official session recovery is required") else Resource.Loading
        _collected.value = null
        _removedTrackIds.value = emptySet()
        _subscribePlaylist.value = Resource.Loading
        _unSubscribePlaylist.value = Resource.Loading
    }

    fun getPlaylistDetail(id: String) {
        requestedId = id
        val stamp = runCatching { sessions.snapshot() }.getOrNull() ?: return
        val version = runCatching { sessions.withCurrent(stamp) {
            synchronized(detailLock) {
                if (loadedId == id && _detailSession.value == stamp && changingCollection) {
                    refreshAfterCollection = true
                    null
                } else {
                    loadedId = id
                    clearDetail()
                    _detailSession.value = stamp
                    detailVersion
                }
            }
        } }.getOrNull() ?: return
        detailJob?.cancel()
        collectionJob?.cancel()
        detailJob = viewModelScope.launch {
            try {
                sessions.requireCurrent(stamp)
                val result = pageSource.getPlaylistDetail(id, stamp)
                if (result is Resource.Success) {
                    check(result.data.code == 200 && result.data.playlist.Id.toString() == id) { "Invalid official playlist detail" }
                }
                currentCoroutineContext().ensureActive()
                val published = publishDetail(stamp, version) {
                    _playlistDetail.value = result
                    _collected.value = (result as? Resource.Success)?.data?.playlist?.subscribed
                }
                if (published && result is Resource.Success && stamp.identity.authenticated) {
                    val coroutine = currentCoroutineContext()
                    runCatching {
                        localPlaylistRepository.touchAccountPlaylist(stamp.identity.userId.toString(), id, System.currentTimeMillis()) {
                            coroutine.ensureActive()
                            requireDetail(stamp, result)
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: HostSessionChangedException) {
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                runCatching { publishDetail(stamp, version) { _playlistDetail.value = Resource.Error(error.message ?: "Playlist request failed") } }
            }
        }
    }

    private fun publishDetail(stamp: HostSessionStamp, version: Long, update: () -> Unit): Boolean = sessions.withCurrent(stamp) {
        synchronized(detailLock) { (detailVersion == version).also { if (it) update() } }
    }

    fun requireDetail(stamp: HostSessionStamp, detail: Resource<PlaylistDetail>) = sessions.withCurrent(stamp) {
        synchronized(detailLock) {
            if (_detailSession.value != stamp || _playlistDetail.value !== detail || detail !is Resource.Success) {
                throw CancellationException("Playlist changed")
            }
        }
    }

    // 分页加载，不是根据歌单id加载，而是根据歌曲id加载
    fun getPlaylistTracks(
        playlistDetailResource: Resource<PlaylistDetail>,
        removedTrackIds: Set<Long> = emptySet()
    ): Flow<PagingData<MediaMetadata>> {
        return playlistTrackFlow(playlistDetailResource, "", removedTrackIds)
    }


    fun updateAllLike(likes: List<Like>) {
        viewModelScope.launch {
            likeRepository.updateAllLike(likes)
        }
    }

    fun addSongToPlaylist(
        pid: String,
        trackIds: String,
        owner: HostSessionStamp,
        onComplete: (PlaylistTrackAddOutcome) -> Unit = {}
    ) {
        runMutation(owner, _manipulateTracks,
            validate = {
                check(_picker.value.owner == owner && _picker.value.playlists.any { it.id == pid }) { "Playlist selection changed" }
            },
            request = { mutations.manipulateTrack("add", pid, trackIds, owner) },
            accepted = { it.code == 200 },
            onComplete = { onComplete(it.toPlaylistTrackAddOutcome()) },
        )
    }


    fun deleteSongFromPlaylist(
        pid: String,
        trackIds: String,
        owner: HostSessionStamp,
        onComplete: (Boolean) -> Unit = {}
    ) {
        val detail = _playlistDetail.value
        runMutation(owner, _manipulateTracks,
            validate = {
                requireDetail(owner, detail)
                val playlist = (detail as Resource.Success).data.playlist
                check(playlist.Id.toString() == pid && playlist.creator.userId == owner.identity.userId) { "Playlist is not owned by this account" }
            },
            request = { mutations.manipulateTrack("del", pid, trackIds, owner) },
            accepted = { it.code == 200 },
            onComplete = { result ->
                if (_playlistDetail.value === detail) {
                    onComplete(result is Resource.Success && result.data.code == 200)
                }
            },
        )
    }

    fun markTrackRemoved(trackId: Long) {
        _removedTrackIds.value = _removedTrackIds.value + trackId
    }
    fun getAllMePlaylist(owner: HostSessionStamp) {
        val version = runCatching { sessions.withCurrent(owner) {
            synchronized(detailLock) {
                pickerVersion++
                _picker.value = PlaylistPickerState(owner, loading = owner.identity.authenticated,
                    error = if (owner.identity.authenticated) null else "Official login is required")
                pickerVersion
            }
        } }.getOrNull() ?: return
        pickerJob?.cancel()
        if (!owner.identity.authenticated) return
        pickerJob = viewModelScope.launch {
            fun publish(update: (PlaylistPickerState) -> PlaylistPickerState) = sessions.withCurrent(owner) {
                synchronized(detailLock) { if (pickerVersion == version) _picker.value = update(_picker.value) }
            }
            try {
                val accountId = owner.identity.userId.toString()
                val cached = library.playlists(accountId).first().map { it.playlist }.filter { it.author == accountId }
                currentCoroutineContext().ensureActive()
                publish { it.copy(playlists = cached) }
                when (val result = library.sync(owner)) {
                    is Resource.Success -> {
                        val fresh = library.playlists(accountId).first().map { it.playlist }.filter { it.author == accountId }
                        currentCoroutineContext().ensureActive()
                        publish { it.copy(playlists = fresh, loading = false) }
                    }
                    is Resource.Error -> publish { it.copy(loading = false, error = result.message) }
                    Resource.Loading -> error("Unexpected pending playlist response")
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: HostSessionChangedException) {
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                runCatching { publish { it.copy(loading = false, error = error.message ?: "Playlist loading failed") } }
            }
        }
    }

    fun stopPlaylistPicker() {
        synchronized(detailLock) { pickerVersion++ }
        pickerJob?.cancel()
    }

    private fun <T> runMutation(
        owner: HostSessionStamp,
        output: MutableStateFlow<Resource<T>>,
        validate: () -> Unit = {},
        request: suspend () -> Resource<T>,
        accepted: (T) -> Boolean,
        onComplete: (Resource<T>) -> Unit,
    ) {
        val version = runCatching { sessions.withCurrent(owner) {
            synchronized(detailLock) {
                if (_actionSession.value != owner || mutationRunning) null else {
                    mutationRunning = true
                    output.value = Resource.Loading
                    actionVersion
                }
            }
        } }.getOrNull() ?: return
        mutationJob = viewModelScope.launch {
            try {
                val result = try {
                    sessions.requireCurrent(owner)
                    check(owner.identity.authenticated) { "Official login is required" }
                    validate()
                    request()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: HostSessionChangedException) {
                    throw error
                } catch (error: Exception) {
                    Resource.Error(error.message ?: "Playlist operation failed")
                }
                currentCoroutineContext().ensureActive()
                val published = sessions.withCurrent(owner) {
                    synchronized(detailLock) {
                        (actionVersion == version).also { current ->
                            if (current) {
                                output.value = result
                                onComplete(result)
                            }
                        }
                    }
                }
                if (published && result is Resource.Success && accepted(result.data)) {
                    viewModelScope.launch { runCatching { library.sync(owner) } }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: HostSessionChangedException) {
            } finally {
                synchronized(detailLock) { if (actionVersion == version) mutationRunning = false }
            }
        }
    }

    fun getEveryDayRecommendSongs() {
        viewModelScope.launch {
            _everyDay.value = Resource.Loading
            _everyDay.value = repository.getEveryDayRecommendSongs()
        }

    }

    /*
     * 创建歌单
     */
    fun createPlaylist(
        name: String,
        privacy: Boolean,
        owner: HostSessionStamp,
        type: String = "NORMAL",
        onComplete: (Boolean) -> Unit = {},
    ) {
        runMutation(owner, _createPlaylist,
            request = { mutations.createPlaylist(name, privacy, type, owner) },
            accepted = { it.code == 200 },
            onComplete = { onComplete(it is Resource.Success && it.data.code == 200) },
        )
    }

    /*
     * 收藏歌单
     */
    fun subscribePlaylist(id: String) {
        setCollection(id, true)
    }

    /*
     * 取消收藏歌单
     */
    fun unsubscribePlaylist(id: String) {
        setCollection(id, false)
    }

    private fun setCollection(id: String, collected: Boolean) {
        val stamp = _detailSession.value ?: return
        val before = _collected.value ?: return
        val output = if (collected) _subscribePlaylist else _unSubscribePlaylist
        val version = runCatching { sessions.withCurrent(stamp) {
            synchronized(detailLock) {
                val detail = (_playlistDetail.value as? Resource.Success)?.data?.playlist
                if (changingCollection || loadedId != id || detail == null || before == collected) null
                else if (!stamp.identity.authenticated || detail.creator.userId == stamp.identity.userId) {
                    output.value = Resource.Error("Official account cannot collect this playlist")
                    null
                } else {
                    changingCollection = true
                    _collected.value = collected
                    output.value = Resource.Loading
                    detailVersion
                }
            }
        } }.getOrNull() ?: return
        collectionJob = viewModelScope.launch {
            try {
                sessions.requireCurrent(stamp)
                val result = if (collected) pageSource.subscribePlaylist(id, stamp) else pageSource.unSubscribePlaylist(id, stamp)
                currentCoroutineContext().ensureActive()
                val accepted = publishDetail(stamp, version) {
                    changingCollection = false
                    _collected.value = if (result is Resource.Success && result.data.code == 200) collected else before
                    output.value = result
                }
                if (accepted && result is Resource.Success && result.data.code == 200) {
                    viewModelScope.launch { runCatching { library.sync(stamp) } }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: HostSessionChangedException) {
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                runCatching { publishDetail(stamp, version) {
                    changingCollection = false
                    _collected.value = before
                    output.value = Resource.Error(error.message ?: "Playlist collection failed")
                } }
            } finally {
                val refresh = synchronized(detailLock) {
                    (detailVersion == version && refreshAfterCollection).also { if (it) refreshAfterCollection = false }
                }
                if (refresh && currentCoroutineContext()[Job]?.isActive == true) getPlaylistDetail(id)
            }
        }
    }

    fun consumeCollectionResult(result: Resource<BaseResponse>, subscribed: Boolean) {
        synchronized(detailLock) {
            val output = if (subscribed) _subscribePlaylist else _unSubscribePlaylist
            if (output.value === result) output.value = Resource.Loading
        }
    }

    /*
     * 删除歌单
     */
    fun deletePlaylist(id: String, owner: HostSessionStamp, onComplete: (Boolean) -> Unit = {}) {
        runMutation(owner, _deletePlaylist,
            request = {
                val entry = library.playlists(owner.identity.userId.toString()).first().firstOrNull { it.playlist.id == id }
                check(entry != null && !entry.isLiked && entry.playlist.author == owner.identity.userId.toString()) { "Playlist cannot be deleted by this account" }
                sessions.requireCurrent(owner)
                mutations.deletePlaylist(id, owner)
            },
            accepted = { it.code == 200 },
            onComplete = { onComplete(it is Resource.Success && it.data.code == 200) },
        )
    }

    suspend fun resolveSongUrls(ids: List<String>, quality: MusicQuality, owner: HostSessionStamp = sessions.snapshot()) =
        sessions.requireCurrent(owner).let {
            repository.getSongUrlV1(ids, quality, owner).also {
                currentCoroutineContext().ensureActive()
                sessions.requireCurrent(owner)
            }
        }

    suspend fun getSongDetails(ids: List<String>, owner: HostSessionStamp = sessions.snapshot()): Tracks {
        sessions.requireCurrent(owner)
        return apiService.getSongDetail(GetSongDetails(c = ids.joinToString(",")), owner).also {
            currentCoroutineContext().ensureActive()
            sessions.requireCurrent(owner)
            check(it.code == 200) { "Playlist tracks failed (${it.code})" }
        }
    }

    /**
     * Loads every track in a playlist before filtering it.  The playlist detail endpoint only
     * includes an initial batch of tracks, so filtering that batch alone would miss results in
     * larger playlists.
     */
    fun searchPlaylistTracks(
        playlistDetailResource: Resource<PlaylistDetail>,
        query: String,
        removedTrackIds: Set<Long> = emptySet()
    ): Flow<PagingData<MediaMetadata>> {
        return playlistTrackFlow(playlistDetailResource, query, removedTrackIds)
    }

    private fun playlistTrackFlow(detail: Resource<PlaylistDetail>, query: String, removed: Set<Long>): Flow<PagingData<MediaMetadata>> {
        val stamp = _detailSession.value ?: return flowOf(PagingData.empty())
        if (detail !is Resource.Success || runCatching { requireDetail(stamp, detail) }.isFailure) return flowOf(PagingData.empty())
        val playlist = detail.data.playlist
        return Pager(PagingConfig(pageSize = 20, enablePlaceholders = false)) {
            PlaylistTrackSource(
                firstData = playlist.tracks,
                ids = playlist.trackIds.map { it.id.toString() }.filterNot { it.toLong() in removed },
                loadTracks = { pageSource.getPlaylistTrackDetails(it, stamp) },
                validate = { requireDetail(stamp, detail) },
                query = query,
            )
        }.flow
    }

    override fun onCleared() {
        invalidation.close()
        synchronized(detailLock) { detailVersion++; clearActions() }
        super.onCleared()
    }
}

enum class PlaylistTrackAddOutcome {
    Added,
    AlreadyExists,
    PartiallyAdded,
    Failed,
}

internal fun Resource<ManipulateTrackResult>.toPlaylistTrackAddOutcome(): PlaylistTrackAddOutcome = when (this) {
    is Resource.Success -> when {
        data.code == 502 -> PlaylistTrackAddOutcome.AlreadyExists
        data.code != 200 -> PlaylistTrackAddOutcome.Failed
        !data.offlineIds.isNullOrEmpty() -> PlaylistTrackAddOutcome.PartiallyAdded
        else -> PlaylistTrackAddOutcome.Added
    }
    else -> PlaylistTrackAddOutcome.Failed
}
