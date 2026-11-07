package com.ljyh.mei.ui.screen.search

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.ljyh.mei.ui.navigation.MeiloXLoadingPage

@Composable
fun SearchLandingScreen(sourceViewModel: SourceSearchViewModel = hiltViewModel(key = "source-search-choice")) {
    LaunchedEffect(sourceViewModel) { sourceViewModel.useSearchPreference() }
    val settings by sourceViewModel.settings.collectAsState()
    val providers by sourceViewModel.sourceChoices.collectAsState()
    val providerId = settings?.preferredSearchProvider
    if (providerId == null) MeiloXLoadingPage("搜索")
    else SharedSearchLandingScreen(providerId = providerId, sourceAction = {
        sourcePickerHeaderAction(providers, providerId, sourceViewModel::selectProvider)
    })
}
