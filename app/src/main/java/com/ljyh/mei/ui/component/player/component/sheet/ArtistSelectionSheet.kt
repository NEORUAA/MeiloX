package com.ljyh.mei.ui.component.player.component.sheet

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ljyh.mei.R
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.ui.component.sheet.rememberMeiloXSheetDismissal
import io.github.neoruaa.glasmio.icons.SfIcon
import io.github.neoruaa.glasmio.scaffold.rememberGlasmioHeaderScrollEdge
import io.github.neoruaa.glasmio.ui.GlasmioBottomSheet
import io.github.neoruaa.glasmio.ui.GlasmioGroupedList
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import io.github.neoruaa.glasmio.ui.GlasmioSheetTopToolbar
import io.github.neoruaa.glasmio.ui.GlasmioSheetToolbarButton
import io.github.neoruaa.glasmio.ui.LocalGlasmioSheetContentPadding

@Composable
fun ArtistSelectionSheet(
    artists: List<MediaMetadata.Artist>,
    onDismiss: () -> Unit,
    onArtistClick: (MediaMetadata.Artist) -> Unit,
) {
    val dismissal = rememberMeiloXSheetDismissal(onDismiss)
    val listState = rememberLazyListState()
    GlasmioBottomSheet(
        onDismissRequest = dismissal::onDismissed,
        sheetState = dismissal.state,
        skipPartiallyExpanded = false,
        fitContent = false,
        title = "选择艺人",
        topBarBlurProgress = rememberGlasmioHeaderScrollEdge(listState),
        topBar = {
            GlasmioSheetTopToolbar(
                title = "选择艺人",
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
        ) {
            item {
                GlasmioGroupedList {
                    artists.forEachIndexed { index, artist ->
                        GlasmioListRow(
                            title = artist.name,
                            systemName = "person.fill",
                            showTopSeparator = index > 0,
                            enabled = !dismissal.isDismissing,
                            onClick = { dismissal.requestDismiss { onArtistClick(artist) } },
                        )
                    }
                }
            }
        }
    }
}
