package com.ljyh.mei.ui.component.sheet

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class PlayerSheetMotionTest {
    private val mini = Rect(12f, 700f, 388f, 748f)
    private val page = Rect(0f, 0f, 400f, 800f)

    @Test fun pressedArtworkKeepsItsActualOriginAndReachesSquareTarget() {
        val pressed = Rect(3f, 707f, 36f, 743f)
        val target = Rect(50f, 100f, 350f, 400f)
        assertEquals(pressed, playerArtworkRect(pressed, target, 0f))
        assertEquals(target, playerArtworkRect(pressed, target, 1f))
        assertEquals(168f, playerArtworkRect(pressed, target, 0.5f).height, 0.001f)
        assertEquals(pressed, playerContainerRect(pressed, page, 0f))
    }

    @Test fun reverseDragReleasesPressContinuouslyToDefaultBounds() {
        val release = PlayerPressRelease()
        val normal = Rect(10f, 700f, 410f, 750f)
        val pressed = Rect(-10f, 695f, 430f, 755f)
        release.update(0f)
        assertEquals(1f, release.fraction(0f), 0f)
        release.update(0.4f)
        assertEquals(1f, release.fraction(0.4f), 0f)
        release.update(0.2f)
        assertEquals(0.5f, release.fraction(0.2f), 0.001f)
        release.update(0.6f)
        assertEquals(0.5f, release.fraction(0.6f), 0.001f)
        release.update(0.3f)
        assertEquals(0.25f, release.fraction(0.3f), 0.001f)
        assertEquals(normal, playerContainerRect(normal, pressed, release.fraction(0f)))
        assertEquals(normal, playerUntransformRect(pressed, pressed, normal))
        release.update(0f)
        release.update(0.2f)
        assertEquals(0f, release.fraction(0.2f), 0f)
        release.reset()
        assertEquals(1f, release.fraction(0f), 0f)
    }

    @Test fun pressLightFadesBeforeMiniContent() {
        assertEquals(1f, playerPressHighlightAlpha(0f), 0f)
        assertTrue(playerPressHighlightAlpha(0.09f) > 0f)
        assertEquals(0f, playerPressHighlightAlpha(0.1f), 0f)
        assertTrue(playerMiniContentAlpha(0.1f) > 0f)
        assertTrue(playerMiniContentAlpha(0.7f) > 0f)
        assertEquals(0f, playerMiniContentAlpha(0.8f), 0f)
    }

    @Test fun requestedSpringAndHandoffContract() {
        assertEquals(300f, playerSheetSpring().stiffness, 0f)
        assertEquals(1f, playerSheetSpring().dampingRatio, 0f)
        assertEquals(0f, playerBackgroundAlpha(0f), 0f)
        assertTrue(playerBackgroundAlpha(0.98f) < 1f)
        assertEquals(1f, playerBackgroundAlpha(0.99f), 0f)
        assertTrue(playerMiniContentAlpha(0.79f) > 0f)
        assertEquals(0f, playerMiniContentAlpha(0.8f), 0f)
        assertEquals(0f, playerContentAlpha(0.3f), 0f)
        assertTrue(playerContentAlpha(0.31f) > 0f)
        assertTrue(playerContentAlpha(0.89f) < 1f)
        assertEquals(1f, playerContentAlpha(0.9f), 0f)
    }

    @Test fun allAlphaChannelsStayBoundedAndMonotonicIncludingOvershoot() {
        val channels = listOf(::playerBackgroundAlpha, ::playerContentAlpha)
        for (channel in channels) {
            var previous = 0f
            for (step in -10..110) {
                val alpha = channel(step / 100f)
                assertTrue(alpha in 0f..1f)
                assertTrue(alpha >= previous)
                previous = alpha
            }
        }
        for (channel in listOf(::playerMiniContentAlpha, ::playerPressHighlightAlpha)) {
            var previous = 1f
            for (step in -10..110) {
                val alpha = channel(step / 100f)
                assertTrue(alpha in 0f..1f)
                assertTrue(alpha <= previous)
                previous = alpha
            }
        }
    }

    @Test fun containerUsesMeasuredEdgesAndReversesWithoutChangingPath() {
        assertEquals(mini, playerContainerRect(mini, page, 0f))
        assertEquals(page, playerContainerRect(mini, page, 1f))
        assertEquals(Rect(6f, 350f, 394f, 774f), playerContainerRect(mini, page, 0.5f))
        val outward = (0..100).map { playerContainerRect(mini, page, it / 100f) }
        val inward = (100 downTo 0).map { playerContainerRect(mini, page, it / 100f) }
        assertEquals(outward, inward.reversed())
    }

    @Test fun artworkUsesUniformScaleAndMeloxCenterPath() {
        val source = Rect(20f, 708f, 52f, 740f)
        val target = Rect(60f, 100f, 340f, 380f)
        assertEquals(source, playerArtworkRect(source, target, 0f))
        assertEquals(target, playerArtworkRect(source, target, 1f))
        val half = playerArtworkRect(source, target, 0.5f)
        assertEquals(156f, half.width, 0.001f)
        assertEquals(half.width, half.height, 0.001f)
        assertEquals(179.5f, half.center.x, 0.001f)
        assertEquals(590.9f, half.center.y, 0.001f)
    }

    @Test fun artworkPreservesRectangularAspectRatio() {
        val source = Rect(0f, 0f, 32f, 24f)
        val target = Rect(100f, 100f, 420f, 340f)
        for (step in 0..100) {
            val bounds = playerArtworkRect(source, target, step / 100f)
            assertEquals(4f / 3f, bounds.width / bounds.height, 0.001f)
        }
    }

    @Test fun dragDirectionPrefersVelocityThenLastMovementThenOrigin() {
        assertTrue(playerDragOpens(-1f, 10f, false))
        assertFalse(playerDragOpens(1f, -10f, true))
        assertTrue(playerDragOpens(0f, -1f, false))
        assertFalse(playerDragOpens(0f, 1f, true))
        assertTrue(playerDragOpens(0f, 0f, true))
        assertFalse(playerDragOpens(0f, 0f, false))
    }

    @Test fun draggingHonorsDensityAndBothBounds() {
        assertEquals(120f, playerDragValue(100f, -60f, 3f, 100f, 800f), 0f)
        assertEquals(100f, playerDragValue(100f, 1000f, 3f, 100f, 800f), 0f)
        assertEquals(800f, playerDragValue(700f, -1000f, 3f, 100f, 800f), 0f)
    }

    @Test fun consecutiveDeltasAccumulateWithoutWaitingForCoroutines() = runBlocking {
        val clock = BroadcastFrameClock()
        val state = BottomSheetState(CoroutineScope(coroutineContext + clock), 2f, {}, 0.dp, 100.dp,
            800.dp, 100.dp, collapsedAnchor)
        try {
            state.beginDrag()
            repeat(10) { state.dispatchRawDelta(-20f) }
            assertEquals(200.dp, state.value)
            assertEquals(1f / 7f, state.progress, 0.00001f)
            state.dispatchRawDelta(10000f)
            assertEquals(100.dp, state.value)
            assertFalse(state.isDismissed)
        } finally { state.dispose() }
    }

    @Test fun cancellationReturnsToTheStartingEndpoint() = runBlocking {
        val clock = BroadcastFrameClock()
        val state = BottomSheetState(CoroutineScope(coroutineContext + clock), 1f, {}, 0.dp, 100.dp,
            800.dp, 800.dp, expandedAnchor)
        try {
            state.beginDrag()
            state.dispatchRawDelta(200f)
            state.cancelDrag()
            assertEquals(expandedAnchor, state.targetAnchor)
            assertFalse(state.isDragging)
        } finally { state.dispose() }
    }

    @Test fun reversalAndMissingRenderLayersStillReachLiveEndpoints() = runBlocking {
        val clock = BroadcastFrameClock()
        val state = BottomSheetState(CoroutineScope(coroutineContext + clock), 2f, {}, 0.dp, 100.dp,
            800.dp, 100.dp, collapsedAnchor)
        var frame = 0L
        suspend fun frames(count: Int) {
            repeat(count) { yield(); frame += 16_666_667L; clock.sendFrame(frame); yield() }
        }
        try {
            state.expandSoft()
            frames(8)
            assertTrue(state.progress > 0f && state.progress < 1f)
            val before = state.value
            state.collapseSoft()
            assertEquals(before, state.value)
            frames(180)
            assertTrue(state.isCollapsed)
            assertEquals(0f, state.progress, 0f)
            state.expandSoft()
            frames(180)
            assertTrue(state.isExpanded)
            assertEquals(1f, state.progress, 0f)
        } finally { state.dispose() }
    }

    @Test fun grabDuringSpringStartsAtRenderedPositionAndReleaseUsesDirection() = runBlocking {
        val clock = BroadcastFrameClock()
        val state = BottomSheetState(CoroutineScope(coroutineContext + clock), 2f, {}, 0.dp, 100.dp,
            800.dp, 100.dp, collapsedAnchor)
        try {
            state.expandSoft()
            repeat(8) { yield(); clock.sendFrame(it * 16_666_667L); yield() }
            val before = state.value
            state.beginDrag()
            state.dispatchRawDelta(20f)
            assertEquals(before - 10.dp, state.value)
            state.performFling(-1200f, null)
            assertEquals(collapsedAnchor, state.targetAnchor)
        } finally { state.dispose() }
    }

    @Test fun clickTakesTransitionOwnershipBeforeTheFirstAnimationFrame() = runBlocking {
        val clock = BroadcastFrameClock()
        val state = BottomSheetState(CoroutineScope(coroutineContext + clock), 1f, {}, 0.dp, 100.dp,
            800.dp, 100.dp, collapsedAnchor)
        try {
            assertFalse(state.hasBeenShown)
            state.expandSoft()
            assertEquals(0f, state.progress, 0f)
            assertTrue(state.hasBeenShown)
            assertTrue(state.isTransitioning)
            assertFalse(state.isCollapsed)
            state.snapTo(800.dp)
            state.collapseSoft()
            assertEquals(1f, state.progress, 0f)
            assertTrue(state.isTransitioning)
            assertFalse(state.isExpanded)
        } finally { state.dispose() }
    }

    @Test fun cancelledOpeningRetainsTheHostButReleasesTransitionInput() = runBlocking {
        val clock = BroadcastFrameClock()
        val state = BottomSheetState(CoroutineScope(coroutineContext + clock), 1f, {}, 0.dp, 100.dp,
            800.dp, 100.dp, collapsedAnchor)
        try {
            state.expandSoft()
            state.collapseSoft()
            assertTrue(state.hasBeenShown)
            assertTrue(state.isCollapsed)
            assertFalse(state.isTransitioning)
            assertFalse(state.isDismissed)
        } finally { state.dispose() }
    }

    @Test fun movingCollapsedNavigationAnchorDoesNotOpenThePlayer() = runBlocking {
        val clock = BroadcastFrameClock()
        val state = BottomSheetState(CoroutineScope(coroutineContext + clock), 1f, {}, 0.dp, 150.dp,
            800.dp, 100.dp, collapsedAnchor, previouslyShown = true)
        try {
            state.relocate()
            assertTrue(state.isCollapsed)
            assertFalse(state.isTransitioning)
            assertEquals(0f, state.progress, 0f)
            repeat(180) { yield(); clock.sendFrame(it * 16_666_667L); yield() }
            assertEquals(150.dp, state.value)
            assertTrue(state.isCollapsed)
            assertTrue(state.hasBeenShown)
        } finally { state.dispose() }
    }


    @Test fun screenCornerInterpolationUsesTheWindowThenLetsTheSystemClipTheEndpoint() {
        assertEquals(24f, playerContainerCornerRadius(24f, 42f, 0f), 0f)
        assertEquals(33f, playerContainerCornerRadius(24f, 42f, 0.5f), 0f)
        assertEquals(12f, playerContainerCornerRadius(24f, 0f, 0.5f), 0f)
        assertEquals(0f, playerContainerCornerRadius(24f, 42f, 1f), 0f)
        assertEquals(0f, playerContainerCornerRadius(24f, 42f, 1.1f), 0f)
    }

}
