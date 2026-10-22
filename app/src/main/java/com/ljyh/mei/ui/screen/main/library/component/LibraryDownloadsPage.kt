package com.ljyh.mei.ui.screen.main.library.component

import android.app.Activity
import android.net.Uri
import android.provider.MediaStore
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import coil3.compose.AsyncImage
import com.kyant.capsule.ContinuousRoundedRectangle
import com.ljyh.mei.R
import com.ljyh.mei.constants.MusicQuality
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.room.DownloadStatus
import com.ljyh.mei.data.model.room.Song
import com.ljyh.mei.data.model.room.SourceType
import com.ljyh.mei.data.model.toMediaItem
import com.ljyh.mei.data.model.toMediaMetadata
import com.ljyh.mei.di.AppDatabase
import com.ljyh.mei.playback.queue.ListQueue
import com.ljyh.mei.ui.component.GlobalProfileAvatarButton
import com.ljyh.mei.ui.component.player.OverlayState
import com.ljyh.mei.ui.component.player.PlayerViewModel
import com.ljyh.mei.ui.glass.*
import com.ljyh.mei.ui.local.LocalNavController
import com.ljyh.mei.ui.local.LocalPlayerAwareWindowInsets
import com.ljyh.mei.ui.local.LocalPlayerConnection
import com.ljyh.mei.ui.navigation.LibraryPage
import com.ljyh.mei.ui.screen.local.LocalLibraryViewModel
import com.ljyh.mei.ui.screen.playlist.PlaylistViewModel
import com.ljyh.mei.ui.screen.playlist.component.StandaloneTrackActionOverlay
import com.ljyh.mei.ui.screen.social.NeteaseShareSheet
import com.ljyh.mei.utils.DownloadManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** The download category also owns the device's offline library. */
@Composable
internal fun LibraryDownloadsPage(
    isNavigationTab: Boolean,
    isCategoryPage: Boolean,
    pages: List<LibraryPage>,
    onPageSelect: (LibraryPage) -> Unit,
    listState: LazyListState,
    tabPosition: Animatable<Float, AnimationVector1D>,
    viewModel: LocalLibraryViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val navController = LocalNavController.current
    val player = LocalPlayerConnection.current
    val colors = LocalGlassColors.current
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val db = remember(context) { AppDatabase.getDatabase(context) }
    val tasksFlow = remember(db) { db.downloadDao().getAll() }
    val tasks by tasksFlow.collectAsState(initial = emptyList())
    val songs by viewModel.localSongs.collectAsState()
    val scan by viewModel.scanState.collectAsState()
    var search by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
    var searchActive by rememberSaveable { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf(DownloadLibraryFilter.All) }
    var sort by rememberSaveable { mutableStateOf(DownloadLibrarySort.Recent) }
    var sortAnchor by remember { mutableStateOf<Rect?>(null) }
    var sortOpen by remember { mutableStateOf(false) }
    val sortTriggerAlpha = remember { mutableFloatStateOf(1f) }
    val sortSeparation = remember { mutableFloatStateOf(0f) }
    var selectedKey by remember { mutableStateOf<String?>(null) }
    var menuAnchor by remember { mutableStateOf<Rect?>(null) }
    var info by remember { mutableStateOf<Song?>(null) }
    var deleteEntry by remember { mutableStateOf<DownloadLibraryEntry?>(null) }
    var permissionDenied by rememberSaveable { mutableStateOf(false) }
    var overlay by remember { mutableStateOf<OverlayState>(OverlayState.None) }
    var share by remember { mutableStateOf<MediaMetadata?>(null) }
    var pendingSystemDelete by remember { mutableStateOf<Song?>(null) }
    val playlistViewModel: PlaylistViewModel = hiltViewModel()
    val playerViewModel: PlayerViewModel = hiltViewModel()
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionDenied = !granted
        if (granted) viewModel.scan()
    }
    val systemDelete = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val song = pendingSystemDelete
        pendingSystemDelete = null
        if (result.resultCode == Activity.RESULT_OK && song != null) {
            scope.launch { db.songDao().updatePath(song.id, null) }
        }
    }
    val entries = remember(songs, tasks, search.text, filter, sort) {
        buildDownloadLibraryEntries(songs, tasks, search.text.trim(), filter, sort)
    }
    val allEntries = remember(songs, tasks) {
        buildDownloadLibraryEntries(songs, tasks, "", DownloadLibraryFilter.All, DownloadLibrarySort.Recent)
    }
    val playable = entries.filter { it.canPlay }
    val pageTitle = stringResource(if (isCategoryPage) R.string.app_tab_library_downloads else R.string.app_tab_library)
    val queueTitle = stringResource(R.string.app_tab_library_downloads)
    fun play(entry: DownloadLibraryEntry) {
        val index = playable.indexOfFirst { it.key == entry.key }
        if (index < 0) return
        player?.playQueue(ListQueue(
            id = "library-downloads",
            title = queueTitle,
            items = playable.mapNotNull { row -> row.song?.toMediaMetadata()?.let { it.id.toString() to it.toMediaItem() } },
            startIndex = index,
        ))
    }
    fun scanDevice() {
        if (viewModel.hasAudioPermission()) {
            permissionDenied = false
            viewModel.scan()
        } else permission.launch(LocalLibraryViewModel.audioPermission)
    }

    IosPinnedListPage(
        title = pageTitle,
        listState = listState,
        bottomPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateBottomPadding(),
        largeTitleHorizontalPadding = 0.dp,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        onNavigateBack = if (isNavigationTab) null else ({ navController.navigateUp() }),
        actions = {
            Box(Modifier.onGloballyPositioned { sortAnchor = it.boundsInWindow() }) {
                GlassIconButton(
                    onClick = { sortOpen = true },
                    modifier = Modifier.graphicsLayer { alpha = sortTriggerAlpha.floatValue },
                    groupSurfaceSeparation = { sortSeparation.floatValue },
                ) {
                    SfIcon("arrow.up.arrow.down", stringResource(R.string.local_library_sort), size = 18.dp)
                }
            }
            if (isNavigationTab) GlobalProfileAvatarButton()
        },
    ) {
        item("library-search") {
            GlassSearchBar(
                query = search,
                onQueryChange = { search = it },
                active = searchActive,
                onActiveChange = { searchActive = it },
                onClose = { search = TextFieldValue(); searchActive = false },
                placeholder = stringResource(R.string.search_bar_search),
                closeContentDescription = stringResource(R.string.cancel),
                onSearch = { focus.clearFocus() },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (!isCategoryPage) item("library-pages") {
            GlassSegmentedControl(
                items = pages.map { it to stringResource(it.titleRes) },
                selected = LibraryPage.Downloads,
                onSelected = onPageSelect,
                positionAnimation = tabPosition,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item("download-filters") {
            GlassSegmentedControl(
                items = DownloadLibraryFilter.entries.map { it to stringResource(it.labelRes()) },
                selected = filter,
                onSelected = { filter = it },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item("local-scan") {
            IosGroupedList {
                IosListRow(
                    title = stringResource(R.string.local_library_scan),
                    subtitle = stringResource(if (permissionDenied) R.string.local_library_permission else R.string.local_library_scan_hint),
                    systemName = "folder",
                    showTopSeparator = false,
                    onClick = if (scan.isScanning) null else ::scanDevice,
                    trailing = {
                        if (scan.isScanning) GlassIconButton(viewModel::cancelScan) {
                            SfIcon("xmark", stringResource(R.string.local_library_scan_cancel), size = 16.dp)
                        } else SfIcon("arrow.clockwise", null, size = 18.dp, tint = colors.accent)
                    },
                )
                if (scan.isScanning || scan.message != null || scan.error != null) {
                    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            if (scan.isScanning) stringResource(R.string.local_library_scan_progress, scan.scanned, scan.total)
                            else scan.error ?: scan.message.orEmpty(),
                            style = IosTypography.caption,
                            color = if (scan.error != null) colors.destructive else colors.secondaryContent,
                        )
                        if (scan.isScanning) {
                            if (scan.total > 0) LocalLibraryProgress(scan.scanned.toFloat() / scan.total)
                            else LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(3.dp), color = colors.accent, trackColor = colors.separator)
                        }
                    }
                }
                IosListRow(
                    title = stringResource(R.string.library_play_all),
                    systemName = "play.fill",
                    detail = stringResource(R.string.local_library_count, entries.size),
                    onClick = if (playable.isNotEmpty() && player != null) {
                        { playable.firstOrNull()?.let(::play) }
                    } else null,
                )
            }
        }
        if (entries.isEmpty()) item("offline-empty") {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SfIcon("music.note", null, size = 42.dp, tint = colors.secondaryContent)
                Text(stringResource(if (search.text.isNotBlank()) R.string.no_search_results else R.string.local_library_empty), style = IosTypography.headline, color = colors.content)
                if (search.text.isBlank()) Text(stringResource(R.string.local_library_empty_hint), style = IosTypography.subheadline, color = colors.secondaryContent)
            }
        }
        items(entries, key = { it.key }, contentType = { "offline-song" }) { entry ->
            DownloadLibraryRow(entry, onPlay = { play(entry) }, onMore = { selectedKey = entry.key; menuAnchor = it })
        }
    }

    if (sortOpen) IosCascadingMenu(
        anchorBounds = sortAnchor,
        title = stringResource(R.string.local_library_sort),
        expandedDescription = stringResource(R.string.menu_expanded),
        collapsedDescription = stringResource(R.string.menu_collapsed),
        onDismiss = { sortOpen = false },
        onTriggerAlphaChanged = { sortTriggerAlpha.floatValue = it },
        onTriggerSeparationChanged = if (isNavigationTab) {
            { sortSeparation.floatValue = it }
        } else null,
        items = DownloadLibrarySort.entries.map { value ->
            IosCascadingMenuItem(stringResource(value.labelRes()), systemName = if (value == sort) "checkmark" else null, onClick = { sort = value })
        },
    )
    allEntries.firstOrNull { it.key == selectedKey }?.let { entry ->
        val song = entry.song
        val task = entry.task
        val metadata = song?.toMediaMetadata()
        val retryMetadata = task?.takeIf { song?.sourceType != SourceType.LOCAL }?.let { download ->
            val id = download.songId.toLongOrNull()?.takeIf { it > 0L } ?: return@let null
            metadata ?: MediaMetadata(
                id = id,
                title = entry.title,
                coverUrl = entry.cover,
                artists = download.songArtist.split(Regex("[/、,;]")).map(String::trim)
                    .filter(String::isNotBlank).map { MediaMetadata.Artist(0L, it) },
                duration = 0L,
                album = MediaMetadata.Album(0L, entry.album),
            )
        }
        val retryQuality = task?.quality?.trim()?.let { saved ->
            MusicQuality.entries.firstOrNull {
                it.text.equals(saved, ignoreCase = true) || it.name.equals(saved, ignoreCase = true)
            }
        }
        DownloadTrackMenu(
            song = song, task = task, anchorBounds = menuAnchor,
            canPlay = entry.canPlay && player != null,
            matchTitle = stringResource(R.string.local_library_match),
            onDismiss = { selectedKey = null },
            onPlay = { play(entry) },
            onPlayNext = { metadata?.toMediaItem()?.let { player?.playNext(it) } },
            onAddToQueue = { metadata?.toMediaItem()?.let { player?.addToQueue(it) } },
            onDelete = { deleteEntry = entry },
            onMatch = song?.let { { viewModel.openMatch(it) } },
            onInfo = song?.let { { info = it } },
            onShare = metadata?.let { { share = it } },
            onAddToPlaylist = metadata?.let { { playlistViewModel.getAllMePlaylist(); overlay = OverlayState.AddToPlaylist(it.id) } },
            onPause = task?.let { { DownloadManager.pauseSong(context, it.songId) } },
            onResume = task?.let { { scope.launch { DownloadManager.resumeSong(context, it.songId, queueTitle) }; Unit } },
            onRetry = retryMetadata?.let { { playerViewModel.downloadSong(it, context, retryQuality) } },
        )
    }
    StandaloneTrackActionOverlay(overlay, { overlay = OverlayState.None }, { overlay = it }, playlistViewModel = playlistViewModel)
    share?.let { NeteaseShareSheet(it, onDismiss = { share = null }) }
    info?.let { song -> LocalFileInfo(song) { info = null } }
    LocalMatchSheet(viewModel)
    deleteEntry?.let { entry ->
        IosAlertDialog(
            onDismissRequest = { deleteEntry = null },
            title = if (entry.canPlay) stringResource(R.string.local_library_delete_confirm, entry.title) else stringResource(R.string.local_library_remove_record),
            message = if (entry.canPlay) stringResource(R.string.local_library_delete_hint) else null,
            buttons = listOf(
                IosAlertButtonSpec(stringResource(R.string.cancel), { deleteEntry = null }, IosAlertButtonRole.Cancel),
                IosAlertButtonSpec(stringResource(R.string.delete), {
                    deleteEntry = null
                    if (scan.isScanning) viewModel.cancelScan()
                    if (entry.task != null) DownloadManager.deleteTask(context, entry.task.songId) { removed ->
                        if (!removed) Toast.makeText(context, R.string.local_library_delete_failed, Toast.LENGTH_SHORT).show()
                    }
                    else entry.song?.let { song ->
                        val uri = song.path?.let(Uri::parse)
                        if (uri?.scheme == "content") {
                            runCatching {
                                val request = MediaStore.createDeleteRequest(context.contentResolver, listOf(uri))
                                pendingSystemDelete = song
                                systemDelete.launch(IntentSenderRequest.Builder(request.intentSender).build())
                            }.onFailure { Toast.makeText(context, R.string.local_library_delete_failed, Toast.LENGTH_SHORT).show() }
                        } else scope.launch {
                            val removed = withContext(Dispatchers.IO) {
                                runCatching { File(if (uri?.scheme == "file") uri.path.orEmpty() else song.path.orEmpty()).let { !it.exists() || it.delete() } }.getOrDefault(false)
                            }
                            if (removed) db.songDao().updatePath(song.id, null)
                            else Toast.makeText(context, R.string.local_library_delete_failed, Toast.LENGTH_SHORT).show()
                        }
                    }
                }, IosAlertButtonRole.Destructive),
            ),
        )
    }
}

@Composable
internal fun DownloadLibraryRow(entry: DownloadLibraryEntry, onPlay: () -> Unit, onMore: (Rect) -> Unit) {
    val colors = LocalGlassColors.current
    var anchor by remember { mutableStateOf(Rect.Zero) }
    val task = entry.task
    val detail = when (task?.status) {
        DownloadStatus.PENDING -> stringResource(R.string.download_waiting)
        DownloadStatus.DOWNLOADING -> "${task.progress.coerceIn(0, 100)}%"
        DownloadStatus.PAUSED -> stringResource(R.string.download_paused)
        DownloadStatus.FAILED -> stringResource(R.string.download_failed)
        DownloadStatus.COMPLETED -> if (entry.canPlay) entry.song?.fileFormat?.uppercase().orEmpty().ifBlank { stringResource(R.string.local_library_downloaded) } else stringResource(R.string.download_file_unavailable)
        null -> entry.song?.fileFormat?.uppercase().orEmpty()
    }
    Column {
        Row(
            Modifier.fillMaxWidth().clip(ContinuousRoundedRectangle(16.dp)).then(if (entry.canPlay) Modifier.clickable(onClick = onPlay) else Modifier).padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(56.dp).clip(ContinuousRoundedRectangle(12.dp)).background(colors.separator.copy(alpha = 0.25f)), contentAlignment = Alignment.Center) {
                SfIcon("music.note", null, size = 24.dp, tint = colors.secondaryContent)
                AsyncImage(entry.cover.ifBlank { null }, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(entry.title, style = IosTypography.body, color = colors.content, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(entry.artist.ifBlank { entry.album }, style = IosTypography.subheadline, color = colors.secondaryContent, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (detail.isNotBlank()) Text(detail, Modifier.padding(start = 8.dp).widthIn(max = 74.dp), style = IosTypography.caption, color = if (task?.status == DownloadStatus.FAILED) colors.destructive else colors.secondaryContent, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Box(Modifier.size(44.dp).onGloballyPositioned { anchor = it.boundsInWindow() }.clip(CircleShape).clickable(onClick = { onMore(anchor) }), contentAlignment = Alignment.Center) {
                SfIcon("ellipsis", stringResource(R.string.more_actions_title), size = 20.dp)
            }
        }
        if (task != null && task.status != DownloadStatus.COMPLETED && task.status != DownloadStatus.FAILED) {
            Box(Modifier.padding(start = 68.dp, top = 4.dp, bottom = 4.dp)) { LocalLibraryProgress(task.progress / 100f) }
        }
    }
}

@Composable
private fun LocalLibraryProgress(progress: Float) {
    val colors = LocalGlassColors.current
    LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(3.dp).clip(CircleShape), color = colors.accent, trackColor = colors.separator.copy(alpha = 0.4f), drawStopIndicator = {})
}

@Composable
private fun LocalFileInfo(song: Song, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val colors = LocalGlassColors.current
    IosModalSheet(onDismissRequest = onDismiss) {
        IosActionSheetContent(title = song.title, message = song.artist.joinToString(" / ")) {
            val fields = listOf(
                stringResource(R.string.local_library_format) to song.fileFormat.orEmpty().uppercase().ifBlank { "—" },
                stringResource(R.string.local_library_duration) to formatLocalDuration(song.duration),
                stringResource(R.string.local_library_size) to Formatter.formatFileSize(context, song.fileSize),
                stringResource(R.string.local_library_audio) to listOfNotNull(song.bitrate?.takeIf { it > 0 }?.let { "${it / 1000} kbps" }, song.sampleRate?.takeIf { it > 0 }?.let { "$it Hz" }).joinToString(" · ").ifBlank { "—" },
                stringResource(R.string.local_library_path) to song.path.orEmpty(),
            )
            fields.forEach { (label, value) ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(label, style = IosTypography.caption, color = colors.secondaryContent)
                    Text(value, style = IosTypography.body, color = colors.content)
                }
            }
        }
    }
}

@Composable
private fun LocalMatchSheet(viewModel: LocalLibraryViewModel) {
    val state by viewModel.matchState.collectAsState()
    val colors = LocalGlassColors.current
    var candidate by remember(state.song?.id) { mutableStateOf<MediaMetadata?>(null) }
    var query by remember(state.song?.id) { mutableStateOf(TextFieldValue(state.query)) }
    var searchActive by remember(state.song?.id) { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    if (state.song == null) return
    IosModalSheet(onDismissRequest = viewModel::dismissMatch) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stringResource(R.string.local_library_match), style = IosTypography.title2, color = colors.content)
            Text(stringResource(R.string.local_library_match_hint), style = IosTypography.subheadline, color = colors.secondaryContent)
            GlassSearchBar(query = query, onQueryChange = { query = it }, active = searchActive, onActiveChange = { searchActive = it }, onClose = { searchActive = false; focus.clearFocus() },
                placeholder = stringResource(R.string.search_bar_search), closeContentDescription = stringResource(R.string.cancel),
                onSearch = { searchActive = false; focus.clearFocus(); viewModel.searchMatches(query.text) }, modifier = Modifier.fillMaxWidth())
            GlassButton(onClick = { searchActive = false; focus.clearFocus(); viewModel.searchMatches(query.text) }, enabled = !state.isSearching && query.text.isNotBlank()) {
                SfIcon("magnifyingglass", null, size = 17.dp)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.search_bar_search))
            }
            if (state.isSearching || state.isApplying) LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp), color = colors.accent, trackColor = colors.separator)
            state.error?.let { Text(it, color = colors.destructive, style = IosTypography.subheadline) }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 330.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(state.candidates, key = { it.id }) { result ->
                    IosListRow(title = result.title, subtitle = result.artists.joinToString(" / ") { it.name }, detail = formatLocalDuration(result.duration), showTopSeparator = false,
                        leading = { AsyncImage(result.coverUrl, null, Modifier.size(44.dp).clip(ContinuousRoundedRectangle(10.dp)), contentScale = ContentScale.Crop) },
                        onClick = if (state.isApplying) null else ({ searchActive = false; focus.clearFocus(); candidate = result }))
                }
                if (!state.isSearching && state.candidates.isEmpty() && state.error == null) item { Text(stringResource(R.string.local_library_no_matches), style = IosTypography.subheadline, color = colors.secondaryContent) }
            }
        }
    }
    candidate?.let { result ->
        IosAlertDialog(onDismissRequest = { candidate = null }, title = stringResource(R.string.local_library_match_confirm),
            message = "${result.title}\n${result.artists.joinToString(" / ") { it.name }}\n${result.album.title}",
            buttons = listOf(
                IosAlertButtonSpec(stringResource(R.string.cancel), { candidate = null }, IosAlertButtonRole.Cancel),
                IosAlertButtonSpec(stringResource(R.string.local_library_apply), { candidate = null; viewModel.applyMatch(result) }),
            ))
    }
}

private fun formatLocalDuration(milliseconds: Long): String {
    val seconds = (milliseconds / 1000).coerceAtLeast(0)
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

private fun DownloadLibraryFilter.labelRes() = when (this) {
    DownloadLibraryFilter.All -> R.string.local_library_filter_all
    DownloadLibraryFilter.Downloaded -> R.string.local_library_filter_downloaded
    DownloadLibraryFilter.Local -> R.string.local_library_filter_local
    DownloadLibraryFilter.Active -> R.string.local_library_filter_active
}

private fun DownloadLibrarySort.labelRes() = when (this) {
    DownloadLibrarySort.Recent -> R.string.local_library_sort_recent
    DownloadLibrarySort.Title -> R.string.local_library_sort_title
    DownloadLibrarySort.Artist -> R.string.local_library_sort_artist
}
