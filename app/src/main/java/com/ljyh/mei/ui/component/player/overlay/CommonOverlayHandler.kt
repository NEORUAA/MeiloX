package com.ljyh.mei.ui.component.player.overlay

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.media3.common.util.UnstableApi
import com.ljyh.mei.ui.component.player.OverlayState
import com.ljyh.mei.ui.component.player.PlayerViewModel
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
import com.ljyh.mei.ui.component.sheet.BottomSheetState
import com.ljyh.mei.ui.local.LocalNavController
import com.ljyh.mei.ui.model.MoreAction
import com.ljyh.mei.ui.screen.Screen
import com.ljyh.mei.constants.MusicQuality
import com.ljyh.mei.R
import com.ljyh.mei.ui.glass.IosAlertDialog
import com.ljyh.mei.ui.glass.IosGroupedList
import com.ljyh.mei.ui.glass.IosListRow
import com.ljyh.mei.ui.glass.LocalGlassColors
import com.ljyh.mei.ui.glass.SfIcon
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
    sheetState: BottomSheetState? = null,
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
            AlbumArtistBottomSheet(
                coverUrl = overlay.cover,
                albumInfo = overlay.album,
                artistList = overlay.artists,
                onAlbumClick = { id ->
                    Screen.Album.navigate(navController) {
                        addPath(id.toString())
                    }
                    sheetState?.collapse(spring(stiffness = Spring.StiffnessVeryLow))
                },
                onArtistClick = { id ->
                    Screen.Artist.navigate(navController) {
                        addPath(id.toString())
                    }
                    sheetState?.collapse(spring(stiffness = Spring.StiffnessVeryLow))
                },
                onDismissRequest = { overlayHandler.dismiss() },
            )
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
            MoreActionsSheet(
                onDismissRequest = {
                    overlayHandler.dismiss()
                },
                onActionClick = { action ->
                    if (action == MoreAction.COMMENT || action == MoreAction.SONG_WIKI) {
                        stateContainer.mediaMetadata.value?.let { v->
                            val destination = if (action == MoreAction.COMMENT) Screen.Comment else Screen.SongWiki
                            destination.navigate(navController) {
                                addPath(v.id.toString())
                            }
                            overlayHandler.dismiss()
                            sheetState?.collapse(spring(stiffness = Spring.StiffnessVeryLow))
                        }

                    }else{
                        overlayHandler.handleMoreAction(action)
                    }

                },
                viewModel = playerViewModel
            )
        }

        is OverlayState.Share -> {
            NeteaseShareSheet(
                metadata = overlay.metadata,
                onDismiss = overlayHandler::dismiss,
            )
        }

        is OverlayState.MusicQualitySelection -> {
            val playerConnection = LocalPlayerConnection.current
            IosAlertDialog(
                onDismissRequest = overlayHandler::dismiss,
                title = stringResource(R.string.music_quality),
            ) {
                IosGroupedList {
                    MusicQuality.entries.forEachIndexed { index, quality ->
                        IosListRow(
                            title = "${quality.explanation} · ${quality.text}",
                            showTopSeparator = index != 0,
                            onClick = {
                                playerConnection?.changeQuality(quality)
                                overlayHandler.dismiss()
                            },
                            trailing = if (quality.ordinal == overlay.current) {
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
                }
            }
        }

        is OverlayState.TrackActionMenu -> {
            TrackActionMenu(
                targetTrack = overlay.track,
                onDismiss = overlayHandler::dismiss,
                onAddToPlaylist = { overlayHandler.showAddToPlaylist(overlay.track.id) },
                onDownloadTrack = { playerViewModel.downloadSong(overlay.track, context) },
                onCopyId = { setClipboard(context, overlay.track.id.toString(), "id") },
                onCopyName = { setClipboard(context, overlay.track.title, "name") },
            )
        }
    }
}
