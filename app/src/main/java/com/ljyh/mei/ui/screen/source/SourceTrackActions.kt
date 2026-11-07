package com.ljyh.mei.ui.screen.source

import android.net.Uri
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.ljyh.mei.constants.MusicQuality
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.toMediaMetadata
import com.ljyh.mei.source.api.*
import com.ljyh.mei.ui.component.player.PlayerViewModel
import com.ljyh.mei.ui.component.playlist.TrackActionMenu
import com.ljyh.mei.ui.local.LocalNavController
import com.ljyh.mei.ui.screen.Screen
import com.ljyh.mei.utils.setClipboard
import io.github.neoruaa.glasmio.ui.*

internal fun sourceContentSupported(ref: ContentRef, providers: List<MusicProvider>): Boolean {
    val provider = providers.firstOrNull { it.id == ref.providerId && it.status == SourceStatus.READY } ?: return false
    return when (ref) {
        is AlbumRef -> SourceCapability.ALBUM in provider.capabilities && provider is AlbumCapability
        is ArtistRef -> SourceCapability.ARTIST in provider.capabilities && provider is ArtistCapability
        is PlaylistRef -> SourceCapability.PLAYLIST in provider.capabilities && provider is PlaylistCapability
        is TrackRef -> SourceCapability.TRACK in provider.capabilities && provider is CatalogCapability
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
    var share by remember { mutableStateOf<MediaMetadata?>(null) }
    selection?.let { selected ->
        val metadata = selected.first.toMediaMetadata()
        key(selected.first.ref, selected.second) {
            SourceTrackActions(metadata = metadata, onDismiss = onDismiss, viewModel = viewModel,
                anchorBounds = selected.second, playerViewModel = playerViewModel, playlist = playlist,
                onShare = { onDismiss(); share = metadata },
                onDownloadTrack = { quality -> playerViewModel.downloadSong(metadata, context, quality) })
        }
    }
    share?.let { metadata -> com.ljyh.mei.ui.screen.social.NeteaseShareSheet(metadata, onDismiss = { share = null }) }
    SourceTrackActionMessage(viewModel)
}

/** Source capabilities supply actions to the original shared song menu. */
@Composable
internal fun SourceTrackActions(
    metadata: MediaMetadata,
    onDismiss: () -> Unit,
    viewModel: SourceContentViewModel,
    anchorBounds: Rect? = null,
    playerViewModel: PlayerViewModel,
    onShare: (() -> Unit)? = null,
    onDownloadTrack: ((MusicQuality) -> Unit)? = null,
    playlist: SourcePlaylist? = null,
) {
    val context = LocalContext.current
    val nav = LocalNavController.current
    val providers by viewModel.providers.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val provider = providers.firstOrNull {
        it.id == metadata.trackRef.providerId && it.status == SourceStatus.READY
    }
    val editor = (provider as? PlaylistEditCapability)?.takeIf {
        SourceCapability.PLAYLIST_EDIT in provider.capabilities
    }
    val canAdd = PlaylistEditAction.ADD_TRACKS in editor?.playlistEditActions.orEmpty()
    val canRemove = playlist != null && playlist.ref.providerId == metadata.trackRef.providerId &&
        PlaylistEditAction.REMOVE_TRACKS in playlist.editableActions &&
        PlaylistEditAction.REMOVE_TRACKS in editor?.playlistEditActions.orEmpty()
    key(metadata.trackRef, provider) {
        TrackActionMenu(
            targetTrack = metadata,
            anchorBounds = anchorBounds,
            onDismiss = onDismiss,
            onAddToPlaylist = if (canAdd) {
                {
                    Screen.SourcePlaylistPicker.navigate(nav) {
                        addPath(Uri.encode(TrackRefCodec.encode(metadata.trackRef)))
                    }
                }
            } else null,
            onShare = onShare?.takeUnless { metadata.isLocal || metadata.isPodcast },
            onDownloadTrack = onDownloadTrack,
            isCreator = canRemove,
            deleteEnabled = !busy,
            onDelete = { playlist?.let { viewModel.removeTrack(it.ref, metadata.trackRef) } },
            onCopyName = { setClipboard(context, metadata.title, "name") },
            onCopyId = { setClipboard(context, metadata.trackRef.trackId, "id") },
            playerViewModel = playerViewModel,
        )
    }
}
