package com.ljyh.mei.ui.component.player.component.mesh

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Looper
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ljyh.mei.playback.PlaybackSpectrum
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MeshAudioResponseTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun onlyVividRedsAreSoftenedWhileOtherColorsKeepTheirOriginalPixels() {
        fun processedPixels(color: Int): IntArray {
            val source = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
                eraseColor(color)
            }
            val processed = AlbumTextureProcessor.process(source)
            return try {
                assertFalse("Processing must preserve the source bitmap", source.isRecycled)
                assertEquals("Processing must preserve the cover pixels", color, source.getPixel(0, 0))
                IntArray(processed.width * processed.height).also {
                    processed.getPixels(it, 0, processed.width, 0, 0, processed.width, processed.height)
                }
            } finally {
                processed.recycle()
                source.recycle()
            }
        }
        fun originalPixel(color: Int): Int {
            val channels = floatArrayOf(Color.red(color).toFloat(), Color.green(color).toFloat(), Color.blue(color).toFloat())
            for (index in channels.indices) channels[index] = (channels[index] - 128f) * 0.4f + 128f
            val gray = channels[0] * 0.3f + channels[1] * 0.59f + channels[2] * 0.11f
            val output = channels.map { channel ->
                val saturated = gray * -2f + channel * 3f
                (((saturated - 128f) * 1.7f + 128f) * 0.75f).toInt().coerceIn(0, 255)
            }
            return Color.argb(Color.alpha(color), output[0], output[1], output[2])
        }
        val sourceHsl = FloatArray(3)
        val processedHsl = FloatArray(3)
        listOf(Color.RED, ColorUtils.HSLToColor(floatArrayOf(330f, 1f, 0.5f)),
            ColorUtils.HSLToColor(floatArrayOf(340f, 1f, 0.5f))).forEach { color ->
            ColorUtils.colorToHSL(color, sourceHsl)
            processedPixels(color).forEach { pixel ->
                val label = "Red cover ${Integer.toHexString(color)}"
                assertEquals("$label must remain opaque", 255, Color.alpha(pixel))
                assertTrue("$label must leave brightness headroom for audio response",
                    ColorUtils.calculateLuminance(pixel) <= 0.161)
                ColorUtils.colorToHSL(pixel, processedHsl)
                assertTrue("$label must not become oversaturated", processedHsl[1] <= 0.61f)
                val difference = abs(sourceHsl[0] - processedHsl[0])
                assertTrue("$label must retain its hue", minOf(difference, 360f - difference) <= 2f)
                assertTrue("$label must retain visible color", processedHsl[1] > 0.3f)
            }
        }
        val unchanged = listOf(Color.MAGENTA, Color.rgb(255, 128, 0), Color.YELLOW,
            Color.GREEN, Color.CYAN, Color.BLUE, Color.rgb(128, 0, 255),
            Color.WHITE, Color.GRAY, Color.BLACK,
            ColorUtils.HSLToColor(floatArrayOf(0f, 0.3f, 0.5f)),
            ColorUtils.HSLToColor(floatArrayOf(340f, 0.2f, 0.3f)))
        unchanged.forEach { color ->
            val expected = originalPixel(color)
            processedPixels(color).forEach { pixel ->
                assertEquals("Non-vivid-red cover ${Integer.toHexString(color)} must retain the original pipeline",
                    expected, pixel)
            }
        }
        // Adjacent colors must cross the correction boundaries without a visible step.
        listOf(299f..321f, 9f..26f).forEach { range ->
            var previous: Int? = null
            repeat(((range.endInclusive - range.start) * 2).toInt() + 1) { step ->
                val hue = range.start + step * 0.5f
                val pixel = processedPixels(ColorUtils.HSLToColor(floatArrayOf(hue, 1f, 0.5f)))[0]
                previous?.let {
                    assertTrue("Hue transition at $hue must be continuous", colorDistance(it, pixel) <= 16)
                }
                previous = pixel
            }
        }
        var previous: Int? = null
        repeat(65) { step ->
            val saturation = 0.34f + step * 0.005f
            val pixel = processedPixels(ColorUtils.HSLToColor(floatArrayOf(0f, saturation, 0.5f)))[0]
            previous?.let {
                assertTrue("Saturation transition at $saturation must be continuous", colorDistance(it, pixel) <= 10)
            }
            previous = pixel
        }
    }

    @Test
    fun maximumAudioResponseKeepsVividRedBackgroundsWithinTheColorBounds() {
        val renderer = compose.runOnIdle { MeshGradientRenderer().apply { flowSpeed = 0f } }
        val host = MeshRenderTestHost(compose, renderer)
        host.show()
        var frameNanos = StartNanos
        listOf(Color.RED, ColorUtils.HSLToColor(floatArrayOf(330f, 1f, 0.5f)),
            ColorUtils.HSLToColor(floatArrayOf(340f, 1f, 0.5f))).forEach { color ->
            val album = meshTestAlbum(color)
            val pixels = host.capture {
                renderer.setAlbum(album)
                renderer.audioStrength = 1f
                renderer.spectrum = PlaybackSpectrum(1f, 1f, 1f, 1f)
                repeat(20) {
                    frameNanos += FrameNanos
                    renderer.advanceFrame(frameNanos)
                }
            }
            pixels.forEach { pixel ->
                val label = "Peak response for ${Integer.toHexString(color)}"
                assertEquals("$label must remain opaque", 255, Color.alpha(pixel))
                assertTrue("$label must keep the background dark enough for white content",
                    ColorUtils.calculateLuminance(pixel) <= 0.18)
                assertTrue("$label must leave channel headroom for the foreground",
                    maxOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel)) <= 0.85f * 255f)
            }
        }
    }

    @Test
    fun maximumResponseKeepsNeutralArtworkOpaqueAndMovesColoredArtwork() {
        // The album color pipeline maps this gray to the shader's neutral 128.
        val neutralAlbum = meshTestAlbum(Color.rgb(191, 191, 191))
        val renderer = compose.runOnIdle {
            MeshGradientRenderer().apply {
                flowSpeed = 0f
                setAlbum(neutralAlbum)
                advanceFrame(StartNanos)
            }
        }
        val host = MeshRenderTestHost(compose, renderer)
        host.show()
        val neutral = host.capture()
        val peak = host.capture {
            renderer.audioStrength = 1f
            renderer.spectrum = PlaybackSpectrum(1f, 1f, 1f, 1f)
            repeat(12) { renderer.advanceFrame(StartNanos + (it + 1) * FrameNanos) }
        }
        neutral.indices.forEach { index ->
            assertTrue("Peak response must keep neutral artwork stable at pixel $index",
                colorDistance(neutral[index], peak[index]) <= 2)
            assertTrue("Peak response must remain opaque at pixel $index", Color.alpha(peak[index]) == 255)
        }
        assertArrayEquals("Drawing the same frame twice must preserve backdrop sampling", peak, host.capture())

        val coloredAlbum = meshTestAlbum()
        val colored = host.capture {
            renderer.audioStrength = 0f
            renderer.spectrum = PlaybackSpectrum.Zero
            renderer.setAlbum(coloredAlbum)
            repeat(10) { renderer.advanceFrame(StartNanos + (it + 13) * FrameNanos) }
        }
        val expanded = host.capture {
            renderer.audioStrength = 1f
            renderer.spectrum = PlaybackSpectrum(1f, 1f, 1f, 1f)
            repeat(12) { renderer.advanceFrame(StartNanos + (it + 23) * FrameNanos) }
        }
        val changed = colored.indices.count { colorDistance(colored[it], expanded[it]) > 5 }
        assertTrue("Audio must move the artwork spatially", changed > colored.size / 4)
        assertTrue("RuntimeShader must render visible artwork", colored.any {
            Color.red(it) > 24 || Color.green(it) > 24 || Color.blue(it) > 24
        })
        val paused = host.capture {
            renderer.isPlaying = false
            renderer.audioStrength = 0f
        }
        assertArrayEquals("Disabling audio while paused must restore the baseline without another tick",
            colored, paused)
    }

    @Test
    fun preparingAlreadySizedArtworkPreservesTheBorrowedSource() {
        val source = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(210, 80, 40))
        }
        val expected = source.getPixel(0, 0)
        try {
            MeshGradientRenderer.prepareAlbum(source, 50, IdentityPreset)
            assertFalse("Texture preparation must not recycle a borrowed 32px cover", source.isRecycled)
            assertEquals("Texture preparation must not modify the source pixels", expected, source.getPixel(0, 0))
        } finally {
            source.recycle()
        }
    }

    @Test
    fun backgroundPreparedShadersSurviveAlbumReentryDuringAndAfterCrossfade() {
        val albums = runBlocking {
            withContext(Dispatchers.Default) {
                assertNotSame("Album preparation must run off main", Looper.getMainLooper(), Looper.myLooper())
                listOf(
                    meshTestAlbum(Color.rgb(210, 80, 40)),
                    meshTestAlbum(Color.rgb(40, 80, 210)),
                    meshTestAlbum(Color.rgb(210, 80, 40)),
                )
            }
        }
        // Shader objects already exist before main-thread adoption. Even preparing
        // the same artwork again must provide independent mutable uniforms.
        assertNotSame(albums[0].shader, albums[1].shader)
        assertNotSame(albums[0].shader, albums[2].shader)
        val renderer = compose.runOnIdle {
            MeshGradientRenderer().apply {
                isPlaying = false
                flowSpeed = 0f
            }
        }
        val host = MeshRenderTestHost(compose, renderer)
        var frameNanos = StartNanos
        fun advanceFrames(count: Int) {
            repeat(count) {
                frameNanos += FrameNanos
                renderer.advanceFrame(frameNanos)
            }
        }
        host.show()
        val red = host.capture {
            assertEquals(Looper.getMainLooper(), Looper.myLooper())
            renderer.setAlbum(albums[0])
            renderer.advanceFrame(frameNanos)
        }
        val fadingToBlue = host.capture {
            renderer.setAlbum(albums[1])
            advanceFrames(3)
        }
        assertTrue("A new album must become visible during its crossfade",
            red.indices.count { colorDistance(red[it], fadingToBlue[it]) > 5 } > red.size / 2)

        val reentered = host.capture {
            renderer.setAlbum(albums[0])
        }
        assertArrayEquals("Reversing a fade must preserve the current compositing order", fadingToBlue, reentered)
        val returnedDuringFade = host.capture {
            advanceFrames(10)
        }
        assertArrayEquals("Returning to a fading album must restore its own texture", red, returnedDuringFade)
        val blue = host.capture {
            renderer.setAlbum(albums[1])
            advanceFrames(10)
        }
        assertTrue("Distinct shaders must keep the two album textures independent",
            red.indices.count { colorDistance(red[it], blue[it]) > 5 } > red.size / 2)
        assertTrue("A crossfade must retain the outgoing album until it completes",
            blue.indices.count { colorDistance(blue[it], fadingToBlue[it]) > 5 } > blue.size / 2)

        val returnedAfterFade = host.capture {
            renderer.setAlbum(albums[0])
            advanceFrames(10)
        }
        assertArrayEquals("A retired album can be adopted again without compiling on main", red, returnedAfterFade)
        assertArrayEquals("Repeated adoption must leave a completed frame unchanged", red, host.capture {
            repeat(3) { renderer.setAlbum(albums[0]) }
        })
        assertFalse("Paused reentry must settle when the crossfade completes", renderer.needsAnimation)
        assertArrayEquals("A newly prepared copy must keep the same visual result", red, host.capture {
            renderer.setAlbum(albums[2])
            advanceFrames(10)
        })
    }
}

/** Exercises the actual hardware Compose draw path, including the recorded mesh layer. */
internal class MeshRenderTestHost(
    private val compose: ComposeContentTestRule,
    private val renderer: MeshGradientRenderer,
) {
    private val redrawGeneration = mutableIntStateOf(0)

    fun show() {
        compose.setContent {
            val layer = rememberGraphicsLayer()
            Canvas(Modifier.size(96.dp).testTag("mesh")) {
                // Renderer uniforms are intentionally not Compose state. This explicitly
                // invalidates the same draw node when the test changes them.
                redrawGeneration.intValue
                renderer.draw(this, layer, 1f)
            }
        }
    }

    fun capture(update: () -> Unit = {}): IntArray {
        compose.runOnIdle {
            update()
            redrawGeneration.intValue++
        }
        val image = compose.onNodeWithTag("mesh").captureToImage()
        return IntArray(image.width * image.height).also { image.readPixels(it) }
    }
}

internal fun meshTestAlbum(solidColor: Int? = null): MeshAlbum {
    val size = 64
    val source = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    source.setPixels(IntArray(size * size) { index ->
        solidColor ?: Color.rgb(40 + index % size * 3, 30 + index / size * 3,
            64 + ((index % size + index / size) % size) * 2)
    }, 0, size, 0, 0, size, size)
    return try {
        MeshGradientRenderer.prepareAlbum(source, 50, IdentityPreset)
    } finally {
        source.recycle()
    }
}

internal fun colorDistance(first: Int, second: Int): Int = maxOf(
    abs(Color.red(first) - Color.red(second)),
    abs(Color.green(first) - Color.green(second)),
    abs(Color.blue(first) - Color.blue(second)),
)

internal const val StartNanos = 1_000_000_000L
internal const val FrameNanos = 100_000_000L

private val IdentityPreset = ControlPointPreset(2, 2, listOf(
    ControlPointConf(0, 0, -1f, -1f, 0f, 0f, 1f, 1f),
    ControlPointConf(1, 0, 1f, -1f, 0f, 0f, 1f, 1f),
    ControlPointConf(0, 1, -1f, 1f, 0f, 0f, 1f, 1f),
    ControlPointConf(1, 1, 1f, 1f, 0f, 0f, 1f, 1f),
))
