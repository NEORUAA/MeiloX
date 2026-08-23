package com.ljyh.mei.ui.glass

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceAtMost
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.rememberCanvasBackdrop
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
    private val layerScope = ShadowGraphicsLayerScope()

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
        val outline = shape().createOutline(size, layoutDirection, this)

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

/** Small adapter that lets the shared GraphicsLayerScope block drive the external shadow layer. */
private class ShadowGraphicsLayerScope : GraphicsLayerScope {
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

/** Shared outer navigation-glass material used by the expanded nav and floating controls. */
internal fun Modifier.navigationGlassBackground(
    backdrop: Backdrop,
    shape: () -> Shape,
    containerColor: Color,
    containerAlphaMultiplier: Float = 1.25f,
    pressProgress: Float = 0f,
    pressProgressState: androidx.compose.runtime.State<Float>? = null,
    highlightAngle: Float = 90f,
    sampleBackdrop: Boolean = true,
    layerBlock: (GraphicsLayerScope.() -> Unit)? = null,
): Modifier = this
    .navigationGlassBoxShadow(shape, GlassBoxShadowAlpha, layerBlock)
    .drawBackdrop(
        backdrop = backdrop,
        shape = shape,
        effects = {
            if (sampleBackdrop) {
                vibrancy()
                blur(2.dp.toPx())
                lens(
                    refractionHeight = 10.dp.toPx(),
                    refractionAmount = 24.dp.toPx(),
                    depthEffect = true,
                    chromaticAberration = true,
                )
            }
        },
        highlight = {
            val progress = pressProgressState?.value ?: pressProgress
            Highlight.Default.copy(
                alpha = 0.54f + 0.38f * progress,
                style = HighlightStyle.Default(angle = highlightAngle),
            )
        },
        shadow = {
            Shadow(
                radius = 15.dp,
                offset = DpOffset(0.dp, 8.dp),
                color = Color.Black,
                alpha = 0.02f * GlassBoxShadowAlpha,
            )
        },
        innerShadow = null,
        layerBlock = layerBlock,
        onDrawSurface = {
            drawRect(
                containerColor.copy(
                    alpha = containerColor.alpha * containerAlphaMultiplier.coerceAtLeast(0f),
                ),
            )
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
    onClick: (() -> Unit)? = null,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = LocalGlassColors.current
    val isLight = !colors.isDark
    val brightness = LocalGlassSurfaceBrightness.current.coerceIn(0f, 1f)
    val animationScope = rememberCoroutineScope()
    val interactiveHighlight = remember(animationScope) {
        InteractiveHighlight(animationScope = animationScope)
    }
    val surfaceColor = when (emphasis) {
        GlassEmphasis.Regular -> colors.container
        GlassEmphasis.Prominent -> colors.prominentContainer
    }
    val dragScaleLayerBlock: GraphicsLayerScope.() -> Unit = {
        applyGlassDragScale(
            pressProgress = interactiveHighlight.pressProgress,
            offset = interactiveHighlight.offset,
        )
    }
    // Some controls (notably sheet headers) need the navigation tint/highlight and the shared
    // drag physics without replaying or refracting the content behind them. Keep an empty
    // canvas backdrop for that mode so the button remains a glass surface without lens/blur
    // sampling of the player window.
    val surfaceBackdrop = if (sampleBackdrop) backdrop else rememberCanvasBackdrop {}
    val surfaceModifier = if (style == GlassSurfaceStyle.Navigation) {
        modifier.navigationGlassBackground(
            backdrop = surfaceBackdrop,
            shape = { shape },
            containerColor = navigationSurfaceColor ?: surfaceColor,
            containerAlphaMultiplier = navigationSurfaceAlphaMultiplier,
            pressProgress = interactiveHighlight.pressProgress,
            sampleBackdrop = sampleBackdrop,
            layerBlock = dragScaleLayerBlock,
        )
    } else {
        modifier.drawBackdrop(
            backdrop = surfaceBackdrop,
            shape = { shape },
            effects = {
                if (sampleBackdrop) {
                    val progress = interactiveHighlight.pressProgress
                    vibrancy()
                    blur(2.dp.toPx())
                    lens(
                        refractionHeight = refractionHeight.toPx(),
                        refractionAmount = refractionAmount.toPx(),
                        depthEffect = progress > 0.01f,
                        chromaticAberration = true,
                    )
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
                drawRect(
                    Color.White.copy(
                        alpha = (if (isLight) 0.16f else 0.06f) + brightness * 0.18f,
                    ),
                    blendMode = BlendMode.Screen,
                )
                if (emphasis == GlassEmphasis.Prominent) {
                    drawRect(
                        colors.prominentContainer.copy(alpha = 1f),
                        alpha = 0.22f,
                        blendMode = BlendMode.Hue,
                    )
                }
                drawRect(surfaceColor.copy(alpha = surfaceColor.alpha * if (enabled) 1f else 0.8f))
            },
        )
    }

    Box(
        modifier = surfaceModifier
            .then(if (onClick != null && enabled) interactiveHighlight.modifier else Modifier)
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        enabled = enabled,
                        interactionSource = null,
                        indication = null,
                        role = Role.Button,
                        onClick = onClick,
                    ).then(if (enabled) interactiveHighlight.gestureModifier else Modifier)
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
            ) {
                content()
            }
        },
    )
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
    content: @Composable RowScope.() -> Unit,
) {
    GlassSurface(
        modifier = modifier.height(LocalGlassDimensions.current.controlHeight),
        backdrop = backdrop,
        style = style,
        navigationSurfaceColor = navigationSurfaceColor,
        shape = Capsule(),
        emphasis = emphasis,
        enabled = enabled,
        onClick = onClick,
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
        onClick = onClick,
        content = content,
    )
}
