package com.ljyh.mei.ui.component.player.component


import android.os.SystemClock
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import com.ljyh.mei.R
import com.ljyh.mei.constants.AccompanimentLyricTextBoldKey
import com.ljyh.mei.constants.AccompanimentLyricTextSizeKey
import com.ljyh.mei.constants.LyricTextSize
import com.ljyh.mei.constants.LyricRomanizationEnabledKey
import com.ljyh.mei.constants.NormalLyricTextBoldKey
import com.ljyh.mei.constants.NormalLyricTextSizeKey
import com.ljyh.mei.playback.PlayerConnection
import com.ljyh.mei.ui.component.sheet.LocalPlayerSheet
import com.ljyh.mei.ui.component.utils.LifecycleStartedEffect
import com.ljyh.mei.ui.model.LyricData
import com.ljyh.mei.ui.model.LyricSource
import com.ljyh.mei.utils.rememberEnumPreference
import com.ljyh.mei.utils.rememberPreference
import com.ljyh.mei.utils.setClipboard
import com.mocharealm.accompanist.lyrics.core.model.ISyncedLine
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeAlignment
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

@OptIn(UnstableApi::class)
@Composable
fun LyricScreen(
    lyricData: LyricData,
    modifier: Modifier = Modifier,
    playerConnection: PlayerConnection,
    onClick: (LyricSource) -> Unit,
    onLongClick: (LyricSource) -> Unit,
    controlsVisible: Boolean,
    onToggleControls: (Boolean) -> Unit,
    active: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    sourceBadgeEndOffset: Dp = 0.dp,
    sourceBadgeBottomPadding: Dp = 8.dp,
    sourceBadgeAnchor: Offset? = null,
    sourceBadgeAnchorProgress: () -> Float = { 1f },
    sourceBadgeHorizontalAnchorX: Float? = null,
    topFadeHeight: Dp = 20.dp,
    bottomFadeHeight: Dp = 100.dp,
    synchronizeEntryWithParent: Boolean = false,
    lyricContentModifier: Modifier = Modifier,
    rendererVerticalPadding: Dp = 8.dp,
    sourceBadgeBottomInset: () -> Dp = { 0.dp },
    controlsFadeRange: (Float) -> ClosedFloatingPointRange<Float>? = { null },
    showSourceBadge: Boolean = true,
) {
    val context = LocalContext.current
    val layoutDirection = LocalLayoutDirection.current
    val sheet = LocalPlayerSheet.current
    val expanded by remember(sheet) { derivedStateOf { sheet == null || sheet.state.isExpanded } }
    val contentVisible by remember(sheet) { derivedStateOf { sheet == null || sheet.state.isContentVisible } }
    val isPlaying by playerConnection.isPlaying.collectAsStateWithLifecycle()
    val (normalLyricTextSize, _) = rememberEnumPreference(
        NormalLyricTextSizeKey,
        LyricTextSize.Size28
    )
    val (normalLyricTextBold, _) = rememberPreference(NormalLyricTextBoldKey, true)
    val (translationLyricTextSize, _) = rememberEnumPreference(
        AccompanimentLyricTextSizeKey,
        LyricTextSize.Size18
    )
    val (translationLyricTextBold, _) = rememberPreference(AccompanimentLyricTextBoldKey, true)
    val (romanizationEnabled, _) = rememberPreference(LyricRomanizationEnabledKey, true)
    val manualScroll = remember(lyricData.lyricLine, playerConnection.player) {
        LyricManualScrollState()
    }
    LaunchedEffect(manualScroll, active) {
        if (!active) manualScroll.onHidden()
    }

    LifecycleStartedEffect(controlsVisible, expanded, active, isPlaying) {
        if (controlsVisible && expanded && active && isPlaying) {
            delay(5000)
            onToggleControls(false)
        }
    }


    val nestedScrollConnection = remember(manualScroll, onToggleControls) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput) {
                    val delta = available.y
                    if (delta != 0f) {
                        manualScroll.onUserScroll(SystemClock.elapsedRealtime())
                    }
                    if (delta < -10) {
                        onToggleControls(false)
                    } else if (delta > 10) {
                        onToggleControls(true)
                    }
                }
                return Offset.Zero
            }
        }
    }

    Column(
        modifier = modifier.fillMaxSize()
            .nestedScroll(nestedScrollConnection)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onToggleControls(true) }
    ) {
        // Bounds of the bottom-right source badge, in this box's coordinates. The
        // lower-half tap-to-toggle gesture runs on the Initial pass and consumes taps
        // before children see them, so the badge region must be excluded explicitly.
        var badgeBounds by remember { mutableStateOf<Rect?>(null) }
        var badgeHostCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .onGloballyPositioned { badgeHostCoordinates = it }
                .pointerInput(controlsVisible, onToggleControls, showSourceBadge) {
                    val badgeSlop = 12.dp.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown(
                            requireUnconsumed = false,
                            pass = PointerEventPass.Initial,
                        )
                        val lowerHalf = down.position.y >= size.height / 2f
                        val inBadge = showSourceBadge && badgeBounds?.inflate(badgeSlop)?.contains(down.position) == true
                        var moved = false
                        var released = false
                        var pressed = true
                        while (pressed && !released) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                                moved = true
                            }
                            if (change.changedToUpIgnoreConsumed()) {
                                released = true
                                if (lowerHalf && !moved && !inBadge) {
                                    change.consume()
                                    onToggleControls(!controlsVisible)
                                }
                            }
                            pressed = event.changes.any { it.pressed }
                        }
                    }
                }
        ) {
            if (lyricData.lyricLine.lines.isNotEmpty()) {
                key(System.identityHashCode(lyricData.lyricLine)) {
                    val player = playerConnection.player
                    val lines = lyricData.lyricLine.lines
                    val accompanimentToMainMap = remember(lines) { lyricAccompanimentOwners(lines) }
                    // Warm the visible window near playback even before this host is shown.
                    val initialPosition = remember(player) { player.currentPosition.coerceAtLeast(0L) }
                    val listState = remember(player) {
                        LazyListState(firstVisibleItemIndex = lyricFocusLineIndex(lines, initialPosition.toInt(), accompanimentToMainMap))
                    }
                    // Rows revealed by a within-screen jump need an existing placement
                    // to animate from. Keep a full window laid out beyond each edge.
                    val keepAliveZone = LocalConfiguration.current.screenHeightDp.dp
                    val keepAliveZonePx = with(LocalDensity.current) { keepAliveZone.roundToPx() }
                    val lyricOffsetPx = with(LocalDensity.current) { 48.dp.roundToPx() }
                    var animatedPosition by remember(player) { mutableLongStateOf(initialPosition) }
                    var placementGeneration by remember(player) { mutableIntStateOf(0) }
                    val lyricAlpha = remember(player) { Animatable(0f) }
                    val focusLineVisible by remember(listState, lines, keepAliveZonePx) {
                        derivedStateOf {
                            val focusIndex = lyricFocusLineIndex(lines, animatedPosition.toInt(), accompanimentToMainMap)
                            val layout = listState.layoutInfo
                            layout.visibleItemsInfo.any { item ->
                                item.index == focusIndex && isLyricLineVisible(
                                    item.offset, item.size, layout.viewportStartOffset,
                                    layout.viewportEndOffset, keepAliveZonePx,
                                )
                            }
                        }
                    }
                    LaunchedEffect(listState, manualScroll, active) {
                        if (!active) return@LaunchedEffect
                        listState.interactionSource.interactions.collect { interaction ->
                            when (interaction) {
                                is DragInteraction.Start -> manualScroll.onDragStarted(SystemClock.elapsedRealtime())
                                is DragInteraction.Stop, is DragInteraction.Cancel ->
                                    manualScroll.onDragStopped(SystemClock.elapsedRealtime())
                            }
                        }
                    }
                    LifecycleStartedEffect(player, keepAliveZonePx, lyricOffsetPx, manualScroll, contentVisible, active, synchronizeEntryWithParent) {
                        if (!contentVisible || !active) return@LifecycleStartedEffect
                        try {
                            // A retained host must position before its old frame becomes visible.
                            lyricAlpha.snapTo(0f)
                            // Measure the actual viewport before deciding whether entry may animate.
                            snapshotFlow { listState.layoutInfo.visibleItemsInfo.isNotEmpty() }
                                .first { it }
                            var lastFocusIndex: Int? = null
                            var initialPositionPending = true
                            while (true) {
                                manualScroll.resumeIfIdle(SystemClock.elapsedRealtime(), listState.isScrollInProgress)
                                var position = player.currentPosition.coerceAtLeast(0L)
                                var focusIndex = lyricFocusLineIndex(lines, position.toInt(), accompanimentToMainMap)
                                var jumped = false
                                if (!manualScroll.isBrowsing && !listState.isScrollInProgress &&
                                    (focusIndex != lastFocusIndex || manualScroll.returnToPlaybackPending)
                                ) {
                                    val layout = listState.layoutInfo
                                    val viewportStart = layout.viewportStartOffset + keepAliveZonePx
                                    val viewportEnd = layout.viewportEndOffset - keepAliveZonePx
                                    val visibleLines = layout.visibleItemsInfo.filter { item ->
                                        item.index < lines.size && isLyricLineVisible(
                                            item.offset, item.size, layout.viewportStartOffset,
                                            layout.viewportEndOffset, keepAliveZonePx,
                                        )
                                    }
                                    // Clipped rows and the renderer's keep-alive area do not
                                    // increase the number of lyric lines that fit on screen.
                                    val fullLines = visibleLines.filter { item ->
                                        item.offset >= viewportStart && item.offset + item.size <= viewportEnd
                                    }
                                    val firstVisibleIndex = (fullLines.firstOrNull() ?: visibleLines.firstOrNull())?.index
                                    val snap = firstVisibleIndex == null || shouldSnapLyricScroll(
                                        lines, firstVisibleIndex, focusIndex, fullLines.size.coerceAtLeast(1),
                                    )
                                    val positioned = runInterruptibleLyricJump(
                                        restoreVisibility = { lyricAlpha.snapTo(1f) },
                                    ) {
                                        if (snap && lyricAlpha.value > 0f && !(initialPositionPending && synchronizeEntryWithParent)) {
                                            lyricAlpha.animateTo(0f, tween(120))
                                        }
                                        // Seeking may continue while the old lyrics fade out.
                                        position = player.currentPosition.coerceAtLeast(0L)
                                        focusIndex = lyricFocusLineIndex(lines, position.toInt(), accompanimentToMainMap)
                                        // A gesture may start during the fade. Leave it in control.
                                        if (!manualScroll.isBrowsing && !listState.isScrollInProgress) {
                                            if (snap) {
                                                // Reset placement springs, retaining the text measurement cache.
                                                listState.scrollToItem(focusIndex)
                                                animatedPosition = position
                                                placementGeneration++
                                                jumped = true
                                            } else {
                                                val currentLayout = listState.layoutInfo
                                                val target = currentLayout.visibleItemsInfo.firstOrNull {
                                                    it.index == focusIndex
                                                }
                                                if (target != null) {
                                                    val anchor = currentLayout.viewportStartOffset +
                                                        lyricOffsetPx + keepAliveZonePx
                                                    listState.scrollBy((target.offset - anchor).toFloat())
                                                } else {
                                                    listState.scrollToItem(focusIndex)
                                                }
                                            }
                                            true
                                        } else {
                                            false
                                        }
                                    }
                                    if (positioned) {
                                        lastFocusIndex = focusIndex
                                        manualScroll.onPlaybackPositioned()
                                    }
                                }
                                animatedPosition = position
                                if (initialPositionPending || jumped) {
                                    val parentOwnsEntry = initialPositionPending && synchronizeEntryWithParent
                                    initialPositionPending = false
                                    // Keep the positioning frame hidden, then fade in while time
                                    // updates continue so karaoke highlighting stays responsive.
                                    withFrameNanos { }
                                    if (parentOwnsEntry) lyricAlpha.snapTo(1f)
                                    else launch { lyricAlpha.animateTo(1f, tween(180)) }
                                }
                                delay(50)
                            }
                        } finally {
                            // STOP or collapse can interrupt an entry or seek fade while
                            // the frame clock is paused. Restore the layer before resuming.
                            withContext(NonCancellable) { lyricAlpha.snapTo(1f) }
                        }
                    }
                    key(keepAliveZonePx) {
                        Box(Modifier.fillMaxSize().padding(contentPadding)) {
                            PlayerLyricsView(
                                showPhonetic = romanizationEnabled,
                                isVerbatim = lyricData.isVerbatim,
                                listState = listState,
                                isManualScrolling = manualScroll.isBrowsing && listState.isScrollInProgress,
                                useBlurEffect = !manualScroll.isBrowsing || focusLineVisible,
                                lyrics = lyricData.lyricLine,
                                currentPosition = { animatedPosition.toInt() },
                                onLineClicked = { line ->
                                    manualScroll.onLineSelected()
                                    playerConnection.player.seekTo(line.start.toLong())
                                    onToggleControls(true)
                                },
                                onLinePressed = { line ->
                                    val result = when (line) {
                                        is KaraokeLine -> {
                                            "${line.syllables.joinToString("") { it.content }}\n${line.translation}"
                                        }
                                        is SyncedLine -> {
                                            "${line.content}\n${line.translation}"
                                        }
                                        else -> {
                                            Toast.makeText(context, "未知的歌词类型", Toast.LENGTH_SHORT).show()
                                            null
                                        }
                                    }

                                    result?.let {
                                        try {
                                            setClipboard(context, it, "lyric")
                                        } catch (e: Exception) {
                                            e.printStackTrace()
                                            Toast.makeText(context, "复制失败", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .padding(vertical = rendererVerticalPadding)
                                    .graphicsLayer {
                                        // The former nested layers applied this entry fade twice.
                                        val fraction = lyricAlpha.value
                                        alpha = fraction * fraction
                                        blendMode = BlendMode.Plus
                                        compositingStrategy = CompositingStrategy.Offscreen
                                    },
                                // Keep the second inset and its former buffer clip, without
                                // another full-viewport additive offscreen layer.
                                listModifier = Modifier
                                    .padding(vertical = rendererVerticalPadding)
                                    .clipToBounds(),
                                normalLineTextStyle = LocalTextStyle.current.copy(
                                    fontSize = normalLyricTextSize.text.sp,
                                    fontWeight = if (normalLyricTextBold) FontWeight.Bold else FontWeight.Normal,
                                    textMotion = TextMotion.Animated,
                                ),
                                accompanimentLineTextStyle = LocalTextStyle.current.copy(
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    textMotion = TextMotion.Animated,
                                ),
                                translationLineTextStyle = LocalTextStyle.current.copy(
                                    fontSize = translationLyricTextSize.text.sp,
                                    fontWeight = if (translationLyricTextBold) FontWeight.Bold else FontWeight.Normal,
                                    textMotion = TextMotion.Animated,
                                ),
                                offset = 48.dp,
                                keepAliveZone = keepAliveZone,
                                placementGeneration = placementGeneration,
                                topFadeHeight = topFadeHeight,
                                bottomFadeHeight = bottomFadeHeight,
                                contentModifier = lyricContentModifier,
                                controlsFadeRange = controlsFadeRange,
                            )
                        }
                    }
                }

                if (showSourceBadge) LyricSourceBadge(
                    source = lyricData.source,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(bottom = sourceBadgeBottomPadding)
                        .offset {
                            val defaultX = sourceBadgeEndOffset.roundToPx()
                            val defaultY = -sourceBadgeBottomInset().roundToPx()
                            val host = badgeHostCoordinates
                            val anchor = sourceBadgeAnchor
                            if (host == null || !host.isAttached) {
                                IntOffset(defaultX, defaultY)
                            } else {
                                val badgeSize = 34.dp.roundToPx()
                                val isLtr = layoutDirection == LayoutDirection.Ltr
                                val left = if (isLtr) host.size.width - badgeSize else 0
                                val top = host.size.height - sourceBadgeBottomPadding.roundToPx() - badgeSize
                                val hostOrigin = host.positionInRoot()
                                lyricSourceBadgeOffset(
                                    defaultX = defaultX,
                                    badgeOrigin = Offset(left.toFloat(), top.toFloat()),
                                    anchor = anchor?.minus(hostOrigin),
                                    progress = sourceBadgeAnchorProgress(),
                                    isLtr = isLtr,
                                    horizontalAnchorX = sourceBadgeHorizontalAnchorX?.minus(hostOrigin.x),
                                    defaultY = defaultY,
                                )
                            }
                        }
                        .onGloballyPositioned { badgeBounds = it.boundsInParent() },
                    onClick = onClick,
                    onLongClick = onLongClick
                )
            }
        }
    }
}

/** Coordinates are local to the badge host; horizontal alignment survives footer motion. */
internal fun lyricSourceBadgeOffset(
    defaultX: Int,
    badgeOrigin: Offset,
    anchor: Offset?,
    progress: Float,
    isLtr: Boolean,
    horizontalAnchorX: Float? = null,
    defaultY: Int = 0,
): IntOffset {
    val direction = if (isLtr) 1f else -1f
    val fraction = progress.coerceIn(0f, 1f)
    val targetX = ((horizontalAnchorX ?: anchor?.x) ?: badgeOrigin.x) - badgeOrigin.x
    val x = if (horizontalAnchorX != null) {
        targetX * direction
    } else if (anchor != null) {
        defaultX + (targetX * direction - defaultX) * fraction
    } else defaultX.toFloat()
    val y = if (anchor != null) {
        defaultY + (anchor.y - badgeOrigin.y - defaultY) * fraction
    } else defaultY.toFloat()
    return IntOffset(x.roundToInt(), y.roundToInt())
}

internal suspend fun runInterruptibleLyricJump(
    restoreVisibility: suspend () -> Unit,
    jump: suspend () -> Boolean,
): Boolean = coroutineScope {
    var completed = false
    // A scroll mutation cancels its calling Job when a gesture wins. Isolate
    // that cancellation so playback updates and subsequent fades keep running.
    launch {
        try {
            completed = jump()
        } finally {
            if (!completed) {
                withContext(NonCancellable) { restoreVisibility() }
            }
        }
    }.join()
    completed
}

internal fun latestMainLyricLineIndices(lines: List<ISyncedLine>, positionMs: Int): List<Int> {
    var leftIndex = -1
    var rightIndex = -1
    lines.forEachIndexed { index, line ->
        if (line.start <= positionMs && line !is KaraokeLine.AccompanimentKaraokeLine) {
            if ((line as? KaraokeLine)?.alignment == KaraokeAlignment.End) {
                rightIndex = index
            } else {
                leftIndex = index
            }
        }
    }
    // Select before testing end times so a superseded long line cannot return in a gap.
    return listOf(leftIndex, rightIndex).filter { it >= 0 }.sorted()
}

internal fun lyricAccompanimentOwners(lines: List<ISyncedLine>): Map<Int, Int> {
    val mainIndices = lines.indices.filter { lines[it] !is KaraokeLine.AccompanimentKaraokeLine }
    return buildMap {
        lines.forEachIndexed { index, line ->
            if (line !is KaraokeLine.AccompanimentKaraokeLine) return@forEachIndexed
            val before = mainIndices.lastOrNull { it < index }
            val after = mainIndices.firstOrNull { it > index }
            val owner = when {
                before == null -> after
                after == null -> before
                (line.start - lines[before].start).absoluteValue <=
                    (lines[after].start - line.start).absoluteValue -> before
                else -> after
            }
            if (owner != null) put(index, owner)
        }
    }
}

internal fun activeMainLyricLineIndices(
    lines: List<ISyncedLine>,
    positionMs: Int,
    accompanimentToMainMap: Map<Int, Int> = lyricAccompanimentOwners(lines),
): List<Int> = latestMainLyricLineIndices(lines, positionMs).filter { index ->
    val line = lines[index]
    val effectiveEnd = if (line is KaraokeLine.MainKaraokeLine) {
        maxOf(line.end, line.accompanimentLines?.maxOfOrNull { it.end } ?: line.end)
    } else {
        line.end
    }
    positionMs < effectiveEnd || accompanimentToMainMap.any { (accompanimentIndex, owner) ->
        val accompaniment = lines[accompanimentIndex]
        owner == index && positionMs >= accompaniment.start && positionMs < accompaniment.end
    }
}

internal fun lyricFocusLineIndex(
    lines: List<ISyncedLine>,
    positionMs: Int,
    accompanimentToMainMap: Map<Int, Int> = lyricAccompanimentOwners(lines),
): Int {
    val activeIndex = activeMainLyricLineIndices(lines, positionMs, accompanimentToMainMap).firstOrNull()
    if (activeIndex != null) return activeIndex
    val nextIndex = lines.indexOfFirst {
        it !is KaraokeLine.AccompanimentKaraokeLine && it.start > positionMs
    }
    return if (nextIndex >= 0) nextIndex else lines.indexOfLast {
        it !is KaraokeLine.AccompanimentKaraokeLine
    }.coerceAtLeast(0)
}

internal fun shouldSnapLyricScroll(
    lines: List<ISyncedLine>,
    fromIndex: Int,
    toIndex: Int,
    visibleLineCount: Int,
): Boolean {
    val distance = (minOf(fromIndex, toIndex) until maxOf(fromIndex, toIndex)).count {
        lines[it] !is KaraokeLine.AccompanimentKaraokeLine
    }
    // Keep two extra main lyric rows beyond the visible window within animation range.
    return visibleLineCount > 0 && distance >= visibleLineCount + 2
}

internal fun isLyricLineVisible(
    itemOffset: Int,
    itemSize: Int,
    viewportStart: Int,
    viewportEnd: Int,
    keepAliveZonePx: Int,
): Boolean = itemSize > 0 &&
    itemOffset + itemSize > viewportStart + keepAliveZonePx &&
    itemOffset < viewportEnd - keepAliveZonePx

@Composable
private fun LyricSourceBadge(
    source: LyricSource,
    modifier: Modifier = Modifier,
    onClick: (LyricSource) -> Unit,
    onLongClick: (LyricSource) -> Unit
) {
    Box(
        modifier = modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.15f))
            .combinedClickable(
                onClick = { onClick(source) },
                onLongClick = { onLongClick(source) }
            ),
        contentAlignment = Alignment.Center,
    ) {

        Icon(
            painter = painterResource(
                when (source) {
                    LyricSource.Empty, LyricSource.Loading -> R.drawable.empty
                    LyricSource.NetEaseCloudMusic -> R.drawable.netease
                    LyricSource.QQMusic -> R.drawable.qq
                    LyricSource.AM -> R.drawable.am
                }
            ),
            modifier = Modifier.size(22.dp),
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.8f)
        )
    }
}
