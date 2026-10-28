package com.ljyh.mei.ui.component.player.overlay

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import com.ljyh.mei.ui.component.player.OverlayState
import com.ljyh.mei.ui.component.player.PlayerViewModel
import com.ljyh.mei.ui.component.player.component.localPlaybackQualityTitle
import com.ljyh.mei.ui.component.player.component.PlayerNavigationMenu
import com.ljyh.mei.ui.component.player.component.sheet.AlbumArtistBottomSheet
import com.ljyh.mei.ui.component.player.component.sheet.MoreActionsSheet
import com.ljyh.mei.ui.component.player.component.sheet.PlayerActionSettingsSheet
import com.ljyh.mei.ui.component.player.component.sheet.PlaylistBottomSheet
import com.ljyh.mei.ui.component.player.component.sheet.QQMusicSelectSheet
import com.ljyh.mei.ui.component.player.component.sheet.SleepTimerSheet
import com.ljyh.mei.ui.component.player.component.sheet.SongInfoSheet
import com.ljyh.mei.ui.component.player.state.PlayerStateContainer
import com.ljyh.mei.ui.component.playlist.AddToPlaylistSheet
import com.ljyh.mei.ui.component.playlist.CreatePlaylistSheet
import com.ljyh.mei.ui.component.playlist.TrackActionMenu
import io.github.neoruaa.glasmio.scaffold.GlasmioBottomSheetState
import com.ljyh.mei.ui.local.LocalNavController
import com.ljyh.mei.ui.model.MoreAction
import com.ljyh.mei.ui.model.LyricSource
import com.ljyh.mei.ui.screen.Screen
import com.ljyh.mei.R
import io.github.neoruaa.glasmio.ui.GlasmioAlertDialog
import io.github.neoruaa.glasmio.ui.GlasmioGroupedList
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import io.github.neoruaa.glasmio.core.LocalGlassColors
import io.github.neoruaa.glasmio.icons.SfIcon
import com.ljyh.mei.ui.local.LocalPlayerConnection
import com.ljyh.mei.ui.screen.social.NeteaseShareSheet
import com.ljyh.mei.utils.setClipboard
import timber.log.Timber

/**
 * 公共弹窗处理器 UI
 * 统一渲染所有弹窗组件，提取约150行重复代码
 */
@UnstableApi
@Composable
fun CommonOverlayHandler(
    overlayHandler: PlayerOverlayHandler,
    stateContainer: PlayerStateContainer,
    sheetState: GlasmioBottomSheetState? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val navController = LocalNavController.current
    val playerViewModel = stateContainer.playerViewModel

    when (val overlay = overlayHandler.currentOverlayValue) {
        OverlayState.None -> {}

        OverlayState.Playlist -> {
            PlaylistBottomSheet(
                onDismiss = { overlayHandler.dismiss() }
            )
        }

        is OverlayState.AlbumArtist -> {
            val onAlbumClick: (Long) -> Unit = { id ->
                Screen.Album.navigate(navController) { addPath(id.toString()) }
                sheetState?.collapse(spring(stiffness = Spring.StiffnessVeryLow))
            }
            val onArtistClick: (Long) -> Unit = { id ->
                Screen.Artist.navigate(navController) { addPath(id.toString()) }
                sheetState?.collapse(spring(stiffness = Spring.StiffnessVeryLow))
            }
            val anchor = overlay.anchorBounds
            if (anchor != null) {
                PlayerNavigationMenu(
                    album = overlay.album,
                    artists = overlay.artists,
                    playlistTitle = overlay.playlistTitle,
                    anchorBounds = anchor,
                    viewportBounds = overlay.viewportBounds,
                    onAlbumClick = onAlbumClick,
                    onArtistClick = onArtistClick,
                    onPlaylistClick = {
                        val playlistId = overlay.playlistId
                        if (playlistId != null && playlistId > 0) {
                            Screen.PlayList.navigate(navController) { addPath(playlistId.toString()) }
                            sheetState?.collapse(spring(stiffness = Spring.StiffnessVeryLow))
                        } else {
                            overlayHandler.showPlaylist()
                        }
                    },
                    onDismiss = overlayHandler::dismiss,
                )
            } else {
                AlbumArtistBottomSheet(
                    coverUrl = overlay.cover,
                    albumInfo = overlay.album,
                    artistList = overlay.artists,
                    onAlbumClick = onAlbumClick,
                    onArtistClick = onArtistClick,
                    onDismissRequest = overlayHandler::dismiss,
                )
            }
        }

        is OverlayState.SongInfo -> {
            val qqSongId by produceState<String?>(null, overlay.metadata.id) {
                value = stateContainer.playerViewModel.getQQSongId(overlay.metadata.id)
            }
            SongInfoSheet(
                metadata = overlay.metadata,
                qqSongId = qqSongId,
                onDismissRequest = { overlayHandler.dismiss() }
            )
        }

        is OverlayState.QQMusicSelection -> {
            QQMusicSelectSheet(
                viewmodel = playerViewModel,
                mediaMetadata = overlay.mediaMetadata,
                onDismiss = { overlayHandler.dismiss() }
            )
        }

        OverlayState.SleepTimer -> {
            SleepTimerSheet(
                playerConnection = stateContainer.playerConnection,
                onDismiss = { overlayHandler.dismiss() }
            )
        }

        is OverlayState.AddToPlaylist -> {
            AddToPlaylistSheet(
                playlists = stateContainer.myPlaylist.value,
                onDismiss = { overlayHandler.dismiss() },
                onSelectPlaylist = { selectedPlaylist ->
                    overlayHandler.addSongToPlaylist(selectedPlaylist, overlay.mediaId)
                },
                onCreateNewPlaylist = {
                    overlayHandler.showCreatePlaylist()
                }
            )
        }

        OverlayState.CreatePlaylist -> {
            CreatePlaylistSheet(
                onDismiss = { overlayHandler.dismiss() },
                onConfirm = { name, privacy ->
                    overlayHandler.createPlaylist(name, privacy)
                }
            )
        }

        OverlayState.BottomAction -> {
            PlayerActionSettingsSheet(onDismiss = { overlayHandler.dismiss() })
        }

        OverlayState.MoreAction -> {
            // MoreActionsSheet queues this callback until its dismissal finishes. Capture
            // the displayed track now so an automatic advance cannot retarget that action.
            val actionMetadata = stateContainer.mediaMetadata.value
            MoreActionsSheet(
                onDismissRequest = {
                    overlayHandler.dismiss()
                },
                onActionClick = { action ->
                    if (action == MoreAction.COMMENT || action == MoreAction.SONG_WIKI) {
                        actionMetadata?.let { v->
                            val destination = if (action == MoreAction.COMMENT) Screen.Comment else Screen.SongWiki
                            destination.navigate(navController) {
                                addPath(v.id.toString())
                            }
                            overlayHandler.dismiss()
                            sheetState?.collapse(spring(stiffness = Spring.StiffnessVeryLow))
                        }

                    }else{
                        overlayHandler.handleMoreAction(action, actionMetadata)
                    }

                },
                viewModel = playerViewModel,
                onFindLyrics = if (actionMetadata != null) {
                    {
                        playerViewModel.searchQQSong(actionMetadata.title)
                        overlayHandler.showQQMusicSelection(actionMetadata)
                    }
                } else null,
                onFindLyricsLongClick = if (stateContainer.lyricLine.source == LyricSource.QQMusic && actionMetadata != null) {
                    {
                        playerViewModel.deleteSongById(actionMetadata.id.toString())
                        android.widget.Toast.makeText(context, "已删除QQ音乐歌词", android.widget.Toast.LENGTH_SHORT).show()
                    }
                } else null,
            )
        }

        is OverlayState.Share -> {
            NeteaseShareSheet(
                metadata = overlay.metadata,
                onDismiss = overlayHandler::dismiss,
            )
        }

        is OverlayState.MusicQualitySelection -> Unit

        is OverlayState.TrackActionMenu -> {
            TrackActionMenu(
                targetTrack = overlay.track,
                anchorBounds = overlay.anchorBounds,
                onShare = { overlayHandler.showShare(overlay.track) },
                onDismiss = overlayHandler::dismiss,
                onAddToPlaylist = { overlayHandler.showAddToPlaylist(overlay.track.id) },
                onDownloadTrack = { quality -> playerViewModel.downloadSong(overlay.track, context, quality) },
                onCopyId = { setClipboard(context, overlay.track.id.toString(), "id") },
                onCopyName = { setClipboard(context, overlay.track.title, "name") },
            )
        }
    }
    val qualityDialogVisible = overlayHandler.currentOverlayValue is OverlayState.MusicQualitySelection
    GlasmioAlertDialog(
        visible = qualityDialogVisible,
        onDismissRequest = overlayHandler::dismiss,
        title = stringResource(R.string.music_quality),
    ) {
        val playerConnection = LocalPlayerConnection.current
        val currentQuality = playerConnection?.currentMusicQuality?.collectAsStateWithLifecycle()?.value
        val availableQualities = playerConnection?.availableMusicQualities?.collectAsStateWithLifecycle()?.value.orEmpty()
        val isLoadingQualities = playerConnection?.isLoadingMusicQualities?.collectAsStateWithLifecycle()?.value ?: false
        val localPlaybackInfo = playerConnection?.localPlaybackInfo?.collectAsStateWithLifecycle()?.value
        val isPlayingLocalSource = playerConnection?.isPlayingLocalSource?.collectAsStateWithLifecycle()?.value ?: false
        val mediaMetadata = playerConnection?.mediaMetadata?.collectAsStateWithLifecycle()?.value
        LaunchedEffect(qualityDialogVisible, playerConnection, mediaMetadata?.id) {
            if (qualityDialogVisible) playerConnection?.refreshAvailableMusicQualities()
        }
        GlasmioGroupedList {
            localPlaybackInfo?.let { info ->
                GlasmioListRow(
                    title = localPlaybackQualityTitle(info, includeSource = true),
                    showTopSeparator = false,
                    onClick = {
                        if (qualityDialogVisible) {
                            playerConnection?.useLocalSource()
                            overlayHandler.dismiss()
                        }
                    },
                    trailing = if (isPlayingLocalSource) {
                        {
                            SfIcon(
                                "checkmark",
                                contentDescription = null,
                                size = 17.dp,
                                tint = LocalGlassColors.current.content,
                            )
                        }
                    } else {
                        null
                    },
                )
            }
            availableQualities.forEachIndexed { index, quality ->
                GlasmioListRow(
                    title = stringResource(quality.labelRes),
                    showTopSeparator = index != 0 || localPlaybackInfo != null,
                    onClick = {
                        if (qualityDialogVisible) {
                            playerConnection?.changeQuality(quality)
                            overlayHandler.dismiss()
                        }
                    },
                    trailing = if (!isPlayingLocalSource && quality == currentQuality) {
                        {
                            SfIcon(
                                "checkmark",
                                contentDescription = null,
                                size = 17.dp,
                                tint = LocalGlassColors.current.content,
                            )
                        }
                    } else {
                        null
                    },
                )
            }
            if (isLoadingQualities || (availableQualities.isEmpty() && localPlaybackInfo == null)) {
                GlasmioListRow(
                    title = stringResource(
                        if (isLoadingQualities) R.string.track_quality_loading
                        else R.string.track_quality_unavailable,
                    ),
                    showTopSeparator = availableQualities.isNotEmpty() || localPlaybackInfo != null,
                )
            }
        }
    }

}
