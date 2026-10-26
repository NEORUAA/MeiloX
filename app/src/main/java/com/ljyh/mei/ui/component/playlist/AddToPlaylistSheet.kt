package com.ljyh.mei.ui.component.playlist

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.kyant.capsule.ContinuousRoundedRectangle
import com.ljyh.mei.R
import com.ljyh.mei.data.model.room.Playlist
import io.github.neoruaa.glasmio.ui.GlasmioGroupedList
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import io.github.neoruaa.glasmio.ui.GlasmioBottomSheet
import io.github.neoruaa.glasmio.ui.GlasmioSheetTopToolbar
import io.github.neoruaa.glasmio.ui.GlasmioSheetToolbarButton
import io.github.neoruaa.glasmio.icons.SfIcon
import io.github.neoruaa.glasmio.icons.SfSymbol
import com.ljyh.mei.utils.smallImage

@Composable
fun AddToPlaylistSheet(
    playlists: List<Playlist>,
    onDismiss: () -> Unit,
    onSelectPlaylist: (Playlist) -> Unit,
    onCreateNewPlaylist: () -> Unit,
) {
    GlasmioBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth()) {
            GlasmioSheetTopToolbar(
                title = stringResource(R.string.add_to_playlist_title),
                actions = {
                    GlasmioSheetToolbarButton(onClick = onDismiss) {
                        SfIcon(SfSymbol.Close, stringResource(R.string.cancel))
                    }
                },
            )
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).weight(1f, fill = false)) {
                GlasmioGroupedList {
                    GlasmioListRow(
                        title = stringResource(R.string.create_playlist_title),
                        systemName = "plus.circle",
                        showTopSeparator = false,
                        onClick = onCreateNewPlaylist,
                    )
                }
                if (playlists.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    GlasmioGroupedList(Modifier.weight(1f, fill = false)) {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(0.dp),
                        ) {
                            itemsIndexed(playlists, key = { _, playlist -> playlist.id }) { index, playlist ->
                                PlaylistSelectionItem(
                                    playlist = playlist,
                                    showTopSeparator = index > 0,
                                    onClick = { onSelectPlaylist(playlist) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaylistSelectionItem(
    playlist: Playlist,
    showTopSeparator: Boolean,
    onClick: () -> Unit,
) {
    GlasmioListRow(
        title = playlist.title,
        subtitle = stringResource(R.string.playlist_song_count, playlist.count),
        showTopSeparator = showTopSeparator,
        onClick = onClick,
        leading = {
            AsyncImage(
                model = playlist.cover.smallImage(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(44.dp).clip(ContinuousRoundedRectangle(9.dp)),
            )
        },
    )
}
