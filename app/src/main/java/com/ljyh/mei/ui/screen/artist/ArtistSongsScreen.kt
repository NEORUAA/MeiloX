package com.ljyh.mei.ui.screen.artist

import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.media3.common.util.UnstableApi
import com.ljyh.mei.R
import com.ljyh.mei.data.model.toMediaItem
import com.ljyh.mei.playback.queue.ListQueue
import com.ljyh.mei.ui.component.item.Track
import com.ljyh.mei.ui.component.player.OverlayState
import com.ljyh.mei.ui.glass.IosListRow
import com.ljyh.mei.ui.glass.IosPinnedListPage
import com.ljyh.mei.ui.glass.LocalGlassColors
import com.ljyh.mei.ui.local.LocalNavController
import com.ljyh.mei.ui.local.LocalPlayerAwareWindowInsets
import com.ljyh.mei.ui.local.LocalPlayerConnection
import com.ljyh.mei.ui.screen.playlist.component.StandaloneTrackActionOverlay
import java.util.UUID

@OptIn(UnstableApi::class)
@Composable
fun ArtistSongsScreen(
    id: String,
    viewModel: ArtistSongsViewModel = hiltViewModel(key = "artist-songs:$id"),
) {
    val navController = LocalNavController.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val state by viewModel.state.collectAsState()
    var overlay by remember { mutableStateOf<OverlayState>(OverlayState.None) }
    val title = stringResource(R.string.artist_all_songs)

    Box(Modifier.fillMaxSize()) {
        IosPinnedListPage(
            title = title,
            bottomPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateBottomPadding(),
            horizontalContentPadding = 0.dp,
            largeTitleHorizontalPadding = 20.dp,
            verticalArrangement = Arrangement.spacedBy(0.dp),
            onNavigateBack = navController::popBackStack,
        ) {
            items(state.songs, key = { it.id }) { song ->
                Track(
                    track = song,
                    onClick = {
                        val songs = state.songs
                        playerConnection.onTrackClicked(
                            trackId = song.id.toString(),
                            buildQueue = {
                                ListQueue(
                                    UUID.randomUUID().toString(),
                                    title,
                                    songs.map { it.id.toString() to it.toMediaItem() },
                                    songs.indexOf(song),
                                )
                            },
                        )
                    },
                    onMoreClick = { overlay = OverlayState.TrackActionMenu(song, it) },
                )
            }
            if (state.error != null) {
                item(key = "retry") {
                    IosListRow(
                        title = stringResource(R.string.retry),
                        subtitle = state.error,
                        onClick = { viewModel.loadMore(id) },
                    )
                }
            } else if (state.hasMore) {
                item(key = "load-more:${state.offset}") {
                    LaunchedEffect(id, state.offset) { viewModel.loadMore(id) }
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = LocalGlassColors.current.accent)
                    }
                }
            } else if (state.songs.isEmpty()) {
                item(key = "empty") {
                    Text(
                        stringResource(R.string.artist_songs_empty),
                        modifier = Modifier.padding(20.dp),
                        color = LocalGlassColors.current.secondaryContent,
                    )
                }
            }
        }
        StandaloneTrackActionOverlay(
            overlay = overlay,
            onDismiss = { overlay = OverlayState.None },
            onUpdateOverlay = { overlay = it },
        )
    }
}
