package com.ljyh.mei.ui.glass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationIndicatorLensTest {
    @Test
    fun departingTabFollowsThePanelBeforeTheRouteCommitsAndReanchorsOnReturn() {
        val panelScale = navigationPressScale(280f, 16f, 1f)
        val forward = (0..100).map { step -> navigationTabPanelScale(0, step / 100f, panelScale) }
        assertEquals(1f, forward.first(), 0f)
        assertEquals(panelScale, forward.last(), 0f)
        assertTrue(forward.zipWithNext().all { (before, after) -> after >= before })
        assertTrue(forward[50] > 1f && forward[50] < panelScale)
        val reverse = (100 downTo 0).map { step -> navigationTabPanelScale(0, step / 100f, panelScale) }
        assertEquals(forward, reverse.reversed())
        assertEquals(1f, navigationTabPanelScale(2, 2f, panelScale), 0f)
        assertEquals(panelScale, navigationTabPanelScale(0, 2f, panelScale), 0f)
    }

    @Test
    fun swappingBetweenRowAndSelectedOverlayPreservesCenterAndSizeDuringDrag() {
        listOf(1f, 2.75f, 3.5f).forEach { density ->
            val width = 280f * density
            val panelScale = navigationPressScale(width, 16f * density, 1f)
            listOf(41.5f, 140f, 238.5f).forEachIndexed { index, centerDp ->
                listOf(centerDp, 280f - centerDp).forEach { directionalCenter ->
                    val center = directionalCenter * density
                    listOf(0f, 0.2f, 0.5f, 1f, 1.8f, 2f).forEach { lensPosition ->
                        val tabScale = navigationTabPanelScale(index, lensPosition, panelScale)
                        val offset = navigationTabPressOffset(center, width / 2f, panelScale, tabScale)
                        val rowCenter = width / 2f + (center + offset - width / 2f) * panelScale
                        val overlayCenter = width / 2f + (center - width / 2f) * tabScale
                        assertEquals(overlayCenter, rowCenter, 0.001f)
                        listOf(1f, 1.2f).forEach { contentScale ->
                            val rowScale = contentScale * (tabScale / panelScale) * panelScale
                            assertEquals(contentScale * tabScale, rowScale, 0.0001f)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun incomingTabIsAnchoredIndependentlyOfThePreviouslySelectedKey() {
        val panelScale = 1.08f
        listOf(0, 1, 2).forEach { incoming ->
            assertEquals(1f, navigationTabPanelScale(incoming, incoming.toFloat(), panelScale), 0f)
            assertEquals(
                navigationTabPanelScale(incoming, incoming - 0.3f, panelScale),
                navigationTabPanelScale(incoming, incoming + 0.3f, panelScale), 0.0001f,
            )
        }
    }

    @Test
    fun hiddenMaterialUsesTheInnerHeightThroughoutTheCompactTransition() {
        listOf(1f, 2.75f, 3.5f).forEach { density ->
            listOf(0f, 0.3f, 0.74f, 1f).forEach { compact ->
                val height = navigationLensSourceHeight((64f - 16f * compact) * density, 4f * density)
                assertEquals((56f - 16f * compact) * density, height, 0.0001f)
            }
        }
    }

    @Test
    fun demoSamplingBandCrossesTheMaterialBoundaryOnAllFourSides() {
        listOf(1f, 2.75f, 3.5f).forEach { density ->
            val scale = 78f / 56f
            val height = NavigationIndicatorRefractionHeight.value * density
            val amount = NavigationIndicatorRefractionAmount.value * density
            val sourceHeight = navigationLensSourceHeight(64f * density, 4f * density)
            val firstCenter = 41.5f * density
            val lastCenter = 238.5f * density
            val insets = sampleInsets(height, amount).map { it * scale }
            val topSamples = insets.map { -39f * density + it }
            val bottomSamples = topSamples.map { -it }
            val leftSamples = insets.map { firstCenter - 75f * density * scale / 2f + it }
            val rightSamples = insets.map { lastCenter + 75f * density * scale / 2f - it }
            listOf(
                topSamples to -sourceHeight / 2f,
                bottomSamples to sourceHeight / 2f,
                leftSamples to 0f,
                rightSamples to 280f * density,
            ).forEach { (samples, boundary) ->
                assertTrue("The refractive band must include pixels outside the source", samples.min() < boundary - 0.5f * density)
                assertTrue("The refractive band must also include pixels inside the source", samples.max() > boundary + 0.5f * density)
            }
        }
    }

    @Test
    fun previousScaledFullHeightSourceKeepsItsTopEdgeOutsideTheSamplingBand() {
        val panelScale = navigationPressScale(280f, 16f, 1f)
        val oldSourceTop = -64f * panelScale / 2f
        val oldSamples = sampleInsets(14f, 22f).map { -39f + it * (78f / 56f) }
        assertTrue(oldSamples.min() > oldSourceTop + 8f)
    }

    /** Trace Backdrop's stock circular edge profile along a capsule's symmetry axes. */
    private fun sampleInsets(height: Float, amount: Float): List<Float> = (0..1000).map { step ->
        val depth = height * step / 1000f
        val x = 1f - depth / height
        depth + amount * (1f - kotlin.math.sqrt((1f - x * x).coerceAtLeast(0f)))
    }
}
