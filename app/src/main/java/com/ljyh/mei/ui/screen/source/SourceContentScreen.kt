package com.ljyh.mei.ui.screen.source

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.media3.common.util.UnstableApi
import coil3.compose.AsyncImage
import com.kyant.capsule.ContinuousRoundedRectangle
import com.ljyh.mei.R
import com.ljyh.mei.data.model.toMediaItem
import com.ljyh.mei.data.model.toMediaMetadata
import com.ljyh.mei.playback.queue.ListQueue
import com.ljyh.mei.source.api.*
import com.ljyh.mei.ui.local.LocalNavController
import com.ljyh.mei.ui.local.LocalPlayerAwareWindowInsets
import com.ljyh.mei.ui.local.LocalPlayerConnection
import com.ljyh.mei.ui.navigation.MeiNavigator
import com.ljyh.mei.ui.navigation.MeiloXPinnedListPage
import com.ljyh.mei.ui.navigation.meiloxHeaderAction
import com.ljyh.mei.ui.screen.Screen
import com.ljyh.mei.ui.screen.main.library.component.groupedLazyItems
import com.ljyh.mei.ui.screen.search.LoadingView
import io.github.neoruaa.glasmio.core.LocalGlassColors
import io.github.neoruaa.glasmio.glass.rememberGlasmioMenuAnchorState
import io.github.neoruaa.glasmio.icons.SfIcon
import io.github.neoruaa.glasmio.scaffold.GlasmioHeaderAction
import io.github.neoruaa.glasmio.ui.*
import kotlinx.coroutines.CancellationException

internal fun navigateSourceContent(nav: MeiNavigator, ref: ContentRef) {
    if (ref.providerId == ProviderIds.NETEASE && ref.contentId.toLongOrNull() != null) {
        when (ref) {
            is AlbumRef -> Screen.Album.navigate(nav) { addPath(ref.albumId) }
            is ArtistRef -> Screen.Artist.navigate(nav) { addPath(ref.artistId) }
            is PlaylistRef -> Screen.PlayList.navigate(nav) { addPath(ref.playlistId) }
            is TrackRef -> Screen.SourceContent.navigate(nav) {
                addPath(Uri.encode(ref.providerId)); addPath(ref.type.wireName); addPath(Uri.encode(ref.contentId))
            }
        }
    } else Screen.SourceContent.navigate(nav) {
        addPath(Uri.encode(ref.providerId)); addPath(ref.type.wireName); addPath(Uri.encode(ref.contentId))
    }
}

internal fun navigateSourcePlaylistEditor(nav: MeiNavigator, providerId: String, ref: PlaylistRef? = null) {
    if (ref == null) Screen.SourcePlaylistCreate.navigate(nav) { addPath(Uri.encode(providerId)) }
    else Screen.SourcePlaylistEdit.navigate(nav) { addPath(Uri.encode(providerId)); addPath(Uri.encode(ref.playlistId)) }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun rememberSourceQueuePlayer(
    id: String,
    title: String,
    viewModel: SourceContentViewModel? = null,
    queueRequest: SourceContentRequest? = null,
): (List<Track>, Int) -> Unit {
    val player = LocalPlayerConnection.current
    return remember(player, id, title, viewModel, queueRequest) {
        { tracks, index ->
            if (tracks.isNotEmpty() && index in tracks.indices) {
                val paged = queueRequest != null && !(queueRequest is SourceContentRequest.Detail && queueRequest.ref is TrackRef)
                val queue = if (paged) viewModel?.pagedQueue(queueRequest!!, tracks, index, title) else ListQueue(
                    id = id, title = title,
                    items = tracks.map { track -> track.toMediaMetadata().toMediaItem().let { it.mediaId to it } },
                    startIndex = index, position = 0,
                )
                if (queue != null) player?.playQueue(queue)
            }
        }
    }
}

internal fun LazyListScope.sourceContentItems(
    items: List<SourceItem>,
    providers: List<MusicProvider>,
    onOpen: (ContentRef) -> Unit,
    onPlay: (List<Track>, Int) -> Unit,
    onMore: (Track, Rect) -> Unit,
    keyPrefix: String = "source",
    firstItemTopPadding: Dp = 10.dp,
) {
    val tracks = items.filterIsInstance<SourceItem.TrackItem>().map { it.track }
    var trackIndex = 0
    val trackIndices = items.map { if (it is SourceItem.TrackItem) trackIndex++ else -1 }
    groupedLazyItems(items.withIndex().toList(),
        key = { "$keyPrefix-${it.index}-${it.value.ref.type.wireName}-${Uri.encode(it.value.ref.providerId)}-${Uri.encode(it.value.ref.contentId)}" },
        firstItemTopPadding = firstItemTopPadding, contentType = "source-content") { indexed, index ->
        val item = indexed.value
        GlasmioListRow(
            title = item.title,
            subtitle = sourceItemSubtitle(item).takeIf(String::isNotBlank),
            showTopSeparator = index > 0,
            leading = {
                AsyncImage(item.artworkUrl, null, contentScale = ContentScale.Crop,
                    modifier = Modifier.size(44.dp).clip(if (item is SourceItem.ArtistItem) CircleShape else ContinuousRoundedRectangle(10.dp)))
            },
            trailing = if (item is SourceItem.TrackItem) ({
                var anchor by remember { mutableStateOf(Rect.Zero) }
                Box(Modifier.size(40.dp).onGloballyPositioned { anchor = it.boundsInWindow() }
                    .clickable(interactionSource = null, indication = null) { onMore(item.track, anchor) }, contentAlignment = Alignment.Center) {
                    SfIcon("ellipsis", "更多操作", size = 18.dp)
                }
            }) else null,
            showDisclosure = item !is SourceItem.TrackItem,
            onClick = {
                if (item is SourceItem.TrackItem) onPlay(tracks, trackIndices[index])
                else onOpen(item.ref)
            },
        )
    }
}

internal fun sourceItemSubtitle(item: SourceItem): String = when (item) {
    is SourceItem.TrackItem -> item.track.artists.joinToString(" / ") { it.name }
    is SourceItem.PlaylistItem -> item.playlist.creatorName
    is SourceItem.AlbumItem -> item.album.artists.joinToString(" / ") { it.name }
    is SourceItem.ArtistItem -> ""
}

@Composable
internal fun sourceOptionsHeaderAction(
    key: String, icon: String, description: String, selected: String,
    options: List<Pair<String, String>>, onSelect: (String) -> Unit,
): GlasmioHeaderAction {
    var expanded by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<String?>(null) }
    val anchor = rememberGlasmioMenuAnchorState()
    GlasmioMenu(expanded = expanded, onExpandedChange = { expanded = it }, itemCount = options.size.coerceAtLeast(1),
        menuAnchorState = anchor, externalAnchorBounds = anchor.liveBounds,
        onClosed = { val next = pending; pending = null; next?.let(onSelect) }, anchor = {}) { backdrop, close ->
        options.forEach { (id, title) ->
            GlasmioMenuItem(title, systemName = if (id == selected) "checkmark" else null, backdrop = backdrop,
                onClick = { pending = id.takeUnless { it == selected }; close() })
        }
        if (options.isEmpty()) GlasmioMenuItem("暂无内容", enabled = false, backdrop = backdrop, onClick = {})
    }
    return meiloxHeaderAction(key, description, onClick = { expanded = true }, enabled = options.isNotEmpty(), menuAnchor = anchor) {
        SfIcon(icon, null, size = 22.dp)
    }
}

@Composable
fun SourceContentScreen(ref: ContentRef, viewModel: SourceContentViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    val providers by viewModel.providers.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val nav = LocalNavController.current
    val bottom = LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateBottomPadding()
    var menu by remember(ref) { mutableStateOf<Pair<Track, Rect>?>(null) }
    var saved by remember(ref, state.providerVersion, state.accountRevision) { mutableStateOf<Boolean?>(null) }
    var deleting by remember(ref) { mutableStateOf(false) }
    var ordering by remember(ref, state.providerVersion, state.accountRevision) { mutableStateOf(false) }
    var orderedRefs by remember(ref, state.providerVersion, state.accountRevision) { mutableStateOf(emptyList<TrackRef>()) }
    var orderingTracks by remember(ref, state.providerVersion, state.accountRevision) { mutableStateOf(emptyList<Track>()) }
    val play = rememberSourceQueuePlayer("source-${ref.providerId}-${ref.type.wireName}-${ref.contentId}", state.title,
        viewModel = viewModel, queueRequest = SourceContentRequest.Detail(ref))
    val playSection = rememberSourceQueuePlayer("source-section-${ref.providerId}-${ref.contentId}", state.title)
    LaunchedEffect(ref) { viewModel.open(SourceContentRequest.Detail(ref)) }
    val provider = providers.firstOrNull { it.id == ref.providerId && it.status == SourceStatus.READY }
    val collections = (provider as? CollectionCapability)?.takeIf { SourceCapability.COLLECTIONS in provider.capabilities && ref.type in it.collectionTypes }
    LaunchedEffect(ref, state.detail, provider, state.accountRevision) {
        saved = null
        if (collections?.canReadCollections == true) try { saved = viewModel.saved(ref) }
        catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { saved = null }
    }
    val playlist = (state.detail as? SourceItem.PlaylistItem)?.playlist
    val actions = playlist?.editableActions.orEmpty()
    val canReorder = playlist?.let(viewModel::canReorder) == true
    MeiloXPinnedListPage(
        title = state.title.ifBlank { when (ref) { is PlaylistRef -> "歌单"; is AlbumRef -> "专辑"; is ArtistRef -> "歌手"; is TrackRef -> "歌曲" } },
        bottomPadding = bottom,
        onNavigateBack = { if (ordering) ordering = false else nav.navigateUp() },
        verticalArrangement = Arrangement.spacedBy(0.dp),
        actions = {
            if (ordering) listOf(meiloxHeaderAction("save-source-order", "保存排序", enabled = !busy && canReorder,
                onClick = { (ref as? PlaylistRef)?.let { viewModel.reorderTracks(it, orderedRefs) { ordering = false } } }) {
                SfIcon("checkmark", null, size = 22.dp)
            }) else {
                val options = buildList {
                    if (collections?.canWriteCollections == true) add("save" to if (saved == true) "取消收藏" else "收藏")
                    if (PlaylistEditAction.UPDATE in actions) add("edit" to "编辑歌单")
                    if (canReorder) add("reorder" to "排序")
                    if (PlaylistEditAction.DELETE in actions) add("delete" to "删除歌单")
                }
                listOfNotNull(if (options.isNotEmpty()) sourceOptionsHeaderAction("content-actions", "ellipsis", "更多操作", "", options) { action ->
                    when (action) {
                        "save" -> viewModel.setSaved(ref, saved != true)
                        "edit" -> (ref as? PlaylistRef)?.let { navigateSourcePlaylistEditor(nav, it.providerId, it) }
                        "delete" -> deleting = true
                        "reorder" -> {
                            orderingTracks = state.items.filterIsInstance<SourceItem.TrackItem>().map { it.track }
                            orderedRefs = orderingTracks.map { it.ref }
                            ordering = true
                        }
                    }
                } else null)
            }
        },
    ) {
        if (ordering) {
            item("source-track-order") {
                val tracks = orderingTracks.associateBy { it.ref }
                GlasmioReorderableGroup(
                    items = orderedRefs,
                    onReorder = { if (!busy) orderedRefs = it },
                    dragHandleContentDescription = stringResource(R.string.reorder_drag_handle),
                    moveUpLabel = stringResource(R.string.move_up),
                    moveDownLabel = stringResource(R.string.move_down),
                    modifier = Modifier.padding(top = 10.dp),
                ) { trackRef ->
                    val track = tracks[trackRef]
                    AsyncImage(track?.artworkUrl, null, contentScale = ContentScale.Crop,
                        modifier = Modifier.size(44.dp).clip(ContinuousRoundedRectangle(10.dp)))
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(track?.title.orEmpty(), maxLines = 1)
                        Text(track?.artists.orEmpty().joinToString(" / ") { it.name }, maxLines = 1,
                            color = LocalGlassColors.current.secondaryContent, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        } else {
            if (state.detail != null) item("source-detail-header") {
                Column(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    AsyncImage(state.artworkUrl, null, contentScale = ContentScale.Crop,
                        modifier = Modifier.size(220.dp).clip(if (ref is ArtistRef) CircleShape else ContinuousRoundedRectangle(16.dp)))
                    sourceItemSubtitle(state.detail!!).takeIf(String::isNotBlank)?.let {
                        Text(it, color = LocalGlassColors.current.secondaryContent, modifier = Modifier.padding(top = 12.dp))
                    }
                    if (state.items.any { it is SourceItem.TrackItem }) GlasmioGroupedList(Modifier.padding(top = 16.dp)) {
                        GlasmioListRow("播放", systemName = "play.fill", showTopSeparator = false, showDisclosure = false,
                            onClick = { play(state.items.filterIsInstance<SourceItem.TrackItem>().map { it.track }, 0) })
                    }
                }
            }
            state.sections.forEach { section -> item("detail-section-${section.id}") {
                SourceFeedSectionContent(section, { navigateSourceContent(nav, it) }, playSection, { track, anchor -> menu = track to anchor })
            } }
            if (state.albumsLoading) item("artist-albums-loading") { LoadingView() }
            if (!state.albumsLoading && (state.albumsHasMore || state.albumsError != null)) item("artist-albums-more") {
                GlasmioGroupedList(Modifier.padding(top = 10.dp)) {
                    GlasmioListRow(state.albumsError?.let(::sourceErrorMessage) ?: "更多专辑", showTopSeparator = false,
                        trailing = if (state.albumsError != null) ({ Text("重试") }) else null,
                        onClick = viewModel::loadMoreArtistAlbums)
                }
            }
            sourceContentItems(state.items, providers, { navigateSourceContent(nav, it) }, play,
                { track, anchor -> menu = track to anchor }, "detail")
            sourceContentStatus(state, viewModel::refresh, viewModel::loadMore)
        }
    }
    SourceTrackActions(menu, { menu = null }, viewModel, playlist = playlist)
    GlasmioAlertDialog(visible = deleting, onDismissRequest = { deleting = false }, title = "删除歌单？", message = playlist?.title.orEmpty(),
        buttons = listOf(GlasmioAlertButtonSpec("取消", role = GlasmioAlertButtonRole.Cancel, onClick = { deleting = false }),
            GlasmioAlertButtonSpec("删除", onClick = { deleting = false; (ref as? PlaylistRef)?.let { viewModel.deletePlaylist(it) { nav.navigateUp() } } })))
}

internal fun LazyListScope.sourceContentStatus(state: SourceContentState, onRetry: () -> Unit, onMore: () -> Unit) {
    if (state.loading) item("source-content-loading") { LoadingView() }
    state.error?.let { error -> item("source-content-error") { GlasmioGroupedList(Modifier.padding(top = 10.dp)) {
        GlasmioListRow(sourceErrorMessage(error), trailing = { Text("重试") }, onClick = onRetry)
    } } }
    if (!state.loading && !state.hasMore && !state.albumsHasMore && state.error == null && state.items.isEmpty() && state.sections.isEmpty()) item("source-content-empty") {
        GlasmioGroupedList(Modifier.padding(top = 10.dp)) { GlasmioListRow("暂无内容", showTopSeparator = false, showDisclosure = false) }
    }
    if (!state.loading && state.hasMore) item("source-content-more") { GlasmioGroupedList(Modifier.padding(top = 10.dp)) {
        GlasmioListRow("加载更多", showTopSeparator = false, onClick = onMore)
    } }
}

@Composable
fun SourcePlaylistEditorScreen(providerId: String, playlistId: String? = null, viewModel: SourceContentViewModel = hiltViewModel()) {
    val nav = LocalNavController.current
    val state by viewModel.state.collectAsState()
    val providers by viewModel.providers.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val message by viewModel.message.collectAsState()
    var title by rememberSaveable(providerId, playlistId) { mutableStateOf("") }
    var description by rememberSaveable(providerId, playlistId) { mutableStateOf("") }
    var initialized by rememberSaveable(providerId, playlistId) { mutableStateOf(false) }
    var draftOwner by rememberSaveable(providerId, playlistId) { mutableStateOf<String?>(null) }
    val ref = playlistId?.let { PlaylistRef(providerId, it) }
    val provider = providers.firstOrNull { it.id == providerId && it.status == SourceStatus.READY }
    val editor = (provider as? PlaylistEditCapability)?.takeIf { SourceCapability.PLAYLIST_EDIT in provider.capabilities }
    val playlist = (state.detail as? SourceItem.PlaylistItem)?.playlist?.takeIf { it.ref == ref }
    val owner = "${state.providerVersion}|${state.accountRevision.orEmpty()}"
    val matchingSource = state.providerId == providerId && state.providerVersion.isNotBlank() && state.providerVersion == provider?.version
    LaunchedEffect(providerId, playlistId) {
        if (ref == null) viewModel.prepareEditor(providerId) else viewModel.open(SourceContentRequest.Detail(ref))
    }
    LaunchedEffect(matchingSource, owner, playlist) {
        if (matchingSource) {
            if (draftOwner != owner) {
                title = ""; description = ""; initialized = false; draftOwner = owner
            }
            if (!initialized && playlist != null) {
                title = playlist.title; description = playlist.description.orEmpty(); initialized = true
            }
        }
    }
    val allowed = if (ref == null) PlaylistEditAction.CREATE in editor?.playlistEditActions.orEmpty()
        else PlaylistEditAction.UPDATE in editor?.playlistEditActions.orEmpty() && PlaylistEditAction.UPDATE in playlist?.editableActions.orEmpty()
    val formReady = matchingSource && draftOwner == owner && (ref == null || initialized && playlist != null)
    MeiloXPinnedListPage(title = if (ref == null) "新建歌单" else "编辑歌单", onNavigateBack = nav::navigateUp,
        bottomPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateBottomPadding(), actions = {
            listOf(meiloxHeaderAction("save-playlist", "保存", enabled = !busy && !state.loading && state.error == null &&
                formReady && allowed && title.isNotBlank(), onClick = {
                if (ref == null) viewModel.createPlaylist(title.trim(), description) { nav.navigateUp() }
                else viewModel.updatePlaylist(ref, title.trim(), description) { nav.navigateUp() }
            }) { SfIcon("checkmark", null, size = 22.dp) })
        }) {
        if (formReady) item("playlist-form") { GlasmioGroupedList {
            GlasmioTextField(value = title, onValueChange = { title = it }, placeholder = "歌单名称")
            GlasmioTextField(value = description, onValueChange = { description = it }, placeholder = "简介")
        } }
        if (state.loading || !matchingSource && state.error == null) item("playlist-form-loading") { LoadingView() }
        if (matchingSource && !allowed && !state.loading && state.error == null) item("playlist-form-unavailable") {
            GlasmioGroupedList { GlasmioListRow(if (ref == null) "此音源不支持创建歌单" else "此歌单不支持编辑", showTopSeparator = false, showDisclosure = false) }
        }
        state.error?.let { error -> item("playlist-form-error") {
            GlasmioGroupedList { GlasmioListRow(sourceErrorMessage(error), onClick = viewModel::refresh) }
        } }
    }
    GlasmioAlertDialog(visible = message != null, onDismissRequest = viewModel::dismissMessage, title = "操作失败",
        message = message?.let(::sourceErrorMessage).orEmpty(), buttons = listOf(GlasmioAlertButtonSpec("好", onClick = viewModel::dismissMessage)))
}

@Composable
fun SourcePlaylistPickerScreen(track: TrackRef, viewModel: SourceContentViewModel = hiltViewModel()) {
    val nav = LocalNavController.current
    val state by viewModel.state.collectAsState()
    val message by viewModel.message.collectAsState()
    val busy by viewModel.busy.collectAsState()
    LaunchedEffect(track) { viewModel.open(SourceContentRequest.Library(LibraryCollection.CREATED_PLAYLISTS, track.providerId)) }
    val editable = state.items.filterIsInstance<SourceItem.PlaylistItem>()
        .filter { PlaylistEditAction.ADD_TRACKS in it.playlist.editableActions }
    MeiloXPinnedListPage("添加到歌单", onNavigateBack = nav::navigateUp,
        bottomPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateBottomPadding(),
        verticalArrangement = Arrangement.spacedBy(0.dp)) {
        groupedLazyItems(editable, key = { it.ref.playlistId }, firstItemTopPadding = 10.dp) { item, index ->
            GlasmioListRow(item.title, showTopSeparator = index > 0, enabled = !busy,
                onClick = { viewModel.addTracks(item.ref, listOf(track)) { nav.navigateUp() } })
        }
        sourceContentStatus(state.copy(items = editable), viewModel::refresh, viewModel::loadMore)
    }
    GlasmioAlertDialog(visible = message != null, onDismissRequest = viewModel::dismissMessage, title = "操作失败",
        message = message?.let(::sourceErrorMessage).orEmpty(), buttons = listOf(GlasmioAlertButtonSpec("好", onClick = viewModel::dismissMessage)))
}
