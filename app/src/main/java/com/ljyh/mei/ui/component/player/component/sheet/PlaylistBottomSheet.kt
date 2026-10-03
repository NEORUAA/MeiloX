package com.ljyh.mei.ui.component.player.component.sheet

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
import com.kyant.shapes.Capsule
import com.ljyh.mei.R
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.metadata
import com.ljyh.mei.playback.playbackOrderIndices
import com.ljyh.mei.playback.queueEntryId
import com.ljyh.mei.ui.glass.IosModalSheetShape
import com.ljyh.mei.ui.glass.IosSheetSurface
import com.ljyh.mei.ui.glass.IosSheetTopToolbar
import com.ljyh.mei.ui.glass.IosSheetTopToolbarButton
import com.ljyh.mei.ui.glass.IosTypography
import com.ljyh.mei.ui.glass.LocalGlassColors
import com.ljyh.mei.ui.glass.LocalGlassDimensions
import com.ljyh.mei.ui.glass.LocalGroupedListBackgroundAlpha
import com.ljyh.mei.ui.glass.SfIcon
import com.ljyh.mei.ui.glass.applyGlassDragScale
import com.ljyh.mei.ui.liquidglass.InteractiveHighlight
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
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val context = LocalContext.current
    val colors = LocalGlassColors.current
    val queueBackground = colors.elevatedBackground.copy(
        alpha = LocalGroupedListBackgroundAlpha.current.coerceIn(0f, 1f),
    )
    val lazyListState = rememberLazyListState()
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
    val currentMediaItemIndex by playerConnection.currentMediaItemIndex.collectAsState()
    val currentQueueIndex = queueEntries.indexOfFirst { it.rawIndex == currentMediaItemIndex }
    var initialScrollPending by remember { mutableStateOf(true) }

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
            IosSheetTopToolbar(
                title = stringResource(R.string.queue_title, queueEntries.size),
                actions = {
                    IosSheetTopToolbarButton(onClick = onDismiss) {
                        SfIcon("xmark", stringResource(R.string.queue_close), size = 20.dp)
                    }
                },
            )
        }

        if (queueEntries.isEmpty()) {
            Column(
                Modifier.fillMaxWidth().weight(1f).padding(36.dp),
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
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 16.dp)
                    .clip(ContinuousRoundedRectangle(LocalGlassDimensions.current.regularCornerRadius))
                    .background(queueBackground),
                state = lazyListState,
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
                        run {
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistBottomSheet(onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val screenShape = IosModalSheetShape
    val animationScope = rememberCoroutineScope()
    val interactiveHighlight = remember(animationScope) {
        InteractiveHighlight(animationScope = animationScope)
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.graphicsLayer {
            clip = false
            applyGlassDragScale(
                pressProgress = interactiveHighlight.pressProgress,
                offset = interactiveHighlight.offset,
            )
        },
        containerColor = Color.Transparent,
        contentColor = LocalGlassColors.current.content,
        // Keep the host unclipped so the sheet's drag-scale overshoot reaches its outer outline.
        shape = RectangleShape,
        dragHandle = null,
        contentWindowInsets = { WindowInsets.statusBars },
    ) {
        IosSheetSurface(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.82f),
            shape = screenShape,
            interactiveHighlight = interactiveHighlight,
            applyDragScale = false,
        ) {
            Column(Modifier.fillMaxSize().navigationBarsPadding()) {
                Box(
                    Modifier.fillMaxWidth().height(16.dp),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Box(
                        Modifier
                            .padding(top = 5.dp)
                            .size(width = 58.dp, height = 4.dp)
                            .background(LocalGlassColors.current.tertiaryContent.copy(alpha = 0.55f), Capsule()),
                    )
                }
                PlaylistContent(
                    onDismiss = onDismiss,
                    modifier = Modifier.fillMaxSize().padding(bottom = 8.dp),
                )
            }
        }
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
                        start = androidx.compose.ui.geometry.Offset(72.dp.toPx(), 0f),
                        end = androidx.compose.ui.geometry.Offset(size.width - 16.dp.toPx(), 0f),
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
            Modifier.size(36.dp).clip(ContinuousRoundedRectangle(50)).clickable(onClick = onRemoveClick),
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
