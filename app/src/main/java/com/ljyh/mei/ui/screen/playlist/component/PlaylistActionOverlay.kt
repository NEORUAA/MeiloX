package com.ljyh.mei.ui.screen.playlist.component

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.ljyh.mei.constants.MusicQuality
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.room.Playlist
import com.ljyh.mei.source.api.TrackRefCodec
import com.ljyh.mei.ui.component.player.OverlayState
import com.ljyh.mei.ui.component.player.PlayerViewModel
import com.ljyh.mei.ui.component.player.component.sheet.SongInfoSheet
import com.ljyh.mei.ui.component.playlist.CreatePlaylistSheet
import com.ljyh.mei.ui.local.LocalNavController
import com.ljyh.mei.ui.screen.Screen
import com.ljyh.mei.ui.screen.source.SourceContentViewModel
import com.ljyh.mei.ui.screen.source.SourceTrackActions
import com.ljyh.mei.ui.screen.source.SourceTrackActionMessage

@Composable
fun PlaylistActionOverlay(
    overlay: OverlayState,
    isCreator: Boolean,
    playlistId: Long, // 当前歌单 ID，用于删除歌曲
    allMePlaylist: List<Playlist>, // 用户拥有的歌单列表
    onDismiss: () -> Unit,
    onUpdateOverlay: (OverlayState) -> Unit, // 用于切换状态（如从菜单跳到收藏页）
    onDownloadTrack: ((MediaMetadata, MusicQuality) -> Unit)? = null,
    playerViewModel: PlayerViewModel = hiltViewModel(),
    sourceViewModel: SourceContentViewModel = hiltViewModel(),
) {
    val nav = LocalNavController.current
    when (overlay) {
        is OverlayState.Share -> com.ljyh.mei.ui.screen.social.NeteaseShareSheet(
            metadata = overlay.metadata, onDismiss = onDismiss,
        )
        is OverlayState.SongInfo -> {
            val qqSongId by produceState<String?>(null, overlay.metadata.trackRef) {
                value = playerViewModel.getQQSongId(overlay.metadata)
            }
            SongInfoSheet(metadata = overlay.metadata, qqSongId = qqSongId, onDismissRequest = onDismiss)
        }
        is OverlayState.AddToPlaylist -> LaunchedEffect(overlay.mediaId) {
            val ref = TrackRefCodec.decode(overlay.mediaId.toString())
            onDismiss()
            if (ref != null) Screen.SourcePlaylistPicker.navigate(nav) { addPath(Uri.encode(TrackRefCodec.encode(ref))) }
        }
        is OverlayState.TrackActionMenu -> SourceTrackActions(
            metadata = overlay.track,
            anchorBounds = overlay.anchorBounds,
            onDismiss = onDismiss,
            viewModel = sourceViewModel,
            playerViewModel = playerViewModel,
            onShare = { onUpdateOverlay(OverlayState.Share(overlay.track)) },
            onDownloadTrack = onDownloadTrack?.let { download -> { quality -> download(overlay.track, quality) } },
        )
        OverlayState.CreatePlaylist -> CreatePlaylistSheet(
            onDismiss = onDismiss,
            onConfirm = { name, privacy -> playerViewModel.createPlaylist(name, privacy); onDismiss() },
        )
        else -> {}
    }
    SourceTrackActionMessage(sourceViewModel)
}
