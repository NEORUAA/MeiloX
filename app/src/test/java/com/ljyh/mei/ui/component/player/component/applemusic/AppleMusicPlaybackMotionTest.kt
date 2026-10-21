package com.ljyh.mei.ui.component.player.component.applemusic

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import androidx.compose.runtime.BroadcastFrameClock
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class AppleMusicPlaybackMotionTest {
    @Test fun pauseShrinksMonotonicallyToTheExistingSizeIn480Milliseconds() {
        val animation = TargetBasedAnimation(appleMusicPlaybackSpec(false), Float.VectorConverter, 0f, 1f)
        assertEquals(480_000_000L, animation.durationNanos)
        var previousScale = 1f
        for (millis in 0..480 step 8) {
            val scale = 1f - 0.1f * animation.getValueFromNanos(millis * 1_000_000L)
            assertTrue(scale <= previousScale)
            assertTrue(scale >= 0.9f)
            previousScale = scale
        }
        assertEquals(0.9f, previousScale, 0f)
    }

    @Test fun playingReboundsAndSettlesAtTheOriginalPlayingSize() {
        val animation = TargetBasedAnimation(appleMusicPlaybackSpec(true), Float.VectorConverter, 1f, 0f)
        val scales = (0L..animation.durationNanos step 8_000_000L)
            .map { 1f - 0.1f * animation.getValueFromNanos(it) }
        assertTrue(scales.any { it > 1f })
        assertTrue(scales.max() < 1.01f)
        assertEquals(1f, 1f - 0.1f * animation.getValueFromNanos(animation.durationNanos), 0f)
    }

    @Test fun reversingPauseToPlayKeepsCurrentPosition() = runBlocking {
        val clock = BroadcastFrameClock()
        val fraction = Animatable(0f)
        var time = 0L
        suspend fun frames(count: Int) {
            repeat(count) {
                yield()
                clock.sendFrame(time)
                time += 16_666_667L
                yield()
            }
        }
        val pause = launch(clock) { fraction.animateTo(1f, appleMusicPlaybackSpec(false)) }
        frames(6)
        pause.cancelAndJoin()
        val interrupted = fraction.value
        assertTrue(interrupted > 0f && interrupted < 1f)
        val play = launch(clock) { fraction.animateTo(0f, appleMusicPlaybackSpec(true)) }
        try {
            frames(1)
            assertEquals(interrupted, fraction.value, 0.0001f)
            frames(120)
            assertEquals(0f, fraction.value, 0f)
        } finally {
            play.cancelAndJoin()
        }
    }

    @Test fun seekUsesTheFullRailAndClampsDraggingPastItsEnds() {
        assertEquals(0f, appleMusicSeekFraction(-20f, 320f, false), 0f)
        assertEquals(0.25f, appleMusicSeekFraction(80f, 320f, false), 0f)
        assertEquals(1f, appleMusicSeekFraction(350f, 320f, false), 0f)
        assertEquals(1f, appleMusicSeekFraction(0f, 320f, true), 0f)
        assertEquals(0.75f, appleMusicSeekFraction(80f, 320f, true), 0f)
        assertEquals(0f, appleMusicSeekFraction(320f, 320f, true), 0f)
    }

    @Test fun unavailableSeekGeometryNeverProducesAnInvalidFraction() {
        for (width in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertEquals(0f, appleMusicSeekFraction(80f, width, false), 0f)
        }
        assertEquals(0f, appleMusicSeekFraction(Float.NaN, 320f, false), 0f)
    }

    @Test fun onlyCompletedDraggingProducesOneSeekPosition() {
        val drag = AppleMusicSeekGesture()
        assertNull(drag.finish())
        drag.start(80f, 320f, false)
        drag.update(240f, 320f, false)
        assertEquals(0.75f, drag.fraction!!, 0f)
        assertEquals(0.75f, drag.finish()!!, 0f)
        assertNull(drag.fraction)
        assertNull(drag.finish())
    }

    @Test fun cancelledDraggingCannotCommitOrResumeFromStrayMoves() {
        val drag = AppleMusicSeekGesture()
        drag.start(80f, 320f, false)
        drag.update(300f, 320f, false)
        drag.cancel()
        drag.update(320f, 320f, false)
        assertNull(drag.fraction)
        assertNull(drag.finish())
        drag.start(80f, 320f, true)
        assertEquals(0.75f, drag.finish()!!, 0f)
    }
}
