package com.ljyh.mei.ui.component.player

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.offset
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.ColumnScope
import com.ljyh.mei.ui.local.LocalPlayerConnection
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.LayoutCoordinates
import com.ljyh.mei.ui.component.sheet.LocalPlayerSheet
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.metadata
import com.ljyh.mei.playback.PlayerConnection
import kotlinx.coroutines.async
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.roundToInt

internal fun miniPlayerSwipeDirection(offset: Float, width: Int, velocity: Float): Int = when {
    width <= 0 -> 0
    abs(velocity) >= 500f -> if (velocity < 0f) 1 else -1
    abs(offset) >= width / 2f -> if (offset < 0f) 1 else -1
    else -> 0
}

/** A normalized swipe is shared by the full-player cover and all metadata viewports. */
internal class PlayerTrackSwipeState(private val scope: kotlinx.coroutines.CoroutineScope) {
    var fraction by mutableFloatStateOf(0f)
        private set
    var captured by mutableStateOf<List<MediaMetadata?>?>(null)
        private set
    private var capturedIndex = -1
    private var settling: Job? = null

    fun begin(connection: PlayerConnection, metadata: MediaMetadata) {
        settling?.cancel()
        fraction = 0f
        val player = connection.player
        capturedIndex = player.currentMediaItemIndex
        fun item(index: Int): MediaMetadata? {
            if (index == capturedIndex || index !in 0 until player.mediaItemCount) return null
            return player.getMediaItemAt(index).metadata
        }
        captured = listOf(item(player.previousMediaItemIndex), metadata, item(player.nextMediaItemIndex))
    }

    fun drag(fraction: Float) { this.fraction = fraction.coerceIn(-1f, 1f) }

    fun settle(connection: PlayerConnection, direction: Int, onConfirmed: () -> Unit = {}) {
        val target = captured?.getOrNull(direction + 1)
        val player = connection.player
        val previousIndex = player.currentMediaItemIndex
        val targetIndex = if (direction > 0) player.nextMediaItemIndex else player.previousMediaItemIndex
        val commit = direction != 0 && target != null && previousIndex == capturedIndex &&
            targetIndex in 0 until player.mediaItemCount && player.getMediaItemAt(targetIndex).metadata?.trackRef == target.trackRef
        if (commit) {
            if (direction > 0) connection.seekToNext() else {
                player.seekToPreviousMediaItem()
                player.playWhenReady = true
            }
        }
        settling = scope.launch {
            val confirmation = if (commit) async {
                val switched = withTimeoutOrNull(750) { connection.currentMediaItemIndex.first { it != previousIndex } }
                if (switched != null) onConfirmed()
            } else null
            animate(fraction, if (commit) -direction.toFloat() else 0f, animationSpec = tween(180)) { value, _ ->
                fraction = value
            }
            confirmation?.await()
            fraction = 0f
            captured = null
        }
    }

    fun dispose() { settling?.cancel() }
}

/** Page only metadata inside the existing marquee viewport. */
@Composable
internal fun MiniPlayerTrackSwipe(
    connection: PlayerConnection,
    metadata: MediaMetadata,
    modifier: Modifier = Modifier,
    edgeInset: Dp = 8.dp,
    consumeMovement: Boolean = false,
    enabled: Boolean = true,
    pageWidth: Int = 0,
    content: @Composable (MediaMetadata, Boolean) -> Unit,
) {
    var width by remember { mutableIntStateOf(0) }
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val sheet = LocalPlayerSheet.current
    DisposableEffect(sheet) {
        onDispose { sheet?.metadataSwipeActive = false }
    }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val currentContent by rememberUpdatedState(content)
    val currentMetadata by rememberUpdatedState(metadata)
    val localMotion = remember(scope) { PlayerTrackSwipeState(scope) }
    val motion = if (consumeMovement) sheet?.trackSwipe ?: localMotion else localMotion
    val swipeWidth = pageWidth.takeIf { it > 0 } ?: width
    val offset = motion.fraction * swipeWidth
    val captured = motion.captured
    DisposableEffect(localMotion) { onDispose { localMotion.dispose() } }
    fun settle(direction: Int, velocitySwitch: Boolean = false) {
        motion.settle(connection, direction) {
            if (velocitySwitch) haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
        }
    }
    Box(modifier.layout { measurable, constraints ->
        // The marquee already extends its leading fade 8dp toward the artwork.
        // Keep that physical viewport while retaining the original text baseline.
        val inset = edgeInset.roundToPx()
        val placeable = measurable.measure(constraints.offset(horizontal = inset))
        layout(constraints.constrainWidth(placeable.width - inset), placeable.height) {
            placeable.placeRelative(-inset, 0)
        }
    }.graphicsLayer {
        clip = true
        compositingStrategy = if (captured != null) CompositingStrategy.Offscreen else CompositingStrategy.Auto
    }.drawWithCache {
        val edge = (edgeInset.toPx() / size.width.coerceAtLeast(1f)).coerceAtMost(0.5f)
        val mask = Brush.horizontalGradient(
            0f to Color.Transparent, edge to Color.Black,
            1f - edge to Color.Black, 1f to Color.Transparent,
        )
        onDrawWithContent {
            drawContent()
            if (captured != null && edge > 0f) drawRect(mask, blendMode = BlendMode.DstIn)
        }
    }.onSizeChanged { width = it.width }
        .onGloballyPositioned { coordinates = it }
        .pointerInput(connection, sheet, edgeInset, consumeMovement, enabled, swipeWidth) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                if (!enabled) return@awaitEachGesture
                fun rootPosition(position: Offset) = coordinates?.takeIf { it.isAttached }
                    ?.localToRoot(position) ?: position
                val origin = rootPosition(down.position)
                val tracker = VelocityTracker()
                tracker.addPosition(down.uptimeMillis, origin)
                var horizontal = false
                var finished = false
                var thresholdReached = false
                try {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        val position = rootPosition(change.position)
                        tracker.addPosition(change.uptimeMillis, position)
                        if (!change.pressed) {
                            if (horizontal) {
                                // Consume release only. Native glass keeps observing movement,
                                // while its click must not expand the player after a swipe.
                                change.consume()
                                val velocity = tracker.calculateVelocity().x
                                settle(miniPlayerSwipeDirection(motion.fraction * swipeWidth, swipeWidth, velocity), abs(velocity) >= 500f)
                                finished = true
                            }
                            break
                        }
                        if (change.isConsumed) break
                        val distance = position - origin
                        if (!horizontal) {
                            if (abs(distance.y) > viewConfiguration.touchSlop && abs(distance.y) >= abs(distance.x)) break
                            if (abs(distance.x) <= viewConfiguration.touchSlop) continue
                            horizontal = true
                            sheet?.metadataSwipeActive = true
                            motion.begin(connection, currentMetadata)
                        }
                        if (consumeMovement) change.consume()
                        motion.drag(distance.x / swipeWidth.coerceAtLeast(1))
                        val direction = if (motion.fraction < 0f) 1 else -1
                        val reached = width > 0 && abs(motion.fraction) >= 0.5f && motion.captured?.getOrNull(direction + 1) != null
                        if (reached && !thresholdReached) {
                            haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                        }
                        thresholdReached = reached
                    }
                } finally {
                    sheet?.metadataSwipeActive = false
                    if (horizontal && !finished) settle(0)
                }
            }
        }) {
        val items = captured
        if (items == null) {
            Column(Modifier.fillMaxWidth().padding(start = edgeInset)) { currentContent(metadata, true) }
        } else {
            // The center page owns measurement; neighbors share exactly its width.
            items.forEachIndexed { index, item ->
                if (item != null) Column((if (index == 1) Modifier.fillMaxWidth() else Modifier.matchParentSize()).align(Alignment.CenterStart).absoluteOffset { IntOffset((offset + (index - 1) * swipeWidth).roundToInt(), 0) }.padding(start = edgeInset)) {
                    currentContent(item, false)
                }
            }
        }
    }
}

/** Retain the full-player text viewport while sharing metadata paging behavior. */
@Composable
internal fun PlayerMetadataTrackSwipe(
    modifier: Modifier = Modifier,
    edgeInset: Dp = 0.dp,
    enabled: Boolean = true,
    pageWidth: Int = 0,
    content: @Composable ColumnScope.(MediaMetadata?, Boolean) -> Unit,
) {
    val connection = LocalPlayerConnection.current
    val density = LocalDensity.current
    val physicalPageWidth = if (pageWidth > 0) pageWidth + with(density) { edgeInset.roundToPx() } else 0
    val metadata = connection?.mediaMetadata?.collectAsState()?.value
    if (connection == null || metadata == null) {
        Column(modifier) { content(null, true) }
    } else {
        MiniPlayerTrackSwipe(connection, metadata, modifier, edgeInset = edgeInset, consumeMovement = true, enabled = enabled, pageWidth = physicalPageWidth) { track, marquee ->
            Column(Modifier.fillMaxWidth()) { content(track, marquee) }
        }
    }
}
