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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.ljyh.mei.ui.navigation.MeiNavigator
import com.ljyh.mei.R
import androidx.compose.ui.graphics.Color
import com.ljyh.mei.constants.LibraryStyle
import com.ljyh.mei.constants.LibraryDefaultPageKey
import com.ljyh.mei.constants.LibraryStyleKey
import com.ljyh.mei.ui.glass.IosListRow
import com.ljyh.mei.ui.glass.LocalGlassColors
import com.ljyh.mei.ui.glass.SfIcon
import com.ljyh.mei.constants.CookieKey
import com.ljyh.mei.constants.CloudMusicEnabledKey
import com.ljyh.mei.constants.DownloadsEnabledKey
import com.ljyh.mei.constants.ListeningHistoryEnabledKey
import com.ljyh.mei.constants.PodcastsEnabledKey
import com.ljyh.mei.constants.UserIdKey
import com.ljyh.mei.ui.local.LocalNavController
import com.ljyh.mei.ui.local.LocalPlayerAwareWindowInsets
import com.ljyh.mei.ui.screen.Screen
import com.ljyh.mei.ui.component.GlobalProfileAvatarButton
import com.ljyh.mei.ui.glass.IosPinnedListPage
import com.ljyh.mei.ui.screen.main.library.component.LibraryMobileLayout
import com.ljyh.mei.ui.navigation.LibraryPage
import com.ljyh.mei.ui.navigation.ContentFeature
import com.ljyh.mei.utils.dataStore
import com.ljyh.mei.utils.toEnum

internal fun resolveLibraryDefaultPage(
    value: String,
    style: LibraryStyle,
    enabledFeatures: Set<ContentFeature>,
): LibraryPage? {
    val page = LibraryPage.entries.firstOrNull { it.name == value } ?: return null
    if (page.requiredFeature != null && page.requiredFeature !in enabledFeatures) return null
    return if (style == LibraryStyle.Default && page == LibraryPage.Podcasts) {
        LibraryPage.Albums
    } else page
}

@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel = hiltViewModel(),
    isNavigationTab: Boolean = false,
    category: LibraryPage? = null,
) {
    val context = LocalContext.current
    val preferenceState by context.dataStore.data.collectAsState(initial = null)
    val preferences = preferenceState ?: return
    val libraryStyle = preferences[LibraryStyleKey].toEnum(LibraryStyle.Default)
    val defaultPagePreference = preferences[LibraryDefaultPageKey].orEmpty()
    val podcastsEnabled = preferences[PodcastsEnabledKey] ?: true
    val enabledFeatures = buildSet {
        if (podcastsEnabled) add(ContentFeature.Podcasts)
        if (preferences[DownloadsEnabledKey] != false) add(ContentFeature.Downloads)
        if (preferences[CloudMusicEnabledKey] != false) add(ContentFeature.CloudMusic)
        if (preferences[ListeningHistoryEnabledKey] != false) add(ContentFeature.ListeningHistory)
    }
    val defaultPage = resolveLibraryDefaultPage(defaultPagePreference, libraryStyle, enabledFeatures)
    val navController = LocalNavController.current
    var defaultPageApplied by rememberSaveable { mutableStateOf(false) }
    var selectedPage by rememberSaveable { mutableStateOf(defaultPage ?: LibraryPage.Songs) }
    LaunchedEffect(Unit) {
        if (!defaultPageApplied) {
            defaultPageApplied = true
            if (category == null && libraryStyle == LibraryStyle.AppleMusic) {
                defaultPage?.let { page ->
                    Screen.LibraryCategory.navigate(navController) { addPath(page.name) }
                }
            }
        }
    }
    if (category == null && libraryStyle == LibraryStyle.AppleMusic) {
        LibraryCategoryList(isNavigationTab, podcastsEnabled)
        return
    }
    val playlists by viewModel.playlists.collectAsState()
    val albums by viewModel.albums.collectAsState()
    val accountId by viewModel.accountId.collectAsState()
    val likedSongs by viewModel.likedSongs.collectAsState()
    val likedSongsLoading by viewModel.likedSongsLoading.collectAsState()
    val likedSongsError by viewModel.likedSongsError.collectAsState()
    val userId = preferences[UserIdKey].orEmpty()
    val cookie = preferences[CookieKey].orEmpty()
    val mergeAlbumPodcasts = libraryStyle == LibraryStyle.Default && category == null
    val displayedPage = category ?: if (mergeAlbumPodcasts && selectedPage == LibraryPage.Podcasts) {
        LibraryPage.Albums
    } else selectedPage
    val accountMatches = accountId == userId && cookie.isNotBlank()
    val (createdPlaylists, collectedPlaylists) = remember(playlists.items, accountMatches, userId) {
        if (accountMatches) playlists.items.partition { it.author == userId }
        else emptyList<com.ljyh.mei.data.model.room.Playlist>() to emptyList()
    }

    if (displayedPage == LibraryPage.Downloads || (userId.isNotBlank() && cookie.isNotBlank())) {
        LibraryMobileLayout(
            userPhoto = "",
            isNavigationTab = isNavigationTab,
            selectedPage = displayedPage,
            isCategoryPage = category != null,
            mergeAlbumPodcasts = mergeAlbumPodcasts,
            onPageSelect = { selectedPage = it },
            createdPlaylists = createdPlaylists,
            collectedPlaylists = collectedPlaylists,
            playlistState = if (accountMatches) playlists else LibraryCollectionState(),
            albumState = if (accountMatches) albums else LibraryCollectionState(),
            onRefreshPlaylists = viewModel::refreshPlaylists,
            onLoadMorePlaylists = viewModel::loadMorePlaylists,
            onRefreshAlbums = viewModel::refreshAlbums,
            onLoadMoreAlbums = viewModel::loadMoreAlbums,
            onPlaylistClick = { id -> Screen.PlayList.navigate(navController) { addPath(id) } },
            onAlbumClick = { id -> Screen.Album.navigate(navController) { addPath(id) } },
            userId = if (accountMatches) userId else "",
            likedSongs = if (accountMatches) likedSongs else emptyList(),
            likedSongsLoading = !accountMatches || likedSongsLoading,
            likedSongsError = if (accountMatches) likedSongsError else null,
            onRetryLikedSongs = viewModel::retryLikedSongs,
        )
    } else {
        EmptyLoginState(navController, isNavigationTab, category)
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
        if (category == null) {
            item(key = "signed-out-downloads") {
                com.ljyh.mei.ui.glass.IosGroupedList {
                    IosListRow(
                        title = stringResource(R.string.app_tab_library_downloads),
                        leading = { SfIcon(LibraryPage.Downloads.symbol, null, tint = LocalGlassColors.current.accent) },
                        showTopSeparator = false,
                        onClick = {
                            Screen.LibraryCategory.navigate(navController) { addPath(LibraryPage.Downloads.name) }
                        },
                    )
                }
            }
        }
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
private fun LibraryCategoryList(isNavigationTab: Boolean, podcastsEnabled: Boolean) {
    val categories = LibraryPage.entries.filter { it != LibraryPage.Podcasts || podcastsEnabled }
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
        categories.forEachIndexed { index, page ->
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
