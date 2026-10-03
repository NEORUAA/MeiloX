package com.ljyh.mei.ui.component.sheet

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import com.kyant.capsule.ContinuousRoundedRectangle
import com.ljyh.mei.ui.glass.LocalGlassColors
import com.ljyh.mei.ui.glass.GlassMorphRenderer

/** The sheet keeps its approved motion; only the glass renderer is shared with home controls. */
@Composable
internal fun PlayerTransitionGlass(
    layers: PlayerSheetLayers,
    renderer: GlassMorphRenderer,
    expandedCornerRadius: Float,
) {
    val tint = LocalGlassColors.current.container
    val recording = remember(renderer) { SheetGlassCoordinates() }
    Canvas(Modifier.fillMaxSize().onGloballyPositioned { recording.coordinates = it }) {
        val coordinates = recording.coordinates ?: return@Canvas
        layers.updateTransitionFrame()
        val p = layers.state.progress
        if (playerBackgroundAlpha(p) >= 1f) return@Canvas
        val bounds = playerContainerRect(layers.sourceContainer, layers.hostBounds, p)
            .translate(-layers.hostBounds.topLeft)
        val radius = playerContainerCornerRadius(
            layers.sourceContainer.height / 2f, expandedCornerRadius, p,
        ).coerceIn(0f, bounds.size.minDimension / 2f)
        renderer.draw(
            scope = this,
            coordinates = coordinates,
            viewport = Rect(coordinates.positionInRoot(), size),
            origin = coordinates.positionInRoot(),
            bounds = bounds,
            radius = radius,
            shape = ContinuousRoundedRectangle(radius.toDp()),
            tint = tint,
            highlightAlpha = 0.5f * (0.54f + 0.38f * layers.transitionPressHighlight.progress),
        )
    }
}

private class SheetGlassCoordinates {
    var coordinates: LayoutCoordinates? = null
}
