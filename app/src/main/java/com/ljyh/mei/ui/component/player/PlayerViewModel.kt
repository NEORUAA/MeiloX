package com.ljyh.mei.ui.component.player

import android.util.Log
import androidx.compose.ui.graphics.Color
import androidx.datastore.dataStore
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ljyh.mei.AppContext
import com.ljyh.mei.constants.CookieKey
import com.ljyh.mei.constants.DownloadPathKey
import com.ljyh.mei.constants.DownloadQualityKey
import com.ljyh.mei.constants.MusicQuality
import com.ljyh.mei.constants.UserIdKey
import com.ljyh.mei.data.model.Lyric
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.Tracks
import com.ljyh.mei.data.model.UserPlaylist
import com.ljyh.mei.data.model.api.CreatePlaylistResult
import com.ljyh.mei.data.model.api.Intelligence
import com.ljyh.mei.data.model.qq.u.SearchResult
import com.ljyh.mei.data.model.room.Playlist
import com.ljyh.mei.data.model.room.QQSong
import com.ljyh.mei.data.model.weapi.Radio
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.data.network.neteaseSessionFingerprint
import com.ljyh.mei.data.repository.PlayerRepository
import com.ljyh.mei.data.repository.LibraryCollectionsRepository
import com.ljyh.mei.data.repository.PlaylistRepository
import com.ljyh.mei.data.repository.UserRepository
import com.ljyh.mei.di.repository.LocalPlaylistRepository
import com.ljyh.mei.di.repository.observeOwnedPlaylists
import com.ljyh.mei.di.repository.toLocalPlaylist
import com.ljyh.mei.di.repository.ColorRepository
import com.ljyh.mei.di.repository.LikeRepository
import com.ljyh.mei.di.repository.QQSongRepository
import com.ljyh.mei.playback.MediaUriProvider
import com.ljyh.mei.ui.model.LyricCandidate
import com.ljyh.mei.ui.model.LyricData
import com.ljyh.mei.ui.model.MoreAction
import com.ljyh.mei.ui.model.SortOrder
import com.ljyh.mei.ui.model.resolveMoreActionOrder
import com.ljyh.mei.utils.dataStore
import com.ljyh.mei.utils.get
import com.ljyh.mei.utils.lyric.LyricManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val repository: PlayerRepository,
    private val qqSongRepository: QQSongRepository,
    private val userRepository: UserRepository,
    private val localPlaylistRepository: LocalPlaylistRepository,
    private val playlistRepository: PlaylistRepository,
    private val likeRepository: LikeRepository,
    private val colorRepository: ColorRepository,
    val lyricManager: LyricManager,
    private val mediaUriProvider: MediaUriProvider,
    private val collections: LibraryCollectionsRepository,
) : ViewModel() {
    val searchResult: StateFlow<Resource<SearchResult>> = lyricManager.qqSearchResult
    val lyric: StateFlow<LyricData> = lyricManager.lyricData
    val lyricCandidates: StateFlow<List<LyricCandidate>> = lyricManager.lyricCandidates
    val lyricOffsetMs: StateFlow<Int> = lyricManager.lyricOffsetMs
    val songLikes = repository.songLikes
    val songLikeGeneration = repository.songLikeGeneration
    val songLikeAccount = collections.accounts.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = null,
    )

    suspend fun ensureSongLikes(ids: Set<Long>, expectedSession: String): Resource<Unit> =
        repository.ensureSongLikes(ids, expectedSession)

    private val _like = MutableStateFlow<Resource<Boolean>>(Resource.Loading)
    val like: StateFlow<Resource<Boolean>> = _like
    private var selectedLikeId: String? = null
    private var likeSelectionGeneration = 0L
    private var likeStatusJob: Job? = null

    init {
        viewModelScope.launch {
            repository.songLikes.collect { likes ->
                _like.value = likes[selectedLikeId]?.let { Resource.Success(it) } ?: Resource.Loading
            }
        }
    }

    private var playlistChoicesJob: Job? = null

    private val _createPlaylist = MutableStateFlow<Resource<CreatePlaylistResult>>(Resource.Loading)
    val createPlaylist: StateFlow<Resource<CreatePlaylistResult>> = _createPlaylist


    private val _intelligenceList = MutableStateFlow<Resource<Intelligence>>(Resource.Loading)
    val intelligenceList: StateFlow<Resource<Intelligence>> = _intelligenceList


    private val _songDetail = MutableStateFlow<Resource<Tracks>>(Resource.Loading)
    val songDetail: StateFlow<Resource<Tracks>> = _songDetail

    private var intelligenceJob: Job? = null


    var mediaMetadata: MediaMetadata? = null

    suspend fun getAvailableDownloadQualities(mediaId: Long): List<MusicQuality> =
        withContext(Dispatchers.IO) {
            mediaUriProvider.getAvailableDownloadQualities(mediaId.toString())
        }

    val localPlaylists: StateFlow<List<Playlist>> = localPlaylistRepository.getAllPlaylist()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = emptyList()
        )

    val myPlaylists: StateFlow<List<Playlist>> = observeOwnedPlaylists(
        localPlaylistRepository.getAllPlaylist(), collections.accounts,
    )
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = emptyList()
        )

    fun getLike(id: Long) {
        val expectedSession = neteaseSessionFingerprint(AppContext.instance.dataStore[CookieKey].orEmpty())
        val key = id.toString()
        selectedLikeId = key
        val generation = ++likeSelectionGeneration
        _like.value = repository.songLikes.value[key]?.let { Resource.Success(it) } ?: Resource.Loading
        likeStatusJob?.cancel()
        likeStatusJob = viewModelScope.launch {
            val result = repository.checkSongLike(id, expectedSession)
            if (selectedLikeId == key && likeSelectionGeneration == generation) {
                _like.value = repository.songLikes.value[key]?.let { Resource.Success(it) } ?: result
            }
        }
    }

    fun like(id: String) {
        val expectedSession = neteaseSessionFingerprint(AppContext.instance.dataStore[CookieKey].orEmpty())
        viewModelScope.launch {
            val result = repository.toggleLike(id, expectedSession)
            if (result is Resource.Error) {
                Timber.tag("PlayerViewModel").w("Unable to toggle song like: %s", result.message)
            }
        }
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
        if (metadata.id.toString() == id) lyricManager.resetLyricSource(metadata)
    }

    suspend fun getQQSongId(metadataId: Long): String? {
        return qqSongRepository.getQQSong(metadataId.toString()).firstOrNull()?.qid
    }



    fun refreshPlaylistChoices() {
        playlistChoicesJob?.cancel()
        playlistChoicesJob = viewModelScope.launch {
            collections.accounts.collectLatest { account ->
                if (account == null) return@collectLatest
                try {
                    val playlists = collections.allPlaylists(account)
                    if (collections.accounts.first() == account) {
                        localPlaylistRepository.mergeRemotePlaylists(playlists.map { it.toLocalPlaylist() })
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Timber.w(error, "Unable to refresh playlist choices")
                }
            }
        }
    }

    fun createPlaylist(
        name: String,
        privacy: Boolean = false, // 0 普通歌单, 10 隐私歌单
        type: String = "NORMAL" // 默认 NORMAL, VIDEO 视频歌单, SHARED 共享歌单
    ) {
        viewModelScope.launch {
            _createPlaylist.value = Resource.Loading
            _createPlaylist.value = playlistRepository.createPlaylist(name, privacy, type)
        }
    }

    fun intelligenceList(id: String, playlistId: String, startSongId: String) {
        startIntelligenceMode(id, playlistId, startSongId)
    }

    fun startIntelligenceMode(id: String, playlistId: String, startSongId: String) {
        intelligenceJob?.cancel()
        intelligenceJob = viewModelScope.launch {
            _songDetail.value = Resource.Loading
            _intelligenceList.value = Resource.Loading

            // Fetch the seed song before publishing the list result so the UI can build one
            // complete queue instead of reacting to two independently completing requests.
            val songDetailResult = repository.getSongDetail(startSongId)
            currentCoroutineContext().ensureActive()
            _songDetail.value = songDetailResult

            val intelligenceListResult =
                repository.getIntelligenceList(id, playlistId, startSongId)
            currentCoroutineContext().ensureActive()
            _intelligenceList.value = intelligenceListResult
        }
    }

    fun consumeIntelligencePlayback() {
        _intelligenceList.value = Resource.Loading
        _songDetail.value = Resource.Loading
    }

    fun getSongDetail(id:String){
        viewModelScope.launch {
            _songDetail.value = Resource.Loading
            val result = repository.getSongDetail(id)
            Timber.tag("songDetail").d("getSongDetail: $result")
            _songDetail.value = result
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

            val result = playlistRepository.getSongUrlV1(
                ids = listOf(metadata.id.toString()),
                quality = quality
            )

            if (result is Resource.Success) {
                val songData = result.data.fullSourceFor(metadata.id.toString())
                val url = songData?.url
                if (url != null) {
                    val downloadPath = AppContext.instance.dataStore[DownloadPathKey]
                        ?: com.ljyh.mei.utils.DownloadManager.getDefaultDownloadPath()

                    com.ljyh.mei.utils.DownloadManager.enqueue(
                        context = context,
                        songs = listOf(
                            com.ljyh.mei.playback.SongDownloadInfo(
                                songId = metadata.id.toString(),
                                url = url,
                                songTitle = metadata.title,
                                songArtist = metadata.artists.map { it.name },
                                songAlbum = metadata.album.title,
                                songCover = metadata.coverUrl,
                                duration = metadata.duration,
                                fileType = songData.encodeType,
                                quality = songData.level,
                            )
                        ),
                        playlistName = "单曲下载",
                        downloadPath = downloadPath
                    )
                    android.widget.Toast.makeText(context, "已添加到下载队列", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    android.widget.Toast.makeText(context, "无法获取歌曲链接", android.widget.Toast.LENGTH_SHORT).show()
                }
            } else {
                android.widget.Toast.makeText(context, "获取链接失败", android.widget.Toast.LENGTH_SHORT).show()
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
