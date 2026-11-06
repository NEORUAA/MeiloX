package com.ljyh.mei.ui.screen.main.library

import com.ljyh.mei.ui.navigation.MeiloXLoadingPage
import io.github.neoruaa.glasmio.ui.GlasmioCard
import io.github.neoruaa.glasmio.ui.GlasmioGroupedList

import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.paging.compose.collectAsLazyPagingItems
import com.ljyh.mei.ui.navigation.MeiNavigator
import com.ljyh.mei.R
import androidx.compose.ui.graphics.Color
import com.ljyh.mei.constants.LibraryStyle
import com.ljyh.mei.constants.LibraryDefaultPageKey
import com.ljyh.mei.constants.LibraryStyleKey
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import io.github.neoruaa.glasmio.core.LocalGlassColors
import io.github.neoruaa.glasmio.icons.SfIcon
import com.ljyh.mei.constants.CookieKey
import com.ljyh.mei.constants.CloudMusicEnabledKey
import com.ljyh.mei.constants.DownloadsEnabledKey
import com.ljyh.mei.constants.ListeningHistoryEnabledKey
import com.ljyh.mei.constants.PodcastsEnabledKey
import com.ljyh.mei.constants.UserIdKey
import com.ljyh.mei.ui.local.LocalNavController
import com.ljyh.mei.ui.local.LocalPlayerAwareWindowInsets
import com.ljyh.mei.ui.screen.Screen
import com.ljyh.mei.source.api.LibraryCollection
import com.ljyh.mei.ui.screen.source.SourceLibraryScreen
import com.ljyh.mei.ui.screen.search.SourceSearchViewModel
import com.ljyh.mei.ui.screen.search.sourcePickerHeaderAction
import com.ljyh.mei.ui.screen.setting.DownloadManageScreen
import io.github.neoruaa.glasmio.scaffold.GlasmioHeaderAction
import com.ljyh.mei.ui.component.globalProfileHeaderAction
import com.ljyh.mei.ui.navigation.MeiloXPinnedListPage
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    isNavigationTab: Boolean = false,
    category: LibraryPage? = null,
    sourceViewModel: SourceSearchViewModel = hiltViewModel(),
    nativeViewModelKey: String? = null,
    shareNativeViewModel: Boolean = false,
) {
    val settings by sourceViewModel.settings.collectAsState()
    val providers by sourceViewModel.libraryChoices.collectAsState()
    val nav = LocalNavController.current
    LaunchedEffect(sourceViewModel) { sourceViewModel.useBrowsePreference() }
    val providerId = settings?.preferredBrowseProvider
    val route = if (category == null) Screen.Library.route else Screen.LibraryCategory.route
    when {
        providerId == null -> MeiloXLoadingPage(
            title = stringResource(category?.titleRes ?: R.string.app_tab_library),
            onNavigateBack = if (isNavigationTab) null else ({ nav.navigateUp() }),
        )
        sourceViewModel.supportsFeatureRoute(providerId, route) -> {
            val nativeViewModel = if (shareNativeViewModel) hiltViewModel<LibraryViewModel>(
                viewModelStoreOwner = LocalActivity.current as ComponentActivity, key = nativeViewModelKey,
            ) else hiltViewModel<LibraryViewModel>(key = nativeViewModelKey)
            NeteaseLibraryScreen(nativeViewModel, isNavigationTab, category,
                sourceAction = if (category == null) ({
                    sourcePickerHeaderAction(providers, providerId, sourceViewModel::selectProvider)
                }) else null)
        }
        category == LibraryPage.Downloads -> DownloadManageScreen(
            scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(), isNavigationTab = isNavigationTab,
        )
        category == null || category == LibraryPage.Songs || category == LibraryPage.Playlists || category == LibraryPage.Albums -> {
            SourceLibraryScreen(isNavigationTab = isNavigationTab, initialCollection = when (category) {
                LibraryPage.Songs -> LibraryCollection.LIKED_TRACKS
                LibraryPage.Playlists -> LibraryCollection.SAVED_PLAYLISTS
                LibraryPage.Albums -> LibraryCollection.SAVED_ALBUMS
                else -> null
            })
        }
        else -> MeiloXPinnedListPage(
            title = stringResource(category?.titleRes ?: R.string.app_tab_library),
            bottomPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateBottomPadding(),
            onNavigateBack = if (isNavigationTab) null else ({ nav.navigateUp() }),
            actions = { listOfNotNull(
                sourcePickerHeaderAction(providers, providerId, sourceViewModel::selectProvider),
                if (isNavigationTab) globalProfileHeaderAction() else null,
            ) },
        ) {
            item("source-library-unsupported") { GlasmioGroupedList {
                GlasmioListRow(title = "此音源暂不支持这项功能", showTopSeparator = false, showDisclosure = false)
            } }
        }
    }
}

@Composable
private fun NeteaseLibraryScreen(
    viewModel: LibraryViewModel,
    isNavigationTab: Boolean = false,
    category: LibraryPage? = null,
    sourceAction: (@Composable () -> GlasmioHeaderAction)? = null,
) {
    val context = LocalContext.current
    val navController = LocalNavController.current
    val preferenceState by context.dataStore.data.collectAsState(initial = null)
    val preferences = preferenceState
    if (preferences == null) {
        MeiloXLoadingPage(
            title = stringResource(category?.titleRes ?: R.string.app_tab_library),
            onNavigateBack = if (isNavigationTab) null else ({ navController.navigateUp() }),
        )
        return
    }
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
        LibraryCategoryList(isNavigationTab, podcastsEnabled, sourceAction)
        return
    }
    val playlists by viewModel.playlists.collectAsState()
    val albums by viewModel.albums.collectAsState()
    val accountId by viewModel.accountId.collectAsState()
    val likedSongs = viewModel.likedSongPages.collectAsLazyPagingItems()
    val likedSongIds by viewModel.likedSongIds.collectAsState()
    val likedPlaylistId by viewModel.likedPlaylistId.collectAsState()
    val likedSongSearchLoading by viewModel.likedSongSearchLoading.collectAsState()
    val likedSongsLoading by viewModel.likedSongsLoading.collectAsState()
    val likedSongsError by viewModel.likedSongsError.collectAsState()
    val userId = preferences[UserIdKey].orEmpty()
    val cookie = preferences[CookieKey].orEmpty()
    val mergeAlbumPodcasts = libraryStyle == LibraryStyle.Default && category == null
    val displayedPage = category ?: if (mergeAlbumPodcasts && selectedPage == LibraryPage.Podcasts) {
        LibraryPage.Albums
    } else selectedPage
    val accountMatches = accountId == userId && cookie.isNotBlank()
    LaunchedEffect(displayedPage, userId, cookie) {
        viewModel.selectPage(displayedPage)
    }
    DisposableEffect(viewModel) {
        onDispose { viewModel.leavePage() }
    }
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
            likedSongs = likedSongs,
            likedSongIds = if (accountMatches) likedSongIds else emptyList(),
            likedPlaylistId = if (accountMatches) likedPlaylistId else null,
            likedSongSearchLoading = likedSongSearchLoading,
            onLikedSongQueryChange = viewModel::setLikedSongQuery,
            likedSongsLoading = !accountMatches || likedSongsLoading,
            likedSongsError = if (accountMatches) likedSongsError else null,
            onRetryLikedSongs = viewModel::retryLikedSongs,
            sourceAction = sourceAction,
        )
    } else {
        EmptyLoginState(navController, isNavigationTab, category, sourceAction)
    }
}

@Composable
fun EmptyLoginState(
    navController: MeiNavigator,
    isNavigationTab: Boolean = false,
    category: LibraryPage? = null,
    sourceAction: (@Composable () -> GlasmioHeaderAction)? = null,
) {
    val insets = LocalPlayerAwareWindowInsets.current.asPaddingValues()
    MeiloXPinnedListPage(
        title = stringResource(category?.titleRes ?: R.string.app_tab_library),
        bottomPadding = insets.calculateBottomPadding(),
        onNavigateBack = if (isNavigationTab) null else ({ navController.navigateUp() }),
        actions = { listOfNotNull(sourceAction?.invoke(), if (isNavigationTab) globalProfileHeaderAction() else null) },
    ) {
        if (category == null) {
            item(key = "signed-out-downloads") {
                io.github.neoruaa.glasmio.ui.GlasmioGroupedList {
                    GlasmioListRow(
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
                io.github.neoruaa.glasmio.ui.GlasmioCard(
                    modifier = Modifier.padding(24.dp),
                    onClick = { Screen.NeteaseLogin.navigate(navController) },
                ) {
                    androidx.compose.foundation.layout.Column(
                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        io.github.neoruaa.glasmio.icons.SfIcon("person.crop.circle", null, size = 42.dp)
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
private fun LibraryCategoryList(isNavigationTab: Boolean, podcastsEnabled: Boolean, sourceAction: (@Composable () -> GlasmioHeaderAction)? = null) {
    val categories = LibraryPage.entries.filter { it != LibraryPage.Podcasts || podcastsEnabled }
    val navController = LocalNavController.current
    val colors = LocalGlassColors.current
    val insets = LocalPlayerAwareWindowInsets.current.asPaddingValues()
    MeiloXPinnedListPage(
        title = stringResource(R.string.app_tab_library),
        bottomPadding = insets.calculateBottomPadding(),
        horizontalContentPadding = 6.dp,
        largeTitleHorizontalPadding = 14.dp,
        verticalArrangement = Arrangement.spacedBy(0.dp),
        backgroundColor = if (colors.isDark) colors.groupedBackground else Color.White,
        onNavigateBack = if (isNavigationTab) null else ({ navController.navigateUp() }),
        actions = { listOfNotNull(sourceAction?.invoke(), if (isNavigationTab) globalProfileHeaderAction() else null) },
    ) {
        categories.forEachIndexed { index, page ->
            item(key = "library-category:${page.name}") {
                GlasmioListRow(
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
