package com.ljyh.mei.ui.component.playlist

import android.widget.Toast
import androidx.compose.runtime.Composable
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
    val qualityTitles = listOf(
        R.string.track_quality_standard,
        R.string.track_quality_high,
        R.string.track_quality_lossless,
        R.string.track_quality_hires,
        R.string.track_quality_surround,
        R.string.track_quality_spatial,
        R.string.track_quality_master,
    ).map { stringResource(it) }
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

    onDownloadTrack?.let { download ->
        items += IosCascadingMenuItem(
            title = stringResource(R.string.track_action_download_song),
            systemName = "arrow.down.circle",
            children = MusicQuality.entries.mapIndexed { index, quality ->
                IosCascadingMenuItem(qualityTitles[index], onClick = { download(quality) })
            },
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
