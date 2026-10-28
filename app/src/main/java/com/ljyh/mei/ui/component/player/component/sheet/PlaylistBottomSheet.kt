package com.ljyh.mei.ui.component.player.component.sheet

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import coil3.compose.AsyncImage
import com.kyant.capsule.ContinuousRoundedRectangle
import com.ljyh.mei.R
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.metadata
import com.ljyh.mei.playback.playbackOrderIndices
import com.ljyh.mei.playback.queueEntryId
import com.ljyh.mei.ui.component.sheet.rememberMeiloXSheetDismissal
import io.github.neoruaa.glasmio.scaffold.rememberGlasmioHeaderScrollEdge
import io.github.neoruaa.glasmio.ui.GlasmioBottomSheet
import io.github.neoruaa.glasmio.ui.LocalGlasmioSheetContentPadding
import io.github.neoruaa.glasmio.ui.glasmioGroupedListBackground
import io.github.neoruaa.glasmio.ui.GlasmioSheetTopToolbar
import io.github.neoruaa.glasmio.ui.GlasmioSheetToolbarButton
import io.github.neoruaa.glasmio.core.IosTypography
import io.github.neoruaa.glasmio.core.LocalGlassColors
import io.github.neoruaa.glasmio.icons.SfIcon
import com.ljyh.mei.ui.local.LocalPlayerConnection
import com.ljyh.mei.utils.TimeUtils.formatDuration
import com.ljyh.mei.utils.smallImage
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

private data class QueueEntry(
    val key: String,
    val mediaItem: MediaItem,
    val rawIndex: Int,
)

private fun Player.queueEntries(): List<QueueEntry> {
    val timeline = currentTimeline
    return playbackOrderIndices().map { index ->
        val windowUid = if (index < timeline.windowCount) {
            timeline.getWindow(index, Timeline.Window()).uid
        } else {
            null
        }
        QueueEntry(
            key = getMediaItemAt(index).queueEntryId ?: windowUid?.let { "window:$it" }
                ?: "fallback:${getMediaItemAt(index).mediaId}:$index",
            mediaItem = getMediaItemAt(index),
            rawIndex = index,
        )
    }
}

@Composable
fun PlaylistContent(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    showTitleBar: Boolean = true,
    lazyListState: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp),
    onQueueSizeChanged: (Int) -> Unit = {},
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val context = LocalContext.current
    val colors = LocalGlassColors.current
    val hapticFeedback = LocalHapticFeedback.current
    val queueEntries = remember(playerConnection) {
        mutableStateListOf<QueueEntry>().apply { addAll(playerConnection.player.queueEntries()) }
    }
    val reorderableLazyListState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val player = playerConnection.player
        val entries = player.queueEntries().toMutableList()
        val fromIndex = entries.indexOfFirst { it.key == from.key }
        val toIndex = entries.indexOfFirst { it.key == to.key }
        if (fromIndex >= 0 && toIndex >= 0) {
            val rawFrom = entries[fromIndex].rawIndex
            val rawTo = entries[toIndex].rawIndex
            entries.add(toIndex, entries.removeAt(fromIndex))
            if (player.shuffleModeEnabled) {
                player.setPlaybackOrder(entries.map { it.rawIndex }, userEdit = true)
            } else {
                player.moveMediaItem(rawFrom, rawTo)
            }
            queueEntries.clear()
            queueEntries.addAll(player.queueEntries())
        }
        hapticFeedback.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
    }
    SideEffect { onQueueSizeChanged(queueEntries.size) }
    val currentMediaItemIndex by playerConnection.currentMediaItemIndex.collectAsState()
    val currentQueueIndex = queueEntries.indexOfFirst { it.rawIndex == currentMediaItemIndex }
    var initialScrollPending by remember { mutableStateOf(true) }

    LaunchedEffect(playerConnection) {
        snapshotFlow {
            lazyListState.layoutInfo.visibleItemsInfo.mapNotNull { visible ->
                queueEntries.firstOrNull { it.key == visible.key }?.mediaItem?.queueEntryId
            }
        }.collect { playerConnection.service.queueManager.loadMetadataForQueueEntries(it) }
    }

    LaunchedEffect(queueEntries.size, currentQueueIndex) {
        if (initialScrollPending && currentQueueIndex in queueEntries.indices) {
            lazyListState.scrollToItem(currentQueueIndex)
            initialScrollPending = false
        }
    }

    DisposableEffect(playerConnection) {
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (!events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
                        Player.EVENT_MEDIA_ITEM_TRANSITION)) return
                queueEntries.clear()
                queueEntries.addAll(playerConnection.player.queueEntries())
            }
        }
        playerConnection.player.addListener(listener)
        onDispose { playerConnection.player.removeListener(listener) }
    }

    Column(modifier) {
        if (showTitleBar) {
            GlasmioSheetTopToolbar(
                title = stringResource(R.string.queue_title, queueEntries.size),
                actions = {
                    GlasmioSheetToolbarButton(onClick = onDismiss) {
                        SfIcon("xmark", stringResource(R.string.queue_close), size = 20.dp)
                    }
                },
            )
        }

        if (queueEntries.isEmpty()) {
            Column(
                Modifier.fillMaxWidth().weight(1f).padding(contentPadding).padding(36.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                SfIcon("list.bullet", null, size = 40.dp, tint = colors.tertiaryContent)
                Text(
                    stringResource(R.string.queue_empty),
                    style = IosTypography.subheadline,
                    color = colors.secondaryContent,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                state = lazyListState,
                contentPadding = contentPadding,
            ) {
                itemsIndexed(queueEntries, key = { _, entry -> entry.key }) { index, entry ->
                    ReorderableItem(reorderableLazyListState, key = entry.key) {
                        val mediaItem = entry.mediaItem
                        val display = mediaItem.mediaMetadata
                        val metadata = mediaItem.metadata ?: MediaMetadata(
                            id = mediaItem.mediaId.toLongOrNull() ?: 0L,
                            title = display.title?.toString().orEmpty(),
                            coverUrl = display.artworkUri?.toString().orEmpty(),
                            artists = emptyList(),
                            duration = 0L,
                            album = MediaMetadata.Album(0L, display.albumTitle?.toString().orEmpty()),
                        )
                        val shape = when {
                            queueEntries.size == 1 -> ContinuousRoundedRectangle(26.dp)
                            index == 0 -> ContinuousRoundedRectangle(topStart = 26.dp, topEnd = 26.dp)
                            index == queueEntries.lastIndex -> ContinuousRoundedRectangle(bottomStart = 26.dp, bottomEnd = 26.dp)
                            else -> RectangleShape
                        }
                        Box(Modifier.fillMaxWidth().glasmioGroupedListBackground(shape)) {
                            PlaylistItem(
                                modifier = Modifier.longPressDraggableHandle(
                                    onDragStarted = {
                                        hapticFeedback.performHapticFeedback(
                                            HapticFeedbackType.GestureThresholdActivate,
                                        )
                                    },
                                    onDragStopped = {
                                        hapticFeedback.performHapticFeedback(HapticFeedbackType.GestureEnd)
                                    },
                                ),
                                metadata = metadata,
                                showTopSeparator = index != 0,
                                isCurrentPlaying = entry.rawIndex == currentMediaItemIndex,
                                onItemClick = {
                                    playerConnection.service.queueManager.seekToQueueEntry(entry.key)
                                },
                                onRemoveClick = {
                                    if (queueEntries.size > 1) {
                                        playerConnection.player.queueEntries().firstOrNull { it.key == entry.key }
                                            ?.let { playerConnection.player.removeMediaItem(it.rawIndex) }
                                    } else {
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.queue_cannot_remove_last),
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun PlaylistBottomSheet(onDismiss: () -> Unit) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val dismissal = rememberMeiloXSheetDismissal(onDismiss)
    val listState = rememberLazyListState()
    var queueSize by remember(playerConnection) { mutableIntStateOf(playerConnection.player.mediaItemCount) }
    GlasmioBottomSheet(
        onDismissRequest = dismissal::onDismissed,
        sheetState = dismissal.state,
        skipPartiallyExpanded = false,
        fitContent = false,
        title = stringResource(R.string.queue_title, queueSize),
        topBarBlurProgress = rememberGlasmioHeaderScrollEdge(listState),
        topBar = {
            GlasmioSheetTopToolbar(
                title = stringResource(R.string.queue_title, queueSize),
                actions = {
                    GlasmioSheetToolbarButton(onClick = { dismissal.requestDismiss() }) {
                        SfIcon("xmark", stringResource(R.string.queue_close), size = 20.dp)
                    }
                },
            )
        },
    ) {
        val sheetPadding = LocalGlasmioSheetContentPadding.current
        PlaylistContent(
            onDismiss = { dismissal.requestDismiss() },
            modifier = Modifier.fillMaxWidth().weight(1f),
            showTitleBar = false,
            lazyListState = listState,
            contentPadding = PaddingValues(
                start = 16.dp, top = sheetPadding.calculateTopPadding() + 8.dp,
                end = 16.dp, bottom = sheetPadding.calculateBottomPadding() + 16.dp,
            ),
            onQueueSizeChanged = { queueSize = it },
        )
    }
}

@Composable
fun PlaylistItem(
    modifier: Modifier,
    metadata: MediaMetadata,
    showTopSeparator: Boolean,
    isCurrentPlaying: Boolean,
    onItemClick: () -> Unit,
    onRemoveClick: () -> Unit,
) {
    val colors = LocalGlassColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .drawBehind {
                if (showTopSeparator) {
                    drawLine(
                        colors.separator,
                        start = androidx.compose.ui.geometry.Offset(72.dp.toPx(), 0.5.dp.toPx()),
                        end = androidx.compose.ui.geometry.Offset(size.width - 16.dp.toPx(), 0.5.dp.toPx()),
                        strokeWidth = 1.dp.toPx(),
                    )
                }
            }
            .clickable(onClick = onItemClick)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = metadata.coverUrl.smallImage(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(44.dp).clip(ContinuousRoundedRectangle(9.dp)),
        )
        Column(Modifier.weight(1f)) {
            Text(
                metadata.title,
                style = IosTypography.body,
                fontWeight = if (isCurrentPlaying) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isCurrentPlaying) colors.accent else colors.content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${metadata.artists.joinToString(", ") { it.name }} · ${formatDuration(metadata.duration)}",
                style = IosTypography.caption,
                color = colors.secondaryContent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (isCurrentPlaying) {
            SfIcon("speaker.wave.2.fill", null, size = 17.dp, tint = colors.accent)
        }
        Box(
            Modifier.size(44.dp).clip(ContinuousRoundedRectangle(50)).clickable(onClick = onRemoveClick),
            contentAlignment = Alignment.Center,
        ) {
            SfIcon("xmark.circle", stringResource(R.string.queue_remove), size = 20.dp, tint = colors.secondaryContent)
        }
    }
}

fun <T> MutableList<T>.move(from: Int, to: Int) {
    if (from == to) return
    add(to, removeAt(from))
}
