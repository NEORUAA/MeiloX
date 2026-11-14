package com.ljyh.mei.ui.component.shimmer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import com.valentinilk.shimmer.defaultShimmerTheme
import kotlinx.coroutines.isActive

@Stable
class ShimmerProgress internal constructor() {
    internal var activeHosts by mutableIntStateOf(0)
    internal val fraction = mutableFloatStateOf(0f)
}

val LocalShimmerProgress = staticCompositionLocalOf<ShimmerProgress?> { null }

/** A shared phase advances only while at least one skeleton is composed. */
@Composable
fun rememberShimmerProgress(): ShimmerProgress {
    val progress = remember { ShimmerProgress() }
    val active = progress.activeHosts > 0
    LaunchedEffect(progress, active) {
        progress.fraction.floatValue = 0f
        if (!active) return@LaunchedEffect
        val started = withFrameNanos { it }
        while (isActive) {
            withFrameNanos { frame ->
                val elapsedMillis = (frame - started) / 1_000_000L
                val cycleMillis = elapsedMillis % 1_050L
                progress.fraction.floatValue = ((cycleMillis - 250L) / 800f).coerceIn(0f, 1f)
            }
        }
    }
    return progress
}

@Composable
fun ShimmerHost(
    modifier: Modifier = Modifier,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable ColumnScope.() -> Unit,
) {
    val progress = LocalShimmerProgress.current ?: rememberShimmerProgress()
    DisposableEffect(progress) {
        progress.activeHosts++
        onDispose { progress.activeHosts-- }
    }
    Column(
        horizontalAlignment = horizontalAlignment,
        verticalArrangement = verticalArrangement,
        modifier = modifier
            .graphicsLayer(alpha = 0.99f)
            .drawWithContent {
                drawContent()
                val travel = size.width.coerceAtLeast(1f) * 2.4f
                val center = -size.width * 0.7f + travel * progress.fraction.floatValue
                drawRect(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.25f),
                            Color.Black.copy(alpha = 0.50f),
                            Color.Black.copy(alpha = 0.25f),
                        ),
                        start = Offset(center - size.width * 0.35f, 0f),
                        end = Offset(center + size.width * 0.35f, 0f),
                    ),
                    blendMode = BlendMode.DstIn,
                )
                drawRect(
                    brush = Brush.verticalGradient(listOf(Color.Black, Color.Transparent)),
                    blendMode = BlendMode.DstIn
                )
            },
        content = content
    )
}

val ShimmerTheme = defaultShimmerTheme.copy(
    animationSpec = infiniteRepeatable(
        animation = tween(
            durationMillis = 800,
            easing = LinearEasing,
            delayMillis = 250,
        ),
        repeatMode = RepeatMode.Restart
    ),
    shaderColors = listOf(
        Color.Unspecified.copy(alpha = 0.25f),
        Color.Unspecified.copy(alpha = 0.50f),
        Color.Unspecified.copy(alpha = 0.25f),
    ),
)
