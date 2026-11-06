package com.ljyh.mei.source.netease.ui

import com.ljyh.mei.ui.screen.search.SearchType
import com.ljyh.mei.ui.screen.search.SuggestionItem
import androidx.compose.runtime.DisposableEffect

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import com.ljyh.mei.R
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.ljyh.mei.constants.SuggestionItemHeight
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.ui.component.SearchBarIconOffsetX
import io.github.neoruaa.glasmio.icons.SfIcon
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import timber.log.Timber


@Composable
fun NeteaseSearchSuggestions(
    query: String,
    onQueryChange: (TextFieldValue) -> Unit,
    onSearch: (String, Int) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: NeteaseSearchViewModel = hiltViewModel(),
) {
    DisposableEffect(viewModel) { onDispose { viewModel.cancelRequests() } }
    val searchSuggest by viewModel.searchSuggest.collectAsState()
    val keyboardController = LocalSoftwareKeyboardController.current

    val lazyListState = rememberLazyListState()

    LaunchedEffect(Unit) {
        snapshotFlow { lazyListState.isScrollInProgress }
            .distinctUntilChanged()
            .filter { it }
            .collect {
                keyboardController?.hide()
            }
    }

    LaunchedEffect(query) {
        viewModel.updateInputQuery( query)
    }

    LazyColumn(
        modifier = modifier,
        state = lazyListState,
        contentPadding = WindowInsets.systemBars
            .only(WindowInsetsSides.Bottom)
            .asPaddingValues()
    ) {

        when (val result=searchSuggest) {
            is Resource.Loading -> {}
            is Resource.Success -> {
                result.data.result.songs?.let { songs->
                    items(
                        items = songs,
                        key = { it.id }
                    ) { query ->
                        SuggestionItem(
                            query = query.name,
                            type = SearchType.Song,
                            onClick = {
                                onSearch(query.name, SearchType.Song.type)
                                onDismiss()
                            },
                            onDelete = {
                            },
                            onFillTextField = {
                                onQueryChange(
                                    TextFieldValue(
                                        text = query.name,
                                        selection = TextRange(query.name.length)
                                    )
                                )
                            },
                            modifier = Modifier.animateItem()
                        )
                    }
                }

                result.data.result.artists?.let { artists->
                    items(
                        items = artists,
                        key = { it.id }
                    ) { query ->
                        SuggestionItem(
                            query = query.name,
                            type = SearchType.Artist,
                            onClick = {
                                onSearch(query.name, SearchType.Artist.type)
                                onDismiss()
                            },
                            onDelete = {
                            },
                            onFillTextField = {
                                onQueryChange(
                                    TextFieldValue(
                                        text = query.name,
                                        selection = TextRange(query.name.length)
                                    )
                                )
                            },
                            modifier = Modifier.animateItem()
                        )
                    }
                }

                result.data.result.albums?.let { albums->
                    items(
                        items = albums,
                        key = { it.id }
                    ) { query ->
                        SuggestionItem(
                            query = query.name,
                            type = SearchType.Album,
                            onClick = {
                                onSearch(query.name, SearchType.Album.type)
                                onDismiss()
                            },
                            onDelete = {
                            },
                            onFillTextField = {
                                onQueryChange(
                                    TextFieldValue(
                                        text = query.name,
                                        selection = TextRange(query.name.length)
                                    )
                                )
                            },
                            modifier = Modifier.animateItem()
                        )
                    }
                }

            }

            is Resource.Error -> {}
        }
    }
}
