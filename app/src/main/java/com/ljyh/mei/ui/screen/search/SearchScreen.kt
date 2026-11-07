package com.ljyh.mei.ui.screen.search

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
fun SearchScreen(
    query: String,
    onQueryChange: (TextFieldValue) -> Unit,
    onSearch: (String, Int) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    sourceViewModel: SourceSearchViewModel = hiltViewModel(key = "source-search-choice"),
    viewModel: SearchDiscoveryViewModel = hiltViewModel(),
) {
    val providerId by sourceViewModel.selectedProviderId.collectAsState()
    val suggestions by viewModel.suggestions.collectAsState()
    LaunchedEffect(sourceViewModel) { sourceViewModel.useSearchPreference() }
    LaunchedEffect(query, providerId) { viewModel.updateInputQuery(query, providerId) }
    androidx.compose.runtime.DisposableEffect(viewModel) { onDispose { viewModel.cancelSuggestions() } }
    val lazyListState = rememberLazyListState()
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(lazyListState) {
        snapshotFlow { lazyListState.isScrollInProgress }.distinctUntilChanged().filter { it }.collect { keyboard?.hide() }
    }
    LazyColumn(modifier = modifier, state = lazyListState,
        contentPadding = WindowInsets.systemBars.only(WindowInsetsSides.Bottom).asPaddingValues()) {
        items(suggestions, key = { "${it.type.name}:${it.id}" }) { suggestion ->
            SuggestionItem(query = suggestion.name, type = suggestion.type,
                onClick = { onSearch(suggestion.name, suggestion.type.type); onDismiss() },
                onFillTextField = { onQueryChange(TextFieldValue(suggestion.name, selection = TextRange(suggestion.name.length))) },
                modifier = Modifier.animateItem())
        }
    }
}

@Composable
fun SuggestionItem(
    modifier: Modifier = Modifier,
    query: String,
    type: SearchType,
    onClick: () -> Unit,
    onDelete: () -> Unit = {},
    onFillTextField: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .height(SuggestionItemHeight)
            .clickable(onClick = onClick)
            .padding(end = SearchBarIconOffsetX)
    ) {
        SfIcon(
            systemName = type.systemName,
            contentDescription = null,
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .alpha(0.5f)
        )

        Text(
            text = query,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )

        if (type == SearchType.History) {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier.alpha(0.5f).clickable(onClick = onDelete).padding(12.dp),
            ) {
                SfIcon("xmark", contentDescription = null, size = 18.dp)
            }
        }

        androidx.compose.foundation.layout.Box(
            modifier = Modifier.alpha(0.5f).clickable(onClick = onFillTextField).padding(12.dp),
        ) {
            SfIcon("arrow.up.right", contentDescription = null, size = 18.dp)
        }
    }
}
//type: 搜索类型；默认为 1 即单曲 , 取值意义 : 1: 单曲, 10: 专辑, 100: 歌手, 1000: 歌单, 1002: 用户, 1004: MV, 1006: 歌词, 1009: 电台, 1014: 视频, 1018:综合, 2000:声音(搜索声音返回字段格式会不一样)

enum class SearchType(val systemName: String, val type: Int, @get:StringRes val labelRes: Int) {
    Song("music.note", 1, R.string.search_type_song),
    Artist("person", 100, R.string.search_type_artist),
    Album("square.on.square", 10, R.string.search_type_album),
    Playlist("music.note.list", 1000, R.string.search_type_playlist),
    Podcast("dot.radiowaves.left.and.right", 1009, R.string.search_type_podcast),
    History("clock", -1, R.string.search_type_history)
}
