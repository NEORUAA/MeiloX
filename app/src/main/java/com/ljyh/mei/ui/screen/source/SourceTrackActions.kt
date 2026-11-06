package com.ljyh.mei.ui.screen.source

import android.net.Uri
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.media3.common.util.UnstableApi
import com.ljyh.mei.R
import com.ljyh.mei.constants.MusicQuality
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.toMediaItem
import com.ljyh.mei.data.model.toMediaMetadata
import com.ljyh.mei.data.model.toSourceTrack
import com.ljyh.mei.source.api.*
import com.ljyh.mei.ui.component.player.PlayerViewModel
import com.ljyh.mei.ui.component.player.component.sheet.SongInfoSheet
import com.ljyh.mei.ui.local.LocalNavController
import com.ljyh.mei.ui.local.LocalPlayerConnection
import com.ljyh.mei.ui.screen.Screen
import com.ljyh.mei.utils.setClipboard
import io.github.neoruaa.glasmio.ui.*
import kotlinx.coroutines.CancellationException

internal fun sourceContentSupported(ref: ContentRef, providers: List<MusicProvider>): Boolean {
    val provider = providers.firstOrNull { it.id == ref.providerId && it.status == SourceStatus.READY } ?: return false
    return when (ref) {
        is AlbumRef -> SourceCapability.ALBUM in provider.capabilities && provider is AlbumCapability
        is ArtistRef -> SourceCapability.ARTIST in provider.capabilities && provider is ArtistCapability
        is PlaylistRef -> SourceCapability.PLAYLIST in provider.capabilities && provider is PlaylistCapability
        is TrackRef -> SourceCapability.TRACK in provider.capabilities && provider is CatalogCapability
    }
}

/** All host menus share the same typed actions and account-bound like state. */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun sourceTrackActionItems(
    track: Track?,
    viewModel: SourceContentViewModel,
    playlist: SourcePlaylist? = null,
    includeQueue: Boolean = true,
): List<GlasmioCascadingMenuItem> {
    val nav = LocalNavController.current
    val player = LocalPlayerConnection.current
    val busy by viewModel.busy.collectAsState()
    val providers by viewModel.providers.collectAsState()
    val contentRevision by viewModel.contentRevision.collectAsState()
    val provider = providers.firstOrNull { it.id == track?.ref?.providerId && it.status == SourceStatus.READY }
    val accountRevision = (provider as? AccountRevisionCapability)?.accountRevision?.collectAsState(initial = "")?.value
    val likes = (provider as? LikeCapability)?.takeIf {
        SourceCapability.LIKE in provider.capabilities && it.canReadLikes && it.canWriteLikes
    }
    val editor = (provider as? PlaylistEditCapability)?.takeIf { SourceCapability.PLAYLIST_EDIT in provider.capabilities }
    var liked by remember(track?.ref, provider, accountRevision, contentRevision) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(track?.ref, provider, accountRevision, contentRevision) {
        if (track != null && likes != null) try { liked = viewModel.liked(track.ref) }
        catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { liked = null }
    }
    if (track == null) return emptyList()
    return buildList {
        if (includeQueue && player != null) {
            add(GlasmioCascadingMenuItem("播放下一首", "text.line.first.and.arrowtriangle.forward",
                onClick = { player.playNext(track.toMediaMetadata().toMediaItem()) }))
            add(GlasmioCascadingMenuItem("添加到队列", "text.line.last.and.arrowtriangle.forward",
                onClick = { player.addToQueue(track.toMediaMetadata().toMediaItem()) }))
        }
        if (likes != null) {
            val nextLiked = liked != true
            add(GlasmioCascadingMenuItem(if (liked == true) "取消喜欢" else "喜欢", if (liked == true) "star.fill" else "star",
                enabled = !busy && liked != null,
                onClick = { viewModel.setLiked(track.ref, nextLiked, provider, accountRevision) }))
        }
        if (PlaylistEditAction.ADD_TRACKS in editor?.playlistEditActions.orEmpty()) {
            add(GlasmioCascadingMenuItem("添加到歌单", "text.badge.plus", onClick = {
                Screen.SourcePlaylistPicker.navigate(nav) { addPath(Uri.encode(TrackRefCodec.encode(track.ref))) }
            }))
        }
        if (playlist != null && playlist.ref.providerId == track.ref.providerId &&
            PlaylistEditAction.REMOVE_TRACKS in playlist.editableActions &&
            PlaylistEditAction.REMOVE_TRACKS in editor?.playlistEditActions.orEmpty()) {
            add(GlasmioCascadingMenuItem("从歌单移除", "text.badge.minus", destructive = true, enabled = !busy,
                onClick = { viewModel.removeTrack(playlist.ref, track.ref) }))
        }
        track.album.ref?.takeIf { sourceContentSupported(it, providers) }?.let { ref ->
            add(GlasmioCascadingMenuItem("查看专辑", "square.stack", onClick = { navigateSourceContent(nav, ref) }))
        }
        track.artists.filter { it.ref?.let { ref -> sourceContentSupported(ref, providers) } == true }
            .distinctBy { it.ref }.forEach { artist ->
                add(GlasmioCascadingMenuItem(artist.name, "person", onClick = { artist.ref?.let { navigateSourceContent(nav, it) } }))
            }
    }
}

@Composable
internal fun SourceTrackActionMessage(viewModel: SourceContentViewModel) {
    val message by viewModel.message.collectAsState()
    GlasmioAlertDialog(visible = message != null, onDismissRequest = viewModel::dismissMessage,
        title = "操作失败", message = message?.let(::sourceErrorMessage).orEmpty(),
        buttons = listOf(GlasmioAlertButtonSpec("好", onClick = viewModel::dismissMessage)))
}

@Composable
internal fun SourceTrackActions(
    selection: Pair<Track, Rect>?, onDismiss: () -> Unit, viewModel: SourceContentViewModel,
    playlist: SourcePlaylist? = null,
) {
    val context = LocalContext.current
    val playerViewModel: PlayerViewModel = hiltViewModel()
    var information by remember { mutableStateOf<MediaMetadata?>(null) }
    LaunchedEffect(selection) { if (selection != null) information = null }
    selection?.let { selected ->
        val metadata = selected.first.toMediaMetadata()
        key(selected.first.ref, selected.second) {
            SourceTrackActions(metadata = metadata, onDismiss = onDismiss, viewModel = viewModel,
                anchorBounds = selected.second, playerViewModel = playerViewModel, playlist = playlist,
                onSongInfo = { information = metadata },
                onDownloadTrack = { quality -> playerViewModel.downloadSong(metadata, context, quality) })
        }
    }
    information?.let { metadata ->
        val qqSongId by produceState<String?>(null, metadata.trackRef) {
            value = playerViewModel.getQQSongId(metadata)
        }
        SongInfoSheet(metadata, qqSongId, onDismissRequest = { information = null })
    }
    SourceTrackActionMessage(viewModel)
}

/** Metadata menus retain download, information, and copy actions without native numeric IDs. */
@Composable
internal fun SourceTrackActions(
    metadata: MediaMetadata,
    onDismiss: () -> Unit,
    viewModel: SourceContentViewModel,
    anchorBounds: Rect? = null,
    playerViewModel: PlayerViewModel,
    onSongInfo: () -> Unit,
    onDownloadTrack: ((MusicQuality) -> Unit)? = null,
    playlist: SourcePlaylist? = null,
) {
    val context = LocalContext.current
    val canDownload = onDownloadTrack != null && !metadata.isLocal && !metadata.isPodcast
    val providers by viewModel.providers.collectAsState()
    val provider = providers.firstOrNull { it.id == metadata.trackRef.providerId }
    var qualities by remember(metadata.trackRef, provider) { mutableStateOf<List<MusicQuality>?>(null) }
    LaunchedEffect(metadata.trackRef, provider, canDownload) {
        if (canDownload) qualities = try { playerViewModel.getAvailableDownloadQualities(metadata) }
        catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { emptyList() }
    }
    val actions = sourceTrackActionItems(metadata.toSourceTrack(), viewModel, playlist)
    val items = buildList {
        addAll(actions)
        if (canDownload) {
            val download = onDownloadTrack!!
            val choices = qualities
            add(GlasmioCascadingMenuItem(stringResource(R.string.track_action_download_song), "arrow.down.circle",
                children = if (choices.isNullOrEmpty()) listOf(GlasmioCascadingMenuItem(
                    stringResource(if (choices == null) R.string.track_quality_loading else R.string.track_quality_unavailable), enabled = false))
                else choices.map { quality -> GlasmioCascadingMenuItem(stringResource(quality.labelRes), onClick = { download(quality) }) }))
        }
        add(GlasmioCascadingMenuItem(stringResource(R.string.more_action_song_info), "info.circle", onClick = onSongInfo))
        add(GlasmioCascadingMenuItem(stringResource(R.string.track_action_copy_name), "square.on.square",
            onClick = { setClipboard(context, metadata.title, "name") }))
        add(GlasmioCascadingMenuItem(stringResource(R.string.track_action_copy_id), "info.circle",
            onClick = { setClipboard(context, metadata.trackRef.trackId, "id") }))
    }
    GlasmioCascadingMenu(anchorBounds = anchorBounds, items = items, title = metadata.title,
        expandedDescription = stringResource(R.string.menu_expanded), collapsedDescription = stringResource(R.string.menu_collapsed),
        useAccentIcons = false, onDismiss = onDismiss)
}
