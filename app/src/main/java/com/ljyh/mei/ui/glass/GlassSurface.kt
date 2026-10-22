package com.ljyh.mei.ui.glass

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceAtMost
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.emptyBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.capsule.ContinuousRoundedRectangle
import com.kyant.shapes.Capsule
import com.ljyh.mei.ui.liquidglass.InteractiveHighlight
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ceil
import kotlin.math.sin
import kotlin.math.tanh

data class GlassPressHighlight(val progress: Float = 0f, val position: Offset = Offset(0.5f, 0.5f))

enum class GlassEmphasis {
    Regular,
    Prominent,
}

enum class GlassSurfaceStyle {
    Standard,
    Navigation,
}

val LocalGlassSurfaceStyle = staticCompositionLocalOf { GlassSurfaceStyle.Standard }
internal val LocalGlassSurfaceBrightness = staticCompositionLocalOf { 0f }

/** Global opacity control for the navigation glass box-shadow stack. */
internal const val GlassBoxShadowAlpha = 0.3f

/** Shared liquid drag deformation used by glass controls and animated glass shells. */
internal fun GraphicsLayerScope.applyGlassDragScale(
    pressProgress: Float,
    offset: Offset,
) {
    val progress = pressProgress.coerceIn(0f, 1f)
    val controlHeight = size.height.coerceAtLeast(1f)
    val scale = lerp(1f, 1f + 4.dp.toPx() / controlHeight, progress)
    val maxOffset = size.minDimension.coerceAtLeast(1f)
    translationX = maxOffset * tanh(0.05f * offset.x / maxOffset)
    translationY = maxOffset * tanh(0.05f * offset.y / maxOffset)
    val maxDragScale = 4.dp.toPx() / controlHeight
    val angle = atan2(offset.y, offset.x)
    scaleX = scale
    scaleY = scale
    scaleX += maxDragScale * abs(cos(angle) * offset.x / size.maxDimension.coerceAtLeast(1f)) *
        (size.width / controlHeight).fastCoerceAtMost(1f)
    scaleY += maxDragScale * abs(sin(angle) * offset.y / size.maxDimension.coerceAtLeast(1f)) *
        (controlHeight / size.width.coerceAtLeast(1f)).fastCoerceAtMost(1f)
}

/**
 * Draws the crisp part of the navigation box shadow outside the glass outline.
 *
 * This is deliberately a separate expanded layer. Drawing the same strokes from
 * `onDrawSurface`/`onDrawFront` leaves them inside the backdrop's clipped layer, which
 * produces the pale fringe visible at the left and right edges of circles and capsules.
 */
internal fun Modifier.navigationGlassBoxShadow(
    shape: () -> Shape,
    alpha: Float,
    layerBlock: (GraphicsLayerScope.() -> Unit)?,
): Modifier = then(NavigationGlassBoxShadowElement(shape, alpha, layerBlock))

private class NavigationGlassBoxShadowElement(
    private val shape: () -> Shape,
    private val alpha: Float,
    private val layerBlock: (GraphicsLayerScope.() -> Unit)?,
) : ModifierNodeElement<NavigationGlassBoxShadowNode>() {

    override fun create(): NavigationGlassBoxShadowNode =
        NavigationGlassBoxShadowNode(shape, alpha, layerBlock)

    override fun update(node: NavigationGlassBoxShadowNode) {
        node.shape = shape
        node.alpha = alpha
        node.layerBlock = layerBlock
        node.invalidateDraw()
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "navigationGlassBoxShadow"
        properties["shape"] = shape
        properties["alpha"] = alpha
        properties["layerBlock"] = layerBlock
    }

    override fun equals(other: Any?): Boolean =
        other is NavigationGlassBoxShadowElement &&
            shape == other.shape &&
            alpha == other.alpha &&
            layerBlock == other.layerBlock

    override fun hashCode(): Int {
        var result = shape.hashCode()
        result = 31 * result + alpha.hashCode()
        result = 31 * result + (layerBlock?.hashCode() ?: 0)
        return result
    }
}

private class NavigationGlassBoxShadowNode(
    var shape: () -> Shape,
    var alpha: Float,
    var layerBlock: (GraphicsLayerScope.() -> Unit)?,
) : DrawModifierNode, Modifier.Node() {

    private var sideLayer: GraphicsLayer? = null
    private var outlineLayer: GraphicsLayer? = null
    private val layerScope = GraphicsLayerBlockScope()
    private var recordedSize = Size.Unspecified
    private var recordedShape: Shape? = null
    private var recordedAlpha = Float.NaN
    private var recordedDensity = Float.NaN
    private var recordedFontScale = Float.NaN
    private var recordedLayoutDirection: LayoutDirection? = null

    private val sidePaint = Paint().apply {
        style = PaintingStyle.Fill
        isAntiAlias = true
    }
    private val outlinePaint = Paint().apply {
        style = PaintingStyle.Stroke
        isAntiAlias = true
    }
    private val clearPaint = Paint().apply {
        style = PaintingStyle.Fill
        blendMode = BlendMode.Clear
        isAntiAlias = true
    }

    override fun ContentDrawScope.draw() {
        val sideLayer = sideLayer ?: return drawContent()
        val outlineLayer = outlineLayer ?: return drawContent()
        val shadowAlpha = alpha.coerceIn(0f, 1f)
        if (shadowAlpha <= 0f || size.minDimension <= 0f) return drawContent()

        val sideOffset = 0.7.dp.toPx()
        val outlineWidth = 0.65.dp.toPx()
        val padding = ceil(sideOffset + outlineWidth / 2f + 1f).toInt()
        val layerSize = IntSize(
            ceil(size.width).toInt() + padding * 2,
            ceil(size.height).toInt() + padding * 2,
        )
        val currentShape = shape()
        if (recordedSize != size || recordedShape != currentShape || recordedAlpha != shadowAlpha ||
            recordedDensity != density || recordedFontScale != fontScale ||
            recordedLayoutDirection != layoutDirection
        ) {
            val outline = currentShape.createOutline(size, layoutDirection, this)

            sidePaint.color = Color(0xFF3F3F3F).copy(alpha = shadowAlpha)
            outlinePaint.color = Color(0xFF6E6E6E).copy(alpha = shadowAlpha)
            outlinePaint.strokeWidth = outlineWidth

            // The side shadow is a horizontally expanded copy of the shape. Its height stays
            // exactly the element height, so it cannot create a shadow line on the top or bottom.
            // Clearing the original shape leaves only the two exposed side capsules.
            sideLayer.record(layerSize) {
                translate(padding.toFloat(), padding.toFloat()) {
                    drawContext.canvas.save()
                    drawContext.canvas.translate(size.width / 2f, 0f)
                    drawContext.canvas.scale(
                        (size.width + sideOffset * 2f) / size.width,
                        1f,
                    )
                    drawContext.canvas.translate(-size.width / 2f, 0f)
                    drawContext.canvas.drawOutline(outline, sidePaint)
                    drawContext.canvas.restore()
                    drawContext.canvas.drawOutline(outline, clearPaint)
                }
            }

            outlineLayer.record(layerSize) {
                translate(padding.toFloat(), padding.toFloat()) {
                    drawContext.canvas.drawOutline(outline, outlinePaint)

                    // Keep only the part of the outline that is outside the glass. This makes the
                    // border an outer stroke instead of an inset stroke.
                    drawContext.canvas.drawOutline(outline, clearPaint)
                }
            }

            // A modal moves in window space while this local outline remains unchanged.
            // Reuse the two offscreen border textures instead of clearing/rasterizing two
            // screen-sized buffers on every animation frame. Press transforms stay live below.
            recordedSize = size
            recordedShape = currentShape
            recordedAlpha = shadowAlpha
            recordedDensity = density
            recordedFontScale = fontScale
            recordedLayoutDirection = layoutDirection
        }

        // The outer layers are separate from drawBackdrop's own graphics layer, so apply the
        // exact same DragScale block to both. The glass layer remains untouched; this only keeps
        // the external shadow in lockstep with it.
        layerScope.reset(this, size)
        layerBlock?.invoke(layerScope)
        val pivotOffset = Offset(
            layerScope.transformOrigin.pivotFractionX * layerSize.width,
            layerScope.transformOrigin.pivotFractionY * layerSize.height,
        )
        sideLayer.alpha = layerScope.alpha
        sideLayer.scaleX = layerScope.scaleX
        sideLayer.scaleY = layerScope.scaleY
        sideLayer.translationX = layerScope.translationX
        sideLayer.translationY = layerScope.translationY
        sideLayer.rotationX = layerScope.rotationX
        sideLayer.rotationY = layerScope.rotationY
        sideLayer.rotationZ = layerScope.rotationZ
        sideLayer.cameraDistance = layerScope.cameraDistance
        sideLayer.pivotOffset = pivotOffset
        outlineLayer.alpha = layerScope.alpha
        outlineLayer.scaleX = layerScope.scaleX
        outlineLayer.scaleY = layerScope.scaleY
        outlineLayer.translationX = layerScope.translationX
        outlineLayer.translationY = layerScope.translationY
        outlineLayer.rotationX = layerScope.rotationX
        outlineLayer.rotationY = layerScope.rotationY
        outlineLayer.rotationZ = layerScope.rotationZ
        outlineLayer.cameraDistance = layerScope.cameraDistance
        outlineLayer.pivotOffset = pivotOffset

        // Put the expanded side capsules underneath the element, and keep the crisp outline on
        // top. Both are external-only, so neither layer interferes with the glass highlight.
        translate(-padding.toFloat(), -padding.toFloat()) {
            drawLayer(sideLayer)
        }
        drawContent()
        translate(-padding.toFloat(), -padding.toFloat()) {
            drawLayer(outlineLayer)
        }
    }

    override fun onAttach() {
        recordedShape = null
        sideLayer = requireGraphicsContext().createGraphicsLayer().apply {
            compositingStrategy = CompositingStrategy.Offscreen
        }
        outlineLayer = requireGraphicsContext().createGraphicsLayer().apply {
            compositingStrategy = CompositingStrategy.Offscreen
        }
    }

    override fun onDetach() {
        sideLayer?.let { requireGraphicsContext().releaseGraphicsLayer(it) }
        outlineLayer?.let { requireGraphicsContext().releaseGraphicsLayer(it) }
        sideLayer = null
        outlineLayer = null
    }
}

/** Adapter used to evaluate a shared GraphicsLayerScope block outside a real graphics layer. */
internal class GraphicsLayerBlockScope : GraphicsLayerScope {
    private var densityValue: Density = Density(1f)

    override val density: Float get() = densityValue.density
    override val fontScale: Float get() = densityValue.fontScale

    override var scaleX: Float = 1f
    override var scaleY: Float = 1f
    override var alpha: Float = 1f
    override var translationX: Float = 0f
    override var translationY: Float = 0f
    override var shadowElevation: Float = 0f
    override var rotationX: Float = 0f
    override var rotationY: Float = 0f
    override var rotationZ: Float = 0f
    override var cameraDistance: Float = 8f
    override var transformOrigin: TransformOrigin = TransformOrigin.Center
    override var shape: Shape = RectangleShape
    override var clip: Boolean = false
    override var size: Size = Size.Zero

    fun reset(drawScope: Density, size: Size) {
        densityValue = drawScope
        this.size = size
        scaleX = 1f
        scaleY = 1f
        alpha = 1f
        translationX = 0f
        translationY = 0f
        shadowElevation = 0f
        rotationX = 0f
        rotationY = 0f
        rotationZ = 0f
        cameraDistance = 8f
        transformOrigin = TransformOrigin.Center
        shape = RectangleShape
        clip = false
    }
}

/** Shared color treatment for standard glass and tinted navigation controls. */
private fun DrawScope.drawGlassSurfaceColor(
    surfaceColor: Color,
    prominent: Boolean,
    isLight: Boolean,
    brightness: Float,
    enabled: Boolean,
) {
    drawRect(
        Color.White.copy(alpha = (if (isLight) 0.16f else 0.06f) + brightness * 0.18f),
        blendMode = BlendMode.Screen,
    )
    if (prominent) {
        drawRect(surfaceColor.copy(alpha = 1f), alpha = 0.22f, blendMode = BlendMode.Hue)
    }
    drawRect(surfaceColor.copy(alpha = surfaceColor.alpha * if (enabled) 1f else 0.8f))
}

internal const val LightNavigationContainerAlphaScale = 0.5f

/** Adjusts only the sampled image, before the neutral tint and foreground are drawn. */
internal fun DrawScope.drawLightNavigationBackdrop() {
    drawRect(Color(0xFFF8F8F8).copy(alpha = 0.25f), blendMode = BlendMode.Luminosity)
    drawRect(Color(0xFF444444).copy(alpha = 0.15f), blendMode = BlendMode.Plus)
}

/** Neutral navigation material, applied to the sampled image before its inner shadows. */
internal fun DrawScope.drawNavigationGlassMaterial(
    containerColor: Color,
    isDark: Boolean,
    containerAlphaMultiplier: Float = 1.25f,
) {
    if (isDark) {
        drawRect(Color(0xFF999999).copy(alpha = 0.17f), blendMode = BlendMode.Luminosity)
    } else {
        drawLightNavigationBackdrop()
    }
    drawRect(
        containerColor.copy(
            alpha = containerColor.alpha * containerAlphaMultiplier.coerceAtLeast(0f) *
                if (isDark) 0.6f else LightNavigationContainerAlphaScale,
        ),
    )
}

/** Both edge highlights use luminosity and additive passes, fading toward the center. */
private fun DrawScope.drawDetailActionHighlights(shape: Shape) {
    if (size.minDimension <= 0f) return
    val outline = shape.createOutline(size, layoutDirection, this)
    val stroke = Stroke(ceil(0.5.dp.toPx()) * 2f)
    val middle = size.height / 2f
    val highlightColor = Color.White.copy(alpha = 0.30f)
    clipRect(bottom = middle) {
        val brush = Brush.verticalGradient(
            colors = listOf(highlightColor, Color.Transparent),
            endY = middle,
        )
        drawOutline(
            outline = outline,
            brush = brush,
            style = stroke,
            blendMode = BlendMode.Luminosity,
        )
        drawOutline(
            outline = outline,
            brush = brush,
            style = stroke,
            blendMode = BlendMode.Plus,
        )
    }
    clipRect(top = middle) {
        val brush = Brush.verticalGradient(
            colors = listOf(Color.Transparent, highlightColor),
            startY = middle,
            endY = size.height,
        )
        drawOutline(
            outline = outline,
            brush = brush,
            style = stroke,
            blendMode = BlendMode.Luminosity,
        )
        drawOutline(
            outline = outline,
            brush = brush,
            style = stroke,
            blendMode = BlendMode.Plus,
        )
    }
}

@Composable
internal fun rememberNavigationInnerShadows(enabled: Boolean): NavigationInnerShadowRenderer? {
    val renderer = remember(enabled) { if (enabled) NavigationInnerShadowRenderer() else null }
    DisposableEffect(renderer) {
        onDispose { renderer?.release() }
    }
    return renderer
}

/** Shared outer navigation-glass material used by the expanded nav and floating controls. */
internal fun Modifier.navigationGlassBackground(
    backdrop: Backdrop,
    shape: () -> Shape,
    containerColor: Color,
    navigationMaterial: Boolean,
    innerShadows: NavigationInnerShadowRenderer?,
    isDark: Boolean = false,
    containerAlphaMultiplier: Float = 1.25f,
    surfaceOverlay: (DrawScope.() -> Unit)? = null,
    pressOverlay: (DrawScope.() -> Unit)? = null,
    accentHighlights: Boolean = false,
    pressProgress: Float = 0f,
    pressProgressState: androidx.compose.runtime.State<Float>? = null,
    highlightAngle: Float = 90f,
    sampleBackdrop: Boolean = true,
    layerBlock: (GraphicsLayerScope.() -> Unit)? = null,
    appearance: GlassAppearance = GlassAppearance(),
): Modifier = this
    .navigationGlassDropShadow(shape, layerBlock)
    .navigationGlassBoxShadow(shape, GlassBoxShadowAlpha, layerBlock)
    .drawBackdrop(
        backdrop = if (sampleBackdrop && appearance.usesBackdrop) backdrop else emptyBackdrop(),
        shape = shape,
        effects = {
            if (sampleBackdrop && appearance.usesBackdrop) {
                vibrancy()
                blur(2.dp.toPx())
                if (appearance.usesRefraction) {
                    lens(
                        refractionHeight = 10.dp.toPx(),
                        refractionAmount = 24.dp.toPx(),
                        depthEffect = true,
                        chromaticAberration = true,
                    )
                }
                if (navigationMaterial && !isDark) liftNavigationShadows()
            }
        },
        highlight = {
            if (accentHighlights) null else {
                val progress = pressProgressState?.value ?: pressProgress
                Highlight.Default.copy(
                    alpha = 0.54f + 0.38f * progress,
                    style = HighlightStyle.Default(angle = highlightAngle),
                )
            }
        },
        shadow = null,
        innerShadow = null,
        layerBlock = layerBlock,
        onDrawSurface = {
            if (appearance.reduceTransparency) {
                drawRect(containerColor.copy(alpha = 1f))
            } else if (surfaceOverlay != null) {
                surfaceOverlay()
            } else if (navigationMaterial && sampleBackdrop && appearance.usesBackdrop) {
                drawNavigationGlassMaterial(containerColor, isDark, containerAlphaMultiplier)
                innerShadows?.draw(this, shape(), isDark = isDark)
            } else {
                drawRect(
                    containerColor.copy(
                        alpha = containerColor.alpha * containerAlphaMultiplier.coerceAtLeast(0f),
                    ),
                )
            }
        },
        onDrawFront = {
            if (accentHighlights) {
                drawDetailActionHighlights(shape())
            }
            pressOverlay?.invoke(this)
        },
    )

@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    backdrop: Backdrop = LocalGlassBackdrop.current,
    shape: Shape = ContinuousRoundedRectangle(LocalGlassDimensions.current.regularCornerRadius),
    style: GlassSurfaceStyle = LocalGlassSurfaceStyle.current,
    navigationSurfaceColor: Color? = null,
    navigationSurfaceAlphaMultiplier: Float = 1.25f,
    emphasis: GlassEmphasis = GlassEmphasis.Regular,
    enabled: Boolean = true,
    refractionHeight: Dp = 12.dp,
    refractionAmount: Dp = 24.dp,
    opticalHighlightBoost: Float = 0f,
    sampleBackdrop: Boolean = true,
    exportedBackdrop: LayerBackdrop? = null,
    onVisualBoundsChanged: ((Rect, GlassPressHighlight) -> Unit)? = null,
    pressHighlight: (() -> GlassPressHighlight?)? = null,
    cancelPressFeedback: Boolean = false,
    groupSurfaceSeparation: (() -> Float)? = LocalToolbarActionSeparation.current,
    morphProgress: androidx.compose.runtime.State<Float>? = null,
    morphCaptureWidth: Dp? = null,
    morphVerticalTravel: Dp = 0.dp,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    contentAlignment: Alignment = Alignment.Center,
    detailAction: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = LocalGlassColors.current
    val appearance = LocalGlassAppearance.current
    val isLight = !colors.isDark
    val navigationMaterial = emphasis == GlassEmphasis.Regular &&
        navigationSurfaceColor == null && sampleBackdrop && appearance.usesBackdrop
    val accentDetailAction = detailAction && style == GlassSurfaceStyle.Navigation &&
        emphasis == GlassEmphasis.Prominent && navigationSurfaceColor == null &&
        sampleBackdrop && appearance.usesBackdrop
    val innerShadows = rememberNavigationInnerShadows(
        style == GlassSurfaceStyle.Navigation && (navigationMaterial || accentDetailAction),
    )
    val brightness = LocalGlassSurfaceBrightness.current.coerceIn(0f, 1f)
    val animationScope = rememberCoroutineScope()
    val interactiveHighlight = remember(animationScope) {
        InteractiveHighlight(animationScope = animationScope)
    }
    val toolbarMember = rememberToolbarActionMember(
        eligible = style == GlassSurfaceStyle.Navigation && emphasis == GlassEmphasis.Regular && !detailAction,
        material = ToolbarActionMaterial(navigationSurfaceColor, navigationSurfaceAlphaMultiplier, sampleBackdrop),
        separation = groupSurfaceSeparation,
        highlight = interactiveHighlight,
    )
    val sharesToolbarSurface = toolbarMember?.sharesSurface == true
    LaunchedEffect(interactiveHighlight, cancelPressFeedback) {
        if (cancelPressFeedback) interactiveHighlight.cancelPress()
    }
    val surfaceColor = when (emphasis) {
        GlassEmphasis.Regular -> colors.container
        GlassEmphasis.Prominent -> colors.prominentContainer
    }
    val pressProgressState = remember(interactiveHighlight, pressHighlight) {
        derivedStateOf { pressHighlight?.invoke()?.progress ?: interactiveHighlight.pressProgress }
    }
    val shapeProvider = remember(shape) { { shape } }
    val dragScaleLayerBlock: GraphicsLayerScope.() -> Unit = remember(interactiveHighlight, toolbarMember, onVisualBoundsChanged) {
        {
            if (toolbarMember != null) toolbarMember.applyPressTransform(this) else applyGlassDragScale(
                pressProgress = interactiveHighlight.pressProgress,
                offset = interactiveHighlight.offset,
            )
            onVisualBoundsChanged?.invoke(
                Rect(
                    size.width * (1f - scaleX) / 2f + translationX,
                    size.height * (1f - scaleY) / 2f + translationY,
                    size.width * (1f + scaleX) / 2f + translationX,
                    size.height * (1f + scaleY) / 2f + translationY,
                ),
                GlassPressHighlight(interactiveHighlight.pressProgress,
                    interactiveHighlight.highlightPosition(size).let {
                        Offset(it.x / size.width.coerceAtLeast(1f), it.y / size.height.coerceAtLeast(1f))
                    }),
            )
        }
    }
    // Some controls (notably sheet headers) need the navigation tint/highlight and the shared
    // drag physics without replaying or refracting the content behind them. Keep an empty
    // backdrop for that mode so the button remains a glass surface without lens/blur
    // sampling of the player window.
    val surfaceBackdrop = if (sampleBackdrop && appearance.usesBackdrop) backdrop else emptyBackdrop()
    val morphing = remember(morphProgress) {
        derivedStateOf {
            morphProgress?.value?.let { it != 0f && it != 1f } == true
        }
    }.value
    val morphGlassModifier = if (morphProgress != null && style == GlassSurfaceStyle.Navigation &&
        sampleBackdrop && emphasis == GlassEmphasis.Regular
    ) {
        rememberMorphingNavigationGlass(
            backdrop = surfaceBackdrop,
            active = morphing,
            shape = shape,
            tint = navigationSurfaceColor ?: surfaceColor,
            navigationMaterial = navigationMaterial,
            isDark = colors.isDark,
            pressProgress = { pressProgressState.value },
            layerBlock = dragScaleLayerBlock,
            tintMultiplier = navigationSurfaceAlphaMultiplier,
            captureWidth = morphCaptureWidth,
            verticalTravel = morphVerticalTravel,
            morphProgress = { morphProgress.value },
        )
    } else null
    val glassModifier = if (sharesToolbarSurface) {
        Modifier.graphicsLayer { toolbarMember?.applyPressTransform(this) }
    } else if (morphing && morphGlassModifier != null) {
        morphGlassModifier
    } else if (style == GlassSurfaceStyle.Navigation) {
        val containerColor = navigationSurfaceColor ?: surfaceColor
        remember(
            surfaceBackdrop,
            shapeProvider,
            containerColor,
            emphasis,
            isLight,
            navigationMaterial,
            accentDetailAction,
            innerShadows,
            brightness,
            enabled,
            navigationSurfaceAlphaMultiplier,
            pressProgressState,
            sampleBackdrop,
            dragScaleLayerBlock,
            appearance,
        ) {
            Modifier.navigationGlassBackground(
                backdrop = surfaceBackdrop,
                shape = shapeProvider,
                containerColor = containerColor,
                navigationMaterial = navigationMaterial,
                innerShadows = innerShadows,
                isDark = colors.isDark,
                containerAlphaMultiplier = navigationSurfaceAlphaMultiplier,
                surfaceOverlay = if (accentDetailAction) {
                    {
                        drawGlassSurfaceColor(containerColor, true, isLight, brightness, enabled)
                        innerShadows?.draw(this, shape, isDark = colors.isDark)
                    }
                } else if (emphasis == GlassEmphasis.Prominent) {
                    {
                        drawGlassSurfaceColor(containerColor, true, isLight, brightness, enabled)
                    }
                } else null,
                accentHighlights = accentDetailAction,
                pressProgressState = pressProgressState,
                sampleBackdrop = sampleBackdrop,
                layerBlock = dragScaleLayerBlock,
                appearance = appearance,
            )
        }
    } else {
        remember(
            surfaceBackdrop,
            shapeProvider,
            refractionHeight,
            refractionAmount,
            isLight,
            brightness,
            opticalHighlightBoost,
            enabled,
            exportedBackdrop,
            emphasis,
            colors.prominentContainer,
            surfaceColor,
            sampleBackdrop,
            dragScaleLayerBlock,
            appearance,
        ) {
            Modifier.drawBackdrop(
                backdrop = surfaceBackdrop,
                shape = shapeProvider,
                effects = {
                    if (sampleBackdrop && appearance.usesBackdrop) {
                        val progress = interactiveHighlight.pressProgress
                        vibrancy()
                        blur(2.dp.toPx())
                        if (appearance.usesRefraction) {
                            lens(
                                refractionHeight = refractionHeight.toPx(),
                                refractionAmount = refractionAmount.toPx(),
                                depthEffect = progress > 0.01f,
                                chromaticAberration = true,
                            )
                        }
                    }
                },
                highlight = {
                    Highlight.Default.copy(
                        alpha = ((if (isLight) 0.48f else 0.32f) + brightness * 0.18f +
                            opticalHighlightBoost + 0.30f * interactiveHighlight.pressProgress)
                            .coerceAtMost(1f),
                    )
                },
                shadow = {
                    Shadow(
                        radius = 24.dp,
                        color = Color.Black.copy(alpha = 0.1f),
                        alpha = (0.08f + 0.22f * interactiveHighlight.pressProgress) *
                            if (enabled) 1f else 0.35f,
                    )
                },
                innerShadow = {
                    InnerShadow(
                        radius = 4.dp + 8.dp * interactiveHighlight.pressProgress,
                        color = Color.Black.copy(alpha = 0.15f),
                        alpha = 0.1f + 0.3f * interactiveHighlight.pressProgress,
                    )
                },
                layerBlock = dragScaleLayerBlock,
                exportedBackdrop = exportedBackdrop,
                onDrawSurface = {
                    if (appearance.reduceTransparency) {
                        drawRect(surfaceColor.copy(alpha = 1f))
                    } else {
                        drawGlassSurfaceColor(
                            surfaceColor = surfaceColor,
                            prominent = emphasis == GlassEmphasis.Prominent,
                            isLight = isLight,
                            brightness = brightness,
                            enabled = enabled,
                        )
                    }
                },
            )
        }
    }
    val drawSurface: @Composable (Modifier) -> Unit = { surfaceModifier ->
        Box(
            modifier = surfaceModifier
                .then(
                    if (sharesToolbarSurface) Modifier
                    else if (pressHighlight != null) interactiveHighlight.highlightModifier(
                        progressProvider = { pressHighlight()?.progress ?: interactiveHighlight.pressProgress },
                        positionProvider = { size ->
                            pressHighlight()?.let { Offset(size.width * it.position.x, size.height * it.position.y) }
                                ?: interactiveHighlight.highlightPosition(size)
                        },
                    ) else if (onClick != null && enabled) interactiveHighlight.modifier else Modifier,
                )
                .then(
                    if (onClick != null) {
                        val click = if (onLongClick != null) Modifier.combinedClickable(
                            enabled = enabled,
                            interactionSource = null,
                            indication = null,
                            role = Role.Button,
                            onClick = onClick,
                            onLongClick = onLongClick,
                        ) else Modifier.clickable(
                            enabled = enabled,
                            interactionSource = null,
                            indication = null,
                            role = Role.Button,
                            onClick = onClick,
                        )
                        click.then(if (enabled) interactiveHighlight.gestureModifier else Modifier)
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = contentAlignment,
            content = {
                val contentColor = if (emphasis == GlassEmphasis.Prominent) Color.White else colors.content
                CompositionLocalProvider(
                    LocalContentColor provides contentColor,
                    LocalGlassContentColor provides contentColor,
                    LocalIosToolbarActionGroup provides null,
                    LocalToolbarActionSeparation provides null,
                ) {
                    if (accentDetailAction) {
                        Box(
                            modifier = Modifier.graphicsLayer {
                                blendMode = BlendMode.Plus
                                compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen
                            },
                            contentAlignment = contentAlignment,
                            content = content,
                        )
                    } else {
                        content()
                    }
                }
            },
        )
    }
    if (toolbarMember == null) {
        drawSurface(modifier.then(glassModifier))
    } else {
        // Register an untransformed layout wrapper. The inner surface owns press scaling,
        // so a press cannot change adjacency or apply the same deformation twice.
        Box(
            modifier = modifier.onGloballyPositioned(toolbarMember::updateCoordinates),
            propagateMinConstraints = true,
        ) {
            drawSurface(glassModifier)
        }
    }
}

@Composable
fun GlassButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    backdrop: Backdrop = LocalGlassBackdrop.current,
    style: GlassSurfaceStyle = LocalGlassSurfaceStyle.current,
    navigationSurfaceColor: Color? = null,
    enabled: Boolean = true,
    emphasis: GlassEmphasis = GlassEmphasis.Regular,
    navigationSurfaceAlphaMultiplier: Float = 1.25f,
    sampleBackdrop: Boolean = true,
    detailAction: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    GlassSurface(
        modifier = modifier.height(LocalGlassDimensions.current.controlHeight),
        backdrop = backdrop,
        style = style,
        navigationSurfaceColor = navigationSurfaceColor,
        navigationSurfaceAlphaMultiplier = navigationSurfaceAlphaMultiplier,
        shape = Capsule(),
        emphasis = emphasis,
        enabled = enabled,
        sampleBackdrop = sampleBackdrop,
        onClick = onClick,
        detailAction = detailAction,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

@Composable
fun GlassIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    backdrop: Backdrop = LocalGlassBackdrop.current,
    style: GlassSurfaceStyle = LocalGlassSurfaceStyle.current,
    navigationSurfaceColor: Color? = null,
    navigationSurfaceAlphaMultiplier: Float = 1.25f,
    enabled: Boolean = true,
    emphasis: GlassEmphasis = GlassEmphasis.Regular,
    sampleBackdrop: Boolean = true,
    morphProgress: androidx.compose.runtime.State<Float>? = null,
    morphCaptureWidth: Dp? = null,
    groupSurfaceSeparation: (() -> Float)? = LocalToolbarActionSeparation.current,
    onLongClick: (() -> Unit)? = null,
    detailAction: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    GlassSurface(
        modifier = modifier.size(LocalGlassDimensions.current.iconButtonSize),
        backdrop = backdrop,
        style = style,
        navigationSurfaceColor = navigationSurfaceColor,
        navigationSurfaceAlphaMultiplier = navigationSurfaceAlphaMultiplier,
        shape = CircleShape,
        emphasis = emphasis,
        enabled = enabled,
        sampleBackdrop = sampleBackdrop,
        morphProgress = morphProgress,
        morphCaptureWidth = morphCaptureWidth,
        groupSurfaceSeparation = groupSurfaceSeparation,
        onClick = onClick,
        onLongClick = onLongClick,
        detailAction = detailAction,
        content = content,
    )
}
