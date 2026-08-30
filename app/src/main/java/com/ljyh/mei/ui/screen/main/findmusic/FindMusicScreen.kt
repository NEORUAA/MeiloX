package com.ljyh.mei.ui.screen.main.findmusic

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import coil3.compose.AsyncImage
import com.kyant.capsule.ContinuousRoundedRectangle
import com.ljyh.mei.R
import com.ljyh.mei.data.model.weapi.Playlists
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.ui.component.GlobalProfileAvatarButton
import com.ljyh.mei.ui.glass.IosPinnedPage
import com.ljyh.mei.ui.glass.IosTypography
import com.ljyh.mei.ui.glass.LocalGlassColors
import com.ljyh.mei.ui.glass.SfIcon
import com.ljyh.mei.ui.glass.rememberIosGridCollapseProgress
import com.ljyh.mei.ui.local.LocalNavController
import com.ljyh.mei.ui.local.LocalPlayerAwareWindowInsets
import com.ljyh.mei.ui.screen.Screen
import java.util.Locale

/** MeloX Discover layout: large title, compact filter pills, one hero and a two-column grid. */
@Composable
fun FindMusicScreen(
    viewModel: FindMusicViewModel = hiltViewModel(),
    modifier: Modifier = Modifier,
    initialCategory: String? = null,
    titleOverride: String? = null,
) {
    val navController = LocalNavController.current
    val playlistState by viewModel.highQualityPlaylist.collectAsState()
    val selectedCategory by viewModel.selectedCategory.collectAsState()
    val listState = rememberLazyGridState()
    val collapseProgress = rememberIosGridCollapseProgress(listState)
    val title = titleOverride ?: stringResource(R.string.app_tab_explore)
    val bottom = LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateBottomPadding()
    val glassColors = LocalGlassColors.current
    LaunchedEffect(initialCategory) {
        if (!initialCategory.isNullOrEmpty() && initialCategory != selectedCategory) {
            viewModel.onCategorySelected(initialCategory)
        }
    }
    LaunchedEffect(selectedCategory) { listState.scrollToItem(0) }

    IosPinnedPage(
        title = title,
        bottomPadding = bottom,
        modifier = modifier,
        collapseProgress = collapseProgress,
        backgroundColor = if (glassColors.isDark) glassColors.groupedBackground else Color.White,
        actions = { GlobalProfileAvatarButton() },
    ) { contentPadding ->
        Box(Modifier.fillMaxSize()) {
            when (val state = playlistState) {
                Resource.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is Resource.Success -> PlaylistGrid(
                    playlists = state.data.playlists,
                    listState = listState,
                    title = title,
                    topPadding = contentPadding.calculateTopPadding(),
                    categories = viewModel.categories,
                    selectedCategory = selectedCategory,
                    onCategorySelected = viewModel::onCategorySelected,
                    onPlaylistClick = { id -> Screen.PlayList.navigate(navController) { addPath(id.toString()) } },
                )
                is Resource.Error -> Column(
                    Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(stringResource(R.string.load_failed), style = IosTypography.headline)
                    Text(state.message, style = IosTypography.subheadline, color = LocalGlassColors.current.secondaryContent)
                }
            }
        }
    }
}

@Composable
private fun CategorySelector(
    categories: List<String>,
    selectedCategory: String,
    onCategorySelected: (String) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(categories) { category ->
            val selected = category == selectedCategory
            Text(
                text = category,
                color = if (selected) Color.White else LocalGlassColors.current.content,
                style = IosTypography.subheadline,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier
                    .clip(ContinuousRoundedRectangle(50))
                    .background(if (selected) MaterialTheme.colorScheme.primary else LocalGlassColors.current.groupedBackground)
                    .clickable { onCategorySelected(category) }
                    .padding(horizontal = 15.dp, vertical = 7.dp),
            )
        }
    }
}

@Composable
private fun PlaylistGrid(
    playlists: List<Playlists>,
    listState: LazyGridState,
    title: String,
    topPadding: androidx.compose.ui.unit.Dp,
    categories: List<String>,
    selectedCategory: String,
    onCategorySelected: (String) -> Unit,
    onPlaylistClick: (Long) -> Unit,
) {
    val bottom = LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateBottomPadding()
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(top = topPadding, bottom = bottom),
        horizontalArrangement = Arrangement.spacedBy(0.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        state = listState,
    ) {
        item(key = "discover-large-title", span = { GridItemSpan(maxLineSpan) }) {
            Text(
                title,
                style = IosTypography.largeTitle,
                modifier = Modifier
                    .offset(y = (-10).dp)
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
        item(key = "discover-categories", span = { GridItemSpan(maxLineSpan) }) {
            CategorySelector(categories, selectedCategory, onCategorySelected)
        }
        playlists.firstOrNull()?.let { featured ->
            item(key = "featured-${featured.id}", span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.padding(horizontal = 16.dp)) {
                    FeaturedPlaylistCard(featured, onPlaylistClick)
                }
            }
        }
        itemsIndexed(items = playlists.drop(1), key = { _, playlist -> playlist.id }) { index, playlist ->
            PlaylistCard(
                playlist = playlist,
                onClick = onPlaylistClick,
                modifier = Modifier.padding(
                    start = if (index % 2 == 0) 16.dp else 6.dp,
                    end = if (index % 2 == 0) 6.dp else 16.dp,
                ),
            )
        }
    }
}

@Composable
private fun FeaturedPlaylistCard(playlist: Playlists, onClick: (Long) -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1.12f)
            .clip(ContinuousRoundedRectangle(22.dp))
            .clickable { onClick(playlist.id) },
    ) {
        AsyncImage(playlist.coverImgUrl, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.72f)), startY = 280f)))
        SfIcon("music.note", null, Modifier.align(Alignment.TopStart).padding(18.dp), tint = Color.White, size = 38.dp, weight = FontWeight.Bold)
        Column(Modifier.align(Alignment.BottomStart).padding(18.dp)) {
            Text(playlist.copywriter.ifBlank { stringResource(R.string.app_tab_explore) }, style = IosTypography.caption, color = Color.White.copy(alpha = 0.82f))
            Text(playlist.name, style = IosTypography.title2, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("▶  ${formatPlayCount(playlist.playCount)}", style = IosTypography.subheadline, color = Color.White.copy(alpha = 0.82f), modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun PlaylistCard(
    playlist: Playlists,
    onClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().clickable { onClick(playlist.id) }) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(ContinuousRoundedRectangle(16.dp))) {
            AsyncImage(playlist.coverImgUrl, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Row(
                Modifier.align(Alignment.TopEnd).padding(6.dp).background(Color.Black.copy(alpha = 0.42f), ContinuousRoundedRectangle(50)).padding(horizontal = 7.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SfIcon("play.fill", null, tint = Color.White, size = 11.dp)
                Spacer(Modifier.width(3.dp))
                Text(formatPlayCount(playlist.playCount), style = IosTypography.caption, color = Color.White, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(7.dp))
        Text(playlist.name, style = IosTypography.subheadline, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

private fun formatPlayCount(count: Int): String = when {
    count >= 100_000_000 -> String.format(Locale.getDefault(), "%.1f亿", count / 100_000_000.0)
    count >= 10_000 -> String.format(Locale.getDefault(), "%.1f万", count / 10_000.0)
    else -> count.toString()
}
