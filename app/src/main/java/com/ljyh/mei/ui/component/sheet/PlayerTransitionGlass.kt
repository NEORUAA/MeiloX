package com.ljyh.mei.ui.component.sheet

import android.graphics.RenderEffect
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.RuntimeXfermode
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.kyant.capsule.ContinuousRoundedRectangle
import com.ljyh.mei.ui.component.player.LocalMiniPlayerAppearance
import io.github.neoruaa.glasmio.core.LocalGlassColors
import io.github.neoruaa.glasmio.glass.GlassPressHighlight
import io.github.neoruaa.glasmio.glass.clipGlassShape
import io.github.neoruaa.glasmio.glass.render.Backdrop
import io.github.neoruaa.glasmio.glass.render.GlassColorSpaceShaderFunctions
import io.github.neoruaa.glasmio.core.defaultGlassColors
import kotlin.math.ceil

private const val TransitionSampleScale = 0.5f

@Composable
internal fun rememberPlayerTransitionGlassRenderer(
    backdrop: Backdrop,
    active: Boolean,
    isDark: Boolean,
): PlayerTransitionGlassRenderer {
    val material = rememberPlayerSheetGlassMorphRenderer(
        backdrop = backdrop,
        active = active,
        // Use the native control graph, including its main and fill frost passes.
        blurRadius = 5.dp,
        renderScale = TransitionSampleScale,
        isDark = isDark,
    )
    val pressSource = rememberGraphicsLayer()
    val pressResult = rememberGraphicsLayer()
    val preparation = rememberGraphicsLayer()
    return remember(material, pressSource, pressResult, preparation) {
        PlayerTransitionGlassRenderer(material, pressSource, pressResult, preparation)
    }
}

internal class PlayerTransitionGlassRenderer(
    val material: PlayerSheetGlassMorphRenderer,
    private val pressSource: GraphicsLayer,
    private val pressResult: GraphicsLayer,
    private val preparation: GraphicsLayer,
) {
    private var preparedViewport: Rect? = null
    private var preparedSourceSize = Size.Unspecified
    private var preparedDark: Boolean? = null
    private val path = Path()
    private var pathBounds: Rect? = null
    private var pathShape: Shape? = null
    private var pathDensity = 0f
    private var pathLayoutDirection: LayoutDirection? = null
    private val light = RuntimeShader(PlayerPressFieldShader + """
half4 main(float2 p) { return half4(playerPressMask(p), 0.0, 1.0); }
""")
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = light }
    private val blender = if (Build.VERSION.SDK_INT >= 36) PlayerPressBlender() else null
    private val sampledLight = if (Build.VERSION.SDK_INT < 36) RuntimeShader(
        GlassColorSpaceShaderFunctions + PlayerPressFieldShader + PlayerPressTransferShader + """
uniform shader content;
uniform float glowGain;
half4 main(float2 p) {
    half4 target = half4(glassWorkingToNativeGammaPremul(float4(content.eval(p))));
    half4 result = playerApplyPressGlow(target, playerPressMask(p), glowGain);
    return half4(glassNativeGammaToWorkingPremul(float4(result)));
}
""") else null

    init {
        val c = PlayerPressGlowCoefficients
        for (program in listOfNotNull(light, sampledLight)) {
            program.setFloatUniform("pressPolynomialLow", c[0], c[1], c[2], c[3])
            program.setFloatUniform("pressPolynomialHigh", c[4], c[5], c[6], c[7])
            program.setFloatUniform("pressPolynomialLast", c[8])
        }
    }

    /** Build the retained CPU effect graph before opening without drawing its pixels. */
    fun prepare(
        scope: DrawScope,
        viewport: Rect,
        sourceSize: Size,
        isDark: Boolean,
        draw: DrawScope.() -> Unit,
    ) {
        if (preparedViewport == viewport && preparedSourceSize == sourceSize && preparedDark == isDark) return
        preparation.record(scope, scope.layoutDirection,
            IntSize(ceil(scope.size.width).toInt(), ceil(scope.size.height).toInt()), draw)
        preparedViewport = viewport
        preparedSourceSize = sourceSize
        preparedDark = isDark
    }

    fun outline(scope: DrawScope, bounds: Rect, shape: Shape): Path {
        if (pathBounds != bounds || pathShape != shape || pathDensity != scope.density ||
            pathLayoutDirection != scope.layoutDirection) {
            path.rewind()
            when (val outline = shape.createOutline(bounds.size, scope.layoutDirection, scope)) {
                is Outline.Rectangle -> path.addRect(outline.rect)
                is Outline.Rounded -> path.addRoundRect(outline.roundRect)
                is Outline.Generic -> path.addPath(outline.path)
            }
            path.translate(bounds.topLeft)
            pathBounds = bounds
            pathShape = shape
            pathDensity = scope.density
            pathLayoutDirection = scope.layoutDirection
        }
        return path
    }

    fun drawWithPressLight(
        scope: DrawScope,
        highlight: GlassPressHighlight,
        source: Rect,
        sourceLayoutSize: Size = source.size,
        bounds: Rect,
        radius: Float,
        shape: Shape,
        isDark: Boolean,
        outlinePath: Path? = null,
        drawMaterial: DrawScope.() -> Unit,
    ) = with(scope) {
        if (highlight.glowProgress <= 0f || source.isEmpty || sourceLayoutSize.isEmpty()) {
            drawMaterial()
            return@with
        }
        val nominalExtent = sourceLayoutSize.minDimension
        val compact = sourceLayoutSize.maxDimension / density < 120f
        val flex = if (compact) 0f else ((nominalExtent / density - 44f) / 116f).coerceIn(0f, 1f)
        val opacity = highlight.glowProgress.coerceIn(0f, 1f)
        val expansion = 1f + 3f * highlight.dissipationProgress.coerceIn(0f, 1f)
        val lightRadius = minOf(nominalExtent * 0.75f, 80f * density) * expansion
        val gain = if (isDark) 4f else 1f / 0.6f
        val program = sampledLight ?: light
        program.setFloatUniform("sourceBounds", source.left, source.top, source.right, source.bottom)
        program.setFloatUniform("position", source.left + source.width * highlight.position.x,
            source.top + source.height * highlight.position.y)
        program.setFloatUniform("lightRadius", lightRadius * source.width / sourceLayoutSize.width,
            lightRadius * source.height / sourceLayoutSize.height)
        program.setFloatUniform("washRadius", source.size.minDimension * 1.5f)
        program.setFloatUniform("pressOpacity", opacity * (1f - flex),
            opacity * if (compact) 1f else 0.3f - 0.1f * flex)
        val path = outlinePath ?: outline(this, bounds, shape)
        if (Build.VERSION.SDK_INT >= 36) {
            drawMaterial()
            if (!drawContext.canvas.nativeCanvas.isHardwareAccelerated) return@with
            val transfer = checkNotNull(blender)
            transfer.shader.setFloatUniform("glowGain", gain)
            paint.xfermode = transfer.shader
            clipGlassShape(bounds, radius, shape, path) {
                drawContext.canvas.nativeCanvas.drawRect(0f, 0f, size.width, size.height, paint)
            }
        } else {
            // Older HWUI samples the same material transfer. Keep the shadow outside
            // the contour unfiltered and composite each material pixel only once.
            val bufferSize = IntSize(ceil(size.width).toInt(), ceil(size.height).toInt())
            pressSource.record(this, layoutDirection, bufferSize, drawMaterial)
            val sampled = checkNotNull(sampledLight)
            sampled.setFloatUniform("glowGain", gain)
            pressResult.renderEffect = RenderEffect.createRuntimeShaderEffect(sampled, "content")
                .asComposeRenderEffect()
            pressResult.record(this, layoutDirection, bufferSize) { drawLayer(pressSource) }
            clipPath(path, ClipOp.Difference) { drawLayer(pressSource) }
            clipGlassShape(bounds, radius, shape, path) { drawLayer(pressResult) }
        }
    }

    @RequiresApi(36)
    private class PlayerPressBlender {
        val shader = RuntimeXfermode(GlassColorSpaceShaderFunctions + PlayerPressTransferShader + """
uniform float glowGain;
half4 main(half4 source, half4 destination) {
    if (source.r <= 0.0 && source.g <= 0.0) return destination;
    half4 target = half4(glassWorkingToNativeGammaPremul(float4(destination)));
    half4 result = playerApplyPressGlow(target, float2(source.r, source.g), glowGain);
    return half4(glassNativeGammaToWorkingPremul(float4(result)));
}
""")
    }
}

/*
 * Press matrices and circular Gaussian adapted from Glasmio, Copyright 2026
 * Glasmio contributors. Licensed under the Apache License, Version 2.0.
 * https://www.apache.org/licenses/LICENSE-2.0
 * Keep the native transfer at takeover; only soften the wash outside the source.
 */
private const val PlayerPressFieldShader = """
uniform float4 sourceBounds;
uniform float2 position;
uniform float2 lightRadius;
uniform float washRadius;
uniform float2 pressOpacity;
uniform float4 pressPolynomialLow;
uniform float4 pressPolynomialHigh;
uniform float pressPolynomialLast;
float2 playerPressMask(float2 p) {
    float2 outside = max(max(sourceBounds.xy - p, p - sourceBounds.zw), 0.0);
    float wash = 1.0 - smoothstep(0.0, max(washRadius, 0.0001), length(outside));
    float x = min(length((p - position) / max(lightRadius, float2(0.0001))), 5.0);
    float q = x * x;
    float integral = pressPolynomialLast;
    integral = integral * q + pressPolynomialHigh.w;
    integral = integral * q + pressPolynomialHigh.z;
    integral = integral * q + pressPolynomialHigh.y;
    integral = integral * q + pressPolynomialHigh.x;
    integral = integral * q + pressPolynomialLow.w;
    integral = integral * q + pressPolynomialLow.z;
    integral = integral * q + pressPolynomialLow.y;
    integral = integral * q + pressPolynomialLow.x;
    return pressOpacity * float2(wash, clamp(exp(-0.5 * q) * integral, 0.0, 1.0));
}
"""

private const val PlayerPressTransferShader = """
float4 playerPressTransfer(float4 target, float3 transformed, float opacity) {
    float alpha = clamp(target.a, 0.0, 1.0);
    float4 source = float4(transformed * alpha, alpha) * opacity;
    return source + target * (1.0 - source.a);
}
half4 playerApplyPressGlow(half4 target, float2 opacity, float gain) {
    float4 result = float4(target);
    float3 color = result.rgb / max(result.a, 0.000001);
    float3 large = float3(
        dot(float3(1.1575000286, -0.1430999935, -0.0143999998), color),
        dot(float3(-0.0425000004, 1.0569999218, -0.0144999996), color),
        dot(float3(-0.0425999984, -0.1429999918, 1.1855999231), color)) + float3(.0499999970);
    result = playerPressTransfer(result, large, opacity.x);
    color = result.rgb / max(result.a, 0.000001);
    float3 small = float3(
        dot(float3(1.3937, -0.3577, -0.0360), color),
        dot(float3(-0.1063, 1.1424, -0.0361), color),
        dot(float3(-0.1064, -0.3575, 1.4639), color)) * gain;
    return half4(playerPressTransfer(result, small, opacity.y));
}
"""

/** Keep the approved motion and fixed source clarity under the growing glass contour. */
@Composable
internal fun PlayerTransitionGlass(
    layers: PlayerSheetLayers,
    renderer: PlayerTransitionGlassRenderer,
    expandedCornerRadius: Float,
    active: Boolean = true,
) {
    val appearance = LocalMiniPlayerAppearance.current
    val colors = appearance?.let { defaultGlassColors(it) } ?: LocalGlassColors.current
    val recording = remember(renderer) { SheetGlassCoordinates() }
    Canvas(Modifier.fillMaxSize().onGloballyPositioned { recording.coordinates = it }) {
        val coordinates = recording.coordinates ?: return@Canvas
        if (active) layers.updateTransitionFrame()
        val p = if (active) layers.motionProgress else 0f
        if (playerBackgroundAlpha(p) >= 1f) return@Canvas
        val bounds = (if (active) layers.containerBounds else layers.sourceContainer)
            .translate(-layers.hostBounds.topLeft)
        val radius = layers.containerCornerRadius(expandedCornerRadius).coerceIn(0f, bounds.size.minDimension / 2f)
        // Exercise both material and press-effect setup during the invisible preparation.
        val pressHighlight = if (active) layers.transitionPressHighlight else GlassPressHighlight(1f)
        val shape = ContinuousRoundedRectangle(radius.toDp())
        val outline = renderer.outline(this, bounds, shape)
        val viewport = Rect(coordinates.positionInRoot(), size)
        val sourceSize = layers.transitionPressLayoutSize
        val source = if (active)
            layers.transitionPressHighlightBounds.translate(-layers.hostBounds.topLeft) else bounds
        val draw: DrawScope.() -> Unit = {
            renderer.drawWithPressLight(
                scope = this,
                highlight = pressHighlight,
                source = source,
                sourceLayoutSize = sourceSize,
                bounds = bounds,
                radius = radius,
                shape = shape,
                isDark = colors.isDark,
                outlinePath = outline,
            ) {
                renderer.material.draw(
                    scope = this,
                    coordinates = coordinates,
                    viewport = viewport,
                    origin = viewport.topLeft,
                    bounds = bounds,
                    radius = radius,
                    shape = shape,
                    outlinePath = outline,
                    tint = colors.container,
                    navigationMaterial = true,
                    dropShadowAlpha = 1f - playerBackgroundAlpha(p),
                    isDark = colors.isDark,
                    highlightAlpha = 1f,
                    materialReferenceSize = sourceSize,
                    materialScale = layers.sourceContainer.size.minDimension /
                        sourceSize.minDimension.coerceAtLeast(1f),
                )
            }
        }
        if (active) draw()
        else renderer.prepare(this, viewport, sourceSize, colors.isDark, draw)
    }
}

private class SheetGlassCoordinates {
    var coordinates: LayoutCoordinates? = null
}
