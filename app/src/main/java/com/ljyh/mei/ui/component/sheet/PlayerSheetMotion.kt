package com.ljyh.mei.ui.component.sheet

import io.github.neoruaa.glasmio.scaffold.GlasmioBottomSheetState
import io.github.neoruaa.glasmio.scaffold.dismissedAnchor

import androidx.compose.animation.core.Spring
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
private const val PlayerCollapseTranslationEnd = 0.3f
private const val PlayerCollapseReturnStart = 0.8f
private const val PlayerCollapseTopReturnStart =
    (PlayerCollapseReturnStart - PlayerCollapseTranslationEnd) / (1f - PlayerCollapseTranslationEnd)
private const val PlayerCollapsePortraitTopOvershootFraction = 1f / 4f
private const val PlayerCollapseLandscapeTopOvershootFraction = 1f / 8f

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

internal data class PlayerCollapseFrame(
    val morphProgress: Float,
    val offsetY: Float,
    // Pixels per unit of closing morph progress, matching the preceding rigid translation.
    val topStartTangent: Float = 0f,
)

/** Use 30% of closing progress for rigid translation, then morph from one third-screen down. */
internal fun playerCollapseFrame(
    progress: Float,
    hostHeight: Float,
    anchorTravel: Float,
    closing: Boolean,
): PlayerCollapseFrame {
    val p = progress.coerceIn(0f, 1f)
    if (!closing || hostHeight <= 0f || anchorTravel <= 0f) return PlayerCollapseFrame(p, 0f)
    val rigidDistance = hostHeight / 3f
    val morphStart = 1f - PlayerCollapseTranslationEnd
    val topStartTangent = rigidDistance * morphStart / PlayerCollapseTranslationEnd
    return if (p >= morphStart) {
        PlayerCollapseFrame(1f, (1f - p) / PlayerCollapseTranslationEnd * rigidDistance, topStartTangent)
    } else {
        val morph = p / morphStart
        PlayerCollapseFrame(morph, rigidDistance * morph, topStartTangent)
    }
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

/** Only the closing top edge overshoots; the other three edges keep their path. */
internal fun playerClosingContainerRect(source: Rect, target: Rect, frame: PlayerCollapseFrame): Rect {
    val p = frame.morphProgress
    val linear = playerContainerRect(source, target, p).translate(Offset(0f, frame.offsetY))
    if (p <= 0f || p >= 1f) return linear
    val startTop = target.top + frame.offsetY / p
    val closing = 1f - p
    val overshootFraction = if (target.height >= target.width) {
        PlayerCollapsePortraitTopOvershootFraction
    } else {
        PlayerCollapseLandscapeTopOvershootFraction
    }
    val peakTop = source.top + source.height * overshootFraction
    val top = if (closing < PlayerCollapseTopReturnStart) {
        val u = closing / PlayerCollapseTopReturnStart
        // Cubic Hermite: preserve entry velocity and stop at the small overshoot.
        val smooth = u * u * (3f - 2f * u)
        mix(startTop, peakTop, smooth) +
            frame.topStartTangent * PlayerCollapseTopReturnStart * u * (1f - u) * (1f - u)
    } else {
        val u = (closing - PlayerCollapseTopReturnStart) / (1f - PlayerCollapseTopReturnStart)
        // Zero tangents join the turnaround without a second animation or a pause.
        mix(peakTop, source.top, u * u * (3f - 2f * u))
    }
    return Rect(linear.left, top, linear.right, linear.bottom)
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

/** Rebase only near an endpoint; retain position and tangent across every path change. */
internal class PlayerSheetMotionPath(initiallyClosing: Boolean) {
    var closing = initiallyClosing
        private set
    private var previousProgress: Float? = null
    private var reversal: Reversal? = null
    private data class Reversal(
        val progress: Float,
        val offset: PlayerSheetMotionFrame,
        val lowerTangent: PlayerSheetMotionFrame,
        val upperTangent: PlayerSheetMotionFrame,
    )

    fun reset(closing: Boolean, progress: Float) {
        this.closing = closing
        previousProgress = progress
        reversal = null
    }

    fun frame(progress: Float, base: (Float, Boolean) -> PlayerSheetMotionFrame): PlayerSheetMotionFrame {
        val p = progress.coerceIn(0f, 1f)
        val previous = previousProgress
        if (previous != null && ((!closing && previous >= 0.95f && p < previous) ||
                (closing && previous <= 0.05f && p > previous))) {
            val captured = evaluate(previous, base)
            val lower = (previous - 0.0001f).coerceAtLeast(0f)
            val upper = (previous + 0.0001f).coerceAtMost(1f)
            val tangent = (evaluate(upper, base) - evaluate(lower, base)) * (1f / (upper - lower))
            closing = !closing
            val start = rebasedBase(previous, previous, base)
            val lowerSlope = if (previous > lower) {
                (start - rebasedBase(lower, previous, base)) * (1f / (previous - lower))
            } else tangent
            val upperSlope = if (upper > previous) {
                (rebasedBase(upper, previous, base) - start) * (1f / (upper - previous))
            } else tangent
            reversal = Reversal(previous, captured - start,
                (tangent - lowerSlope) * -previous,
                (tangent - upperSlope) * (1f - previous))
        }
        previousProgress = p
        return evaluate(p, base)
    }

    private fun rebasedBase(p: Float, start: Float, base: (Float, Boolean) -> PlayerSheetMotionFrame) = base(
        if (closing) (p / start.coerceAtLeast(0.000001f)).coerceIn(0f, 1f)
        else ((p - start) / (1f - start).coerceAtLeast(0.000001f)).coerceIn(0f, 1f),
        closing,
    )

    private fun evaluate(p: Float, base: (Float, Boolean) -> PlayerSheetMotionFrame): PlayerSheetMotionFrame {
        val capture = reversal ?: return base(p, closing)
        val upper = p >= capture.progress
        val distance = if (upper) 1f - capture.progress else capture.progress
        val u = (kotlin.math.abs(p - capture.progress) / distance.coerceAtLeast(0.000001f)).coerceIn(0f, 1f)
        val remaining = 1f - u
        val frame = rebasedBase(p, capture.progress, base)
        val offset = capture.offset
        val tangent = if (upper) capture.upperTangent else capture.lowerTangent
        val offsetWeight = remaining * remaining * (1f + 2f * u)
        val tangentWeight = u * remaining * remaining
        return PlayerSheetMotionFrame(
            Rect(
                frame.bounds.left + offset.bounds.left * offsetWeight + tangent.bounds.left * tangentWeight,
                frame.bounds.top + offset.bounds.top * offsetWeight + tangent.bounds.top * tangentWeight,
                frame.bounds.right + offset.bounds.right * offsetWeight + tangent.bounds.right * tangentWeight,
                frame.bounds.bottom + offset.bounds.bottom * offsetWeight + tangent.bounds.bottom * tangentWeight,
            ),
            (frame.morphProgress + offset.morphProgress * offsetWeight + tangent.morphProgress * tangentWeight)
                .coerceIn(0f, 1f),
        )
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
