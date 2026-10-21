package com.ljyh.mei.ui.glass

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import com.kyant.backdrop.BackdropEffectScope
import com.kyant.backdrop.effects.runtimeShaderEffect

/** Lifts only the sampled shadows, before the navigation material is composited. */
internal fun BackdropEffectScope.liftNavigationShadows() {
    // The backdrop scope caches the compiled shader and chains it after prior effects.
    runtimeShaderEffect("NavigationShadowLift", NavigationShadowLiftShader, "content") {}
}

/** Retain this effect with the morph renderer rather than compiling it during drawing. */
internal fun navigationShadowLiftEffect(): RenderEffect =
    RenderEffect.createRuntimeShaderEffect(RuntimeShader(NavigationShadowLiftShader), "content")

internal const val NavigationShadowLiftShader = """
uniform shader content;

half4 main(float2 coord) {
    half4 sampled = content.eval(coord);
    float alpha = float(sampled.a);
    if (alpha <= 0.0) {
        return half4(0.0);
    }

    // Work on straight, linear sRGB so the lift behaves consistently across colour spaces.
    float3 linear = toLinearSrgb(float3(sampled.rgb) / alpha);
    float luminance = dot(linear, float3(0.2126, 0.7152, 0.0722));
    // Judge shadows by luminance so dark saturated colours are included as well.
    // Return normal/bright samples unchanged, starting at about neutral sRGB 0.46.
    const float shadowLimit = 0.18;
    if (luminance >= shadowLimit) {
        return sampled;
    }

    // Black rises to about sRGB 0.31; retain a visible lift after the neutral tint.
    float lift = 0.08 * (1.0 - smoothstep(0.0, shadowLimit, luminance));
    float3 lifted = fromLinearSrgb(min(linear + float3(lift), float3(1.0)));
    return half4(lifted * alpha, alpha);
}
"""
