package com.ljyh.mei.ui.component.player

import android.util.Log
import androidx.compose.ui.graphics.Color
import androidx.datastore.dataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ljyh.mei.AppContext
import com.ljyh.mei.constants.DownloadPathKey
import com.ljyh.mei.constants.DownloadQualityKey
import com.ljyh.mei.constants.MusicQuality
import com.ljyh.mei.constants.UserIdKey
import com.ljyh.mei.data.model.Lyric
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.Tracks
import com.ljyh.mei.data.model.api.Intelligence
import com.ljyh.mei.data.model.qq.u.SearchResult
import com.ljyh.mei.data.model.room.QQSong
import com.ljyh.mei.data.model.weapi.Radio
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.data.repository.PlayerRepository
import com.ljyh.mei.data.repository.PlaylistRepository
import com.ljyh.mei.di.repository.ColorRepository
import com.ljyh.mei.di.repository.LikeRepository
import com.ljyh.mei.di.repository.QQSongRepository
import com.ljyh.mei.ui.model.LyricData
import com.ljyh.mei.ui.model.MoreAction
import com.ljyh.mei.ui.model.SortOrder
import com.ljyh.mei.utils.dataStore
import com.ljyh.mei.utils.get
import com.ljyh.mei.utils.lyric.LyricManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

class PlayerViewModel @Inject constructor(
    private val repository: PlayerRepository,
    private val qqSongRepository: QQSongRepository,
    private val playlistRepository: PlaylistRepository,
    private val likeRepository: LikeRepository,
    private val colorRepository: ColorRepository,
    val lyricManager: LyricManager
) : ViewModel() {
    val searchResult: StateFlow<Resource<SearchResult>> = lyricManager.qqSearchResult
    val lyric: StateFlow<LyricData> = lyricManager.lyricData

    private val _like = MutableStateFlow<Resource<Boolean>>(Resource.Loading)
    val like: StateFlow<Resource<Boolean>> = _like


    private val _intelligenceList = MutableStateFlow<Resource<Intelligence>>(Resource.Loading)
    val intelligenceList: StateFlow<Resource<Intelligence>> = _intelligenceList


    private val _songDetail = MutableStateFlow<Resource<Tracks>>(Resource.Loading)
    val songDetail: StateFlow<Resource<Tracks>> = _songDetail

    private var intelligenceJob: Job? = null


    var mediaMetadata: MediaMetadata? = null

    val userId = AppContext.instance.dataStore[UserIdKey] ?: ""

    // 获取点赞状态
    fun getLike(id: Long) {
        viewModelScope.launch {
            Timber.tag("PlayerViewModel").d("get like $id")
            _like.value = repository.checkSongLike(id)
        }
    }

    // 切换点赞状态
    fun like(id: String) {
        viewModelScope.launch {
            try {
                val currentLiked = (_like.value as? Resource.Success)?.data == true
                repository.like(id, !currentLiked)
                _like.value = Resource.Success(!currentLiked)
            } catch (e: Exception) {
                e.printStackTrace()
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

    fun insertSong(song: QQSong) {
        viewModelScope.launch {
            qqSongRepository.insertSong(song)
        }
    }

    fun deleteSongById(id: String) {
        viewModelScope.launch {
            qqSongRepository.deleteSongById(id)
            lyricManager.loadLyrics(mediaMetadata ?: return@launch, forceReload = true)
        }
    }

    suspend fun getQQSongId(metadataId: Long): String? {
        return qqSongRepository.getQQSong(metadataId.toString()).firstOrNull()?.qid
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

    private val _moreSortOrder = MutableStateFlow(SortOrder.FREQUENCY)
    val moreSortOrder = _moreSortOrder.asStateFlow()
    val sortedMoreActions: StateFlow<List<MoreAction>> =
        _moreSortOrder
            .map { sortOrder ->
                val actions = MoreAction.entries.toMutableList()
                sortMoreActions(actions, sortOrder)
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = MoreAction.entries.toList()
            )



    fun setMoreSortOrder(order: SortOrder) {
        _moreSortOrder.value = order
    }

    // 排序函数
    private fun sortMoreActions(actions: List<MoreAction>, order: SortOrder): List<MoreAction> {
        return when (order) {
            SortOrder.FREQUENCY -> actions.sortedByDescending { it.frequency }
            SortOrder.RISK -> actions.sortedBy { it.riskLevel }
        }
    }


}
