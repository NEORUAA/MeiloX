package com.ljyh.mei.ui.component.player.component.applemusic

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import androidx.compose.runtime.BroadcastFrameClock
import androidx.media3.common.C
import com.ljyh.mei.ui.component.player.state.playerProgressDuration
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class AppleMusicPlaybackMotionTest {
    @Test fun progressUsesMetadataUntilTheCurrentSourceReportsItsDuration() {
        assertEquals(222_000L, playerProgressDuration(C.TIME_UNSET, 222_000L))
        assertEquals(222_000L, playerProgressDuration(0L, 222_000L))
        assertEquals(221_500L, playerProgressDuration(221_500L, 222_000L))
    }

    @Test fun unknownProgressDurationStaysUnavailableInsteadOfBecomingOneMillisecond() {
        assertEquals(0L, playerProgressDuration(C.TIME_UNSET, null))
        assertEquals(0L, playerProgressDuration(C.TIME_UNSET, C.TIME_UNSET))
        assertEquals(0L, playerProgressDuration(0L, 0L))
        assertEquals(0L, playerProgressDuration(-1L, -1L))
    }

    @Test fun skipFollowsTheReferenceExchangeAndSettlingRebound() {
        fun frame(millis: Int) = appleMusicSkipFrame(millis.toFloat() / AppleMusicSkipDurationMillis)
        val start = frame(0)
        val early = frame(40)
        val leaving = frame(100)
        val overshoot = frame(320)
        val end = frame(AppleMusicSkipDurationMillis)

        assertEquals(0f, start.moving.position, 0f)
        assertEquals(1f, start.exiting.position, 0f)
        assertEquals(0f, start.entering.alpha, 0f)
        assertTrue(early.entering.position < 0f)
        assertTrue(early.moving.position in 0f..1f)
        assertTrue(early.exiting.position > 1f)
        assertTrue(early.entering.alpha > 0f && early.exiting.alpha > 0f)
        assertEquals(0f, leaving.exiting.alpha, 0f)
        assertTrue(overshoot.moving.position > 1f)
        assertTrue(overshoot.entering.position > 0f)
        assertEquals(0f, end.entering.position, 0f)
        assertEquals(1f, end.moving.position, 0f)
        assertEquals(1f, end.entering.scale, 0f)
        assertEquals(1f, end.moving.scale, 0f)
        assertEquals(1f, end.entering.alpha, 0f)
        assertEquals(1f, end.moving.alpha, 0f)
        assertEquals(0f, end.exiting.alpha, 0f)

        for (millis in 0..AppleMusicSkipDurationMillis) {
            val pose = frame(millis)
            assertEquals(1f, pose.moving.alpha, 0f)
            assertTrue(pose.moving.scale in 0.875f..1f)
            for (triangle in listOf(pose.entering, pose.moving, pose.exiting)) {
                assertTrue(triangle.scale in 0f..1f)
                assertTrue(triangle.alpha in 0f..1f)
            }
        }
    }

    @Test fun playPauseOverlapsDuringContractionInBothDirectionsAndReversesContinuously() = runBlocking {
        val clock = BroadcastFrameClock()
        var time = 0L
        suspend fun frames(count: Int) {
            repeat(count) {
                repeat(3) { yield() }
                clock.sendFrame(time)
                time += 10_000_000L
                repeat(3) { yield() }
            }
        }
        for ((source, target) in listOf("pause.fill" to "play.fill", "play.fill" to "pause.fill")) {
            val motion = AppleMusicPlayPauseMotion(source)
            val change = launch(clock) { motion.animateTo(target) }
            frames(5)
            assertTrue(motion.scale.value in 0.3f..0.5f)
            assertEquals(2, motion.layers.size)
            assertTrue(motion.layers.all { it.opacity.value > 0f && it.opacity.value < 1f })
            change.cancelAndJoin()
            val interruptedScale = motion.scale.value
            val interruptedAlphas = motion.layers.map { it.opacity.value }
            val reverse = launch(clock) { motion.animateTo(source) }
            frames(1)
            assertEquals(interruptedScale, motion.scale.value, 0.0001f)
            motion.layers.forEachIndexed { index, layer ->
                assertEquals(interruptedAlphas[index], layer.opacity.value, 0.0001f)
            }
            frames(40)
            reverse.join()
            assertEquals(source, motion.layers.single().icon)
            assertEquals(1f, motion.layers.single().opacity.value, 0f)
            assertEquals(1f, motion.scale.value, 0f)

            val complete = launch(clock) { motion.animateTo(target) }
            frames(9)
            assertEquals(0f, motion.layers.single { it.icon == source }.opacity.value, 0f)
            assertTrue(motion.layers.single { it.icon == target }.opacity.value > 0f)
            frames(32)
            complete.join()
            assertEquals(target, motion.layers.single().icon)
            assertEquals(1f, motion.layers.single().opacity.value, 0f)
            assertEquals(1f, motion.scale.value, 0f)
        }
    }

    @Test fun pauseShrinksMonotonicallyToThreeQuarterSizeIn480Milliseconds() {
        val animation = TargetBasedAnimation(appleMusicPlaybackSpec(false), Float.VectorConverter, 0f, 1f)
        assertEquals(480_000_000L, animation.durationNanos)
        var previousScale = 1f
        for (millis in 0..480 step 8) {
            val scale = 1f - 0.25f * animation.getValueFromNanos(millis * 1_000_000L)
            assertTrue(scale <= previousScale)
            assertTrue(scale >= 0.75f)
            previousScale = scale
        }
        assertEquals(0.75f, previousScale, 0f)
    }

    @Test fun playingReboundsAndSettlesAtTheOriginalPlayingSize() {
        val animation = TargetBasedAnimation(appleMusicPlaybackSpec(true), Float.VectorConverter, 1f, 0f)
        val scales = (0L..animation.durationNanos step 8_000_000L)
            .map { 1f - 0.25f * animation.getValueFromNanos(it) }
        assertTrue(scales.any { it > 1f })
        assertTrue(scales.max() < 1.025f)
        assertEquals(1f, 1f - 0.25f * animation.getValueFromNanos(animation.durationNanos), 0f)
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
