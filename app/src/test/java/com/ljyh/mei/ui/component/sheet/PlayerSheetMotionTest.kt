package com.ljyh.mei.ui.component.sheet

import io.github.neoruaa.glasmio.scaffold.GlasmioBottomSheetState
import io.github.neoruaa.glasmio.scaffold.collapsedAnchor
import io.github.neoruaa.glasmio.scaffold.dismissedAnchor
import io.github.neoruaa.glasmio.scaffold.expandedAnchor

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerSheetMotionTest {
    private val mini = Rect(12f, 700f, 388f, 748f)
    private val page = Rect(0f, 0f, 400f, 800f)

    @Test fun landscapeContentKeepsItsFullSizeAndFollowsTheShellTopLeftThroughoutMotion() {
        val host = Rect(20f, 30f, 820f, 430f)
        val source = Rect(40f, 350f, 800f, 414f)
        for (closing in listOf(false, true)) {
            for (step in 0..100) {
                val frame = playerCollapseFrame(step / 100f, host.height, 336f, closing)
                val container = if (closing) playerClosingContainerRect(source, host, frame)
                    else playerContainerRect(source, host, frame.morphProgress)
                val content = playerFullContentRect(container, host)
                assertEquals(host.width, content.width, 0.001f)
                assertEquals(host.height, content.height, 0.001f)
                assertEquals(container.topLeft, content.topLeft)
                if (step == 100) assertEquals(host, content)
            }
        }
    }

    @Test fun portraitContentRetainsItsExistingUniformScaling() {
        for (step in 0..100) {
            val container = playerContainerRect(mini, page, step / 100f)
            val content = playerFullContentRect(container, page)
            assertEquals(container.topLeft, content.topLeft)
            assertEquals(container.width, content.width, 0.001f)
            assertEquals(page.height * container.width / page.width, content.height, 0.001f)
        }
    }

    @Test fun collapseKeepsTheWholePageRigidUntilOneThirdScreenInBothOrientations() {
        for (host in listOf(page, Rect(0f, 0f, 800f, 400f))) {
            val travel = host.height - 64f
            val threshold = 0.7f
            for (raw in listOf(1f, (1f + threshold) / 2f, threshold)) {
                val frame = playerCollapseFrame(raw, host.height, travel, closing = true)
                val bounds = playerContainerRect(mini, host, frame.morphProgress)
                    .translate(androidx.compose.ui.geometry.Offset(0f, frame.offsetY))
                assertEquals(1f, frame.morphProgress, 0.00001f)
                assertEquals(host.width, bounds.width, 0.001f)
                assertEquals(host.height, bounds.height, 0.001f)
                assertEquals((1f - raw) / 0.3f * host.height / 3f, bounds.top, 0.001f)
                assertEquals(1f, playerContentAlpha(frame.morphProgress), 0f)
                assertEquals(1f, playerBackgroundAlpha(frame.morphProgress), 0f)
                assertEquals(0f, playerMiniContentAlpha(frame.morphProgress), 0f)
                assertEquals(0f, playerContainerCornerRadius(24f, 0f, frame.morphProgress), 0.00001f)
            }
            val before = playerCollapseFrame(threshold + 0.000001f, host.height, travel, true)
            val after = playerCollapseFrame(threshold - 0.000001f, host.height, travel, true)
            assertEquals(before.offsetY, after.offsetY, 0.01f)
            assertEquals(before.morphProgress, after.morphProgress, 0.00001f)
            val morph = playerCollapseFrame(threshold / 2f, host.height, travel, true)
            assertEquals(0.5f, morph.morphProgress, 0.00001f)
            assertTrue(playerContentAlpha(morph.morphProgress) < 1f)
            val settled = playerCollapseFrame(0f, host.height, travel, true)
            assertEquals(mini, playerClosingContainerRect(mini, host, settled))
        }
    }

    @Test fun openingRetainsItsExistingGeometryAndCollapseCanRetraceAfterReversal() {
        val samples = (0..100).map { it / 100f }
        for (p in samples) {
            assertEquals(PlayerCollapseFrame(p, 0f), playerCollapseFrame(p, 800f, 736f, false))
        }
        val closing = samples.reversed().map { playerCollapseFrame(it, 800f, 736f, true) }
        val reversing = samples.map { playerCollapseFrame(it, 800f, 736f, true) }
        assertEquals(closing.reversed(), reversing)
    }

    @Test fun closingTopReboundsWithinTheSameProgressWhileOtherEdgesKeepTheirPath() {
        for (host in listOf(page, Rect(0f, 0f, 800f, 400f))) {
            val source = Rect(24f, host.height - 80f, host.width - 24f, host.height - 16f)
            val travel = host.height - 64f
            val bounds = (1000 downTo 0).map { step ->
                val frame = playerCollapseFrame(step / 1000f, host.height, travel, true)
                val linear = playerContainerRect(source, host, frame.morphProgress)
                    .translate(androidx.compose.ui.geometry.Offset(0f, frame.offsetY))
                playerClosingContainerRect(source, host, frame).also { actual ->
                    assertEquals(linear.left, actual.left, 0f)
                    assertEquals(linear.right, actual.right, 0f)
                    assertEquals(linear.bottom, actual.bottom, 0f)
                    assertTrue(actual.height > 0f)
                    if (frame.morphProgress == 1f) assertEquals(linear, actual)
                }
            }
            assertEquals(host, bounds.first())
            assertEquals(source, bounds.last())
            assertTrue(bounds.maxOf { it.top } > source.top)
            assertTrue(bounds.maxOf { it.top } < source.top + source.height / 3f)
            assertTrue(bounds[bounds.lastIndex - 1].top > bounds.last().top)
        }
    }

    @Test fun closingCornersAdoptScreenRadiusBeforeMorphingToTheMiniPlayer() {
        val travel = 736f
        val threshold = 0.7f
        for (p in listOf(1f, 0.9f, threshold)) {
            val frame = playerCollapseFrame(p, 800f, travel, true)
            assertEquals(36f, playerClosingCornerRadius(24f, 36f, frame.morphProgress), 0.00001f)
        }
        assertEquals(30f, playerClosingCornerRadius(24f, 36f, 0.5f), 0f)
        assertEquals(24f, playerClosingCornerRadius(24f, 36f, 0f), 0f)
    }

    @Test fun closingTopPreservesTranslationVelocityAtTheMorphHandoff() {
        for (host in listOf(page, Rect(0f, 0f, 800f, 400f))) {
            val source = Rect(24f, host.height - 80f, host.width - 24f, host.height - 16f)
            val travel = host.height - 64f
            val threshold = 0.7f
            fun topAt(raw: Float) = playerClosingContainerRect(
                source, host, playerCollapseFrame(raw, host.height, travel, true),
            ).top
            val step = 0.0001f
            val entry = (topAt(threshold) - topAt(threshold + step)) / step
            val exit = (topAt(threshold - step) - topAt(threshold)) / step
            assertEquals(host.height / (3f * 0.3f), entry, travel * 0.005f)
            assertEquals(entry, exit, travel * 0.005f)
        }
    }

    @Test fun closingTopTurnsSmoothlyDuringTheFinalProgressWhileTheOtherEdgesStillShrink() {
        for (host in listOf(page, Rect(0f, 0f, 800f, 400f))) {
            val source = Rect(24f, host.height - 80f, host.width - 24f, host.height - 16f)
            val travel = host.height - 64f
            fun boundsAt(raw: Float) = playerClosingContainerRect(
                source, host, playerCollapseFrame(raw, host.height, travel, true),
            )
            val turnaround = 0.2f
            val peak = boundsAt(turnaround)
            val returning = boundsAt(turnaround / 2f)
            assertEquals(0.8f, 1f - turnaround, 0.00001f)
            val overshoot = source.height / if (host == page) 4f else 8f
            assertEquals(source.top + overshoot, peak.top, 0.001f)
            assertTrue(returning.top < peak.top && returning.top > source.top)
            assertTrue(peak.left < returning.left && returning.left < source.left)
            assertTrue(peak.right > returning.right && returning.right > source.right)
            assertTrue(peak.bottom > returning.bottom && returning.bottom > source.bottom)
            val step = 0.0001f
            val entry = (peak.top - boundsAt(turnaround + step).top) / step
            val exit = (boundsAt(turnaround - step).top - peak.top) / step
            assertEquals(0f, entry, travel * 0.005f)
            assertEquals(0f, exit, travel * 0.005f)
        }
    }

    @Test fun collapseUsesThirtyFiftyTwentyProgressRegardlessOfOrientationAndAnchorTravel() {
        for (host in listOf(page, Rect(0f, 0f, 800f, 400f))) {
            val source = Rect(24f, host.height - 80f, host.width - 24f, host.height - 16f)
            for (travel in listOf(host.height - 64f, host.height - 128f)) {
                fun boundsAt(closing: Float) = playerClosingContainerRect(
                    source, host, playerCollapseFrame(1f - closing, host.height, travel, true),
                )
                val translated = boundsAt(0.3f)
                assertEquals(host.width, translated.width, 0.001f)
                assertEquals(host.height, translated.height, 0.001f)
                assertEquals(host.height / 3f, translated.top, 0.001f)
                val peak = boundsAt(0.8f)
                val overshoot = source.height / if (host == page) 4f else 8f
                assertEquals(source.top + overshoot, peak.top, 0.001f)
                assertTrue(peak.width > source.width)
                assertTrue(peak.bottom > source.bottom)
                val returning = boundsAt(0.9f)
                assertEquals(source.top + overshoot / 2f, returning.top, 0.001f)
                assertTrue(returning.width < peak.width && returning.width > source.width)
                assertEquals(source, boundsAt(1f))
            }
        }
    }

    private fun reversalFrame(p: Float, closing: Boolean): PlayerSheetMotionFrame {
        val frame = playerCollapseFrame(p, page.height, 736f, closing)
        return PlayerSheetMotionFrame(
            if (closing) playerClosingContainerRect(mini, page, frame)
            else playerContainerRect(mini, page, frame.morphProgress),
            frame.morphProgress,
        )
    }

    private fun assertMotionFrame(expected: PlayerSheetMotionFrame, actual: PlayerSheetMotionFrame, tolerance: Float) {
        assertEquals(expected.bounds.left, actual.bounds.left, tolerance)
        assertEquals(expected.bounds.top, actual.bounds.top, tolerance)
        assertEquals(expected.bounds.right, actual.bounds.right, tolerance)
        assertEquals(expected.bounds.bottom, actual.bounds.bottom, tolerance)
        assertEquals(expected.morphProgress, actual.morphProgress, tolerance)
    }

    @Test fun nearEndpointReversalUsesInclusiveFiveAndNinetyFivePercentThresholds() {
        for (p in listOf(0.949f, 0.95f, 0.951f)) {
            val path = PlayerSheetMotionPath(false)
            path.frame(p, ::reversalFrame)
            path.frame(p - 0.001f, ::reversalFrame)
            assertEquals(p >= 0.95f, path.closing)
        }
        for (p in listOf(0.049f, 0.05f, 0.051f)) {
            val path = PlayerSheetMotionPath(true)
            path.frame(p, ::reversalFrame)
            path.frame(p + 0.001f, ::reversalFrame)
            assertEquals(p > 0.05f, path.closing)
        }
    }

    @Test fun lateReversalPreservesPresentedGeometryOpacityAndProgressTangent() {
        for ((closing, start) in listOf(false to 0.97f, true to 0.03f)) {
            val path = PlayerSheetMotionPath(closing)
            val captured = path.frame(start, ::reversalFrame)
            val step = if (closing) 0.0001f else -0.0001f
            val next = path.frame(start + step, ::reversalFrame)
            assertEquals(!closing, path.closing)
            val atCapture = path.frame(start, ::reversalFrame)
            assertMotionFrame(captured, atCapture, 0.0001f)
            assertEquals(playerMiniContentAlpha(captured.morphProgress), playerMiniContentAlpha(atCapture.morphProgress), 0.00001f)
            assertEquals(playerContentAlpha(captured.morphProgress), playerContentAlpha(atCapture.morphProgress), 0.00001f)
            assertEquals(playerBackgroundAlpha(captured.morphProgress), playerBackgroundAlpha(atCapture.morphProgress), 0.00001f)
            val oldTangent = (reversalFrame(start + step, closing) - captured) * (1f / step)
            val newTangent = (next - captured) * (1f / step)
            assertMotionFrame(oldTangent, newTangent, 4f)
            assertEquals(oldTangent.morphProgress, newTangent.morphProgress, 0.01f)
            val endpoint = if (closing) 1f else 0f
            for (stepIndex in 1..1000) {
                val p = start + (endpoint - start) * (stepIndex / 1000f)
                val frame = path.frame(p, ::reversalFrame)
                assertTrue(frame.bounds.width > 0f && frame.bounds.height > 0f)
                assertTrue(frame.morphProgress in 0f..1f)
            }
            assertMotionFrame(reversalFrame(endpoint, !closing), path.frame(endpoint, ::reversalFrame), 0.0001f)
        }
    }

    @Test fun middleReversalRetracesTheOriginalPath() {
        for (closing in listOf(false, true)) {
            val path = PlayerSheetMotionPath(closing)
            val samples = if (closing) listOf(1f, 0.5f, 0.6f, 0.8f, 1f)
                else listOf(0f, 0.5f, 0.4f, 0.2f, 0f)
            for (p in samples) {
                assertMotionFrame(reversalFrame(p, closing), path.frame(p, ::reversalFrame), 0f)
                assertEquals(closing, path.closing)
            }
        }
    }

    @Test fun rebasedPathsRemainContinuousThroughRepeatedReversalsAndReachBothEndpoints() {
        val path = PlayerSheetMotionPath(false)
        for (p in listOf(0f, 0.98f, 0.979f, 0.5f, 0.04f, 0.041f, 0.5f, 0.97f, 0.969f)) {
            val frame = path.frame(p, ::reversalFrame)
            assertTrue(frame.bounds.width > 0f && frame.bounds.height > 0f)
            assertTrue(frame.morphProgress in 0f..1f)
        }
        assertTrue(path.closing)
        assertMotionFrame(reversalFrame(0f, true), path.frame(0f, ::reversalFrame), 0.0001f)
        for (step in 1..1000) {
            val frame = path.frame(step / 1000f, ::reversalFrame)
            assertTrue(frame.bounds.width > 0f && frame.bounds.height > 0f)
        }
        assertFalse(path.closing)
        assertMotionFrame(reversalFrame(1f, false), path.frame(1f, ::reversalFrame), 0.0001f)
        path.reset(true, 1f)
        assertMotionFrame(reversalFrame(0.5f, true), path.frame(0.5f, ::reversalFrame), 0f)
    }

    @Test fun retainedMaterialBytecodeDoesNotReferenceCompilerPlaceholderTypes() {
        for (name in listOf("PlayerSheetGlassMorphRenderer", "PlayerSheetGlassMorphRendererKt", "MorphNativeFilterScope")) {
            val resource = "com/ljyh/mei/ui/component/sheet/$name.class"
            val bytecode = checkNotNull(javaClass.classLoader?.getResourceAsStream(resource)) {
                "Missing compiled renderer resource: $resource"
            }.use { it.readBytes() }
            val classFile = bytecode.toString(Charsets.ISO_8859_1)
            assertFalse("$name must not contain an unresolved compiler type",
                classFile.contains("error/NonExistentClass"))
            assertFalse("$name must not erase material/filter objects to Void",
                classFile.contains("java/lang/Void"))
        }
    }

    @Test fun accessoryMorphStagesWidthBeforeCollapseAndHeightBeforeExpansion() {
        val collapse = miniPlayerChromeTransition(true, MiniPlayerChromeFrame(0f, 0f), 63.dp)
        assertEquals(MiniPlayerChromeFrame(1f, 0f), collapse.intermediate)
        assertEquals(MiniPlayerChromeSpring(0.72f, 0.36f), collapse.intermediateSpring)
        assertEquals(MiniPlayerChromeSpring(0.76f, 0.42f), collapse.finalSpring)
        assertEquals(50L, collapse.finalDelayMillis)

        val expand = miniPlayerChromeTransition(false, MiniPlayerChromeFrame(1f, 1f), 63.dp)
        assertEquals(MiniPlayerChromeFrame(1f, 0f), expand.intermediate)
        assertEquals(MiniPlayerChromeSpring(0.72f, 0.30f), expand.intermediateSpring)
        assertEquals(MiniPlayerChromeSpring(0.72f, 0.36f), expand.finalSpring)
        assertEquals(50L, expand.finalDelayMillis)
    }

    @Test fun shortAccessoryTravelUsesTheModelFrameAndIncludesTheFifteenPointBoundary() {
        for (compact in listOf(false, true)) {
            val model = if (compact) MiniPlayerChromeFrame(0f, 0f) else MiniPlayerChromeFrame(1f, 1f)
            val short = miniPlayerChromeTransition(compact, model, 15.dp)
            val target = if (compact) 1f else 0f
            assertEquals(MiniPlayerChromeFrame(target, target), short.intermediate)
            assertEquals(MiniPlayerChromeSpring(1f, 0.3f), short.intermediateSpring)
            assertEquals(0L, short.finalDelayMillis)
            assertEquals(50L, miniPlayerChromeTransition(compact, model, 15.01.dp).finalDelayMillis)
        }
    }

    @Test fun accessoryStagesOverlapAndInterruptedReturnSettlesItsHiddenEndpoint() = runTest {
        val clock = BroadcastFrameClock()
        val motion = MiniPlayerChromeMotion(compact = false)
        var frame = 0L
        suspend fun frames(count: Int) {
            repeat(count) {
                advanceTimeBy(16)
                frame += 16_000_000L
                clock.sendFrame(frame)
                runCurrent()
            }
        }
        val collapse = launch(clock) { motion.update(true, playerVisible = false, centerTravel = 63.dp) }
        runCurrent()
        frames(2)
        assertTrue(motion.horizontalProgress.value > 0f)
        assertEquals(0f, motion.verticalProgress.value, 0f)
        assertTrue(motion.contentProgress.value > 0f)
        val beforeReverse = listOf(motion.horizontalProgress.value, motion.verticalProgress.value, motion.contentProgress.value)
        collapse.cancelAndJoin()
        val expand = launch(clock) { motion.update(false, playerVisible = false, centerTravel = 63.dp) }
        runCurrent()
        assertEquals(beforeReverse, listOf(motion.horizontalProgress.value, motion.verticalProgress.value, motion.contentProgress.value))
        frames(2)
        // The final model was committed before its delayed vertical presentation.
        // Expansion therefore retains the compact width in its first stage.
        assertTrue(motion.horizontalProgress.value > beforeReverse[0])
        assertEquals(0f, motion.verticalProgress.value, 0f)
        frames(180)
        expand.join()
        assertEquals(0f, motion.horizontalProgress.value, 0f)
        assertEquals(0f, motion.verticalProgress.value, 0f)
        assertEquals(0f, motion.contentProgress.value, 0f)

        val interrupted = launch(clock) { motion.update(true, playerVisible = false, centerTravel = 63.dp) }
        runCurrent()
        frames(6)
        assertTrue(motion.horizontalProgress.value < 1f)
        assertTrue(motion.verticalProgress.value > 0f)
        interrupted.cancelAndJoin()
        motion.update(true, playerVisible = true, centerTravel = 63.dp)
        assertEquals(1f, motion.horizontalProgress.value, 0f)
        assertEquals(1f, motion.verticalProgress.value, 0f)
        assertEquals(1f, motion.contentProgress.value, 0f)
    }

    @Test fun lyricHeaderArtworkKeepsExactEndpointsAndShrinksItsTopInsetOnCollapse() {
        val source = Rect(20f, 708f, 52f, 740f)
        val header = Rect(24f, 76f, 70f, 122f)
        assertEquals(source, playerContainerAnchoredArtworkRect(source, header, mini, page, 0f))
        assertEquals(header, playerContainerAnchoredArtworkRect(source, header, mini, page, 1f))
        var previous = Float.POSITIVE_INFINITY
        for (step in 100 downTo 0) {
            val progress = step / 100f
            val artwork = playerContainerAnchoredArtworkRect(source, header, mini, page, progress)
            val container = playerContainerRect(mini, page, progress)
            val inset = artwork.top - container.top
            assertTrue(inset <= previous + 0.001f)
            assertTrue(inset >= 8f - 0.001f && inset <= 76f + 0.001f)
            previous = inset
        }
    }

    @Test fun lyricHeaderTopInsetReversesWithoutASecondTrajectory() {
        val source = Rect(20f, 708f, 52f, 740f)
        val header = Rect(24f, 76f, 70f, 122f)
        val opening = (0..100).map { playerContainerAnchoredArtworkRect(source, header, mini, page, it / 100f) }
        val closing = (100 downTo 0).map { playerContainerAnchoredArtworkRect(source, header, mini, page, it / 100f) }
        assertEquals(opening, closing.reversed())
    }

    @Test fun containerAnchoredArtworkKeepsBothInsetsAndSizeThroughPortraitAndLandscapeReversals() {
        val source = Rect(40f, 708f, 72f, 740f)
        val portrait = Rect(16f, 24f, 416f, 824f)
        val landscape = Rect(32f, 16f, 832f, 416f)
        val targets = listOf(
            portrait to Rect(56f, 124f, 376f, 444f),
            portrait to Rect(40f, 100f, 86f, 146f),
            landscape to Rect(100f, 60f, 400f, 360f),
        )
        for ((host, target) in targets) {
            assertEquals(source, playerContainerAnchoredArtworkRect(source, target, mini, host, 0f))
            assertEquals(target, playerContainerAnchoredArtworkRect(source, target, mini, host, 1f))
            val opening = (0..100).map { step ->
                val progress = step / 100f
                val artwork = playerContainerAnchoredArtworkRect(source, target, mini, host, progress)
                val container = playerContainerRect(mini, host, progress)
                val expectedLeftInset = (source.left - mini.left) * (1f - progress) +
                    (target.left - host.left) * progress
                val expectedTopInset = (source.top - mini.top) * (1f - progress) +
                    (target.top - host.top) * progress
                assertEquals(expectedLeftInset, artwork.left - container.left, 0.001f)
                assertEquals(expectedTopInset, artwork.top - container.top, 0.001f)
                assertEquals(source.width * (1f - progress) + target.width * progress, artwork.width, 0.001f)
                assertEquals(source.height * (1f - progress) + target.height * progress, artwork.height, 0.001f)
                artwork
            }
            val closing = (100 downTo 0).map {
                playerContainerAnchoredArtworkRect(source, target, mini, host, it / 100f)
            }
            assertEquals(opening, closing.reversed())
        }
    }

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

    @Test fun pressLightFadesAtTheNativeBackgroundHandoff() {
        assertEquals(1f, playerPressHighlightAlpha(0f), 0f)
        assertTrue(playerPressHighlightAlpha(0.09f) > 0f)
        assertTrue(playerPressHighlightAlpha(0.1f) > 0f)
        assertTrue(playerPressHighlightAlpha(0.8f) > 0f)
        assertEquals(0f, playerPressHighlightAlpha(PlayerBackgroundHandoffEnd), 0f)
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

    @Test fun landscapeMiniContentKeepsLeadingAndTopInsetsThroughReversal() {
        val container = Rect(250f, 700f, 950f, 764f)
        val content = Rect(262f, 700f, 930f, 764f)
        val host = Rect(17f, 23f, 1217f, 823f)
        val leadingInset = content.left - container.left
        val progress = listOf(0f, .2f, .5f, .8f, 1f, .8f, .5f, .2f, 0f)
        val origins = progress.map { p ->
            val moving = playerContainerRect(container, host, p)
            val origin = playerMiniContentOrigin(content, container, moving)
            val frame = Rect(origin, content.size)
            assertEquals(content.size, frame.size)
            assertEquals(leadingInset, frame.left - moving.left, .001f)
            assertEquals(content.top - container.top, frame.top - moving.top, .001f)
            origin
        }
        assertEquals(content.topLeft, origins.first())
        assertEquals(origins.take(4), origins.takeLast(4).reversed())
    }

    @Test fun portraitMiniContentRetainsItsMeasuredTopAndLeadingInset() {
        val content = Rect(20f, 700f, 380f, 748f)
        listOf(0f, .25f, .5f, .75f, 1f).forEach { p ->
            val moving = playerContainerRect(mini, page, p)
            val origin = playerMiniContentOrigin(content, mini, moving)
            assertEquals(content.left - mini.left, origin.x - moving.left, .001f)
            assertEquals(content.top - mini.top, origin.y - moving.top, .001f)
        }
    }

    @Test fun landscapeTrailingActionsAnchorRightOnOpenAndLeftOnClose() {
        val mini = Rect(250f, 700f, 950f, 764f)
        val actions = Rect(786f, 708f, 930f, 756f)
        val host = Rect(17f, 23f, 1217f, 823f)
        for (progress in listOf(0f, .25f, .5f, .75f, 1f)) {
            val moving = playerContainerRect(mini, host, progress)
            val opening = playerMiniTrailingOrigin(actions, mini, moving, rightAnchored = true)
            val closing = playerMiniTrailingOrigin(actions, mini, moving, rightAnchored = false)
            assertEquals(mini.right - actions.right, moving.right - (opening.x + actions.width), .001f)
            assertEquals(actions.left - mini.left, closing.x - moving.left, .001f)
            assertEquals(actions.top - mini.top, opening.y - moving.top, .001f)
            assertEquals(actions.topLeft, playerMiniTrailingOrigin(actions, mini, mini, true))
            assertEquals(actions.topLeft, playerMiniTrailingOrigin(actions, mini, mini, false))
        }
    }

    @Test fun landscapeTrailingAnchorKeepsItsStartingSideAcrossDragReversals() {
        val anchor = PlayerMiniTrailingAnchor()
        for (progress in listOf(0f, .3f, .8f, .4f, 1f)) {
            assertTrue(anchor.anchorsRight(progress))
        }
        anchor.reset()
        for (progress in listOf(1f, .7f, .2f, .6f, 0f)) {
            assertFalse(anchor.anchorsRight(progress))
        }
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
        val state = GlasmioBottomSheetState(CoroutineScope(coroutineContext + clock), 2f, {}, 0.dp, 100.dp,
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
        val state = GlasmioBottomSheetState(CoroutineScope(coroutineContext + clock), 1f, {}, 0.dp, 100.dp,
            800.dp, 800.dp, expandedAnchor)
        try {
            state.beginDrag()
            state.dispatchRawDelta(200f)
            state.cancelDrag()
            assertEquals(expandedAnchor, state.targetAnchor)
            assertFalse(state.isDragging)
        } finally { state.dispose() }
    }

    @Test fun contentVisibilityDoesNotRestartAtExpandedDragBoundaries() = runBlocking {
        val clock = BroadcastFrameClock()
        val state = GlasmioBottomSheetState(CoroutineScope(coroutineContext + clock), 1f, {}, 0.dp, 100.dp,
            800.dp, 800.dp, expandedAnchor)
        try {
            assertTrue(state.isContentVisible)
            state.beginDrag()
            assertFalse(state.isExpanded)
            assertTrue(state.isContentVisible)
            state.dispatchRawDelta(200f)
            assertTrue(state.isContentVisible)
            state.dispatchRawDelta(-200f)
            assertTrue(state.isContentVisible)
            state.cancelDrag()
            assertTrue(state.isExpanded)
            assertTrue(state.isContentVisible)
        } finally { state.dispose() }
    }

    @Test fun contentUpdatesStartOnOpeningAndStopOnlyAfterCollapseSettles() = runBlocking {
        val clock = BroadcastFrameClock()
        val state = GlasmioBottomSheetState(CoroutineScope(coroutineContext + clock), 1f, {}, 0.dp, 100.dp,
            800.dp, 100.dp, collapsedAnchor)
        try {
            assertFalse(state.isContentVisible)
            state.beginDrag()
            assertTrue(state.isContentVisible)
            state.dispatchRawDelta(-200f)
            state.cancelDrag()
            assertTrue(state.isContentVisible)
            repeat(180) { yield(); clock.sendFrame(it * 16_666_667L); yield() }
            assertTrue(state.isCollapsed)
            assertFalse(state.isContentVisible)
        } finally { state.dispose() }
    }

    @Test fun reversalAndMissingRenderLayersStillReachLiveEndpoints() = runBlocking {
        val clock = BroadcastFrameClock()
        val state = GlasmioBottomSheetState(CoroutineScope(coroutineContext + clock), 2f, {}, 0.dp, 100.dp,
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
        val state = GlasmioBottomSheetState(CoroutineScope(coroutineContext + clock), 2f, {}, 0.dp, 100.dp,
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
        val state = GlasmioBottomSheetState(CoroutineScope(coroutineContext + clock), 1f, {}, 0.dp, 100.dp,
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
        val state = GlasmioBottomSheetState(CoroutineScope(coroutineContext + clock), 1f, {}, 0.dp, 100.dp,
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

    @Test fun restoredPlaybackReversesDismissalBeforeOrAfterItFinishes() = runBlocking {
        for (restoreAfterFrames in listOf(0, 8, 180)) {
            val clock = BroadcastFrameClock()
            val state = GlasmioBottomSheetState(CoroutineScope(coroutineContext + clock), 1f, {}, 0.dp, 100.dp,
                800.dp, 100.dp, collapsedAnchor)
            var frame = 0L
            suspend fun frames(count: Int) {
                repeat(count) { yield(); frame += 16_666_667L; clock.sendFrame(frame); yield() }
            }
            try {
                state.syncPlaybackVisibility(false)
                frames(restoreAfterFrames)
                assertEquals(dismissedAnchor, state.targetAnchor)
                assertEquals(restoreAfterFrames == 180, state.isDismissed)
                val beforeRestore = state.value
                state.syncPlaybackVisibility(true)
                assertEquals(collapsedAnchor, state.targetAnchor)
                assertEquals(beforeRestore, state.value)
                frames(180)
                assertTrue(state.isCollapsed)
                assertFalse(state.isDismissed)
                assertEquals(100.dp, state.value)
            } finally { state.dispose() }
        }
    }

    @Test fun playbackVisibilityUpdatesPreserveExpansionAndUserDragging() = runBlocking {
        val clock = BroadcastFrameClock()
        val anchors = mutableListOf<Int>()
        val state = GlasmioBottomSheetState(CoroutineScope(coroutineContext + clock), 1f, { anchors += it },
            0.dp, 100.dp, 800.dp, 100.dp, collapsedAnchor)
        var frame = 0L
        suspend fun frames(count: Int) {
            repeat(count) { yield(); frame += 16_666_667L; clock.sendFrame(frame); yield() }
        }
        try {
            state.expandSoft()
            frames(8)
            assertTrue(state.progress > 0f && state.progress < 1f)
            val expandingValue = state.value
            repeat(3) { state.syncPlaybackVisibility(true) }
            assertEquals(expandingValue, state.value)
            assertEquals(listOf(expandedAnchor), anchors)
            frames(180)
            assertTrue(state.isExpanded)
            repeat(3) { state.syncPlaybackVisibility(true) }
            assertTrue(state.isExpanded)
            assertEquals(listOf(expandedAnchor), anchors)

            state.beginDrag()
            state.dispatchRawDelta(200f)
            val draggingValue = state.value
            repeat(3) { state.syncPlaybackVisibility(true) }
            assertTrue(state.isDragging)
            assertEquals(draggingValue, state.value)
            assertEquals(listOf(expandedAnchor), anchors)
            state.cancelDrag()
            frames(180)
            assertTrue(state.isExpanded)
        } finally { state.dispose() }
    }

    @Test fun clearingPlaybackWhileThePlayerOpensFinishesDismissal() = runBlocking {
        for (initialAnchor in listOf(dismissedAnchor, collapsedAnchor)) {
            for (clearAfterFrames in listOf(0, 8)) {
                val clock = BroadcastFrameClock()
                val initialValue = if (initialAnchor == dismissedAnchor) 0.dp else 100.dp
                val state = GlasmioBottomSheetState(CoroutineScope(coroutineContext + clock), 1f, {},
                    0.dp, 100.dp, 800.dp, initialValue, initialAnchor)
                var frame = 0L
                suspend fun frames(count: Int) {
                    repeat(count) { yield(); frame += 16_666_667L; clock.sendFrame(frame); yield() }
                }
                try {
                    if (initialAnchor == dismissedAnchor) state.syncPlaybackVisibility(true)
                    else state.expandSoft()
                    frames(clearAfterFrames)
                    assertFalse(state.isDismissed)
                    state.syncPlaybackVisibility(false)
                    assertEquals(dismissedAnchor, state.targetAnchor)
                    frames(180)
                    assertTrue(state.isDismissed)
                    assertEquals(0.dp, state.value)
                } finally { state.dispose() }
            }
        }
    }

    @Test fun changingNavigationAnchorDuringCollapseKeepsTheFullPlayerTransition() = runBlocking {
        for (framesBeforeNavigation in listOf(0, 8)) {
            val clock = BroadcastFrameClock()
            val scope = CoroutineScope(coroutineContext + clock)
            val previous = GlasmioBottomSheetState(scope, 1f, {}, 0.dp, 150.dp,
                800.dp, 800.dp, expandedAnchor)
            var replacement: GlasmioBottomSheetState? = null
            var frame = 0L
            suspend fun frames(count: Int) {
                repeat(count) { yield(); frame += 16_666_667L; clock.sendFrame(frame); yield() }
            }
            try {
                previous.collapseSoft()
                frames(framesBeforeNavigation)
                val current = GlasmioBottomSheetState(scope, 1f, {}, 0.dp, 100.dp,
                    800.dp, previous.value, previous.targetAnchor,
                    previouslyShown = previous.hasBeenShown,
                    previouslyCollapsed = previous.isCollapsed)
                replacement = current
                previous.dispose()
                assertFalse(current.isCollapsed)
                assertTrue(current.isTransitioning)
                assertTrue(current.isContentVisible)
                assertTrue(current.progress > 0f)
                assertEquals(previous.value, current.value)
                current.relocate()
                assertTrue(current.isTransitioning)
                frames(180)
                assertEquals(100.dp, current.value)
                assertTrue(current.isCollapsed)
                assertFalse(current.isTransitioning)
            } finally {
                previous.dispose()
                replacement?.dispose()
            }
        }
    }

    @Test fun movingCollapsedNavigationAnchorDoesNotOpenThePlayer() = runBlocking {
        val clock = BroadcastFrameClock()
        val state = GlasmioBottomSheetState(CoroutineScope(coroutineContext + clock), 1f, {}, 0.dp, 150.dp,
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
