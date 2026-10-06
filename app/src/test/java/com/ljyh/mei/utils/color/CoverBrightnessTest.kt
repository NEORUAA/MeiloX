package com.ljyh.mei.utils.color

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CoverBrightnessTest {
    @Test
    fun opaqueWhiteAndBlackHaveExpectedBrightness() {
        assertEquals(false, classify(0xFFFFFFFF.toInt()))
        assertEquals(true, classify(0xFF000000.toInt()))
        assertEquals(1.0, luminance(0xFFFFFFFF.toInt()), 0.000001)
        assertEquals(0.0, luminance(0xFF000000.toInt()), 0.000001)
    }

    @Test
    fun saturatedColorsUsePerceptualChannelWeights() {
        assertEquals(false, classify(0xFFFF0000.toInt()))
        assertEquals(false, classify(0xFF00FF00.toInt()))
        assertEquals(true, classify(0xFF0000FF.toInt()))
        assertEquals(0.2126, luminance(0xFFFF0000.toInt()), 0.000001)
        assertEquals(0.7152, luminance(0xFF00FF00.toInt()), 0.000001)
        assertEquals(0.0722, luminance(0xFF0000FF.toInt()), 0.000001)
    }

    @Test
    fun grayscaleBoundaryUsesLinearLuminance() {
        assertEquals(true, classify(0xFF757575.toInt()))
        assertEquals(false, classify(0xFF767676.toInt()))
    }

    @Test
    fun transparentPixelsDoNotChooseTheTheme() {
        val pixels = intArrayOf(0x00FFFFFF, 0xFF000000.toInt(), 0x00FFFFFF, 0xFF000000.toInt())
        assertEquals(true, CoverBrightness.isDark(pixels, 2, 2))
        assertEquals(0.0, CoverBrightness.averageLuminance(pixels, 2, 2)!!, 0.000001)
        assertNull(CoverBrightness.isDark(IntArray(4) { 0x00FFFFFF }, 2, 2))
    }

    @Test
    fun translucentPixelsAreAlphaWeighted() {
        val pixels = intArrayOf(0x01FFFFFF, 0xFF000000.toInt(), 0x01FFFFFF, 0xFF000000.toInt())
        assertEquals(1.0 / 256.0, CoverBrightness.averageLuminance(pixels, 2, 2)!!, 0.000001)
        assertEquals(true, CoverBrightness.isDark(pixels, 2, 2))
        assertEquals(1.0, luminance(0x01FFFFFF), 0.000001)
    }

    @Test
    fun landscapeImageExcludesSidesOutsideTheSquareCrop() {
        val pixels = IntArray(12) { 0xFFFFFFFF.toInt() }
        for (row in 0 until 2) {
            pixels[row * 6 + 2] = 0xFF000000.toInt()
            pixels[row * 6 + 3] = 0xFF000000.toInt()
        }
        assertEquals(true, CoverBrightness.isDark(pixels, 6, 2))
    }

    @Test
    fun portraitImageExcludesRowsOutsideTheSquareCrop() {
        val pixels = IntArray(12) { 0xFF000000.toInt() }
        for (index in 4 until 8) pixels[index] = 0xFFFFFFFF.toInt()
        assertEquals(false, CoverBrightness.isDark(pixels, 2, 6))
    }

    @Test
    fun oppositePixelBrightnessIsAveragedAfterLinearization() {
        val pixels = intArrayOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFF000000.toInt())
        assertEquals(0.5, CoverBrightness.averageLuminance(pixels, 2, 2)!!, 0.000001)
        assertEquals(false, CoverBrightness.isDark(pixels, 2, 2))
    }

    @Test
    fun invalidOrMissingImagesHaveNoClassification() {
        assertNull(CoverBrightness.isDark(intArrayOf(), 0, 0))
        assertNull(CoverBrightness.isDark(intArrayOf(), 1, 1))
        assertNull(CoverBrightness.isDark(IntArray(4), -2, 2))
    }

    private fun classify(pixel: Int): Boolean? = CoverBrightness.isDark(intArrayOf(pixel), 1, 1)

    private fun luminance(pixel: Int): Double =
        CoverBrightness.averageLuminance(intArrayOf(pixel), 1, 1)!!
}
