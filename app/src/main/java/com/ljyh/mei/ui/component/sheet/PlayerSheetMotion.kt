package com.ljyh.mei.ui.component.sheet

import io.github.neoruaa.glasmio.scaffold.GlasmioBottomSheetState
import io.github.neoruaa.glasmio.scaffold.dismissedAnchor

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import androidx.compose.animation.core.spring
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize

internal const val PlayerSheetStiffness = 300f
internal const val PlayerBackgroundHandoffEnd = 0.99f
internal const val PlayerMiniContentFadeEnd = 0.8f
internal const val PlayerPressHighlightFadeEnd = PlayerBackgroundHandoffEnd
internal const val PlayerContentAppearStart = 0.3f
internal const val PlayerContentAppearEnd = 0.9f
internal const val PlayerReleasePositionDamping = 0.9f

internal fun playerSheetSpring() = spring<Dp>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = PlayerSheetStiffness,
)

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

internal fun playerCoverSwipeStartsInside(
    enabled: Boolean,
    position: Offset,
    artwork: Rect,
    clip: Rect?,
    opacity: Float,
): Boolean = enabled && opacity > 0f && !artwork.isEmpty && artwork.contains(position) &&
    (clip == null || clip.contains(position))

/** Keep the page rigid until the user releases the drag. */
internal fun playerClosingDragFrame(host: Rect, progress: Float, travel: Float) = PlayerSheetMotionFrame(
    host.translate(Offset(0f, (1f - progress.coerceIn(0f, 1f)) * travel)), 1f,
)

internal fun playerReleaseFrame(
    start: PlayerSheetMotionFrame,
    target: Rect,
    returning: Boolean,
    remaining: Float,
    top: Float,
): PlayerSheetMotionFrame {
    val fraction = remaining.coerceIn(0f, 1f)
    val bounds = playerContainerRect(target, start.bounds, fraction)
    return PlayerSheetMotionFrame(
        bounds.translate(Offset(0f, top - bounds.top)),
        mix(if (returning) 1f else 0f, start.morphProgress, fraction),
    )
}

/** Size settles independently while the entire shell retains the finger's vertical velocity. */
internal class PlayerSheetRelease {
    private val morph = Animatable(1f)
    private val top = Animatable(0f)
    private var start by mutableStateOf<PlayerSheetMotionFrame?>(null)
    private var target = Rect.Zero
    private var returning = false
    var isRunning by mutableStateOf(false)
        private set
    val frame: PlayerSheetMotionFrame? get() = start?.let {
        playerReleaseFrame(it, target, returning, morph.value, top.value)
    }

    suspend fun settle(start: PlayerSheetMotionFrame, target: Rect, returning: Boolean, velocityY: Float) {
        this.target = target
        this.returning = returning
        morph.snapTo(1f)
        top.snapTo(start.bounds.top)
        this.start = start
        isRunning = true
        try {
            coroutineScope {
                launch {
                    morph.animateTo(0f, spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = PlayerSheetStiffness,
                        visibilityThreshold = 0.0001f,
                    ))
                }
                launch {
                    top.animateTo(target.top, spring(
                        dampingRatio = if (returning) Spring.DampingRatioNoBouncy else PlayerReleasePositionDamping,
                        stiffness = PlayerSheetStiffness,
                        visibilityThreshold = 0.1f,
                    ), initialVelocity = velocityY)
                }
            }
        } finally {
            isRunning = false
        }
    }

    fun reset() { start = null }
}

internal fun playerContainerRect(source: Rect, target: Rect, progress: Float): Rect {
    val p = progress.coerceIn(0f, 1f)
    return Rect(
        mix(source.left, target.left, p), mix(source.top, target.top, p),
        mix(source.right, target.right, p), mix(source.bottom, target.bottom, p),
    )
}

/** Landscape pages retain their layout size while the shell reveals or crops them. */
internal fun playerFullContentRect(container: Rect, host: Rect): Rect {
    val scale = if (host.width > host.height) 1f else container.width / host.width.coerceAtLeast(1f)
    return Rect(container.topLeft, Size(host.width * scale, host.height * scale))
}

internal data class PlayerSheetMotionFrame(val bounds: Rect, val morphProgress: Float) {
    operator fun plus(other: PlayerSheetMotionFrame) = PlayerSheetMotionFrame(
        Rect(bounds.left + other.bounds.left, bounds.top + other.bounds.top,
            bounds.right + other.bounds.right, bounds.bottom + other.bounds.bottom),
        morphProgress + other.morphProgress,
    )
    operator fun minus(other: PlayerSheetMotionFrame) = this + other * -1f
    operator fun times(scale: Float) = PlayerSheetMotionFrame(
        Rect(bounds.left * scale, bounds.top * scale, bounds.right * scale, bounds.bottom * scale),
        morphProgress * scale,
    )
}

/** Select the next direction only at a completed endpoint. */
internal class PlayerSheetMotionPath(initiallyClosing: Boolean) {
    var closing = initiallyClosing
        private set
    private var previousProgress: Float? = null

    fun reset(closing: Boolean, progress: Float) {
        this.closing = closing
        previousProgress = progress
    }

    fun frame(progress: Float, base: (Float, Boolean) -> PlayerSheetMotionFrame): PlayerSheetMotionFrame {
        val p = progress.coerceIn(0f, 1f)
        if ((!closing && previousProgress == 1f && p < 1f) ||
            (closing && previousProgress == 0f && p > 0f)) closing = !closing
        previousProgress = p
        return base(p, closing)
    }
}

internal fun playerClosingCornerRadius(source: Float, screen: Float, progress: Float): Float =
    mix(source, screen, progress.coerceIn(0f, 1f))

/** Preserve the measured leading and top insets as the container moves. */
internal fun playerMiniContentOrigin(
    content: Rect,
    sourceContainer: Rect,
    container: Rect,
): Offset = Offset(
    content.left + container.left - sourceContainer.left,
    content.top + container.top - sourceContainer.top,
)

internal fun playerMiniTrailingOrigin(
    content: Rect,
    sourceContainer: Rect,
    container: Rect,
    rightAnchored: Boolean,
): Offset = Offset(
    if (rightAnchored) container.right - (sourceContainer.right - content.left)
        else container.left + content.left - sourceContainer.left,
    container.top + content.top - sourceContainer.top,
)

internal class PlayerMiniTrailingAnchor {
    private var opening: Boolean? = null

    fun anchorsRight(startProgress: Float): Boolean =
        opening ?: (startProgress < 0.5f).also { opening = it }

    fun reset() { opening = null }
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

internal fun playerContainerAnchoredArtworkRect(
    source: Rect,
    target: Rect,
    sourceContainer: Rect,
    targetContainer: Rect,
    progress: Float,
): Rect {
    val p = progress.coerceIn(0f, 1f)
    val artwork = playerArtworkRect(source, target, p)
    val container = playerContainerRect(sourceContainer, targetContainer, p)
    val left = container.left + mix(
        source.left - sourceContainer.left,
        target.left - targetContainer.left,
        p,
    )
    val top = container.top + mix(
        source.top - sourceContainer.top,
        target.top - targetContainer.top,
        p,
    )
    return Rect(left, top, left + artwork.width, top + artwork.height)
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
