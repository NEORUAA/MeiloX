package com.ljyh.mei.ui.screen.main.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.ljyh.mei.source.netease.ui.NeteaseHomeScreen
import com.ljyh.mei.ui.navigation.MeiloXLoadingPage
import com.ljyh.mei.ui.screen.source.SourceFeedScreen
import com.ljyh.mei.source.api.FeedKind
import com.ljyh.mei.ui.screen.search.sourcePickerHeaderAction
import com.ljyh.mei.ui.screen.search.SourceSearchViewModel
import com.ljyh.mei.ui.screen.Screen

@Composable
fun HomeScreen(sourceViewModel: SourceSearchViewModel = hiltViewModel()) {
    val settings by sourceViewModel.settings.collectAsState()
    val providers by sourceViewModel.browseChoices.collectAsState()
    LaunchedEffect(sourceViewModel) { sourceViewModel.useBrowsePreference() }
    val providerId = settings?.preferredBrowseProvider
    when {
        providerId == null -> MeiloXLoadingPage("首页")
        sourceViewModel.supportsFeatureRoute(providerId, Screen.Home.route) -> NeteaseHomeScreen(sourceAction = {
            sourcePickerHeaderAction(providers, providerId, sourceViewModel::selectProvider)
        })
        else -> SourceFeedScreen("首页", FeedKind.HOME)
    }
}
