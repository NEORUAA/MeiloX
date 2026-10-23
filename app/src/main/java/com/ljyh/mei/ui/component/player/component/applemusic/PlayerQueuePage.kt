package com.ljyh.mei.ui.component.player.component.applemusic

import android.widget.Toast
import androidx.annotation.OptIn
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.Indication
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.LayerOutsets
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.Remeasurement
import androidx.compose.ui.layout.RemeasurementModifier
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
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
import com.ljyh.mei.ui.glass.IosMenuItem
import com.ljyh.mei.ui.glass.IosPopupMenu
import com.ljyh.mei.ui.glass.SfIcon
import com.ljyh.mei.utils.rememberPreference
import com.ljyh.mei.utils.smallImage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

private val PlayerQueueHistoryGap = 16.dp
private val PlayerQueueCoverHeight = AppleMusicDimensions.headerSize + 16.dp
internal const val PlayerQueueCurrentHeaderKey = "queue_current_song"

// Fit to the reference recording's row travel, including its gradual settling tail.
internal val PlayerQueueRowAnimationSpec = tween<Float>(
    durationMillis = 400,
    easing = CubicBezierEasing(0.34f, 0.52f, 0.07f, 1f),
)

internal suspend fun animatePlayerQueueRows(
    progress: Animatable<Float, AnimationVector1D>,
    listState: LazyListState,
    headerReturnDistance: Float = 0f,
    isHeaderReturnInterrupted: () -> Boolean = { false },
) {
    var returned = 0f
    var interrupted = false
    progress.animateTo(1f, PlayerQueueRowAnimationSpec) {
        interrupted = interrupted || isHeaderReturnInterrupted()
        if (interrupted) return@animateTo
        val target = headerReturnDistance * value
        if (target != returned) listState.dispatchRawDelta(target - returned)
        returned = target
    }
}

internal data class QueueRowPlacement(val top: Float, val opacity: Float = 1f)

/** Rows entering/leaving a section fade in place; survivors alone change slots. */
internal fun queueRowPlacement(
    before: QueueRowPlacement?,
    after: QueueRowPlacement?,
    progress: Float,
): QueueRowPlacement {
    require(before != null || after != null)
    val start = before ?: after!!.copy(opacity = 0f)
    val end = after ?: before!!.copy(opacity = 0f)
    val fraction = progress.coerceIn(0f, 1f)
    return QueueRowPlacement(
        start.top + (end.top - start.top) * fraction,
        start.opacity + (end.opacity - start.opacity) * fraction,
    )
}

data class PlayerQueueHeaderPosition(val top: Float, val viewportHeight: Float)

@Composable
internal fun rememberPlayerQueueHeaderPosition(
    listState: LazyListState,
    headerKey: String?,
    headerIndex: Int,
    headerHeight: Float,
    visualOffset: () -> Float = { 0f },
): State<PlayerQueueHeaderPosition> {
    val currentKey = rememberUpdatedState(headerKey)
    val currentIndex = rememberUpdatedState(headerIndex)
    val currentHeight = rememberUpdatedState(headerHeight)
    val currentVisualOffset = rememberUpdatedState(visualOffset)
    // A resident artwork observer must survive songs crossing the current slot.
    return remember(listState) {
        derivedStateOf {
            val layout = listState.layoutInfo
            val height = (layout.viewportEndOffset - layout.viewportStartOffset)
                .toFloat().coerceAtLeast(0f)
            val header = layout.visibleItemsInfo.firstOrNull { it.key == currentKey.value }
            val top = header?.offset?.toFloat() ?: when {
                currentKey.value == null -> 0f
                listState.firstVisibleItemIndex > currentIndex.value -> -currentHeight.value
                else -> height
            }
            PlayerQueueHeaderPosition(top + currentVisualOffset.value(), height)
        }
    }
}

internal class PlayerQueueHeaderAnchor(private val listState: LazyListState) : RemeasurementModifier {
    private var currentKey: String? = null
    private var previousHistoryTailKey: String? = null
    private var previousHistoryGap = 0f
    private var beforeLayout: LazyListLayoutInfo? = null
    private var remeasurement: Remeasurement? = null
    private var top = 0f
    private var expectedContentRevision = 0
    var isPending by mutableStateOf(false)
        private set

    override fun onRemeasurementAvailable(remeasurement: Remeasurement) {
        this.remeasurement = remeasurement
    }

    fun update(
        key: String?, enabled: Boolean, historyTailKey: String?, historyGap: Float,
        headerTop: Float? = null, layoutChanged: Boolean = false,
        contentRevision: Int = 0,
    ) {
        if (!enabled || key == null) {
            isPending = false
            beforeLayout = null
        } else if (currentKey != null && (currentKey != key || layoutChanged)) {
            val layout = listState.layoutInfo
            if (!isPending) {
                val header = layout.visibleItemsInfo.firstOrNull { it.key == PlayerQueueCurrentHeaderKey }
                val historyTail = layout.visibleItemsInfo.firstOrNull { it.key == previousHistoryTailKey }
                val headerAtViewport = header != null && header.offset <= layout.viewportEndOffset &&
                    header.offset + header.size >= layout.viewportStartOffset
                val historyTailVisible = historyTail != null && historyTail.offset < layout.viewportEndOffset &&
                    historyTail.offset + historyTail.size > layout.viewportStartOffset
                // Keep the history endpoint below the viewport when its last song leaves.
                isPending = headerTop != null || headerAtViewport || historyTailVisible
                beforeLayout = if (isPending) layout else null
                top = headerTop ?: header?.offset?.toFloat()
                    ?: historyTail?.let { it.offset + it.size + previousHistoryGap } ?: 0f
            }
            expectedContentRevision = contentRevision
        }
        currentKey = key
        previousHistoryTailKey = historyTailKey
        previousHistoryGap = historyGap
    }

    fun onScroll(consumed: Float) {
        if (isPending) top += consumed
    }

    fun restore(contentRevision: Int = 0, headerTop: (LazyListLayoutInfo) -> Float?) {
        if (!isPending || contentRevision != expectedContentRevision) return
        val layout = listState.layoutInfo
        if (layout === beforeLayout) return
        val measuredTop = headerTop(layout)
        isPending = false
        beforeLayout = null
        if (measuredTop != null && abs(measuredTop - top) >= 0.5f) {
            // Correct only the data mutation; do not cancel the user's scroll mutation.
            listState.dispatchRawDelta(measuredTop - top)
            // Raw scrolling can invalidate placement only; finish it before this frame draws.
            remeasurement?.forceRemeasure()
        }
    }
}

@Composable
internal fun rememberPlayerQueueHeaderAnchor(
    listState: LazyListState,
    currentKey: String?,
    enabled: Boolean,
    historyTailKey: String? = null,
    historyGap: Float = 0f,
    contentRevision: Int = 0,
): PlayerQueueHeaderAnchor {
    val anchor = remember(listState) { PlayerQueueHeaderAnchor(listState) }
    SideEffect { anchor.update(currentKey, enabled, historyTailKey, historyGap, contentRevision = contentRevision) }
    return anchor
}

internal fun playerQueueTailHeight(
    viewportHeight: Float,
    contentHeight: Float,
    rowHeight: Float,
    bottomContentPadding: Float,
    coverHeight: Float = 0f,
): Float {
    if (contentHeight - coverHeight + rowHeight >= viewportHeight) return rowHeight
    val minimumTail = maxOf(rowHeight, bottomContentPadding)
    val hasScrollableUpcoming = contentHeight + minimumTail > viewportHeight
    val targetHeight = viewportHeight + if (hasScrollableUpcoming) coverHeight else 0f
    return maxOf(minimumTail, targetHeight - contentHeight).coerceAtLeast(0f)
}

internal fun playerQueueHistoryPadding(
    viewportHeight: Float,
    headingHeight: Float,
    rowsHeight: Float,
    gapHeight: Float,
): Float = (viewportHeight - headingHeight - rowsHeight - gapHeight).coerceAtLeast(0f)

internal fun playerQueueSnapTarget(
    coverTop: Float,
    coverHeight: Float,
    viewportHeight: Float,
    scrollDirection: Int,
    hasHistory: Boolean,
    canPinUpcoming: Boolean,
): Float? {
    if (viewportHeight <= 0f || coverTop !in -coverHeight..viewportHeight) return null
    if (coverTop == 0f) return 0f
    if (coverTop == viewportHeight && hasHistory) return viewportHeight
    if (coverTop == -coverHeight && canPinUpcoming) return -coverHeight
    if (coverTop > 0f) {
        val historyRevealThreshold = viewportHeight * 0.25f
        return if (hasHistory && ((scrollDirection < 0 && coverTop >= historyRevealThreshold) ||
                (scrollDirection == 0 && coverTop >= viewportHeight / 2f))) viewportHeight else 0f
    }
    return if (canPinUpcoming && (scrollDirection > 0 ||
            (scrollDirection == 0 && coverTop <= -coverHeight / 2f))) -coverHeight else 0f
}

@Composable
internal fun PlayerQueueSnapEffect(
    listState: LazyListState,
    active: Boolean,
    canSnap: () -> Boolean,
    isReordering: () -> Boolean,
    coverTop: () -> Float,
    snapTarget: (top: Float, travel: Float) -> Float?,
) {
    val currentCanSnap by rememberUpdatedState(canSnap)
    val currentIsReordering by rememberUpdatedState(isReordering)
    val currentCoverTop by rememberUpdatedState(coverTop)
    val currentSnapTarget by rememberUpdatedState(snapTarget)
    // Track changes must not restart gesture observation or discard a deferred snap.
    LaunchedEffect(listState, active) {
        if (!active) return@LaunchedEffect
        var snapJob: Job? = null
        var dragStart by mutableStateOf<DragInteraction.Start?>(null)
        var pendingSettle by mutableStateOf(false)
        var gestureRevision = 0
        var gestureStartCoverTop = 0f
        launch {
            listState.interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is DragInteraction.Start -> {
                        snapJob?.cancel()
                        gestureRevision++
                        gestureStartCoverTop = currentCoverTop()
                        dragStart = interaction
                        pendingSettle = true
                    }
                    is DragInteraction.Stop -> if (dragStart === interaction.start) dragStart = null
                    is DragInteraction.Cancel -> if (dragStart === interaction.start) {
                        dragStart = null
                        pendingSettle = false
                    }
                }
            }
        }
        snapshotFlow {
            Triple(listState.isScrollInProgress || dragStart != null, currentIsReordering(),
                currentCanSnap() && pendingSettle)
        }.collect { (scrolling, dragging, ready) ->
            if (!ready || dragging) snapJob?.cancel()
            if (dragging) {
                pendingSettle = false
                return@collect
            }
            if (!scrolling && ready && snapJob?.isActive != true) {
                val top = currentCoverTop()
                val target = currentSnapTarget(top, gestureStartCoverTop - top)
                val distance = target?.let { top - it }
                if (distance == null || abs(distance) < 0.5f) {
                    pendingSettle = false
                } else {
                    val revision = gestureRevision
                    // Playback may defer a user settle, but must never create one.
                    snapJob = launch {
                        listState.animateScrollBy(distance)
                        if (gestureRevision == revision) pendingSettle = false
                    }
                }
            }
        }
    }
}

internal fun playerQueueAutoplaySectionIndex(
    currentQueueIndex: Int,
    autoplayQueueIndex: Int,
    displayedCount: Int,
    inlineCurrent: Boolean,
): Int = when {
    autoplayQueueIndex < 0 || displayedCount == 0 -> displayedCount
    inlineCurrent -> (autoplayQueueIndex - currentQueueIndex - 1).coerceIn(0, displayedCount)
    else -> autoplayQueueIndex.coerceIn(0, displayedCount)
}

internal data class PhoneQueueEntry(
    val key: String,
    val mediaItem: MediaItem,
    val rawIndex: Int,
)

internal data class QueueRowVisual(
    val entry: PhoneQueueEntry?,
    val history: Boolean,
    val placement: QueueRowPlacement,
)

internal data class QueueRowsSnapshot(
    val entries: List<PhoneQueueEntry>,
    val currentKey: String?,
    val tops: Map<String, Float>,
    val viewportHeight: Float,
    val rowHeight: Float,
    val historyGap: Float,
    val autoplayKey: String?,
    val autoplayEnabled: Boolean,
    val autoplayHeight: Float,
) {
    fun headerTop(headerHeight: Float, controlsHeight: Float): Float? {
        val current = entries.indexOfFirst { it.key == currentKey }
        if (current < 0) return null
        tops[PlayerQueueCurrentHeaderKey]?.let { return it }
        // A single track change can push the header just outside the measured window.
        for (index in 0 until current) {
            tops[entries[index].key]?.let { return it + (current - index) * rowHeight + historyGap }
        }
        val autoplay = if (autoplayEnabled) entries.indexOfFirst { it.key == autoplayKey } else -1
        for (index in current + 1 until entries.size) {
            tops[entries[index].key]?.let {
                val divider = if (autoplay >= 0 && index >= maxOf(autoplay, current + 1)) autoplayHeight else 0f
                return it - (index - current - 1) * rowHeight - divider - headerHeight - controlsHeight
            }
        }
        // With no visible song rows, a pinned mode heading still identifies the
        // hidden upcoming endpoint (for example, after selecting the final song).
        tops["queue_upcoming_controls_bottom"]?.let { return it - headerHeight - controlsHeight }
        return null
    }

    fun rows(extraKeys: Set<String> = emptySet(), viewportExtension: Float = 0f): Map<String, QueueRowVisual> = buildMap {
        val queueEntries = this@QueueRowsSnapshot.entries
        val current = queueEntries.indexOfFirst { it.key == currentKey }
        if (current < 0) return@buildMap
        listOf(queueEntries.take(current), queueEntries.drop(current + 1)).forEachIndexed { section, rows ->
            val history = section == 0
            val prefix = if (history) "history:" else "upcoming:"
            val autoplay = if (history || !autoplayEnabled) -1 else {
                val index = queueEntries.indexOfFirst { it.key == autoplayKey }
                if (index in 0..current) 0 else rows.indexOfFirst { it.key == autoplayKey }.takeIf { it >= 0 } ?: rows.size
            }
            fun relativeTop(index: Int) = index * rowHeight + if (autoplay in 0..index) autoplayHeight else 0f
            val anchor = rows.indexOfFirst { it.key in tops }
            val base = when {
                anchor >= 0 -> tops.getValue(rows[anchor].key) - relativeTop(anchor)
                history -> tops[PlayerQueueCurrentHeaderKey]?.minus(historyGap + rows.size * rowHeight)
                else -> tops["queue_upcoming_controls_bottom"]
            } ?: return@forEachIndexed
            rows.forEachIndexed { index, entry ->
                val id = prefix + entry.key
                val top = tops[entry.key] ?: (base + relativeTop(index))
                if (id in extraKeys || top in -rowHeight..(viewportHeight + rowHeight + viewportExtension)) {
                    put(id, QueueRowVisual(entry, history, QueueRowPlacement(top)))
                }
            }
            if (autoplay >= 0) {
                val id = "upcoming:autoplay_section"
                val top = tops["autoplay_section"] ?: (base + autoplay * rowHeight)
                if (id in extraKeys || top + autoplayHeight >= -rowHeight && top <= viewportHeight + rowHeight + viewportExtension) {
                    put(id, QueueRowVisual(null, false, QueueRowPlacement(top)))
                }
            }
        }
    }
}

private class QueueRowsTransition(
    val snapshot: QueueRowsSnapshot,
    val beforeLayout: LazyListLayoutInfo,
    initial: Map<String, QueueRowVisual>,
    val returnHeaderToTop: Boolean = false,
) {
    var before by mutableStateOf(initial)
    var after by mutableStateOf<Map<String, QueueRowVisual>?>(null)
    val progress = Animatable(0f)

    fun scrollOffset(history: Boolean, layout: LazyListLayoutInfo): Float {
        val destination = after ?: return 0f
        for (item in layout.visibleItemsInfo) {
            val id = (if (history) "history:" else "upcoming:") + item.key
            destination[id]?.let { return item.offset - it.placement.top }
        }
        return 0f
    }

    fun frame(id: String, layout: LazyListLayoutInfo): QueueRowVisual {
        val start = before[id]
        val end = after?.get(id)
        val visual = end ?: start!!
        val placement = if (after == null) start!!.placement else queueRowPlacement(start?.placement, end?.placement, progress.value)
        return visual.copy(placement = placement.copy(top = placement.top + scrollOffset(visual.history, layout)))
    }

    fun frames(layout: LazyListLayoutInfo): Map<String, QueueRowVisual> =
        (before.keys + after.orEmpty().keys).associateWith { frame(it, layout) }
}

@OptIn(UnstableApi::class)
private fun Player.phoneQueueEntries(): List<PhoneQueueEntry> {
    val timeline = currentTimeline
    var fallbackWindow: Timeline.Window? = null
    return playbackOrderIndices().map { index ->
        val item = getMediaItemAt(index)
        val key = item.queueEntryId ?: run {
            val windowUid = if (index < timeline.windowCount) {
                val window = fallbackWindow ?: Timeline.Window().also { fallbackWindow = it }
                timeline.getWindow(index, window).uid
            } else null
            windowUid?.let { "window:$it" } ?: "fallback:${item.mediaId}:$index"
        }
        PhoneQueueEntry(
            key = key,
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
    indication: Indication? = LocalIndication.current,
    controlsFadeRange: (Float) -> ClosedFloatingPointRange<Float>? = { null },
    active: Boolean = true,
    selected: Boolean = active,
    animateEntryScroll: Boolean = false,
    currentHeader: (@Composable (Modifier) -> Unit)? = null,
    onHeaderPositionChanged: (State<PlayerQueueHeaderPosition>) -> Unit = {},
    bottomContentPadding: Dp = 56.dp,
) {
    val selectionPadding = 5.dp
    val inlineCurrent = currentHeader != null
    val player = playerConnection.player
    val context = LocalContext.current
    val density = LocalDensity.current
    val coverHeightPx = with(density) { PlayerQueueCoverHeight.toPx() }
    val visualCoverHeightPx = with(density) { AppleMusicDimensions.headerSize.toPx() }
    val historyGapPx = with(density) { PlayerQueueHistoryGap.toPx() }
    val rowHeightPx = with(density) { 56.dp.roundToPx().toFloat() }
    val snapDirectionThresholdPx = with(density) { 8.dp.toPx() }
    val reportHeaderPosition by rememberUpdatedState(onHeaderPositionChanged)
    val animateEntryScrollNow by rememberUpdatedState(animateEntryScroll)
    val haptics = LocalHapticFeedback.current
    val queueTitle by playerConnection.queueTitle.collectAsStateWithLifecycle()
    var currentIndex by remember(player) { mutableIntStateOf(player.currentMediaItemIndex) }
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
    val entriesSnapshot = entries.toList()
    var queueContentRevision by remember(player) { mutableIntStateOf(0) }
    val renderedContentRevision = queueContentRevision
    val entriesByKey = remember(entriesSnapshot) { entriesSnapshot.associateBy { it.key } }
    val latestEntriesByKey by rememberUpdatedState(entriesByKey)
    val listState = rememberLazyListState()
    val isListDragged by listState.interactionSource.collectIsDraggedAsState()
    var rowsTransition by remember(player) { mutableStateOf<QueueRowsTransition?>(null) }
    var emptyHistoryPaddingPx by remember(player) { mutableFloatStateOf(0f) }
    val activeNow by rememberUpdatedState(active)
    var viewportCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    var measuredHeaderHeightPx by remember { mutableIntStateOf(0) }
    var controlsHeadingHeightPx by remember { mutableIntStateOf(0) }
    var historyHeadingHeightPx by remember { mutableIntStateOf(0) }
    var autoplayHeadingHeightPx by remember { mutableIntStateOf(0) }
    var dragFrame by remember { mutableIntStateOf(0) }
    var initialScrollPending by remember(player) { mutableStateOf(true) }
    var wasQueueSelected by remember(player) { mutableStateOf(false) }
    var automaticScrollInProgress by remember { mutableStateOf(false) }
    var pendingHeaderAlignmentKey by remember(player) { mutableStateOf<String?>(null) }
    var pendingHeaderAlignmentAnimated by remember(player) { mutableStateOf(false) }
    var menuEntryKey by remember { mutableStateOf<String?>(null) }
    var menuAnchorBounds by remember { mutableStateOf<Rect?>(null) }
    var menuExpanded by remember { mutableStateOf(false) }
    var menuRemovalRequested by remember { mutableStateOf(false) }
    val currentQueueIndex = remember(entriesSnapshot, currentIndex) {
        entriesSnapshot.indexOfFirst { it.rawIndex == currentIndex }
    }
    val autoplayQueueIndex = remember(entriesSnapshot, autoplayStartEntryId) {
        entriesSnapshot.indexOfFirst { it.key == autoplayStartEntryId }
    }
    val currentEntry = entriesSnapshot.getOrNull(currentQueueIndex)
    val headerAnchor = rememberPlayerQueueHeaderAnchor(
        listState, currentEntry?.key, active && inlineCurrent && !initialScrollPending,
        historyTailKey = entriesSnapshot.getOrNull(currentQueueIndex - 1)?.key,
        historyGap = historyGapPx,
        contentRevision = renderedContentRevision,
    )
    // User input owns a real top-edge pull even while entry alignment is pending.
    // Automatic scroll deltas are excluded by the nested-scroll source instead.
    val pullEnabled = active && inlineCurrent && currentQueueIndex == 0 && emptyHistoryPaddingPx <= 0f &&
        rowsTransition == null
    val pullEnabledNow = rememberUpdatedState(pullEnabled)
    val onUserScrollNow = rememberUpdatedState {
        if (active && inlineCurrent) {
            pendingHeaderAlignmentKey = null
            initialScrollPending = false
        }
    }
    val pullScope = rememberCoroutineScope()
    val pullLimit = with(density) { 24.dp.toPx() }
    val topPull = remember(pullScope, pullLimit) {
        PlayerQueueTopOverscroll(
            pullScope, pullLimit,
            isAtTop = {
                // Zero-height history slots can leave canScrollBackward true at the physical start.
                listState.layoutInfo.visibleItemsInfo.any { it.key == PlayerQueueCurrentHeaderKey && it.offset == 0 }
            },
            onUserInput = { onUserScrollNow.value() },
            onListScroll = headerAnchor::onScroll,
        ) {
            pullEnabledNow.value
        }
    }
    LaunchedEffect(pullEnabled, isListDragged, active, topPull) {
        if (!pullEnabled && (!isListDragged || !active)) topPull.release()
    }
    val historyEntries = remember(entriesSnapshot, currentQueueIndex) {
        entriesSnapshot.subList(0, currentQueueIndex.coerceAtLeast(0))
    }
    val displayedEntries = remember(entriesSnapshot, currentQueueIndex, inlineCurrent) {
        if (inlineCurrent) entriesSnapshot.subList((currentQueueIndex + 1).coerceAtLeast(0), entriesSnapshot.size)
        else entriesSnapshot
    }
    val autoplaySectionIndex = if (autoplayEnabled) {
        playerQueueAutoplaySectionIndex(currentQueueIndex, autoplayQueueIndex, displayedEntries.size, inlineCurrent)
    } else displayedEntries.size
    // Heading and filler precede history rows; the gap and zero-height sticky boundary precede the cover.
    val showHistorySection = historyEntries.isNotEmpty() || emptyHistoryPaddingPx > 0f
    val currentHeaderIndex = historyEntries.size + if (showHistorySection) 4 else 2
    val historyPaddingPx = ceil(playerQueueHistoryPadding(
        viewportHeight = viewportSize.height.toFloat(),
        headingHeight = historyHeadingHeightPx.toFloat(),
        rowsHeight = historyEntries.size * rowHeightPx,
        gapHeight = historyGapPx,
    ).toDouble()).toInt()
    val inlineHeaderHeightPx = if (currentEntry != null) {
        measuredHeaderHeightPx.takeIf { it > 0 }?.toFloat() ?: coverHeightPx
    } else 0f
    val inlineContentHeightPx = inlineHeaderHeightPx + controlsHeadingHeightPx +
        displayedEntries.size * rowHeightPx + (if (autoplayEnabled) autoplayHeadingHeightPx.toFloat() else 0f)
    val tailHeightPx = if (inlineCurrent) {
        ceil(playerQueueTailHeight(
            viewportHeight = viewportSize.height.toFloat(),
            contentHeight = inlineContentHeightPx,
            rowHeight = rowHeightPx,
            bottomContentPadding = with(density) { bottomContentPadding.toPx() },
            coverHeight = inlineHeaderHeightPx,
        ).toDouble()).toInt()
    } else with(density) { 8.dp.roundToPx() }
    val initialLayoutReady = viewportSize.height > 0 && measuredHeaderHeightPx > 0 &&
        (controlsHeadingHeightPx > 0 || measuredHeaderHeightPx >= viewportSize.height)

    fun captureRows(model: List<PhoneQueueEntry>, playingKey: String?, layout: LazyListLayoutInfo): QueueRowsSnapshot {
        val tops = layout.visibleItemsInfo.associate { it.key.toString() to it.offset.toFloat() }.toMutableMap()
        layout.visibleItemsInfo.firstOrNull { it.key == "queue_upcoming_controls" }?.let {
            tops["queue_upcoming_controls_bottom"] = (it.offset + it.size).toFloat()
        }
        val snapshot = QueueRowsSnapshot(
            model, playingKey, tops, viewportSize.height.toFloat(), rowHeightPx, historyGapPx,
            autoplayStartEntryId, autoplayEnabled,
            layout.visibleItemsInfo.firstOrNull { it.key == "autoplay_section" }?.size?.toFloat()
                ?: autoplayHeadingHeightPx.toFloat(),
        )
        val headerHeight = measuredHeaderHeightPx.takeIf { it > 0 }?.toFloat() ?: coverHeightPx
        val headerTop = snapshot.headerTop(headerHeight, controlsHeadingHeightPx.toFloat())
            ?: if (model.indexOfFirst { it.key == playingKey } == 0 && emptyHistoryPaddingPx > 0f) {
                layout.visibleItemsInfo.firstOrNull { it.key == "queue_history_padding" }
                    ?.let { (it.offset + it.size).toFloat() }
            } else null
        headerTop?.let { top ->
            tops.putIfAbsent(PlayerQueueCurrentHeaderKey, top)
            tops.putIfAbsent("queue_upcoming_controls_bottom", top + headerHeight + controlsHeadingHeightPx)
        }
        return snapshot
    }

    LaunchedEffect(rowsTransition, active, inlineCurrent, viewportSize) {
        val motion = rowsTransition ?: return@LaunchedEffect
        if (active && inlineCurrent && motion.snapshot.viewportHeight == viewportSize.height.toFloat()) {
            // Await the real queue layout. The row overlay holds the previous visual
            // frame while the unchanged viewport measures the new queue snapshot.
            val layout = snapshotFlow { listState.layoutInfo to headerAnchor.isPending }
                .first { (layout, pending) ->
                    if (layout === motion.beforeLayout || pending) return@first false
                    if (!motion.returnHeaderToTop) return@first true
                    val header = layout.visibleItemsInfo.firstOrNull { it.key == PlayerQueueCurrentHeaderKey }
                    val padding = layout.visibleItemsInfo.firstOrNull { it.key == "queue_history_padding" }
                    val retainedPadding = (emptyHistoryPaddingPx - historyHeadingHeightPx)
                        .coerceAtLeast(0f).roundToInt()
                    // A metadata-only layout is not the new empty-history geometry.
                    padding?.size == retainedPadding ||
                        (header?.index == currentHeaderIndex && header.offset <= 0) ||
                        listState.firstVisibleItemIndex > currentHeaderIndex
                }.first
            val target = captureRows(entries.toList(), currentEntry?.key, layout)
            val targetHeaderTop = target.headerTop(inlineHeaderHeightPx, controlsHeadingHeightPx.toFloat())
                ?: motion.snapshot.headerTop(inlineHeaderHeightPx, controlsHeadingHeightPx.toFloat())
            val returnDistance = if (motion.returnHeaderToTop) targetHeaderTop?.coerceAtLeast(0f) ?: 0f else 0f
            val destinationSnapshot = if (motion.returnHeaderToTop && targetHeaderTop != null) target.copy(
                tops = target.tops + mapOf(
                    PlayerQueueCurrentHeaderKey to targetHeaderTop,
                    "queue_upcoming_controls_bottom" to (targetHeaderTop + inlineHeaderHeightPx + controlsHeadingHeightPx),
                ),
            ) else target
            val destination = destinationSnapshot.rows(motion.before.keys, viewportExtension = returnDistance)
            motion.before = motion.snapshot.rows(destination.keys) + motion.before
            motion.after = destination
            animatePlayerQueueRows(motion.progress, listState, returnDistance) { isListDragged }
        }
        if (rowsTransition === motion) rowsTransition = null
    }

    LaunchedEffect(currentEntry?.key, historyEntries.isEmpty(), rowsTransition, emptyHistoryPaddingPx) {
        if (historyEntries.isNotEmpty() || emptyHistoryPaddingPx <= 0f || rowsTransition != null) return@LaunchedEffect
        // Retain the empty-history space until the normal return or user scroll passes it.
        snapshotFlow {
            val header = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == PlayerQueueCurrentHeaderKey }
            !listState.isScrollInProgress &&
                (header?.let { it.offset <= 0 } ?: (listState.firstVisibleItemIndex > currentHeaderIndex))
        }.first { it }
        emptyHistoryPaddingPx = 0f
    }

    val liveRows = Modifier.graphicsLayer { alpha = if (rowsTransition == null) 1f else 0f }

    LaunchedEffect(active) {
        if (!active) menuExpanded = false
    }

    LaunchedEffect(playerConnection, active, inlineCurrent, currentEntry?.key) {
        if (!active) return@LaunchedEffect
        snapshotFlow {
            val byKey = latestEntriesByKey
            listState.layoutInfo.visibleItemsInfo.mapNotNull { visible ->
                val key = visible.key as? String ?: return@mapNotNull null
                (if (inlineCurrent && key == PlayerQueueCurrentHeaderKey) currentEntry else byKey[key])
                    ?.mediaItem?.queueEntryId
            }
        }.collect { playerConnection.service.queueManager.loadMetadataForQueueEntries(it) }
    }

    LaunchedEffect(currentEntry?.key, currentHeaderIndex, active, selected, inlineCurrent, autoplayEnabled, autoplayQueueIndex) {
        val reopening = inlineCurrent && selected && !wasQueueSelected
        val closing = inlineCurrent && !selected && wasQueueSelected
        wasQueueSelected = selected
        if (closing) {
            pendingHeaderAlignmentKey = null
            initialScrollPending = false
        }
        val key = currentEntry?.key ?: return@LaunchedEffect
        if (reopening || initialScrollPending) {
            val initialIndex = if (inlineCurrent) {
                currentHeaderIndex
            } else {
                val nextIndex = (currentQueueIndex + 1).coerceAtMost(entries.lastIndex)
                nextIndex + if (autoplayEnabled && autoplayQueueIndex in 0..nextIndex) 1 else 0
            }
            val animateAlignment = reopening && animateEntryScrollNow
            if (inlineCurrent) {
                pendingHeaderAlignmentAnimated = animateAlignment
                pendingHeaderAlignmentKey = key
            }
            automaticScrollInProgress = true
            try {
                if (animateAlignment) {
                    listState.animateScrollToItem(initialIndex)
                } else {
                    listState.scrollToItem(initialIndex)
                }
            } finally {
                automaticScrollInProgress = false
            }
            // Inline queues retry once their measured tail can support the requested position.
            if (active && !inlineCurrent) initialScrollPending = false
        }
    }

    LaunchedEffect(
        pendingHeaderAlignmentKey, pendingHeaderAlignmentAnimated, currentEntry?.key, currentHeaderIndex, inlineCurrent,
        initialLayoutReady, viewportSize.height, inlineContentHeightPx, tailHeightPx, active, selected,
    ) {
        val key = pendingHeaderAlignmentKey ?: return@LaunchedEffect
        if (!inlineCurrent || !initialLayoutReady || key != currentEntry?.key ||
            (!selected && !initialScrollPending)
        ) return@LaunchedEffect
        snapshotFlow {
            val layout = listState.layoutInfo
            val viewportHeight = layout.viewportEndOffset - layout.viewportStartOffset
            val tailApplied = inlineContentHeightPx >= viewportHeight ||
                layout.visibleItemsInfo.any { it.key == "queue_bottom_space" && it.size == tailHeightPx }
            viewportHeight > 0 && viewportHeight == viewportSize.height &&
                tailApplied && !automaticScrollInProgress
        }.first { it }
        if (pendingHeaderAlignmentKey != key) return@LaunchedEffect
        if (!listState.isScrollInProgress) {
            val header = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == PlayerQueueCurrentHeaderKey }
            if (header?.offset != 0) {
                automaticScrollInProgress = true
                try {
                    if (pendingHeaderAlignmentAnimated) {
                        listState.animateScrollToItem(currentHeaderIndex)
                    } else {
                        listState.scrollToItem(currentHeaderIndex)
                    }
                } finally {
                    automaticScrollInProgress = false
                }
            }
        }
        pendingHeaderAlignmentKey = null
        // Prewarming follows playback until the first visit, without re-centering after gestures.
        if (active) initialScrollPending = false
    }

    val headerPosition = rememberPlayerQueueHeaderPosition(
        listState, currentEntry?.let { PlayerQueueCurrentHeaderKey }, currentHeaderIndex, visualCoverHeightPx,
        visualOffset = { topPull.offset },
    )
    SideEffect {
        // Publish the observable layout, not an asynchronously copied coordinate.
        if (inlineCurrent) reportHeaderPosition(headerPosition)
    }

    LaunchedEffect(listState, inlineCurrent, topPull) {
        if (!inlineCurrent) return@LaunchedEffect
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) {
                pendingHeaderAlignmentKey = null
                initialScrollPending = false
            } else if (interaction is DragInteraction.Cancel) {
                launch { topPull.release() }
            }
        }
    }

    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        val currentEntries = player.phoneQueueEntries().toMutableList()
        val fromIndex = currentEntries.indexOfFirst { it.key == from.key }
        val toIndex = currentEntries.indexOfFirst { it.key == to.key }
        val playingIndex = currentEntries.indexOfFirst { it.rawIndex == player.currentMediaItemIndex }
        val allowedSection = !inlineCurrent ||
            (playingIndex >= 0 && fromIndex > playingIndex && toIndex > playingIndex)
        if (fromIndex >= 0 && toIndex >= 0 && fromIndex != toIndex && allowedSection) {
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

    DisposableEffect(player, inlineCurrent) {
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (events.containsAny(
                        Player.EVENT_TIMELINE_CHANGED,
                        Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
                        Player.EVENT_MEDIA_ITEM_TRANSITION,
                        Player.EVENT_MEDIA_METADATA_CHANGED,
                    )
                ) {
                    val nextEntries = player.phoneQueueEntries()
                    val previous = entries.firstOrNull { it.rawIndex == currentIndex }
                    val next = nextEntries.firstOrNull { it.rawIndex == player.currentMediaItemIndex }
                    val nextQueueIndex = nextEntries.indexOfFirst { it.key == next?.key }
                    val previousQueueIndex = entries.indexOfFirst { it.key == previous?.key }
                    val historyEmptied = previousQueueIndex > 0 && nextQueueIndex == 0
                    val changed = previous?.key != next?.key
                    val changedOrder = entries.map { it.key } != nextEntries.map { it.key }
                    val layout = listState.layoutInfo
                    val snapshot = captureRows(entries.toList(), previous?.key, layout)
                    val headerTop = snapshot.headerTop(inlineHeaderHeightPx, controlsHeadingHeightPx.toFloat())
                    val nextContentRevision = queueContentRevision + if (changed || changedOrder) 1 else 0
                    val oldHeaderIndex = previousQueueIndex + if (previousQueueIndex > 0 || emptyHistoryPaddingPx > 0f) 4 else 2
                    val anchorTop = if (changed && previous != null && next != null) {
                        // Keep a distant header at its existing hidden edge, not at
                        // the viewport position of the newly selected song row.
                        (headerTop ?: if (listState.firstVisibleItemIndex > oldHeaderIndex) {
                            -inlineHeaderHeightPx
                        } else viewportSize.height.toFloat()).coerceIn(-inlineHeaderHeightPx, viewportSize.height.toFloat())
                    } else headerTop?.takeIf { it in -inlineHeaderHeightPx..viewportSize.height.toFloat() }
                    // Capture before publishing any new lazy-item mapping. A composition
                    // side effect can otherwise observe an already remeasured queue.
                    headerAnchor.update(
                        next?.key, inlineCurrent && activeNow && !initialScrollPending,
                        nextEntries.getOrNull(nextQueueIndex - 1)?.key, historyGapPx,
                        headerTop = anchorTop,
                        layoutChanged = changedOrder && !reorderState.isAnyItemDragging,
                        contentRevision = nextContentRevision,
                    )
                    if (inlineCurrent && activeNow && viewportSize.height > 0 && !reorderState.isAnyItemDragging &&
                        previous != null && next != null &&
                        (changed || historyEmptied || rowsTransition != null && changedOrder)
                    ) {
                        val before = rowsTransition?.frames(layout) ?: snapshot.rows()
                        if (historyEmptied) {
                            val rowsHeight = previousQueueIndex * rowHeightPx
                            val headingHeight = historyHeadingHeightPx.toFloat()
                            emptyHistoryPaddingPx = headingHeight + rowsHeight + historyGapPx +
                                playerQueueHistoryPadding(viewportSize.height.toFloat(), headingHeight, rowsHeight, historyGapPx)
                        } else if (nextQueueIndex > 0) {
                            emptyHistoryPaddingPx = 0f
                        }
                        rowsTransition = QueueRowsTransition(
                            snapshot, layout, before,
                            returnHeaderToTop = nextQueueIndex == 0 && emptyHistoryPaddingPx > 0f,
                        )
                    } else if (changed || changedOrder) {
                        rowsTransition = null
                        emptyHistoryPaddingPx = 0f
                    }
                    entries.clear()
                    entries.addAll(nextEntries)
                    currentIndex = player.currentMediaItemIndex
                    queueContentRevision = nextContentRevision
                }
                if (events.contains(Player.EVENT_REPEAT_MODE_CHANGED)) repeatMode = player.repeatMode
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    LaunchedEffect(active, reorderState.isAnyItemDragging) {
        if (active && reorderState.isAnyItemDragging) {
            // Drag translations do not change LazyList layout info; refresh their viewport masks.
            while (isActive) withFrameNanos { dragFrame++ }
        }
    }
    val stickyContentTop: () -> Float = {
        if (inlineCurrent) {
            listState.layoutInfo.visibleItemsInfo.firstOrNull {
                (it.key == "queue_upcoming_controls" || it.key == "queue_history_section") && it.offset <= 0
            }?.let { (it.offset + it.size).coerceAtLeast(0).toFloat() } ?: 0f
        } else 0f
    }
    val viewportContentTop = {
        val top = stickyContentTop()
        if (top > 0f) top + topPull.offset else 0f
    }
    val redrawRows: () -> Int = {
        rowsTransition?.progress?.value
        topPull.offset
        dragFrame
    }
    val normalFade = Modifier.queueViewportFade(
        viewportCoordinates, listState, redrawRows, controlsFadeRange = controlsFadeRange,
        contentTop = viewportContentTop,
    )
    val additiveFade = Modifier.queueViewportFade(
        viewportCoordinates, listState, redrawRows, additive = true, controlsFadeRange = controlsFadeRange,
        contentTop = viewportContentTop,
    )
    val coverFade = Modifier.queueViewportFade(
        viewportCoordinates, listState, {
            topPull.offset
            dragFrame
        }, additive = true, controlsFadeRange = controlsFadeRange,
        layerOutsets = LayerOutsets(
            left = AppleMusicDimensions.headerMarqueeEdgeWidth,
            right = AppleMusicDimensions.headerMarqueeEdgeWidth,
        ),
    )
    val autoplayHeadingModifier = additiveFade
        .then(liveRows)
        .then(if (inlineCurrent) Modifier.onSizeChanged { autoplayHeadingHeightPx = it.height } else Modifier)
        .padding(horizontal = selectionPadding)

    PlayerQueueSnapEffect(
        listState = listState,
        active = active && inlineCurrent,
        canSnap = {
            currentEntry != null && rowsTransition == null && !headerAnchor.isPending &&
                !initialScrollPending && !automaticScrollInProgress && pendingHeaderAlignmentKey == null
        },
        isReordering = { reorderState.isAnyItemDragging },
        coverTop = {
            val layout = listState.layoutInfo
            layout.visibleItemsInfo.firstOrNull { it.key == PlayerQueueCurrentHeaderKey }?.offset?.toFloat()
                ?: if (listState.firstVisibleItemIndex > currentHeaderIndex) {
                    -inlineHeaderHeightPx - 1f
                } else {
                    (layout.viewportEndOffset - layout.viewportStartOffset).toFloat() + 1f
                }
        },
        snapTarget = { top, travel ->
            val layout = listState.layoutInfo
            val height = (layout.viewportEndOffset - layout.viewportStartOffset).toFloat()
            val direction = when {
                travel >= snapDirectionThresholdPx -> 1
                travel <= -snapDirectionThresholdPx -> -1
                else -> 0
            }
            playerQueueSnapTarget(
                coverTop = top,
                coverHeight = inlineHeaderHeightPx,
                viewportHeight = height,
                scrollDirection = direction,
                hasHistory = historyEntries.isNotEmpty(),
                canPinUpcoming = inlineContentHeightPx + tailHeightPx >= height + inlineHeaderHeightPx - 0.5f,
            )
        },
    )

    val modeControls: @Composable (Modifier) -> Unit = { controlsModifier ->
        Row(
            modifier = controlsModifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            QueueModeButton(
                indication = indication,
                systemName = "shuffle",
                label = stringResource(R.string.notification_mode_shuffle),
                selected = shuffleEnabled,
                modifier = Modifier.weight(1f),
                enabled = !fmEnabled || autoplayEnabled,
                onClick = { playerConnection.service.setShuffleModeEnabled(!shuffleEnabled) },
            )
            QueueModeButton(
                indication = indication,
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
                indication = indication,
                systemName = "infinity",
                label = stringResource(R.string.queue_autoplay),
                selected = autoplayEnabled,
                modifier = Modifier.weight(1f),
                enabled = !fmEnabled || autoplayEnabled,
                onClick = { playerConnection.setQueueAutoplayEnabled(!autoplayEnabled) },
            )
            QueueModeButton(
                indication = indication,
                systemName = "circlebadge.2",
                label = stringResource(R.string.automix_enabled),
                selected = autoMixEnabled,
                modifier = Modifier.weight(1f),
                onClick = { setAutoMixEnabled(!autoMixEnabled) },
            )
        }
    }
    val continueHeading: @Composable (Modifier) -> Unit = { headingModifier ->
        Column(
            headingModifier,
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
    }

    val openEntryMenu: (String, Rect) -> Unit = { key, bounds ->
        menuAnchorBounds = bounds
        menuEntryKey = key
        menuRemovalRequested = false
        menuExpanded = true
    }
    Column(modifier.testTag("player_queue_page")) {
        if (!inlineCurrent) {
            modeControls(Modifier.padding(horizontal = horizontalPadding))
            continueHeading(
                Modifier.padding(horizontal = horizontalPadding)
                    .padding(top = 18.dp, bottom = 10.dp).playerAdditiveBlend(),
            )
        }
        Box(
            Modifier.fillMaxWidth().weight(1f).clipToBounds()
                .nestedScroll(topPull)
                .onSizeChanged { viewportSize = it }
                .onGloballyPositioned { viewportCoordinates = it },
        ) {
            LazyColumn(
                state = listState,
                overscrollEffect = null,
                contentPadding = PaddingValues(
                    horizontal = (horizontalPadding - selectionPadding).coerceAtLeast(0.dp),
                    vertical = 0.dp,
                ),
                modifier = Modifier.fillMaxSize().graphicsLayer { translationY = topPull.offset }
                    .then(headerAnchor).onGloballyPositioned {
                    // Global placement has completed; a raw correction remeasures before drawing
                    // and preserves the drag/fling owner instead of requesting a new scroll.
                    headerAnchor.restore(renderedContentRevision) { layout ->
                        captureRows(entriesSnapshot, currentEntry?.key, layout)
                            .headerTop(inlineHeaderHeightPx, controlsHeadingHeightPx.toFloat())
                    }
                },
            ) {
                if (inlineCurrent && showHistorySection) {
                    stickyHeader(key = "queue_history_section") {
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .onSizeChanged { historyHeadingHeightPx = it.height }
                                .pointerInput(Unit) { detectTapGestures {} }
                                .padding(horizontal = selectionPadding)
                                .padding(top = 4.dp, bottom = 10.dp)
                                .playerAdditiveBlend(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                stringResource(R.string.queue_history),
                                modifier = Modifier.weight(1f),
                                color = Color.White.copy(alpha = 0.92f),
                                fontSize = 17.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                stringResource(R.string.clear),
                                modifier = Modifier.clickable(
                                    enabled = historyEntries.isNotEmpty(),
                                    interactionSource = null,
                                    indication = indication,
                                    role = Role.Button,
                                ) {
                                    rowsTransition = null
                                    val currentEntries = player.phoneQueueEntries()
                                    val current = currentEntries.indexOfFirst { it.rawIndex == player.currentMediaItemIndex }
                                    currentEntries.take(current.coerceAtLeast(0))
                                        .map { it.rawIndex }.sortedDescending().forEach(player::removeMediaItem)
                                    pendingHeaderAlignmentAnimated = false
                                    pendingHeaderAlignmentKey = currentEntry?.key
                                }.padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
                                color = Color.White.copy(alpha = 0.55f),
                                fontSize = 15.sp,
                            )
                        }
                    }
                    item(key = "queue_history_padding") {
                        val padding = if (historyEntries.isEmpty()) {
                            (emptyHistoryPaddingPx - historyHeadingHeightPx).coerceAtLeast(0f)
                        } else historyPaddingPx.toFloat()
                        Spacer(Modifier.height(with(density) { padding.toDp() }))
                    }
                    items(count = historyEntries.size, key = { historyEntries[it].key }) { index ->
                        val entry = historyEntries[index]
                        QueueSongRow(
                            entry = entry,
                            modifier = (if (rowsTransition == null) Modifier.animateItem() else Modifier).then(liveRows),
                            enabled = rowsTransition == null,
                            indication = indication,
                            normalFade = normalFade,
                            additiveFade = additiveFade,
                            onClick = { playerConnection.service.queueManager.seekToQueueEntry(entry.key) },
                            onLongClick = { openEntryMenu(entry.key, it) },
                        )
                    }
                }
                if (inlineCurrent) {
                    item(key = "queue_history_gap") {
                        Spacer(Modifier.height(if (historyEntries.isEmpty()) 0.dp else PlayerQueueHistoryGap))
                    }
                    // End the history heading's sticky lifetime as soon as the cover reaches the top.
                    stickyHeader(key = "queue_current_boundary") {
                        Spacer(Modifier.height(0.dp))
                    }
                    if (currentEntry != null) {
                        // This slot owns layout identity; songs keep their own keys only in the lists.
                        item(key = PlayerQueueCurrentHeaderKey, contentType = "current_header") {
                            Column(
                                Modifier.fillMaxWidth().onSizeChanged { measuredHeaderHeightPx = it.height }
                                    .padding(horizontal = selectionPadding),
                            ) {
                                Box(Modifier.fillMaxWidth().height(AppleMusicDimensions.headerSize)) {
                                    currentHeader?.invoke(coverFade)
                                }
                                Spacer(Modifier.height(16.dp))
                            }
                        }
                    }
                    stickyHeader(key = "queue_upcoming_controls") {
                        Column(
                            Modifier.fillMaxWidth().onSizeChanged { controlsHeadingHeightPx = it.height }
                                .pointerInput(Unit) { detectTapGestures {} }
                                .padding(horizontal = selectionPadding),
                        ) {
                            modeControls(Modifier)
                            Spacer(Modifier.height(18.dp))
                            continueHeading(Modifier.padding(bottom = 10.dp).playerAdditiveBlend())
                        }
                    }
                }
                if (entries.isEmpty()) {
                    item(key = "queue_empty") {
                        Text(
                            text = stringResource(R.string.queue_empty),
                            modifier = Modifier.padding(horizontal = selectionPadding, vertical = 24.dp)
                                .then(additiveFade),
                            color = Color.White.copy(alpha = 0.55f),
                            fontSize = 15.sp,
                        )
                    }
                }
                fun queueSongs(startIndex: Int, endIndex: Int) {
                    items(
                        count = endIndex - startIndex,
                        key = { displayedEntries[startIndex + it].key },
                    ) { index ->
                        val entry = displayedEntries[startIndex + index]
                        ReorderableItem(
                            reorderState, key = entry.key,
                            animateItemModifier = if (rowsTransition == null) Modifier.animateItem() else Modifier,
                        ) {
                            QueueSongRow(
                                entry = entry,
                                modifier = liveRows,
                                enabled = rowsTransition == null,
                                indication = indication,
                                normalFade = normalFade,
                                additiveFade = additiveFade,
                                onClick = { playerConnection.service.queueManager.seekToQueueEntry(entry.key) },
                                onLongClick = { openEntryMenu(entry.key, it) },
                                selected = !inlineCurrent && entry.rawIndex == currentIndex,
                                dragHandleModifier = Modifier.longPressDraggableHandle(
                                    enabled = rowsTransition == null,
                                    onDragStarted = {
                                        haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                    },
                                    onDragStopped = {
                                        haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
                                    },
                                ),
                            )
                        }
                    }
                }
                queueSongs(0, autoplaySectionIndex)
                if (autoplayEnabled) {
                    item(key = "autoplay_section") {
                        QueueAutoplayHeader(autoplayHeadingModifier)
                    }
                }
                queueSongs(autoplaySectionIndex, displayedEntries.size)
                item(key = "queue_bottom_space") {
                    Spacer(Modifier.height(with(density) { tailHeightPx.toDp() }))
                }
            }
            rowsTransition?.let { motion ->
                val destination = motion.after.orEmpty()
                val ids = (motion.before.keys - destination.keys) +
                    (destination.keys - motion.before.keys) + destination.keys.intersect(motion.before.keys)
                Box(Modifier.matchParentSize().graphicsLayer { translationY = topPull.offset }.clearAndSetSemantics { }
                    .padding(horizontal = (horizontalPadding - selectionPadding).coerceAtLeast(0.dp))) {
                    // These copies own drawing only. Lazy row identities, viewport size,
                    // sticky headings and the shared header artwork stay untouched.
                    for (id in ids) key(id) {
                        val visual = destination[id] ?: motion.before.getValue(id)
                        val rowModifier = Modifier.fillMaxWidth().graphicsLayer {
                            val placement = motion.frame(id, listState.layoutInfo).placement
                            translationY = placement.top
                            alpha = placement.opacity
                        }.drawWithContent {
                            val layout = listState.layoutInfo
                            val top = motion.frame(id, layout).placement.top
                            val sectionTop = if (visual.history) stickyContentTop() else {
                                layout.visibleItemsInfo.firstOrNull { it.key == "queue_upcoming_controls" }
                                    ?.let { (it.offset + it.size).coerceAtLeast(0).toFloat() } ?: 0f
                            }
                            val sectionBottom = if (visual.history) {
                                layout.visibleItemsInfo.firstOrNull { it.key == PlayerQueueCurrentHeaderKey }
                                    ?.let { it.offset - historyGapPx }
                                    ?: if (listState.firstVisibleItemIndex > currentHeaderIndex) 0f else Float.POSITIVE_INFINITY
                            } else Float.POSITIVE_INFINITY
                            val clipTop = (sectionTop - top).coerceIn(0f, size.height)
                            val clipBottom = (sectionBottom - top).coerceIn(clipTop, size.height)
                            clipRect(top = clipTop, bottom = clipBottom) { this@drawWithContent.drawContent() }
                        }
                        val entry = visual.entry
                        if (entry == null) {
                            QueueAutoplayHeader(rowModifier.padding(horizontal = selectionPadding).then(additiveFade))
                        } else {
                            QueueSongRow(
                                entry = entry,
                                modifier = rowModifier,
                                interactive = false,
                                indication = indication,
                                normalFade = normalFade,
                                additiveFade = additiveFade,
                                onClick = { playerConnection.service.queueManager.seekToQueueEntry(entry.key) },
                                onLongClick = { openEntryMenu(entry.key, it) },
                                dragHandleModifier = if (visual.history) null else Modifier,
                            )
                        }
                    }
                }
            }
        }
    }

    // Keep the popup outside lazy items so removal cannot dispose its closing animation.
    IosPopupMenu(
        expanded = menuExpanded,
        onExpandedChange = { menuExpanded = it },
        itemCount = 1,
        externalAnchorBounds = menuAnchorBounds,
        alignToAnchorStart = true,
        expandFromAnchor = Alignment.Start,
        forceBelowAnchor = true,
        keepAnchorVisible = true,
        fullScreen = true,
        onClosed = {
            if (!menuExpanded) {
                val selectedKey = menuEntryKey
                val removeRequested = menuRemovalRequested
                menuEntryKey = null
                menuAnchorBounds = null
                menuRemovalRequested = false
                if (removeRequested && selectedKey != null) {
                    val currentEntries = player.phoneQueueEntries()
                    currentEntries.firstOrNull { it.key == selectedKey }?.let { selected ->
                        if (currentEntries.size > 1) {
                            player.removeMediaItem(selected.rawIndex)
                        } else {
                            Toast.makeText(context, R.string.queue_cannot_remove_last, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        },
        anchor = { Spacer(Modifier.size(1.dp)) },
    ) { childBackdrop, close ->
        IosMenuItem(
            title = stringResource(R.string.queue_remove),
            systemName = "trash",
            destructive = true,
            backdrop = childBackdrop,
            onClick = {
                menuRemovalRequested = true
                close()
            },
        )
    }
}

@OptIn(UnstableApi::class)
@Composable
internal fun QueueSongRow(
    entry: PhoneQueueEntry,
    indication: Indication?,
    normalFade: Modifier,
    additiveFade: Modifier,
    onClick: () -> Unit,
    onLongClick: (Rect) -> Unit,
    selected: Boolean = false,
    dragHandleModifier: Modifier? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    interactive: Boolean = true,
) {
    var entryCoordinates by remember(entry.key) { mutableStateOf<LayoutCoordinates?>(null) }
    val metadata = entry.mediaItem.metadata
    val display = entry.mediaItem.mediaMetadata
    val title = metadata?.title ?: display.title?.toString().orEmpty()
    val artist = metadata?.artists?.joinToString(", ") { it.name } ?: display.artist?.toString().orEmpty()
    val cover = metadata?.coverUrl ?: display.artworkUri?.toString().orEmpty()
    Box(modifier) {
        if (selected) {
            Box(
                Modifier.matchParentSize().then(normalFade)
                    .clip(ContinuousRoundedRectangle(10.dp))
                    .background(Color.White.copy(alpha = 0.06f)),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().height(56.dp).padding(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.weight(1f).fillMaxHeight()
                    .onGloballyPositioned { entryCoordinates = it }
                    .then(if (interactive) Modifier.combinedClickable(
                        enabled = enabled,
                        interactionSource = null,
                        indication = indication,
                        onClick = onClick,
                        onLongClick = {
                            entryCoordinates?.takeIf { it.isAttached }?.boundsInWindow()?.let(onLongClick)
                        },
                    ) else Modifier),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AsyncImage(
                    model = cover.smallImage(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(46.dp).clip(ContinuousRoundedRectangle(5.dp)).then(normalFade),
                )
                Column(Modifier.weight(1f).padding(horizontal = 12.dp).then(additiveFade)) {
                    Text(
                        title,
                        color = Color.White.copy(alpha = 0.92f),
                        fontSize = 16.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        lineHeight = 20.sp,
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
            if (dragHandleModifier != null) {
                Box(
                    modifier = Modifier.size(32.dp, 46.dp).then(dragHandleModifier).then(additiveFade),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    SfIcon(
                        "line.3.horizontal",
                        stringResource(R.string.queue_reorder),
                        tint = Color.White.copy(alpha = 0.3f),
                        size = 24.dp,
                    )
                }
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

internal fun Modifier.queueViewportFade(
    viewportCoordinates: LayoutCoordinates?,
    listState: LazyListState,
    dragFrame: () -> Int,
    additive: Boolean = false,
    controlsFadeRange: (Float) -> ClosedFloatingPointRange<Float>? = { null },
    contentTop: () -> Float = { 0f },
    layerOutsets: LayerOutsets = LayerOutsets.Zero,
): Modifier = composed {
    var contentCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var contentPositionInRoot by remember { mutableStateOf<Offset?>(null) }
    graphicsLayer {
        blendMode = if (additive) BlendMode.Plus else BlendMode.SrcOver
        compositingStrategy = CompositingStrategy.Offscreen
        outsets = layerOutsets
    }
        .onGloballyPositioned {
            contentCoordinates = it
            // LayoutCoordinates is reused while animateItem moves the row. Snapshot its
            // position too so cached masks redraw as content enters the viewport.
            contentPositionInRoot = it.positionInRoot()
        }
        .drawWithCache {
            val maskLeft = -layerOutsets.left.toPx()
            val maskSize = Size(size.width + layerOutsets.left.toPx() + layerOutsets.right.toPx(), size.height)
            val topFadeHeight = 8.dp.toPx()
            val bottomFadeHeight = 60.dp.toPx()
            var viewportBrush: Brush? = null
            var cachedFadeTop = Float.NaN
            var cachedHeight = Float.NaN
            var cachedTopFade = Float.NaN
            var cachedBottomFade = Float.NaN
            var controlsBrush: Brush? = null
            var cachedControlsStart = Float.NaN
            var cachedControlsEnd = Float.NaN
            onDrawWithContent {
                val layout = listState.layoutInfo
                dragFrame()
                val viewport = viewportCoordinates
                val content = contentCoordinates
                val top = if (contentPositionInRoot != null && viewport?.isAttached == true && content?.isAttached == true) {
                    viewport.localPositionOf(content, Offset.Zero).y
                } else null
                val visibleTop = contentTop().coerceAtLeast(0f)
                val clipTop = if (top != null) visibleTop - top else 0f
                clipRect(left = maskLeft, top = clipTop.coerceIn(0f, size.height), right = maskLeft + maskSize.width) { this@onDrawWithContent.drawContent() }
                if (top == null) return@onDrawWithContent
                val height = (layout.viewportEndOffset - layout.viewportStartOffset).toFloat()
                if (height <= 0f) return@onDrawWithContent
                val controlsRange = controlsFadeRange(height)
                val fadeTop = visibleTop.coerceAtMost(height)
                val fadeSpan = height - fadeTop
                val fadeHeight = fadeSpan.coerceAtLeast(1f)
                val topFade = if (listState.canScrollBackward) (topFadeHeight / fadeHeight).coerceIn(0f, 0.5f) else 0f
                val bottomFade = if (controlsRange == null && listState.canScrollForward) {
                    (bottomFadeHeight / fadeHeight).coerceIn(0f, 0.5f)
                } else 0f
                val needsViewportMask = (topFade > 0f && top < fadeTop + fadeSpan * topFade) ||
                    (bottomFade > 0f && top + size.height > height - fadeSpan * bottomFade)
                val controlsStart = controlsRange?.start ?: 0f
                val controlsEnd = controlsRange?.endInclusive ?: 0f
                val needsControlsMask = controlsEnd > controlsStart && top + size.height > controlsStart
                if (!needsViewportMask && !needsControlsMask) return@onDrawWithContent

                // Keep shaders in viewport coordinates; scrolling only translates their draw.
                if (needsViewportMask && (viewportBrush == null || cachedFadeTop != fadeTop ||
                        cachedHeight != height || cachedTopFade != topFade || cachedBottomFade != bottomFade)) {
                    viewportBrush = Brush.verticalGradient(
                        0f to if (topFade > 0f) Color.Transparent else Color.Black,
                        topFade to Color.Black,
                        1f - bottomFade to Color.Black,
                        1f to if (bottomFade > 0f) Color.Transparent else Color.Black,
                        startY = fadeTop,
                        endY = height,
                    )
                    cachedFadeTop = fadeTop
                    cachedHeight = height
                    cachedTopFade = topFade
                    cachedBottomFade = bottomFade
                }
                if (needsControlsMask && (controlsBrush == null || cachedControlsStart != controlsStart || cachedControlsEnd != controlsEnd)) {
                    controlsBrush = Brush.verticalGradient(
                        colors = listOf(Color.Black, Color.Transparent),
                        startY = controlsStart,
                        endY = controlsEnd,
                    )
                    cachedControlsStart = controlsStart
                    cachedControlsEnd = controlsEnd
                }
                translate(top = -top) {
                    if (needsViewportMask) {
                        drawRect(
                            brush = checkNotNull(viewportBrush),
                            topLeft = Offset(maskLeft, top),
                            size = maskSize,
                            blendMode = BlendMode.DstIn,
                        )
                    }
                    if (needsControlsMask) {
                        drawRect(
                            brush = checkNotNull(controlsBrush),
                            topLeft = Offset(maskLeft, top),
                            size = maskSize,
                            blendMode = BlendMode.DstIn,
                        )
                    }
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
    indication: Indication? = LocalIndication.current,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .height(36.dp)
            .playerAdditiveBlend()
            .clip(Capsule())
            .background(Color.White.copy(alpha = if (selected) 0.45f else 0.1f))
            .semantics { this.selected = selected }
            .clickable(
                interactionSource = null,
                indication = indication,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            ),
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
