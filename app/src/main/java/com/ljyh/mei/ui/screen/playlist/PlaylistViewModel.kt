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
import com.ljyh.mei.data.repository.UserRepository
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

class PlaylistViewModel internal constructor(
    private val pageSource: com.ljyh.mei.data.repository.PlaylistPageSource,
    private val repository: PlaylistRepository,
    private val userRepository: UserRepository,
    private val likeRepository: LikeRepository,
    private val localPlaylistRepository: com.ljyh.mei.di.repository.LocalPlaylistRepository,
    val apiService: ApiService,
    private val sessions: HostSessionBridge,
    private val library: com.ljyh.mei.data.repository.AccountLibrarySource,
) : ViewModel() {
    @Inject constructor(
        repository: PlaylistRepository, userRepository: UserRepository, likeRepository: LikeRepository,
        localPlaylistRepository: com.ljyh.mei.di.repository.LocalPlaylistRepository, apiService: ApiService,
        sessions: HostSessionBridge, library: com.ljyh.mei.data.repository.AccountLibraryRepository,
    ) : this(repository, repository, userRepository, likeRepository, localPlaylistRepository, apiService, sessions, library)
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


    private val _playlist = MutableStateFlow<List<Playlist>>(emptyList())
    val playlist: StateFlow<List<Playlist>> = _playlist


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
            }
        }
    }

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
        previousTrackCount: Int = 0,
        onComplete: (PlaylistTrackAddOutcome) -> Unit = {}
    ) {
        viewModelScope.launch {
            _manipulateTracks.value = Resource.Loading
            val result = repository.manipulateTrack("add", pid, trackIds)
            _manipulateTracks.value = result
            onComplete(result.toPlaylistTrackAddOutcome(previousTrackCount))
        }
    }


    fun deleteSongFromPlaylist(
        pid: String,
        trackIds: String,
        onComplete: (Boolean) -> Unit = {}
    ) {
        viewModelScope.launch {
            _manipulateTracks.value = Resource.Loading
            val result = repository.manipulateTrack("del", pid, trackIds)
            _manipulateTracks.value = result
            onComplete(result is Resource.Success && result.data.code == 200)
        }
    }

    fun markTrackRemoved(trackId: Long) {
        _removedTrackIds.value = _removedTrackIds.value + trackId
    }
    fun getAllMePlaylist(){
        viewModelScope.launch {
            _playlist.value = localPlaylistRepository.getPlaylistByAuthor(userId)
            if (userId.isNotEmpty()) {
                when (val result = userRepository.getUserPlaylist(userId, 100)) {
                    is Resource.Success -> {
                        val existingPlaylists = localPlaylistRepository.getPlaylistByAuthor(userId)
                        val existingMap = existingPlaylists.associateBy { it.id }
                        val playlistsToInsert = result.data.playlist.map {
                            val existing = existingMap[it.id.toString()]
                            Playlist(
                                id = it.id.toString(),
                                title = it.name,
                                cover = it.coverImgUrl,
                                author = it.creator.userId.toString(),
                                authorName = it.creator.nickname,
                                authorAvatar = it.creator.avatarUrl,
                                count = it.trackCount,
                                playCount = it.playCount,
                                lastPlayTime = existing?.lastPlayTime ?: 0L,
                                localPlayCount = existing?.localPlayCount ?: 0
                            )
                        }
                        localPlaylistRepository.insertPlaylists(playlistsToInsert)
                        _playlist.value = localPlaylistRepository.getPlaylistByAuthor(userId)
                    }
                    is Resource.Error -> {}
                    Resource.Loading -> {}
                }
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
        privacy: Boolean =  false, // 0 普通歌单, 10 隐私歌单
        type: String = "NORMAL" // 默认 NORMAL, VIDEO 视频歌单, SHARED 共享歌单
    ) {
        viewModelScope.launch {
            _createPlaylist.value = Resource.Loading
            _createPlaylist.value = repository.createPlaylist(name, privacy, type)
        }
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
    fun deletePlaylist(id: String) {
        viewModelScope.launch {
            _deletePlaylist.value = Resource.Loading
            _deletePlaylist.value = repository.deletePlaylist(id)
        }
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
        synchronized(detailLock) { detailVersion++ }
        super.onCleared()
    }
}

enum class PlaylistTrackAddOutcome {
    Added,
    AlreadyExists,
    Failed,
}

internal fun Resource<ManipulateTrackResult>.toPlaylistTrackAddOutcome(
    previousTrackCount: Int
): PlaylistTrackAddOutcome = when (this) {
    is Resource.Success -> when {
        data.code == 502 && data.message == "歌单内歌曲重复" -> PlaylistTrackAddOutcome.AlreadyExists
        data.code != 200 -> PlaylistTrackAddOutcome.Failed
        data.count > previousTrackCount -> PlaylistTrackAddOutcome.Added
        data.count == previousTrackCount -> PlaylistTrackAddOutcome.AlreadyExists
        else -> PlaylistTrackAddOutcome.Failed
    }
    else -> PlaylistTrackAddOutcome.Failed
}
