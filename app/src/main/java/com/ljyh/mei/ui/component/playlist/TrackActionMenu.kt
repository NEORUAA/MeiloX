package com.ljyh.mei.ui.component.playlist

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.ljyh.mei.R
import com.ljyh.mei.constants.MusicQuality
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.toMediaItem
import com.ljyh.mei.ui.glass.IosCascadingMenu
import com.ljyh.mei.ui.glass.IosCascadingMenuItem
import com.ljyh.mei.ui.local.LocalPlayerConnection
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.ljyh.mei.ui.component.player.PlayerViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

@Composable
fun TrackActionMenu(
    targetTrack: MediaMetadata?,
    isCreator: Boolean = false,
    onDismiss: () -> Unit,
    onAddToPlaylist: (() -> Unit)? = null,
    onDownloadTrack: ((MusicQuality) -> Unit)? = null,
    onDelete: () -> Unit? = {},
    onCopyId: () -> Unit,
    onCopyName: () -> Unit,
    anchorBounds: Rect? = null,
    onShare: () -> Unit,
) {
    if (targetTrack == null) return
    val context = LocalContext.current
    val connection = LocalPlayerConnection.current
    val canDownload = onDownloadTrack != null && !targetTrack.isLocal && !targetTrack.isPodcast
    val downloadViewModel = if (canDownload) hiltViewModel<PlayerViewModel>() else null
    var downloadQualities by remember(targetTrack.id, downloadViewModel) {
        mutableStateOf<List<MusicQuality>?>(null)
    }
    LaunchedEffect(targetTrack.id, downloadViewModel) {
        if (downloadViewModel == null) return@LaunchedEffect
        val qualities = try {
            downloadViewModel.getAvailableDownloadQualities(targetTrack.id)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            emptyList()
        }
        currentCoroutineContext().ensureActive()
        downloadQualities = qualities
    }
    val items = mutableListOf<IosCascadingMenuItem>()
    if (connection != null) {
        items += IosCascadingMenuItem(
            title = stringResource(R.string.download_play_next),
            systemName = "text.line.first.and.arrowtriangle.forward",
            onClick = {
                connection.playNext(targetTrack.toMediaItem())
                Toast.makeText(context, R.string.download_added_next, Toast.LENGTH_SHORT).show()
            },
        )
        items += IosCascadingMenuItem(
            title = stringResource(R.string.download_add_to_queue),
            systemName = "text.badge.plus",
            onClick = {
                connection.addToQueue(targetTrack.toMediaItem())
                Toast.makeText(context, R.string.download_added_to_queue, Toast.LENGTH_SHORT).show()
            },
        )
    }
    val headerActions = mutableListOf<IosCascadingMenuItem>()
    onAddToPlaylist?.let {
        headerActions += IosCascadingMenuItem(
            title = stringResource(R.string.track_action_add_playlist),
            systemName = "plus.circle.fill",
            onClick = it,
        )
    }
    headerActions += IosCascadingMenuItem(
        title = stringResource(R.string.track_action_share),
        systemName = "square.and.arrow.up.fill",
        onClick = onShare,
    )

    onDownloadTrack?.takeIf { canDownload }?.let { download ->
        val qualityOptions = downloadQualities
        val children = if (qualityOptions.isNullOrEmpty()) {
            listOf(IosCascadingMenuItem(
                title = stringResource(
                    if (qualityOptions == null) R.string.track_quality_loading
                    else R.string.track_quality_unavailable,
                ),
                enabled = false,
            ))
        } else {
            qualityOptions.map { quality ->
                IosCascadingMenuItem(stringResource(quality.labelRes), onClick = { download(quality) })
            }
        }
        items += IosCascadingMenuItem(
            title = stringResource(R.string.track_action_download_song),
            systemName = "arrow.down.circle",
            children = children,
        )
    }
    if (isCreator) {
        items += IosCascadingMenuItem(
            title = stringResource(R.string.track_action_delete),
            systemName = "trash",
            destructive = true,
            onClick = { onDelete() },
        )
    }
    items += IosCascadingMenuItem(
        title = stringResource(R.string.track_action_copy_name),
        systemName = "square.on.square",
        onClick = onCopyName,
    )
    items += IosCascadingMenuItem(
        title = stringResource(R.string.track_action_copy_id),
        systemName = "info.circle",
        onClick = onCopyId,
    )
    IosCascadingMenu(
        anchorBounds = anchorBounds,
        items = items,
        headerActions = headerActions,
        useAccentIcons = false,
        title = targetTrack.title,
        expandedDescription = stringResource(R.string.menu_expanded),
        collapsedDescription = stringResource(R.string.menu_collapsed),
        onDismiss = onDismiss,
    )
}
