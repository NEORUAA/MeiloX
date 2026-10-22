package com.ljyh.mei.ui.glass

import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Size

internal val NavigationIndicatorRefractionHeight = 10.dp
internal val NavigationIndicatorRefractionAmount = 14.dp

internal val SegmentedIndicatorHeight = 28.dp
internal val SegmentedIndicatorRefractionHeight = 5.dp
internal val SegmentedIndicatorRefractionAmount = 7.dp
internal val SegmentedIndicatorHorizontalExpansion = 16.dp
internal const val SegmentedIndicatorPressedScale = 39f / 28f

/** Measure the deformed surface before resolving Capsule and its optical outline. */
internal fun segmentedIndicatorSize(
    restingSize: Size,
    scaleX: Float,
    scaleY: Float,
    velocity: Float,
    expansion: Float,
): Size {
    val horizontalProgress = (scaleX - 1f) / (SegmentedIndicatorPressedScale - 1f)
    return Size(
        ((restingSize.width + expansion * horizontalProgress) /
            (1f - (velocity * 0.75f).coerceIn(-0.18f, 0.18f))).coerceAtLeast(1f),
        (restingSize.height * scaleY *
            (1f - (velocity * 0.25f).coerceIn(-0.14f, 0.14f))).coerceAtLeast(1f),
    )
}

internal fun navigationPressScale(width: Float, expansion: Float, press: Float): Float =
    1f + expansion * press / width.coerceAtLeast(1f)

/** The live lens, not the committed route, determines which tab is anchored. */
internal fun navigationTabPanelScale(
    index: Int,
    lensPosition: Float,
    panelScale: Float,
    lensAnchoring: Float = 1f,
): Float {
    val distance = kotlin.math.abs(lensPosition - index).coerceIn(0f, 1f)
    val follow = distance * distance * (3f - 2f * distance)
    return panelScale - (panelScale - 1f) * (1f - follow) * lensAnchoring
}

internal fun navigationPanelCenterOffset(center: Float, panelCenter: Float, scale: Float): Float =
    (center - panelCenter) * (scale - 1f)

/** Compensate the row's parent scale to match the independent selected overlay. */
internal fun navigationTabPressOffset(center: Float, panelCenter: Float, panelScale: Float, tabScale: Float): Float =
    (center - panelCenter) * (tabScale / panelScale - 1f)

/** The material source follows the inner bar height, independently of the press scale. */
internal fun navigationLensSourceHeight(surfaceHeight: Float, inset: Float): Float =
    (surfaceHeight - 2f * inset).coerceAtLeast(1f)
