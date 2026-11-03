package com.ljyh.mei.ui.component.sheet

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import io.github.neoruaa.glasmio.glass.GlassPressHighlight
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class PlayerPressLightTest {
    private val pressed = GlassPressHighlight(0.8f, Offset(0.25f, 0.7f))

    @Test fun combinedGlowPolynomialPreservesOriginalField() {
        val points = floatArrayOf(.0198550718f, .1016667613f, .2372337950f, .4082826788f,
            .5917173212f, .7627662050f, .8983332387f, .9801449282f)
        val weights = floatArrayOf(.0506142681f, .1111905172f, .1568533229f, .1813418917f,
            .1813418917f, .1568533229f, .1111905172f, .0506142681f)
        val bessel = floatArrayOf(1f, 1f, .25f, .02777777778f, .001736111111f,
            .00006944444444f, 1.929012346e-6f, 3.936759889e-8f, 6.151187327e-10f)
        var maxError = 0f
        var tail = 0f
        for (sample in 0..100000) {
            val x = sample * (5f / 100000f)
            var reference = 0f
            for (i in points.indices) {
                val t = points[i]
                val q = x * x * t * t * .25f
                var value = bessel.last()
                for (k in bessel.lastIndex - 1 downTo 0) value = value * q + bessel[k]
                reference += weights[i] * t * kotlin.math.exp(-.5f * t * t) * value
            }
            val envelope = kotlin.math.exp(-.5f * x * x)
            reference *= envelope
            val c = PlayerPressGlowCoefficients
            var combined = c.last()
            for (k in c.lastIndex - 1 downTo 0) combined = combined * (x * x) + c[k]
            combined *= envelope
            val error = kotlin.math.abs(combined - reference)
            maxError = maxOf(maxError, error)
            assertTrue(error <= maxOf(0.0000000298023224f, reference / 4096f))
            if (sample == 100000) tail = combined
        }
        assertTrue("Maximum field error: $maxError", maxError <= 0.0000002f)
        assertTrue(tail > 0f)
    }

    @Test fun heldPointerMovesIndependentlyOfTheFrozenMiniPlayerGeometry() = runBlocking {
        val light = PlayerPressLight(this)
        val source = Rect(16f, 700f, 388f, 750f)
        light.capture(pressed)
        light.updatePointer(Offset(295f, 720f))
        val current = light.current(0.25f, source)
        assertEquals(Offset(0.75f, 0.4f), current.position)
        assertEquals(pressed.progress * playerPressHighlightAlpha(0.25f), current.progress, 0f)
    }

    @Test fun heldLightStaysUnderTheFingerWhileTheShellMovesAndReverses() = runBlocking {
        val light = PlayerPressLight(this)
        val source = Rect(16f, 700f, 388f, 750f)
        val page = Rect(0f, 0f, 400f, 800f)
        val pointer = Offset(300f, 710f)
        light.capture(pressed)
        light.updatePointer(pointer)
        for (step in (0..100) + (100 downTo 0)) {
            val p = step / 100f
            val bounds = playerPressHighlightRect(source.size, playerContainerRect(source, page, p))
            val current = light.current(p, bounds)
            assertEquals(pointer.x, bounds.left + bounds.width * current.position.x, 0.0001f)
            assertEquals(pointer.y, bounds.top + bounds.height * current.position.y, 0.0001f)
            assertEquals(source.size, bounds.size)
            if (step == 50) assertTrue(current.position.y > 1f)
        }
    }

    @Test fun releaseFreezesTheLastPointerPositionAndIgnoresLaterMoves() = runBlocking {
        val clock = BroadcastFrameClock()
        val light = PlayerPressLight(CoroutineScope(coroutineContext + clock))
        try {
            val source = Rect(16f, 700f, 388f, 750f)
            light.capture(pressed)
            light.updatePointer(Offset(295f, 720f))
            val before = light.current(0.25f, source)
            light.release(0.25f, source)
            light.updatePointer(Offset(20f, 705f))
            assertEquals(before, light.current(0f, source.translate(Offset(10f, 20f))))
            repeat(4) { yield(); clock.sendFrame(it * 16_666_667L); yield() }
            val released = light.current(0f, source)
            assertEquals(before.position, released.position)
            assertTrue(released.progress < before.progress)
            light.capture(pressed)
            assertEquals(pressed, light.current(0f, source))
        } finally { light.clear() }
    }

    @Test fun firstTransitionFramePreservesPartialPressAndPosition() = runBlocking {
        val light = PlayerPressLight(this)
        light.capture(pressed)
        assertEquals(pressed, light.current(0f))
        assertTrue(light.hasCapture)
    }

    @Test fun takeoverPreservesTheActualGlowIndependentlyOfPressDeformation() = runBlocking {
        val clock = BroadcastFrameClock()
        val light = PlayerPressLight(CoroutineScope(coroutineContext + clock))
        try {
            val captured = pressed.copy(progress = 0.35f, glowProgress = 0.9f, dissipationProgress = 0.2f)
            light.capture(captured)
            assertEquals(captured, light.current(0f))
            val held = light.current(0.4f)
            assertEquals(captured.glowProgress * playerPressHighlightAlpha(0.4f), held.glowProgress, 0f)
            assertEquals(captured.copy(progress = 0f, glowProgress = 0f),
                light.current(PlayerBackgroundHandoffEnd))
            light.release(0.4f)
            assertEquals(held, light.current(0f))
            repeat(4) { yield(); clock.sendFrame(it * 16_666_667L); yield() }
            val released = light.current(0f)
            assertTrue(released.progress < held.progress)
            assertTrue(released.glowProgress < held.glowProgress)
            assertEquals(captured.glowProgress / captured.progress,
                released.glowProgress / released.progress, 0.0001f)
            assertEquals(captured.dissipationProgress, released.dissipationProgress, 0f)
            assertEquals(released, light.current(1f))
        } finally { light.clear() }
    }

    @Test fun heldDragFadesUntilTheBackgroundOwnsTheSurface() = runBlocking {
        val light = PlayerPressLight(this)
        light.capture(pressed)
        var previous = pressed.progress
        for (step in 0..99) {
            val p = step / 100f
            val value = light.current(p)
            assertTrue(value.progress <= previous)
            assertEquals(pressed.position, value.position)
            if (step == 10 || step == 80 || step == 98) assertTrue(value.progress > 0f)
            previous = value.progress
        }
        assertEquals(0f, light.current(PlayerBackgroundHandoffEnd).progress, 0f)
    }

    @Test fun heldReversalRetracesOpacityEvenAfterTheBackgroundHandoff() = runBlocking {
        val light = PlayerPressLight(this)
        light.capture(pressed)
        val forward = (0..100).map { light.current(it / 100f) }
        assertEquals(0f, forward.last().progress, 0f)
        val reverse = (100 downTo 0).map { light.current(it / 100f) }
        assertEquals(forward, reverse.reversed())
        assertEquals(pressed, reverse.last())
    }

    @Test fun lightSourceKeepsItsRadiusAndPositionAsTheShellExposesMoreOfItsField() {
        val source = Rect(16f, 700f, 388f, 750f)
        val page = Rect(0f, 0f, 400f, 800f)
        val originalPosition = Offset(source.width * pressed.position.x, source.height * pressed.position.y)
        for (step in 0..100) {
            val shell = playerContainerRect(source, page, step / 100f)
            val light = playerPressHighlightRect(source.size, shell)
            assertEquals(shell.topLeft, light.topLeft)
            assertEquals(source.width, light.width, 0.0001f)
            assertEquals(source.height, light.height, 0.0001f)
            assertEquals(originalPosition.x, light.width * pressed.position.x, 0.0001f)
            assertEquals(originalPosition.y, light.height * pressed.position.y, 0.0001f)
        }
    }

    @Test fun releasingAtAFixedDragPositionFadesWithoutAFurtherDragFrame() = runBlocking {
        val clock = BroadcastFrameClock()
        val light = PlayerPressLight(CoroutineScope(coroutineContext + clock))
        try {
            light.capture(pressed)
            val before = light.current(0.25f)
            light.release(0.25f)
            assertEquals(before, light.current(0.25f))
            repeat(4) { yield(); clock.sendFrame(it * 16_666_667L); yield() }
            assertTrue(light.current(0.25f).progress < before.progress)
            assertEquals(pressed.position, light.current(0.25f).position)
            repeat(120) { yield(); clock.sendFrame((it + 4) * 16_666_667L); yield() }
            assertEquals(0f, light.current(0.25f).progress, 0f)
        } finally { light.clear() }
    }

    @Test fun releaseDoesNotBrightenWhenTheSheetReturnsToTheMiniPlayer() = runBlocking {
        val clock = BroadcastFrameClock()
        val light = PlayerPressLight(CoroutineScope(coroutineContext + clock))
        try {
            light.capture(pressed)
            val before = light.current(0.6f)
            light.release(0.6f)
            assertEquals(before, light.current(0f))
            repeat(120) {
                yield(); clock.sendFrame(it * 16_666_667L); yield()
                // Release timing, including the collapsed endpoint, ignores sheet progress.
                assertEquals(light.current(1f), light.current(0f))
                assertTrue(light.current(0f).progress <= before.progress)
            }
            assertEquals(0f, light.current(0f).progress, 0f)
            assertFalse(light.hasCapture)
        } finally { light.clear() }
    }

    @Test fun returningWhileHeldWaitsForReleaseBeforeStartingTheSpring() = runBlocking {
        val clock = BroadcastFrameClock()
        val light = PlayerPressLight(CoroutineScope(coroutineContext + clock))
        try {
            light.capture(pressed)
            light.current(0.75f)
            assertEquals(pressed, light.current(0f))
            repeat(120) { yield(); clock.sendFrame(it * 16_666_667L); yield() }
            assertEquals(pressed, light.current(0f))
            light.release(0f)
            assertEquals(pressed, light.current(0f))
            repeat(4) { yield(); clock.sendFrame((it + 120) * 16_666_667L); yield() }
            assertTrue(light.current(0f).progress in 0f..pressed.progress)
            assertTrue(light.current(0f).progress < pressed.progress)
        } finally { light.clear() }
    }

    @Test fun aNewPressDoesNotInheritTheOldReleasesAnimation() = runBlocking {
        val clock = BroadcastFrameClock()
        val light = PlayerPressLight(CoroutineScope(coroutineContext + clock))
        try {
            light.capture(pressed)
            light.release(0f)
            repeat(4) { yield(); clock.sendFrame(it * 16_666_667L); yield() }
            val next = GlassPressHighlight(0.5f, Offset(0.8f, 0.2f))
            light.capture(next)
            repeat(10) { yield(); clock.sendFrame((it + 4) * 16_666_667L); yield() }
            assertEquals(next, light.current(0f))
            light.clear()
            assertEquals(0f, light.current(0f).progress, 0f)
        } finally { light.clear() }
    }
}
