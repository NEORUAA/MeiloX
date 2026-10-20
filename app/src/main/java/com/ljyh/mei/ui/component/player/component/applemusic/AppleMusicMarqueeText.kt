package com.ljyh.mei.ui.component.player.component.applemusic

import androidx.compose.foundation.MarqueeSpacing
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset

@Composable
internal fun AppleMusicMarqueeText(
    text: String,
    style: TextStyle,
    color: Color,
    edgeWidth: Dp,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val edgeWidthPx = with(density) { edgeWidth.toPx() }
    key(text) {
        var viewportWidth by remember { mutableIntStateOf(0) }
        var textWidth by remember { mutableIntStateOf(0) }
        Text(
            text = text,
            style = style,
            color = color,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
            onTextLayout = { textWidth = it.size.width },
            modifier = modifier
                .fillMaxWidth()
                .layout { measurable, constraints ->
                    // Let moving text enter the fade without shifting the resting text.
                    val inset = edgeWidth.roundToPx()
                    val placeable = measurable.measure(constraints.offset(horizontal = inset))
                    layout(constraints.constrainWidth(placeable.width - inset), placeable.height) {
                        if (constraints.maxWidth > 0) placeable.placeRelative(-inset, 0)
                    }
                }
                .onSizeChanged { viewportWidth = it.width }
                .graphicsLayer {
                    // Only the fade mask needs an isolated destination. Use the layer's
                    // current size so resizing cannot leave DstIn on an unisolated layer.
                    compositingStrategy = if (size.width > 0f && textWidth + edgeWidthPx > size.width) {
                        CompositingStrategy.Offscreen
                    } else CompositingStrategy.Auto
                    clip = true
                }
                .drawWithCache {
                    val edge = if (size.width > 0f) {
                        (edgeWidth.toPx() / size.width).coerceIn(0f, 0.5f)
                    } else 0f
                    val mask = Brush.horizontalGradient(
                        0f to Color.Transparent,
                        edge / 4f to Color.Black.copy(alpha = 0.17f),
                        edge / 2f to Color.Black.copy(alpha = 0.35f),
                        edge * 0.75f to Color.Black.copy(alpha = 0.7f),
                        edge to Color.Black,
                        1f - edge to Color.Black,
                        1f - edge * 0.75f to Color.Black.copy(alpha = 0.7f),
                        1f - edge / 2f to Color.Black.copy(alpha = 0.35f),
                        1f - edge / 4f to Color.Black.copy(alpha = 0.17f),
                        1f to Color.Transparent,
                    )
                    onDrawWithContent {
                        drawContent()
                        if (size.width > 0f && textWidth + edgeWidthPx > size.width) {
                            drawRect(mask, blendMode = BlendMode.DstIn)
                        }
                    }
                }
                .basicMarquee(
                    iterations = if (enabled && viewportWidth > 0) Int.MAX_VALUE else 0,
                    repeatDelayMillis = 3_000,
                    initialDelayMillis = 3_000,
                    // The leading padding is repeated too; keep the visible text gap at 30dp.
                    spacing = MarqueeSpacing(30.dp - edgeWidth),
                    velocity = with(density) { viewportWidth.toDp() / 13.5f }.coerceAtLeast(1.dp),
                )
                .padding(start = edgeWidth),
        )
    }
}
