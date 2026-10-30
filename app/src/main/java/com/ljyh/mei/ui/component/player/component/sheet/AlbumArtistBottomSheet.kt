package com.ljyh.mei.ui.component.player.component.sheet

import com.ljyh.mei.ui.component.sheet.MeiloXBottomSheet
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.kyant.capsule.ContinuousRoundedRectangle
import com.ljyh.mei.R
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.ui.component.sheet.rememberMeiloXSheetDismissal
import com.ljyh.mei.utils.smallImage
import io.github.neoruaa.glasmio.icons.SfIcon
import io.github.neoruaa.glasmio.scaffold.rememberGlasmioHeaderScrollEdge
import io.github.neoruaa.glasmio.ui.GlasmioGroupedList
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import io.github.neoruaa.glasmio.ui.GlasmioSectionTitle
import io.github.neoruaa.glasmio.ui.GlasmioSheetTopToolbar
import io.github.neoruaa.glasmio.ui.GlasmioSheetToolbarButton
import io.github.neoruaa.glasmio.ui.LocalGlasmioSheetContentPadding

@Composable
fun AlbumArtistBottomSheet(
    albumInfo: MediaMetadata.Album,
    artistList: List<MediaMetadata.Artist>,
    coverUrl: String,
    onAlbumClick: (Long) -> Unit,
    onArtistClick: (Long) -> Unit,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val navigableArtists = artistList.filter { it.id > 0 }
    val dismissal = rememberMeiloXSheetDismissal(onDismissRequest)
    val listState = rememberLazyListState()
    MeiloXBottomSheet(
        modifier = modifier,
        onDismissRequest = dismissal::onDismissed,
        sheetState = dismissal.state,
        skipPartiallyExpanded = false,
        fitContent = false,
        title = "前往",
        topBarBlurProgress = rememberGlasmioHeaderScrollEdge(listState),
        topBar = {
            GlasmioSheetTopToolbar(
                title = "前往",
                actions = {
                    GlasmioSheetToolbarButton(
                        onClick = { dismissal.requestDismiss() },
                        enabled = !dismissal.isDismissing,
                    ) { SfIcon("xmark", stringResource(R.string.cancel)) }
                },
            )
        },
    ) {
        val padding = LocalGlasmioSheetContentPadding.current
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(
                start = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                end = 16.dp,
                bottom = padding.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { GlasmioSectionTitle("专辑") }
            item {
                GlasmioGroupedList {
                    GlasmioListRow(
                        title = albumInfo.title,
                        subtitle = "查看专辑",
                        showTopSeparator = false,
                        enabled = !dismissal.isDismissing,
                        leading = {
                            AsyncImage(
                                model = ImageRequest.Builder(context)
                                    .data(coverUrl.smallImage())
                                    .crossfade(true)
                                    .build(),
                                contentDescription = "Album Cover",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(56.dp).clip(ContinuousRoundedRectangle(12.dp)),
                            )
                        },
                        onClick = { dismissal.requestDismiss { onAlbumClick(albumInfo.id) } },
                    )
                }
            }
            if (navigableArtists.isNotEmpty()) {
                item { GlasmioSectionTitle("歌手") }
                item {
                    GlasmioGroupedList {
                        navigableArtists.forEachIndexed { index, artist ->
                            GlasmioListRow(
                                title = artist.name,
                                systemName = "person.fill",
                                showTopSeparator = index > 0,
                                enabled = !dismissal.isDismissing,
                                onClick = { dismissal.requestDismiss { onArtistClick(artist.id) } },
                            )
                        }
                    }
                }
            }
        }
    }
}
