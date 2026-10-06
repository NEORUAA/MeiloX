package com.ljyh.mei.ui.component.playlist

import androidx.compose.animation.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.ljyh.mei.ui.glass.LocalGlassColors
import com.ljyh.mei.utils.color.ColorExtractionUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs

data class CoverBackground(
    val color: Color,
    val endColor: Color,
    val brush: Brush,
)

@Composable
fun rememberCoverBackground(
    coverUrl: String,
    getCachedColor: (String) -> Color?,
    getOrExtractColor: suspend (String) -> Color,
    ownerKey: Any? = Unit,
): CoverBackground {
    val colors = LocalGlassColors.current
    val currentGetCachedColor by rememberUpdatedState(getCachedColor)
    val currentGetOrExtractColor by rememberUpdatedState(getOrExtractColor)
    var coverColor by remember(ownerKey, coverUrl) {
        mutableStateOf(currentGetCachedColor(coverUrl)?.takeUnless { it == Color.Black })
    }
    LaunchedEffect(ownerKey, coverUrl) {
        if (coverUrl.isBlank()) return@LaunchedEffect
        try {
            coverColor = currentGetOrExtractColor(coverUrl).takeUnless { it == Color.Black }
        } catch (cause: CancellationException) {
            throw cause
        } catch (_: Exception) {
            // Keep the cached seed or the regular page background if extraction fails.
        }
    }
    val targetBackground = remember(coverColor, colors.isDark, colors.groupedBackground) {
        coverColor?.let {
            ColorExtractionUtils.detailBackgroundColor(it, colors.isDark)
        } ?: colors.groupedBackground
    }
    val targetBackgroundEnd = remember(targetBackground, coverColor) {
        if (coverColor == null) targetBackground
        else ColorExtractionUtils.detailBackgroundEndColor(targetBackground)
    }
    // Animate cached seeds from the neutral surface too; reset on theme changes.
    val backgroundAnimation = remember(ownerKey, coverUrl, colors.isDark) {
        Animatable(colors.groupedBackground)
    }
    val backgroundEndAnimation = remember(ownerKey, coverUrl, colors.isDark) {
        Animatable(colors.groupedBackground)
    }
    LaunchedEffect(backgroundAnimation, backgroundEndAnimation, targetBackground, targetBackgroundEnd) {
        coroutineScope {
            launch { backgroundAnimation.animateTo(targetBackground, tween(600)) }
            launch { backgroundEndAnimation.animateTo(targetBackgroundEnd, tween(600)) }
        }
    }
    val pageBackground = backgroundAnimation.value
    val animatedBackgroundEnd = backgroundEndAnimation.value
    // Near black, a single 8-bit channel step can exceed 10% relative luminance.
    val pageBackgroundEnd = if (
        abs(animatedBackgroundEnd.luminance() - pageBackground.luminance()) <=
        pageBackground.luminance() * 0.1f
    ) animatedBackgroundEnd else pageBackground
    val brush = remember(pageBackground, pageBackgroundEnd) {
        Brush.verticalGradient(listOf(pageBackground, pageBackgroundEnd))
    }
    return CoverBackground(pageBackground, pageBackgroundEnd, brush)
}

@Composable
fun PlaylistBackground(coverUrl: String?) {
    Box(modifier = Modifier.fillMaxSize()) {
        if (coverUrl != null) {
            AsyncImage(
                model = coverUrl,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(50.dp)
                    .matchParentSize(),
                contentScale = ContentScale.Crop,
                alpha = 0.6f
            )
            // Gradient Overlay for readability
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.surface.copy(alpha = 0.3f),
                                MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                                MaterialTheme.colorScheme.surface
                            )
                        )
                    )
            )
        }
    }
}
