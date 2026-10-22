package com.ljyh.mei.ui.glass

import android.view.ViewTreeObserver
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import java.util.WeakHashMap
import kotlin.math.ceil

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
private val backdropSourceFrames = WeakHashMap<LayerBackdrop, State<Int>>()

/** Attach next to a [layerBackdrop] recording so dialog-window glass can locate the source. */
fun Modifier.trackBackdropPosition(backdrop: LayerBackdrop, frameVersion: State<Int>? = null): Modifier {
    frameVersion?.let { backdropSourceFrames[backdrop] = it }
    return onGloballyPositioned { coordinates ->
        if (coordinates.isAttached) {
            backdropSourcePositions[backdrop] = coordinates
        }
    }
}

/**
 * Samples a [LayerBackdrop] that was recorded in another window (e.g. app content from a
 * dialog/sheet window). LayerBackdrop maps coordinates with localPositionOf, which cannot
 * cross compose owners and silently yields a wrong offset there, so glass in a dialog
 * samples nothing. Map through screen coordinates so ancestor transforms, including
 * predictive-back scaling, are preserved across windows.
 */
class CrossWindowBackdrop(
    private val source: LayerBackdrop,
    private val rasterized: GraphicsLayer? = null,
    private val renderScale: Float = 1f,
    private val captureHeight: Dp? = null,
    private val sourceFrame: State<Int>? = null,
) : Backdrop {

    private val sourceToGlass = Matrix()
    private val screenToGlass = Matrix()
    private var recordedSourceSize = IntSize.Zero
    private var recordedSourceFrame: Int? = null

    override val isCoordinatesDependent: Boolean get() = true

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?,
    ) {
        val glassCoordinates = coordinates ?: return
        if (!glassCoordinates.isAttached) return
        val sourceCoordinates = backdropSourcePositions[source]?.takeIf { it.isAttached } ?: return
        // A window draw includes foreground changes isolated in child graphics layers.
        val frame = if (glassCoordinates.findRootCoordinates() !== sourceCoordinates.findRootCoordinates()) {
            (sourceFrame ?: backdropSourceFrames[source])?.value
        } else null
        val sourceSize = source.graphicsLayer.size
        val captureTop = captureHeight?.let {
            (sourceSize.height - with(density) { it.roundToPx() }).coerceAtLeast(0)
        } ?: 0
        val layer = rasterized?.also { cached ->
            val bufferSize = IntSize(
                ceil(sourceSize.width * renderScale).toInt(),
                ceil((sourceSize.height - captureTop) * renderScale).toInt(),
            )
            if (bufferSize.width <= 0 || bufferSize.height <= 0) return
            if (cached.size != bufferSize || recordedSourceSize != sourceSize ||
                (frame != null && recordedSourceFrame != frame)
            ) {
                cached.clip = true
                cached.compositingStrategy = CompositingStrategy.Offscreen
                cached.record(bufferSize) {
                    withTransform({
                        scale(renderScale, renderScale, Offset.Zero)
                        translate(0f, -captureTop.toFloat())
                    }) {
                        drawLayer(source.graphicsLayer)
                    }
                }
                recordedSourceSize = sourceSize
                recordedSourceFrame = frame
            }
        } ?: source.graphicsLayer
        // Include ancestor scale/translation so the sampled page stays screen-anchored
        // while the sheet shrinks during predictive back. This also includes layerBlock.
        sourceToGlass.reset()
        sourceCoordinates.transformToScreen(sourceToGlass)
        screenToGlass.reset()
        glassCoordinates.transformToScreen(screenToGlass)
        screenToGlass.invert()
        sourceToGlass *= screenToGlass
        withTransform({
            transform(sourceToGlass)
            translate(0f, captureTop.toFloat())
            if (rasterized != null) scale(1f / renderScale, 1f / renderScale, Offset.Zero)
        }) {
            drawLayer(layer)
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

/** Share a live, flattened player source between modal glass and its small glass controls. */
@Composable
fun rememberRasterizedPlayerBackdrop(source: LayerBackdrop): Backdrop {
    val rasterized = rememberGraphicsLayer()
    val view = LocalView.current
    val sourceFrame = remember(view) { mutableIntStateOf(0) }
    DisposableEffect(view, sourceFrame) {
        var frame = 0
        val listener = ViewTreeObserver.OnPreDrawListener {
            // Only cross-window drawing observes this state; the source stays independent.
            sourceFrame.intValue = ++frame
            true
        }
        view.viewTreeObserver.addOnPreDrawListener(listener)
        onDispose {
            val observer = view.viewTreeObserver
            if (observer.isAlive) observer.removeOnPreDrawListener(listener)
        }
    }
    return remember(source, rasterized, sourceFrame) {
        CrossWindowBackdrop(source, rasterized, renderScale = 0.25f, sourceFrame = sourceFrame)
    }
}

/**
 * Share rasterized page regions between glass consumers instead of replaying the display
 * list (including nested blur and navigation layers) for every lens. This is a live layer
 * reference: source changes invalidate the GPU cache even when the recording is unchanged.
 * Only sampled pixels use half resolution; text, glass outlines and highlights stay native.
 */
@Composable
fun rememberSharedGlassBackdrop(base: Backdrop, source: LayerBackdrop): Backdrop {
    val full = rememberGraphicsLayer()
    val bottom = rememberGraphicsLayer()
    return remember(base, source, full, bottom) {
        SharedGlassBackdrop(base, source, full, bottom)
    }
}

/** A morph records in root coordinates, but only needs pixels inside its capture viewport. */
internal interface ViewportBackdrop : Backdrop {
    fun DrawScope.drawViewport(density: Density, coordinates: LayoutCoordinates, viewport: Rect)
}

private val BottomCaptureHeight = 240.dp

private class SharedGlassBackdrop(
    private val base: Backdrop,
    private val source: LayerBackdrop,
    fullLayer: GraphicsLayer,
    bottomLayer: GraphicsLayer,
) : ViewportBackdrop {
    private val full = CrossWindowBackdrop(source, fullLayer, renderScale = 0.5f)
    private val bottom = CrossWindowBackdrop(source, bottomLayer, renderScale = 0.5f,
        captureHeight = BottomCaptureHeight)

    override val isCoordinatesDependent: Boolean get() = true

    private fun sample(density: Density, coordinates: LayoutCoordinates?, top: Float): Backdrop {
        val recorded = backdropSourcePositions[source]
        if (coordinates?.isAttached != true || recorded?.isAttached != true) return full
        val sourceTop = coordinates.positionOnScreen().y - recorded.positionOnScreen().y + top
        val bandTop = source.graphicsLayer.size.height - with(density) { BottomCaptureHeight.toPx() }
        // Include the largest navigation lens/blur padding. Upper controls and full-sheet
        // captures use the full page; bottom morphs never replay the top toolbar's blur.
        return if (sourceTop - with(density) { 32.dp.toPx() } >= bandTop) bottom else full
    }

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?,
    ) {
        with(base) { drawBackdrop(density, coordinates, layerBlock) }
        with(sample(density, coordinates, 0f)) { drawBackdrop(density, coordinates, layerBlock) }
    }

    override fun DrawScope.drawViewport(
        density: Density,
        coordinates: LayoutCoordinates,
        viewport: Rect,
    ) {
        with(base) { drawBackdrop(density, coordinates, null) }
        with(sample(density, coordinates, viewport.top)) { drawBackdrop(density, coordinates, null) }
    }
}
