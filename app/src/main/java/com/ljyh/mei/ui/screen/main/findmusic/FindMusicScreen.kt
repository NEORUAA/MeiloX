package com.ljyh.mei.ui.screen.main.findmusic

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.ljyh.mei.R
import com.ljyh.mei.source.api.FeedKind
import com.ljyh.mei.ui.navigation.MeiloXLoadingPage
import com.ljyh.mei.ui.screen.search.SourceSearchViewModel
import com.ljyh.mei.ui.screen.source.SourceFeedScreen

@Composable
fun FindMusicScreen(
    modifier: Modifier = Modifier,
    initialCategory: String? = null,
    titleOverride: String? = null,
    isNavigationTab: Boolean = false,
    sourceViewModel: SourceSearchViewModel = hiltViewModel(key = "source-browse-choice"),
    providerId: String? = null,
) {
    val settings by sourceViewModel.settings.collectAsState()
    LaunchedEffect(sourceViewModel) { sourceViewModel.useBrowsePreference() }
    val selectedProviderId = providerId ?: settings?.preferredBrowseProvider
    when {
        selectedProviderId == null -> MeiloXLoadingPage(titleOverride ?: stringResource(R.string.app_tab_explore))
        else -> Box(modifier) { SourceFeedScreen(titleOverride ?: stringResource(R.string.app_tab_explore), FeedKind.DISCOVER, initialCategory = initialCategory, isNavigationTab = isNavigationTab, providerId = providerId) }
    }
}
