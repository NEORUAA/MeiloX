package com.ljyh.mei.ui.component.sheet

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.*
import org.junit.Test

class PlayerSheetPressOriginTest {
    private val layout = Rect(20f, 700f, 420f, 750f)
    private val localPress = Rect(-16f, -5.5f, 424f, 49.5f)
    private val pressed = Rect(4f, 694.5f, 444f, 749.5f)
    private val artwork = Rect(32f, 708f, 64f, 740f)
    private val header = Rect(24f, 76f, 70f, 122f)
    private val host = Rect(0f, 0f, 440f, 800f)

    private fun assertBounds(expected: Rect, actual: Rect) {
        assertEquals(expected.left, actual.left, 0.0001f)
        assertEquals(expected.top, actual.top, 0.0001f)
        assertEquals(expected.right, actual.right, 0.0001f)
        assertEquals(expected.bottom, actual.bottom, 0.0001f)
    }

    @Test fun pressedCoordinatesRecoverTheRawContainerWithoutApplyingPressTwice() {
        val shiftedMeasurement = Rect(4f, 694.5f, 404f, 744.5f)
        val raw = playerMiniLayoutRect(pressed, localPress, IntSize(400, 50), shiftedMeasurement)
        assertBounds(layout, raw)
        assertBounds(pressed, playerUntransformRect(raw, raw, pressed))
    }

    @Test fun firstLyricTransitionFrameStartsAtTheVisiblePressedCover() {
        val raw = playerMiniLayoutRect(pressed, localPress, IntSize(400, 50), layout)
        val visualArtwork = playerUntransformRect(artwork, raw, pressed)
        assertBounds(Rect(17.2f, 703.3f, 52.4f, 738.5f), visualArtwork)
        assertBounds(visualArtwork, playerTopAnchoredArtworkRect(visualArtwork, header, pressed, host, 0f))
        assertBounds(header, playerTopAnchoredArtworkRect(visualArtwork, header, pressed, host, 1f))
    }

    @Test fun reverseProgressReturnsToTheRawCoverRatherThanThePressedOffset() {
        val release = PlayerPressRelease()
        release.update(0.5f)
        release.update(0.25f)
        val visual = playerUntransformRect(artwork, layout, pressed)
        val returning = playerContainerRect(artwork, visual, release.fraction(0.25f))
        assertBounds(Rect(24.6f, 705.65f, 58.2f, 739.25f), returning)
        assertBounds(artwork, playerContainerRect(artwork, visual, release.fraction(0f)))
    }

    @Test fun unavailableCoordinatesUseTheLastUsableLayout() {
        assertEquals(layout, playerMiniLayoutRect(null, localPress, IntSize(400, 50), layout))
        assertEquals(layout, playerMiniLayoutRect(pressed, Rect.Zero, IntSize(400, 50), layout))
        assertEquals(layout, playerMiniLayoutRect(pressed, localPress, IntSize.Zero, layout))
    }

    @Test fun fullPlayerReturnsDirectlyToTheSettledNavigationEndpoint() {
        val compact = Rect(76f, 748f, 364f, 798f)
        val compactArtwork = Rect(88f, 756f, 120f, 788f)
        val release = PlayerPressRelease()
        release.update(0.4f)
        assertBounds(pressed, playerMiniTransitionBounds(layout, layout, pressed, compact, release.fraction(0.4f), 0.4f))
        release.update(1f)
        for (progress in listOf(1f, 0.95f, 0.5f, 0.1f, 0f)) {
            release.update(progress)
            val container = playerMiniTransitionBounds(layout, layout, pressed, compact, release.fraction(progress), progress)
            val cover = playerMiniTransitionBounds(artwork, layout, pressed, compactArtwork, release.fraction(progress), progress)
            assertBounds(compact, container)
            assertBounds(compactArtwork, cover)
            if (progress == 0.5f) {
                assertBounds(Rect(38f, 374f, 402f, 799f), playerContainerRect(container, host, progress))
            }
        }
    }

    @Test fun immediateOpeningReversalTakesOneContinuousPathToTheSettledEndpoint() {
        val release = PlayerPressRelease()
        release.update(0.4f)
        val compact = Rect(76f, 748f, 364f, 798f)
        val firstFrame = playerContainerRect(pressed, host, 0.4f)
        var previousTop = firstFrame.top
        for (step in 40 downTo 0) {
            val progress = step / 100f
            release.update(progress)
            val source = playerMiniTransitionBounds(layout, layout, pressed, compact, release.fraction(progress), progress)
            val frame = playerContainerRect(source, host, progress)
            if (step == 40) assertBounds(firstFrame, frame)
            else assertEquals("The return has a single trajectory", 8.2825f, frame.top - previousTop, 0.0002f)
            if (step == 20) assertBounds(Rect(39.2f, 582.35f, 403.2f, 783.85f), frame)
            previousTop = frame.top
        }
        assertBounds(compact, playerMiniTransitionBounds(layout, layout, pressed, compact, release.fraction(0f), 0f))
    }

    @Test fun unpressedOpeningAndMissingCurrentGeometryKeepUsableEndpoints() {
        val compact = layout.translate(Offset(0f, 48f))
        assertBounds(layout, playerMiniTransitionBounds(layout, layout, null, compact, 1f, 0.4f))
        assertBounds(compact, playerMiniTransitionBounds(layout, layout, null, compact, 0f, 0f))
        assertBounds(layout, playerMiniTransitionBounds(layout, layout, pressed, Rect.Zero, 0f, 0f))
        assertBounds(compact, playerMiniTransitionBounds(layout, null, null, compact, 1f, 0.4f))
    }
}
