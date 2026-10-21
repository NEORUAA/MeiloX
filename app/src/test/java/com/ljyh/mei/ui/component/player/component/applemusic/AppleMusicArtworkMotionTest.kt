package com.ljyh.mei.ui.component.player.component.applemusic

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class AppleMusicArtworkMotionTest {
    private val normal = Rect(48.25f, 151.75f, 351.75f, 455.25f)
    private val header = Rect(28.5f, 76f, 92.5f, 140f)

    private fun frame(progress: Float, playbackScale: Float = 1f, bounds: Rect = normal) =
        appleMusicArtworkFrame(bounds, header, 12f, 8f, 16f, playbackScale, progress)

    @Test fun enlargedAlbumMatchesTheProgressWidthWithoutMovingItsBottom() {
        val previous = Rect(48f, 160f, 352f, 464f)
        val enlarged = appleMusicAlbumBounds(previous, 400f, 24f, 36f)
        assertEquals(Rect(24f, 112f, 376f, 464f), enlarged)
        assertEquals(previous.bottom, enlarged.bottom, 0f)
        assertTrue(enlarged.top < previous.top)
    }

    @Test fun shortScreensKeepTheBottomAnchorWithoutCrossingTheTopSafeArea() {
        val previous = Rect(100f, 48f, 300f, 248f)
        val enlarged = appleMusicAlbumBounds(previous, 400f, 24f, 36f)
        assertEquals(36f, enlarged.top, 0f)
        assertEquals(previous.bottom, enlarged.bottom, 0f)
        assertEquals(212f, enlarged.width, 0f)
        assertEquals(200f, enlarged.center.x, 0f)
    }

    @Test fun newAlbumAndHeaderBoundsShareExactEndpointsAcrossInterruptedMotion() {
        val enlarged = appleMusicAlbumBounds(normal, 400f, 24f, 36f)
        for (progress in listOf(0f, 0.6f, 0.25f, 1f, 0.75f, 0f)) {
            val value = frame(progress, bounds = enlarged)
            assertTrue(value.bounds.top >= 36f)
            assertEquals(value.bounds.width, value.bounds.height, 0.001f)
        }
        assertEquals(enlarged, frame(0f, bounds = enlarged).bounds)
        assertEquals(header, frame(1f, bounds = enlarged).bounds)
    }

    @Test fun normalAndHeaderKeepExactVisualEndpoints() {
        assertEquals(normal, frame(0f).bounds)
        for (scale in listOf(0.9f, 0.95f, 1f)) {
            assertEquals(header, frame(1f, scale).bounds)
            assertEquals(8f, frame(1f, scale).cornerRadius, 0f)
            assertEquals(0f, frame(1f, scale).shadowElevation, 0f)
        }
    }

    @Test fun pausedArtworkKeepsItsCenterAndScaledOutline() {
        val paused = frame(0f, 0.9f)
        assertEquals(normal.center.x, paused.bounds.center.x, 0.0001f)
        assertEquals(normal.center.y, paused.bounds.center.y, 0.0001f)
        assertEquals(normal.width * 0.9f, paused.bounds.width, 0.0001f)
        assertEquals(10.8f, paused.cornerRadius, 0.0001f)
        assertEquals(14.4f, paused.shadowElevation, 0.0001f)
    }

    @Test fun compactAndTallLayoutsMoveContinuouslyToTheHeader() {
        for (bounds in listOf(normal, Rect(100f, 120f, 240f, 260f), Rect(96f, 360f, 1296f, 1560f))) {
            for (scale in listOf(0.9f, 1f)) {
                var previous = frame(0f, scale, bounds)
                for (step in 1..1000) {
                    val next = frame(step / 1000f, scale, bounds)
                    assertTrue(next.bounds.left <= previous.bounds.left + 0.0001f)
                    assertTrue(next.bounds.top <= previous.bounds.top + 0.0001f)
                    assertTrue(next.bounds.width <= previous.bounds.width + 0.0001f)
                    assertEquals(next.bounds.width, next.bounds.height, 0.001f)
                    assertTrue(next.cornerRadius <= previous.cornerRadius + 0.0001f)
                    assertTrue(next.shadowElevation <= previous.shadowElevation + 0.0001f)
                    previous = next
                }
                assertEquals(header, previous.bounds)
            }
        }
    }

    @Test fun fixedImageScalePreservesFractionalPlacementNearTheHeader() {
        val before = frame(0.999f)
        val after = frame(0.9995f)
        assertTrue(before.bounds.width > after.bounds.width)
        assertTrue(before.bounds.top > after.bounds.top)
        assertTrue(after.bounds.width > header.width)
        assertTrue(after.bounds.top > header.top)
        assertTrue(before.bounds.width - after.bounds.width < 0.5f)
        assertTrue(after.bounds.width - header.width < 0.5f)
    }

    @Test fun springFinishesWithinASubpixelEvenOnALargeCover() {
        val bounds = Rect(96f, 360f, 1296f, 1560f)
        for ((from, to) in listOf(0f to 1f, 1f to 0f)) {
            val animation = TargetBasedAnimation(appleMusicLyricSpring(), Float.VectorConverter, from, to)
            val before = frame(animation.getValueFromNanos(animation.durationNanos - 16_666_667L), bounds = bounds)
            val end = frame(animation.getValueFromNanos(animation.durationNanos), bounds = bounds)
            assertTrue(abs(before.bounds.width - end.bounds.width) < 0.5f)
            assertTrue(abs(before.bounds.top - end.bounds.top) < 0.5f)
            assertEquals(frame(to, bounds = bounds), end)
        }
    }

    @Test fun cancelledLyricMotionHoldsItsPositionAndReversesFromThere() = runBlocking {
        val clock = BroadcastFrameClock()
        val progress = Animatable(0f)
        var time = 0L
        suspend fun frames(count: Int) {
            repeat(count) {
                yield()
                clock.sendFrame(time)
                time += 16_666_667L
                yield()
            }
        }
        val opening = launch(clock) { progress.animateTo(1f, appleMusicLyricSpring()) }
        frames(8)
        opening.cancelAndJoin()
        val interrupted = progress.value
        assertTrue(interrupted > 0f && interrupted < 1f)
        frames(4)
        assertEquals(interrupted, progress.value, 0f)
        val closing = launch(clock) { progress.animateTo(0f, appleMusicLyricSpring()) }
        try {
            frames(1)
            assertEquals(interrupted, progress.value, 0.0001f)
            frames(120)
            assertEquals(0f, progress.value, 0f)
        } finally {
            closing.cancelAndJoin()
        }
    }

    @Test fun overshootCannotInvertTheOutlineOrMoveBeyondTheEndpoints() {
        assertEquals(frame(0f), frame(-0.05f))
        assertEquals(frame(1f), frame(1.05f))
    }
}
