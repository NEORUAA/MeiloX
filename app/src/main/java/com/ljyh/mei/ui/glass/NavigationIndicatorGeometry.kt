package com.ljyh.mei.ui.glass

import androidx.compose.ui.unit.dp

internal val NavigationIndicatorRefractionHeight = 10.dp
internal val NavigationIndicatorRefractionAmount = 14.dp

internal fun navigationPressScale(width: Float, expansion: Float, press: Float): Float =
    1f + expansion * press / width.coerceAtLeast(1f)

/** The live lens, not the committed route, determines which tab is anchored. */
internal fun navigationTabPanelScale(index: Int, lensPosition: Float, panelScale: Float): Float {
    val distance = kotlin.math.abs(lensPosition - index).coerceIn(0f, 1f)
    val follow = distance * distance * (3f - 2f * distance)
    return 1f + (panelScale - 1f) * follow
}

/** Compensate the row's parent scale to match the independent selected overlay. */
internal fun navigationTabPressOffset(center: Float, panelCenter: Float, panelScale: Float, tabScale: Float): Float =
    (center - panelCenter) * (tabScale / panelScale - 1f)

/** The material source follows the inner bar height, independently of the press scale. */
internal fun navigationLensSourceHeight(surfaceHeight: Float, inset: Float): Float =
    (surfaceHeight - 2f * inset).coerceAtLeast(1f)
