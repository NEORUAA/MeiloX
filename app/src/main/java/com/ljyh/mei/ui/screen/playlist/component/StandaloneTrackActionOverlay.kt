package com.ljyh.mei.ui.screen.playlist.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.ljyh.mei.ui.component.player.OverlayState
import com.ljyh.mei.ui.component.player.PlayerViewModel
import com.ljyh.mei.ui.screen.playlist.PlaylistViewModel

@Composable
fun StandaloneTrackActionOverlay(
    overlay: OverlayState,
    onDismiss: () -> Unit,
    onUpdateOverlay: (OverlayState) -> Unit,
    playlistViewModel: PlaylistViewModel = hiltViewModel(),
    playerViewModel: PlayerViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val playlists by playlistViewModel.playlist.collectAsState()
    PlaylistActionOverlay(
        overlay = overlay,
        isCreator = false,
        playlistId = 0L,
        allMePlaylist = playlists,
        onDismiss = onDismiss,
        onUpdateOverlay = onUpdateOverlay,
        onDownloadTrack = { track, quality -> playerViewModel.downloadSong(track, context, quality) },
        viewModel = playlistViewModel,
    )
}
