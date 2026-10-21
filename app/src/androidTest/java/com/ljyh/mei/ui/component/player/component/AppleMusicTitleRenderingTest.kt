package com.ljyh.mei.ui.component.player.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ljyh.mei.ui.component.player.component.applemusic.AppleMusicMarqueeText
import com.ljyh.mei.ui.component.player.component.applemusic.Title
import com.ljyh.mei.ui.component.player.component.applemusic.playerInfoMotion
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppleMusicTitleRenderingTest {
    @get:Rule val compose = createComposeRule()

    private fun showMarquee(text: String) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(190.dp).background(Color.Black).testTag("metadata")) {
                    AppleMusicMarqueeText(
                        text = text,
                        style = TextStyle(fontSize = 17.sp, lineHeight = 21.sp),
                        color = Color.White,
                        edgeWidth = 26.dp,
                        enabled = true,
                        modifier = Modifier.padding(start = 26.dp),
                    )
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
    }

    private fun image() = compose.onNodeWithTag("metadata").captureToImage()

    @Test fun overflowingMetadataWaitsThenScrolls() {
        showMarquee("A long title with enough different words to overflow the available width")
        val initial = image()
        compose.mainClock.advanceTimeBy(2_500)
        assertTrue(samePixels(initial, image()))
        compose.mainClock.advanceTimeBy(2_000)
        assertFalse("Overflowing metadata must scroll after the initial wait", samePixels(initial, image()))
    }

    @Test fun shortMetadataStaysStill() {
        showMarquee("Short title")
        val initial = image()
        compose.mainClock.advanceTimeBy(8_000)
        assertTrue("Fitting metadata must not scroll or fade", samePixels(initial, image()))
    }

    @Test fun pressingMetadataDoesNotDarkenItAndStillInvokesTheAction() {
        compose.mainClock.autoAdvance = false
        var clicks = 0
        compose.setContent {
            MaterialTheme {
                Title(
                    title = "Short title",
                    subTitle = "Artist",
                    isLiked = false,
                    onLikeClick = {},
                    onMoreClick = {},
                    onTitleClick = { clicks++ },
                    needShadow = false,
                    marqueeEnabled = false,
                    modifier = Modifier.width(360.dp).background(Color.Black).testTag("metadata"),
                )
            }
        }
        val initial = image()
        compose.onNodeWithText("Short title").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(300)
        assertTrue("The title press must not add a dark overlay", samePixels(initial, image()))
        compose.onNodeWithText("Short title").performTouchInput { up() }
        compose.runOnIdle { assertEquals(1, clicks) }
    }

    @Test fun movingMetadataKeepsAdditiveBlendingOutsideItsOriginalRow() {
        val opacity = mutableStateOf(1f)
        val progress = mutableStateOf(0f)
        val background = Color(0.10f, 0.25f, 0.40f)
        compose.setContent {
            MaterialTheme {
                val destination = Offset(0f, with(LocalDensity.current) { 96.dp.toPx() })
                val motion = Modifier.playerInfoMotion(opacity, progress, Offset.Zero, destination, true)
                Box(
                    Modifier.width(360.dp).height(220.dp)
                        .background(background).testTag("metadata-blend-background"),
                ) {
                    Title(
                        title = "Title",
                        subTitle = "MMMM",
                        isLiked = false,
                        onLikeClick = {},
                        onMoreClick = {},
                        onTitleClick = {},
                        titleStyle = TextStyle(fontSize = 22.sp, lineHeight = 28.sp),
                        subTitleStyle = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
                        needShadow = false,
                        marqueeEnabled = false,
                        textModifier = motion,
                        actionsModifier = motion,
                        modifier = Modifier.width(360.dp),
                    )
                }
            }
        }

        val artist = compose.onNodeWithText("MMMM", useUnmergedTree = true)
        val originalTop = artist.getUnclippedBoundsInRoot().top.value
        for ((fraction, alpha) in listOf(0f to 1f, 0.5f to 0.5f, 1f to 0.25f, 1f to 1f, 0f to 0.75f)) {
            compose.runOnIdle {
                progress.value = fraction
                opacity.value = alpha
            }
            val root = compose.onNodeWithTag("metadata-blend-background")
            val rootBounds = root.getUnclippedBoundsInRoot()
            val bounds = artist.getUnclippedBoundsInRoot()
            assertEquals("Artist must follow the metadata motion", originalTop + 96f * fraction, bounds.top.value, 1f)
            val pixels = root.captureToImage().toPixelMap()
            val pixelsPerDp = pixels.width / (rootBounds.right - rootBounds.left).value
            val left = ((bounds.left - rootBounds.left).value * pixelsPerDp).roundToInt().coerceIn(0, pixels.width)
            val right = ((bounds.right - rootBounds.left).value * pixelsPerDp).roundToInt().coerceIn(0, pixels.width)
            val top = ((bounds.top - rootBounds.top).value * pixelsPerDp).roundToInt().coerceIn(0, pixels.height)
            val bottom = ((bounds.bottom - rootBounds.top).value * pixelsPerDp).roundToInt().coerceIn(0, pixels.height)
            var glyph = background
            for (y in top until bottom) for (x in left until right) {
                if (pixels[x, y].red > glyph.red) glyph = pixels[x, y]
            }
            // Sample the background with the artist so a flattened fade cannot pass.
            val contribution = 0.35f * alpha
            assertEquals("Artist red at motion $fraction", background.red + contribution, glyph.red, 0.03f)
            assertEquals("Artist green at motion $fraction", background.green + contribution, glyph.green, 0.03f)
            assertEquals("Artist blue at motion $fraction", background.blue + contribution, glyph.blue, 0.03f)
        }
    }

    private fun samePixels(first: ImageBitmap, second: ImageBitmap): Boolean {
        if (first.width != second.width || first.height != second.height) return false
        val a = first.toPixelMap()
        val b = second.toPixelMap()
        for (y in 0 until a.height) for (x in 0 until a.width) {
            if (a[x, y] != b[x, y]) return false
        }
        return true
    }
}
