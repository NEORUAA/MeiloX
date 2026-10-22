package com.ljyh.mei.ui.screen.main.library.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.ljyh.mei.R
import com.ljyh.mei.data.model.room.DownloadStatus
import com.ljyh.mei.data.model.room.DownloadTask
import com.ljyh.mei.data.model.room.Song
import com.ljyh.mei.data.model.room.SourceType
import com.ljyh.mei.ui.glass.IosCascadingMenu
import com.ljyh.mei.ui.glass.IosCascadingMenuItem
import com.ljyh.mei.utils.setClipboard

/** Adapts local files and download tasks to the shared song dropdown menu. */
@Composable
internal fun DownloadTrackMenu(
    song: Song?,
    task: DownloadTask?,
    anchorBounds: Rect?,
    canPlay: Boolean,
    matchTitle: String,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onDelete: () -> Unit,
    onAddToPlaylist: (() -> Unit)? = null,
    onShare: (() -> Unit)? = null,
    onMatch: (() -> Unit)? = null,
    onInfo: (() -> Unit)? = null,
    onPause: (() -> Unit)? = null,
    onResume: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
) {
    if (song == null && task == null) return
    val context = LocalContext.current
    val title = song?.title?.ifBlank { null }
        ?: task?.songTitle?.ifBlank { null }
        ?: song?.id
        ?: task?.songId.orEmpty()
    val hasLocalFile = !song?.path.isNullOrBlank()
    val catalogId = task?.songId?.toLongOrNull()?.takeIf { it > 0L }
        ?: song?.takeIf { it.sourceType != SourceType.LOCAL }
            ?.id?.toLongOrNull()?.takeIf { it > 0L }
    val isCompleted = task == null || task.status == DownloadStatus.COMPLETED
    val headerActions = buildList {
        if (catalogId != null && canPlay) {
            onAddToPlaylist?.let { action ->
                add(IosCascadingMenuItem(
                    title = stringResource(R.string.track_action_add_playlist),
                    systemName = "plus.circle.fill",
                    onClick = action,
                ))
            }
            onShare?.let { action ->
                add(IosCascadingMenuItem(
                    title = stringResource(R.string.track_action_share),
                    systemName = "square.and.arrow.up.fill",
                    onClick = action,
                ))
            }
        }
    }
    val items = buildList {
        if (isCompleted && canPlay) {
            add(IosCascadingMenuItem(
                title = stringResource(R.string.player_play),
                systemName = "play.fill",
                onClick = onPlay,
            ))
            add(IosCascadingMenuItem(
                title = stringResource(R.string.download_play_next),
                systemName = "text.line.first.and.arrowtriangle.forward",
                onClick = onPlayNext,
            ))
            add(IosCascadingMenuItem(
                title = stringResource(R.string.download_add_to_queue),
                systemName = "text.badge.plus",
                onClick = onAddToQueue,
            ))
        }
        if (isCompleted && hasLocalFile) {
            onMatch?.let { action ->
                add(IosCascadingMenuItem(
                    title = matchTitle,
                    systemName = "magnifyingglass",
                    separatorBefore = canPlay,
                    onClick = action,
                ))
            }
            onInfo?.let { action ->
                add(IosCascadingMenuItem(
                    title = stringResource(R.string.more_action_song_info),
                    systemName = "info.circle",
                    onClick = action,
                ))
            }
        }
        when (task?.status) {
            DownloadStatus.PENDING, DownloadStatus.DOWNLOADING -> onPause?.let { action ->
                add(IosCascadingMenuItem(
                    title = stringResource(R.string.pause),
                    systemName = "pause.circle",
                    onClick = action,
                ))
            }
            DownloadStatus.PAUSED -> onResume?.let { action ->
                add(IosCascadingMenuItem(
                    title = stringResource(R.string.resume),
                    systemName = "arrow.clockwise",
                    onClick = action,
                ))
            }
            DownloadStatus.FAILED -> onRetry?.let { action ->
                add(IosCascadingMenuItem(
                    title = stringResource(R.string.retry),
                    systemName = "arrow.clockwise",
                    onClick = action,
                ))
            }
            DownloadStatus.COMPLETED -> if (!hasLocalFile) onRetry?.let { action ->
                add(IosCascadingMenuItem(
                    title = stringResource(R.string.retry),
                    systemName = "arrow.clockwise",
                    onClick = action,
                ))
            }
            null -> Unit
        }
        add(IosCascadingMenuItem(
            title = stringResource(R.string.track_action_copy_name),
            systemName = "square.on.square",
            onClick = { setClipboard(context, title, "name") },
        ))
        catalogId?.let { id ->
            add(IosCascadingMenuItem(
                title = stringResource(R.string.track_action_copy_id),
                systemName = "info.circle",
                onClick = { setClipboard(context, id.toString(), "id") },
            ))
        }
        add(IosCascadingMenuItem(
            title = stringResource(when {
                hasLocalFile -> R.string.download_delete_local_file
                task?.status == DownloadStatus.PENDING ||
                    task?.status == DownloadStatus.DOWNLOADING ||
                    task?.status == DownloadStatus.PAUSED -> R.string.download_cancel
                else -> R.string.download_delete_record
            }),
            systemName = "trash",
            destructive = true,
            separatorBefore = true,
            onClick = onDelete,
        ))
    }
    IosCascadingMenu(
        anchorBounds = anchorBounds,
        items = items,
        headerActions = headerActions,
        useAccentIcons = false,
        title = title,
        expandedDescription = stringResource(R.string.menu_expanded),
        collapsedDescription = stringResource(R.string.menu_collapsed),
        onDismiss = onDismiss,
    )
}
