package com.ljyh.mei.ui.component.player.component.applemusic

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.EaseOutQuad
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.ljyh.mei.ui.glass.SfIcon
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

internal fun appleMusicPlaybackSpec(isPlaying: Boolean) = if (isPlaying) {
    spring(dampingRatio = 0.65f, stiffness = 150f, visibilityThreshold = 0.001f)
} else {
    tween<Float>(durationMillis = 480, easing = CubicBezierEasing(0.25f, 1f, 0.5f, 1f))
}

@Composable
internal fun rememberAppleMusicPlaybackScale(isPlaying: Boolean, expanded: Boolean): State<Float> {
    // Normalize the motion so Flamingo's completion threshold survives our smaller size change.
    val pausedFraction = animateFloatAsState(
        targetValue = if (isPlaying) 0f else 1f,
        animationSpec = if (expanded) appleMusicPlaybackSpec(isPlaying) else snap(),
        label = "AppleMusicPlaybackFraction",
    )
    return remember(pausedFraction) { derivedStateOf { 1f - 0.25f * pausedFraction.value } }
}

@Composable
internal fun AppleMusicPlayPauseIcon(systemName: String) {
    AppleMusicPlayPauseMotionContent(systemName, Modifier.size(44.dp)) { icon ->
        SfIcon(
            systemName = icon,
            contentDescription = null,
            tint = Color.White,
            size = 44.dp,
            modifier = if (icon == "play.fill") Modifier.offset(x = 2.dp) else Modifier,
        )
    }
}

@Composable
internal fun AppleMusicPlayPauseMotionContent(
    systemName: String,
    modifier: Modifier = Modifier,
    content: @Composable (String) -> Unit,
) {
    val motion = remember { AppleMusicPlayPauseMotion(systemName) }
    LaunchedEffect(systemName) { motion.animateTo(systemName) }
    Box(
        modifier.graphicsLayer {
            scaleX = motion.scale.value
            scaleY = motion.scale.value
        },
        contentAlignment = Alignment.Center,
    ) {
        motion.layers.forEach { layer ->
            Box(Modifier.graphicsLayer { alpha = layer.opacity.value }) {
                content(layer.icon)
            }
        }
    }
}

internal class AppleMusicPlayPauseLayer(val icon: String, initialOpacity: Float) {
    val opacity = Animatable(initialOpacity)
}

internal class AppleMusicPlayPauseMotion(initialIcon: String) {
    var layers by mutableStateOf(listOf(AppleMusicPlayPauseLayer(initialIcon, 1f)))
        private set
    val scale = Animatable(1f)

    suspend fun animateTo(targetIcon: String) {
        if (layers.size == 1 && layers.single().icon == targetIcon && scale.value == 1f) return
        val incoming = layers.find { it.icon == targetIcon }
            ?: AppleMusicPlayPauseLayer(targetIcon, 0f).also { layers = layers + it }
        // Keep both glyphs mounted: the incoming shape appears during contraction.
        // Reversals reuse each layer's current alpha and the shared current scale.
        coroutineScope {
            launch {
                scale.animateTo(0.3f, tween(60, easing = EaseOutQuad))
                scale.animateTo(1f, tween(220, easing = EaseOutQuad))
            }
            layers.forEach { layer ->
                launch {
                    if (layer === incoming) {
                        layer.opacity.animateTo(
                            maxOf(0.5f, layer.opacity.value),
                            tween(60, delayMillis = 20, easing = LinearEasing),
                        )
                        layer.opacity.animateTo(1f, tween(220, easing = LinearEasing))
                    } else {
                        layer.opacity.animateTo(0f, tween(80, easing = LinearEasing))
                    }
                }
            }
        }
        layers = listOf(incoming)
    }
}

internal const val AppleMusicSkipDurationMillis = 520

internal data class AppleMusicSkipTriangle(
    val position: Float,
    val scale: Float,
    val alpha: Float,
)

internal data class AppleMusicSkipFrame(
    val entering: AppleMusicSkipTriangle,
    val moving: AppleMusicSkipTriangle,
    val exiting: AppleMusicSkipTriangle,
)

// Positions use the resting triangle spacing: left = 0, right = 1.
// Samples follow the first click in VID_20261023_021243.mp4, including its rebound.
private val AppleMusicSkipSamples = listOf(
    0 to AppleMusicSkipFrame(
        AppleMusicSkipTriangle(-0.5f, 0f, 0f),
        AppleMusicSkipTriangle(0f, 1f, 1f),
        AppleMusicSkipTriangle(1f, 1f, 1f),
    ),
    20 to AppleMusicSkipFrame(
        AppleMusicSkipTriangle(-0.492f, 0.1f, 0.28f),
        AppleMusicSkipTriangle(0.111f, 1f, 1f),
        AppleMusicSkipTriangle(1.063f, 0.625f, 0.61f),
    ),
    40 to AppleMusicSkipFrame(
        AppleMusicSkipTriangle(-0.428f, 0.2f, 0.42f),
        AppleMusicSkipTriangle(0.196f, 0.975f, 1f),
        AppleMusicSkipTriangle(1.126f, 0.475f, 0.44f),
    ),
    60 to AppleMusicSkipFrame(
        AppleMusicSkipTriangle(-0.285f, 0.375f, 0.66f),
        AppleMusicSkipTriangle(0.393f, 0.925f, 1f),
        AppleMusicSkipTriangle(1.265f, 0.225f, 0.21f),
    ),
    80 to AppleMusicSkipFrame(
        AppleMusicSkipTriangle(-0.219f, 0.45f, 0.78f),
        AppleMusicSkipTriangle(0.478f, 0.9f, 1f),
        AppleMusicSkipTriangle(1.326f, 0.125f, 0.14f),
    ),
    100 to AppleMusicSkipFrame(
        AppleMusicSkipTriangle(-0.154f, 0.525f, 0.84f),
        AppleMusicSkipTriangle(0.561f, 0.9f, 1f),
        AppleMusicSkipTriangle(1.36f, 0f, 0f),
    ),
    120 to AppleMusicSkipFrame(
        AppleMusicSkipTriangle(-0.154f, 0.525f, 0.84f),
        AppleMusicSkipTriangle(0.561f, 0.9f, 1f),
        AppleMusicSkipTriangle(1.36f, 0f, 0f),
    ),
    160 to AppleMusicSkipFrame(
        AppleMusicSkipTriangle(-0.026f, 0.725f, 0.95f),
        AppleMusicSkipTriangle(0.771f, 0.875f, 1f),
        AppleMusicSkipTriangle(1.36f, 0f, 0f),
    ),
    200 to AppleMusicSkipFrame(
        AppleMusicSkipTriangle(0.049f, 0.875f, 0.99f),
        AppleMusicSkipTriangle(0.922f, 0.9f, 1f),
        AppleMusicSkipTriangle(1.36f, 0f, 0f),
    ),
    260 to AppleMusicSkipFrame(
        AppleMusicSkipTriangle(0.069f, 1f, 1f),
        AppleMusicSkipTriangle(1.031f, 0.95f, 1f),
        AppleMusicSkipTriangle(1.36f, 0f, 0f),
    ),
    320 to AppleMusicSkipFrame(
        AppleMusicSkipTriangle(0.056f, 1f, 1f),
        AppleMusicSkipTriangle(1.043f, 0.975f, 1f),
        AppleMusicSkipTriangle(1.36f, 0f, 0f),
    ),
    420 to AppleMusicSkipFrame(
        AppleMusicSkipTriangle(0.014f, 1f, 1f),
        AppleMusicSkipTriangle(1.028f, 1f, 1f),
        AppleMusicSkipTriangle(1.36f, 0f, 0f),
    ),
    AppleMusicSkipDurationMillis to AppleMusicSkipFrame(
        AppleMusicSkipTriangle(0f, 1f, 1f),
        AppleMusicSkipTriangle(1f, 1f, 1f),
        AppleMusicSkipTriangle(1.36f, 0f, 0f),
    ),
)

internal fun appleMusicSkipFrame(fraction: Float): AppleMusicSkipFrame {
    val millis = fraction.coerceIn(0f, 1f) * AppleMusicSkipDurationMillis
    val next = AppleMusicSkipSamples.indexOfFirst { it.first >= millis }
    if (next == 0) return AppleMusicSkipSamples.first().second
    val (startTime, start) = AppleMusicSkipSamples[next - 1]
    val (endTime, end) = AppleMusicSkipSamples[next]
    val progress = (millis - startTime) / (endTime - startTime)
    fun interpolate(a: AppleMusicSkipTriangle, b: AppleMusicSkipTriangle) = AppleMusicSkipTriangle(
        position = lerp(a.position, b.position, progress),
        scale = lerp(a.scale, b.scale, progress),
        alpha = lerp(a.alpha, b.alpha, progress),
    )
    return AppleMusicSkipFrame(
        entering = interpolate(start.entering, end.entering),
        moving = interpolate(start.moving, end.moving),
        exiting = interpolate(start.exiting, end.exiting),
    )
}

@Composable
internal fun AppleMusicSkipIcon(
    forward: Boolean,
    clickCount: Int,
    size: Dp = 44.dp,
    tint: Color = Color.White,
    weight: FontWeight = FontWeight.Normal,
) {
    val travel = remember { Animatable(clickCount.toFloat()) }
    val frame = remember { derivedStateOf { appleMusicSkipFrame(travel.value % 1f) } }
    LaunchedEffect(clickCount) {
        travel.animateTo(clickCount.toFloat(), tween(AppleMusicSkipDurationMillis, easing = LinearEasing))
    }
    Box(Modifier.size(size).graphicsLayer { scaleX = if (forward) 1f else -1f }) {
        repeat(3) { triangle ->
            // Use whole SF triangles: clipping a double glyph cuts through its tips.
            SfIcon(
                systemName = "play.fill",
                contentDescription = null,
                tint = tint,
                size = size * (24.75f / 44f),
                weight = weight,
                modifier = Modifier
                    .align(Alignment.Center)
                    .graphicsLayer {
                        val pose = when (triangle) {
                            0 -> frame.value.entering
                            1 -> frame.value.moving
                            else -> frame.value.exiting
                        }
                        scaleX = pose.scale
                        scaleY = pose.scale
                        alpha = pose.alpha
                        translationX = (pose.position - 0.5f) * (size * (19.32f / 44f)).toPx()
                    },
            )
        }
    }
}
