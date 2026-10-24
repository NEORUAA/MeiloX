/*
 * Refraction math adapted from AndroidLiquidGlass, Copyright 2025 Kyant.
 * Licensed under the Apache License, Version 2.0.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package com.ljyh.mei.ui.glass

import com.kyant.backdrop.BackdropEffectScope
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.runtimeShaderEffect
import com.kyant.backdrop.isRuntimeShaderSupported
import kotlin.math.ceil

/** Redirects only the horizontal edge rays facing a neighboring bottom control. */
internal fun BackdropEffectScope.navigationContactLens(
    refractionHeight: Float,
    refractionAmount: Float,
    contactDirection: Float,
) {
    if (!isRuntimeShaderSupported()) return
    // Cache the parsed shader at rest instead of compiling its source during first contact.
    obtainRuntimeShader("NavigationContactLens", NavigationContactRefractionShader)
    val direction = contactDirection.coerceIn(-1f, 1f)
    if (direction == 0f) {
        lens(refractionHeight, refractionAmount, chromaticAberration = true)
        return
    }
    if (refractionHeight <= 0f || refractionAmount <= 0f) return

    // Cover the full outward displacement and dispersion without resizing on each press frame.
    padding = ceil(maxOf(padding, 2f * NavigationIndicatorRefractionAmount.toPx()))
    runtimeShaderEffect("NavigationContactLens", NavigationContactRefractionShader, "content") {
        setFloatUniform("size", size.width, size.height)
        setFloatUniform("offset", -padding, -padding)
        setFloatUniform("radius", size.minDimension / 2f)
        setFloatUniform("refractionHeight", refractionHeight)
        setFloatUniform("refractionAmount", -refractionAmount)
        setFloatUniform("contactDirection", direction)
    }
}

private const val NavigationContactRefractionShader = """
uniform shader content;
uniform float2 size;
uniform float2 offset;
uniform float radius;
uniform float refractionHeight;
uniform float refractionAmount;
uniform float contactDirection;

float sdRoundedRect(float2 coord, float2 halfSize, float cornerRadius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(cornerRadius));
    float outside = length(max(cornerCoord, 0.0)) - cornerRadius;
    float inside = min(max(cornerCoord.x, cornerCoord.y), 0.0);
    return outside + inside;
}

float2 gradSdRoundedRect(float2 coord, float2 halfSize, float cornerRadius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(cornerRadius));
    if (cornerCoord.x >= 0.0 || cornerCoord.y >= 0.0) {
        return sign(coord) * normalize(max(cornerCoord, 0.0));
    } else {
        float gradX = step(cornerCoord.y, cornerCoord.x);
        return sign(coord) * float2(gradX, 1.0 - gradX);
    }
}

float circleMap(float x) {
    return 1.0 - sqrt(1.0 - x * x);
}

half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 centeredCoord = (coord + offset) - halfSize;
    float sd = sdRoundedRect(centeredCoord, halfSize, radius);
    if (-sd >= refractionHeight) {
        return content.eval(coord);
    }
    sd = min(sd, 0.0);

    float d = circleMap(1.0 - -sd / refractionHeight) * refractionAmount;
    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
    float2 grad = normalize(gradSdRoundedRect(centeredCoord, halfSize, gradRadius));
    // Preserve vertical refraction and the opposite edge while opening the neighboring edge.
    float facing = smoothstep(0.0, 1.0, grad.x * sign(contactDirection));
    grad.x *= 1.0 - 2.0 * abs(contactDirection) * facing;

    float2 refractedCoord = coord + d * grad;
    float dispersionIntensity = (centeredCoord.x * centeredCoord.y) / (halfSize.x * halfSize.y);
    float2 dispersedCoord = d * grad * dispersionIntensity;

    half4 color = half4(0.0);
    half4 red = content.eval(refractedCoord + dispersedCoord);
    color.r += red.r / 3.5;
    color.a += red.a / 7.0;

    half4 orange = content.eval(refractedCoord + dispersedCoord * (2.0 / 3.0));
    color.r += orange.r / 3.5;
    color.g += orange.g / 7.0;
    color.a += orange.a / 7.0;

    half4 yellow = content.eval(refractedCoord + dispersedCoord * (1.0 / 3.0));
    color.r += yellow.r / 3.5;
    color.g += yellow.g / 3.5;
    color.a += yellow.a / 7.0;

    half4 green = content.eval(refractedCoord);
    color.g += green.g / 3.5;
    color.a += green.a / 7.0;

    half4 cyan = content.eval(refractedCoord - dispersedCoord * (1.0 / 3.0));
    color.g += cyan.g / 3.5;
    color.b += cyan.b / 3.0;
    color.a += cyan.a / 7.0;

    half4 blue = content.eval(refractedCoord - dispersedCoord * (2.0 / 3.0));
    color.b += blue.b / 3.0;
    color.a += blue.a / 7.0;

    half4 purple = content.eval(refractedCoord - dispersedCoord);
    color.r += purple.r / 7.0;
    color.b += purple.b / 3.0;
    color.a += purple.a / 7.0;
    return color;
}
"""
