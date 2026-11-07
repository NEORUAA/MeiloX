package com.ljyh.mei.ui.screen.main.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.ljyh.mei.ui.navigation.MeiloXLoadingPage
import com.ljyh.mei.ui.screen.source.SourceFeedScreen
import com.ljyh.mei.source.api.FeedKind
import com.ljyh.mei.ui.screen.search.SourceSearchViewModel

@Composable
fun HomeScreen(sourceViewModel: SourceSearchViewModel = hiltViewModel(key = "source-browse-choice")) {
    val settings by sourceViewModel.settings.collectAsState()
    LaunchedEffect(sourceViewModel) { sourceViewModel.useBrowsePreference() }
    val providerId = settings?.preferredBrowseProvider
    when {
        providerId == null -> MeiloXLoadingPage("首页")
        else -> SourceFeedScreen("首页", FeedKind.HOME)
    }
}
