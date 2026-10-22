package com.ljyh.mei.ui.screen.album

import com.ljyh.mei.constants.MusicQuality
import com.ljyh.mei.constants.DetailPageHorizontalPadding

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.media3.common.util.UnstableApi
import com.ljyh.mei.constants.DownloadPathKey
import com.ljyh.mei.constants.DownloadQuality
import com.ljyh.mei.constants.DownloadQualityKey
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.toMediaItem
import com.ljyh.mei.data.model.toMediaMetadata
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.playback.SongDownloadInfo
import com.ljyh.mei.playback.queue.ListQueue
import com.ljyh.mei.ui.component.DownloadConfirmDialog
import com.ljyh.mei.ui.glass.GlassButton
import com.ljyh.mei.ui.glass.IosPinnedListPage
import com.ljyh.mei.ui.glass.LocalGlassColors
import com.ljyh.mei.ui.local.LocalNavController
import com.ljyh.mei.ui.local.LocalPlayerAwareWindowInsets
import com.ljyh.mei.ui.local.LocalPlayerConnection
import com.ljyh.mei.ui.model.UiPlaylist
import com.ljyh.mei.ui.screen.Screen
import com.ljyh.mei.ui.screen.playlist.CommonSongListScreen
import com.ljyh.mei.ui.screen.playlist.matchesPlaylistSearch
import com.ljyh.mei.utils.DownloadManager
import com.ljyh.mei.utils.rememberEnumPreference
import com.ljyh.mei.utils.rememberPreference
import kotlinx.coroutines.launch

@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumDetailScreen(
    id: Long,
    viewModel: AlbumDetailViewModel = hiltViewModel(),
) {
    // 1. 请求初始数据
    LaunchedEffect(id) {
        viewModel.getAlbumDetail(id.toString())
        viewModel.refreshSubscription(id)
    }

    val context = LocalContext.current
    val navController = LocalNavController.current
    val playerConnection = LocalPlayerConnection.current ?: return

    val albumDetail by viewModel.albumDetail.collectAsState()
    val subscription by viewModel.subscription.collectAsState()
    val isSubscribed = subscription.subscribed == true
    val cookie by rememberPreference(com.ljyh.mei.constants.CookieKey, "")
    LaunchedEffect(subscription.error) {
        val message = when (subscription.error) {
            AlbumSubscriptionError.Load -> com.ljyh.mei.R.string.album_subscription_load_failed
            AlbumSubscriptionError.Update -> com.ljyh.mei.R.string.album_subscription_update_failed
            null -> null
        }
        message?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }

    // 4. 构建 UI 数据模型
    val uiData = remember(albumDetail, isSubscribed) {
        if (albumDetail is Resource.Success) {
            val album = (albumDetail as Resource.Success).data.album
            val songs = (albumDetail as Resource.Success).data.songs
            UiPlaylist(
                id = album.id,
                title = album.name,
                count = album.size,
                subscriberCount = -1, // 专辑通常没有订阅人数，或者在 dynamicInfo 中
                cover = album.picUrl,
                coverList = listOf(album.picUrl),
                creatorName = album.artists.joinToString(", ") { it.name },
                isCreator = false,
                description = album.description,
                tracks = songs.map {
                    it.toMediaMetadata().copy(
                        coverUrl = album.picUrl,
                        album = MediaMetadata.Album(album.id, album.name),
                    )
                },
                playCount = -1,
                isSubscribed = isSubscribed
            )
        } else {
            UiPlaylist(
                id = 0L, title = "", cover = "", coverList = emptyList(), creatorName = "", tracks = emptyList(),
                count = 0, subscriberCount = 0, isCreator = false, description = "",
                trackCount = 0, playCount = 0, isSubscribed = false
            )
        }
    }

    var selectionMode by remember(id) { mutableStateOf(false) }
    var selectedIds by remember(id) { mutableStateOf<Set<String>>(emptySet()) }
    val selectionToolbar = com.ljyh.mei.ui.local.LocalSelectionToolbar.current
    androidx.activity.compose.BackHandler(selectionMode) {
        selectionMode = false
        selectedIds = emptySet()
    }
    androidx.compose.runtime.DisposableEffect(selectionToolbar) {
        onDispose { selectionToolbar.content.value = null }
    }
    var isAlbumSearchActive by remember { mutableStateOf(false) }
    var albumSearchQuery by remember { mutableStateOf("") }
    val trackNumbers = remember(uiData.tracks) {
        uiData.tracks.mapIndexed { index, track -> track.id to index + 1 }.toMap()
    }
    val displayedUiData = remember(uiData, albumSearchQuery) {
        if (albumSearchQuery.isBlank()) uiData else uiData.copy(
            tracks = uiData.tracks.filter { it.matchesPlaylistSearch(albumSearchQuery) }
        )
    }

    val scope = rememberCoroutineScope()

    // Download dialog state
    var showDownloadDialog by remember { mutableStateOf(false) }
    var pendingDownloadTracks by remember { mutableStateOf<List<MediaMetadata>>(emptyList()) }

    val (downloadPath) = rememberPreference(DownloadPathKey, DownloadManager.getDefaultDownloadPath())
    val (downloadQuality) = rememberEnumPreference(DownloadQualityKey, DownloadQuality.EXHIGH)

    // 5. 下载处理逻辑
    fun doDownload(tracks: List<MediaMetadata>, quality: MusicQuality = downloadQuality.toMusicQuality()) {
        scope.launch {
            val songIds = tracks.map { it.id.toString() }
            val result = viewModel.resolveSongUrls(songIds, quality)
            val sourceMap = if (result is Resource.Success) {
                result.data.fullSourcesFor(songIds.toSet()).associateBy { it.id.toString() }
            } else emptyMap()

            val downloadInfos = tracks.mapNotNull { track ->
                val source = sourceMap[track.id.toString()] ?: return@mapNotNull null
                val url = source.url ?: return@mapNotNull null
                SongDownloadInfo(
                    songId = track.id.toString(),
                    url = url,
                    songTitle = track.title,
                    songArtist = track.artists.map { it.name },
                    songAlbum = track.album.title,
                    songCover = track.coverUrl,
                    duration = track.duration,
                    fileType = source.encodeType,
                    quality = source.level,
                )
            }

            if (downloadInfos.isEmpty()) {
                Toast.makeText(context, "无法获取歌曲链接，请稍后重试", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val detail = albumDetail
            val playlistName = if (detail is Resource.Success) {
                detail.data.album.name
            } else {
                "未分类"
            }

            DownloadManager.enqueue(
                context = context,
                songs = downloadInfos,
                playlistName = playlistName,
                playlistId = id.toString(),
                downloadPath = downloadPath
            )
            Toast.makeText(context, "已添加 ${downloadInfos.size} 首到下载队列", Toast.LENGTH_SHORT).show()
        }
    }

    fun toggleSubscription() {
        if (cookie.isBlank()) Screen.NeteaseLogin.navigate(navController)
        else viewModel.toggleSubscription(id)
    }
    val subscriptionLabel = androidx.compose.ui.res.stringResource(
        when {
            subscription.isUpdating -> com.ljyh.mei.R.string.album_subscription_updating
            subscription.error == AlbumSubscriptionError.Load -> com.ljyh.mei.R.string.retry
            isSubscribed -> com.ljyh.mei.R.string.album_unsubscribe
            else -> com.ljyh.mei.R.string.album_subscribe
        },
    )
    val detailMenu = listOf(
        com.ljyh.mei.ui.glass.IosCascadingMenuItem(
            androidx.compose.ui.res.stringResource(com.ljyh.mei.R.string.album_download_all, uiData.tracks.size),
            "arrow.down.circle",
            children = MusicQuality.entries.map { quality ->
                com.ljyh.mei.ui.glass.IosCascadingMenuItem(androidx.compose.ui.res.stringResource(quality.labelRes), onClick = { doDownload(uiData.tracks, quality) })
            },
        ),
        com.ljyh.mei.ui.glass.IosCascadingMenuItem(
            androidx.compose.ui.res.stringResource(com.ljyh.mei.R.string.album_multi_select), "checklist",
            onClick = { selectedIds = emptySet(); selectionMode = true },
        ),
        com.ljyh.mei.ui.glass.IosCascadingMenuItem(
            subscriptionLabel,
            if (isSubscribed) "checkmark" else "plus", separatorBefore = true,
            enabled = !subscription.isLoading && !subscription.isUpdating,
            onClick = ::toggleSubscription,
        ),
        com.ljyh.mei.ui.glass.IosCascadingMenuItem(
            androidx.compose.ui.res.stringResource(com.ljyh.mei.R.string.album_refresh), "arrow.clockwise",
            onClick = { viewModel.getAlbumDetail(id.toString()); viewModel.refreshSubscription(id) },
        ),
    )
    androidx.compose.runtime.DisposableEffect(selectionMode, selectedIds, displayedUiData, downloadPath, downloadQuality) {
        selectionToolbar.content.value = if (!selectionMode) null else {
            {
                androidx.compose.foundation.layout.Row(
                    Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
                ) {
                    com.ljyh.mei.ui.glass.GlassIconButton(style = com.ljyh.mei.ui.glass.GlassSurfaceStyle.Navigation, onClick = {
                        val visibleIds = displayedUiData.tracks.map { it.id.toString() }.toSet()
                        selectedIds = if (visibleIds.isNotEmpty() && selectedIds.containsAll(visibleIds)) selectedIds - visibleIds else selectedIds + visibleIds
                    }, enabled = displayedUiData.tracks.isNotEmpty()) {
                        com.ljyh.mei.ui.glass.SfIcon("checkmark.circle", androidx.compose.ui.res.stringResource(com.ljyh.mei.R.string.album_select_all),
                            tint = com.ljyh.mei.ui.glass.LocalGlassColors.current.accent)
                    }
                    com.ljyh.mei.ui.glass.GlassIconButton(style = com.ljyh.mei.ui.glass.GlassSurfaceStyle.Navigation, onClick = {
                        pendingDownloadTracks = uiData.tracks.filter { it.id.toString() in selectedIds }
                        showDownloadDialog = pendingDownloadTracks.isNotEmpty()
                    }, enabled = selectedIds.isNotEmpty()) {
                        com.ljyh.mei.ui.glass.SfIcon(com.ljyh.mei.ui.glass.SfSymbol.Download,
                            androidx.compose.ui.res.stringResource(com.ljyh.mei.R.string.album_download_selected),
                            tint = com.ljyh.mei.ui.glass.LocalGlassColors.current.accent)
                    }
                }
            }
        }
        onDispose { selectionToolbar.content.value = null }
    }

    if (showDownloadDialog) {
        DownloadConfirmDialog(
            currentQuality = downloadQuality,
            downloadPath = downloadPath,
            onDismiss = { showDownloadDialog = false },
            onConfirm = {
                showDownloadDialog = false
                doDownload(pendingDownloadTracks)
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

    // 6. 提取播放队列构建逻辑 (避免重复)
    fun buildListQueue(startIndex: Int = 0): ListQueue? {
        val detail = albumDetail
        if (detail is Resource.Success) {
            val items = detail.data.songs.map { song ->
                Pair(
                    song.id.toString(),
                    song.toMediaMetadata().copy(
                        coverUrl = detail.data.album.picUrl,
                        album = MediaMetadata.Album(detail.data.album.id, detail.data.album.name),
                    ).toMediaItem()
                )
            }
            return ListQueue(
                id = "album_${uiData.id}",
                title = uiData.title,
                items = items,
                startIndex = startIndex
            )
        }
        return null
    }

    // 6. UI 渲染
    if (albumDetail is Resource.Error) {
        IosPinnedListPage(
            title = stringResource(com.ljyh.mei.R.string.playlist_table_album),
            bottomPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateBottomPadding(),
            showsLargeTitle = false,
            horizontalContentPadding = DetailPageHorizontalPadding,
            onNavigateBack = { navController.popBackStack() },
        ) {
            item(key = "album-load-error") {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        stringResource(com.ljyh.mei.R.string.load_failed),
                        color = LocalGlassColors.current.secondaryContent,
                    )
                    GlassButton(onClick = { viewModel.getAlbumDetail(id.toString()) }) {
                        Text(stringResource(com.ljyh.mei.R.string.retry))
                    }
                }
            }
        }
    } else {
        CommonSongListScreen(
            useCoverBackground = true,
            showTrackArtwork = false,
            trackNumbers = trackNumbers,
            uiData = displayedUiData,
            pagingItems = null, // 专辑通常一次性加载，不需要 paging
            isLoading = albumDetail is Resource.Loading,

            // 头部按钮逻辑
            headerActionIcon = if (isSubscribed) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
            headerActionLabel = subscriptionLabel,
            onHeaderAction = ::toggleSubscription,
            isSubscribed = isSubscribed,
            detailMenu = detailMenu,
            selectionMode = selectionMode,
            selectedTrackIds = selectedIds,
            onSelectionDone = { selectionMode = false; selectedIds = emptySet() },

            onTrackDownload = { track, quality -> doDownload(listOf(track), quality) },

            // 播放全部
            onPlayAll = {
                buildListQueue(0)?.let { queue ->
                    playerConnection.playQueue(queue)
                }
            },

            // 点击单曲
            onTrackClick = { mediaMetadata, index ->
                if (selectionMode) {
                    val trackId = mediaMetadata.id.toString()
                    selectedIds = if (trackId in selectedIds) selectedIds - trackId else selectedIds + trackId
                } else {
                    playerConnection.onTrackClicked(
                        trackId = mediaMetadata.id.toString(),
                        buildQueue = {
                            val originalIndex = uiData.tracks.indexOfFirst { it.id == mediaMetadata.id }
                                .takeIf { it >= 0 } ?: index
                            buildListQueue(originalIndex)
                        }
                    )
                }
            },

            playlistSearchQuery = albumSearchQuery,
            isPlaylistSearchActive = isAlbumSearchActive,
            onPlaylistSearchQueryChange = { albumSearchQuery = it },
            onPlaylistSearchActiveChange = { active ->
                isAlbumSearchActive = active
                if (!active) albumSearchQuery = ""
            },
            onBack = { navController.popBackStack() }
        )
    }
}
