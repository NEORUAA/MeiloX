package com.ljyh.mei.ui.screen.search

import android.net.Uri
import com.ljyh.mei.ui.screen.Screen
import com.ljyh.mei.ui.local.LocalNavController
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.ljyh.mei.ui.navigation.meiloxHeaderAction
import com.ljyh.mei.ui.component.globalProfileHeaderAction
import com.ljyh.mei.ui.screen.main.library.component.groupedLazyItems
import com.ljyh.mei.utils.smallImage
import io.github.neoruaa.glasmio.core.LocalGlassColors
import io.github.neoruaa.glasmio.icons.SfIcon
import io.github.neoruaa.glasmio.glass.rememberGlasmioMenuAnchorState
import io.github.neoruaa.glasmio.scaffold.GlasmioHeaderAction
import io.github.neoruaa.glasmio.ui.GlasmioGroupedList
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import io.github.neoruaa.glasmio.ui.GlasmioMenu
import io.github.neoruaa.glasmio.ui.GlasmioMenuItem
import io.github.neoruaa.glasmio.ui.GlasmioSearchBar

@Composable
fun sourcePickerHeaderAction(
    providers: List<MusicProvider>,
    selectedId: String,
    onSelect: (String) -> Unit,
): GlasmioHeaderAction {
    var expanded by remember { mutableStateOf(false) }
    var pendingSelection by remember { mutableStateOf<String?>(null) }
    val anchor = rememberGlasmioMenuAnchorState()
    val choices = providers.filter { it.status == com.ljyh.mei.source.api.SourceStatus.READY }.distinctBy { it.id }
    GlasmioMenu(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        itemCount = choices.size.coerceAtLeast(1),
        menuAnchorState = anchor,
        externalAnchorBounds = anchor.liveBounds,
        onClosed = {
            val selected = pendingSelection
            pendingSelection = null
            selected?.let(onSelect)
        },
        anchor = {},
    ) { childBackdrop, close ->
        choices.forEach { provider ->
            GlasmioMenuItem(
                title = provider.displayName,
                systemName = if (provider.id == selectedId) "checkmark" else null,
                backdrop = childBackdrop,
                onClick = {
                    pendingSelection = provider.id.takeUnless { it == selectedId }
                    close()
                },
            )
        }
        if (choices.isEmpty()) {
            GlasmioMenuItem("没有可用的音源", onClick = {}, enabled = false, backdrop = childBackdrop)
        }
    }
    return meiloxHeaderAction(
        key = "source-picker",
        contentDescription = "切换音源",
        onClick = { expanded = true },
        menuAnchor = anchor,
        enabled = choices.isNotEmpty(),
    ) { SfIcon("music.note.list", null, size = 22.dp) }
}

/** Generic catalog surface used when a source does not contribute a native home page. */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun SourceCatalogScreen(title: String, viewModel: SourceSearchViewModel, showProfileAction: Boolean = false) {
    val state by viewModel.state.collectAsState()
    val selectedId by viewModel.selectedProviderId.collectAsState()
    val providers by viewModel.providers.collectAsState()
    val choices by viewModel.sourceChoices.collectAsState()
    val player = LocalPlayerConnection.current
    val nav = LocalNavController.current
    val focus = LocalFocusManager.current
    val bottom = LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateBottomPadding()
    var query by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(state.query)) }
    var active by remember { mutableStateOf(false) }
    MeiloXPinnedListPage(title, bottomPadding = bottom, verticalArrangement = Arrangement.spacedBy(0.dp), actions = {
        listOfNotNull(
            sourcePickerHeaderAction(choices, selectedId, viewModel::selectProvider),
            if (showProfileAction) globalProfileHeaderAction() else null,
        )
    }) {
        item("source-query") {
            GlasmioSearchBar(query, { query = it }, active, { active = it },
                onClose = { active = false; focus.clearFocus() }, placeholder = "搜索歌曲", closeContentDescription = "取消",
                onSearch = { value ->
                    val keyword = value.trim()
                    if (keyword.isNotEmpty()) Screen.SearchResult.navigate(nav) { addPath(Uri.encode(keyword)); addPath(SearchType.Song.type.toString()) }
                    active = false; focus.clearFocus()
                },
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
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
    groupedLazyItems(
        state.tracks,
        key = { "source-track-${TrackRefCodec.encode(it.ref)}" },
        contentType = "source-track",
        firstItemTopPadding = 10.dp,
    ) { track, index ->
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
                    if (onSongMore != null) {
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
        GlasmioGroupedList(Modifier.padding(top = 10.dp)) {
            GlasmioListRow(title = sourceSearchErrorText(state.error), trailing = { Text("重试") }, onClick = onRetry)
        }
    }
    if (state.loading) item("source-loading") { LoadingView() }
    if (state.query.isNotEmpty() && !state.loading && state.error == null && state.tracks.isEmpty()) {
        item("source-empty") { GlasmioGroupedList(Modifier.padding(top = 10.dp)) { EmptyView() } }
    }
    if (state.hasMore && !state.loading && state.error == null) item("source-next") {
        LaunchedEffect(state.query, state.tracks.firstOrNull()?.ref?.providerId, state.tracks.size, state.nextCursor) {
            onLoadMore()
        }
        LoadingView()
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
