package com.ljyh.mei.ui.screen.main.library.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.kyant.capsule.ContinuousRoundedRectangle
import com.ljyh.mei.R
import com.ljyh.mei.data.model.AlbumPhoto
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.ui.component.sheet.rememberMeiloXSheetDismissal
import io.github.neoruaa.glasmio.icons.SfIcon
import io.github.neoruaa.glasmio.scaffold.rememberGlasmioHeaderScrollEdge
import io.github.neoruaa.glasmio.ui.GlasmioBottomSheet
import io.github.neoruaa.glasmio.ui.GlasmioSheetTopToolbar
import io.github.neoruaa.glasmio.ui.GlasmioSheetToolbarButton
import io.github.neoruaa.glasmio.ui.LocalGlasmioSheetContentPadding

@Composable
fun PhotoPickerSheet(
    photoAlbum: Resource<AlbumPhoto>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val dismissal = rememberMeiloXSheetDismissal(onDismiss)
    val gridState = rememberLazyGridState()
    GlasmioBottomSheet(
        onDismissRequest = dismissal::onDismissed,
        sheetState = dismissal.state,
        skipPartiallyExpanded = false,
        fitContent = false,
        title = "选择图片",
        topBarBlurProgress = rememberGlasmioHeaderScrollEdge(gridState),
        topBar = {
            GlasmioSheetTopToolbar("选择图片", actions = {
                GlasmioSheetToolbarButton(onClick = { dismissal.requestDismiss() }) {
                    SfIcon("xmark", stringResource(R.string.cancel))
                }
            })
        },
    ) {
        val padding = LocalGlasmioSheetContentPadding.current
        LazyVerticalGrid(
            columns = GridCells.Adaptive(96.dp),
            state = gridState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (photoAlbum) {
                is Resource.Success -> items(photoAlbum.data.data.records.size) { index ->
                    val image = photoAlbum.data.data.records[index].imageUrl
                    AsyncImage(
                        model = image,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.aspectRatio(1f).clip(ContinuousRoundedRectangle(12.dp))
                            .clickable { dismissal.requestDismiss { onSelect(image) } },
                    )
                }
                else -> item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                        if (photoAlbum is Resource.Loading) CircularProgressIndicator()
                        else Text("无法加载图片")
                    }
                }
            }
        }
    }
}
