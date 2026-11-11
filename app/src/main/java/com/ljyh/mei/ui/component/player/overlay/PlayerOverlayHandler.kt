package com.ljyh.mei.ui.component.player.overlay

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.content.Intent
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.room.Playlist
import com.ljyh.mei.ui.component.player.OverlayState
import com.ljyh.mei.ui.component.player.PlayerViewModel
import com.ljyh.mei.ui.component.player.state.PlayerStateContainer
import com.ljyh.mei.ui.model.MoreAction
import com.ljyh.mei.ui.model.availableFor
import com.ljyh.mei.source.api.ProviderIds
import com.ljyh.mei.utils.lyric.lyricStorageKey
import com.ljyh.mei.ui.component.player.enterFloatingLyricsPip
import com.ljyh.mei.ui.screen.Screen
import android.net.Uri
import com.ljyh.mei.source.api.TrackRefCodec
import com.ljyh.mei.ui.navigation.MeiNavigator

/**
 * 播放器弹窗处理器
 * 统一管理所有弹窗的显示逻辑
 */
@OptIn(UnstableApi::class)
class PlayerOverlayHandler(
    private val stateContainer: PlayerStateContainer,
    private val navController: MeiNavigator,
    private val context: android.content.Context,
    private val onNavigateFromPlayer: () -> Unit = {},
) {
    private val _currentOverlay = mutableStateOf<OverlayState>(OverlayState.None)
    val currentOverlay: State<OverlayState> = _currentOverlay

    val currentOverlayValue: OverlayState
        get() = _currentOverlay.value

    /** Optional inline queue route supplied by the active player layout. */
    internal var onShowPlaylist: (() -> Unit)? = null

    /** Open the inline queue when available, otherwise use the shared sheet. */
    fun showPlaylist() {
        val openPlaylist = onShowPlaylist
        if (openPlaylist != null) {
            dismiss()
            openPlaylist()
        } else {
            _currentOverlay.value = OverlayState.Playlist
        }
    }

    /**
     * 显示睡眠定时弹窗
     */
    fun showSleepTimer() {
        _currentOverlay.value = OverlayState.SleepTimer
    }

    /**
     * 显示添加到播放列表弹窗
     */
    fun showAddToPlaylist(mediaId: Long) {
        val metadata = stateContainer.mediaMetadata.value?.takeIf { it.id == mediaId } ?: return
        showAddToPlaylist(metadata)
    }

    fun showAddToPlaylist(metadata: MediaMetadata) {
        if (metadata.isLocal || metadata.isPodcast) return
        dismiss()
        Screen.SourcePlaylistPicker.navigate(navController) { addPath(Uri.encode(TrackRefCodec.encode(metadata.trackRef))) }
        onNavigateFromPlayer()
    }

    /**
     * 显示创建播放列表弹窗
     */
    fun showCreatePlaylist() {
        _currentOverlay.value = OverlayState.CreatePlaylist
    }

    /**
     * 显示更多操作弹窗
     */
    fun showMoreAction() {
        _currentOverlay.value = OverlayState.MoreAction
    }

    fun showPrivateFmScenes() {
        _currentOverlay.value = OverlayState.PrivateFmScenes
    }

    /**
     * 显示底部设置弹窗
     */
    fun showBottomAction() {
        _currentOverlay.value = OverlayState.BottomAction
    }

    /**
     * 显示专辑艺术家弹窗
     */
    fun showAlbumArtist(
        album: MediaMetadata.Album,
        artists: List<MediaMetadata.Artist>,
        cover: String,
        anchorBounds: Rect? = null,
        viewportBounds: Rect? = null,
    ) {
        val connection = stateContainer.playerConnection
        _currentOverlay.value = OverlayState.AlbumArtist(
            album, artists, cover, anchorBounds,
            playlistTitle = connection.queueTitle.value,
            playlistId = connection.service.queueManager.playlistSource?.playlistId,
            viewportBounds = viewportBounds,
        )
    }

    /**
     * 显示QQ音乐选择弹窗
     */
    fun showQQMusicSelection(mediaMetadata: MediaMetadata) {
        _currentOverlay.value = OverlayState.QQMusicSelection(mediaMetadata)
    }

    /**
     * 显示音质选择弹窗
     */
    fun showMusicQualitySelection(current: Int) {
        _currentOverlay.value = OverlayState.MusicQualitySelection(current)
    }

    /**
     * 显示轨道操作菜单
     */
    fun showShare(metadata: MediaMetadata) {
        if (!stateContainer.playerViewModel.isActionAvailable(MoreAction.SHARE, metadata)) return
        _currentOverlay.value = OverlayState.Share(metadata)
    }

    fun showTrackActionMenu(track: MediaMetadata) {
        _currentOverlay.value = OverlayState.TrackActionMenu(track)
    }

    fun showSongInfo(metadata: MediaMetadata) {
        _currentOverlay.value = OverlayState.SongInfo(metadata)
    }

    /**
     * 关闭当前弹窗
     */
    fun dismiss() {
        _currentOverlay.value = OverlayState.None
    }

    /**
     * 处理更多操作点击
     */
    fun handleMoreAction(
        action: MoreAction,
        mediaMetadata: MediaMetadata? = stateContainer.mediaMetadata.value,
    ) {
        if (!stateContainer.playerViewModel.isActionAvailable(action, mediaMetadata)) return
        when (action) {
            MoreAction.ADD_TO_PLAYLIST -> {
                mediaMetadata?.let {
                    showAddToPlaylist(it)
                }
            }
            MoreAction.SHARE -> {
                mediaMetadata?.let { metadata ->
                    _currentOverlay.value = OverlayState.Share(metadata)
                }
            }
            MoreAction.DOWNLOAD -> {
                mediaMetadata?.let {
                    dismiss()
                    requestNotificationPermission()
                    stateContainer.playerViewModel.downloadSong(it, context)
                }
            }
            MoreAction.DELETE -> {
                mediaMetadata?.let {
                    stateContainer.playerViewModel.deleteSongById(it.lyricStorageKey())
                }
            }
            MoreAction.VIEW_PLAYLIST -> {
                showPlaylist()
            }
            MoreAction.SLEEP_TIMER -> {
                showSleepTimer()
            }
            MoreAction.PICTURE_IN_PICTURE -> {
                dismiss()
                enterFloatingLyricsPip(context, stateContainer.isPlaying.value)
            }
            MoreAction.BOTTOM_ACTION -> {
                showBottomAction()
            }
            MoreAction.SONG_INFO -> {
                mediaMetadata?.let {
                    showSongInfo(it)
                }
            }
            else -> {}
        }
    }

    /**
     * 添加歌曲到播放列表
     */
    fun addSongToPlaylist(selectedPlaylist: Playlist, mediaId: Long) {
        val ref = stateContainer.mediaMetadata.value?.takeIf { it.id == mediaId }?.trackRef
            ?: TrackRefCodec.decode(mediaId.toString()) ?: return
        stateContainer.playerViewModel.addSongToPlaylist(selectedPlaylist, ref) { added ->
            val message = if (added) "已添加到 ${selectedPlaylist.title}" else "添加到歌单失败"
            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
        }
        dismiss()
    }

    /**
     * 创建新播放列表
     */
    fun createPlaylist(name: String, privacy: Boolean) {
        stateContainer.playerViewModel.createPlaylist(name, privacy)
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasPermission = ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasPermission) {
                val activity = context as? Activity ?: return
                ActivityCompat.requestPermissions(
                    activity,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    1001
                )
            }
        }
    }
}

/**
 * 记忆并创建弹窗处理器
 */
@OptIn(UnstableApi::class)
@Composable
fun rememberOverlayHandler(
    stateContainer: PlayerStateContainer,
    navController: MeiNavigator,
    onNavigateFromPlayer: () -> Unit = {},
): PlayerOverlayHandler {
    val context = LocalContext.current

    val currentOnNavigate = rememberUpdatedState(onNavigateFromPlayer)
    return remember(stateContainer, navController, context) {
        PlayerOverlayHandler(
            stateContainer = stateContainer,
            navController = navController,
            context = context,
            onNavigateFromPlayer = { currentOnNavigate.value() },
        )
    }
}
