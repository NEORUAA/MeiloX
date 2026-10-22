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

    @Test fun queueTailKeepsShortHeadersReachableAndLastSongsAboveTheFade() {
        val rowHeight = 56f
        for (viewport in listOf(280f, 460f, 680f)) {
            val fadeDepth = 104f
            for (remainingSongs in listOf(0, 1, 2, 20)) {
                for (sectionHeight in listOf(48f, 96f, 152f)) {
                    val contentHeight = 138f + sectionHeight + remainingSongs * rowHeight
                    val tail = playerQueueTailHeight(viewport, contentHeight, rowHeight, fadeDepth)
                    assertTrue("The header must be able to reach the viewport top", contentHeight + tail >= viewport)
                    assertTrue("The tail must reserve at least one song row", tail >= rowHeight)
                    assertTrue("The last song must finish above the fade", viewport - tail <= viewport - fadeDepth)
                    if (contentHeight >= viewport) assertEquals(fadeDepth, tail, 0f)
                }
            }
        }
        assertEquals(56f, playerQueueTailHeight(280f, 600f, rowHeight, 0f), 0f)
        assertEquals(130f, playerQueueTailHeight(460f, 414f, rowHeight, 104f, 84f), 0f)
        assertEquals(212f, playerQueueTailHeight(460f, 248f, rowHeight, 104f, 84f), 0f)
        for (contentHeight in listOf(248f, 356f, 357f, 414f, 600f)) {
            val tail = playerQueueTailHeight(460f, contentHeight, rowHeight, 104f, 84f)
            if (contentHeight + 104f > 460f) {
                assertTrue("Scrollable songs must allow the controls to dock", contentHeight + tail >= 544f)
            } else {
                assertEquals("Short queues must stop at the current song", 460f, contentHeight + tail, 0f)
            }
        }
    }

    @Test fun queueSnapsBetweenThreeStatesWithoutResettingSectionScrolling() {
        fun target(top: Float, direction: Int, hasHistory: Boolean = true, canPinUpcoming: Boolean = true) =
            playerQueueSnapTarget(top, 84f, 600f, direction, hasHistory, canPinUpcoming)

        assertEquals(0f, target(48f, -1)!!, 0f)
        assertEquals(0f, target(149f, -1)!!, 0f)
        assertEquals(600f, target(150f, -1)!!, 0f)
        assertEquals(600f, target(240f, -1)!!, 0f)
        assertEquals(0f, target(552f, 1)!!, 0f)
        assertEquals(-84f, target(-16f, 1)!!, 0f)
        assertEquals(0f, target(-68f, -1)!!, 0f)
        assertEquals(0f, target(-16f, 1, canPinUpcoming = false)!!, 0f)
        assertEquals(0f, target(48f, -1, hasHistory = false)!!, 0f)
        assertEquals(0f, target(200f, 0)!!, 0f)
        assertEquals(600f, target(400f, 0)!!, 0f)
        assertNull(target(650f, 1))
        assertNull(target(-150f, -1))
    }

    @Test fun shortHistoryFillsOnlyTheSpaceBetweenItsHeadingAndSongs() {
        for (viewport in listOf(280f, 460f, 680f)) {
            for (count in listOf(1, 2, 4, 20)) {
                val heading = 52f
                val rows = count * 56f
                val gap = 16f
                val padding = playerQueueHistoryPadding(viewport, heading, rows, gap)
                assertTrue(padding >= 0f)
                assertEquals(maxOf(viewport, heading + rows + gap), heading + padding + rows + gap, 0f)
            }
        }
    }

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

    @Test fun measuredFooterAndMetadataLimitArtworkInShortWindows() {
        val previous = Rect(48f, 160f, 352f, 464f)
        val limited = appleMusicAlbumBounds(previous, 400f, 26f, 36f, bottomLimit = 240f)
        assertEquals(Rect(98f, 36f, 302f, 240f), limited)
        val unconstrained = appleMusicAlbumBounds(previous, 400f, 26f, 36f)
        assertEquals(unconstrained, appleMusicAlbumBounds(previous, 400f, 26f, 36f, bottomLimit = 600f))
        assertEquals(limited, frame(0f, bounds = limited).bounds)
        assertEquals(header, frame(1f, bounds = limited).bounds)
    }

    @Test fun exhaustedArtworkSpaceNeverProducesNegativeDimensions() {
        val previous = Rect(48f, 160f, 352f, 464f)
        for (bottom in listOf(0f, 20f, 36f)) {
            val bounds = appleMusicAlbumBounds(previous, 400f, 26f, 36f, bottomLimit = bottom)
            assertEquals(0f, bounds.width, 0f)
            assertEquals(0f, bounds.height, 0f)
            assertEquals(36f, bounds.top, 0f)
            assertEquals(200f, bounds.center.x, 0f)
        }
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

    @Test fun offscreenQueueTargetsStayAtTheBoundaryTheyLeft() {
        val viewportTop = 100f
        val viewportBottom = 500f
        val above = appleMusicQueueArtworkTarget(
            Rect(28.5f, -300f, 92.5f, -236f), viewportTop, viewportBottom,
        )
        val below = appleMusicQueueArtworkTarget(
            Rect(28.5f, 900f, 92.5f, 964f), viewportTop, viewportBottom,
        )
        assertEquals(Rect(28.5f, 36f, 92.5f, 100f), above.bounds)
        assertEquals(Rect(28.5f, 500f, 92.5f, 564f), below.bounds)
        for (target in listOf(above, below)) {
            assertEquals(0f, target.opacity, 0f)
            assertEquals(target.bounds, appleMusicArtworkFrame(
                normal, target.bounds, 12f, 8f, 16f, 0.75f, 1f,
            ).bounds)
            assertEquals(normal, appleMusicArtworkFrame(
                normal, target.bounds, 12f, 8f, 16f, 1f, 0f,
            ).bounds)
        }
    }

    @Test fun partialQueueCoversFadeByTheirVisibleHeightAtBothEdges() {
        val topPartial = Rect(28.5f, 68f, 92.5f, 132f)
        val bottomPartial = Rect(28.5f, 468f, 92.5f, 532f)
        for (bounds in listOf(topPartial, bottomPartial)) {
            val target = appleMusicQueueArtworkTarget(bounds, 100f, 500f)
            assertEquals(bounds, target.bounds)
            assertEquals(0.5f, target.opacity, 0f)
        }
        val visible = Rect(28.5f, 200f, 92.5f, 264f)
        val target = appleMusicQueueArtworkTarget(visible, 100f, 500f)
        assertEquals(visible, target.bounds)
        assertEquals(1f, target.opacity, 0f)
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
        val targets = listOf(
            header,
            appleMusicQueueArtworkTarget(Rect(28.5f, -300f, 92.5f, -236f), 100f, 500f).bounds,
            appleMusicQueueArtworkTarget(Rect(28.5f, 900f, 92.5f, 964f), 100f, 500f).bounds,
        )
        fun artworkFrames() = targets.map { target ->
            appleMusicArtworkFrame(normal, target, 12f, 8f, 16f, 1f, progress.value)
        }
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
        val interruptedFrames = artworkFrames()
        assertTrue(interrupted > 0f && interrupted < 1f)
        frames(4)
        assertEquals(interrupted, progress.value, 0f)
        assertEquals(interruptedFrames, artworkFrames())
        val closing = launch(clock) { progress.animateTo(0f, appleMusicLyricSpring()) }
        try {
            frames(1)
            assertEquals(interrupted, progress.value, 0.0001f)
            assertEquals(interruptedFrames, artworkFrames())
            frames(120)
            assertEquals(0f, progress.value, 0f)
            artworkFrames().forEach { assertEquals(normal, it.bounds) }
        } finally {
            closing.cancelAndJoin()
        }
    }

    @Test fun overshootCannotInvertTheOutlineOrMoveBeyondTheEndpoints() {
        assertEquals(frame(0f), frame(-0.05f))
        assertEquals(frame(1f), frame(1.05f))
    }
}
