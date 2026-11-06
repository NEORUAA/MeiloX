package com.ljyh.mei.ui.screen.playlist.component

import com.ljyh.mei.constants.MusicQuality

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.room.Playlist
import com.ljyh.mei.ui.component.player.OverlayState
import com.ljyh.mei.ui.component.player.PlayerViewModel
import com.ljyh.mei.ui.component.player.component.sheet.SongInfoSheet
import com.ljyh.mei.ui.component.playlist.AddToPlaylistSheet
import com.ljyh.mei.ui.component.playlist.CreatePlaylistSheet
import com.ljyh.mei.ui.component.playlist.TrackActionMenu
import com.ljyh.mei.ui.model.UiPlaylist
import com.ljyh.mei.ui.screen.playlist.PlaylistViewModel
import com.ljyh.mei.ui.screen.playlist.PlaylistTrackAddOutcome
import com.ljyh.mei.utils.setClipboard
import com.ljyh.mei.source.api.ProviderIds
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
    viewModel: PlaylistViewModel,
    playerViewModel: PlayerViewModel = hiltViewModel(),
    sourceViewModel: SourceContentViewModel = hiltViewModel(),
) {
    val context = LocalContext.current

    when (overlay) {
        is OverlayState.Share -> if (overlay.metadata.trackRef.providerId == ProviderIds.NETEASE) com.ljyh.mei.ui.screen.social.NeteaseShareSheet(
            metadata = overlay.metadata,
            onDismiss = onDismiss,
        )

        is OverlayState.SongInfo -> {
            val qqSongId by produceState<String?>(null, overlay.metadata.trackRef) {
                value = playerViewModel.getQQSongId(overlay.metadata)
            }
            SongInfoSheet(metadata = overlay.metadata, qqSongId = qqSongId, onDismissRequest = onDismiss)
        }

        is OverlayState.AddToPlaylist -> {
            AddToPlaylistSheet(
                playlists = allMePlaylist,
                onDismiss = onDismiss,
                onCreateNewPlaylist = {
                    // 这里可以跳转到创建歌单的 Overlay 或页面
                    onUpdateOverlay(OverlayState.CreatePlaylist)
                },
                onSelectPlaylist = { selectedPlaylist ->
                    viewModel.addSongToPlaylist(
                        pid = selectedPlaylist.id,
                        trackIds = overlay.mediaId.toString(),
                        previousTrackCount = selectedPlaylist.count,
                    ) { outcome ->
                        val message = when (outcome) {
                            PlaylistTrackAddOutcome.Added -> "已添加到 ${selectedPlaylist.title}"
                            PlaylistTrackAddOutcome.AlreadyExists -> "歌曲已在 ${selectedPlaylist.title} 中"
                            PlaylistTrackAddOutcome.Failed -> "添加到歌单失败"
                        }
                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    }
                    onDismiss()
                }
            )
        }

        is OverlayState.TrackActionMenu -> {
            if (overlay.track.trackRef.providerId != ProviderIds.NETEASE) SourceTrackActions(
                metadata = overlay.track,
                anchorBounds = overlay.anchorBounds,
                onDismiss = onDismiss,
                viewModel = sourceViewModel,
                playerViewModel = playerViewModel,
                onSongInfo = { onUpdateOverlay(OverlayState.SongInfo(overlay.track)) },
                onDownloadTrack = onDownloadTrack?.let { download -> { quality -> download(overlay.track, quality) } },
            ) else TrackActionMenu(
                targetTrack = overlay.track,
                anchorBounds = overlay.anchorBounds,
                onShare = { onUpdateOverlay(OverlayState.Share(overlay.track)) },
                isCreator = isCreator,
                onDismiss = onDismiss,
                onAddToPlaylist = {
                    viewModel.getAllMePlaylist()
                    onUpdateOverlay(OverlayState.AddToPlaylist(overlay.track.id))
                },
                onDownloadTrack = onDownloadTrack?.let { download -> { quality -> download(overlay.track, quality) } },
                onDelete = {
                    viewModel.deleteSongFromPlaylist(
                        playlistId.toString(),
                        overlay.track.id.toString()
                    ) { deleted ->
                        if (deleted) {
                            viewModel.markTrackRemoved(overlay.track.id)
                            Toast.makeText(context, "已从歌单删除", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "从歌单删除失败", Toast.LENGTH_SHORT).show()
                        }
                    }
                    onDismiss()
                },
                onCopyId = {
                    setClipboard(context, overlay.track.trackRef.trackId, "id")
                    onDismiss()
                },
                onCopyName = {
                    setClipboard(context, overlay.track.title, "name")
                    onDismiss()
                }
            )
        }

        OverlayState.CreatePlaylist -> {
            CreatePlaylistSheet(
                onDismiss = onDismiss,
                onConfirm = { name, privacy ->
                    viewModel.createPlaylist(name, privacy)
                    onDismiss()
                },
            )
        }

        else -> {}
    }
    SourceTrackActionMessage(sourceViewModel)
}
