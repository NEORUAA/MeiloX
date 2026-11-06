package com.ljyh.mei.ui.screen.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import coil3.compose.AsyncImage
import com.kyant.capsule.ContinuousRoundedRectangle
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.toMediaItem
import com.ljyh.mei.data.model.toMediaMetadata
import com.ljyh.mei.playback.queue.ListQueue
import com.ljyh.mei.source.api.MusicProvider
import com.ljyh.mei.source.api.ProviderIds
import com.ljyh.mei.source.api.SourceErrorCode
import com.ljyh.mei.source.api.Track
import com.ljyh.mei.source.api.TrackRefCodec
import com.ljyh.mei.ui.local.LocalPlayerAwareWindowInsets
import com.ljyh.mei.ui.local.LocalPlayerConnection
import com.ljyh.mei.ui.navigation.MeiloXPinnedListPage
import com.ljyh.mei.ui.screen.main.library.component.groupedLazyItems
import com.ljyh.mei.utils.smallImage
import io.github.neoruaa.glasmio.core.LocalGlassColors
import io.github.neoruaa.glasmio.icons.SfIcon
import io.github.neoruaa.glasmio.ui.GlasmioGroupedList
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import io.github.neoruaa.glasmio.ui.GlasmioSearchBar

@Composable
fun SourcePicker(
    providers: List<MusicProvider>,
    selectedId: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val choices = searchableProviders(providers)
    val selected = providers.firstOrNull { it.id == selectedId }
    Column(modifier) {
        GlasmioGroupedList {
            GlasmioListRow(
                title = selected?.displayName ?: "来源不可用",
                showTopSeparator = false,
                trailing = { SfIcon("chevron.down", "选择音源", size = 14.dp) },
                onClick = { expanded = !expanded },
            )
        }
        if (expanded) {
            GlasmioGroupedList(Modifier.padding(top = 8.dp)) {
                choices.forEachIndexed { index, provider ->
                    GlasmioListRow(title = provider.displayName, showTopSeparator = index > 0,
                        trailing = { if (provider.id == selectedId) SfIcon("checkmark", null, size = 16.dp) },
                        onClick = { expanded = false; onSelect(provider.id) })
                }
                if (choices.isEmpty()) GlasmioListRow(title = "没有可搜索的音源", showTopSeparator = false)
            }
        }
    }
}

/** Generic catalog surface used when a source does not contribute a native home page. */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun SourceCatalogScreen(title: String, viewModel: SourceSearchViewModel) {
    val state by viewModel.state.collectAsState()
    val selectedId by viewModel.selectedProviderId.collectAsState()
    val providers by viewModel.providers.collectAsState()
    val choices by viewModel.sourceChoices.collectAsState()
    val player = LocalPlayerConnection.current
    val focus = LocalFocusManager.current
    val bottom = LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateBottomPadding()
    var query by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(state.query)) }
    var active by remember { mutableStateOf(false) }
    MeiloXPinnedListPage(title, bottomPadding = bottom) {
        item("source-selector") { SourcePicker(choices, selectedId, viewModel::selectProvider) }
        item("source-query") {
            GlasmioSearchBar(query, { query = it }, active, { active = it },
                onClose = { active = false; focus.clearFocus() }, placeholder = "搜索歌曲", closeContentDescription = "取消",
                onSearch = { viewModel.search(it); active = false; focus.clearFocus() }, modifier = Modifier.fillMaxWidth())
        }
        SourceTrackResults(state, providers,
            onPlay = { tracks, index ->
                val items = tracks.map { track -> track.toMediaMetadata().toMediaItem().let { it.mediaId to it } }
                player?.playQueue(ListQueue(id = "source-$selectedId-${state.query}", title = state.query,
                    items = items, startIndex = index, position = 0))
            }, onLoadMore = viewModel::loadMore, onRetry = { viewModel.search(state.query, force = true) })
    }
}

fun LazyListScope.SourceTrackResults(
    state: SourceSearchState,
    providers: List<MusicProvider>,
    onPlay: (List<Track>, Int) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onSongMore: ((MediaMetadata, Rect) -> Unit)? = null,
) {
    groupedLazyItems(state.tracks, key = { "source-track-${TrackRefCodec.encode(it.ref)}" }, contentType = "source-track") { track, index ->
        val sourceName = providers.firstOrNull { it.id == track.ref.providerId }?.displayName ?: track.ref.providerId
        GlasmioListRow(
            title = track.title,
            subtitle = track.artists.joinToString(" / ") { it.name }.takeIf(String::isNotBlank),
            showTopSeparator = index > 0,
            leading = {
                AsyncImage(track.artworkUrl.smallImage(), null, contentScale = ContentScale.Crop,
                    modifier = Modifier.size(44.dp).clip(ContinuousRoundedRectangle(10.dp)))
            },
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(sourceName, style = MaterialTheme.typography.labelSmall, color = LocalGlassColors.current.secondaryContent)
                    if (onSongMore != null && track.ref.providerId == ProviderIds.NETEASE) {
                        var anchor by remember { mutableStateOf(Rect.Zero) }
                        Box(Modifier.size(40.dp).onGloballyPositioned { anchor = it.boundsInWindow() }
                            .clickable(interactionSource = null, indication = null) { onSongMore(track.toMediaMetadata(), anchor) },
                            contentAlignment = Alignment.Center) { SfIcon("ellipsis", "更多操作", size = 18.dp) }
                    }
                }
            },
            onClick = { onPlay(state.tracks, index) },
        )
    }
    if (state.error != null) item("source-error") {
        GlasmioGroupedList { GlasmioListRow(title = sourceSearchErrorText(state.error), trailing = { Text("重试") }, onClick = onRetry) }
    }
    if (state.loading) item("source-loading") { LoadingView() }
    if (state.query.isNotEmpty() && !state.loading && state.error == null && state.tracks.isEmpty()) {
        item("source-empty") { GlasmioGroupedList { EmptyView() } }
    }
    if (state.hasMore && !state.loading) item("source-next") {
        GlasmioGroupedList { GlasmioListRow(title = "加载更多", onClick = onLoadMore) }
    }
}

internal fun sourceSearchErrorText(code: SourceErrorCode): String = when (code) {
    SourceErrorCode.AUTH_REQUIRED, SourceErrorCode.AUTH_EXPIRED -> "请在音源管理中更新登录状态"
    SourceErrorCode.MISSING_PROVIDER -> "来源未安装或已停用"
    SourceErrorCode.UNSUPPORTED -> "此音源不支持搜索"
    SourceErrorCode.TIMEOUT -> "搜索超时"
    SourceErrorCode.PLUGIN, SourceErrorCode.INVALID_RESPONSE -> "音源返回异常"
    else -> "搜索失败"
}
