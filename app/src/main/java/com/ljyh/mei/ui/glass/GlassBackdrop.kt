package com.ljyh.mei.ui.glass

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.unit.Density
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import java.util.WeakHashMap

val LocalGlassBackdrop = staticCompositionLocalOf<Backdrop> {
    error("Glass controls must be hosted by GlassBackdropHost or GlassBackdropProvider")
}

/** Backdrop source reserved for modal surfaces that sample the rendered page. */
val LocalBlurBackdrop = staticCompositionLocalOf<Backdrop> {
    error("Blur glass must be hosted by the app backdrop provider")
}

@Composable
fun GlassBackdropProvider(
    backdrop: Backdrop,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalGlassBackdrop provides backdrop,
        LocalBlurBackdrop provides backdrop,
        content = content,
    )
}

/**
 * Keeps sampled content and glass overlays in separate layers. Glass controls must never be
 * placed inside [sampledContent], otherwise the backdrop can recursively sample itself.
 */
@Composable
fun GlassBackdropHost(
    modifier: Modifier = Modifier,
    sampledContent: @Composable BoxScope.(LayerBackdrop) -> Unit,
    overlayContent: @Composable BoxScope.(LayerBackdrop) -> Unit,
) {
    val backdrop = rememberLayerBackdrop()
    CompositionLocalProvider(
        LocalGlassBackdrop provides backdrop,
        LocalBlurBackdrop provides backdrop,
    ) {
        Box(modifier) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .layerBackdrop(backdrop),
            ) {
                sampledContent(backdrop)
            }
            overlayContent(backdrop)
        }
    }
}

fun Modifier.glassBackdropSource(backdrop: LayerBackdrop): Modifier = layerBackdrop(backdrop)

/** Window-space positions of backdrop source layers, for cross-window sampling. */
private val backdropSourcePositions = WeakHashMap<LayerBackdrop, LayoutCoordinates>()

/** Attach next to a [layerBackdrop] recording so dialog-window glass can locate the source. */
fun Modifier.trackBackdropPosition(backdrop: LayerBackdrop): Modifier =
    onGloballyPositioned { coordinates ->
        if (coordinates.isAttached) {
            backdropSourcePositions[backdrop] = coordinates
        }
    }

/**
 * Samples a [LayerBackdrop] that was recorded in another window (e.g. app content from a
 * dialog/sheet window). LayerBackdrop maps coordinates with localPositionOf, which cannot
 * cross compose owners and silently yields a wrong offset there, so glass in a dialog
 * samples nothing. Both windows share the screen origin, making window-space subtraction
 * the correct mapping.
 */
class CrossWindowBackdrop(
    private val source: LayerBackdrop,
) : Backdrop {

    override val isCoordinatesDependent: Boolean get() = true

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?,
    ) {
        val glassCoordinates = coordinates ?: return
        if (!glassCoordinates.isAttached) return
        val sourceCoordinates = backdropSourcePositions[source]?.takeIf { it.isAttached } ?: return
        // Popup/Dialog owners have their own Compose window origin. `positionInWindow()` is
        // therefore local to that owner on Android and makes a popup sample from its host
        // overshoot area (typically near the top edge) instead of the pixels behind the menu.
        // Screen coordinates are shared by both owners and include the real popup placement.
        val offset = glassCoordinates.positionOnScreen() - sourceCoordinates.positionOnScreen()
        withTransform({
            translate(-offset.x, -offset.y)
        }) {
            drawLayer(source.graphicsLayer)
        }
    }
}

/**
 * Wraps a [LayerBackdrop] for glass rendered in a separate window (dialog/sheet).
 * Non-layer backdrops pass through unchanged.
 */
@Composable
fun rememberCrossWindowBackdrop(backdrop: Backdrop): Backdrop = remember(backdrop) {
    if (backdrop is LayerBackdrop) CrossWindowBackdrop(backdrop) else backdrop
}
