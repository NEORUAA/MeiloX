package com.ljyh.mei.utils.color

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import com.materialkolor.hct.Hct
import kotlin.math.abs
import kotlin.math.pow

object ColorExtractionUtils {

    /** Preserves the cover's RGB hue with bounded chroma and perceptual lightness. */
    fun detailBackgroundColor(seedColor: Color, isDark: Boolean): Color {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(seedColor.toArgb(), hsl)
        val saturation = if (hsl[1] < 0.01f) {
            0f
        } else {
            hsl[1].coerceAtMost(if (isDark) 0.60f else 0.90f)
        }
        val tone = if (isDark) 22.0 else 85.0
        val targetLuminance = ((tone + 16.0) / 116.0).pow(3.0)
        fun colorAtSaturation(value: Float): Int {
            hsl[1] = value
            var lowerLightness = 0f
            var upperLightness = 1f
            var bestColor = seedColor.toArgb()
            var bestDifference = Double.POSITIVE_INFINITY
            repeat(16) {
                hsl[2] = (lowerLightness + upperLightness) / 2f
                val candidate = ColorUtils.HSLToColor(hsl)
                val luminance = ColorUtils.calculateLuminance(candidate)
                val difference = abs(luminance - targetLuminance)
                if (difference < bestDifference) {
                    bestColor = candidate
                    bestDifference = difference
                }
                if (luminance < targetLuminance) lowerLightness = hsl[2]
                else upperLightness = hsl[2]
            }
            return bestColor
        }

        val chromaLimit = if (isDark) 28.0 else 30.0
        val initialColor = colorAtSaturation(saturation)
        if (Hct.fromInt(initialColor).chroma <= chromaLimit) return Color(initialColor)

        // Equal HSL saturation can make greens and yellows much more vivid than blues.
        // Measure chroma without remapping the RGB hue as the lightness changes.
        var lowerSaturation = 0f
        var upperSaturation = saturation
        var bestColor = colorAtSaturation(0f)
        repeat(10) {
            val candidateSaturation = (lowerSaturation + upperSaturation) / 2f
            val candidate = colorAtSaturation(candidateSaturation)
            if (Hct.fromInt(candidate).chroma <= chromaLimit) {
                bestColor = candidate
                lowerSaturation = candidateSaturation
            } else upperSaturation = candidateSaturation
        }
        return Color(bestColor)
    }

    /** Scales linear RGB to 95% for a subtly darker gradient endpoint. */
    fun detailBackgroundEndColor(backgroundColor: Color): Color {
        val linear = backgroundColor.convert(ColorSpaces.LinearSrgb)
        return Color(
            red = linear.red * 0.95f,
            green = linear.green * 0.95f,
            blue = linear.blue * 0.95f,
            alpha = backgroundColor.alpha,
            colorSpace = ColorSpaces.LinearSrgb,
        ).convert(backgroundColor.colorSpace)
    }

    /**
     * Extracts a vibrant theme seed color from a bitmap using Android's Palette API.
     * This replicates the intent of SPlayer's Score.score by prioritizing vibrancy.
     */
    fun extractSPlayerThemeColor(bitmap: Bitmap): Color {
        // Quantize and extract using Palette
        val palette = Palette.from(bitmap)
            .maximumColorCount(128)
            .generate()

        // SPlayer priorities: High chroma/vibrancy over simple dominance.
        // We check swatches in order of visual interest.
        val targetSwatch = palette.vibrantSwatch 
            ?: palette.lightVibrantSwatch 
            ?: palette.darkVibrantSwatch
            ?: palette.dominantSwatch

        return if (targetSwatch != null) {
            Color(targetSwatch.rgb)
        } else {
            // Fallback
            Color(0xFF1A237E)
        }
    }

    /**
     * Generates a 4-color palette based on SPlayer's tonal logic.
     * @param seedColor The primary vibrant color extracted from the cover.
     * @param isDark Whether the system is in dark mode.
     */
    fun generateSPlayerPalette(seedColor: Color, isDark: Boolean): List<Color> {
        // Without MaterialKolor, we manually generate the 4 tonal analogues 
        // to mimic SPlayer's mesh gradient roles (Primary, Secondary, Tertiary, Container)
        
        var c1 = seedColor
        
        // Ensure the base color isn't too desaturated
        if (c1.isGrayscale()) c1 = c1.boostSaturation(2.5f)
        
        // C1: Base Primary
        // C2: Darker/lighter analogue (Background/Container)
        val c2 = if (isDark) c1.darken(0.4f) else c1.lighten(0.3f)
        
        // C3: Triadic or Hue-shifted tertiary
        val c3 = c1.shiftHue(45f)
        
        // C4: Another analogue or brightness variant
        val c4 = c1.shiftHue(-30f).lighten(0.1f)
        
        return listOf(c1, c2, c3, c4)
    }
}

// --- Extensions identical to AppleMusicFluidBackground's logic for standalone use ---
fun Color.isGrayscale(threshold: Float = 0.15f): Boolean {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(this.toArgb(), hsl)
    return hsl[1] < threshold 
}

fun Color.boostSaturation(multiplier: Float): Color {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(this.toArgb(), hsl)
    if (hsl[2] < 0.2f) hsl[2] = 0.2f
    hsl[1] = (hsl[1] * multiplier).coerceIn(0.2f, 1f) 
    return Color(ColorUtils.HSLToColor(hsl))
}

fun Color.darken(factor: Float): Color {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(this.toArgb(), hsl)
    hsl[2] = (hsl[2] * (1f - factor)).coerceIn(0f, 1f)
    return Color(ColorUtils.HSLToColor(hsl))
}

fun Color.lighten(factor: Float): Color {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(this.toArgb(), hsl)
    hsl[2] = (hsl[2] + (1f - hsl[2]) * factor).coerceIn(0f, 1f)
    return Color(ColorUtils.HSLToColor(hsl))
}

fun Color.shiftHue(amount: Float): Color {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(this.toArgb(), hsl)
    hsl[0] = (hsl[0] + amount).mod(360f)
    return Color(ColorUtils.HSLToColor(hsl))
}
