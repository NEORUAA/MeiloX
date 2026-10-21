package com.ljyh.mei.ui.component.player.component.applemusic

import android.widget.Toast
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import coil3.compose.AsyncImage
import com.kyant.capsule.ContinuousRoundedRectangle
import com.kyant.shapes.Capsule
import com.ljyh.mei.R
import com.ljyh.mei.constants.AutoMixEnabledKey
import com.ljyh.mei.constants.LoopPlaybackKey
import com.ljyh.mei.data.model.metadata
import com.ljyh.mei.playback.PlayerConnection
import com.ljyh.mei.playback.playbackOrderIndices
import com.ljyh.mei.playback.queueEntryId
import com.ljyh.mei.ui.glass.SfIcon
import com.ljyh.mei.utils.rememberPreference
import com.ljyh.mei.utils.smallImage
import kotlinx.coroutines.isActive
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

private data class PhoneQueueEntry(
    val key: String,
    val mediaItem: MediaItem,
    val rawIndex: Int,
)

@OptIn(UnstableApi::class)
private fun Player.phoneQueueEntries(): List<PhoneQueueEntry> {
    val timeline = currentTimeline
    return playbackOrderIndices().map { index ->
        val item = getMediaItemAt(index)
        val windowUid = if (index < timeline.windowCount) {
            timeline.getWindow(index, Timeline.Window()).uid
        } else null
        PhoneQueueEntry(
            key = item.queueEntryId ?: windowUid?.let { "window:$it" }
                ?: "fallback:${item.mediaId}:$index",
            mediaItem = item,
            rawIndex = index,
        )
    }
}

@OptIn(UnstableApi::class)
@Composable
fun PlayerQueuePage(
    playerConnection: PlayerConnection,
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = AppleMusicDimensions.lyricTextInset,
) {
    val selectionPadding = 5.dp
    val artworkCornerRadius = 5.dp
    val player = playerConnection.player
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val queueTitle by playerConnection.queueTitle.collectAsStateWithLifecycle()
    val currentIndex by playerConnection.currentMediaItemIndex.collectAsStateWithLifecycle()
    val shuffleEnabled by playerConnection.shuffleModeEnabled.collectAsStateWithLifecycle()
    val fmEnabled by playerConnection.isFMMode.collectAsStateWithLifecycle()
    val autoplayEnabled by playerConnection.queueAutoplayEnabled.collectAsStateWithLifecycle()
    val autoplayStartEntryId by playerConnection.queueAutoplayStartEntryId.collectAsStateWithLifecycle()
    val (autoMixEnabled, setAutoMixEnabled) = rememberPreference(AutoMixEnabledKey, false)
    val (_, setLoopPlayback) = rememberPreference(LoopPlaybackKey, true)
    var repeatMode by remember(player) { mutableIntStateOf(player.repeatMode) }
    val entries = remember(player) {
        mutableStateListOf<PhoneQueueEntry>().apply { addAll(player.phoneQueueEntries()) }
    }
    val listState = rememberLazyListState()
    var viewportCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var dragFrame by remember { mutableIntStateOf(0) }
    var initialScrollPending by remember(player) { mutableStateOf(true) }
    var menuEntryKey by remember { mutableStateOf<String?>(null) }
    val currentQueueIndex = entries.indexOfFirst { it.rawIndex == currentIndex }
    val autoplayQueueIndex = entries.indexOfFirst { it.key == autoplayStartEntryId }

    LaunchedEffect(entries.size, currentQueueIndex) {
        if (initialScrollPending && currentQueueIndex in entries.indices) {
            // Start with Up Next while keeping earlier queue entries reachable by scrolling.
            val nextIndex = (currentQueueIndex + 1).coerceAtMost(entries.lastIndex)
            val sectionOffset = if (autoplayEnabled && autoplayQueueIndex in 0..nextIndex) 1 else 0
            listState.scrollToItem(nextIndex + sectionOffset)
            initialScrollPending = false
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (events.containsAny(
                        Player.EVENT_TIMELINE_CHANGED,
                        Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
                        Player.EVENT_MEDIA_ITEM_TRANSITION,
                        Player.EVENT_MEDIA_METADATA_CHANGED,
                    )
                ) {
                    entries.clear()
                    entries.addAll(player.phoneQueueEntries())
                }
                if (events.contains(Player.EVENT_REPEAT_MODE_CHANGED)) repeatMode = player.repeatMode
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        val currentEntries = player.phoneQueueEntries().toMutableList()
        val fromIndex = currentEntries.indexOfFirst { it.key == from.key }
        val toIndex = currentEntries.indexOfFirst { it.key == to.key }
        if (fromIndex >= 0 && toIndex >= 0 && fromIndex != toIndex) {
            val rawFrom = currentEntries[fromIndex].rawIndex
            val rawTo = currentEntries[toIndex].rawIndex
            currentEntries.add(toIndex, currentEntries.removeAt(fromIndex))
            if (player.shuffleModeEnabled) {
                player.setPlaybackOrder(currentEntries.map { it.rawIndex }, userEdit = true)
            } else {
                player.moveMediaItem(rawFrom, rawTo)
            }
            entries.clear()
            entries.addAll(player.phoneQueueEntries())
            haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
        }
    }

    LaunchedEffect(reorderState.isAnyItemDragging) {
        if (reorderState.isAnyItemDragging) {
            // Drag translations do not change LazyList layout info; refresh their viewport masks.
            while (isActive) withFrameNanos { dragFrame++ }
        }
    }
    val normalFade = Modifier.queueViewportFade(viewportCoordinates, listState, { dragFrame })
    val additiveFade = Modifier.queueViewportFade(viewportCoordinates, listState, { dragFrame }, additive = true)

    Column(modifier.testTag("player_queue_page")) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = horizontalPadding),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            QueueModeButton(
                systemName = "shuffle",
                label = stringResource(R.string.notification_mode_shuffle),
                selected = shuffleEnabled,
                modifier = Modifier.weight(1f),
                enabled = !fmEnabled || autoplayEnabled,
                onClick = { playerConnection.service.setShuffleModeEnabled(!shuffleEnabled) },
            )
            QueueModeButton(
                systemName = if (repeatMode == Player.REPEAT_MODE_ONE) "repeat.1" else "repeat",
                label = stringResource(R.string.loop_playback),
                selected = repeatMode != Player.REPEAT_MODE_OFF,
                modifier = Modifier.weight(1f),
                onClick = {
                    val nextMode = when (repeatMode) {
                        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                        else -> Player.REPEAT_MODE_OFF
                    }
                    player.repeatMode = nextMode
                    setLoopPlayback(nextMode != Player.REPEAT_MODE_OFF)
                },
            )
            QueueModeButton(
                systemName = "infinity",
                label = stringResource(R.string.queue_autoplay),
                selected = autoplayEnabled,
                modifier = Modifier.weight(1f),
                enabled = !fmEnabled || autoplayEnabled,
                onClick = { playerConnection.setQueueAutoplayEnabled(!autoplayEnabled) },
            )
            QueueModeButton(
                systemName = "circlebadge.2",
                label = stringResource(R.string.automix_enabled),
                selected = autoMixEnabled,
                modifier = Modifier.weight(1f),
                onClick = { setAutoMixEnabled(!autoMixEnabled) },
            )
        }

        Column(
            Modifier.padding(horizontal = horizontalPadding)
                .padding(top = 18.dp, bottom = 10.dp)
                .playerAdditiveBlend(),
        ) {
            Text(
                stringResource(R.string.queue_continue_playing),
                color = Color.White.copy(alpha = 0.92f),
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(
                    R.string.queue_from_source,
                    queueTitle?.takeIf(String::isNotBlank) ?: stringResource(R.string.player_action_queue),
                ),
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (entries.isEmpty()) {
            Text(
                text = stringResource(R.string.queue_empty),
                modifier = Modifier.padding(horizontal = horizontalPadding, vertical = 24.dp).playerAdditiveBlend(),
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 15.sp,
            )
        } else {
            LazyColumn(
                state = listState,
                overscrollEffect = null,
                // Let the selection extend beyond the artwork and handle without insetting them.
                contentPadding = PaddingValues(horizontal = (horizontalPadding - selectionPadding).coerceAtLeast(0.dp)),
                modifier = Modifier.fillMaxWidth().weight(1f)
                    .onGloballyPositioned { viewportCoordinates = it },
            ) {
                entries.forEachIndexed { index, entry ->
                    if (autoplayEnabled && index == autoplayQueueIndex) {
                        item(key = "autoplay_section") {
                            QueueAutoplayHeader(additiveFade.padding(horizontal = selectionPadding))
                        }
                    }
                    item(key = entry.key) {
                        ReorderableItem(reorderState, key = entry.key) {
                            val item = entry.mediaItem
                            val metadata = item.metadata
                            val display = item.mediaMetadata
                            val title = metadata?.title ?: display.title?.toString().orEmpty()
                            val artist = metadata?.artists?.joinToString(", ") { it.name }
                                ?: display.artist?.toString().orEmpty()
                            val cover = metadata?.coverUrl ?: display.artworkUri?.toString().orEmpty()
                            Box {
                                if (entry.rawIndex == currentIndex) {
                                    Box(
                                        Modifier.matchParentSize()
                                            .then(normalFade)
                                            .clip(ContinuousRoundedRectangle(artworkCornerRadius + selectionPadding))
                                            .background(Color.White.copy(alpha = 0.06f)),
                                    )
                                }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        // 46dp artwork with a 10dp cover gap gives 5dp selection padding.
                                        .height(56.dp)
                                        .padding(selectionPadding),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Row(
                                        modifier = Modifier.weight(1f).fillMaxHeight().combinedClickable(
                                            onClick = {
                                                playerConnection.service.queueManager.seekToQueueEntry(entry.key)
                                            },
                                            onLongClick = { menuEntryKey = entry.key },
                                        ),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        AsyncImage(
                                            model = cover.smallImage(),
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.size(46.dp).clip(ContinuousRoundedRectangle(artworkCornerRadius))
                                                .then(normalFade),
                                        )
                                        Column(
                                            Modifier.weight(1f).padding(horizontal = 12.dp).then(additiveFade),
                                        ) {
                                            Text(
                                                title,
                                                color = Color.White.copy(alpha = 0.92f),
                                                fontSize = 16.sp,
                                                lineHeight = 20.sp,
                                                fontWeight = if (entry.rawIndex == currentIndex) FontWeight.SemiBold else FontWeight.Normal,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Text(
                                                artist,
                                                color = Color.White.copy(alpha = 0.5f),
                                                fontSize = 13.sp,
                                                lineHeight = 17.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                    Box(
                                        Modifier
                                            .size(32.dp, 46.dp)
                                            .longPressDraggableHandle(
                                                onDragStarted = {
                                                    haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                                },
                                                onDragStopped = {
                                                    haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
                                                },
                                            )
                                            .then(additiveFade),
                                        contentAlignment = Alignment.CenterEnd,
                                    ) {
                                        SfIcon(
                                            systemName = "line.3.horizontal",
                                            contentDescription = stringResource(R.string.queue_reorder),
                                            tint = Color.White.copy(alpha = 0.28f),
                                            size = 20.dp,
                                        )
                                    }
                                }
                                DropdownMenu(
                                    expanded = menuEntryKey == entry.key,
                                    onDismissRequest = { menuEntryKey = null },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.queue_remove)) },
                                        onClick = {
                                            menuEntryKey = null
                                            val currentEntries = player.phoneQueueEntries()
                                            if (currentEntries.size > 1) {
                                                currentEntries.firstOrNull { it.key == entry.key }?.let {
                                                    player.removeMediaItem(it.rawIndex)
                                                }
                                            } else {
                                                Toast.makeText(context, R.string.queue_cannot_remove_last, Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
                if (autoplayEnabled && autoplayQueueIndex < 0) {
                    item(key = "autoplay_section") {
                        QueueAutoplayHeader(additiveFade.padding(horizontal = selectionPadding))
                    }
                }
                item(key = "queue_bottom_space") { Spacer(Modifier.height(8.dp)) }
            }
        }
    }
}

@Composable
private fun QueueAutoplayHeader(modifier: Modifier) {
    Column(modifier.padding(top = 16.dp, bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SfIcon(
                "infinity",
                null,
                tint = Color.White.copy(alpha = 0.92f),
                size = 22.dp,
                weight = FontWeight.SemiBold,
            )
            Text(
                stringResource(R.string.queue_autoplay),
                modifier = Modifier.padding(start = 4.dp),
                color = Color.White.copy(alpha = 0.92f),
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Text(
            stringResource(R.string.queue_autoplay_description),
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 13.sp,
        )
    }
}

private fun Modifier.queueViewportFade(
    viewportCoordinates: LayoutCoordinates?,
    listState: LazyListState,
    dragFrame: () -> Int,
    additive: Boolean = false,
): Modifier = composed {
    var contentCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    graphicsLayer {
        blendMode = if (additive) BlendMode.Plus else BlendMode.SrcOver
        compositingStrategy = CompositingStrategy.Offscreen
    }
        .onGloballyPositioned { contentCoordinates = it }
        .drawWithContent {
            val layout = listState.layoutInfo
            dragFrame()
            drawContent()
            val viewport = viewportCoordinates
            val content = contentCoordinates
            if (viewport?.isAttached == true && content?.isAttached == true) {
                val height = (layout.viewportEndOffset - layout.viewportStartOffset).toFloat()
                if (height > 0f) {
                    val top = viewport.localPositionOf(content, Offset.Zero).y
                    val topFade = if (listState.canScrollBackward) (8.dp.toPx() / height).coerceIn(0f, 0.5f) else 0f
                    val bottomFade = if (listState.canScrollForward) (60.dp.toPx() / height).coerceIn(0f, 0.5f) else 0f
                    drawRect(
                        brush = Brush.verticalGradient(
                            0f to if (topFade > 0f) Color.Transparent else Color.Black,
                            topFade to Color.Black,
                            1f - bottomFade to Color.Black,
                            1f to if (bottomFade > 0f) Color.Transparent else Color.Black,
                            startY = -top,
                            endY = height - top,
                        ),
                        blendMode = BlendMode.DstIn,
                    )
                }
            }
        }
}

@Composable
private fun QueueModeButton(
    systemName: String,
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .height(36.dp)
            .playerAdditiveBlend()
            .clip(Capsule())
            .background(Color.White.copy(alpha = if (selected) 0.45f else 0.1f))
            .semantics { this.selected = selected }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        SfIcon(
            systemName = systemName,
            contentDescription = label,
            tint = if (selected) Color.White else Color.White.copy(alpha = if (enabled) 0.8f else 0.3f),
            size = 22.dp,
            weight = FontWeight.Medium,
            modifier = if (selected) Modifier.graphicsLayer {
                blendMode = BlendMode.DstOut
                compositingStrategy = CompositingStrategy.Offscreen
            } else Modifier,
        )
    }
}
