package com.ljyh.mei.ui.component.sheet

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import com.ljyh.mei.ui.component.player.component.applemusic.appleMusicArtworkFrame
import org.junit.Assert.*
import org.junit.Test

class PlayerSheetArtworkTargetTest {
    private val normal = Rect(48f, 150f, 352f, 454f)
    private val header = Rect(24f, 76f, 70f, 122f)
    private val layoutSize = IntSize(304, 304)
    private val host = Rect(0f, 0f, 400f, 800f)
    private val mini = Rect(20f, 708f, 52f, 740f)
    private val miniContainer = Rect(12f, 700f, 388f, 748f)

    private fun frame(progress: Float, playbackScale: Float = 1f) =
        appleMusicArtworkFrame(normal, header, 12f, 4f, 16f, playbackScale, progress)

    private fun assertBounds(expected: Rect, actual: Rect) {
        assertEquals(expected.left, actual.left, 0.0001f)
        assertEquals(expected.top, actual.top, 0.0001f)
        assertEquals(expected.right, actual.right, 0.0001f)
        assertEquals(expected.bottom, actual.bottom, 0.0001f)
    }

    @Test fun explicitHeaderNeverReadsTheHiddenLargeLayout() {
        val target = playerArtworkTargetGeometry(PlayerArtworkGeometry(header, layoutSize), host) {
            error("Hidden layout coordinates must not override the visual target")
        }
        assertEquals(header, target.bounds)
        assertEquals(46f, target.bounds.width, 0f)
        assertEquals(layoutSize, target.layoutSize)
    }

    @Test fun hostOffsetIsAppliedOnceWithoutScalingTheHeader() {
        val offsetHost = Rect(15f, 31.5f, 415f, 831.5f)
        val viewport = Rect(0f, 76f, 400f, 650f)
        val target = playerArtworkTargetGeometry(
            PlayerArtworkGeometry(header, layoutSize, opacity = 0.35f, clipBounds = viewport), offsetHost,
        ) {
            error("Unexpected fallback")
        }
        assertEquals(Rect(39f, 107.5f, 85f, 153.5f), target.bounds)
        assertEquals(layoutSize, target.layoutSize)
        assertEquals(0.35f, target.opacity, 0f)
        assertEquals(Rect(15f, 107.5f, 415f, 681.5f), target.clipBounds)
    }

    @Test fun queueViewportClipExpandsContinuouslyWhenTheSheetReverses() {
        val offsetHost = Rect(15f, 31.5f, 415f, 831.5f)
        val viewport = Rect(0f, 76f, 400f, 650f)
        val target = playerArtworkTargetGeometry(
            PlayerArtworkGeometry(header, layoutSize, clipBounds = viewport), offsetHost,
        ) { error("Unexpected fallback") }
        val targetClip = checkNotNull(target.clipBounds)
        assertBounds(Rect(0f, 0f, 400f, 800f), playerArtworkClipRect(targetClip, offsetHost, 0f))
        assertBounds(viewport, playerArtworkClipRect(targetClip, offsetHost, 1f))
        val interrupted = playerArtworkClipRect(targetClip, offsetHost, 0.65f)
        val closing = playerArtworkClipRect(targetClip, offsetHost, 0.3f)
        assertTrue(closing.top < interrupted.top)
        assertTrue(closing.bottom > interrupted.bottom)
        assertBounds(interrupted, playerArtworkClipRect(targetClip, offsetHost, 0.65f))
    }

    @Test fun offscreenQueueRetainsSharedArtworkOwnershipThroughReversedFades() {
        for (edgeBounds in listOf(Rect(24f, 30f, 70f, 76f), Rect(24f, 650f, 70f, 696f))) {
            val target = playerArtworkTargetGeometry(
                PlayerArtworkGeometry(edgeBounds, layoutSize, opacity = 0f), host,
            ) { error("An invisible queue header still has an explicit transition endpoint") }
            assertTrue(playerSharesArtwork(true, true, true, mini, target.bounds, host))
            assertEquals(1f, playerArtworkOpacity(target.opacity, 0f), 0f)
            assertEquals(0f, playerArtworkOpacity(target.opacity, 1f), 0f)
            val interruptedOpacity = playerArtworkOpacity(target.opacity, 0.65f)
            assertTrue(playerArtworkOpacity(target.opacity, 0.3f) > interruptedOpacity)
            assertEquals(interruptedOpacity, playerArtworkOpacity(target.opacity, 0.65f), 0f)
            assertTrue(playerArtworkOpacity(target.opacity, 0.8f) < interruptedOpacity)
        }
    }

    @Test fun reopeningAndCollapsingLyricsKeepTheSmallSharedTarget() {
        for (playbackScale in listOf(0.9f, 1f)) {
            repeat(3) {
                val visual = frame(1f, playbackScale)
                val target = playerArtworkTargetGeometry(PlayerArtworkGeometry(visual.bounds, layoutSize), host) {
                    // The measured image can still have its full logical dimensions.
                    PlayerArtworkGeometry(Rect(24f, 76f, 328f, 380f), layoutSize)
                }
                for (progress in (0..100).map { it / 100f } + (100 downTo 0).map { it / 100f }) {
                    val bounds = playerTopAnchoredArtworkRect(mini, target.bounds, miniContainer, host, progress)
                    assertTrue(bounds.width >= 32f && bounds.width <= 46f)
                    if (progress == 1f) assertEquals(header, bounds)
                    if (progress == 0f) assertEquals(mini, bounds)
                }
            }
        }
    }

    @Test fun normalAndPausedPlaybackRetainTheirLargeCenteredTargets() {
        for (playbackScale in listOf(0.9f, 1f)) {
            val visual = frame(0f, playbackScale)
            val target = playerArtworkTargetGeometry(PlayerArtworkGeometry(visual.bounds, layoutSize), host) {
                error("Unexpected fallback")
            }
            assertEquals(normal.center.x, target.bounds.center.x, 0.0001f)
            assertEquals(normal.center.y, target.bounds.center.y, 0.0001f)
            assertEquals(304f * playbackScale, target.bounds.width, 0.0001f)
            assertBounds(target.bounds, playerArtworkRect(mini, target.bounds, 1f))
        }
    }

    @Test fun interruptedLyricMotionUsesItsCurrentVisualSize() {
        val visual = frame(0.42f, 0.9f)
        val target = playerArtworkTargetGeometry(PlayerArtworkGeometry(visual.bounds, layoutSize), host) {
            PlayerArtworkGeometry(normal, layoutSize)
        }
        assertEquals(visual.bounds, target.bounds)
        assertTrue(target.bounds.width > header.width && target.bounds.width < normal.width)
        assertBounds(target.bounds, playerTopAnchoredArtworkRect(mini, target.bounds, miniContainer, host, 1f))
    }

    @Test fun classicKeepsItsMeasuredRootBoundsAndLogicalSize() {
        val measured = PlayerArtworkGeometry(Rect(81.25f, 113.5f, 354.75f, 387f), layoutSize)
        val target = playerArtworkTargetGeometry(null, Rect(15f, 31.5f, 415f, 831.5f)) { measured }
        assertEquals(measured, target)
    }

    @Test fun classicPausedOpeningDoesNotReuseAPlayingSizedHiddenLayer() {
        val measured = PlayerArtworkGeometry(normal, layoutSize, opacity = 0.6f, clipBounds = host)
        val target = playerArtworkTargetGeometry(null, host, playbackScale = 0.9f) { measured }
        val paused = Rect(63.2f, 165.2f, 336.8f, 438.8f)
        assertBounds(paused, target.bounds)
        assertBounds(paused, playerArtworkRect(mini, target.bounds, 1f))
        assertEquals(layoutSize, target.layoutSize)
        assertEquals(measured.opacity, target.opacity, 0f)
        assertEquals(measured.clipBounds, target.clipBounds)
    }

    @Test fun classicPlayingOpeningDoesNotReuseAPausedSizedHiddenLayer() {
        val measured = PlayerArtworkGeometry(Rect(63.2f, 165.2f, 336.8f, 438.8f), layoutSize)
        val target = playerArtworkTargetGeometry(null, host, playbackScale = 1f) { measured }
        assertBounds(normal, target.bounds)
        assertBounds(normal, playerArtworkRect(mini, target.bounds, 1f))
    }

    @Test fun classicOffscreenPageRetainsItsCenterWhenPlaybackScaleChanges() {
        val offscreen = Rect(-352f, 150f, -48f, 454f)
        val measured = PlayerArtworkGeometry(offscreen, layoutSize)
        val target = playerArtworkTargetGeometry(null, host, playbackScale = 0.9f) { measured }
        assertEquals(offscreen.center.x, target.bounds.center.x, 0.0001f)
        assertEquals(offscreen.center.y, target.bounds.center.y, 0.0001f)
        assertEquals(273.6f, target.bounds.width, 0.0001f)
        assertTrue(target.bounds.right < 0f)
    }
}
