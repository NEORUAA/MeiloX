package com.ljyh.mei.ui.component.sheet

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.ljyh.mei.ui.glass.GlassPressHighlight
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class PlayerPressLightTest {
    private val pressed = GlassPressHighlight(0.8f, Offset(0.25f, 0.7f))

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
