package com.ljyh.mei.ui.screen.main.library

import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.ljyh.mei.R
import com.ljyh.mei.constants.LibraryStyle
import com.ljyh.mei.source.api.LibraryCollection
import com.ljyh.mei.ui.component.globalProfileHeaderAction
import com.ljyh.mei.ui.local.LocalNavController
import com.ljyh.mei.ui.local.LocalPlayerAwareWindowInsets
import com.ljyh.mei.ui.navigation.ContentFeature
import com.ljyh.mei.ui.navigation.LibraryPage
import com.ljyh.mei.ui.navigation.MeiloXLoadingPage
import com.ljyh.mei.ui.navigation.MeiloXPinnedListPage
import com.ljyh.mei.ui.screen.Screen
import com.ljyh.mei.ui.screen.search.SourceSearchViewModel
import com.ljyh.mei.ui.screen.search.sourcePickerHeaderAction
import com.ljyh.mei.ui.screen.source.SourceLibraryScreen
import io.github.neoruaa.glasmio.ui.GlasmioGroupedList
import io.github.neoruaa.glasmio.ui.GlasmioListRow

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
    isNavigationTab: Boolean = false,
    category: LibraryPage? = null,
    sourceViewModel: SourceSearchViewModel = hiltViewModel(key = "source-browse-choice"),
) {
    val settings by sourceViewModel.settings.collectAsState()
    val providers by sourceViewModel.libraryChoices.collectAsState()
    val nav = LocalNavController.current
    LaunchedEffect(sourceViewModel) { sourceViewModel.useBrowsePreference() }
    val providerId = settings?.preferredBrowseProvider
    when {
        providerId == null -> MeiloXLoadingPage(
            title = stringResource(category?.titleRes ?: R.string.app_tab_library),
            onNavigateBack = if (isNavigationTab) null else ({ nav.navigateUp() }),
        )
        category == LibraryPage.Downloads -> SourceLibraryScreen(isNavigationTab = isNavigationTab, category = category)
        category == null || category == LibraryPage.Songs || category == LibraryPage.Playlists || category == LibraryPage.Albums -> {
            SourceLibraryScreen(isNavigationTab = isNavigationTab, initialCollection = when (category) {
                LibraryPage.Songs -> LibraryCollection.LIKED_TRACKS
                LibraryPage.Playlists -> LibraryCollection.SAVED_PLAYLISTS
                LibraryPage.Albums -> LibraryCollection.SAVED_ALBUMS
                else -> null
            }, category = category)
        }
        category == LibraryPage.History -> com.ljyh.mei.ui.screen.history.HistoryScreen(isNavigationTab = isNavigationTab)
        category == LibraryPage.Podcasts && sourceViewModel.supportsFeatureRoute(providerId, Screen.Podcasts.route) -> com.ljyh.mei.ui.screen.podcast.PodcastScreen(isNavigationTab = isNavigationTab)
        category == LibraryPage.Cloud && sourceViewModel.supportsFeatureRoute(providerId, Screen.CloudMusic.route) -> com.ljyh.mei.ui.screen.cloud.CloudMusicScreen(isNavigationTab = isNavigationTab)
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
