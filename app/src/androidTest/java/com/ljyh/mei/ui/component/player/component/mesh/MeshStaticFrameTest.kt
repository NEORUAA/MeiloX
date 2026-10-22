package com.ljyh.mei.ui.component.player.component.mesh

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.ljyh.mei.playback.PlaybackBeatMeter
import com.ljyh.mei.ui.component.player.LocalPlayerBackgroundReadyChanged
import com.ljyh.mei.ui.component.player.component.FluidBackground
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MeshStaticFrameTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun modulatedMeshPresentationPreservesCrossfadesAndUnfadedBackdropSampling() {
        val firstAlbum = meshTestAlbum()
        val secondAlbum = meshTestAlbum(Color.rgb(40, 70, 210))
        val renderer = compose.runOnIdle {
            MeshGradientRenderer().apply {
                isPlaying = false
                setAlbum(firstAlbum)
                advanceFrame(StartNanos)
            }
        }
        val opacity = mutableFloatStateOf(1f)
        val backing = mutableStateOf(ComposeColor.Black)
        val redrawGeneration = mutableIntStateOf(0)
        compose.setContent {
            // Both presentation paths replay this exact renderer frame and flattened
            // texture. Drawing either side must never advance the album transition.
            val meshLayer = rememberGraphicsLayer()
            val backdrops = listOf(rememberLayerBackdrop(), rememberLayerBackdrop())
            val coordinates = remember { List(2) { mutableStateOf<LayoutCoordinates?>(null) } }
            Row {
                listOf(CompositingStrategy.Auto, CompositingStrategy.ModulateAlpha)
                    .forEachIndexed { index, strategy ->
                        Column {
                            Box(
                                Modifier.size(96.dp).background(backing.value)
                                    .testTag("meshPresentation$index"),
                            ) {
                                Box(Modifier.fillMaxSize().graphicsLayer {
                                    compositingStrategy = strategy
                                    alpha = opacity.floatValue
                                }) {
                                    Canvas(
                                        Modifier.fillMaxSize().layerBackdrop(backdrops[index])
                                            .onGloballyPositioned { coordinates[index].value = it },
                                    ) {
                                        assertTrue("Mesh presentation requires GPU rendering", drawContext.canvas.nativeCanvas.isHardwareAccelerated)
                                        redrawGeneration.intValue
                                        renderer.draw(this, meshLayer, 0.75f)
                                    }
                                }
                            }
                            Canvas(Modifier.size(96.dp).testTag("meshBackdrop$index")) {
                                redrawGeneration.intValue
                                val sourceCoordinates = coordinates[index].value ?: return@Canvas
                                // Sample the actual layerBackdrop recording at its source
                                // coordinates, independently of the outer presentation fade.
                                with(backdrops[index]) {
                                    drawBackdrop(this@Canvas, sourceCoordinates, null)
                                }
                            }
                        }
                    }
            }
        }
        fun capture(tag: String): IntArray {
            val image = compose.onNodeWithTag(tag).captureToImage()
            return IntArray(image.width * image.height).also { image.readPixels(it) }
        }
        fun assertPixels(label: String, expected: IntArray, actual: IntArray) {
            assertEquals("$label pixel count", expected.size, actual.size)
            var maximum = 0
            var total = 0L
            expected.indices.forEach { pixel ->
                val differences = intArrayOf(
                    abs(Color.red(expected[pixel]) - Color.red(actual[pixel])),
                    abs(Color.green(expected[pixel]) - Color.green(actual[pixel])),
                    abs(Color.blue(expected[pixel]) - Color.blue(actual[pixel])),
                    abs(Color.alpha(expected[pixel]) - Color.alpha(actual[pixel])),
                )
                differences.forEach { error ->
                    maximum = maxOf(maximum, error)
                    total += error
                }
            }
            val mean = total.toDouble() / (expected.size * 4)
            assertTrue("$label maximum RGBA error: $maximum", maximum <= 2)
            assertTrue("$label mean RGBA error: $mean", mean <= 1.0)
        }
        var frameNanos = StartNanos
        var previousSource: IntArray? = null
        repeat(3) { frame ->
            compose.runOnIdle {
                opacity.floatValue = 1f
                if (frame == 1) renderer.setAlbum(secondAlbum)
                repeat(if (frame == 1) 3 else if (frame == 2) 5 else 0) {
                    frameNanos += FrameNanos
                    renderer.advanceFrame(frameNanos)
                }
                redrawGeneration.intValue++
            }
            compose.waitForIdle()
            val unfadedSource = capture("meshBackdrop0")
            assertTrue("Frame $frame must contain an opaque mesh source", unfadedSource.all { Color.alpha(it) == 255 })
            assertTrue("Frame $frame must contain album colors", unfadedSource.any {
                Color.red(it) > 24 || Color.green(it) > 24 || Color.blue(it) > 24
            })
            previousSource?.let { previous ->
                assertTrue("The real album crossfade must advance between frames", previous.indices.any {
                    colorDistance(previous[it], unfadedSource[it]) > 5
                })
            }
            previousSource = unfadedSource
            listOf(ComposeColor.Black, ComposeColor(0xFF74519B)).forEach { color ->
                listOf(0f, 0.15f, 0.5f, 1f).forEach { alpha ->
                    compose.runOnIdle {
                        backing.value = color
                        opacity.floatValue = alpha
                        redrawGeneration.intValue++
                    }
                    compose.waitForIdle()
                    val label = "Mesh frame=$frame, alpha=$alpha, backing=$color"
                    assertPixels(label, capture("meshPresentation0"), capture("meshPresentation1"))
                    repeat(2) { output ->
                        assertPixels("$label source=$output", unfadedSource, capture("meshBackdrop$output"))
                    }
                }
            }
        }
    }

    @Test
    fun settledStaticLayerSurvivesExplicitRedrawsWithoutAnimating() {
        val album = meshTestAlbum()
        val renderer = compose.runOnIdle {
            MeshGradientRenderer().apply {
                staticMode = true
                setAlbum(album)
                advanceFrame(StartNanos)
            }
        }
        val host = MeshRenderTestHost(compose, renderer)
        host.show()
        val expected = host.capture()
        assertTrue("The first album must be ready", renderer.hasAlbum)
        assertTrue("The static frame must contain artwork", expected.any {
            Color.red(it) > 24 || Color.green(it) > 24 || Color.blue(it) > 24
        })
        assertFalse("A settled static frame needs no continuous rendering", renderer.needsAnimation)

        repeat(3) { redraw ->
            val actual = host.capture {
                renderer.advanceFrame(StartNanos + (redraw + 1) * 60_000_000_000L)
            }
            assertArrayEquals("An explicit redraw must retain the complete static frame", expected, actual)
            assertFalse("Redrawing a static layer must remain on demand", renderer.needsAnimation)
        }
    }

    @Test
    fun pausedPlaybackCompletesAlbumCrossfadeThenStops() {
        assertAlbumCrossfadeSettles(staticMode = false)
    }

    @Test
    fun staticModeCompletesAlbumCrossfadeThenStops() {
        assertAlbumCrossfadeSettles(staticMode = true)
    }

    @Test
    fun resumingPlaybackExcludesTimeSpentPaused() {
        val album = meshTestAlbum()
        val renderer = compose.runOnIdle {
            MeshGradientRenderer().apply {
                setAlbum(album)
                advanceFrame(StartNanos)
                advanceFrame(StartNanos + FrameNanos)
            }
        }
        val host = MeshRenderTestHost(compose, renderer)
        host.show()
        val playing = host.capture()
        val paused = host.capture {
            renderer.isPlaying = false
            renderer.advanceFrame(PausedNanos)
        }
        assertArrayEquals("Pausing must preserve the current flow phase", playing, paused)
        assertFalse("Paused flow needs no continuous rendering", renderer.needsAnimation)

        val resumed = host.capture {
            renderer.isPlaying = true
            renderer.advanceFrame(PausedNanos + 60_000_000_000L)
        }
        assertArrayEquals("The first resumed frame must exclude the paused interval", paused, resumed)
        val advanced = host.capture {
            renderer.advanceFrame(PausedNanos + 60_000_000_000L + FrameNanos)
        }
        assertTrue("Flow must advance after playback resumes", advanced.indices.any {
            colorDistance(advanced[it], resumed[it]) > 2
        })
    }

    @Test
    fun realFrameClockPacesRequestsAndStopsWithoutPollingDisplayFrames() {
        compose.setContent { Spacer(Modifier.size(1.dp)) }
        val uiContext = AndroidUiDispatcher.Main
        val realClock = checkNotNull(uiContext[MonotonicFrameClock])
        var requests = 0
        var callbacks = 0
        val countingClock = object : MonotonicFrameClock {
            override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
                requests++
                return realClock.withFrameNanos {
                    callbacks++
                    onFrame(it)
                }
            }
        }

        runBlocking {
            withContext(uiContext + countingClock) {
                withTimeout(10_000) {
                    for (targetHz in listOf(30, 60)) {
                        requests = 0
                        callbacks = 0
                        var animating = true
                        val drawTimes = mutableListOf<Long>()
                        runMeshFrames(1_000_000_000L / targetHz, { animating }) { frameNanos ->
                            drawTimes += frameNanos
                            if (frameNanos - drawTimes.first() >= 1_200_000_000L) animating = false
                        }
                        val elapsedSeconds = (drawTimes.last() - drawTimes.first()) / 1_000_000_000.0
                        val observedHz = (drawTimes.size - 1) / elapsedSeconds
                        Log.i("MeshFrameClockTest",
                            "targetHz=$targetHz observedHz=$observedHz requests=$requests callbacks=$callbacks")
                        assertEquals("Every registered frame must advance the mesh", requests, drawTimes.size)
                        assertEquals(drawTimes.size, callbacks)
                        assertTrue("The real frame clock must retain $targetHz Hz cadence: $observedHz",
                            observedHz in (targetHz * 0.75)..(targetHz * 1.1))
                        val stoppedRequests = requests
                        delay(100)
                        assertEquals("A settled animation must stop requesting frames", stoppedRequests, requests)
                    }

                    requests = 0
                    callbacks = 0
                    val firstFrame = CompletableDeferred<Unit>()
                    val delayedWork = launch {
                        runMeshFrames(100_000_000L, { true }) { firstFrame.complete(Unit) }
                    }
                    firstFrame.await()
                    delay(20)
                    delayedWork.cancelAndJoin()
                    assertEquals("Cancellation during pacing must retain just the first request", 1, requests)
                    assertEquals(1, callbacks)
                    delay(150)
                    assertEquals("Cancelled pacing must stay idle", 1, requests)

                    val awaitingWork = launch(start = CoroutineStart.UNDISPATCHED) {
                        runMeshFrames(100_000_000L, { true }) {
                            throw AssertionError("A cancelled frame wait must never advance the mesh")
                        }
                    }
                    assertEquals("The cancellation case must suspend in a real frame wait", 2, requests)
                    awaitingWork.cancelAndJoin()
                    delay(100)
                    assertEquals("Cancelled frame waits must not invoke their callback", 1, callbacks)
                }
            }
        }
    }

    @Test
    fun fluidBackgroundPublishesReadinessAcrossAlbumChangesAndDisposal() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        fun cover(color: Int): File {
            val file = File.createTempFile("mesh-readiness-", ".png", context.cacheDir)
            val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(color)
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } finally {
                bitmap.recycle()
            }
            return file
        }
        val first = cover(Color.rgb(210, 80, 40))
        val second = cover(Color.rgb(40, 80, 210))
        val imageUrl = mutableStateOf<String?>(null)
        val visible = mutableStateOf(true)
        val events = CopyOnWriteArrayList<Boolean>()
        val beatMeter = PlaybackBeatMeter()
        fun redBlueBalance(): Double {
            val image = compose.onNodeWithTag("fluidBackground").captureToImage()
            val pixels = IntArray(image.width * image.height).also { image.readPixels(it) }
            return pixels.sumOf { Color.red(it) - Color.blue(it) }.toDouble() / pixels.size
        }
        compose.mainClock.autoAdvance = false
        try {
            compose.setContent {
                val backdrop = rememberLayerBackdrop()
                CompositionLocalProvider(
                    LocalPlayerBackgroundReadyChanged provides { events += it },
                ) {
                    if (visible.value) {
                        Box(Modifier.size(96.dp).background(ComposeColor.Black).testTag("fluidBackground")) {
                            FluidBackground(
                                imageUrl = imageUrl.value,
                                beatMeter = beatMeter,
                                isPlaying = false,
                                backdrop = backdrop,
                            )
                        }
                    }
                }
            }
            compose.runOnIdle {
                assertTrue("A background without an album must report not ready", events.isNotEmpty())
                assertTrue("An empty background must never report ready", events.none { it })
                imageUrl.value = first.toURI().toString()
            }
            var enteringBalance = 0.0
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.mainClock.advanceTimeByFrame()
                enteringBalance = redBlueBalance()
                enteringBalance > 5
            }
            assertTrue("The underlying page must remain visible during the first reveal", events.none { it })
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.mainClock.advanceTimeByFrame()
                events.any { it }
            }
            val settledBalance = redBlueBalance()
            assertTrue("The first mesh must fade in instead of appearing at full opacity",
                settledBalance > enteringBalance + 5)
            assertTrue("The ready background must display the first cover", settledBalance > 40)
            val notReadyCount = events.count { !it }
            compose.runOnIdle { imageUrl.value = second.toURI().toString() }
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.mainClock.advanceTimeBy(100)
                redBlueBalance() < -40
            }
            assertEquals("Replacing an album must retain the ready background", notReadyCount,
                events.count { !it })

            compose.runOnIdle { visible.value = false }
            compose.mainClock.advanceTimeByFrame()
            compose.runOnIdle {
                assertEquals("Disposal must publish not ready", false, events.last())
                assertEquals(notReadyCount + 1, events.count { !it })
            }
        } finally {
            first.delete()
            second.delete()
        }
    }

    private fun assertAlbumCrossfadeSettles(staticMode: Boolean) {
        val album = meshTestAlbum(Color.rgb(220, 50, 50))
        val nextAlbum = meshTestAlbum(Color.rgb(50, 50, 220))
        val renderer = compose.runOnIdle {
            MeshGradientRenderer().apply {
                this.staticMode = staticMode
                isPlaying = false
                setAlbum(album)
                advanceFrame(StartNanos)
            }
        }
        val host = MeshRenderTestHost(compose, renderer)
        host.show()
        val initial = host.capture()
        val transitioning = host.capture {
            renderer.setAlbum(nextAlbum)
            assertTrue("An album change must schedule its crossfade", renderer.needsAnimation)
            repeat(3) { renderer.advanceFrame(StartNanos + (it + 1) * FrameNanos) }
            assertTrue("The crossfade must remain active before it settles", renderer.needsAnimation)
        }
        val final = host.capture {
            repeat(5) { renderer.advanceFrame(StartNanos + (it + 4) * FrameNanos) }
        }
        assertTrue("The crossfade must change the displayed album", initial.indices.any {
            colorDistance(initial[it], final[it]) > 24
        })
        assertTrue("The intermediate frame must differ from the settled album", final.indices.any {
            colorDistance(transitioning[it], final[it]) > 5
        })
        assertFalse("Paused or static flow must stop after the crossfade", renderer.needsAnimation)
        assertArrayEquals("Settled crossfades must survive later redraws", final, host.capture {
            renderer.advanceFrame(PausedNanos)
        })
    }

    private companion object {
        const val PausedNanos = 3_600_000_000_000L
    }
}
