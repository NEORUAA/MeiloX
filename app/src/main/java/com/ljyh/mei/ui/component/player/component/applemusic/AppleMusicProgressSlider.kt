package com.ljyh.mei.ui.component.player.component.applemusic

import android.view.KeyEvent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ljyh.mei.ui.component.player.component.PlayerQualityDropdown
import com.ljyh.mei.ui.local.LocalPlayerConnection
import com.ljyh.mei.ui.theme.LocalInterfaceLetterSpacing
import com.ljyh.mei.utils.TimeUtils.makeTimeString
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlin.math.abs
import kotlin.math.roundToLong

@Composable
internal fun AppleMusicProgressSlider(
    position: Long,
    duration: Long,
    onPositionChange: (Long) -> Unit,
    modifier: Modifier = Modifier,
    onTrackTopChanged: ((Float) -> Unit)? = null,
) {
    val playerConnection = LocalPlayerConnection.current
    val mediaId = playerConnection?.mediaMetadata?.collectAsStateWithLifecycle()?.value?.id
    val musicQuality = playerConnection?.currentMusicQuality?.collectAsStateWithLifecycle()?.value
    val availableQualities = playerConnection?.availableMusicQualities?.collectAsStateWithLifecycle()?.value.orEmpty()
    val isLoadingQualities = playerConnection?.isLoadingMusicQualities?.collectAsStateWithLifecycle()?.value ?: false
    val localPlaybackInfo = playerConnection?.localPlaybackInfo?.collectAsStateWithLifecycle()?.value
    val isPlayingLocalSource = playerConnection?.isPlayingLocalSource?.collectAsStateWithLifecycle()?.value ?: false
    val drag = remember(mediaId) { AppleMusicSeekGesture() }
    val previewFraction = drag.fraction
    var pointerPressed by remember(mediaId) { mutableStateOf(false) }
    var pendingSeek by remember(mediaId) { mutableStateOf<Long?>(null) }
    val durationValid = duration > 0L
    val onSeek by rememberUpdatedState(onPositionChange)
    val layoutDirection = LocalLayoutDirection.current
    val isRtl = layoutDirection == LayoutDirection.Rtl
    val haptics = LocalHapticFeedback.current
    val pressed = durationValid && pointerPressed
    // Flamingo animates each property separately, with its native completion threshold.
    val scale = animateFloatAsState(
        if (pressed) 1.05f else 1f,
        spring(dampingRatio = 0.6f, stiffness = 200f), label = "AppleMusicProgressScale",
    )
    val opacity = animateFloatAsState(
        if (pressed) 0.62f else 0.45f,
        spring(dampingRatio = 0.6f, stiffness = 200f), label = "AppleMusicProgressOpacity",
    )
    val trackOpacity = animateFloatAsState(
        if (pressed) 0.8f else 0.6f,
        spring(dampingRatio = 0.6f, stiffness = 200f), label = "AppleMusicTrackOpacity",
    )
    val thickness = animateDpAsState(
        if (pressed) 12.dp else 7.dp,
        spring(dampingRatio = 0.6f, stiffness = 200f), label = "AppleMusicProgressThickness",
    )
    val density = LocalDensity.current
    var trackCenterInRoot by remember { mutableStateOf<Float?>(null) }
    val trackTopListener by rememberUpdatedState(onTrackTopChanged)
    if (onTrackTopChanged != null) LaunchedEffect(density) {
        snapshotFlow {
            trackCenterInRoot?.let { center ->
                center - with(density) { thickness.value.toPx() } * scale.value / 2f
            }
        }.filterNotNull().distinctUntilChanged().collect { trackTopListener?.invoke(it) }
    }
    LaunchedEffect(pendingSeek) {
        if (pendingSeek != null) {
            // Hold the new position until the playback clock acknowledges the seek.
            delay(1000)
            pendingSeek = null
        }
    }
    val waitingFraction = pendingSeek?.takeIf { abs(position - it) > 1000L }
        ?.let { if (durationValid) it.toFloat() / duration else null }
    val fraction = if (durationValid) {
        previewFraction ?: waitingFraction ?: (position.toDouble() / duration).toFloat().coerceIn(0f, 1f)
    } else 0f
    val displayPosition = if (durationValid) {
        ((previewFraction ?: waitingFraction)?.let { (it * duration.toDouble()).roundToLong() }
            ?: position).coerceIn(0L, duration)
    } else 0L
    fun seekToFraction(value: Float) {
        if (!durationValid) return
        val target = (value.coerceIn(0f, 1f) * duration.toDouble()).roundToLong().coerceIn(0L, duration)
        pendingSeek = target
        onSeek(target)
    }

    Column(modifier) {
        Box(
            modifier = Modifier.fillMaxWidth().height(20.dp)
                .semantics {
                    progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                    if (!durationValid) disabled()
                    setProgress { value ->
                        if (!durationValid) false else {
                            seekToFraction(value)
                            true
                        }
                    }
                }
                .onKeyEvent { event ->
                    if (!durationValid || event.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) false
                    else {
                        val direction = if (isRtl) -1f else 1f
                        val target = when (event.nativeKeyEvent.keyCode) {
                            KeyEvent.KEYCODE_DPAD_LEFT -> fraction - 0.05f * direction
                            KeyEvent.KEYCODE_DPAD_RIGHT -> fraction + 0.05f * direction
                            KeyEvent.KEYCODE_MOVE_HOME -> 0f
                            KeyEvent.KEYCODE_MOVE_END -> 1f
                            else -> return@onKeyEvent false
                        }
                        seekToFraction(target)
                        true
                    }
                }
                .focusable(enabled = durationValid)
                .pointerInput(mediaId, duration) {
                    if (!durationValid) return@pointerInput
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        pointerPressed = true
                        try {
                            do {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                            } while (event.changes.any { it.pressed })
                        } finally {
                            pointerPressed = false
                        }
                    }
                }
                .pointerInput(mediaId, duration, isRtl) {
                    if (!durationValid) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart = { start ->
                            pendingSeek = null
                            drag.start(start.x, size.width.toFloat(), isRtl)
                        },
                        onHorizontalDrag = { change, _ ->
                            change.consume()
                            drag.update(change.position.x, size.width.toFloat(), isRtl)
                        },
                        onDragEnd = {
                            drag.finish()?.let {
                                seekToFraction(it)
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                        },
                        onDragCancel = drag::cancel,
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxWidth().height(14.dp)
                .onGloballyPositioned { trackCenterInRoot = it.boundsInRoot().center.y }
                .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
                alpha = trackOpacity.value.coerceIn(0f, 1f)
            }) {
                val trackHeight = thickness.value.toPx().coerceIn(0f, size.height)
                val top = (size.height - trackHeight) / 2f
                val outline = Path().apply {
                    addRoundRect(RoundRect(Rect(0f, top, size.width, top + trackHeight), CornerRadius(trackHeight / 2f)))
                }
                // Clip once: the active segment ends flat, even near either round outer edge.
                clipPath(outline) {
                    drawRect(Color.White.copy(alpha = 0.5f), Offset(0f, top), Size(size.width, trackHeight))
                    val activeWidth = size.width * fraction.coerceIn(0f, 1f)
                    if (activeWidth > 0f) drawRect(
                        Color.White,
                        Offset(if (isRtl) size.width - activeWidth else 0f, top),
                        Size(activeWidth, trackHeight),
                    )
                }
            }
        }

        val textStyle = MaterialTheme.typography.labelSmall.copy(
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = LocalInterfaceLetterSpacing.current,
        )
        val timeTextStyle = textStyle.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Box(
            modifier = Modifier.fillMaxWidth()
                .padding(horizontal = 1.dp)
                .heightIn(min = 22.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                    alpha = opacity.value.coerceIn(0f, 1f)
                },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = makeTimeString(displayPosition),
                    style = timeTextStyle,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (durationValid) "-${makeTimeString(duration - displayPosition)}" else "-:--",
                    style = timeTextStyle,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End,
                )
            }
            PlayerQualityDropdown(
                quality = musicQuality,
                availableQualities = availableQualities,
                isLoading = isLoadingQualities,
                localPlaybackInfo = localPlaybackInfo,
                isPlayingLocalSource = isPlayingLocalSource,
                onMenuOpened = { playerConnection?.refreshAvailableMusicQualities() },
                onQualitySelected = { playerConnection?.changeQuality(it) },
                onLocalSourceSelected = { playerConnection?.useLocalSource() },
                style = textStyle.copy(fontSize = 11.sp),
                color = Color.White.copy(alpha = 0.45f),
                backgroundColor = Color(0x14FFFFFF),
                cornerRadius = 5.dp,
            )
        }
    }
}

/** Map the finger to the full rail, with no Material thumb inset or endpoint gap. */
internal fun appleMusicSeekFraction(x: Float, width: Float, isRtl: Boolean): Float {
    if (width <= 0f || !width.isFinite() || !x.isFinite()) return 0f
    val fraction = (x / width).coerceIn(0f, 1f)
    return if (isRtl) 1f - fraction else fraction
}

/** Own one drag preview; only a completed drag may produce a seek. */
internal class AppleMusicSeekGesture {
    var fraction by mutableStateOf<Float?>(null)
        private set

    fun start(x: Float, width: Float, isRtl: Boolean) {
        fraction = appleMusicSeekFraction(x, width, isRtl)
    }

    fun update(x: Float, width: Float, isRtl: Boolean) {
        if (fraction != null) fraction = appleMusicSeekFraction(x, width, isRtl)
    }

    fun finish(): Float? = fraction.also { fraction = null }

    fun cancel() {
        fraction = null
    }
}
