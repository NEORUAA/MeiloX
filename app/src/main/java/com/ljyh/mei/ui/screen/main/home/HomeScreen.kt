package com.ljyh.mei.ui.screen.main.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.ljyh.mei.source.netease.ui.NeteaseHomeScreen
import com.ljyh.mei.ui.navigation.MeiloXLoadingPage
import com.ljyh.mei.ui.screen.search.SourceCatalogScreen
import com.ljyh.mei.ui.screen.search.SourcePicker
import com.ljyh.mei.ui.screen.search.SourceSearchViewModel
import com.ljyh.mei.ui.screen.search.searchableProviders
import com.ljyh.mei.ui.screen.Screen

@Composable
fun HomeScreen(sourceViewModel: SourceSearchViewModel = hiltViewModel()) {
    val settings by sourceViewModel.settings.collectAsState()
    val providers by sourceViewModel.sourceChoices.collectAsState()
    LaunchedEffect(sourceViewModel) { sourceViewModel.useBrowsePreference() }
    val providerId = settings?.preferredBrowseProvider
    when {
        providerId == null -> MeiloXLoadingPage("首页")
        sourceViewModel.supportsFeatureRoute(providerId, Screen.Home.route) -> NeteaseHomeScreen(sourcePicker = if (searchableProviders(providers).size > 1) {
            { SourcePicker(providers, providerId, sourceViewModel::selectProvider) }
        } else null)
        else -> SourceCatalogScreen("首页", sourceViewModel)
    }
}
