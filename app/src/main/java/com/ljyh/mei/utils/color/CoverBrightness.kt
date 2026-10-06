package com.ljyh.mei.utils.color

import kotlin.math.min
import kotlin.math.pow

/** Classifies the visible center crop without depending on the application's theme. */
object CoverBrightness {
    // At this luminance, opaque black and white have equal WCAG contrast ratios.
    const val DARK_LUMINANCE_THRESHOLD = 0.179128784747792
    private const val MAX_SAMPLES_PER_SIDE = 64

    private val linearSrgb = DoubleArray(256) { channel ->
        val value = channel / 255.0
        if (value <= 0.04045) value / 12.92
        else ((value + 0.055) / 1.055).pow(2.4)
    }

    fun isDark(argb: IntArray, width: Int, height: Int): Boolean? =
        averageLuminance(argb, width, height)?.let { it < DARK_LUMINANCE_THRESHOLD }

    /**
     * Samples the centered square used by a 1:1 ContentScale.Crop image. Transparent
     * pixels contribute proportionally to their alpha, without assuming a background.
     * Missing or fully transparent images have no brightness classification.
     */
    fun averageLuminance(argb: IntArray, width: Int, height: Int): Double? {
        if (width <= 0 || height <= 0 || width.toLong() * height > argb.size) return null
        val side = min(width, height)
        val left = (width - side) / 2
        val top = (height - side) / 2
        val samples = min(side, MAX_SAMPLES_PER_SIDE)
        var weightedLuminance = 0.0
        var alphaWeight = 0.0
        for (row in 0 until samples) {
            val y = top + ((row + 0.5) * side / samples).toInt()
            for (column in 0 until samples) {
                val x = left + ((column + 0.5) * side / samples).toInt()
                val pixel = argb[y * width + x]
                val alpha = (pixel ushr 24) / 255.0
                if (alpha == 0.0) continue
                val red = linearSrgb[(pixel ushr 16) and 0xFF]
                val green = linearSrgb[(pixel ushr 8) and 0xFF]
                val blue = linearSrgb[pixel and 0xFF]
                weightedLuminance += alpha * (0.2126 * red + 0.7152 * green + 0.0722 * blue)
                alphaWeight += alpha
            }
        }
        return if (alphaWeight > 0.0) weightedLuminance / alphaWeight else null
    }
}
