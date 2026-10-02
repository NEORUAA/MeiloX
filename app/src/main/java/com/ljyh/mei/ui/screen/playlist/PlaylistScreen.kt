package com.ljyh.mei.ui.screen.playlist

import com.ljyh.mei.constants.MusicQuality

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import androidx.paging.compose.collectAsLazyPagingItems
import com.ljyh.mei.constants.DownloadPathKey
import com.ljyh.mei.constants.DownloadQuality
import com.ljyh.mei.constants.DownloadQualityKey
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.PlaylistDetail
import com.ljyh.mei.data.model.toMediaItem
import com.ljyh.mei.data.model.toMediaMetadata
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.playback.SongDownloadInfo
import com.ljyh.mei.playback.queue.ListQueue
import com.ljyh.mei.ui.component.DownloadConfirmDialog
import com.ljyh.mei.ui.local.LocalNavController
import com.ljyh.mei.ui.local.LocalPlayerConnection
import com.ljyh.mei.ui.model.UiPlaylist
import com.ljyh.mei.ui.screen.Screen
import com.ljyh.mei.utils.DownloadManager
import com.ljyh.mei.utils.rememberEnumPreference
import com.ljyh.mei.utils.rememberPreference
import kotlinx.coroutines.launch

@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistScreen(
    id: Long,
    viewModel: PlaylistViewModel = viewModel()
) {
    // 1. 初始化数据请求
    LaunchedEffect(key1 = id) {
        viewModel.getPlaylistDetail(id.toString())
    }
    val session by viewModel.detailSession.collectAsState()
    key(id, session) { PlaylistContent(id, session, viewModel) }
}

@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaylistContent(id: Long, session: com.ljyh.mei.data.session.SessionStamp?, viewModel: PlaylistViewModel) {
    val context = LocalContext.current
    val navController = LocalNavController.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val scope = rememberCoroutineScope()
    val selection = rememberDetailSelection(id)

    // 2. 状态收集
    val userId = session?.identity?.takeIf { it.authenticated }?.userId?.toString().orEmpty()
    val playlistDetail by viewModel.playlistDetail.collectAsState()
    val removedTrackIds by viewModel.removedTrackIds.collectAsState()
    val subscriberState by viewModel.subscribePlaylist.collectAsState()
    val unSubscriberState by viewModel.unSubscribePlaylist.collectAsState()

    // 3. Paging 数据
    var isPlaylistSearchActive by remember { mutableStateOf(false) }
    var playlistSearchQuery by remember { mutableStateOf("") }
    val pagingFlow = remember(id, playlistDetail, playlistSearchQuery, removedTrackIds) {
        viewModel.searchPlaylistTracks(playlistDetail, playlistSearchQuery, removedTrackIds)
    }
    val lazyPagingItems = pagingFlow.collectAsLazyPagingItems()

    // Download dialog state
    var showDownloadDialog by remember { mutableStateOf(false) }
    var pendingDownloadTracks by remember { mutableStateOf<List<MediaMetadata>>(emptyList()) }
    var pendingDownloadDetail by remember { mutableStateOf<Resource<PlaylistDetail>?>(null) }
    var isPreparingDownload by remember { mutableStateOf(false) }

    val (downloadPath) = rememberPreference(DownloadPathKey, DownloadManager.getDefaultDownloadPath())
    val (downloadQuality) = rememberEnumPreference(DownloadQualityKey, DownloadQuality.EXHIGH)

    // 4. 管理收藏状态 (乐观更新核心)
    // 默认 false，等待数据加载后同步
    val collection by viewModel.collected.collectAsState()
    val isSubscribed = collection == true

    LaunchedEffect(subscriberState) {
        when(val result=subscriberState){
            is Resource.Success ->{
                if(result.data.code!=200){
                    Toast.makeText(context, "收藏失败: 错误码:${result.data.code}", Toast.LENGTH_SHORT).show()
                }else{
                    Toast.makeText(context, "收藏成功", Toast.LENGTH_SHORT).show()

                }
            }
            is Resource.Error->{
                Toast.makeText(context, "收藏失败: ${(subscriberState as Resource.Error).message}", Toast.LENGTH_SHORT).show()
            }
            else -> {}
        }
        if (subscriberState !is Resource.Loading) viewModel.consumeCollectionResult(subscriberState, true)
    }

    LaunchedEffect(unSubscriberState) {
        when(val result=unSubscriberState){
            is Resource.Success ->{
                if(result.data.code!=200){
                    Toast.makeText(context, "取消收藏失败: 错误码:${result.data.code}", Toast.LENGTH_SHORT).show()
                }else{
                    Toast.makeText(context, "取消收藏成功", Toast.LENGTH_SHORT).show()
                }
            }
            is Resource.Error->{
                Toast.makeText(context, "取消收藏失败: ${result.message}", Toast.LENGTH_SHORT).show()
            }
            else -> {}
        }
        if (unSubscriberState !is Resource.Loading) viewModel.consumeCollectionResult(unSubscriberState, false)
    }

    // 5. 构建 UI 模型 (移除副作用和内部状态修改)
    val uiData = remember(playlistDetail, userId, removedTrackIds) {
        if (playlistDetail is Resource.Success) {
            val data = (playlistDetail as Resource.Success).data.playlist
            val visibleTrackCount = (data.trackCount - removedTrackIds.size).coerceAtLeast(0)
            UiPlaylist(
                id = data.Id,
                title = data.name,
                count = visibleTrackCount,
                subscriberCount = data.subscribedCount,
                cover = data.coverImgUrl,
                coverList = data.tracks.take(6).map { it.al.picUrl },
                creatorName = data.creator.nickname,
                isCreator = data.creator.userId.toString() == userId,
                description = data.description,
                tracks = data.tracks
                    .map { it.toMediaMetadata() }
                    .filterNot { it.id in removedTrackIds },
                trackCount = visibleTrackCount,
                playCount = data.playCount,
                isSubscribed = data.subscribed // 注意：这里仅用于 UI 初始化，后续由 isSubscribed 状态变量控制
            )
        } else {
            UiPlaylist(
                id = 0L, title = "", cover = "", coverList = emptyList(), creatorName = "", tracks = emptyList(),
                count = 0, subscriberCount = 0, isCreator = false, description = "",
                trackCount = 0, playCount = 0, isSubscribed = false
            )
        }
    }

    // 6. 提取构建播放队列的逻辑 (避免重复代码)
    fun buildListQueue(startTrackId: Long? = null, randomStart: Boolean = false): ListQueue? {
        val owner = session ?: return null
        if (runCatching { viewModel.requireDetail(owner, playlistDetail) }.isFailure) return null
        val detail = playlistDetail
        if (detail is Resource.Success) {
            val playlist = detail.data.playlist
            // 优化：在此处构建 map 可能会比较耗时，如果列表很大，建议放到 ViewModel 或 IO 线程处理
            // 但对于点击事件，直接处理通常也能接受
            val mediaItemsMap = playlist.tracks.associate {
                it.id.toString() to it.toMediaMetadata().toMediaItem()
            }
            // 保持原始顺序
            val allPairs = playlist.trackIds
                .filterNot { it.id in removedTrackIds }
                .map { trackId ->
                    val tid = trackId.id.toString()
                    Pair(tid, mediaItemsMap[tid])
                }

            return ListQueue(
                id = "playlist_${uiData.id}",
                title = uiData.title,
                items = allPairs,
                playlistSource = com.ljyh.mei.playback.queue.PlaylistQueueSource(uiData.id),
                startIndex = if (randomStart && allPairs.isNotEmpty()) {
                    allPairs.indices.random()
                } else {
                    startTrackId
                        ?.let { trackId -> allPairs.indexOfFirst { it.first == trackId.toString() } }
                        ?.takeIf { it >= 0 }
                        ?: 0
                },
            )
        }
        return null
    }

    // 7. 下载处理逻辑
    fun doBulkDownload(
        allTracks: List<MediaMetadata>,
        quality: MusicQuality = downloadQuality.toMusicQuality(),
        detail: Resource<PlaylistDetail> = playlistDetail,
    ) {
        val owner = session ?: return
        scope.launch {
            try {
                viewModel.requireDetail(owner, detail)
                val downloadInfos = allTracks.map { track ->
                    SongDownloadInfo(
                        songId = track.id.toString(),
                        songTitle = track.title,
                        songArtist = track.artists.map { it.name },
                        songAlbum = track.album.title,
                        songCover = track.coverUrl,
                        duration = track.duration,
                        quality = quality.text,
                    )
                }

                if (downloadInfos.isEmpty()) {
                    Toast.makeText(context, "无法获取歌曲链接，请稍后重试", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val playlistName = if (detail is Resource.Success) {
                    detail.data.playlist.name
                } else {
                    "未分类"
                }

                viewModel.requireDetail(owner, detail)
                DownloadManager.enqueue(
                    context = context,
                    owner = owner,
                    songs = downloadInfos,
                    playlistName = playlistName,
                    playlistId = id.toString(),
                    downloadPath = downloadPath
                )
                Toast.makeText(context, "已添加 ${downloadInfos.size} 首到下载队列", Toast.LENGTH_SHORT).show()
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                Toast.makeText(context, com.ljyh.mei.utils.downloadEnqueueError(error), Toast.LENGTH_SHORT).show()
            }
        }
    }

    suspend fun loadTracks(
        selectedIds: Set<String>? = null,
        detail: Resource<PlaylistDetail> = playlistDetail,
    ): List<MediaMetadata> {
        val owner = session ?: return emptyList()
        viewModel.requireDetail(owner, detail)
        val playlist = (detail as? Resource.Success)?.data?.playlist ?: return emptyList()
        val ids = playlist.trackIds.map { it.id }.filterNot { it in removedTrackIds }
            .filter { selectedIds == null || it.toString() in selectedIds }
        val known = playlist.tracks.associate { it.id to it.toMediaMetadata() }.toMutableMap()
        ids.filterNot(known::containsKey).chunked(200).forEach { chunk ->
            viewModel.getSongDetails(chunk.map(Long::toString), owner).songs.forEach { known[it.id] = it.toMediaMetadata() }
        }
        viewModel.requireDetail(owner, detail)
        check(ids.all(known::containsKey)) { "Incomplete playlist details" }
        return ids.map { known.getValue(it) }
    }

    fun prepareDownload(quality: MusicQuality? = null, ids: Set<String>? = null) {
        if (isPreparingDownload) return
        val detail = playlistDetail
        isPreparingDownload = true
        scope.launch {
            try {
                val tracks = loadTracks(ids, detail)
                if (tracks.isNotEmpty()) {
                    if (quality != null) doBulkDownload(tracks, quality, detail)
                    else {
                        pendingDownloadTracks = tracks
                        pendingDownloadDetail = detail
                        showDownloadDialog = true
                    }
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (_: Exception) {
                Toast.makeText(context, com.ljyh.mei.R.string.load_failed, Toast.LENGTH_SHORT).show()
            } finally { isPreparingDownload = false }
        }
    }

    fun toggleSubscription() {
        if (uiData.isCreator) {
            Toast.makeText(context, "不能收藏自己创建的歌单", Toast.LENGTH_SHORT).show()
            return
        }
        if (isSubscribed) viewModel.unsubscribePlaylist(id.toString())
        else viewModel.subscribePlaylist(id.toString())
    }

    DetailSelectionToolbar(
        selection = selection,
        onSelectAll = {
            loadTracks().filter { it.matchesPlaylistSearch(playlistSearchQuery) }.map { it.id.toString() }.toSet()
        },
        onDownload = { prepareDownload(ids = selection.ids) },
    )
    val menu = detailMenuItems(
        downloadTitle = androidx.compose.ui.res.stringResource(com.ljyh.mei.R.string.album_download_all, uiData.count),
        subscriptionTitle = androidx.compose.ui.res.stringResource(
            if (isSubscribed) com.ljyh.mei.R.string.detail_playlist_unsubscribe else com.ljyh.mei.R.string.detail_playlist_subscribe),
        subscribed = isSubscribed,
        onDownload = { prepareDownload(quality = it) },
        onSelect = selection::start,
        onSubscribe = ::toggleSubscription,
        onRefresh = { selection.finish(); viewModel.getPlaylistDetail(id.toString()) },
    )

    if (showDownloadDialog) {
        DownloadConfirmDialog(
            currentQuality = downloadQuality,
            downloadPath = downloadPath,
            onDismiss = { showDownloadDialog = false },
            onConfirm = {
                showDownloadDialog = false
                pendingDownloadDetail?.let { doBulkDownload(pendingDownloadTracks, detail = it) }
            },
            onGoToSettings = {
                showDownloadDialog = false
                Screen.DownloadSettings.navigate(navController)
            },
            onGoToDownloadManage = {
                showDownloadDialog = false
                Screen.DownloadManage.navigate(navController)
            }
        )
    }

    // 8. UI 渲染
    if (playlistDetail is Resource.Error) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Error: ${(playlistDetail as Resource.Error).message}")
                com.ljyh.mei.ui.glass.GlassButton(onClick = { viewModel.getPlaylistDetail(id.toString()) }) {
                    Text(androidx.compose.ui.res.stringResource(com.ljyh.mei.R.string.retry))
                }
            }
        }
    } else {
        CommonSongListScreen(
            uiData = uiData,
            pagingItems = lazyPagingItems,
            isLoading = playlistDetail is Resource.Loading,

            // 收藏按钮逻辑
            headerActionIcon = if (isSubscribed) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
            headerActionLabel = if (isSubscribed) "取消收藏" else "收藏",
            isSubscribed = isSubscribed,
            onHeaderAction = ::toggleSubscription,
            detailMenu = menu,
            detailMenuTitle = androidx.compose.ui.res.stringResource(com.ljyh.mei.R.string.detail_playlist_menu),
            selectionMode = selection.active,
            selectedTrackIds = selection.ids,
            onSelectionDone = selection::finish,

            onTrackDownload = { track, quality -> doBulkDownload(listOf(track), quality) },

            // 播放全部
            onPlayAll = {
                buildListQueue()?.let { queue ->
                    playerConnection.playQueue(queue, expectedSession = session)
                }
            },
            onShufflePlay = {
                buildListQueue(randomStart = true)?.let { queue ->
                    playerConnection.playQueue(queue, shuffle = true, expectedSession = session)
                }
            },

            // 点击单曲播放
            onTrackClick = { mediaMetadata, index ->
                if (selection.active) selection.toggle(mediaMetadata.id.toString())
                else if (session != null) playerConnection.onTrackClicked(
                    trackId = mediaMetadata.id.toString(),
                    expectedSession = session,
                    buildQueue = {
                        buildListQueue(mediaMetadata.id)
                    }
                )
            },

            playlistSearchQuery = playlistSearchQuery,
            isPlaylistSearchActive = isPlaylistSearchActive,
            onPlaylistSearchQueryChange = { playlistSearchQuery = it },
            onPlaylistSearchActiveChange = { active ->
                isPlaylistSearchActive = active
                if (!active) playlistSearchQuery = ""
            },
            onBack = { navController.popBackStack() }
        )
    }
}
