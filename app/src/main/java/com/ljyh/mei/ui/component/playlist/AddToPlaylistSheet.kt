package com.ljyh.mei.ui.component.playlist

import com.ljyh.mei.ui.component.sheet.MeiloXBottomSheet
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.itemsIndexed
import io.github.neoruaa.glasmio.ui.glasmioGroupedListBackground
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import io.github.neoruaa.glasmio.scaffold.rememberGlasmioHeaderScrollEdge
import io.github.neoruaa.glasmio.ui.LocalGlasmioSheetContentPadding
import com.ljyh.mei.ui.component.sheet.rememberMeiloXSheetDismissal
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
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
    val dismissal = rememberMeiloXSheetDismissal(onDismiss)
    val listState = rememberLazyListState()
    val title = stringResource(R.string.add_to_playlist_title)
    MeiloXBottomSheet(
        onDismissRequest = dismissal::onDismissed,
        sheetState = dismissal.state,
        skipPartiallyExpanded = false,
        fitContent = false,
        title = title,
        topBarBlurProgress = rememberGlasmioHeaderScrollEdge(listState),
        topBar = {
            GlasmioSheetTopToolbar(title = title, actions = {
                GlasmioSheetToolbarButton(onClick = { dismissal.requestDismiss() }) {
                    SfIcon(SfSymbol.Close, stringResource(R.string.cancel))
                }
            })
        },
    ) {
        val padding = LocalGlasmioSheetContentPadding.current
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            item {
                GlasmioGroupedList(Modifier.padding(bottom = 24.dp)) {
                    GlasmioListRow(
                        title = stringResource(R.string.create_playlist_title),
                        systemName = "plus.circle",
                        showTopSeparator = false,
                        onClick = { dismissal.requestDismiss(onCreateNewPlaylist) },
                    )
                }
            }
            itemsIndexed(playlists, key = { _, playlist -> "playlist:${playlist.id}" }) { index, playlist ->
                val shape = ContinuousRoundedRectangle(
                    topStart = if (index == 0) 26.dp else 0.dp,
                    topEnd = if (index == 0) 26.dp else 0.dp,
                    bottomStart = if (index == playlists.lastIndex) 26.dp else 0.dp,
                    bottomEnd = if (index == playlists.lastIndex) 26.dp else 0.dp,
                )
                Box(Modifier.glasmioGroupedListBackground(shape)) {
                    PlaylistSelectionItem(
                        playlist = playlist,
                        showTopSeparator = index > 0,
                        onClick = { dismissal.requestDismiss { onSelectPlaylist(playlist) } },
                    )
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
