package com.ljyh.mei.ui.screen.playlist.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ljyh.mei.ui.component.player.OverlayState
import com.ljyh.mei.ui.component.player.PlayerViewModel
import com.ljyh.mei.ui.screen.playlist.PlaylistViewModel

@Composable
fun StandaloneTrackActionOverlay(
    overlay: OverlayState,
    onDismiss: () -> Unit,
    onUpdateOverlay: (OverlayState) -> Unit,
    playlistViewModel: PlaylistViewModel = viewModel(),
    playerViewModel: PlayerViewModel = viewModel(),
) {
    val context = LocalContext.current
    PlaylistActionOverlay(
        overlay = overlay,
        isCreator = false,
        playlistId = 0L,
        onDismiss = onDismiss,
        onUpdateOverlay = onUpdateOverlay,
        onDownloadTrack = { track, quality -> playerViewModel.downloadSong(track, context, quality) },
        viewModel = playlistViewModel,
    )
}
