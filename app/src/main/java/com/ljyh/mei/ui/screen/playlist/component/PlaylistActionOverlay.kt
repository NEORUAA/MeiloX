package com.ljyh.mei.ui.screen.playlist.component

import com.ljyh.mei.constants.MusicQuality

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.ui.component.player.OverlayState
import com.ljyh.mei.ui.component.playlist.AddToPlaylistSheet
import com.ljyh.mei.ui.component.playlist.CreatePlaylistSheet
import com.ljyh.mei.ui.component.playlist.TrackActionMenu
import com.ljyh.mei.ui.screen.playlist.PlaylistViewModel
import com.ljyh.mei.ui.screen.playlist.PlaylistTrackAddOutcome
import com.ljyh.mei.utils.setClipboard


@Composable
fun PlaylistActionOverlay(
    overlay: OverlayState,
    isCreator: Boolean,
    playlistId: Long, // 当前歌单 ID，用于删除歌曲
    onDismiss: () -> Unit,
    onUpdateOverlay: (OverlayState) -> Unit, // 用于切换状态（如从菜单跳到收藏页）
    onDownloadTrack: ((MediaMetadata, MusicQuality) -> Unit)? = null,
    viewModel: PlaylistViewModel
) {
    if (overlay == OverlayState.None) return
    val context = LocalContext.current
    val owner = remember(viewModel) { viewModel.captureActionSession() }
    val currentOwner by viewModel.actionSession.collectAsState()
    val actionIsCurrent = { owner != null && viewModel.captureActionSession() == owner }
    LaunchedEffect(currentOwner, owner) {
        if (owner != currentOwner) onDismiss()
    }

    when (overlay) {
        is OverlayState.Share -> com.ljyh.mei.ui.screen.social.NeteaseShareSheet(
            metadata = overlay.metadata,
            onDismiss = onDismiss,
        )

        is OverlayState.AddToPlaylist -> {
            val picker by viewModel.picker.collectAsState()
            DisposableEffect(owner) {
                owner?.let(viewModel::getAllMePlaylist)
                onDispose { viewModel.stopPlaylistPicker() }
            }
            AddToPlaylistSheet(
                playlists = picker.playlists.takeIf { picker.owner == owner }.orEmpty(),
                loading = picker.loading,
                error = picker.error,
                onRetry = { owner?.let(viewModel::getAllMePlaylist) },
                onDismiss = onDismiss,
                onCreateNewPlaylist = {
                    if (actionIsCurrent()) onUpdateOverlay(OverlayState.CreatePlaylist)
                },
                onSelectPlaylist = { selectedPlaylist ->
                    if (!actionIsCurrent()) return@AddToPlaylistSheet
                    viewModel.addSongToPlaylist(
                        pid = selectedPlaylist.id,
                        track = overlay.track,
                        owner = owner ?: return@AddToPlaylistSheet,
                    ) { outcome ->
                        val message = when (outcome) {
                            PlaylistTrackAddOutcome.Added -> "已添加到 ${selectedPlaylist.title}"
                            PlaylistTrackAddOutcome.AlreadyExists -> "歌曲已在 ${selectedPlaylist.title} 中"
                            PlaylistTrackAddOutcome.PartiallyAdded -> "操作完成，部分歌曲未能添加"
                            PlaylistTrackAddOutcome.Failed -> "添加到歌单失败"
                        }
                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    }
                    onDismiss()
                }
            )
        }

        is OverlayState.TrackActionMenu -> {
            TrackActionMenu(
                targetTrack = overlay.track,
                anchorBounds = overlay.anchorBounds,
                onShare = { if (actionIsCurrent()) onUpdateOverlay(OverlayState.Share(overlay.track)) },
                isCreator = isCreator,
                onDismiss = onDismiss,
                onAddToPlaylist = {
                    if (actionIsCurrent()) onUpdateOverlay(OverlayState.AddToPlaylist(overlay.track))
                },
                onDownloadTrack = onDownloadTrack?.let { download -> { quality ->
                    if (actionIsCurrent()) download(overlay.track, quality)
                } },
                onDelete = {
                    if (!actionIsCurrent()) return@TrackActionMenu null
                    if (owner != null) viewModel.deleteSongFromPlaylist(
                        playlistId.toString(),
                        overlay.track,
                        owner = owner,
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
                    setClipboard(context, overlay.track.id.toString(), "id")
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
                    if (!actionIsCurrent()) return@CreatePlaylistSheet
                    viewModel.createPlaylist(name, privacy, owner ?: return@CreatePlaylistSheet) { created ->
                        Toast.makeText(context, if (created) "歌单已创建" else "创建歌单失败", Toast.LENGTH_SHORT).show()
                    }
                    onDismiss()
                },
            )
        }

        else -> {}
    }
}
