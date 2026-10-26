/*
 * Refraction math adapted from AndroidLiquidGlass, Copyright 2025 Kyant.
 * Licensed under the Apache License, Version 2.0.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package com.ljyh.mei.ui.glass

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.floor
import com.kyant.backdrop.Backdrop
import com.ljyh.mei.ui.liquidglass.PressHighlightRenderer

// Sample and soft material fields share this scale. Keep moving outlines, optical
// highlights and foreground at native resolution while reducing filter bandwidth.
private const val DefaultGlassRenderScale = 0.5f

@Composable
internal fun rememberGlassMorphRenderer(
    backdrop: Backdrop,
    active: Boolean,
    blurRadius: Dp = 2.dp,
    renderScale: Float = DefaultGlassRenderScale,
    programs: LiquidMenuShaderPrograms? = null,
): GlassMorphRenderer {
    val density = LocalDensity.current
    val appearance = LocalGlassAppearance.current
    val source = rememberGraphicsLayer()
    val refracted = rememberGraphicsLayer()
    val material = rememberGraphicsLayer()
    val renderer = remember(backdrop, density, blurRadius, renderScale, source, refracted, material, appearance, programs) {
        GlassMorphRenderer(backdrop, density, blurRadius, renderScale, source, refracted, material, appearance, programs)
    }
    DisposableEffect(renderer, active) {
        // Keep shaders/layers across endpoints, but never reuse an old capture when a new
        // transition starts after scrolling, changing pages, or updating the backdrop.
        if (active) renderer.invalidateRecording()
        onDispose { }
    }
    DisposableEffect(renderer) {
        onDispose { renderer.release() }
    }
    return renderer
}

/** One active menu per anchor; compile its mutable shader builders before the first tap. */
internal class LiquidMenuShaderPrograms {
    init { LiquidMenuNativeField.warmUp() }
    val refraction = RuntimeShader(TransitionRefractionShader)
    val highlight = RuntimeShader(TransitionHighlightShader)
    val portal = RuntimeShader(LiquidMenuPortalShader)
}

/** Fixed-size source/effect layers shared by the player sheet and home control morphs. */
internal class GlassMorphRenderer(
    private val backdrop: Backdrop,
    private val density: Density,
    blurRadius: Dp,
    private val renderScale: Float,
    private val source: GraphicsLayer,
    private val refracted: GraphicsLayer,
    private val material: GraphicsLayer,
    private val appearance: GlassAppearance,
    programs: LiquidMenuShaderPrograms? = null,
) {
    private val shader = if (appearance.usesRefraction) programs?.refraction ?: RuntimeShader(TransitionRefractionShader) else null
    private val highlight = programs?.highlight ?: RuntimeShader(TransitionHighlightShader)
    private val highlightBrush = ShaderBrush(highlight)
    private val pressLight = PressHighlightRenderer()
    private val innerShadows = NavigationInnerShadowRenderer(renderScale = renderScale)
    private val dropShadow = NavigationDropShadowRenderer(renderScale = renderScale)
    private val shadowLiftEffect = if (appearance.usesBackdrop) navigationShadowLiftEffect() else null
    private val reusableOutlinePath = Path()
    private var materialWidth = 0
    private var materialHeight = 0
    private var recordedViewport: Rect? = null
    private val sourceEffect = if (appearance.usesBackdrop) run {
        val color = RenderEffect.createColorFilterEffect(
            ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(1.5f) }),
        )
        val blur = with(density) { blurRadius.toPx() } * renderScale
        RenderEffect.createBlurEffect(blur, blur, color, Shader.TileMode.CLAMP)
            .asComposeRenderEffect()
    } else null

    fun invalidateRecording() {
        recordedViewport = null
    }

    fun release() {
        shader?.let { bindLiquidMenuField(it, null) }
        bindLiquidMenuField(highlight, null)
        innerShadows.release()
        dropShadow.release()
    }

    fun draw(
        scope: DrawScope,
        coordinates: LayoutCoordinates,
        viewport: Rect,
        origin: Offset,
        bounds: Rect,
        radius: Float,
        shape: Shape,
        tint: Color,
        highlightAlpha: Float,
        tintMultiplier: Float = 1.25f,
        refractionHeight: Dp = 10.dp,
        refractionAmount: Dp = 24.dp,
        navigationOutline: Boolean = true,
        navigationMaterial: Boolean = false,
        dropShadowAlpha: Float = 0f,
        isDark: Boolean = false,
        sampleScale: Offset = Offset(1f, 1f),
        cornerRadii: Offset? = null,
        lensBounds: Rect? = null,
        secondaryLensBounds: Rect? = null,
        secondaryLensRadii: Offset? = null,
        lensSmoothing: Float = 0f,
        menuField: LiquidMenuFieldTexture? = null,
        pressHighlight: GlassPressHighlight? = null,
        pressHighlightBounds: Rect? = null,
        outlinePath: Path? = null,
    ) = with(scope) {
        if (bounds.isEmpty || viewport.isEmpty) return@with
        val recordedLayoutDirection = layoutDirection
        val path = outlinePath ?: reusableOutlinePath.apply {
            rewind()
            when (val outline = shape.createOutline(bounds.size, layoutDirection, this@with)) {
                is Outline.Rectangle -> addRect(outline.rect)
                is Outline.Rounded -> addRoundRect(outline.roundRect)
                is Outline.Generic -> addPath(outline.path)
            }
            translate(bounds.topLeft)
        }
        val sourceOffset = origin - viewport.topLeft
        if (dropShadowAlpha > 0f) {
            dropShadow.draw(this, shape, bounds, alpha = dropShadowAlpha)
        }
        if (appearance.usesBackdrop && recordedViewport != viewport) {
            val bufferSize = IntSize(
                ceil(viewport.width * renderScale).toInt(),
                ceil(viewport.height * renderScale).toInt(),
            )
            // The source display list contains the entire page. Its logical size alone
            // does not clip that list: bound both offscreen passes to the capture band.
            source.clip = true
            refracted.clip = true
            source.renderEffect = sourceEffect
            val root = coordinates.findRootCoordinates()
            source.record(
                density = this@GlassMorphRenderer.density,
                layoutDirection = recordedLayoutDirection,
                size = bufferSize,
            ) {
                withTransform({
                    scale(renderScale, renderScale, Offset.Zero)
                    translate(-viewport.left, -viewport.top)
                }) {
                    // CanvasBackdrop also reads DrawScope.size. Record in root coordinates
                    // so its base fill and every LayerBackdrop cover the same capture band.
                    val previousSize = drawContext.size
                    drawContext.size = Size(root.size.width.toFloat(), root.size.height.toFloat())
                    try {
                        if (backdrop is ViewportBackdrop) {
                            with(backdrop) { drawViewport(this@GlassMorphRenderer.density, root, viewport) }
                        } else {
                            with(backdrop) { drawBackdrop(this@GlassMorphRenderer.density, root, null) }
                        }
                    } finally {
                        drawContext.size = previousSize
                    }
                }
            }
            refracted.record(
                density = this@GlassMorphRenderer.density,
                layoutDirection = recordedLayoutDirection,
                size = bufferSize,
            ) { drawLayer(source) }
            recordedViewport = viewport
        }
        val refractionEffect = if (shader != null) {
            val opticalBounds = lensBounds ?: bounds
            val sampleBounds = Rect(
                sourceOffset.x + opticalBounds.left * sampleScale.x,
                sourceOffset.y + opticalBounds.top * sampleScale.y,
                sourceOffset.x + opticalBounds.right * sampleScale.x,
                sourceOffset.y + opticalBounds.bottom * sampleScale.y,
            )
            shader.setFloatUniform("center", sampleBounds.center.x * renderScale, sampleBounds.center.y * renderScale)
            shader.setFloatUniform("halfSize", sampleBounds.width * renderScale / 2f, sampleBounds.height * renderScale / 2f)
            shader.setFloatUniform("radius", radius * minOf(sampleScale.x, sampleScale.y) * renderScale)
            shader.setFloatUniform("ellipticalCorners", if (cornerRadii != null) 1f else 0f)
            shader.setFloatUniform("cornerRadii",
                (cornerRadii?.x ?: radius) * sampleScale.x * renderScale,
                (cornerRadii?.y ?: radius) * sampleScale.y * renderScale,
            )
            shader.setFloatUniform("secondaryEnabled", if (secondaryLensBounds != null) 1f else 0f)
            val secondary = secondaryLensBounds ?: opticalBounds
            shader.setFloatUniform("secondaryCenter",
                (sourceOffset.x + secondary.center.x * sampleScale.x) * renderScale,
                (sourceOffset.y + secondary.center.y * sampleScale.y) * renderScale,
            )
            shader.setFloatUniform("secondaryHalfSize", secondary.width * sampleScale.x * renderScale / 2f, secondary.height * sampleScale.y * renderScale / 2f)
            shader.setFloatUniform("secondaryRadii", (secondaryLensRadii?.x ?: radius) * sampleScale.x * renderScale, (secondaryLensRadii?.y ?: radius) * sampleScale.y * renderScale)
            shader.setFloatUniform("smoothing", lensSmoothing * minOf(sampleScale.x, sampleScale.y) * renderScale)
            bindLiquidMenuField(shader, menuField,
                coordinateScale = Offset(sampleScale.x * renderScale, sampleScale.y * renderScale),
                coordinateOffset = sourceOffset * renderScale,
            )
            shader.setFloatUniform("sampleExtent", ceil(viewport.width * renderScale), ceil(viewport.height * renderScale))
            shader.setFloatUniform("drawingBounds",
                (sourceOffset.x + bounds.left * sampleScale.x) * renderScale - 1f,
                (sourceOffset.y + bounds.top * sampleScale.y) * renderScale - 1f,
                (sourceOffset.x + bounds.right * sampleScale.x) * renderScale + 1f,
                (sourceOffset.y + bounds.bottom * sampleScale.y) * renderScale + 1f,
            )
            shader.setFloatUniform("height", refractionHeight.toPx() * renderScale)
            shader.setFloatUniform("amount", -refractionAmount.toPx() * renderScale)
            RenderEffect.createRuntimeShaderEffect(shader, "content")
        } else null
        val sampledEffect = if (navigationMaterial && !isDark && shadowLiftEffect != null) {
            if (refractionEffect != null) RenderEffect.createChainEffect(shadowLiftEffect, refractionEffect)
            else shadowLiftEffect
        } else refractionEffect
        refracted.renderEffect = sampledEffect?.asComposeRenderEffect()

        if (navigationOutline) {
            val sideExpansion = 1.4.dp.toPx()
            withTransform({
                scale((bounds.width + sideExpansion) / bounds.width, 1f, bounds.center)
            }) {
                drawPath(path, Color(0xFF3F3F3F).copy(alpha = GlassBoxShadowAlpha))
            }
            drawPath(path, Color(0xFF6E6E6E).copy(alpha = GlassBoxShadowAlpha), style = Stroke(0.65.dp.toPx()))
        }
        val opticalHighlightBounds = lensBounds ?: bounds
        highlight.setFloatUniform("center", opticalHighlightBounds.center.x, opticalHighlightBounds.center.y)
        highlight.setFloatUniform("halfSize", opticalHighlightBounds.width / 2f, opticalHighlightBounds.height / 2f)
        highlight.setFloatUniform("radius", radius)
        highlight.setFloatUniform("ellipticalCorners", if (cornerRadii != null) 1f else 0f)
        highlight.setFloatUniform("cornerRadii", cornerRadii?.x ?: radius, cornerRadii?.y ?: radius)
        highlight.setFloatUniform("secondaryEnabled", if (secondaryLensBounds != null) 1f else 0f)
        val secondaryHighlight = secondaryLensBounds ?: opticalHighlightBounds
        highlight.setFloatUniform("secondaryCenter", secondaryHighlight.center.x, secondaryHighlight.center.y)
        highlight.setFloatUniform("secondaryHalfSize", secondaryHighlight.width / 2f, secondaryHighlight.height / 2f)
        highlight.setFloatUniform("secondaryRadii", secondaryLensRadii?.x ?: radius, secondaryLensRadii?.y ?: radius)
        highlight.setFloatUniform("smoothing", lensSmoothing)
        bindLiquidMenuField(highlight, menuField)
        highlight.setFloatUniform("opacity", highlightAlpha)
        // This rectangle anchors the light field; only the moving glass outline clips it.
        val lightBounds = pressHighlightBounds ?: bounds
        val lightPosition = (pressHighlight?.position ?: Offset(0.5f, 0.5f)).let {
            if (pressHighlightBounds != null) it
            else Offset(it.x.coerceIn(0f, 1f), it.y.coerceIn(0f, 1f))
        }
        highlight.setFloatUniform(
            "pressPosition",
            lightBounds.left + lightBounds.width * lightPosition.x,
            lightBounds.top + lightBounds.height * lightPosition.y,
        )
        highlight.setFloatUniform("pressRadius", (lightBounds.size.minDimension * 1.5f).coerceAtLeast(1f))
        highlight.setFloatUniform(
            "pressOpacity",
            if (pressHighlightBounds != null) 0.5f * 0.38f * (pressHighlight?.progress ?: 0f) else 0f,
        )
        val useNavigationMaterial = navigationMaterial && appearance.usesBackdrop
        var materialOrigin = Offset.Zero
        if (useNavigationMaterial) {
            val nativeSize = size
            // The material has no filter of its own. Its guarded source/effect layers
            // keep the full optical capture; only the final compositing target is cropped.
            // Integer texel origins preserve the original raster and bilinear sample grid.
            materialOrigin = Offset(
                floor((sourceOffset.x + bounds.left) * renderScale) - 1f,
                floor((sourceOffset.y + bounds.top) * renderScale) - 1f,
            )
            // Reserve the stable endpoint host on the first frame, avoiding repeated
            // allocations while the opening droplet grows through the size buckets.
            val requiredWidth = maxOf(
                (ceil((sourceOffset.x + bounds.right) * renderScale) + 1f - materialOrigin.x).toInt(),
                ceil(nativeSize.width * renderScale).toInt() + 3,
            )
            val requiredHeight = maxOf(
                (ceil((sourceOffset.y + bounds.bottom) * renderScale) + 1f - materialOrigin.y).toInt(),
                ceil(nativeSize.height * renderScale).toInt() + 3,
            )
            materialWidth = maxOf(materialWidth, (requiredWidth + 63) / 64 * 64)
            materialHeight = maxOf(materialHeight, (requiredHeight + 63) / 64 * 64)
            val bufferSize = IntSize(materialWidth, materialHeight)
            material.clip = true
            material.compositingStrategy = CompositingStrategy.Offscreen
            material.record(
                density = this@GlassMorphRenderer.density,
                layoutDirection = recordedLayoutDirection,
                size = bufferSize,
            ) {
                // Material pixels map to (local + sourceOffset) * renderScale - origin.
                withTransform({
                    translate(-materialOrigin.x, -materialOrigin.y)
                    scale(renderScale, renderScale, Offset.Zero)
                    translate(sourceOffset.x, sourceOffset.y)
                }) {
                    val previousSize = drawContext.size
                    drawContext.size = nativeSize
                    try {
                        withTransform({
                            scale(1f / sampleScale.x, 1f / sampleScale.y, Offset.Zero)
                            translate(-sourceOffset.x, -sourceOffset.y)
                            scale(1f / renderScale, 1f / renderScale, Offset.Zero)
                        }) { drawLayer(refracted) }
                        inset(bounds.left, bounds.top, size.width - bounds.right, size.height - bounds.bottom) {
                            drawNavigationGlassMaterial(tint, isDark, tintMultiplier)
                        }
                        innerShadows.draw(this, shape, bounds, isDark)
                    } finally {
                        drawContext.size = previousSize
                    }
                }
            }
        } else {
            clipGlassShape(bounds, radius, shape, path) {
                if (appearance.usesBackdrop) {
                    withTransform({
                        scale(1f / sampleScale.x, 1f / sampleScale.y, Offset.Zero)
                        translate(-sourceOffset.x, -sourceOffset.y)
                        scale(1f / renderScale, 1f / renderScale, Offset.Zero)
                    }) { drawLayer(refracted) }
                }
                drawRect(
                    tint.copy(
                        alpha = if (appearance.reduceTransparency) 1f
                            else (tint.alpha * tintMultiplier).coerceIn(0f, 1f),
                    ),
                    topLeft = bounds.topLeft,
                    size = bounds.size,
                )
            }
        }
        clipGlassShape(bounds, radius, shape, path) {
            if (useNavigationMaterial) {
                withTransform({
                    translate(
                        materialOrigin.x / renderScale - sourceOffset.x,
                        materialOrigin.y / renderScale - sourceOffset.y,
                    )
                    scale(1f / renderScale, 1f / renderScale, Offset.Zero)
                }) { drawLayer(material) }
            }
            drawPath(
                path,
                highlightBrush,
                style = Stroke(ceil(0.5.dp.toPx()) * 2f),
                blendMode = androidx.compose.ui.graphics.BlendMode.Plus,
            )
            if (pressHighlight != null && pressHighlight.progress > 0f) {
                inset(bounds.left, bounds.top, size.width - bounds.right, size.height - bounds.bottom) {
                    val source = lightBounds.translate(-bounds.topLeft)
                    pressLight.draw(
                        this,
                        pressHighlight.progress,
                        source.topLeft + Offset(
                            source.width * pressHighlight.position.x,
                            source.height * pressHighlight.position.y,
                        ),
                        sourceBounds = source,
                        clampPosition = pressHighlightBounds == null,
                    )
                }
            }
        }
    }
}

/**
 * Remember outside the animation branch; only attach while a home control morphs.
 * Shaders/layers survive endpoints and the normal material resumes at rest.
 * The capture band covers both width endpoints and the mini-player's vertical travel.
 * Content and hit targets retain their original layout and gesture modifiers.
 */
@Composable
internal fun rememberMorphingNavigationGlass(
    backdrop: Backdrop,
    active: Boolean,
    shape: Shape,
    tint: Color,
    navigationMaterial: Boolean,
    pressProgress: () -> Float,
    layerBlock: GraphicsLayerScope.() -> Unit,
    isDark: Boolean = false,
    tintMultiplier: Float = 1.25f,
    lensSource: Boolean = false,
    captureWidth: Dp? = null,
    verticalTravel: Dp = 0.dp,
    morphProgress: () -> Float = { 0f },
    backdropPosition: (() -> Offset)? = null,
): Modifier {
    val renderer = rememberGlassMorphRenderer(backdrop, active, if (lensSource) 8.dp else 2.dp)
    val view = LocalView.current
    if (!active) return Modifier
    val recording = remember(renderer, view.width, view.height) { MorphCaptureBand() }
    return Modifier.graphicsLayer(layerBlock)
        .onGloballyPositioned { recording.coordinates = it }
        .drawWithContent {
            val coordinates = recording.coordinates
            if (coordinates != null && coordinates.isAttached) {
                // Local backdrop layers can move inside a fixed capture band. Refresh
                // their recorded translation without replacing the buffers or shaders.
                val sourcePosition = backdropPosition?.invoke()
                if (recording.sourcePosition != sourcePosition) {
                    renderer.invalidateRecording()
                    recording.sourcePosition = sourcePosition
                }
                val origin = coordinates.positionInRoot()
                val initialTop = origin.y - verticalTravel.toPx() * morphProgress().coerceIn(0f, 1f)
                val captureRight = if (captureWidth != null) origin.x + size.width + 16.dp.toPx()
                    else view.width.toFloat().coerceAtLeast(size.width)
                val capturePadding = 16.dp.toPx()
                val cachedViewport = recording.viewport
                // IME movement can carry a control beyond its normal morph path.
                // Keep reusing the capture until that extra travel leaves its band.
                val viewport = if (cachedViewport == null ||
                    origin.y < cachedViewport.top ||
                    origin.y + size.height > cachedViewport.bottom
                ) {
                    Rect(
                        left = if (captureWidth != null) captureRight - captureWidth.toPx() - 32.dp.toPx() else 0f,
                        top = initialTop - capturePadding,
                        right = captureRight,
                        bottom = initialTop + maxOf(size.height, 64.dp.toPx()) + verticalTravel.toPx() + capturePadding,
                    ).also { recording.viewport = it }
                } else cachedViewport
                val visualEnd = coordinates.localToRoot(Offset(size.width, size.height))
                val sampleScale = Offset(
                    ((visualEnd.x - origin.x) / size.width.coerceAtLeast(1f)).coerceAtLeast(0.001f),
                    ((visualEnd.y - origin.y) / size.height.coerceAtLeast(1f)).coerceAtLeast(0.001f),
                )
                val press = pressProgress()
                renderer.draw(
                    scope = this,
                    coordinates = coordinates,
                    viewport = viewport,
                    origin = origin,
                    bounds = Rect(Offset.Zero, size),
                    radius = size.minDimension / 2f,
                    shape = shape,
                    tint = tint,
                    highlightAlpha = if (lensSource) 0.5f * 0.94f * press else 0.5f * (0.54f + 0.38f * press),
                    tintMultiplier = tintMultiplier,
                    refractionHeight = if (lensSource) 24.dp * press else 10.dp,
                    refractionAmount = if (lensSource) 28.dp * press else 24.dp,
                    navigationOutline = !lensSource,
                    navigationMaterial = navigationMaterial,
                    dropShadowAlpha = if (lensSource) 0f else 1f,
                    isDark = isDark,
                    sampleScale = sampleScale,
                )
            }
            drawContent()
        }
        // Match drawBackdrop's descendant clip. The renderer's exterior shadow stays outside it.
        .clip(shape)
}

private class MorphCaptureBand {
    var coordinates: LayoutCoordinates? = null
    var viewport: Rect? = null
    var sourcePosition: Offset? = null
}

// Same edge depth and seven-sample dispersion as Navigation glass, in a fixed viewport.
// Pixels outside the moving shell bypass sampling; Compose clips its continuous outline.
internal const val LiquidMorphFieldShader = """
uniform shader smoothedField;
uniform float smoothedFieldEnabled;
uniform float2 fieldCoordinateScale;
uniform float2 fieldCoordinateOffset;
uniform float fieldDistanceScale;
uniform float2 fieldGradientScale;
uniform float2 fieldExtent;
// QuartzCore supercircle_sdf_sampler (24A434, 0x1c39c8dd0).
// Oversized radii are intentional during overrounding; the native builder's
// curvature terms handle them before the two fields are combined.
float3 roundedFieldGradient(float2 point, float2 origin, float2 extent, float2 radii) {
    float2 r = max(radii, float2(0.001));
    float radius = min(r.x, r.y);
    float2 inverseAxis = radius / r;
    float2 local = (point - origin) * inverseAxis;
    float2 halfExtent = extent * inverseAxis;
    float expandedRadius = radius * 1.5286649465560913;
    float2 curvature = clamp(2.8915570351735034 -
        1.4457785175867517 * (halfExtent * 2.0) / (radius * 1.528665), 0.0, 1.0);
    float baseRadius = radius * (1.5286649465560913 -
        0.5286649465560913 * max(curvature.x, curvature.y));
    float2 edge = abs(local) - halfExtent;
    float2 circle = edge + baseRadius;
    float interior = min(max(circle.x, circle.y), 0.0);
    float2 uv = (edge + expandedRadius) / expandedRadius;
    float2 z = max(uv, 0.0);
    float t = min(z.x, z.y) / max(max(z.x, z.y), 0.000001);
    float d = length(z);
    float bezier = ((((-0.9260540008544922 * t + 3.1560099124908447) * t -
        3.6412200927734375) * t + 1.268030047416687) * t + 0.2685309946537018) * t * t;
    float raw = d + 1.0 - 1.0 / (1.0 - bezier * min(d, 1.0));
    float alternate = length(max(1.5286649465560913 * uv - 0.5286649465560913, 0.0));
    float difference = 0.3458344340324402 - raw + 0.6541655659675598 * alternate;
    float weight = clamp((z.x < z.y ? 1.0 : -1.0) * (t - 1.0) + 0.5, 0.0, 1.0);
    float distance = interior + expandedRadius *
        (raw - 1.0 + difference * mix(curvature.x, curvature.y, weight));
    float2 normal = max(circle, 0.0);
    float normalLength = length(normal);
    normal = normalLength > 0.000001 ? normal / normalLength :
        (circle.x > circle.y ? float2(1.0, 0.0) : float2(0.0, 1.0));
    normal *= float2(local.x < 0.0 ? -1.0 : 1.0, local.y < 0.0 ? -1.0 : 1.0);
    float2 oval = float2(local.x, local.y * halfExtent.x / max(halfExtent.y, 0.000001));
    float ovalLength = length(oval);
    oval = ovalLength > 0.000001 ? oval / ovalLength : normal;
    normal = mix(normal, oval, 0.5);
    normal /= max(length(normal), 0.000001);
    // CASDF transforms the canonical gradient after normalization, without
    // renormalizing it before SDFUnion computes the alignment-dependent radius.
    return float3(distance, normal * inverseAxis);
}
float3 liquidFieldGradient(float2 point) {
    if (smoothedFieldEnabled > 0.5) {
        float2 pixel = clamp(point * fieldCoordinateScale + fieldCoordinateOffset,
            float2(0.5), fieldExtent - float2(0.5));
        float3 field = smoothedField.eval(pixel).rgb;
        return field * float3(fieldDistanceScale, fieldGradientScale);
    }
    float3 a = roundedFieldGradient(point, center, halfSize, cornerRadii);
    if (secondaryEnabled < 0.5) return a;
    float3 b = roundedFieldGradient(point, secondaryCenter, secondaryHalfSize, secondaryRadii);
    // QuartzCore SDFUnion, iOS 24A434, 0x1c39e27d0: only opposing
    // unit gradients widen the polynomial union between the two skins.
    float k = smoothing * clamp(0.5 - 0.5 * dot(a.yz, b.yz), 0.0, 1.0);
    if (k <= 0.000001) return a.x < b.x ? a : b;
    float h = clamp(0.5 + 0.5 * (a.x - b.x) / k, 0.0, 1.0);
    return float3(mix(a.x, b.x, h) - k * h * (1.0 - h), mix(a.yz, b.yz, h));
}

"""

private const val TransitionRefractionShader = """
uniform shader content;
uniform float2 sampleExtent;
uniform float4 drawingBounds;
uniform float2 center;
uniform float2 halfSize;
uniform float radius;
uniform float2 cornerRadii;
uniform float ellipticalCorners;
uniform float secondaryEnabled;
uniform float2 secondaryCenter;
uniform float2 secondaryHalfSize;
uniform float2 secondaryRadii;
uniform float smoothing;
uniform float height;
uniform float amount;

float2 safeNormal(float2 v) { return v / max(length(v), 0.0001); }

$LiquidMorphFieldShader

half4 sampleContent(float2 coord) {
    return content.eval(clamp(coord, float2(0.5), sampleExtent - float2(0.5)));
}

half4 main(float2 coord) {
    if (ellipticalCorners > 0.5 && (coord.x < drawingBounds.x || coord.y < drawingBounds.y ||
        coord.x > drawingBounds.z || coord.y > drawingBounds.w)) return half4(0.0);
    if (height <= 0.0 || amount == 0.0) return sampleContent(coord);
    float2 p = coord - center;
    float2 q = abs(p) - halfSize + radius;
    float sd = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;
    float3 liquid = float3(0.0);
    if (ellipticalCorners > 0.5) {
        liquid = liquidFieldGradient(coord);
        sd = liquid.x;
    }
    if (secondaryEnabled < 0.5 && (abs(p.x) > halfSize.x + 1.0 || abs(p.y) > halfSize.y + 1.0)) return half4(0.0);
    if (-sd >= height) return sampleContent(coord);
    float x = clamp(1.0 + min(sd, 0.0) / height, 0.0, 1.0);
    float d = (1.0 - sqrt(max(0.0, 1.0 - x * x))) * amount;
    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
    float2 corner = abs(p) - halfSize + gradRadius;
    float gx = step(corner.y, corner.x);
    float2 grad = sign(p) * (max(corner.x, corner.y) >= 0.0
        ? safeNormal(max(corner, 0.0)) : float2(gx, 1.0 - gx));
    if (ellipticalCorners > 0.5) {
        grad = liquid.yz;
    } else {
        grad = safeNormal(grad + safeNormal(p));
    }
    float2 refracted = coord + d * grad;
    float dispersionFactor = clamp(p.x * p.y / max(halfSize.x * halfSize.y, 0.0001), -1.0, 1.0);
    float2 dispersion = d * grad * dispersionFactor;
    half4 red = sampleContent(refracted + dispersion);
    half4 orange = sampleContent(refracted + dispersion * (2.0 / 3.0));
    half4 yellow = sampleContent(refracted + dispersion * (1.0 / 3.0));
    half4 green = sampleContent(refracted);
    half4 cyan = sampleContent(refracted - dispersion * (1.0 / 3.0));
    half4 blue = sampleContent(refracted - dispersion * (2.0 / 3.0));
    half4 purple = sampleContent(refracted - dispersion);
    return half4(
        (red.r + orange.r + yellow.r) / 3.5 + purple.r / 7.0,
        (yellow.g + green.g + cyan.g) / 3.5 + orange.g / 7.0,
        (cyan.b + blue.b + purple.b) / 3.0,
        (red.a + orange.a + yellow.a + green.a + cyan.a + blue.a + purple.a) / 7.0
    );
}
"""

// Navigation's 90-degree directional highlight, drawn on the full-resolution outline.
private const val TransitionHighlightShader = """
uniform float2 center;
uniform float2 halfSize;
uniform float radius;
uniform float2 cornerRadii;
uniform float ellipticalCorners;
uniform float secondaryEnabled;
uniform float2 secondaryCenter;
uniform float2 secondaryHalfSize;
uniform float2 secondaryRadii;
uniform float smoothing;
uniform float opacity;
uniform float2 pressPosition;
uniform float pressRadius;
uniform float pressOpacity;
$LiquidMorphFieldShader
half4 main(float2 coord) {
    float2 p = coord - center;
    float r = min(radius * 1.5, min(halfSize.x, halfSize.y));
    float2 corner = abs(p) - halfSize + r;
    float gx = step(corner.y, corner.x);
    float2 v = max(corner, 0.0);
    float2 grad = max(corner.x, corner.y) >= 0.0
        ? v / max(length(v), 0.0001) : float2(gx, 1.0 - gx);
    if (ellipticalCorners > 0.5) {
        float2 eg = liquidFieldGradient(coord).yz;
        grad = eg / max(length(eg), 0.0001);
    }
    float pressDistance = distance(coord, pressPosition);
    float pressFalloff = 1.0 - smoothstep(pressRadius * 0.5, pressRadius, pressDistance);
    return half4((opacity + pressOpacity * pressFalloff) * abs(grad.y));
}
"""
