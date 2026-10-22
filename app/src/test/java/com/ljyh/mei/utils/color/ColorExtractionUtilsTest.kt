package com.ljyh.mei.utils.color

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import com.materialkolor.hct.Hct
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ColorExtractionUtilsTest {
    @Test
    fun themeAccentsRemainReadableAcrossSurfacesAndSeedAnimations() {
        val seeds = listOf(
            Color.Black, Color.White, Color(0xFF131018), Color(0xFF808080),
            Color(0xFFFF0000), Color(0xFF00FF00), Color(0xFF0000FF), Color(0xFFFFCC00),
        )
        for (isDark in listOf(false, true)) {
            val backgrounds = if (isDark) listOf(Color.Black, Color(0xFF3A3A3C))
                else listOf(Color.White, Color(0xFFF2F2F7))
            for (from in seeds) for (to in seeds) for (step in 0..10) {
                val seed = lerp(from, to, step / 10f)
                val accent = ColorExtractionUtils.themeAccentColor(seed, isDark)
                assertReadable(accent, backgrounds)
                assertReadable(ColorExtractionUtils.accentContentColor(accent), listOf(accent))
            }
        }
    }

    @Test
    fun themeAccentsKeepReadableSeedsAndCorrectUnreadableHueWithoutAddingChroma() {
        assertEquals(Color.White, ColorExtractionUtils.themeAccentColor(Color.White, isDark = true))
        assertEquals(Color.Black, ColorExtractionUtils.themeAccentColor(Color.Black, isDark = false))
        for (isDark in listOf(false, true)) {
            for (seedColor in listOf(Color(0xFF170A20), Color(0xFFFFEAD0))) {
                val seed = Hct.fromInt(seedColor.toArgb())
                val accent = Hct.fromInt(ColorExtractionUtils.themeAccentColor(seedColor, isDark).toArgb())
                val hueDifference = abs(seed.hue - accent.hue).let { minOf(it, 360.0 - it) }
                assertTrue("Cover hue drifted by $hueDifference degrees", hueDifference < 4.0)
                assertTrue("Correcting tone should not add saturation", accent.chroma <= seed.chroma + 2.0)
            }
        }
    }

    @Test
    fun coverAccentsKeepTheirHueAndContrastOnLightAndDarkPages() {
        val seeds = listOf(
            Color(0xFFBB2649), Color(0xFF005AC1), Color(0xFF008A58),
            Color(0xFF8D3FC0), Color(0xFFB0A400), Color(0xFFB85500),
        )
        for (seed in seeds) {
            val cover = Hct.fromInt(seed.toArgb())
            for (tone in listOf(22.0, 85.0)) {
                val background = Color(Hct.from(cover.hue, 28.0, tone).toInt())
                val end = ColorExtractionUtils.detailBackgroundEndColor(background)
                val accent = ColorExtractionUtils.detailAccentColor(seed, background, end)
                assertReadable(accent, listOf(background, end))

                val result = Hct.fromInt(accent.toArgb())
                val hueDifference = abs(result.hue - cover.hue).let { minOf(it, 360.0 - it) }
                assertTrue("Cover hue drifted by $hueDifference degrees", hueDifference < 4.0)
                assertTrue("Chromatic covers should keep a visible accent", result.chroma > 15.0)
            }
        }
    }

    @Test
    fun grayCoversStayNeutralAndSubtleCoversStaySubtle() {
        for (seed in listOf(Color.Black, Color(0xFF808080), Color.White)) {
            for (background in listOf(Color(0xFF302E35), Color(0xFFD5CCD8))) {
                val end = ColorExtractionUtils.detailBackgroundEndColor(background)
                val accent = ColorExtractionUtils.detailAccentColor(seed, background, end)
                assertReadable(accent, listOf(background, end))
                assertTrue(Hct.fromInt(accent.toArgb()).chroma < 4.0)
            }
        }

        val seed = Color(Hct.from(240.0, 10.0, 50.0).toInt())
        val accent = ColorExtractionUtils.detailAccentColor(seed, Color(0xFFD5CCD8), Color(0xFFCFC6D2))
        assertTrue("Low-chroma covers should not gain a saturated accent", Hct.fromInt(accent.toArgb()).chroma < 16.0)
    }

    @Test
    fun accentsRemainReadableAgainstBothAnimatedGradientEndpoints() {
        val seed = Color(0xFFAC2375)
        for ((start, target) in listOf(
            Color(0xFFBFCFD9) to Color(0xFFE0C8C7),
            Color(0xFF24343D) to Color(0xFF422B35),
            Color(0xFF6C6C6C) to Color(0xFF8A8A8A),
        )) {
            for (step in 0..10) {
                val background = lerp(start, target, step / 10f)
                val end = ColorExtractionUtils.detailBackgroundEndColor(background)
                assertReadable(ColorExtractionUtils.detailAccentColor(seed, background, end), listOf(background, end))
            }
        }
    }

    private fun assertReadable(accent: Color, backgrounds: List<Color>) {
        val foreground = accent.luminance().toDouble()
        for (background in backgrounds) {
            val luminance = background.luminance().toDouble()
            val contrast = (maxOf(foreground, luminance) + 0.05) / (minOf(foreground, luminance) + 0.05)
            assertTrue("Expected at least 4.5 contrast, got $contrast", contrast >= 4.5)
        }
    }
}
