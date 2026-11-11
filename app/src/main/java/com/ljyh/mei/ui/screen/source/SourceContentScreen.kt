package com.ljyh.mei.ui.screen.source

import android.net.Uri
import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import com.ljyh.mei.data.model.mediaId
import com.ljyh.mei.data.model.supportsNeteaseSongActions
import com.ljyh.mei.ui.screen.playlist.SongListDetailData
import com.ljyh.mei.ui.screen.playlist.SongListDetailScreen
import com.ljyh.mei.ui.screen.playlist.SongListLandscapeLayout
import com.ljyh.mei.ui.screen.playlist.DetailSelectionToolbar
import com.ljyh.mei.ui.screen.playlist.PlaylistOrderError
import com.ljyh.mei.ui.screen.playlist.PlaylistOrderItem
import com.ljyh.mei.ui.screen.playlist.PlaylistOrderSheet
import com.ljyh.mei.ui.screen.playlist.PlaylistOrderState
import com.ljyh.mei.ui.screen.playlist.detailMenuItems
import com.ljyh.mei.ui.screen.playlist.rememberDetailSelection
import com.ljyh.mei.ui.screen.artist.*
import com.ljyh.mei.ui.screen.playlist.component.DetailTrackRow
import com.ljyh.mei.ui.screen.playlist.component.DetailTrackDivider
import com.ljyh.mei.ui.component.utils.rememberDeviceInfo
import com.ljyh.mei.ui.component.playlist.rememberFavoriteTrackIds
import com.ljyh.mei.ui.component.item.LandscapeMediaRow
import com.ljyh.mei.utils.color.ColorExtractionUtils
import io.github.neoruaa.glasmio.core.IosTypography
import androidx.compose.foundation.background
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
import com.ljyh.mei.constants.DownloadPathKey
import com.ljyh.mei.constants.DownloadQuality
import com.ljyh.mei.constants.DownloadQualityKey
import com.ljyh.mei.constants.MusicQuality
import com.ljyh.mei.data.model.toMediaItem
import com.ljyh.mei.data.model.toMediaMetadata
import com.ljyh.mei.playback.queue.ListQueue
import com.ljyh.mei.playback.SongDownloadInfo
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
import com.ljyh.mei.ui.component.DownloadConfirmDialog
import com.ljyh.mei.utils.DownloadManager
import com.ljyh.mei.utils.rememberEnumPreference
import com.ljyh.mei.utils.rememberPreference
import io.github.neoruaa.glasmio.core.swipeBackExcluded
import io.github.neoruaa.glasmio.core.LocalGlassColors
import io.github.neoruaa.glasmio.glass.rememberGlasmioMenuAnchorState
import io.github.neoruaa.glasmio.icons.SfIcon
import io.github.neoruaa.glasmio.scaffold.GlasmioHeaderAction
import io.github.neoruaa.glasmio.ui.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal fun navigateSourceContent(nav: MeiNavigator, ref: ContentRef) {
    Screen.SourceContent.navigate(nav) {
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
    shuffle: Boolean? = null,
    preserveRepeatMode: Boolean = true,
    randomStart: Boolean = false,
): (List<Track>, Int) -> Unit {
    val player = LocalPlayerConnection.current
    return remember(player, id, title, viewModel, queueRequest, shuffle, preserveRepeatMode, randomStart) {
        { tracks, index ->
            if (tracks.isNotEmpty() && index in tracks.indices) {
                val paged = queueRequest != null && !(queueRequest is SourceContentRequest.Detail && queueRequest.ref is TrackRef)
                val queue = if (paged) viewModel?.pagedQueue(queueRequest!!, tracks, index, title) else ListQueue(
                    id = id, title = title,
                    items = tracks.map { track -> track.toMediaMetadata().toMediaItem().let { it.mediaId to it } },
                    startIndex = index, position = 0,
                )
                if (queue != null && player != null) {
                    if (preserveRepeatMode) player.playQueue(queue, shuffle = shuffle,
                        repeatMode = player.player.repeatMode, randomStart = randomStart)
                    else player.playQueue(queue, shuffle = shuffle, randomStart = randomStart)
                }
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
    subtitle: @Composable (SourceItem) -> String = { sourceItemSubtitle(it) },
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
            subtitle = subtitle(item).takeIf(String::isNotBlank),
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

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun SourceContentScreen(ref: ContentRef, viewModel: SourceContentViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    val providers by viewModel.providers.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val contentRevision by viewModel.contentRevision.collectAsState()
    val nav = LocalNavController.current
    var menu by remember(ref) { mutableStateOf<Pair<Track, Rect>?>(null) }
    var saved by remember(ref, state.providerVersion, state.accountRevision, contentRevision) { mutableStateOf<Boolean?>(null) }
    var savedLoading by remember(ref, state.providerVersion, state.accountRevision, contentRevision) { mutableStateOf(false) }
    var savedError by remember(ref, state.providerVersion, state.accountRevision, contentRevision) { mutableStateOf<SourceErrorCode?>(null) }
    var savedAttempt by remember(ref) { mutableIntStateOf(0) }
    var query by rememberSaveable(ref) { mutableStateOf("") }
    var searchActive by rememberSaveable(ref) { mutableStateOf(false) }
    val play = rememberSourceQueuePlayer("source-${ref.providerId}-${ref.type.wireName}-${ref.contentId}", state.title,
        viewModel = viewModel, queueRequest = SourceContentRequest.Detail(ref))
    val playAll = rememberSourceQueuePlayer("source-${ref.providerId}-${ref.type.wireName}-${ref.contentId}", state.title,
        viewModel = viewModel, queueRequest = SourceContentRequest.Detail(ref), preserveRepeatMode = false)
    val shuffle = rememberSourceQueuePlayer("source-${ref.providerId}-${ref.type.wireName}-${ref.contentId}", state.title,
        viewModel = viewModel, queueRequest = SourceContentRequest.Detail(ref), shuffle = true,
        preserveRepeatMode = false, randomStart = true)
    LaunchedEffect(ref) { viewModel.open(SourceContentRequest.Detail(ref)) }
    val provider = providers.firstOrNull { it.id == ref.providerId && it.status == SourceStatus.READY }
    val collections = (provider as? CollectionCapability)?.takeIf {
        SourceCapability.COLLECTIONS in provider.capabilities && ref.type in it.collectionTypes
    }
    LaunchedEffect(ref, state.detail, provider, state.accountRevision, contentRevision, savedAttempt) {
        saved = null
        savedError = null
        savedLoading = collections?.canReadCollections == true
        if (savedLoading) try { saved = viewModel.saved(ref) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { savedError = error.sourceCode() }
        finally { savedLoading = false }
    }
    val toggleCollection: () -> Unit = {
        when {
            saved != null -> viewModel.setSaved(ref, saved != true)
            savedError == SourceErrorCode.AUTH_REQUIRED || savedError == SourceErrorCode.AUTH_EXPIRED ->
                Screen.SourceAccount.navigate(nav) { addPath(Uri.encode(ref.providerId)) }
            else -> savedAttempt++
        }
    }
    val playlist = (state.detail as? SourceItem.PlaylistItem)?.playlist
    val album = (state.detail as? SourceItem.AlbumItem)?.album
    val tracks = state.items.filterIsInstance<SourceItem.TrackItem>().map { it.track }
    val actions = playlist?.editableActions.orEmpty()
    val canReorder = SourceCapability.PLAYLIST_EDIT in provider?.capabilities.orEmpty() &&
        PlaylistEditAction.REORDER_TRACKS in actions &&
        PlaylistEditAction.REORDER_TRACKS in (provider as? PlaylistEditCapability)?.playlistEditActions.orEmpty()
    val title = state.title.ifBlank { when (ref) {
        is PlaylistRef -> "歌单"; is AlbumRef -> "专辑"; is ArtistRef -> "歌手"; is TrackRef -> "歌曲"
    } }
    if (ref is ArtistRef) {
        SourceArtistDetail(ref, state, provider, busy || savedLoading, saved, viewModel,
            onFollow = toggleCollection, selection = menu, onDismissMenu = { menu = null },
            onInformation = { Screen.ArtistInfo.navigate(nav) { addPath(Uri.encode(ref.contentId)); addQuery("providerId", ref.providerId) } }, onPlay = play,
            onMore = { track, anchor -> menu = track to anchor })
    } else {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val ownerKey = Triple(ref, provider, state.accountRevision)
        val selection = rememberDetailSelection(ownerKey)
        var pendingDownload by remember(ownerKey) { mutableStateOf<SourceDetailTracks?>(null) }
        var bulkJob by remember(ownerKey) { mutableStateOf<Job?>(null) }
        var orderJob by remember(ownerKey) { mutableStateOf<Job?>(null) }
        var orderGeneration by remember(ownerKey) { mutableIntStateOf(0) }
        var order by remember(ownerKey) { mutableStateOf<PlaylistOrderState<TrackRef>?>(null) }
        var orderTracks by remember(ownerKey) { mutableStateOf<SourceDetailTracks?>(null) }
        var searchTracks by remember(ownerKey) { mutableStateOf<SourceDetailTracks?>(null) }
        var searching by remember(ownerKey) { mutableStateOf(false) }
        var searchError by remember(ownerKey) { mutableStateOf<SourceErrorCode?>(null) }
        var searchAttempt by remember(ownerKey) { mutableIntStateOf(0) }
        val playSearch = rememberSourceQueuePlayer("source-${ref.providerId}-${ref.type.wireName}-${ref.contentId}", state.title)
        val shuffleSearch = rememberSourceQueuePlayer("source-search-${ref.providerId}-${ref.type.wireName}-${ref.contentId}",
            state.title, shuffle = true, preserveRepeatMode = false, randomStart = true)
        LaunchedEffect(ownerKey, query, contentRevision, state.detail?.ref, state.loading, state.refreshing, searchAttempt) {
            searchTracks = null
            searchError = null
            searching = false
            if (query.isBlank() || state.detail?.ref != ref || state.loading || state.refreshing) return@LaunchedEffect
            searching = true
            try {
                delay(200)
                searchTracks = viewModel.detailTracks(ref)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) { searchError = error.sourceCode()
            } finally { searching = false }
        }
        val downloadPath by rememberPreference(DownloadPathKey, DownloadManager.getDefaultDownloadPath())
        val downloadQuality by rememberEnumPreference(DownloadQualityKey, DownloadQuality.EXHIGH)
        DisposableEffect(ownerKey) {
            onDispose { bulkJob?.cancel(); orderJob?.cancel(); selection.finish() }
        }
        suspend fun download(snapshot: SourceDetailTracks, quality: MusicQuality) {
            val songs = snapshot.tracks.distinctBy { it.ref }.map { track ->
                SongDownloadInfo(songId = TrackRefCodec.storageKey(track.ref), url = null,
                    songTitle = track.title, songArtist = track.artists.map { it.name }, songAlbum = track.album.title,
                    songCover = track.artworkUrl, duration = track.durationMs, quality = quality.text,
                    originTrackRef = track.ref)
            }
            viewModel.ensureDetailCurrent(snapshot)
            DownloadManager.enqueue(context, songs, snapshot.title,
                playlistId = "source:${Uri.encode(ref.providerId)}:${ref.type.wireName}:${Uri.encode(ref.contentId)}", downloadPath = downloadPath)
            Toast.makeText(context, context.getString(R.string.detail_download_queued, songs.size), Toast.LENGTH_SHORT).show()
        }
        fun prepareDownload(quality: MusicQuality? = null, ids: Set<String>? = null, prepared: SourceDetailTracks? = null) {
            if (bulkJob?.isActive == true) return
            bulkJob = scope.launch {
                try {
                    val snapshot = prepared ?: if (ids == null) viewModel.detailTracks(ref)
                        else viewModel.selectedDetailTracks(ref, ids)
                    viewModel.ensureDetailCurrent(snapshot)
                    if (snapshot.tracks.isEmpty()) return@launch
                    if (quality == null) pendingDownload = snapshot else download(snapshot, quality)
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (_: Exception) { Toast.makeText(context, R.string.load_failed, Toast.LENGTH_SHORT).show() }
            }
        }
        fun openOrder() {
            selection.finish()
            val token = ++orderGeneration
            orderJob?.cancel()
            orderTracks = null
            order = PlaylistOrderState(ref.contentId)
            orderJob = scope.launch {
                try {
                    val snapshot = viewModel.detailTracks(ref, reload = true)
                    val refs = snapshot.tracks.map { it.ref }
                    if (refs.distinct().size != refs.size) throw SourceException(SourceErrorCode.UNSUPPORTED)
                    if (token != orderGeneration) return@launch
                    orderTracks = snapshot
                    order = PlaylistOrderState(ref.contentId,
                        items = snapshot.tracks.map { PlaylistOrderItem(it.ref, it.toMediaMetadata()) },
                        originalIds = refs, loading = false)
                } catch (cancelled: CancellationException) {
                    if (token == orderGeneration) order = null
                    throw cancelled
                } catch (_: Exception) {
                    if (token == orderGeneration) order = order?.copy(loading = false, error = PlaylistOrderError.LoadFailed)
                }
            }
        }
        fun saveOrder() {
            val current = order ?: return
            val snapshot = orderTracks ?: return
            if (current.loading || current.saving) return
            val token = orderGeneration
            order = current.copy(saving = true, error = null)
            orderJob = scope.launch {
                try {
                    val saved = viewModel.saveTrackOrder(snapshot, current.items.map { it.id })
                    if (token == orderGeneration) order = if (saved) null else current.copy(error = PlaylistOrderError.PlaylistChanged)
                } catch (cancelled: CancellationException) {
                    if (token == orderGeneration) order = null
                    throw cancelled
                } catch (_: Exception) {
                    if (token == orderGeneration) order = current.copy(error = PlaylistOrderError.SaveFailed)
                }
            }
        }
        DetailSelectionToolbar(selection,
            onSelectAll = { sourceDetailMatches(viewModel.detailTracks(ref).tracks, query).map { TrackRefCodec.mediaId(it.value.ref) }.toSet() },
            onDownload = { prepareDownload(ids = selection.ids) })
        val matchingTracks = if (query.isBlank()) tracks else searchTracks?.tracks.orEmpty()
        val displayed = remember(matchingTracks, query) { sourceDetailMatches(matchingTracks, query) }
        val displayedMetadata = remember(displayed) { displayed.map { it.value.toMediaMetadata() } }
        val favoriteIds = rememberFavoriteTrackIds(displayedMetadata)
        val listState = rememberLazyListState()
        val locateTrack = if (ref is PlaylistRef && ref.providerId == ProviderIds.NETEASE &&
            state.detail?.ref == ref && !searchActive && query.isBlank() && !selection.active) {
            LocalPlayerConnection.current?.mediaMetadata?.collectAsState()?.value
                ?.takeIf { it.supportsNeteaseSongActions }?.trackRef
        } else null
        val bottomPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateBottomPadding()
        val count = maxOf(playlist?.trackCount ?: album?.trackCount ?: 0, tracks.size)
        val detailMenu = detailMenuItems(
            downloadTitle = stringResource(R.string.album_download_all, count),
            subscriptionTitle = stringResource(if (ref is PlaylistRef) {
                if (saved == true) R.string.detail_playlist_unsubscribe else R.string.detail_playlist_subscribe
            } else if (saved == true) R.string.album_unsubscribe else R.string.album_subscribe),
            subscribed = saved == true,
            subscriptionEnabled = collections?.canWriteCollections == true && !busy && !savedLoading,
            onDownload = { prepareDownload(it) }, onSelect = selection::start,
            onSubscribe = toggleCollection,
            onRefresh = { selection.finish(); viewModel.refresh() },
        ).toMutableList().apply {
            if (canReorder) add(2, GlasmioCascadingMenuItem(stringResource(R.string.playlist_order_title), "line.3.horizontal",
                enabled = !busy && order == null, onClick = { openOrder() }))
        }
        SongListDetailScreen(
            uiData = SongListDetailData(title = title, count = count,
                cover = state.artworkUrl, creatorName = state.detail?.let(::sourceItemSubtitle).orEmpty(),
                tracks = displayedMetadata, isSubscribed = saved == true, playCount = playlist?.playCount),
            isLoading = state.loading && (state.detail == null || query.isNotBlank()) || searching,
            onPlayAll = { if (tracks.isNotEmpty()) playAll(tracks, 0) },
            onShufflePlay = {
                if (query.isNotBlank()) {
                    val matches = displayed.map { it.value }
                    if (matches.isNotEmpty()) shuffleSearch(matches, 0)
                } else if (tracks.isNotEmpty()) shuffle(tracks, 0)
            },
            onHeaderAction = toggleCollection,
            headerActionIcon = if (saved == true) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
            headerActionLabel = if (savedError != null) "重试" else if (saved == true) "取消收藏" else "收藏",
            showHeaderAction = collections?.canWriteCollections == true && collections.canReadCollections,
            headerActionPending = busy || savedLoading, isSubscribed = saved == true,
            onTrackClick = { metadata, index ->
                if (selection.active) selection.toggle(metadata.mediaId)
                else displayed.getOrNull(index)?.let { selected ->
                    val snapshot = searchTracks.takeIf { query.isNotBlank() }
                    if (snapshot == null) play(tracks, selected.index)
                    else scope.launch {
                        try {
                            viewModel.ensureDetailCurrent(snapshot)
                            playSearch(snapshot.tracks, selected.index)
                        } catch (cancelled: CancellationException) { throw cancelled
                        } catch (_: Exception) { Toast.makeText(context, R.string.load_failed, Toast.LENGTH_SHORT).show() }
                    }
                }
            },
            onTrackMore = { metadata, anchor ->
                matchingTracks.firstOrNull { it.ref == metadata.trackRef }?.let { menu = it to anchor }
            },
            onBack = nav::navigateUp, detailMenu = detailMenu,
            selectionMode = selection.active, selectedTrackIds = selection.ids, onSelectionDone = selection::finish,
            detailMenuTitle = stringResource(if (ref is PlaylistRef) R.string.detail_playlist_menu else R.string.album_menu_title),
            playlistSearchQuery = query, isPlaylistSearchActive = searchActive,
            onPlaylistSearchQueryChange = { query = it }, onPlaylistSearchActiveChange = { searchActive = it; if (!it) query = "" },
            useCoverBackground = true, usePlaylistPortraitHeader = ref is PlaylistRef,
            landscapeLayout = if (ref is PlaylistRef) SongListLandscapeLayout.Split else SongListLandscapeLayout.AlbumHeader,
            showTrackArtwork = ref !is AlbumRef, hasLoadedContent = state.detail != null,
            listState = listState,
            favoriteTrackIds = favoriteIds,
            trackNumber = { _, index -> (displayed.getOrNull(index)?.index ?: index) + 1 },
            footer = {
                if (query.isBlank()) sourceDetailPagingStatus(state, viewModel::refresh, viewModel::loadMore)
                else searchError?.let { error -> item("source-detail-search-error") {
                    GlasmioListRow(sourceErrorMessage(error), trailing = { Text("重试") }, onClick = { searchAttempt++ })
                } }
            },
            overlayContent = {
                locateTrack?.let { track ->
                    SourceTrackLocateButton(
                        viewModel, SourceContentRequest.Detail(ref), state, track, listState,
                        leadingItems = 1, // Both layouts reserve a slot for the playlist hero.
                        modifier = Modifier.align(Alignment.BottomEnd),
                    )
                }
                SourceTrackActions(menu, { menu = null }, viewModel, playlist = playlist)
            },
        )
        var retainedOrder by remember(ownerKey) { mutableStateOf<PlaylistOrderState<TrackRef>?>(null) }
        order?.let { current -> SideEffect { retainedOrder = current } }
        (order ?: retainedOrder)?.let { current ->
            PlaylistOrderSheet(current, visible = order != null, trackKey = TrackRefCodec::encode,
                onMove = { from, to ->
                    val active = order
                    if (active != null && !active.loading && !active.saving && from in active.items.indices && to in active.items.indices) {
                        order = active.copy(items = active.items.toMutableList().apply { add(to, removeAt(from)) })
                    }
                }, onVisibleTracks = {}, onSave = { saveOrder() }, onReload = { openOrder() },
                onDismiss = { orderGeneration++; orderJob?.cancel(); order = null; orderTracks = null })
        }
        DownloadConfirmDialog(visible = pendingDownload != null, currentQuality = downloadQuality, downloadPath = downloadPath,
            onDismiss = { pendingDownload = null },
            onConfirm = { val selected = pendingDownload; pendingDownload = null; selected?.let { prepareDownload(downloadQuality.toMusicQuality(), prepared = it) } },
            onGoToSettings = { pendingDownload = null; Screen.DownloadSettings.navigate(nav) },
            onGoToDownloadManage = { pendingDownload = null; Screen.DownloadManage.navigate(nav) })
    }
}

internal fun sourceDetailMatches(tracks: List<Track>, query: String): List<IndexedValue<Track>> {
    val terms = query.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
    return tracks.withIndex().filter { (_, track) ->
        val text = listOf(track.title, track.artists.joinToString(" ") { it.name }, track.album.title).joinToString(" ")
        terms.all { text.contains(it, ignoreCase = true) }
    }
}

private fun LazyListScope.sourceDetailPagingStatus(state: SourceContentState, onRetry: () -> Unit, onMore: () -> Unit, automatic: Boolean = true) {
    val detailRef = state.detail?.ref
    if (state.loading && state.items.isEmpty() && !state.hasMore && state.error == null &&
        (detailRef is PlaylistRef || detailRef is AlbumRef)) item("source-detail-first-page-loading") { LoadingView() }
    if (state.error != null) item("source-detail-retry") {
        GlasmioListRow(sourceErrorMessage(state.error), trailing = { Text("重试") }, onClick = onRetry)
    }
    if (state.hasMore && state.error == null) item("source-detail-append:${state.nextOffset}") {
        if (automatic) LaunchedEffect(state.providerId, state.providerVersion, state.accountRevision,
            state.nextOffset, state.nextCursor, state.loading) { if (!state.loading) onMore() }
        else if (!state.loading) GlasmioListRow(stringResource(R.string.artist_catalog_search_continue), onClick = onMore)
        if (state.loading) LoadingView()
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun SourceArtistDetail(
    ref: ArtistRef,
    state: SourceContentState,
    provider: MusicProvider?,
    busy: Boolean,
    followed: Boolean?,
    viewModel: SourceContentViewModel,
    onInformation: () -> Unit,
    selection: Pair<Track, Rect>?,
    onDismissMenu: () -> Unit,
    onFollow: () -> Unit,
    onPlay: (List<Track>, Int) -> Unit,
    onMore: (Track, Rect) -> Unit,
) {
    val nav = LocalNavController.current
    val player = LocalPlayerConnection.current
    val currentId = player?.mediaMetadata?.collectAsState()?.value?.mediaId
    val playing = player?.isPlaying?.collectAsState()?.value == true
    val tracks = state.items.filterIsInstance<SourceItem.TrackItem>().map { it.track }
    val favoriteIds = rememberFavoriteTrackIds(remember(tracks) { tracks.map { it.toMediaMetadata() } })
    val albums = state.sections.flatMap { it.items }.filterIsInstance<SourceItem.AlbumItem>().map { it.album }
    val artist = (state.detail as? SourceItem.ArtistItem)?.artist
    val subtitle = remember(artist) { artist?.alternateNames.orEmpty().map(String::trim)
        .filter { it.isNotEmpty() && !it.equals(state.title, true) }.distinct().joinToString(" · ") }
    val landscape = rememberDeviceInfo().isLandscape
    val scroll = rememberLazyListState()
    var popularHeightPx by remember(ref) { mutableStateOf(0) }
    val latestArtworkHeight = with(LocalDensity.current) { popularHeightPx.toDp() }.takeIf { it > 0.dp }
        ?: (com.ljyh.mei.constants.LandscapeSongCoverSize * 3 + com.ljyh.mei.constants.HomeSongRowSpacing * 2)
    val canFollow = provider is CollectionCapability && SourceCapability.COLLECTIONS in provider.capabilities &&
        ContentType.ARTIST in provider.collectionTypes && provider.canWriteCollections && provider.canReadCollections
    val onAllSongs = {
        Screen.SourceArtistSongs.navigate(nav) { addPath(Uri.encode(ref.providerId)); addPath(Uri.encode(ref.artistId)) }
    }
    val onAllAlbums = {
        Screen.SourceArtistAlbums.navigate(nav) { addPath(Uri.encode(ref.providerId)); addPath(Uri.encode(ref.artistId)) }
    }
    ArtistDetailTheme(route = "${Screen.SourceContent.route}/${Uri.encode(ref.providerId)}/artist/${Uri.encode(ref.artistId)}",
        coverUrl = state.artworkUrl) { background ->
        val accent = remember(background) { ColorExtractionUtils.detailAccentColor(
            background.seedColor ?: Color.Gray, background.color, background.endColor) }
        val songSection: @Composable () -> Unit = {
            if (tracks.isNotEmpty()) {
                SectionTitle(stringResource(R.string.artist_hot_songs), onClick = onAllSongs)
                tracks.take(10).forEachIndexed { index, track ->
                    Column(Modifier.padding(horizontal = 20.dp)) {
                        DetailTrackRow(track.toMediaMetadata(), index + 1, showArtwork = true,
                            isCurrentTrack = track.toMediaMetadata().mediaId == currentId, isPlaying = playing,
                            isFavorite = track.toMediaMetadata().mediaId in favoriteIds, selected = null, onClick = { onPlay(tracks, index) },
                            onMoreClick = { onMore(track, it) })
                        if (index < minOf(10, tracks.size) - 1) DetailTrackDivider()
                    }
                }
            }
        }
        val albumSection: @Composable () -> Unit = {
            if (albums.isNotEmpty() || state.albumsHasMore || state.albumsError != null || state.albumsLoading) {
                Column {
                    SectionTitle(stringResource(R.string.artist_albums), onClick = onAllAlbums,
                        landscape = landscape, topPadding = 16.dp)
                    LazyRow(contentPadding = PaddingValues(horizontal = if (landscape)
                        com.ljyh.mei.constants.DetailPageHorizontalPadding else 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.swipeBackExcluded()) {
                        items(albums, key = { it.ref.albumId }) { album ->
                            ArtistAlbumCard(album.title, album.artworkUrl, album.trackCount,
                                com.ljyh.mei.constants.homePlaylistCardSize(landscape)) {
                                navigateSourceContent(nav, album.ref)
                            }
                        }
                        if (albums.isEmpty() && state.albumsLoading) items(4) {
                            com.ljyh.mei.ui.component.shimmer.ShimmerHost {
                                AlbumCardShimmer(com.ljyh.mei.constants.homePlaylistCardSize(landscape))
                            }
                        }
                    }
                    state.albumsError?.let { error ->
                        GlasmioListRow(sourceErrorMessage(error), trailing = { Text("重试") }, onClick = viewModel::loadMoreArtistAlbums)
                    }
                }
            }
        }
        val latestRelease: @Composable () -> Unit = {
            albums.maxByOrNull { it.publishedAtMs ?: Long.MIN_VALUE }?.let { album ->
                if (landscape) SectionTitle(stringResource(R.string.artist_latest_release), bottomPadding = 12.dp, landscape = true)
                SourceArtistFeaturedAlbum(album, provider, state.accountRevision, viewModel,
                    latestArtworkHeight, landscape, onOpen = { navigateSourceContent(nav, album.ref) })
            }
        }
        ArtistDetailPageLayout(title = state.title, background = background, scrollState = scroll,
            landscape = landscape, popularSongsHeightPx = popularHeightPx, onBack = nav::navigateUp,
            header = { _ ->
                if (state.detail == null && state.loading) ArtistHeaderShimmer(landscape) else ArtistDetailHeader(
                    name = state.title, subtitle = subtitle, cover = state.artworkUrl, accent = accent,
                    canPlayHotSongs = tracks.isNotEmpty() && player != null,
                    isFollowed = followed == true, isFollowLoading = busy,
                    onInfoClick = onInformation,
                    onPlayHotSongs = { if (tracks.isNotEmpty()) onPlay(tracks, 0) },
                    onFollowClick = onFollow,
                    landscape = landscape, canFollow = canFollow)
            }, latestRelease = latestRelease, songSection = songSection, albumSection = albumSection,
            popularSongs = {
                if (tracks.isNotEmpty()) {
                    SectionTitle(stringResource(R.string.artist_hot_songs), onClick = onAllSongs, landscape = true)
                    ArtistPopularSongs(tracks.take(10).map { it.toMediaMetadata() }, favoriteIds, currentId, playing,
                        onClick = { onPlay(tracks, it) },
                        onMore = { metadata, bounds -> tracks.firstOrNull { it.ref == metadata.trackRef }?.let { onMore(it, bounds) } },
                        onHeightChanged = { popularHeightPx = it })
                }
            }, footer = {
                state.error?.let { error -> item("artist-error") {
                    GlasmioListRow(sourceErrorMessage(error), trailing = { Text("重试") }, onClick = viewModel::refresh)
                } }
            })
        SourceTrackActions(selection, onDismissMenu, viewModel)
    }
}

@Composable
private fun SourceArtistFeaturedAlbum(
    album: SourceAlbumInfo, provider: MusicProvider?, revision: String?,
    viewModel: SourceContentViewModel, artworkHeight: Dp, landscape: Boolean, onOpen: () -> Unit,
) {
    val canSave = provider is CollectionCapability && SourceCapability.COLLECTIONS in provider.capabilities &&
        ContentType.ALBUM in provider.collectionTypes && provider.canWriteCollections && provider.canReadCollections
    val contentRevision by viewModel.contentRevision.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val nav = LocalNavController.current
    var loading by remember(album.ref, provider, revision, contentRevision) { mutableStateOf(false) }
    var error by remember(album.ref, provider, revision, contentRevision) { mutableStateOf<SourceErrorCode?>(null) }
    var attempt by remember(album.ref) { mutableIntStateOf(0) }
    var saved by remember(album.ref, provider, revision, contentRevision) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(album.ref, provider, revision, contentRevision, attempt) {
        loading = canSave
        error = null
        if (canSave) try { saved = viewModel.saved(album.ref) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (cause: Exception) { error = cause.sourceCode() }
        finally { loading = false }
    }
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val releaseDate = remember(album.publishedAtMs, configuration) {
        album.publishedAtMs?.takeIf { it > 0 }?.let {
            java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(it))
        }
    }
    ArtistFeaturedAlbumLayout(landscape, artworkHeight,
        artwork = { modifier -> AsyncImage(album.artworkUrl, album.title, modifier, contentScale = ContentScale.Crop) },
        information = {
            ArtistFeaturedAlbumInformation(album.title, album.trackCount, releaseDate, landscape)
        }, action = {
            if (canSave) Box(Modifier.size(44.dp).clickable(enabled = !busy && !loading, role = androidx.compose.ui.semantics.Role.Button) {
                when {
                    saved != null -> viewModel.setSaved(album.ref, saved != true)
                    error == SourceErrorCode.AUTH_REQUIRED || error == SourceErrorCode.AUTH_EXPIRED ->
                        Screen.SourceAccount.navigate(nav) { addPath(Uri.encode(album.ref.providerId)) }
                    else -> attempt++
                }
            }, contentAlignment = Alignment.Center) {
                Box(Modifier.size(if (landscape) 36.dp else 32.dp).clip(CircleShape)
                    .background(LocalGlassColors.current.content.copy(alpha = 0.10f)), contentAlignment = Alignment.Center) {
                    SfIcon(if (saved == true) "checkmark" else "plus", if (saved == true) "取消收藏" else "收藏",
                        size = if (landscape) 18.dp else 16.dp, weight = FontWeight.SemiBold,
                        tint = LocalGlassColors.current.content)
                }
            }
        }, onClick = onOpen)
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun SourceArtistCatalogScreen(ref: ArtistRef, albums: Boolean, viewModel: SourceContentViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    val contentRevision by viewModel.contentRevision.collectAsState()
    val nav = LocalNavController.current
    val landscape = rememberDeviceInfo().isLandscape
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    var query by rememberSaveable(ref, albums, stateSaver = androidx.compose.ui.text.input.TextFieldValue.Saver) { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue()) }
    var searchActive by remember(ref, albums) { mutableStateOf(false) }
    var menu by remember(ref) { mutableStateOf<Pair<Track, Rect>?>(null) }
    val tracks = state.items.filterIsInstance<SourceItem.TrackItem>().map { it.track }
    val records = state.sections.flatMap { it.items }.filterIsInstance<SourceItem.AlbumItem>().map { it.album }
    val visibleTracks = remember(tracks, query.text) { sourceDetailMatches(tracks, query.text) }
    val favoriteIds = rememberFavoriteTrackIds(remember(tracks) { tracks.map { it.toMediaMetadata() } }, enabled = !albums)
    val visibleAlbums = remember(records, query.text) { records.filter { album ->
        listOf(album.title, album.artists.joinToString(" ") { it.name }).joinToString(" ").contains(query.text.trim(), true)
    } }
    val play = rememberSourceQueuePlayer("source-artist:${ref.providerId}:${ref.artistId}", state.title,
        viewModel, SourceContentRequest.Detail(ref))
    LaunchedEffect(ref) { viewModel.open(SourceContentRequest.Detail(ref)) }
    LaunchedEffect(ref, albums, query.text) { viewModel.updateArtistCatalogSearch(ref, albums, query.text) }
    MeiloXPinnedListPage(stringResource(if (albums) R.string.artist_all_albums else R.string.artist_all_songs),
        bottomPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateBottomPadding(),
        horizontalContentPadding = 0.dp, largeTitleHorizontalPadding = 20.dp,
        verticalArrangement = Arrangement.spacedBy(0.dp), onNavigateBack = nav::navigateUp) {
        item("catalog-search") {
            GlasmioSearchBar(query, { query = it }, searchActive, { searchActive = it },
                onClose = { query = androidx.compose.ui.text.input.TextFieldValue(); searchActive = false },
                placeholder = stringResource(if (albums) R.string.artist_albums_search else R.string.artist_songs_search),
                closeContentDescription = stringResource(R.string.cancel), onSearch = { focus.clearFocus() },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 12.dp))
        }
        if (query.text.isNotBlank()) item("catalog-search-status") {
            ArtistCatalogSearchStatus(if (albums) records.size else tracks.size,
                if (albums) visibleAlbums.size else visibleTracks.size,
                state.loading || state.albumsLoading, if (albums) state.albumsHasMore else state.hasMore)
        }
        if (albums) {
            items(visibleAlbums, key = { it.ref.albumId }) { album ->
                var saved by remember(album.ref, state.providerVersion, state.accountRevision) { mutableStateOf(false) }
                LaunchedEffect(album.ref, state.providerVersion, state.accountRevision, contentRevision) {
                    saved = try { viewModel.saved(album.ref) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { false }
                }
                LandscapeMediaRow(image = album.artworkUrl, title = album.title,
                    artist = album.artists.joinToString(" / ") { it.name },
                    detail = stringResource(R.string.playlist_song_count, album.trackCount),
                    onClick = { navigateSourceContent(nav, album.ref) },
                    modifier = Modifier.padding(horizontal = if (landscape) 32.dp else 20.dp),
                    album = "", favorite = saved, showDivider = album != visibleAlbums.lastOrNull())
            }
            if (state.albumsError != null) item("catalog-albums-error") {
                GlasmioListRow(sourceErrorMessage(state.albumsError!!), onClick = {
                    if (query.text.isBlank()) viewModel.loadMoreArtistAlbums() else viewModel.continueArtistCatalogSearch()
                })
            }
            if (state.albumsLoading) item("catalog-albums-loading") { LoadingView() }
            if (state.albumsHasMore && !state.albumsLoading && !state.loading && state.albumsError == null) {
                item("catalog-albums-more:${state.albumsNextOffset}") {
                    if (query.text.isBlank()) {
                        LaunchedEffect(ref, state.providerVersion, state.accountRevision, state.albumsNextOffset, state.albumsNextCursor) {
                            viewModel.loadMoreArtistAlbums()
                        }
                        LoadingView()
                    } else GlasmioListRow(stringResource(R.string.artist_catalog_search_continue), onClick = viewModel::continueArtistCatalogSearch)
                }
            }
        } else {
            items(visibleTracks, key = { "${it.index}:${TrackRefCodec.encode(it.value.ref)}" }) { indexed ->
                val track = indexed.value
                LandscapeMediaRow(image = track.artworkUrl, title = track.title,
                    artist = track.artists.joinToString(" / ") { it.name },
                    detail = if (landscape) com.ljyh.mei.utils.TimeUtils.formatDuration(track.durationMs) else "",
                    onClick = { play(tracks, indexed.index) },
                    modifier = Modifier.padding(horizontal = if (landscape) 32.dp else 20.dp),
                    album = track.album.title, favorite = track.toMediaMetadata().mediaId in favoriteIds, showDivider = indexed != visibleTracks.lastOrNull(),
                    onMore = { menu = track to it })
            }
            sourceDetailPagingStatus(state, viewModel::refresh, {
                if (query.text.isBlank()) viewModel.loadMore() else viewModel.continueArtistCatalogSearch()
            }, automatic = false)
        }
        if (!state.loading && state.error == null && (if (albums) records.isEmpty() && !state.albumsHasMore else tracks.isEmpty() && !state.hasMore)) {
            item("artist-catalog-empty") {
                Text(stringResource(if (albums) R.string.artist_albums_empty else R.string.artist_songs_empty),
                    modifier = Modifier.padding(20.dp), color = LocalGlassColors.current.secondaryContent)
            }
        }
        if (state.loading) item("artist-catalog-loading") { LoadingView() }
        if (albums && state.error != null) item("artist-catalog-error") {
            GlasmioListRow(sourceErrorMessage(state.error!!), onClick = viewModel::refresh)
        }
    }
    SourceTrackActions(menu, { menu = null }, viewModel)
}

internal fun LazyListScope.sourceContentStatus(state: SourceContentState, onRetry: () -> Unit, onMore: () -> Unit) {
    if (state.loading) item("source-content-loading") { LoadingView() }
    state.error?.let { error -> item("source-content-error") { GlasmioGroupedList(Modifier.padding(top = 10.dp)) {
        GlasmioListRow(sourceErrorMessage(error), trailing = { Text("重试") }, onClick = onRetry)
    } } }
    if (!state.loading && !state.hasMore && !state.albumsHasMore && state.error == null && state.items.isEmpty() && state.sections.isEmpty()) item("source-content-empty") {
        GlasmioGroupedList(Modifier.padding(top = 10.dp)) { GlasmioListRow("暂无内容", showTopSeparator = false, showDisclosure = false) }
    }
    if (!state.loading && state.hasMore && state.error == null) item("source-content-more:${state.nextOffset}") {
        LaunchedEffect(state.providerId, state.providerVersion, state.accountRevision, state.nextOffset, state.nextCursor) {
            onMore()
        }
        LoadingView()
    }
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
    val providers by viewModel.providers.collectAsState()
    val provider = providers.firstOrNull { it.id == track.providerId && it.status == SourceStatus.READY }
    var showCreate by remember(track.providerId, provider, state.accountRevision) { mutableStateOf(false) }
    val canCreate = SourceCapability.PLAYLIST_EDIT in provider?.capabilities.orEmpty() &&
        PlaylistEditAction.CREATE in (provider as? PlaylistEditCapability)?.playlistEditActions.orEmpty()
    LaunchedEffect(track) { viewModel.open(SourceContentRequest.Library(LibraryCollection.CREATED_PLAYLISTS, track.providerId)) }
    val editable = state.items.filterIsInstance<SourceItem.PlaylistItem>()
        .filter { PlaylistEditAction.ADD_TRACKS in it.playlist.editableActions }
    MeiloXPinnedListPage("添加到歌单", onNavigateBack = nav::navigateUp,
        bottomPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateBottomPadding(),
        verticalArrangement = Arrangement.spacedBy(0.dp)) {
        if (canCreate) item("create-playlist") {
            GlasmioGroupedList(Modifier.padding(top = 10.dp, bottom = 24.dp)) {
                GlasmioListRow(stringResource(R.string.create_playlist_title), systemName = "plus.circle",
                    showTopSeparator = false, enabled = !busy && !state.loading && state.account != null,
                    onClick = { showCreate = true })
            }
        }
        groupedLazyItems(editable, key = { it.ref.playlistId }, firstItemTopPadding = if (canCreate) 0.dp else 10.dp) { item, index ->
            GlasmioListRow(item.title, showTopSeparator = index > 0, enabled = !busy,
                onClick = { viewModel.addTracks(item.ref, listOf(track)) { nav.navigateUp() } })
        }
        sourceContentStatus(state.copy(items = editable), viewModel::refresh, viewModel::loadMore)
    }
    if (showCreate && canCreate) com.ljyh.mei.ui.component.playlist.CreatePlaylistSheet(
        onDismiss = { showCreate = false },
        onConfirm = { name, privacy ->
            showCreate = false
            viewModel.createPlaylist(name, privacy = privacy, expectedProvider = provider,
                expectedAccountRevision = state.accountRevision)
        },
    )
    GlasmioAlertDialog(visible = message != null, onDismissRequest = viewModel::dismissMessage, title = "操作失败",
        message = message?.let(::sourceErrorMessage).orEmpty(), buttons = listOf(GlasmioAlertButtonSpec("好", onClick = viewModel::dismissMessage)))
}
