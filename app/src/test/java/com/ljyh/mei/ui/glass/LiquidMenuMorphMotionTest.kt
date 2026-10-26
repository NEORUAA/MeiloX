package com.ljyh.mei.ui.glass

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.junit.Test
import kotlin.math.abs
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.hypot
import kotlin.math.ceil

class LiquidMenuMorphMotionTest {
    private val source = Rect(676f, 183f, 996f, 321f)
    private val destination = Rect(242f, 185f, 996f, 687f)

    @Test
    fun parallelEnvelopesContractBeforeTheyFormTheMenu() {
        val motion = motion()
        motion.retarget(true)
        motion.advanceBy(0.05f)
        val early = motion.frame
        val lobe = requireNotNull(early.sourceLobe)
        assertTrue("The fused source contracts rather than detaching", lobe.width < source.width * 0.75f)
        assertTrue("The incoming menu starts as a rounded droplet", early.radiusX > early.bounds.width * 0.3f)
        assertTrue("The optical field joins both lobes", early.smoothing > 0f)
        assertTrue("The physical kick bends the path downward", early.bounds.center.y > source.center.y + 40f)
        motion.advanceBy(3f)
        assertRectEquals(destination, motion.frame.bounds)
        assertEquals(102f, motion.frame.radiusX, 0.001f)
        assertEquals(102f, motion.frame.radiusY, 0.001f)
        assertEquals(0f, motion.frame.smoothing, 0.001f)
        assertEquals(1f, motion.frame.contentProgress, 0.001f)
    }

    @Test
    fun retargetingPreservesTheLiveGeometryAndMomentum() {
        val motion = motion()
        motion.retarget(true)
        motion.advanceBy(0.08f)
        val before = motion.frame
        motion.retarget(false)
        assertEquals("Changing direction cannot replace the shape",
            before.copy(lensHeight = motion.frame.lensHeight, lensAmount = motion.frame.lensAmount), motion.frame)
        motion.advanceBy(0.004f)
        assertTrue("Momentum carries the existing eject through the reversal", motion.frame.bounds.width > before.bounds.width)
        val closing = motion.frame
        motion.retarget(true)
        assertEquals(closing.copy(lensHeight = motion.frame.lensHeight, lensAmount = motion.frame.lensAmount), motion.frame)
        motion.advanceBy(0.02f)
        assertTrue(motion.frame.bounds.width.isFinite())
        motion.retarget(false)
        motion.advanceBy(3f)
        assertRectEquals(source, requireNotNull(motion.frame.sourceLobe))
        assertEquals(0f, motion.frame.contentProgress, 0.001f)
    }

    @Test
    fun distanceFieldKeepsOneContinuousSkinThroughOpeningAndReversal() {
        val scenes = buildList {
            add(Triple(source, destination, source))
            listOf(4, 5).forEach { itemCount ->
                listOf(132f, 320f).forEach { sourceWidth ->
                    listOf(false, true).forEach { opensAbove ->
                        val top = if (opensAbove) 1900f else 185f
                        val source = Rect(1072f - sourceWidth, top, 1072f, top + 132f)
                        val height = (20f + 44f * itemCount) * 3f
                        val menuTop = if (opensAbove) source.bottom - height else source.top
                        val through = Rect(source.right - source.height, source.top, source.right, source.bottom)
                        add(Triple(source, Rect(358f, menuTop, 1072f, menuTop + height), through))
                    }
                }
            }
        }
        scenes.forEachIndexed { scene, (source, destination, through) ->
            val motion = LiquidMenuMorphMotion().apply {
                initialize(source, destination, 102f, 3f, through = through)
            }
            val workspace = LiquidMenuContourWorkspace()
            val initial = contour(motion.frame)
            listOf(
                Offset(source.left, source.center.y), Offset(source.right, source.center.y),
                Offset(source.center.x, source.top), Offset(source.center.x, source.bottom),
            ).forEach { edge ->
                assertEquals("Scene $scene starts at the original capsule", 0f, initial.distance(edge), 0.001f)
            }
            assertTrue(initial.distance(source.center) < 0f)
            assertTrue("Scene $scene keeps the capsule corner outside the skin", initial.distance(source.topLeft) > 0f)

            motion.retarget(true)
            repeat(720) { index ->
                // Cover the complete initial eject before testing a mid-close reversal.
                if (index == 144 || index == 288) motion.retarget(false)
                if (index == 168) motion.retarget(true)
                motion.advanceBy(1f / 240f)
                val frame = motion.frame
                val skin = contour(frame, workspace)
                val sourceCenter = requireNotNull(frame.sourceLobe).center
                repeat(65) { sample ->
                    val point = sourceCenter + (frame.bounds.center - sourceCenter) * (sample / 64f)
                    val distance = skin.distance(point)
                    assertTrue("Scene $scene has a finite field at frame $index", distance.isFinite())
                }
                // Gaussian smoothing can absorb the tiny raw source orb completely.
                // Test the physical negative region instead of requiring that erased orb's
                // center and its straight line to the menu remain inside the final skin.
                assertSingleSkin(skin, source.height, "Scene $scene, frame $index")
                listOf(frame.bounds.topLeft, frame.bounds.bottomRight, sourceCenter).forEach {
                    assertTrue("Scene $scene has a finite exterior at frame $index", skin.distance(it).isFinite())
                }
            }
        }
    }

    @Test
    fun absorptionRoundsTheMenuBeforeItReturnsToTheCapsule() {
        val motion = motion()
        motion.retarget(true)
        motion.advanceBy(3f)
        val openRoundness = motion.frame.radiusX / motion.frame.bounds.width
        motion.retarget(false)
        motion.advanceBy(0.1f)
        assertTrue("The closing surface becomes a droplet as its area shrinks",
            motion.frame.radiusX / motion.frame.bounds.width > openRoundness + 0.1f)
        motion.advanceBy(3f)
        assertRectEquals(source, requireNotNull(motion.frame.sourceLobe))
    }

    @Test
    fun nativeThroughPreviewRemainsIndependentOfTheFusedSource() {
        val through = Rect(source.right - source.height, source.top, source.right, source.bottom)
        val motion = LiquidMenuMorphMotion().apply {
            initialize(source, destination, 102f, 3f, through = through)
        }
        assertEquals(Offset(926f, 251f), motion.frame.bounds.center)
        assertEquals(destination.width * 0.5f * through.height / maxOf(destination.width, destination.height),
            motion.frame.bounds.width, 0.001f)
        assertRectEquals(source, requireNotNull(motion.frame.sourceLobe))
        motion.retarget(true)
        motion.advanceBy(3f)
        assertEquals(Offset(849f, 297f), requireNotNull(motion.frame.sourceLobe).center)
        assertEquals(source.size.minDimension * 0.25f, requireNotNull(motion.frame.sourceLobe).width, 0.001f)
        motion.retarget(false)
        motion.advanceBy(3f)
        assertEquals(source.center, motion.frame.bounds.center)
        assertRectEquals(source, requireNotNull(motion.frame.sourceLobe))
    }

    @Test
    fun nativeUIKitGeometryKeepsTheThroughPreviewAndLargestAxisScale() {
        val source = Rect(284.33334f, 62f, 386f, 106f)
        val through = Rect(344f, 66f, 382f, 102f)
        val destination = Rect(136f, 62f, 386f, 250f)
        val motion = LiquidMenuMorphMotion().apply {
            initialize(source, destination, 32f, 1f, through = through)
        }
        // Live ARM64 register capture at 0x1de40b6ac / b754: center (363,84), scale .072.
        assertEquals(Offset(363f, 84f), motion.frame.bounds.center)
        assertEquals(18f, motion.frame.bounds.width, 0.001f)
        motion.retarget(true)
        motion.advanceBy(0.3f)
        val springFrame = motion.frame
        val sourceScale = requireNotNull(springFrame.sourceLobe).height / source.height
        val ejectProgress = (1f - sourceScale) / 0.75f
        assertTrue("Native source transform retains the main eject spring's overshoot", sourceScale < 0.25f)
        assertEquals(84f + (102f - 84f) * ejectProgress,
            requireNotNull(springFrame.sourceLobe).center.y, 0.001f)
        assertTrue("The source's explicit bounds spring remains critically damped",
            requireNotNull(springFrame.sourceLobe).width / sourceScale >= source.height)
        motion.advanceBy(3f)
        assertEquals(Offset(338f, 102f), requireNotNull(motion.frame.sourceLobe).center)
        assertEquals(11f, requireNotNull(motion.frame.sourceLobe).height, 0.001f)
        motion.retarget(false)
        motion.advanceBy(3f)
        // The toolbar group coordinator's measured final model rect is (326.1667,75,18,18).
        assertRectEquals(Rect(326.1667f, 75f, 344.1667f, 93f), motion.frame.bounds, 0.001f)
    }

    @Test
    fun nativeUnionKeepsParallelEdgesAndBlendsFacingNormals() {
        val circle = Rect(0f, 0f, 100f, 100f)
        val radii = Offset(50f, 50f)
        listOf(0f, 20f, 80f).forEach { smoothness ->
            val identical = LiquidMenuContour(circle, radii, circle, radii, smoothness)
            assertEquals("Parallel unit normals do not inflate an already unified edge",
                0f, identical.rawDistance(Offset(100f, 50f)), 0.0001f)
            val tangent = LiquidMenuContour(circle, radii, circle.translate(Offset(100f, 0f)), radii, smoothness)
            assertEquals("CASDF's opposing-normal contact receives smoothness / 4 lift",
                -smoothness / 4f, tangent.rawDistance(Offset(100f, 50f)), 0.0001f)
            assertEquals("Outside the effective blend band, union is the nearer field",
                0f, tangent.rawDistance(Offset(0f, 50f)), 0.0001f)
        }
    }

    @Test
    fun nativeRoundedSamplerPreservesRawOverrounding() {
        val bounds = Rect(0f, 0f, 240f, 200f)
        val resting = LiquidMenuContour(bounds, Offset(34f, 34f), null, Offset.Zero, 0f)
        // Samples executed from QuartzCore's original ARM64 sampler, including its 1/127 quantization.
        assertEquals(0.48031496f, resting.distance(Offset(240f, 166f)), 0.02f)
        val overrounded = LiquidMenuContour(bounds, Offset(240f, 240f), null, Offset.Zero, 0f)
        assertEquals(37.8582677f, overrounded.distance(Offset(240f, 100f)), 0.06f)
        assertEquals(28.3307087f, overrounded.distance(Offset(120f, 200f)), 0.06f)
    }

    @Test
    fun nativeAbsorptionRestoresTheSourceWithTheMainAbsorbSpring() {
        val motion = motion()
        motion.retarget(true)
        motion.advanceBy(3f)
        motion.retarget(false)
        motion.advanceBy(0.05f)
        val source = requireNotNull(motion.frame.sourceLobe)
        // AnimationKit mainAbsorbSpring: damping .8, response .7, withSpeed(.7).
        // Its 50 ms solution is 0.14542210, shared by bounds and transform scale.
        val restored = 0.14542210f
        val expectedScale = 0.25f + 0.75f * restored
        assertEquals(138f * expectedScale, source.height, 0.002f)
        assertEquals((138f + (320f - 138f) * restored) * expectedScale, source.width, 0.002f)
        assertTrue("The native absorb turn enables the liquid union before releasing it", motion.frame.smoothing > 40f)
        assertTrue("Portal blur is a separate native track, not content opacity", motion.frame.portalBlur > 1f)
        assertEquals(restored, motion.frame.sourceOpacity, 0.0001f)
        assertEquals(1f - restored, motion.frame.contentProgress, 0.0001f)
        assertEquals(12f * (1f - restored), motion.frame.sourceBlur, 0.002f)
        assertEquals(12f * restored, motion.frame.contentBlur, 0.002f)
        assertEquals(motion.frame.sourceZoom, motion.frame.contentZoom, 0f)
        assertEquals(75f * 3f * (502f / (250f * 3f)) * motion.frame.lensProgress,
            motion.frame.lensHeight, 0.001f)
        assertEquals(-motion.frame.lensHeight, motion.frame.lensAmount, 0.001f)
        var recoiledBelowIdentity = false
        repeat(120) {
            motion.updateSource(this.source)
            motion.advanceBy(1f / 240f)
            if (motion.frame.contentZoom < 1f) recoiledBelowIdentity = true
            assertTrue("Blur radius remains valid during transform recoil", motion.frame.portalBlur >= 0f)
        }
        assertTrue("Native transform recoil is not clamped at identity", recoiledBelowIdentity)
        assertTrue("Geometry tracking does not restart the released lens", motion.frame.lensProgress < 0.02f)
    }

    @Test
    fun oscillatorAndTimelineAreIndependentOfFrameSubdivision() {
        val whole = motion()
        val split = motion()
        whole.retarget(true)
        split.retarget(true)
        whole.advanceBy(0.7f)
        repeat(70) { split.advanceBy(0.01f) }
        assertRectEquals(whole.frame.bounds, split.frame.bounds, 0.01f)
        assertEquals(whole.frame.radiusX, split.frame.radiusX, 0.01f)
        assertEquals(whole.frame.radiusY, split.frame.radiusY, 0.01f)
        assertEquals(whole.frame.smoothing, split.frame.smoothing, 0.01f)
        whole.retarget(false)
        split.retarget(false)
        whole.advanceBy(0.24f)
        repeat(24) { split.advanceBy(0.01f) }
        assertRectEquals(whole.frame.bounds, split.frame.bounds, 0.01f)
        assertRectEquals(requireNotNull(whole.frame.sourceLobe), requireNotNull(split.frame.sourceLobe), 0.01f)
        assertEquals(whole.frame.smoothing, split.frame.smoothing, 0.01f)
        assertEquals(whole.frame.contentBlur, split.frame.contentBlur, 0.01f)
        assertEquals(whole.frame.lensProgress, split.frame.lensProgress, 0.01f)
        val spring = LiquidMenuSpring()
        spring.reset(0f)
        spring.target(100f, LiquidMenuSpringSpec(0.75f, 0.5f))
        spring.advance(0.1f)
        val value = spring.value
        val velocity = spring.velocity
        spring.target(0f, LiquidMenuSpringSpec(0.9f, 0.3f))
        assertEquals(value, spring.value, 0f)
        assertEquals(velocity, spring.velocity, 0f)
        spring.advance(10f)
        assertTrue(abs(spring.value) < 0.001f)
        assertTrue(abs(spring.velocity) < 0.001f)
    }

    @Test
    fun cachedContourPreservesSubpixelRootsAcrossOvershootAndReversal() {
        var evaluations = 0L
        var sampledFrames = 0
        var maximumError = 0f
        listOf(
            source to destination,
            Rect(784f, 155f, 1072f, 287f) to Rect(358f, 155f, 1072f, 779f),
        ).forEach { (source, destination) ->
            val motion = motion(source, destination)
            val workspace = LiquidMenuContourWorkspace()
            motion.retarget(true)
            repeat(180) { index ->
                if (index == 72 || index == 120) motion.retarget(false)
                if (index == 84) motion.retarget(true)
                motion.advanceBy(1f / 120f)
                if (index % 3 != 0) return@repeat
                val frame = motion.frame
                val skin = contour(frame)
                val outgoing = requireNotNull(frame.sourceLobe)
                val size = Size(
                    maxOf(frame.bounds.right, outgoing.right) - minOf(frame.bounds.left, outgoing.left),
                    maxOf(frame.bounds.bottom, outgoing.bottom) - minOf(frame.bounds.top, outgoing.top),
                )
                val beforeEvaluations = workspace.totalFieldEvaluations
                val vertices = workspace.vertices(skin, size)
                evaluations += workspace.totalFieldEvaluations - beforeEvaluations
                sampledFrames++
                val unchangedEvaluations = workspace.totalFieldEvaluations
                assertSame("Stable geometry reuses the numeric contour", vertices, workspace.vertices(skin.copy(), size))
                assertEquals(unchangedEvaluations, workspace.totalFieldEvaluations)
                val sourceArea = outgoing.width * outgoing.height
                val incomingArea = frame.bounds.width * frame.bounds.height
                val center = (outgoing.center * sourceArea + frame.bounds.center * incomingArea) / (sourceArea + incomingArea)
                repeat(96) { ray ->
                    val angle = ray * (2.0 * PI / 96.0)
                    val direction = Offset(cos(angle).toFloat(), sin(angle).toFloat())
                    var low = 0f
                    var high = hypot(size.width, size.height)
                    repeat(25) {
                        val radius = (low + high) / 2f
                        if (skin.distance(center + direction * radius) <= 0f) low = radius else high = radius
                    }
                    val expected = center + direction * ((low + high) / 2f)
                    val actual = Offset(vertices[ray * 2], vertices[ray * 2 + 1])
                    val error = (actual - expected).getDistance()
                    maximumError = maxOf(maximumError, error)
                    assertTrue("A certified root stays subpixel at frame $index, ray $ray: $error", error <= 0.08f)
                }
            }
        }
        val previousEvaluations = sampledFrames.toLong() * 96 * 14
        assertTrue("Root certification uses fewer field evaluations than blind bisection",
            evaluations < previousEvaluations * 0.8)
        println("Liquid contour: $evaluations / $previousEvaluations field evaluations; max error $maximumError px")
    }

    private fun assertSingleSkin(skin: LiquidMenuContour, sourceHeight: Float, label: String) {
        val bounds = skin.exteriorBounds()
        val step = minOf(sourceHeight / 12f, maxOf(bounds.width, bounds.height) / 96f).coerceAtLeast(1f)
        val columns = ceil(bounds.width / step).toInt() + 1
        val rows = ceil(bounds.height / step).toInt() + 1
        val inside = BooleanArray(columns * rows)
        var seed = -1
        var count = 0
        repeat(rows) { row ->
            repeat(columns) { column ->
                val distance = skin.distance(bounds.left + column * step, bounds.top + row * step)
                assertTrue("$label has a finite physical field", distance.isFinite())
                if (distance < 0f) {
                    val index = row * columns + column
                    inside[index] = true
                    if (seed < 0) seed = index
                    count++
                }
            }
        }
        assertTrue("$label retains a visible skin", seed >= 0)
        val queue = IntArray(count)
        var head = 0
        var tail = 1
        queue[0] = seed
        inside[seed] = false
        while (head < tail) {
            val index = queue[head++]
            val row = index / columns
            val column = index % columns
            for (dy in -1..1) for (dx in -1..1) {
                val y = row + dy
                val x = column + dx
                if (x !in 0 until columns || y !in 0 until rows) continue
                val neighbor = y * columns + x
                if (inside[neighbor]) {
                    inside[neighbor] = false
                    queue[tail++] = neighbor
                }
            }
        }
        assertEquals("$label has one connected physical skin", count, tail)
    }

    private fun contour(frame: LiquidMenuMorphFrame, workspace: LiquidMenuContourWorkspace? = null) = LiquidMenuContour(
        frame.bounds, Offset(frame.radiusX, frame.radiusY), frame.sourceLobe,
        Offset(frame.sourceRadiusX, frame.sourceRadiusY), frame.smoothing, workspace,
    )

    private fun motion(
        source: Rect = this.source,
        destination: Rect = this.destination,
    ) = LiquidMenuMorphMotion().also {
        it.initialize(source, destination, targetRadius = 102f, density = 3f)
    }

    private fun assertRectEquals(expected: Rect, actual: Rect, tolerance: Float = 0.001f) {
        assertEquals(expected.left, actual.left, tolerance)
        assertEquals(expected.top, actual.top, tolerance)
        assertEquals(expected.right, actual.right, tolerance)
        assertEquals(expected.bottom, actual.bottom, tolerance)
    }
}
