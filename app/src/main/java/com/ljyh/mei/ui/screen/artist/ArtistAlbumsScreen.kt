package com.ljyh.mei.ui.screen.artist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.ljyh.mei.R
import com.ljyh.mei.ui.component.item.AlbumItem
import com.ljyh.mei.ui.glass.IosListRow
import com.ljyh.mei.ui.glass.IosPinnedListPage
import com.ljyh.mei.ui.glass.LocalGlassColors
import com.ljyh.mei.ui.local.LocalNavController
import com.ljyh.mei.ui.local.LocalPlayerAwareWindowInsets
import com.ljyh.mei.ui.screen.Screen

@Composable
fun ArtistAlbumsScreen(
    id: String,
    viewModel: ArtistAlbumsViewModel = hiltViewModel(key = "artist-albums:$id"),
) {
    val navController = LocalNavController.current
    val state by viewModel.state.collectAsState()

    IosPinnedListPage(
        title = stringResource(R.string.artist_all_albums),
        bottomPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateBottomPadding(),
        horizontalContentPadding = 0.dp,
        largeTitleHorizontalPadding = 20.dp,
        verticalArrangement = Arrangement.spacedBy(0.dp),
        onNavigateBack = navController::popBackStack,
    ) {
        items(state.albums, key = { it.id }) { album ->
            AlbumItem(album) { albumId ->
                navController.navigate("${Screen.Album.route}/$albumId")
            }
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
        } else if (state.albums.isEmpty()) {
            item(key = "empty") {
                Text(
                    stringResource(R.string.artist_albums_empty),
                    modifier = Modifier.padding(20.dp),
                    color = LocalGlassColors.current.secondaryContent,
                )
            }
        }
    }
}
