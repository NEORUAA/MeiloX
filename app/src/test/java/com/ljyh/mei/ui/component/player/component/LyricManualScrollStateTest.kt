package com.ljyh.mei.ui.component.player.component

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricManualScrollStateTest {
    @Test
    fun initialPlaybackDoesNotBrowseOrRequestRepositioning() {
        val state = LyricManualScrollState()

        state.resumeIfIdle(nowMs = 10_000, isScrollInProgress = false)

        assertFalse(state.isBrowsing)
        assertFalse(state.isDragging)
        assertFalse(state.returnToPlaybackPending)
    }

    @Test
    fun dragReleaseAllowsFiveFullSecondsBeforeReturning() {
        val state = LyricManualScrollState()
        state.onDragStarted(nowMs = 1_000)
        state.onDragStopped(nowMs = 2_000)

        state.resumeIfIdle(nowMs = 6_999, isScrollInProgress = false)
        assertTrue(state.isBrowsing)
        assertFalse(state.isDragging)

        state.resumeIfIdle(nowMs = 7_000, isScrollInProgress = false)
        assertFalse(state.isBrowsing)
    }

    @Test
    fun anotherDragReplacesThePreviousReturnDeadline() {
        val state = LyricManualScrollState()
        state.onDragStarted(nowMs = 0)
        state.onDragStopped(nowMs = 1_000)
        state.onDragStarted(nowMs = 5_000)
        state.onDragStopped(nowMs = 5_500)

        state.resumeIfIdle(nowMs = 6_000, isScrollInProgress = false)
        assertTrue(state.isBrowsing)
        state.resumeIfIdle(nowMs = 10_499, isScrollInProgress = false)
        assertTrue(state.isBrowsing)

        state.resumeIfIdle(nowMs = 10_500, isScrollInProgress = false)
        assertFalse(state.isBrowsing)
    }

    @Test
    fun holdingADragDoesNotReturnEvenWithoutFurtherScrollDeltas() {
        val state = LyricManualScrollState()
        state.onDragStarted(nowMs = 0)

        state.resumeIfIdle(nowMs = 60_000, isScrollInProgress = false)
        assertTrue(state.isDragging)
        assertTrue(state.isBrowsing)

        state.onDragStopped(nowMs = 60_000)
        state.resumeIfIdle(nowMs = 64_999, isScrollInProgress = false)
        assertTrue(state.isBrowsing)
        state.resumeIfIdle(nowMs = 65_000, isScrollInProgress = false)
        assertFalse(state.isBrowsing)
    }

    @Test
    fun aFlingThatOutlastsTheDeadlineReturnsWhenItEnds() {
        val state = LyricManualScrollState()
        state.onDragStarted(nowMs = 0)
        state.onDragStopped(nowMs = 1_000)

        state.resumeIfIdle(nowMs = 6_000, isScrollInProgress = true)
        assertTrue(state.isBrowsing)
        state.resumeIfIdle(nowMs = 8_000, isScrollInProgress = true)
        assertTrue(state.isBrowsing)

        state.resumeIfIdle(nowMs = 8_001, isScrollInProgress = false)
        assertFalse(state.isBrowsing)
    }

    @Test
    fun aShortFlingDoesNotRestartTheGracePeriod() {
        val state = LyricManualScrollState()
        state.onDragStarted(nowMs = 0)
        state.onDragStopped(nowMs = 1_000)
        state.resumeIfIdle(nowMs = 3_000, isScrollInProgress = true)
        state.resumeIfIdle(nowMs = 4_000, isScrollInProgress = false)
        assertTrue(state.isBrowsing)

        state.resumeIfIdle(nowMs = 6_000, isScrollInProgress = false)
        assertFalse(state.isBrowsing)
    }

    @Test
    fun returningStillRequestsPositioningWithoutAPlaybackLineChange() {
        val state = LyricManualScrollState()
        state.onDragStarted(nowMs = 0)
        state.onDragStopped(nowMs = 1_000)
        assertTrue(state.returnToPlaybackPending)

        state.resumeIfIdle(nowMs = 6_000, isScrollInProgress = false)
        assertFalse(state.isBrowsing)
        assertTrue(state.returnToPlaybackPending)
        state.resumeIfIdle(nowMs = 7_000, isScrollInProgress = false)
        assertTrue(state.returnToPlaybackPending)

        state.onPlaybackPositioned()
        assertFalse(state.returnToPlaybackPending)
    }

    @Test
    fun stalePositioningCompletionDoesNotClearANewerDragsReturnRequest() {
        val state = LyricManualScrollState()
        state.onDragStarted(nowMs = 0)
        state.onDragStopped(nowMs = 1_000)
        state.resumeIfIdle(nowMs = 6_000, isScrollInProgress = false)
        assertFalse(state.isBrowsing)

        state.onDragStarted(nowMs = 6_100)
        state.onPlaybackPositioned()
        assertTrue(state.isBrowsing)
        assertTrue(state.returnToPlaybackPending)

        state.onDragStopped(nowMs = 6_200)
        state.resumeIfIdle(nowMs = 11_200, isScrollInProgress = false)
        assertFalse(state.isBrowsing)
        assertTrue(state.returnToPlaybackPending)

        state.onPlaybackPositioned()
        assertFalse(state.returnToPlaybackPending)
    }

    @Test
    fun selectingALineResumesPlaybackFollowingBeforeTheDeadline() {
        val state = LyricManualScrollState()
        state.onDragStarted(nowMs = 0)
        state.onDragStopped(nowMs = 1_000)
        state.resumeIfIdle(nowMs = 2_000, isScrollInProgress = false)
        assertTrue(state.isBrowsing)

        state.onLineSelected()
        assertFalse(state.isBrowsing)
        assertTrue(state.returnToPlaybackPending)

        state.onPlaybackPositioned()
        assertFalse(state.returnToPlaybackPending)
    }
}
