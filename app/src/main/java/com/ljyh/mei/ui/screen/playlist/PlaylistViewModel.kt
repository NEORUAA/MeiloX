package com.ljyh.mei.ui.screen.playlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.filter
import com.ljyh.mei.AppContext
import com.ljyh.mei.constants.MusicQuality
import com.ljyh.mei.constants.UserIdKey
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.PlaylistDetail
import com.ljyh.mei.data.model.PlaylistOrderChangedException
import com.ljyh.mei.data.model.validatePlaylistTrackOrder
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
import com.ljyh.mei.data.repository.LibraryCollectionsRepository
import com.ljyh.mei.data.repository.LibraryAccount
import com.ljyh.mei.di.repository.ColorRepository
import com.ljyh.mei.di.repository.LikeRepository
import com.ljyh.mei.utils.dataStore
import com.ljyh.mei.utils.get
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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

internal fun Flow<PagingData<MediaMetadata>>.excludeRemovedPlaylistTracks(
    removedTrackIds: StateFlow<Set<Long>>,
): Flow<PagingData<MediaMetadata>> = combine(removedTrackIds) { pagingData, removedIds ->
    pagingData.filter { it.id !in removedIds }
}

@HiltViewModel
class PlaylistViewModel @Inject constructor(
    private val repository: PlaylistRepository,
    private val likeRepository: LikeRepository,
    private val colorRepository: ColorRepository,
    private val localPlaylistRepository: com.ljyh.mei.di.repository.LocalPlaylistRepository,
    val apiService: ApiService,
    private val collections: LibraryCollectionsRepository,
) : ViewModel() {
    val userId: String get() = AppContext.instance.dataStore[UserIdKey].orEmpty()

    fun getCachedColor(url: String) = colorRepository.getFromMemory(url)

    suspend fun getOrExtractColor(url: String) =
        colorRepository.getColorOrExtract(AppContext.instance, url)

    private val _playlistDetail = MutableStateFlow<Resource<PlaylistDetail>>(Resource.Loading)
    val playlistDetail: StateFlow<Resource<PlaylistDetail>> = _playlistDetail
    private var playlistDetailRequestGeneration = 0
    private var activePlaylistId: String? = null
    private val _trackOrder = MutableStateFlow<PlaylistOrderState?>(null)
    val trackOrder: StateFlow<PlaylistOrderState?> = _trackOrder
    private var trackOrderGeneration = 0
    private var trackOrderJob: Job? = null
    private var trackOrderAccount: LibraryAccount? = null
    private val attemptedOrderMetadata = mutableSetOf<Long>()
    private val _confirmedSubscription = MutableStateFlow<Boolean?>(null)
    val confirmedSubscription: StateFlow<Boolean?> = _confirmedSubscription

    private val _removedTrackIds = MutableStateFlow<Set<Long>>(emptySet())
    val removedTrackIds: StateFlow<Set<Long>> = _removedTrackIds

    private val _manipulateTracks =
        MutableStateFlow<Resource<ManipulateTrackResult>>(Resource.Loading)
    val manipulateTracks: StateFlow<Resource<ManipulateTrackResult>> = _manipulateTracks


    private val _playlist = MutableStateFlow<List<Playlist>>(emptyList())
    val playlist: StateFlow<List<Playlist>> = _playlist
    private var playlistChoicesJob: Job? = null


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

    private val _subscriptionUpdating = MutableStateFlow(false)
    val subscriptionUpdating: StateFlow<Boolean> = _subscriptionUpdating

    // 删除歌单状态
    private val _deletePlaylist = MutableStateFlow<Resource<BaseMessageResponse>>(Resource.Loading)
    val deletePlaylist: StateFlow<Resource<BaseMessageResponse>> = _deletePlaylist

    init {
        viewModelScope.launch {
            collections.accounts.collect { account ->
                if (trackOrderAccount != null && account != trackOrderAccount) closeTrackOrder()
            }
        }
    }

    fun getPlaylistDetail(id: String) {
        closeTrackOrder()
        if (activePlaylistId != id) {
            activePlaylistId = id
            _confirmedSubscription.value = null
        }
        val generation = ++playlistDetailRequestGeneration
        viewModelScope.launch {
            _removedTrackIds.value = emptySet()
            _playlistDetail.value = Resource.Loading
            val result = repository.getPlaylistDetail(id)
            if (generation != playlistDetailRequestGeneration) return@launch
            _playlistDetail.value = result
            if (result is Resource.Success) {
                _confirmedSubscription.value = result.data.playlist.subscribed
            }
        }
    }

    fun openTrackOrder(id: String) {
        val displayed = (_playlistDetail.value as? Resource.Success)?.data?.playlist ?: return
        if (activePlaylistId != id || displayed.Id.toString() != id ||
            displayed.creator.userId.toString() != userId || _trackOrder.value != null) return
        val generation = ++trackOrderGeneration
        val detailGeneration = playlistDetailRequestGeneration
        _trackOrder.value = PlaylistOrderState(playlistId = id)
        attemptedOrderMetadata.clear()
        trackOrderJob = viewModelScope.launch {
            try {
                val account = collections.accounts.first() ?: error("NetEase account is not signed in")
                if (account.userId != displayed.creator.userId.toString()) error("Playlist owner changed")
                trackOrderAccount = account
                val detail = repository.loadPlaylistForOrder(id, account.userId.toLong(), account.sessionFingerprint)
                if (!isTrackOrderCurrent(id, generation, detailGeneration, account)) return@launch
                val ids = detail.playlist.trackIds.map { it.id }
                validatePlaylistTrackOrder(ids, ids, ids)
                _trackOrder.value = PlaylistOrderState(
                    playlistId = id,
                    items = playlistOrderItems(ids, detail.playlist.tracks.map { it.toMediaMetadata() }),
                    originalIds = ids,
                    loading = false,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (trackOrderGeneration == generation) {
                    _trackOrder.value = _trackOrder.value?.copy(loading = false, error = PlaylistOrderError.LoadFailed)
                }
            }
        }
    }

    fun closeTrackOrder() {
        trackOrderGeneration++
        trackOrderJob?.cancel()
        trackOrderJob = null
        trackOrderAccount = null
        _trackOrder.value = null
        attemptedOrderMetadata.clear()
    }

    fun moveOrderedTrack(from: Int, to: Int) {
        val state = _trackOrder.value ?: return
        if (state.loading || state.saving || from !in state.items.indices || to !in state.items.indices) return
        _trackOrder.value = state.copy(items = state.items.toMutableList().apply { add(to, removeAt(from)) })
    }

    fun loadVisibleOrderTracks(ids: List<Long>) {
        val state = _trackOrder.value ?: return
        val account = trackOrderAccount ?: return
        if (state.loading || state.saving) return
        val missing = state.items.asSequence().filter { it.id in ids && it.track == null }
            .map { it.id }.filter { attemptedOrderMetadata.add(it) }.take(200).toList()
        if (missing.isEmpty()) return
        val generation = trackOrderGeneration
        val detailGeneration = playlistDetailRequestGeneration
        viewModelScope.launch {
            try {
                if (!isTrackOrderCurrent(state.playlistId, generation, detailGeneration, account)) return@launch
                val tracks = apiService.getSongDetail(
                    GetSongDetails(missing.joinToString(",")), account.sessionFingerprint,
                ).songs.associate { it.id to it.toMediaMetadata() }
                if (!isTrackOrderCurrent(state.playlistId, generation, detailGeneration, account)) return@launch
                val current = _trackOrder.value ?: return@launch
                if (!current.saving) _trackOrder.value = current.copy(items = current.items.map {
                    if (it.track == null) it.copy(track = tracks[it.id]) else it
                })
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Metadata is optional; unavailable IDs remain in their original order slots.
            }
        }
    }

    fun saveTrackOrder() {
        val state = _trackOrder.value ?: return
        val account = trackOrderAccount ?: return
        if (state.loading || state.saving || state.error == PlaylistOrderError.LoadFailed ||
            state.error == PlaylistOrderError.PlaylistChanged) return
        val ids = state.items.map { it.id }
        if (ids == state.originalIds) { closeTrackOrder(); return }
        val generation = trackOrderGeneration
        val detailGeneration = playlistDetailRequestGeneration
        val saving = state.copy(saving = true, error = null)
        _trackOrder.value = saving
        trackOrderJob = viewModelScope.launch {
            try {
                if (!isTrackOrderCurrent(state.playlistId, generation, detailGeneration, account)) return@launch
                repository.updatePlaylistTrackOrder(
                    id = state.playlistId,
                    originalIds = state.originalIds,
                    orderedIds = ids,
                    ownerUserId = account.userId.toLong(),
                    expectedSession = account.sessionFingerprint,
                    isCurrent = { _trackOrder.value === saving && activePlaylistId == state.playlistId &&
                        playlistDetailRequestGeneration == detailGeneration },
                )
                if (!isTrackOrderCurrent(state.playlistId, generation, detailGeneration, account)) return@launch
                // Gson can populate legacy non-null DTO fields with null. Refresh the server
                // response instead of copying the entire DTO to fabricate the saved order.
                trackOrderJob = null
                getPlaylistDetail(state.playlistId)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (trackOrderGeneration == generation) {
                    _trackOrder.value = _trackOrder.value?.copy(
                        saving = false,
                        error = if (error is PlaylistOrderChangedException) PlaylistOrderError.PlaylistChanged
                            else PlaylistOrderError.SaveFailed,
                    )
                }
            }
        }
    }

    private suspend fun isTrackOrderCurrent(
        id: String,
        generation: Int,
        detailGeneration: Int,
        account: LibraryAccount,
    ): Boolean = trackOrderGeneration == generation && activePlaylistId == id &&
        playlistDetailRequestGeneration == detailGeneration && trackOrderAccount == account &&
        collections.accounts.first() == account

    fun recordPlaylistPlayback(id: String) {
        viewModelScope.launch {
            localPlaylistRepository.touchPlaylist(id, System.currentTimeMillis())
        }
    }

    // 分页加载，不是根据歌单id加载，而是根据歌曲id加载
    fun getPlaylistTracks(
        playlistDetailResource: Resource<PlaylistDetail>,
    ): Flow<PagingData<MediaMetadata>> {
        // Cache pages before filtering so removals reuse the loaded generation and viewport.
        return when (playlistDetailResource) {
            is Resource.Success -> {
                val playlist = playlistDetailResource.data.playlist

                // Every playlist follows its canonical IDs, including liked-song playlists.
                Pager(
                    config = PagingConfig(pageSize = 20, enablePlaceholders = false),
                    pagingSourceFactory = {
                        PlaylistTrackSource(
                            apiService = apiService,
                            firstData = playlist.tracks,
                            ids = playlist.trackIds.map { it.id.toString() }
                        )
                    }
                ).flow
            }
            else -> flowOf(PagingData.empty())
        }.cachedIn(viewModelScope).excludeRemovedPlaylistTracks(_removedTrackIds)
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
    fun getAllMePlaylist() {
        playlistChoicesJob?.cancel()
        _playlist.value = emptyList()
        playlistChoicesJob = viewModelScope.launch {
            collections.accounts.collectLatest { account ->
                _playlist.value = emptyList()
                if (account == null) return@collectLatest
                try {
                    val cached = localPlaylistRepository.getPlaylistByAuthor(account.userId)
                    if (collections.accounts.first() != account) return@collectLatest
                    _playlist.value = cached
                    val playlists = collections.allPlaylists(account)
                    if (collections.accounts.first() != account) return@collectLatest
                    val existingPlaylists = localPlaylistRepository.getPlaylistByAuthor(account.userId)
                    val existingMap = existingPlaylists.associateBy { it.id }
                    val playlistsToInsert = playlists.map {
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
                    localPlaylistRepository.mergeRemotePlaylists(playlistsToInsert)
                    val updated = localPlaylistRepository.getPlaylistByAuthor(account.userId)
                    if (collections.accounts.first() == account) _playlist.value = updated
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // Keep this account's cached choices available after a failed refresh.
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
        if (_subscriptionUpdating.value || activePlaylistId != id || _playlistDetail.value !is Resource.Success) return
        _subscriptionUpdating.value = true
        playlistDetailRequestGeneration++
        viewModelScope.launch {
            _subscribePlaylist.value = Resource.Loading
            try {
                val result = repository.subscribePlaylist(id)
                if (result is Resource.Success && result.data.code == 200) {
                    updateConfirmedSubscription(id, subscribed = true)
                }
                _subscribePlaylist.value = result
            } finally {
                _subscriptionUpdating.value = false
            }
        }
    }

    /*
     * 取消收藏歌单
     */
    fun unsubscribePlaylist(id: String) {
        if (_subscriptionUpdating.value || activePlaylistId != id || _playlistDetail.value !is Resource.Success) return
        _subscriptionUpdating.value = true
        playlistDetailRequestGeneration++
        viewModelScope.launch {
            _unSubscribePlaylist.value = Resource.Loading
            try {
                val result = repository.unSubscribePlaylist(id)
                if (result is Resource.Success && result.data.code == 200) {
                    updateConfirmedSubscription(id, subscribed = false)
                }
                _unSubscribePlaylist.value = result
            } finally {
                _subscriptionUpdating.value = false
            }
        }
    }

    private fun updateConfirmedSubscription(id: String, subscribed: Boolean) {
        if (activePlaylistId == id) _confirmedSubscription.value = subscribed
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

    suspend fun resolveSongUrls(ids: List<String>, quality: MusicQuality) =
        repository.getSongUrlV1(ids, quality)

    suspend fun getSongDetails(ids: List<String>): Tracks =
        apiService.getSongDetail(GetSongDetails(c = ids.joinToString(",")))

    /**
     * Loads every track in a playlist before filtering it.  The playlist detail endpoint only
     * includes an initial batch of tracks, so filtering that batch alone would miss results in
     * larger playlists.
     */
    fun searchPlaylistTracks(
        playlistDetailResource: Resource<PlaylistDetail>,
        query: String,
    ): Flow<PagingData<MediaMetadata>> {
        if (playlistDetailResource !is Resource.Success || query.isBlank()) {
            return getPlaylistTracks(playlistDetailResource)
        }

        val playlist = playlistDetailResource.data.playlist
        return kotlinx.coroutines.flow.flow {
            val tracksById = playlist.tracks.associateBy { it.id }.toMutableMap()
            playlist.trackIds
                .map { it.id }
                .filterNot(tracksById::containsKey)
                .chunked(200)
                .forEach { ids ->
                    apiService.getSongDetail(GetSongDetails(ids.joinToString(","))).songs.forEach { track ->
                        tracksById[track.id] = track
                    }
                }

            val matchingTracks = playlist.trackIds
                .mapNotNull { tracksById[it.id] }
                .map { it.toMediaMetadata() }
                .filter { it.matchesPlaylistSearch(query) }
            emit(PagingData.from(matchingTracks))
        }.cachedIn(viewModelScope).excludeRemovedPlaylistTracks(_removedTrackIds)
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
