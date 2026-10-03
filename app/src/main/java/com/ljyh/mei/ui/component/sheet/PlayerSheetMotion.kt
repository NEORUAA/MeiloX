package com.ljyh.mei.ui.component.sheet

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Dp

internal const val PlayerSheetStiffness = 300f
internal const val PlayerBackgroundHandoffEnd = 0.99f
internal const val PlayerMiniContentFadeEnd = 0.8f
internal const val PlayerPressHighlightFadeEnd = 0.1f
internal const val PlayerContentAppearStart = 0.3f
internal const val PlayerContentAppearEnd = 0.9f

internal fun playerSheetSpring() = spring<Dp>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = PlayerSheetStiffness,
)

internal fun easeInCubic(value: Float): Float = value.coerceIn(0f, 1f).let { it * it * it }
internal fun easeOutCubic(value: Float): Float = 1f - easeInCubic(1f - value)
internal fun playerBackgroundAlpha(progress: Float) = easeInCubic(progress / PlayerBackgroundHandoffEnd)
internal fun playerPressHighlightAlpha(progress: Float) = 1f - easeOutCubic(progress / PlayerPressHighlightFadeEnd)
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
