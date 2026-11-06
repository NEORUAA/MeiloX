package com.ljyh.mei.ui.screen.search

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.ljyh.mei.source.api.ProviderIds
import com.ljyh.mei.source.netease.ui.NeteaseSearchLandingScreen
import com.ljyh.mei.ui.navigation.MeiloXLoadingPage

typealias SearchDiscoveryState = com.ljyh.mei.source.netease.ui.SearchDiscoveryState
typealias SearchDiscoveryViewModel = com.ljyh.mei.source.netease.ui.SearchDiscoveryViewModel

@Composable
fun SearchLandingScreen(sourceViewModel: SourceSearchViewModel = hiltViewModel()) {
    val settings by sourceViewModel.settings.collectAsState()
    val providers by sourceViewModel.sourceChoices.collectAsState()
    val providerId = settings?.preferredSearchProvider
    when (providerId) {
        null -> MeiloXLoadingPage("搜索")
        ProviderIds.NETEASE -> NeteaseSearchLandingScreen(sourceAction = {
            sourcePickerHeaderAction(providers, providerId, sourceViewModel::selectProvider)
        })
        else -> SourceCatalogScreen("搜索", sourceViewModel)
    }
}
