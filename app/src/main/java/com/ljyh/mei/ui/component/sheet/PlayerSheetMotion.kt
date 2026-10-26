package com.ljyh.mei.ui.component.sheet

import io.github.neoruaa.glasmio.scaffold.GlasmioBottomSheetState
import io.github.neoruaa.glasmio.scaffold.dismissedAnchor

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize

internal const val PlayerSheetStiffness = 300f
internal const val PlayerBackgroundHandoffEnd = 0.99f
internal const val PlayerMiniContentFadeEnd = 0.8f
internal const val PlayerPressHighlightFadeEnd = PlayerBackgroundHandoffEnd
internal const val PlayerContentAppearStart = 0.3f
internal const val PlayerContentAppearEnd = 0.9f

internal fun playerSheetSpring() = spring<Dp>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = PlayerSheetStiffness,
)

@Composable
internal fun rememberMiniPlayerCompactProgress(compact: Boolean, sheet: GlasmioBottomSheetState): State<Float> {
    val target = if (compact) 1f else 0f
    val animation = remember { Animatable(target) }
    val playerVisible by remember(sheet) { derivedStateOf { sheet.isContentVisible } }
    LaunchedEffect(target, playerVisible) {
        // The opening capture keeps its visible origin. Settle the hidden mini player
        // at the navigation's destination before it becomes the return endpoint.
        if (playerVisible) animation.snapTo(target)
        else animation.animateTo(target, spring(stiffness = Spring.StiffnessMediumLow))
    }
    return animation.asState()
}

internal fun GlasmioBottomSheetState.syncPlaybackVisibility(hasCurrentMediaItem: Boolean) {
    // Restoration can finish before dismissal settles. Reverse its target instead
    // of waiting for isDismissed, while preserving expansion and user gestures.
    if (hasCurrentMediaItem) {
        if (targetAnchor == dismissedAnchor) collapseSoft()
    } else if (targetAnchor != dismissedAnchor) {
        dismiss()
    }
}

internal fun easeInCubic(value: Float): Float = value.coerceIn(0f, 1f).let { it * it * it }
internal fun easeOutCubic(value: Float): Float = 1f - easeInCubic(1f - value)
internal fun playerBackgroundAlpha(progress: Float) = easeInCubic(progress / PlayerBackgroundHandoffEnd)
internal fun playerPressHighlightAlpha(progress: Float) = 1f - easeOutCubic(progress / PlayerPressHighlightFadeEnd)
internal fun playerPressHighlightRect(sourceSize: Size, container: Rect) = Rect(container.topLeft, sourceSize)
internal fun playerMiniContentAlpha(progress: Float) = 1f - easeOutCubic(progress / PlayerMiniContentFadeEnd)
internal fun playerContentAlpha(progress: Float) = easeInCubic(
    (progress - PlayerContentAppearStart) / (PlayerContentAppearEnd - PlayerContentAppearStart),
)

internal fun playerContainerRect(source: Rect, target: Rect, progress: Float): Rect {
    val p = progress.coerceIn(0f, 1f)
    return Rect(
        mix(source.left, target.left, p), mix(source.top, target.top, p),
        mix(source.right, target.right, p), mix(source.bottom, target.bottom, p),
    )
}

internal fun playerContainerCornerRadius(source: Float, target: Float, progress: Float): Float =
    if (progress >= 1f) 0f else mix(source, target, progress.coerceAtLeast(0f))

internal fun playerArtworkRect(source: Rect, target: Rect, progress: Float): Rect {
    val p = progress.coerceIn(0f, 1f)
    val centerX = mix(source.center.x, target.center.x, easeOutCubic(p))
    val centerY = mix(source.center.y, target.center.y, mix(easeInCubic(p), p, 0.4f))
    val scale = mix(1f, target.width / source.width.coerceAtLeast(1f), p)
    val width = source.width * scale
    val height = mix(source.height, target.height, p)
    return Rect(centerX - width / 2, centerY - height / 2, centerX + width / 2, centerY + height / 2)
}

internal fun playerTopAnchoredArtworkRect(
    source: Rect,
    target: Rect,
    sourceContainer: Rect,
    targetContainer: Rect,
    progress: Float,
): Rect {
    val p = progress.coerceIn(0f, 1f)
    val artwork = playerArtworkRect(source, target, p)
    val container = playerContainerRect(sourceContainer, targetContainer, p)
    val top = container.top + mix(
        source.top - sourceContainer.top,
        target.top - targetContainer.top,
        p,
    )
    return Rect(artwork.left, top, artwork.right, top + artwork.height)
}

internal fun playerDragOpens(velocityY: Float, lastDeltaY: Float, originOpen: Boolean): Boolean = when {
    velocityY < 0f -> true
    velocityY > 0f -> false
    lastDeltaY < 0f -> true
    lastDeltaY > 0f -> false
    else -> originOpen
}

internal fun playerDragValue(value: Float, deltaPx: Float, density: Float, collapsed: Float, expanded: Float): Float =
    (value - deltaPx / density).coerceIn(collapsed, expanded.coerceAtLeast(collapsed))

internal fun Rect.isUsable() = width > 0f && height > 0f

internal data class PlayerArtworkGeometry(
    val bounds: Rect,
    val layoutSize: IntSize,
    val opacity: Float = 1f,
    val clipBounds: Rect? = null,
)

/** The mini-player stays opaque while an offscreen expanded endpoint can disappear. */
internal fun playerArtworkOpacity(targetOpacity: Float, progress: Float): Float =
    mix(1f, targetOpacity.coerceIn(0f, 1f), progress.coerceIn(0f, 1f))

/** Expand the viewport continuously as the shared cover returns to the mini-player. */
internal fun playerArtworkClipRect(targetClip: Rect, host: Rect, progress: Float): Rect =
    playerContainerRect(host, targetClip, progress).translate(-host.topLeft)

internal fun playerArtworkTargetGeometry(
    explicitGeometry: PlayerArtworkGeometry?,
    host: Rect,
    playbackScale: Float? = null,
    measuredGeometry: () -> PlayerArtworkGeometry,
): PlayerArtworkGeometry = explicitGeometry?.let {
    // Explicit visual bounds are host-local; logical image size is never visual size.
    it.copy(
        bounds = it.bounds.translate(host.topLeft),
        clipBounds = it.clipBounds?.translate(host.topLeft),
    )
} ?: measuredGeometry().let { measured ->
    if (playbackScale == null || !measured.bounds.isUsable() ||
        measured.layoutSize.width <= 0 || measured.layoutSize.height <= 0
    ) return@let measured
    // Hidden layer transforms may retain an old playback scale; the center is stable.
    val halfWidth = measured.layoutSize.width * playbackScale / 2f
    val halfHeight = measured.layoutSize.height * playbackScale / 2f
    val center = measured.bounds.center
    measured.copy(bounds = Rect(
        center.x - halfWidth, center.y - halfHeight,
        center.x + halfWidth, center.y + halfHeight,
    ))
}

internal fun playerSharesArtwork(
    transitioning: Boolean,
    enabled: Boolean,
    sourceAvailable: Boolean,
    source: Rect,
    target: Rect,
    host: Rect,
): Boolean = transitioning && enabled && sourceAvailable &&
    source.isUsable() && target.isUsable() && host.isUsable()

internal fun mix(start: Float, end: Float, progress: Float) = start + (end - start) * progress


internal class PlayerPressRelease {
    private var previousProgress = 0f
    private var remaining = 1f

    fun fraction(progress: Float): Float = if (previousProgress > 0f) {
        remaining * (progress / previousProgress).coerceIn(0f, 1f)
    } else remaining

    fun update(progress: Float) {
        remaining = fraction(progress)
        previousProgress = progress
    }

    fun reset() {
        previousProgress = 0f
        remaining = 1f
    }
}

internal fun playerUntransformRect(rect: Rect, visual: Rect, layout: Rect): Rect {
    if (!visual.isUsable()) return rect
    return Rect(
        layout.left + (rect.left - visual.left) * layout.width / visual.width,
        layout.top + (rect.top - visual.top) * layout.height / visual.height,
        layout.left + (rect.right - visual.left) * layout.width / visual.width,
        layout.top + (rect.bottom - visual.top) * layout.height / visual.height,
    )
}

/** Return directly to the settled layout, retaining continuity for an interrupted opening. */
internal fun playerMiniTransitionBounds(
    capturedBounds: Rect,
    capturedContainer: Rect?,
    pressedContainer: Rect?,
    currentBounds: Rect,
    openingFraction: Float,
    progress: Float,
): Rect {
    if (capturedContainer == null) return currentBounds
    val opening = pressedContainer?.let {
        playerUntransformRect(capturedBounds, capturedContainer, it)
    } ?: capturedBounds
    val destination = currentBounds.takeIf { it.isUsable() } ?: capturedBounds
    val p = progress.coerceIn(0f, 1f)
    // The shell already interpolates this endpoint toward the full player by p.
    // Remove that interpolation from the remaining opening fraction: a full-player
    // return uses only the final destination, while an early reversal stays continuous.
    val originWeight = if (p < 1f) ((openingFraction - p) / (1f - p)).coerceIn(0f, 1f) else 0f
    return playerContainerRect(destination, opening, originWeight)
}

internal fun playerMiniLayoutRect(
    visualContainer: Rect?,
    localPressedBounds: Rect,
    layoutSize: IntSize,
    fallback: Rect,
): Rect = if (visualContainer?.isUsable() == true && localPressedBounds.isUsable() &&
    layoutSize.width > 0 && layoutSize.height > 0
) {
    playerUntransformRect(
        Rect(0f, 0f, layoutSize.width.toFloat(), layoutSize.height.toFloat()),
        localPressedBounds,
        visualContainer,
    )
} else fallback
