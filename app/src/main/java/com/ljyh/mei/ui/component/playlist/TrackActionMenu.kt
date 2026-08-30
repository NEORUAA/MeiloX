package com.ljyh.mei.ui.component.playlist

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.ljyh.mei.R
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.toMediaItem
import com.ljyh.mei.ui.glass.IosActionSheetContent
import com.ljyh.mei.ui.glass.IosListRow
import com.ljyh.mei.ui.glass.IosModalSheet
import com.ljyh.mei.ui.local.LocalPlayerConnection

@Composable
fun TrackActionMenu(
    targetTrack: MediaMetadata?,
    isCreator: Boolean = false,
    onDismiss: () -> Unit,
    onAddToPlaylist: (() -> Unit)? = null,
    onDownloadTrack: (() -> Unit)? = null,
    onDelete: () -> Unit? = {},
    onCopyId: () -> Unit,
    onCopyName: () -> Unit
) {
    if (targetTrack != null) {
        val context = LocalContext.current
        val playerConnection = LocalPlayerConnection.current
        val addToPlaylistTitle = stringResource(R.string.track_action_add_playlist)
        val downloadTitle = stringResource(R.string.track_action_download)
        val deleteTitle = stringResource(R.string.track_action_delete)
        val copyNameTitle = stringResource(R.string.track_action_copy_name)
        val copyIdTitle = stringResource(R.string.track_action_copy_id)
        IosModalSheet(
            onDismissRequest = onDismiss,
        ) {
            IosActionSheetContent(
                title = targetTrack.title,
                message = targetTrack.artists.joinToString(", ") { it.name },
            ) {
                playerConnection?.let { connection ->
                    IosListRow(
                        showTopSeparator = false,
                        systemName = "text.line.first.and.arrowtriangle.forward",
                        title = stringResource(R.string.download_play_next),
                        onClick = {
                            onDismiss()
                            connection.playNext(targetTrack.toMediaItem())
                            Toast.makeText(context, R.string.download_added_next, Toast.LENGTH_SHORT).show()
                        },
                    )
                    IosListRow(
                        systemName = "text.badge.plus",
                        title = stringResource(R.string.download_add_to_queue),
                        onClick = {
                            onDismiss()
                            connection.addToQueue(targetTrack.toMediaItem())
                            Toast.makeText(
                                context,
                                R.string.download_added_to_queue,
                                Toast.LENGTH_SHORT,
                            ).show()
                        },
                    )
                }
                onAddToPlaylist?.let { addToPlaylist ->
                    IosListRow(
                        showTopSeparator = playerConnection != null,
                        systemName = "plus.circle",
                        title = addToPlaylistTitle,
                        onClick = {
                            onDismiss()
                            addToPlaylist()
                        },
                    )
                }

                onDownloadTrack?.let { downloadFunc ->
                    IosListRow(
                        systemName = "arrow.down.circle",
                        title = downloadTitle,
                        onClick = {
                            onDismiss()
                            downloadFunc()
                        }
                    )
                }

                if (isCreator) {
                    IosListRow(
                        systemName = "trash",
                        title = deleteTitle,
                        onClick = {
                            onDismiss()
                            onDelete()
                        }
                    )
                }
                IosListRow(
                    systemName = "square.on.square",
                    title = copyNameTitle,
                    onClick = { onDismiss(); onCopyName() })
                IosListRow(
                    systemName = "info.circle",
                    title = copyIdTitle,
                    onClick = { onDismiss(); onCopyId() })
            }
        }
    }
}
