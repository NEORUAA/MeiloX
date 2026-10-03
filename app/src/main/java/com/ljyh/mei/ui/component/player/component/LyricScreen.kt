package com.ljyh.mei.ui.component.player.component


import android.widget.Toast
import androidx.annotation.OptIn
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import com.kyant.capsule.ContinuousRoundedRectangle
import com.ljyh.mei.R
import com.ljyh.mei.constants.AccompanimentLyricTextBoldKey
import com.ljyh.mei.constants.AccompanimentLyricTextSizeKey
import com.ljyh.mei.constants.LyricTextSize
import com.ljyh.mei.constants.NormalLyricTextBoldKey
import com.ljyh.mei.constants.NormalLyricTextSizeKey
import com.ljyh.mei.playback.PlayerConnection
import com.ljyh.mei.ui.model.LyricData
import com.ljyh.mei.ui.model.LyricSource
import com.ljyh.mei.utils.rememberEnumPreference
import com.ljyh.mei.utils.rememberPreference
import com.ljyh.mei.utils.setClipboard
import com.mocharealm.accompanist.lyrics.core.model.ISyncedLine
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
import com.mocharealm.accompanist.lyrics.ui.composable.lyrics.KaraokeLyricsView
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(UnstableApi::class)
@Composable
fun LyricScreen(
    lyricData: LyricData,
    modifier: Modifier = Modifier,
    playerConnection: PlayerConnection,
    onClick: (LyricSource) -> Unit,
    onLongClick: (LyricSource) -> Unit,
    controlsVisible: Boolean,
    onToggleControls: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val (normalLyricTextSize, _) = rememberEnumPreference(
        NormalLyricTextSizeKey,
        LyricTextSize.Size28
    )
    val (normalLyricTextBold, _) = rememberPreference(NormalLyricTextBoldKey, true)
    val (accompanimentLyricTextSize, _) = rememberEnumPreference(
        AccompanimentLyricTextSizeKey,
        LyricTextSize.Size18
    )
    val (accompanimentLyricTextBold, _) = rememberPreference(AccompanimentLyricTextBoldKey, true)

    LaunchedEffect(controlsVisible) {
        if (controlsVisible) {
            delay(5000)
            onToggleControls(false)
        }
    }


    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput) {
                    val delta = available.y
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
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .pointerInput(controlsVisible, onToggleControls) {
                    val badgeSlop = 12.dp.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown(
                            requireUnconsumed = false,
                            pass = PointerEventPass.Initial,
                        )
                        val lowerHalf = down.position.y >= size.height / 2f
                        val inBadge = badgeBounds?.inflate(badgeSlop)?.contains(down.position) == true
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
                    // Restore from playback after measuring, not from a saved scroll offset.
                    val listState = remember(player) { LazyListState() }
                    // Rows revealed by a within-screen jump need an existing placement
                    // to animate from. Keep a full window laid out beyond each edge.
                    val keepAliveZone = LocalConfiguration.current.screenHeightDp.dp
                    val keepAliveZonePx = with(LocalDensity.current) { keepAliveZone.roundToPx() }
                    var animatedPosition by remember(player) { mutableLongStateOf(0L) }
                    var placementGeneration by remember(player) { mutableIntStateOf(0) }
                    val lyricAlpha = remember(player) { Animatable(0f) }
                    LaunchedEffect(player, keepAliveZonePx) {
                        // Measure the actual viewport before deciding whether entry may animate.
                        snapshotFlow { listState.layoutInfo.visibleItemsInfo.isNotEmpty() }
                            .first { it }
                        var lastFocusIndex: Int? = null
                        var initialPositionPending = true
                        while (true) {
                            var position = player.currentPosition.coerceAtLeast(0L)
                            var focusIndex = lyricFocusLineIndex(lines, position.toInt())
                            var jumped = false
                            if (focusIndex != lastFocusIndex) {
                                val layout = listState.layoutInfo
                                val viewportStart = layout.viewportStartOffset + keepAliveZonePx
                                val viewportEnd = layout.viewportEndOffset - keepAliveZonePx
                                val visibleLines = layout.visibleItemsInfo.filter { item ->
                                    item.index < lines.size && item.size > 0 &&
                                        item.offset + item.size > viewportStart &&
                                        item.offset < viewportEnd
                                }
                                // Clipped rows and the renderer's keep-alive area do not
                                // increase the number of lyric lines that fit on screen.
                                val fullLines = visibleLines.filter { item ->
                                    item.offset >= viewportStart && item.offset + item.size <= viewportEnd
                                }
                                val firstVisibleIndex = (fullLines.firstOrNull() ?: visibleLines.firstOrNull())?.index
                                if (!listState.isScrollInProgress && firstVisibleIndex != null && shouldSnapLyricScroll(
                                        lines, firstVisibleIndex, focusIndex, fullLines.size.coerceAtLeast(1),
                                    )
                                ) {
                                    jumped = runInterruptibleLyricJump(
                                        restoreVisibility = { lyricAlpha.snapTo(1f) },
                                    ) {
                                        if (lyricAlpha.value > 0f) {
                                            lyricAlpha.animateTo(0f, tween(120))
                                        }
                                        // Seeking may continue while the old lyrics fade out.
                                        position = player.currentPosition.coerceAtLeast(0L)
                                        focusIndex = lyricFocusLineIndex(lines, position.toInt())
                                        // A gesture may start during the fade. Leave it in control.
                                        if (!listState.isScrollInProgress) {
                                            // Reset cached per-line springs while transparent.
                                            listState.scrollToItem(focusIndex)
                                            // Publish time before the new renderer can compose.
                                            animatedPosition = position
                                            placementGeneration++
                                            true
                                        } else {
                                            false
                                        }
                                    }
                                }
                                lastFocusIndex = focusIndex
                            }
                            animatedPosition = position
                            if (initialPositionPending || jumped) {
                                initialPositionPending = false
                                // Keep the positioning frame hidden, then fade in while time
                                // updates continue so karaoke highlighting stays responsive.
                                withFrameNanos { }
                                launch { lyricAlpha.animateTo(1f, tween(180)) }
                            }
                            delay(50)
                        }
                    }
                    key(placementGeneration, keepAliveZonePx) {
                        KaraokeLyricsView(
                            listState = listState,
                            lyrics = lyricData.lyricLine,
                            currentPosition = { animatedPosition.toInt() },
                            onLineClicked = { line ->
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
                                .padding(vertical = 8.dp)
                                .graphicsLayer {
                                    alpha = lyricAlpha.value
                                    blendMode = BlendMode.Plus
                                    compositingStrategy = CompositingStrategy.Offscreen
                                },
                            normalLineTextStyle = LocalTextStyle.current.copy(
                                fontSize = normalLyricTextSize.text.sp,
                                fontWeight = if (normalLyricTextBold) FontWeight.Bold else FontWeight.Normal,
                                textMotion = TextMotion.Animated,
                            ),
                            accompanimentLineTextStyle = LocalTextStyle.current.copy(
                                fontSize = accompanimentLyricTextSize.text.sp,
                                fontWeight = if (accompanimentLyricTextBold) FontWeight.Bold else FontWeight.Normal,
                                textMotion = TextMotion.Animated,
                            ),
                            offset = 48.dp,
                            keepAliveZone = keepAliveZone,
                        )
                    }
                }

                LyricSourceBadge(
                    source = lyricData.source,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding( bottom = 8.dp)
                        .onGloballyPositioned { badgeBounds = it.boundsInParent() },
                    onClick = onClick,
                    onLongClick = onLongClick
                )
            }
        }
    }
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

internal fun lyricFocusLineIndex(lines: List<ISyncedLine>, positionMs: Int): Int {
    // Match lyrics-ui's focus rules, including overlapping vocals and interludes.
    val activeIndex = lines.indexOfFirst { line ->
        val effectiveEnd = if (line is KaraokeLine.MainKaraokeLine) {
            maxOf(line.end, line.accompanimentLines?.maxOfOrNull { it.end } ?: line.end)
        } else {
            line.end
        }
        positionMs >= line.start && positionMs < effectiveEnd
    }
    if (activeIndex >= 0) return activeIndex
    val nextIndex = lines.indexOfFirst { it.start > positionMs }
    return if (nextIndex >= 0) nextIndex else lines.lastIndex.coerceAtLeast(0)
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
    // A screen containing N rows from index I ends at I + N - 1.
    return visibleLineCount > 0 && distance >= visibleLineCount
}

@Composable
private fun LyricSourceBadge(
    source: LyricSource,
    modifier: Modifier = Modifier,
    onClick: (LyricSource) -> Unit,
    onLongClick: (LyricSource) -> Unit
) {
    Box(
        modifier = modifier
            .clip(ContinuousRoundedRectangle(4.dp))
            .background(Color.White.copy(alpha = 0.2f))
            .border(0.5.dp, Color.White.copy(alpha = 0.1f), ContinuousRoundedRectangle(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .combinedClickable(
                onClick = { onClick(source) },
                onLongClick = { onLongClick(source) }
            )
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
            modifier = Modifier.size(16.dp),
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.6f)
        )
    }
}
