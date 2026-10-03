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
import androidx.compose.ui.graphics.graphicsLayer
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
import com.kyant.backdrop.Backdrop

// These passes only carry an already blurred backdrop. Keep the moving outline, tint,
// highlight and foreground at native resolution while reducing filter fill and bandwidth.
private const val DefaultGlassRenderScale = 0.5f

@Composable
internal fun rememberGlassMorphRenderer(
    backdrop: Backdrop,
    active: Boolean,
    blurRadius: Dp = 2.dp,
    renderScale: Float = DefaultGlassRenderScale,
): GlassMorphRenderer {
    val density = LocalDensity.current
    val source = rememberGraphicsLayer()
    val refracted = rememberGraphicsLayer()
    val renderer = remember(backdrop, density, blurRadius, renderScale, source, refracted) {
        GlassMorphRenderer(backdrop, density, blurRadius, renderScale, source, refracted)
    }
    DisposableEffect(renderer, active) {
        // Keep shaders/layers across endpoints, but never reuse an old capture when a new
        // transition starts after scrolling, changing pages, or updating the backdrop.
        if (active) renderer.invalidateRecording()
        onDispose { }
    }
    return renderer
}

/** Fixed-size source/effect layers shared by the player sheet and home control morphs. */
internal class GlassMorphRenderer(
    private val backdrop: Backdrop,
    private val density: Density,
    blurRadius: Dp,
    private val renderScale: Float,
    private val source: GraphicsLayer,
    private val refracted: GraphicsLayer,
) {
    private val shader = RuntimeShader(TransitionRefractionShader)
    private val highlight = RuntimeShader(TransitionHighlightShader)
    private val highlightBrush = ShaderBrush(highlight)
    private var recordedViewport: Rect? = null
    private val sourceEffect = run {
        val color = RenderEffect.createColorFilterEffect(
            ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(1.5f) }),
        )
        val blur = with(density) { blurRadius.toPx() } * renderScale
        RenderEffect.createBlurEffect(blur, blur, color, Shader.TileMode.CLAMP)
            .asComposeRenderEffect()
    }

    fun invalidateRecording() {
        recordedViewport = null
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
        sampleScale: Offset = Offset(1f, 1f),
    ) = with(scope) {
        if (bounds.isEmpty || viewport.isEmpty) return@with
        val outline = shape.createOutline(bounds.size, layoutDirection, this)
        val path = Path().apply {
            when (outline) {
                is Outline.Rectangle -> addRect(outline.rect)
                is Outline.Rounded -> addRoundRect(outline.roundRect)
                is Outline.Generic -> addPath(outline.path)
            }
            translate(bounds.topLeft)
        }
        val sourceOffset = origin - viewport.topLeft
        if (recordedViewport != viewport) {
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
            source.record(bufferSize) {
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
            refracted.record(bufferSize) { drawLayer(source) }
            recordedViewport = viewport
        }
        val sampleBounds = Rect(
            sourceOffset.x + bounds.left * sampleScale.x,
            sourceOffset.y + bounds.top * sampleScale.y,
            sourceOffset.x + bounds.right * sampleScale.x,
            sourceOffset.y + bounds.bottom * sampleScale.y,
        )
        shader.setFloatUniform("center", sampleBounds.center.x * renderScale, sampleBounds.center.y * renderScale)
        shader.setFloatUniform("halfSize", sampleBounds.width * renderScale / 2f, sampleBounds.height * renderScale / 2f)
        shader.setFloatUniform("radius", radius * minOf(sampleScale.x, sampleScale.y) * renderScale)
        shader.setFloatUniform("height", refractionHeight.toPx() * renderScale)
        shader.setFloatUniform("amount", -refractionAmount.toPx() * renderScale)
        refracted.renderEffect = RenderEffect.createRuntimeShaderEffect(shader, "content")
            .asComposeRenderEffect()

        if (navigationOutline) {
            val sideExpansion = 1.4.dp.toPx()
            withTransform({
                scale((bounds.width + sideExpansion) / bounds.width, 1f, bounds.center)
            }) {
                drawPath(path, Color(0xFF3F3F3F).copy(alpha = GlassBoxShadowAlpha))
            }
            drawPath(path, Color(0xFF6E6E6E).copy(alpha = GlassBoxShadowAlpha), style = Stroke(0.65.dp.toPx()))
        }
        highlight.setFloatUniform("center", bounds.center.x, bounds.center.y)
        highlight.setFloatUniform("halfSize", bounds.width / 2f, bounds.height / 2f)
        highlight.setFloatUniform("radius", radius)
        highlight.setFloatUniform("opacity", highlightAlpha)
        clipGlassShape(bounds, radius, shape, path) {
            withTransform({
                scale(1f / sampleScale.x, 1f / sampleScale.y, Offset.Zero)
                translate(-sourceOffset.x, -sourceOffset.y)
                scale(1f / renderScale, 1f / renderScale, Offset.Zero)
            }) { drawLayer(refracted) }
            drawRect(tint.copy(alpha = (tint.alpha * tintMultiplier).coerceIn(0f, 1f)))
            drawPath(
                path,
                highlightBrush,
                style = Stroke(ceil(0.5.dp.toPx()) * 2f),
                blendMode = androidx.compose.ui.graphics.BlendMode.Plus,
            )
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
    pressProgress: () -> Float,
    layerBlock: GraphicsLayerScope.() -> Unit,
    tintMultiplier: Float = 1.25f,
    lensSource: Boolean = false,
    captureWidth: Dp? = null,
    verticalTravel: Dp = 0.dp,
    morphProgress: () -> Float = { 0f },
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
                val origin = coordinates.positionInRoot()
                val initialTop = origin.y - verticalTravel.toPx() * morphProgress().coerceIn(0f, 1f)
                val captureRight = if (captureWidth != null) origin.x + size.width + 16.dp.toPx()
                    else view.width.toFloat().coerceAtLeast(size.width)
                val viewport = recording.viewport ?: Rect(
                    left = if (captureWidth != null) captureRight - captureWidth.toPx() - 32.dp.toPx() else 0f,
                    top = initialTop - 16.dp.toPx(),
                    right = captureRight,
                    bottom = initialTop + maxOf(size.height, 64.dp.toPx()) + verticalTravel.toPx() + 16.dp.toPx(),
                ).also { recording.viewport = it }
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
                    sampleScale = sampleScale,
                )
            }
            drawContent()
        }
}

private class MorphCaptureBand {
    var coordinates: LayoutCoordinates? = null
    var viewport: Rect? = null
}

// Same edge depth and seven-sample dispersion as Navigation glass, in a fixed viewport.
// Pixels outside the moving shell bypass sampling; Compose clips its continuous outline.
private const val TransitionRefractionShader = """
uniform shader content;
uniform float2 center;
uniform float2 halfSize;
uniform float radius;
uniform float height;
uniform float amount;

float2 safeNormal(float2 v) { return v / max(length(v), 0.0001); }

half4 main(float2 coord) {
    if (height <= 0.0 || amount == 0.0) return content.eval(coord);
    float2 p = coord - center;
    float2 q = abs(p) - halfSize + radius;
    float sd = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;
    if (abs(p.x) > halfSize.x + 1.0 || abs(p.y) > halfSize.y + 1.0) return half4(0.0);
    if (-sd >= height) return content.eval(coord);
    float x = clamp(1.0 + min(sd, 0.0) / height, 0.0, 1.0);
    float d = (1.0 - sqrt(max(0.0, 1.0 - x * x))) * amount;
    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
    float2 corner = abs(p) - halfSize + gradRadius;
    float gx = step(corner.y, corner.x);
    float2 grad = sign(p) * (max(corner.x, corner.y) >= 0.0
        ? safeNormal(max(corner, 0.0)) : float2(gx, 1.0 - gx));
    grad = safeNormal(grad + safeNormal(p));
    float2 refracted = coord + d * grad;
    float2 dispersion = d * grad * (p.x * p.y / max(halfSize.x * halfSize.y, 0.0001));
    half4 red = content.eval(refracted + dispersion);
    half4 orange = content.eval(refracted + dispersion * (2.0 / 3.0));
    half4 yellow = content.eval(refracted + dispersion * (1.0 / 3.0));
    half4 green = content.eval(refracted);
    half4 cyan = content.eval(refracted - dispersion * (1.0 / 3.0));
    half4 blue = content.eval(refracted - dispersion * (2.0 / 3.0));
    half4 purple = content.eval(refracted - dispersion);
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
uniform float opacity;
half4 main(float2 coord) {
    float2 p = coord - center;
    float r = min(radius * 1.5, min(halfSize.x, halfSize.y));
    float2 corner = abs(p) - halfSize + r;
    float gx = step(corner.y, corner.x);
    float2 v = max(corner, 0.0);
    float2 grad = max(corner.x, corner.y) >= 0.0
        ? v / max(length(v), 0.0001) : float2(gx, 1.0 - gx);
    return half4(opacity * abs(grad.y));
}
"""
