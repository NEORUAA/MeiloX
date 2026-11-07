package com.ljyh.mei.ui.component.player

import android.widget.Toast
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonObject
import com.ljyh.mei.AppContext
import com.ljyh.mei.constants.DownloadPathKey
import com.ljyh.mei.constants.DownloadQualityKey
import com.ljyh.mei.constants.MusicQuality
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.toSourceTrack
import com.ljyh.mei.data.model.qq.u.SearchResult
import com.ljyh.mei.data.model.room.Playlist
import com.ljyh.mei.data.model.room.QQSong
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.di.repository.LocalPlaylistRepository
import com.ljyh.mei.di.repository.QQSongRepository
import com.ljyh.mei.playback.MediaUriProvider
import com.ljyh.mei.source.api.*
import com.ljyh.mei.source.core.SourceFeatureClient
import com.ljyh.mei.source.core.SourceRouter
import com.ljyh.mei.ui.model.LyricCandidate
import com.ljyh.mei.ui.model.LyricData
import com.ljyh.mei.ui.model.MoreAction
import com.ljyh.mei.ui.model.SortOrder
import com.ljyh.mei.ui.model.resolveMoreActionOrder
import com.ljyh.mei.ui.model.availableFor
import com.ljyh.mei.utils.dataStore
import com.ljyh.mei.utils.get
import com.ljyh.mei.utils.lyric.LyricManager
import com.ljyh.mei.utils.lyric.lyricStorageKey
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val qqSongRepository: QQSongRepository,
    private val localPlaylistRepository: LocalPlaylistRepository,
    val lyricManager: LyricManager,
    private val mediaUriProvider: MediaUriProvider,
    private val sourceRouter: SourceRouter,
    private val sourceFeatures: SourceFeatureClient,
) : ViewModel() {
    val searchResult: StateFlow<Resource<SearchResult>> = lyricManager.qqSearchResult
    val lyric: StateFlow<LyricData> = lyricManager.lyricData
    val lyricCandidates: StateFlow<List<LyricCandidate>> = lyricManager.lyricCandidates
    val lyricOffsetMs: StateFlow<Int> = lyricManager.lyricOffsetMs
    val sourceProviders = sourceRouter.registry.providers
    fun isActionAvailable(action: MoreAction, metadata: MediaMetadata?): Boolean =
        action.availableFor(metadata, metadata?.trackRef?.providerId?.let { sourceRouter.registry.provider(it) })
    private val mutableSongLikes = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val songLikes = mutableSongLikes.asStateFlow()
    private val mutableSongLikeGeneration = MutableStateFlow(0L)
    val songLikeGeneration = mutableSongLikeGeneration.asStateFlow()

    suspend fun ensureSourceLikes(refs: Set<TrackRef>): Resource<Unit> {
        val generation = songLikeGeneration.value
        return try {
            refs.groupBy { it.providerId }.forEach { (_, group) ->
                val readable = group.filter { sourceLikeProvider(it) != null && TrackRefCodec.mediaId(it) !in mutableSongLikes.value }
                readable.chunked(100).forEach { batch ->
                    val states = sourceRouter.liked(batch)
                    if (songLikeGeneration.value != generation) throw CancellationException("Favorite owner changed")
                    mutableSongLikes.update { existing -> existing + states.mapKeys { TrackRefCodec.mediaId(it.key) } }
                }
            }
            Resource.Success(Unit)
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (error: Exception) { Resource.Error(sourceLikeError(error)) }
    }

    private val _like = MutableStateFlow<Resource<Boolean>>(Resource.Loading)
    val like: StateFlow<Resource<Boolean>> = _like
    private val _canLike = MutableStateFlow(false)
    val canLike: StateFlow<Boolean> = _canLike.asStateFlow()
    private var selectedLikeRef: TrackRef? = null
    private var selectedLikeProvider: MusicProvider? = null
    private var selectedLikeProviderVersion: String? = null
    private var selectedLikeAccountRevision: String? = null
    private var likeSelectionGeneration = 0L
    private var likeStatusJob: Job? = null
    private var sourceLikeWriteJob: Job? = null

    init {
        viewModelScope.launch {
            sourceRouter.registry.revision.collect {
                invalidateFavoriteCache()
                selectedLikeRef?.let(::getSourceLike)
            }
        }
        viewModelScope.launch {
            sourceRouter.contentRevision.drop(1).collect {
                if (sourceLikeWriteJob?.isActive != true) {
                    invalidateFavoriteCache()
                    selectedLikeRef?.let(::getSourceLike)
                }
            }
        }
        viewModelScope.launch {
            sourceRouter.registry.providers.collectLatest { providers ->
                coroutineScope {
                    providers.forEach { provider ->
                        val account = (provider as? AccountRevisionCapability)?.accountRevision ?: return@forEach
                        launch {
                            account.distinctUntilChanged().drop(1).collect {
                                invalidateFavoriteCache()
                                if (playlistChoiceOwner?.id == provider.id) mutableMyPlaylists.value = emptyList()
                                if (selectedLikeRef?.providerId == provider.id) {
                                    sourceLikeWriteJob?.cancel()
                                    selectedLikeRef?.let(::getSourceLike)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun invalidateFavoriteCache() {
        mutableSongLikes.value = emptyMap()
        mutableSongLikeGeneration.update { it + 1 }
    }

    private var playlistChoicesJob: Job? = null

    private val _createPlaylist = MutableStateFlow<Resource<SourcePlaylist>>(Resource.Loading)
    val createPlaylist: StateFlow<Resource<SourcePlaylist>> = _createPlaylist


    private val _intelligenceList = MutableStateFlow<Resource<List<Track>>>(Resource.Loading)
    val intelligenceList: StateFlow<Resource<List<Track>>> = _intelligenceList


    private val _songDetail = MutableStateFlow<Resource<Track>>(Resource.Loading)
    val songDetail: StateFlow<Resource<Track>> = _songDetail

    private var intelligenceJob: Job? = null


    var mediaMetadata: MediaMetadata? = null

    suspend fun getAvailableDownloadQualities(mediaId: Long): List<MusicQuality> =
        withContext(Dispatchers.IO) {
            mediaUriProvider.getAvailableDownloadQualities(mediaId.toString())
        }

    suspend fun getAvailableDownloadQualities(metadata: MediaMetadata): List<MusicQuality> =
        withContext(Dispatchers.IO) {
            mediaUriProvider.getAvailableDownloadQualities(TrackRefCodec.mediaId(metadata.trackRef))
        }

    val localPlaylists: StateFlow<List<Playlist>> = localPlaylistRepository.getAllPlaylist()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = emptyList()
        )

    private val mutableMyPlaylists = MutableStateFlow<List<Playlist>>(emptyList())
    val myPlaylists = mutableMyPlaylists.asStateFlow()
    private var playlistChoiceOwner: MusicProvider? = null
    private var playlistChoiceAccount: String? = null

    fun getLike(id: Long) {
        val current = mediaMetadata?.takeIf { it.id == id }
        if (current != null) getLike(current)
        else TrackRefCodec.decode(id.toString())?.let(::getSourceLike) ?: clearLikeSelection()
    }

    fun getLike(metadata: MediaMetadata?) {
        if (metadata == null || metadata.isLocal || metadata.isPodcast) { clearLikeSelection(); return }
        getSourceLike(metadata.trackRef)
    }

    private fun getSourceLike(ref: TrackRef) {
        val provider = sourceLikeProvider(ref)
        val selectionChanged = selectedLikeRef != ref
        val providerChanged = selectedLikeProvider !== provider || selectedLikeProviderVersion != provider?.version
        if (selectionChanged || providerChanged) sourceLikeWriteJob?.cancel()
        selectedLikeRef = ref
        selectedLikeProvider = provider
        selectedLikeProviderVersion = provider?.version
        selectedLikeAccountRevision = null
        val generation = ++likeSelectionGeneration
        likeStatusJob?.cancel()
        _canLike.value = provider != null
        if (provider == null) { _like.value = Resource.Success(false); return }
        _like.value = mutableSongLikes.value[TrackRefCodec.mediaId(ref)]?.let { Resource.Success(it) } ?: Resource.Loading
        likeStatusJob = viewModelScope.launch {
            try {
                val revision = (provider as? AccountRevisionCapability)?.accountRevision?.first()
                if (currentSourceLike(ref, generation, provider)) selectedLikeAccountRevision = revision
                val liked = sourceRouter.liked(ref)
                if ((provider as? AccountRevisionCapability)?.accountRevision?.first() != revision) return@launch
                if (currentSourceLike(ref, generation, provider)) {
                    selectedLikeAccountRevision = revision
                    mutableSongLikes.update { it + (TrackRefCodec.mediaId(ref) to liked) }
                    _like.value = Resource.Success(liked)
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                if (currentSourceLike(ref, generation, provider)) _like.value = Resource.Error(sourceLikeError(error))
            }
        }
    }

    private fun sourceLikeProvider(ref: TrackRef): MusicProvider? = sourceRouter.registry.provider(ref.providerId)?.takeIf {
        SourceCapability.LIKE in it.capabilities && it is LikeCapability && it.canReadLikes && it.canWriteLikes
    }

    private fun currentSourceLike(ref: TrackRef, generation: Long, provider: MusicProvider): Boolean =
        selectedLikeRef == ref && likeSelectionGeneration == generation && sourceLikeProvider(ref) === provider &&
            selectedLikeProviderVersion == provider.version

    private fun clearLikeSelection() {
        selectedLikeRef = null
        selectedLikeProvider = null
        selectedLikeProviderVersion = null
        selectedLikeAccountRevision = null
        likeSelectionGeneration++
        likeStatusJob?.cancel()
        sourceLikeWriteJob?.cancel()
        _canLike.value = false
        _like.value = Resource.Success(false)
    }

    fun like(metadata: MediaMetadata) {
        if (metadata.trackRef != mediaMetadata?.trackRef || metadata.isLocal || metadata.isPodcast) return
        val ref = metadata.trackRef
        val provider = sourceLikeProvider(ref) ?: return
        if (selectedLikeRef != ref || selectedLikeProvider !== provider) getSourceLike(ref)
        val current = (_like.value as? Resource.Success<Boolean>)?.data
        val generation = likeSelectionGeneration
        val account = ((provider as? AccountRevisionCapability)?.accountRevision as? StateFlow<String?>)?.value
            ?: selectedLikeAccountRevision
        if (!currentSourceLike(ref, generation, provider)) return
        sourceLikeWriteJob = viewModelScope.launch {
            try {
                currentCoroutineContext().ensureActive()
                if (!currentSourceLike(ref, generation, provider)) return@launch
                val liked = sourceRouter.toggleLike(ref, account, provider)
                if (currentSourceLike(ref, generation, provider)) {
                    mutableSongLikes.update { it + (TrackRefCodec.mediaId(ref) to liked) }
                    _like.value = Resource.Success(liked)
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                if (currentSourceLike(ref, generation, provider)) {
                    val message = sourceLikeError(error)
                    _like.value = current?.let { Resource.Success(it) } ?: Resource.Error(message)
                    Toast.makeText(AppContext.instance, message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun like(id: String) {
        mediaMetadata?.takeIf { it.trackRef.trackId == id || TrackRefCodec.mediaId(it.trackRef) == id }?.let(::like)
    }

    private fun sourceLikeError(error: Exception): String = when ((error as? SourceException)?.code) {
        SourceErrorCode.AUTH_REQUIRED, SourceErrorCode.AUTH_EXPIRED -> "请在音源管理中更新登录状态"
        SourceErrorCode.MISSING_PROVIDER -> "来源未安装或已停用"
        SourceErrorCode.UNSUPPORTED -> "此音源不支持收藏歌曲"
        SourceErrorCode.TIMEOUT -> "收藏请求超时"
        else -> "无法更新歌曲收藏状态"
    }


    private val _qqSong = MutableStateFlow<QQSong?>(null)
    val qqSong: StateFlow<QQSong?> = _qqSong

    fun searchQQSong(keyword: String) {
        lyricManager.searchQQSong(keyword)
    }

    fun selectQQSong(
        metadata: MediaMetadata,
        song: SearchResult.Request.Data.Body.ItemSong,
    ) {
        lyricManager.selectQQSongForLyric(metadata, song)
    }

    fun selectLyricCandidate(metadata: MediaMetadata, candidate: LyricCandidate) {
        lyricManager.selectLyricCandidate(metadata, candidate)
    }

    fun adjustLyricOffset(metadata: MediaMetadata, deltaMs: Int) {
        lyricManager.adjustLyricOffset(metadata, deltaMs)
    }

    fun resetLyricOffset(metadata: MediaMetadata) {
        lyricManager.resetLyricOffset(metadata)
    }

    fun resetLyricSource(metadata: MediaMetadata) {
        lyricManager.resetLyricSource(metadata)
    }

    fun insertSong(song: QQSong) {
        viewModelScope.launch {
            qqSongRepository.insertSong(song)
        }
    }

    fun deleteSongById(id: String) {
        val metadata = mediaMetadata ?: return
        if (metadata.lyricStorageKey() == id) lyricManager.resetLyricSource(metadata)
    }

    suspend fun getQQSongId(metadataId: Long): String? {
        if (metadataId == 0L) return null
        return qqSongRepository.getQQSong(metadataId.toString()).firstOrNull()?.qid
    }

    suspend fun getQQSongId(metadata: MediaMetadata): String? =
        qqSongRepository.getQQSong(metadata.lyricStorageKey()).firstOrNull()?.qid



    fun refreshPlaylistChoices() {
        playlistChoicesJob?.cancel()
        mutableMyPlaylists.value = emptyList()
        playlistChoiceOwner = null
        val providerId = mediaMetadata?.trackRef?.providerId ?: return
        playlistChoicesJob = viewModelScope.launch {
            try {
                sourceRouter.registry.awaitInitialized()
                val owner = sourceRouter.registry.requireProvider(providerId)
                val account = sourceRouter.account(providerId) ?: return@launch
                val revision = (owner as? AccountRevisionCapability)?.accountRevision?.first()
                val choices = mutableListOf<SourcePlaylist>()
                var cursor: String? = null
                var offset = 0
                val seen = mutableSetOf<String>()
                do {
                    val page = sourceRouter.library(providerId, LibraryCollection.CREATED_PLAYLISTS, PageRequest(offset, 100, cursor))
                    choices += page.items.mapNotNull { (it as? SourceItem.PlaylistItem)?.playlist }
                    if (!page.hasMore) break
                    cursor = page.nextCursor ?: throw SourceException(SourceErrorCode.INVALID_RESPONSE)
                    if (!seen.add(cursor) || choices.size > 10_000) throw SourceException(SourceErrorCode.INVALID_RESPONSE)
                    offset += page.items.size
                } while (true)
                ensurePlaylistAccount(owner, revision)
                playlistChoiceOwner = owner
                playlistChoiceAccount = revision
                mutableMyPlaylists.value = choices.distinctBy { it.ref }.map { playlist ->
                    Playlist(id = "source:${providerId.length}:$providerId:${playlist.ref.playlistId}",
                        title = playlist.title, cover = playlist.artworkUrl, author = account.ref.accountId.value,
                        authorName = account.displayName, authorAvatar = account.avatarUrl, count = playlist.trackCount,
                        description = playlist.description, providerId = providerId, providerPlaylistId = playlist.ref.playlistId)
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) { Timber.w(error, "Unable to refresh playlist choices") }
        }
    }

    private suspend fun ensurePlaylistAccount(owner: MusicProvider, revision: String?) {
        if (sourceRouter.registry.provider(owner.id) !== owner ||
            (owner as? AccountRevisionCapability)?.accountRevision?.first() != revision) {
            throw CancellationException("Source account changed while selecting a playlist")
        }
    }

    fun addSongToPlaylist(playlist: Playlist, ref: TrackRef, onResult: (Boolean) -> Unit) {
        val owner = playlistChoiceOwner ?: return
        val revision = playlistChoiceAccount
        if (playlist !in myPlaylists.value || playlist.providerId != ref.providerId || owner.id != ref.providerId) return
        viewModelScope.launch {
            try {
                ensurePlaylistAccount(owner, revision)
                sourceRouter.addTracks(PlaylistRef(playlist.providerId, playlist.providerPlaylistId), listOf(ref))
                ensurePlaylistAccount(owner, revision)
                onResult(true)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { onResult(false) }
        }
    }

    fun createPlaylist(name: String, privacy: Boolean = false, type: String = "NORMAL") {
        val providerId = mediaMetadata?.trackRef?.providerId ?: return
        viewModelScope.launch {
            _createPlaylist.value = Resource.Loading
            try {
                _createPlaylist.value = Resource.Success(sourceRouter.createPlaylist(providerId, name, privacy = privacy, type = type))
                refreshPlaylistChoices()
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { _createPlaylist.value = Resource.Error("创建歌单失败") }
        }
    }

    fun intelligenceList(id: String, playlistId: String, startSongId: String) = startIntelligenceMode(id, playlistId, startSongId)

    fun startIntelligenceMode(id: String, playlistId: String, startSongId: String) {
        if (listOf(id, playlistId, startSongId).any { it.isBlank() }) return
        val ref = mediaMetadata?.trackRef?.let { TrackRef(it.providerId, startSongId) }
            ?: TrackRefCodec.decode(startSongId) ?: return
        intelligenceJob?.cancel()
        intelligenceJob = viewModelScope.launch {
            _songDetail.value = Resource.Loading
            _intelligenceList.value = Resource.Loading
            try {
                val owner = sourceFeatures.bind(ref.providerId, "player-intelligence:${java.util.UUID.randomUUID()}")
                val seed = sourceRouter.getTrack(ref) ?: throw SourceException(SourceErrorCode.NOT_FOUND)
                owner.ensureCurrent()
                _songDetail.value = Resource.Success(seed)
                val result = owner.invoke("discovery", "intelligence", JsonObject().apply {
                    addProperty("id", id); addProperty("playlistId", playlistId); addProperty("startSongId", startSongId)
                })
                val rows = result.takeIf { it.isJsonObject }?.asJsonObject?.getAsJsonArray("tracks")
                    ?: throw SourceException(SourceErrorCode.INVALID_RESPONSE)
                val tracks = rows.map { row -> owner.track(row.asJsonObject) }
                owner.ensureCurrent()
                _intelligenceList.value = Resource.Success(tracks)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { _intelligenceList.value = Resource.Error("获取推荐歌曲失败") }
        }
    }

    fun consumeIntelligencePlayback() {
        _intelligenceList.value = Resource.Loading
        _songDetail.value = Resource.Loading
    }

    fun getSongDetail(id: String) {
        val ref = mediaMetadata?.trackRef?.takeIf { it.trackId == id || TrackRefCodec.mediaId(it) == id }
            ?: TrackRefCodec.decode(id) ?: return
        viewModelScope.launch {
            _songDetail.value = Resource.Loading
            try {
                _songDetail.value = sourceRouter.getTrack(ref)?.let { Resource.Success(it) } ?: Resource.Error("歌曲不存在")
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { _songDetail.value = Resource.Error("获取歌曲失败") }
        }
    }

    fun downloadSong(metadata: MediaMetadata, context: android.content.Context, requestedQuality: MusicQuality? = null) {
        viewModelScope.launch {
            val quality = requestedQuality ?: try {
                val saved = AppContext.instance.dataStore[DownloadQualityKey]
                if (saved != null) com.ljyh.mei.constants.DownloadQuality.valueOf(saved).toMusicQuality()
                else MusicQuality.EXHIGH
            } catch (_: Exception) {
                MusicQuality.EXHIGH
            }

            try {
                val resolution = mediaUriProvider.resolveDownloadSource(metadata.toSourceTrack(), quality.text)
                if (resolution.resource.cachePolicy == CachePolicy.NO_STORE) {
                    android.widget.Toast.makeText(context, "此音源不允许下载", android.widget.Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val downloadPath = AppContext.instance.dataStore[DownloadPathKey]
                    ?: com.ljyh.mei.utils.DownloadManager.getDefaultDownloadPath()
                com.ljyh.mei.utils.DownloadManager.enqueue(
                    context = context,
                    songs = listOf(com.ljyh.mei.playback.SongDownloadInfo(
                        songId = TrackRefCodec.storageKey(metadata.trackRef),
                        url = resolution.resource.uri,
                        songTitle = metadata.title,
                        songArtist = metadata.artists.map { it.name },
                        songAlbum = metadata.album.title,
                        songCover = metadata.coverUrl,
                        duration = metadata.duration,
                        quality = quality.text,
                        originTrackRef = metadata.trackRef,
                        resolution = resolution,
                    )),
                    playlistName = "单曲下载",
                    downloadPath = downloadPath,
                )
                android.widget.Toast.makeText(context, "已添加到下载队列", android.widget.Toast.LENGTH_SHORT).show()
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
                android.widget.Toast.makeText(context, "获取播放源失败", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val moreSortKey = stringPreferencesKey("moreActions.sort")
    private val moreOrderKey = stringPreferencesKey("moreActions.order")
    val moreSortOrder = AppContext.instance.dataStore.data.map { preferences ->
        runCatching { SortOrder.valueOf(preferences[moreSortKey].orEmpty()) }.getOrDefault(SortOrder.FREQUENCY)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SortOrder.FREQUENCY)

    val sortedMoreActions = AppContext.instance.dataStore.data.map { preferences ->
        val order = runCatching { SortOrder.valueOf(preferences[moreSortKey].orEmpty()) }.getOrDefault(SortOrder.FREQUENCY)
        resolveMoreActionOrder(order, preferences[moreOrderKey].orEmpty().split(','))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), resolveMoreActionOrder(SortOrder.FREQUENCY, emptyList()))

    fun setMoreSortOrder(order: SortOrder) {
        viewModelScope.launch { AppContext.instance.dataStore.edit { it[moreSortKey] = order.name } }
    }

    fun saveMoreActionOrder(actions: List<MoreAction>) {
        val ids = resolveMoreActionOrder(SortOrder.CUSTOM, actions.map { it.id }).joinToString(",") { it.id }
        viewModelScope.launch {
            AppContext.instance.dataStore.edit {
                it[moreOrderKey] = ids
                it[moreSortKey] = SortOrder.CUSTOM.name
            }
        }
    }


}
