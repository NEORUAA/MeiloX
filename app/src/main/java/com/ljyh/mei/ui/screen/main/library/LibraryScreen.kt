package com.ljyh.mei.ui.screen.main.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ljyh.mei.ui.navigation.MeiNavigator
import com.ljyh.mei.R
import androidx.compose.ui.graphics.Color
import com.ljyh.mei.constants.LibraryStyle
import com.ljyh.mei.constants.LibraryStyleKey
import com.ljyh.mei.utils.rememberEnumPreference
import com.ljyh.mei.ui.glass.IosListRow
import com.ljyh.mei.ui.glass.LocalGlassColors
import com.ljyh.mei.ui.glass.SfIcon
import androidx.datastore.preferences.core.stringPreferencesKey
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.ui.local.LocalNavController
import com.ljyh.mei.ui.local.LocalPlayerAwareWindowInsets
import com.ljyh.mei.ui.model.toAlbum
import com.ljyh.mei.ui.screen.Screen
import com.ljyh.mei.ui.component.GlobalProfileAvatarButton
import com.ljyh.mei.ui.glass.IosPinnedListPage
import com.ljyh.mei.ui.screen.main.library.component.LibraryMobileLayout
import com.ljyh.mei.ui.screen.main.library.component.PhotoPickerSheet
import com.ljyh.mei.ui.navigation.LibraryPage
import com.ljyh.mei.utils.rememberPreference

@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel = viewModel(),
    isNavigationTab: Boolean = false,
    category: LibraryPage? = null,
) {
    val libraryStyle by rememberEnumPreference(LibraryStyleKey, LibraryStyle.Default)
    if (category == null && libraryStyle == LibraryStyle.AppleMusic) {
        LibraryCategoryList(isNavigationTab)
        return
    }
    val navController = LocalNavController.current
    val state = viewModel.state.collectAsState().value
    val userId = state.userId
    val photoAlbum = state.photos
    val albumList = state.albums
    val (userPhoto, setUserPhoto) = key(userId) {
        rememberPreference(stringPreferencesKey("official_user_photo_$userId"), "")
    }

    // State
    var showPhotoPicker by remember(userId) { mutableStateOf(false) }
    var selectedPage by rememberSaveable { mutableStateOf(LibraryPage.Songs) }
    val visiblePlaylists = remember(state.playlists) {
        state.playlists.filterNot { it.isLiked }.map { it.playlist }
    }
    val (createdPlaylists, collectedPlaylists) = remember(visiblePlaylists, userId) {
        if (userId.isEmpty()) Pair(emptyList(), emptyList())
        else {
            val (created, collected) = visiblePlaylists.partition { it.author == userId }
            fun sorted(playlists: List<com.ljyh.mei.data.model.room.Playlist>): List<com.ljyh.mei.data.model.room.Playlist> {
                val maxLocalPlayCount = playlists.maxOfOrNull { it.localPlayCount } ?: 1
                val maxServerPlayCount = playlists.maxOfOrNull { it.playCount } ?: 1L
                val now = System.currentTimeMillis()
                return playlists.sortedByDescending {
                    it.sortScore(maxLocalPlayCount, maxServerPlayCount, now)
                }
            }
            Pair(sorted(created), sorted(collected))
        }
    }

    LaunchedEffect(userId, photoAlbum) {
        if (userId.isNotEmpty() && userPhoto.isEmpty() && photoAlbum is Resource.Success) {
            photoAlbum.data.data.records.firstOrNull()?.imageUrl?.let {
                setUserPhoto(it)
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {

        if (userId.isNotEmpty()) {
            key(userId) {
                LibraryMobileLayout(
                    userPhoto = userPhoto,
                    isNavigationTab = isNavigationTab,
                    selectedPage = category ?: selectedPage,
                    isCategoryPage = category != null,
                    onPageSelect = { selectedPage = it },
                    createdPlaylists = createdPlaylists,
                    collectedPlaylists = collectedPlaylists,
                    albums = if (albumList is Resource.Success) albumList.data.data.map { it.toAlbum() } else emptyList(),
                    onPlaylistClick = { id->
                        Screen.PlayList.navigate(navController) { addPath(id) }
                    },
                    onAlbumClick = { id->
                        Screen.Album.navigate(navController) { addPath(id) }
                    },
                    userId = userId,
                    session = state.session,
                    likedSongs = state.likedSongs,
                    likedSongsLoading = state.playlistsLoading || state.likedSongsLoading,
                    isRefreshing = state.playlistsLoading || state.likedSongsLoading || state.albums is Resource.Loading,
                    error = when (category ?: selectedPage) {
                        LibraryPage.Songs -> state.likedSongsError ?: state.playlistsError
                        LibraryPage.Playlists -> state.playlistsError ?: (state.albums as? Resource.Error)?.message
                        else -> null
                    },
                )
            }

            if (showPhotoPicker) {
                PhotoPickerSheet(
                    photoAlbum = photoAlbum,
                    onSelect = { setUserPhoto(it); showPhotoPicker = false },
                    onDismiss = { showPhotoPicker = false }
                )
            }
        } else {
            // 未登录逻辑
            EmptyLoginState(navController, isNavigationTab, category)
        }
    }
}

@Composable
fun EmptyLoginState(
    navController: MeiNavigator,
    isNavigationTab: Boolean = false,
    category: LibraryPage? = null,
) {
    val insets = LocalPlayerAwareWindowInsets.current.asPaddingValues()
    IosPinnedListPage(
        title = stringResource(category?.titleRes ?: R.string.app_tab_library),
        bottomPadding = insets.calculateBottomPadding(),
        onNavigateBack = if (isNavigationTab) null else ({ navController.navigateUp() }),
        actions = {
            if (isNavigationTab) GlobalProfileAvatarButton()
        },
    ) {
        item {
            Box(
                modifier = Modifier.fillParentMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                com.ljyh.mei.ui.glass.GlassCard(
                    modifier = Modifier.padding(24.dp),
                    onClick = { Screen.NeteaseLogin.navigate(navController) },
                ) {
                    androidx.compose.foundation.layout.Column(
                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        com.ljyh.mei.ui.glass.SfIcon("person.crop.circle", null, size = 42.dp)
                        Text(
                            stringResource(com.ljyh.mei.R.string.library_sign_in),
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryCategoryList(isNavigationTab: Boolean) {
    val navController = LocalNavController.current
    val colors = LocalGlassColors.current
    val insets = LocalPlayerAwareWindowInsets.current.asPaddingValues()
    IosPinnedListPage(
        title = stringResource(R.string.app_tab_library),
        bottomPadding = insets.calculateBottomPadding(),
        horizontalContentPadding = 6.dp,
        largeTitleHorizontalPadding = 14.dp,
        verticalArrangement = Arrangement.spacedBy(0.dp),
        backgroundColor = if (colors.isDark) colors.groupedBackground else Color.White,
        onNavigateBack = if (isNavigationTab) null else ({ navController.navigateUp() }),
        actions = { if (isNavigationTab) GlobalProfileAvatarButton() },
    ) {
        LibraryPage.entries.forEachIndexed { index, page ->
            item(key = "library-category:${page.name}") {
                IosListRow(
                    title = stringResource(page.titleRes),
                    leading = { SfIcon(page.symbol, null, tint = colors.accent) },
                    showTopSeparator = index > 0,
                    onClick = {
                        Screen.LibraryCategory.navigate(navController) { addPath(page.name) }
                    },
                )
            }
        }
    }
}
