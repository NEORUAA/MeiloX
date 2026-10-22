package com.ljyh.mei.ui.liquidglass

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.util.fastCoerceIn
import com.kyant.backdrop.RuntimeShader
import com.kyant.backdrop.asComposeShader
import com.kyant.backdrop.isRuntimeShaderSupported

/** The same local press light for live controls and a sheet taking their place. */
internal class PressHighlightRenderer {
    private val shader = if (isRuntimeShaderSupported()) {
        RuntimeShader(
            """
uniform float2 size;
uniform float2 sourceOrigin;
layout(color) uniform half4 color;
layout(color) uniform half4 washColor;
uniform float radius;
uniform float horizontalRadius;
uniform float2 position;

half4 main(float2 coord) {
    float2 delta = coord - position;
    delta.x *= radius / horizontalRadius;
    float dist = length(delta);
    float intensity = smoothstep(radius, radius * 0.5, dist);
    float2 local = coord - sourceOrigin;
    float2 outside = max(max(-local, local - size), 0.0);
    float wash = 1.0 - smoothstep(0.0, radius, length(outside));
    return washColor * wash + color * intensity;
}""",
        )
    } else null

    fun draw(
        scope: DrawScope,
        progress: Float,
        position: Offset,
        sourceBounds: Rect = Rect(Offset.Zero, scope.size),
        clampPosition: Boolean = true,
        horizontalRadius: Float? = null,
    ) = with(scope) {
        if (progress <= 0f) return@with
        val radius = (sourceBounds.size.minDimension * 1.5f).coerceAtLeast(1f)
        val lightPosition = if (clampPosition) Offset(
            position.x.fastCoerceIn(sourceBounds.left, sourceBounds.right),
            position.y.fastCoerceIn(sourceBounds.top, sourceBounds.bottom),
        ) else position
        if (shader != null) {
            shader.apply {
                setFloatUniform("size", sourceBounds.width, sourceBounds.height)
                setFloatUniform("sourceOrigin", sourceBounds.left, sourceBounds.top)
                setColorUniform("color", Color.White.copy(0.15f * progress))
                setColorUniform("washColor", Color.White.copy(0.08f * progress))
                setFloatUniform("radius", radius)
                setFloatUniform("horizontalRadius", horizontalRadius?.coerceAtLeast(radius) ?: radius)
                setFloatUniform("position", lightPosition.x, lightPosition.y)
            }
            drawRect(ShaderBrush(shader.asComposeShader()), blendMode = BlendMode.Plus)
        } else if (sourceBounds != Rect(Offset.Zero, size)) {
            drawRect(
                Brush.radialGradient(
                    0f to Color.White.copy(0.25f * progress),
                    0.5f to Color.White.copy(0.25f * progress),
                    1f to Color.Transparent,
                    center = lightPosition,
                    radius = radius,
                ),
                blendMode = BlendMode.Plus,
            )
        } else {
            drawRect(Color.White.copy(0.25f * progress), blendMode = BlendMode.Plus)
        }
    }
}
