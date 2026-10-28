package com.ljyh.mei.ui.glass

import io.github.neoruaa.glasmio.core.GlassAppearance
import io.github.neoruaa.glasmio.core.LocalGlassAppearance
import io.github.neoruaa.glasmio.core.LocalGlassColors
import io.github.neoruaa.glasmio.core.defaultGlassColors
import io.github.neoruaa.glasmio.glass.BottomContactLensTransform
import io.github.neoruaa.glasmio.glass.BottomControlContact
import io.github.neoruaa.glasmio.glass.BottomNavigationContact
import io.github.neoruaa.glasmio.glass.GlassPressHighlight
import io.github.neoruaa.glasmio.glass.GlassSurface
import io.github.neoruaa.glasmio.glass.GlassSurfaceStyle
import io.github.neoruaa.glasmio.glass.GraphicsLayerBlockScope
import io.github.neoruaa.glasmio.glass.LiquidContactCapsule
import io.github.neoruaa.glasmio.glass.LiquidContactShape
import io.github.neoruaa.glasmio.glass.LiquidMenuContour
import io.github.neoruaa.glasmio.glass.LiquidMenuContourWorkspace
import io.github.neoruaa.glasmio.glass.LiquidMenuMorphMotion
import io.github.neoruaa.glasmio.glass.LocalBottomControlContact
import io.github.neoruaa.glasmio.glass.NavigationDropShadowRenderer
import io.github.neoruaa.glasmio.glass.NavigationInnerShadowRenderer
import io.github.neoruaa.glasmio.glass.clipGlassShape
import io.github.neoruaa.glasmio.glass.drawNavigationGlassMaterial
import io.github.neoruaa.glasmio.glass.expandedLiquidMenuShadowHole
import io.github.neoruaa.glasmio.glass.liquidContactShape
import io.github.neoruaa.glasmio.glass.navigationContactLens
import io.github.neoruaa.glasmio.glass.navigationGlassBackground
import io.github.neoruaa.glasmio.glass.navigationLensSourceDropShadow
import io.github.neoruaa.glasmio.glass.navigationShadowLiftEffect
import io.github.neoruaa.glasmio.glass.rememberBottomControlContactMember
import io.github.neoruaa.glasmio.glass.rememberGlassMorphRenderer
import io.github.neoruaa.glasmio.glass.rememberMorphingNavigationGlass
import io.github.neoruaa.glasmio.glass.rememberNavigationInnerShadows
import io.github.neoruaa.glasmio.icons.SfSymbol
import io.github.neoruaa.glasmio.scaffold.GlasmioPinnedListPage
import io.github.neoruaa.glasmio.ui.GlasmioNavigationBar
import io.github.neoruaa.glasmio.ui.GlasmioTabItem

import android.graphics.RenderNode
import android.graphics.Region
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.State
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.util.lerp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kyant.backdrop.backdrops.emptyBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCanvasBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.capsule.ContinuousRoundedRectangle
import com.kyant.shapes.Capsule
import com.ljyh.mei.ui.component.sheet.BottomSheet
import com.ljyh.mei.AnimatedHomeSearchButton
import io.github.neoruaa.glasmio.scaffold.GlasmioBottomSheetState
import com.ljyh.mei.ui.component.sheet.LocalPlayerSheet
import com.ljyh.mei.ui.component.sheet.PlayerArtworkGeometry
import com.ljyh.mei.ui.component.sheet.PlayerSheetLayers
import io.github.neoruaa.glasmio.scaffold.collapsedAnchor
import io.github.neoruaa.glasmio.scaffold.expandedAnchor
import io.github.neoruaa.glasmio.scaffold.rememberGlasmioBottomSheetState
import com.ljyh.mei.ui.component.sheet.rememberMiniPlayerCompactProgress
import com.ljyh.mei.ui.component.sheet.rememberPlayerSheetLayers
import com.ljyh.mei.ui.component.sheet.playerContainerRect
import io.github.neoruaa.glasmio.glass.InteractiveHighlight
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GlassBottomBarInteractionTest {
    @get:Rule val compose = createComposeRule()

    private val home = GlasmioTabItem("home", "Home", SfSymbol.House)
    private val radio = GlasmioTabItem("radio", "Radio", SfSymbol.RadioFilled)
    private val library = GlasmioTabItem("library", "Library", SfSymbol.MusicNoteList)
    private val items = mutableStateOf(listOf(home, radio, library))
    private val selectedKey = mutableStateOf(home.key)
    private val compactProgress = mutableFloatStateOf(0f)
    private val glassAppearance = mutableStateOf(GlassAppearance(reduceTransparency = true))
    private val barLayoutDirection = mutableStateOf(LayoutDirection.Ltr)
    private val selections = mutableListOf<String>()
    private var expansions = 0

    @Test
    fun searchInsetsUpdateScrollPaddingWithoutRebuildingListContent() {
        val bottomInset = mutableFloatStateOf(20f)
        val insets = object : PaddingValues {
            override fun calculateLeftPadding(layoutDirection: LayoutDirection) = 0.dp
            override fun calculateTopPadding() = 0.dp
            override fun calculateRightPadding(layoutDirection: LayoutDirection) = 0.dp
            override fun calculateBottomPadding() = bottomInset.floatValue.dp
        }
        lateinit var listState: LazyListState
        var contentBuilds = 0
        var density = 1f
        compose.setContent {
            listState = rememberLazyListState()
            density = LocalDensity.current.density
            MaterialTheme {
                CompositionLocalProvider(
                    LocalGlassAppearance provides GlassAppearance(reduceTransparency = true),
                    LocalGlassColors provides defaultGlassColors(isDark = false),
                ) {
                    GlasmioPinnedListPage(
                        title = "Search",
                        bottomPadding = 0.dp,
                        bottomPaddingValues = insets,
                        modifier = Modifier.size(300.dp, 400.dp),
                        listState = listState,
                        showsLargeTitle = false,
                    ) {
                        contentBuilds++
                        items(20) { Box(Modifier.size(100.dp, 40.dp)) }
                    }
                }
            }
        }
        compose.waitForIdle()
        var initialBuilds = 0
        var initialPadding = 0
        compose.runOnIdle {
            initialBuilds = contentBuilds
            initialPadding = listState.layoutInfo.afterContentPadding
            bottomInset.floatValue = 160f
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals("IME padding must remain a measurement read", initialBuilds, contentBuilds)
            assertEquals("The original scrollable bottom reservation is preserved",
                initialPadding + (140f * density).roundToInt(), listState.layoutInfo.afterContentPadding)
        }
    }

    @Test
    fun homeSearchMorphKeepsExactLiveHitAndSymbolSizes() {
        val progress = mutableFloatStateOf(0f)
        val description = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            .targetContext.getString(com.ljyh.mei.R.string.app_tab_search)
        var density = 1f
        var clicks = 0
        compose.setContent {
            density = LocalDensity.current.density
            val backdrop = rememberCanvasBackdrop { drawRect(Color.White) }
            MaterialTheme {
                CompositionLocalProvider(
                    LocalGlassAppearance provides GlassAppearance(reduceTransparency = true),
                    LocalGlassColors provides defaultGlassColors(isDark = false),
                ) {
                    Box(Modifier.size(80.dp), contentAlignment = Alignment.Center) {
                        AnimatedHomeSearchButton(progress, backdrop) { clicks++ }
                    }
                }
            }
        }
        listOf(0f to (60f to 26f), 0.5f to (52f to 24f), 1f to (44f to 22f),
            0.5f to (52f to 24f), 0f to (60f to 26f)).forEach { (value, sizes) ->
            compose.runOnIdle { progress.floatValue = value }
            compose.waitForIdle()
            val hitBounds = compose.onNodeWithContentDescription(description).fetchSemanticsNode().boundsInRoot
            val iconBounds = compose.onNodeWithContentDescription(description, useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot
            assertEquals("The live search surface keeps its specified width at $value", sizes.first * density, hitBounds.width, 1f)
            assertEquals("The live search surface keeps its specified height at $value", sizes.first * density, hitBounds.height, 1f)
            assertEquals("SF Symbols keeps its live layout width at $value", sizes.second * density, iconBounds.width, 1f)
            assertEquals("SF Symbols keeps its live layout height at $value", sizes.second * density, iconBounds.height, 1f)
            assertEquals("The search glyph stays centered during the morph", hitBounds.center.x, iconBounds.center.x, 1f)
            assertEquals("The search glyph stays centered during the morph", hitBounds.center.y, iconBounds.center.y, 1f)
        }
        compose.onNodeWithContentDescription(description).performClick()
        compose.runOnIdle { assertEquals("The live search action remains clickable", 1, clicks) }
    }

    @Test
    fun materialOwnerRebindingRetainsContactGeometryAndUsesLiveCallbacks() {
        val owner = mutableStateOf(0)
        val inset = mutableFloatStateOf(0f)
        val sourceScale = mutableStateOf(Offset(0.94f, 0.86f))
        val surfaceWidth = mutableFloatStateOf(90f)
        val boundsCalls = IntArray(2)
        val highlightCalls = IntArray(2)
        val visualCalls = IntArray(2)
        val visualBounds = arrayOfNulls<Rect>(2)
        var contact: BottomControlContact? = null
        var density = 1f
        compose.setContent {
            val currentOwner = owner.value
            density = LocalDensity.current.density
            val surfaceShape = remember { Capsule() }
            val backdrop = rememberCanvasBackdrop { drawRect(Color.Gray) }
            CompositionLocalProvider(
                LocalGlassAppearance provides GlassAppearance(reduceTransparency = true),
                LocalGlassColors provides defaultGlassColors(isDark = false),
            ) {
                Column {
                    BottomNavigationContact(backdrop, Modifier.size(240.dp, 56.dp)) {
                        contact = LocalBottomControlContact.current
                        val first = rememberBottomControlContactMember(
                            visualBounds = { size ->
                                boundsCalls[currentOwner]++
                                Rect(inset.floatValue, 0f, size.width, size.height)
                            },
                            materialPressProgress = { 0f },
                            highlight = { GlassPressHighlight() },
                            lensSourceActive = { true },
                            lensSourceScale = { sourceScale.value },
                        )
                        Box(Modifier.size(160.dp, 56.dp).onGloballyPositioned { first?.updateCoordinates(it) })
                        Spacer(Modifier.width(8.dp))
                        val second = rememberBottomControlContactMember(
                            visualBounds = { Rect(Offset.Zero, it) },
                            materialPressProgress = { 0f },
                            highlight = { GlassPressHighlight() },
                        )
                        Box(Modifier.size(56.dp).onGloballyPositioned { second?.updateCoordinates(it) })
                    }
                    GlassSurface(
                        modifier = Modifier.size(surfaceWidth.floatValue.dp, 44.dp),
                        backdrop = backdrop,
                        shape = surfaceShape,
                        pressHighlight = {
                            highlightCalls[currentOwner]++
                            GlassPressHighlight()
                        },
                        onVisualBoundsChanged = { bounds, _ ->
                            visualCalls[currentOwner]++
                            visualBounds[currentOwner] = bounds
                        },
                    ) {}
                }
            }
        }
        compose.waitForIdle()
        lateinit var initialSurface: io.github.neoruaa.glasmio.glass.BottomContactSurface
        compose.runOnIdle {
            initialSurface = checkNotNull(contact?.surface?.value)
            assertTrue("The material must have rendered its original owner", visualCalls[0] > 0)
            assertTrue("The external highlight must have read its original owner", highlightCalls[0] > 0)
            owner.value = 1
        }
        compose.waitForIdle()
        var retiredBoundsCalls = 0
        var retiredHighlightCalls = 0
        var retiredVisualCalls = 0
        compose.runOnIdle {
            val rebound = checkNotNull(contact?.surface?.value)
            assertSame("Unchanged callback geometry keeps its complete material snapshot", initialSurface, rebound)
            assertTrue("The current geometry owner is evaluated even when its pixels are unchanged", boundsCalls[1] > 0)
            assertTrue("The current visual owner is called after rebinding", visualCalls[1] > 0)
            assertTrue("The current external highlight owner is used", highlightCalls[1] > 0)
            assertEquals(visualBounds[0], visualBounds[1])
            retiredBoundsCalls = boundsCalls[0]
            retiredHighlightCalls = highlightCalls[0]
            retiredVisualCalls = visualCalls[0]
            inset.floatValue = 6f
            surfaceWidth.floatValue = 102f
        }
        compose.waitForIdle()
        lateinit var resizedSurface: io.github.neoruaa.glasmio.glass.BottomContactSurface
        compose.runOnIdle {
            val current = checkNotNull(contact)
            resizedSurface = checkNotNull(current.surface.value)
            assertNotSame("A changed contour must replace the material snapshot", initialSurface.shape, resizedSurface.shape)
            assertEquals("The live inset reaches the contact geometry", 6f, current.members.first().capsule()!!.bounds.left, 0.01f)
            assertEquals("The visual callback receives the current layout", 102f * density, visualBounds[1]!!.width, 1f)
            assertEquals("Retired geometry callbacks cannot be retained", retiredBoundsCalls, boundsCalls[0])
            assertEquals("Retired highlight callbacks cannot be retained", retiredHighlightCalls, highlightCalls[0])
            assertEquals("Retired visual callbacks cannot be retained", retiredVisualCalls, visualCalls[0])
            sourceScale.value = Offset(0.91f, 0.8f)
        }
        compose.waitForIdle()
        compose.runOnIdle {
            val current = checkNotNull(contact)
            assertEquals("The sampled material uses the current transform", sourceScale.value, current.lensTransform.value!!.scale)
            assertNotSame("Changed refraction coverage cannot reuse the old material snapshot", resizedSurface.shape, current.surface.value!!.shape)
        }
    }

    @Test
    fun contactLensSamplesTheNeighborWhilePreservingItsCenter() {
        val contactDirection = mutableFloatStateOf(0f)
        val backing = Color(0xFF203040)
        compose.setContent {
            val backdrop = rememberLayerBackdrop()
            Box(Modifier.size(300.dp, 140.dp)) {
                Canvas(Modifier.matchParentSize().layerBackdrop(backdrop)) {
                    drawRect(backing)
                    drawRect(Color.Red, Offset(60.dp.toPx(), 20.dp.toPx()), Size(35.dp.toPx(), 100.dp.toPx()))
                    drawRect(Color.Red, Offset(205.dp.toPx(), 20.dp.toPx()), Size(35.dp.toPx(), 100.dp.toPx()))
                    drawRect(Color.White, Offset(149.dp.toPx(), 60.dp.toPx()), Size(2.dp.toPx(), 20.dp.toPx()))
                    drawRect(Color.White, Offset(145.dp.toPx(), 69.dp.toPx()), Size(10.dp.toPx(), 2.dp.toPx()))
                }
                Box(
                    Modifier.offset(100.dp, 20.dp).size(100.dp)
                        .drawBackdrop(
                            backdrop = backdrop,
                            shape = { Capsule() },
                            effects = { navigationContactLens(10.dp.toPx(), 14.dp.toPx(), contactDirection.floatValue) },
                            highlight = null,
                            shadow = null,
                        ).testTag("contactSamplingLens"),
                )
            }
        }
        compose.waitForIdle()
        val baseline = compose.onNodeWithTag("contactSamplingLens").captureToImage().toPixelMap()
        fun redSamples(pixels: androidx.compose.ui.graphics.PixelMap, right: Boolean): Int {
            val edge = (pixels.width * 0.1f).roundToInt()
            val start = if (right) pixels.width - edge else 0
            var count = 0
            for (x in start until start + edge) {
                for (y in pixels.height * 2 / 5 until pixels.height * 3 / 5) {
                    val color = pixels[x, y]
                    if (color.red > 0.65f && color.red > color.green * 1.7f) count++
                }
            }
            return count
        }
        assertEquals("The inward lens cannot reach either neighboring marker", 0,
            redSamples(baseline, false) + redSamples(baseline, true))
        for (direction in listOf(1f, -1f)) {
            compose.runOnIdle { contactDirection.floatValue = direction }
            compose.waitForIdle()
            val actual = compose.onNodeWithTag("contactSamplingLens").captureToImage().toPixelMap()
            assertTrue("Contact refraction reaches the neighboring surface",
                redSamples(actual, direction > 0f) > actual.height / 3)
            assertEquals("The opposite edge retains its original inward refraction", 0,
                redSamples(actual, direction < 0f))
            for (x in actual.width * 2 / 5 until actual.width * 3 / 5) {
                for (y in actual.height * 2 / 5 until actual.height * 3 / 5) {
                    assertEquals("The central marker keeps its position and scale", baseline[x, y], actual[x, y])
                }
            }
        }
    }

    @Test
    fun bottomControlContactPreservesIndependentBodiesAcrossProximityAndUnequalScales() {
        val nav = LiquidContactCapsule(Size(280f, 60f), Rect(20f, 20f, 328f, 92f))
        fun search(gap: Float, verticalOffset: Float = 0f) = LiquidContactCapsule(
            Size(60f, 60f),
            Rect(328f + gap, 24f + verticalOffset, 392f + gap, 88f + verticalOffset),
        )
        fun outline(first: LiquidContactCapsule, second: LiquidContactCapsule, distance: Float = 8f): Outline.Generic {
            val shape = liquidContactShape(first, second, distance)
            assertNotNull(shape)
            return shape!!.createOutline(Size(500f, 160f), LayoutDirection.Ltr, Density(1f)) as Outline.Generic
        }
        fun region(outline: Outline.Generic) = Region().apply {
            setPath(outline.path.asAndroidPath(), Region(0, 0, 500, 160))
        }
        fun bodyInterior(capsule: LiquidContactCapsule): Region {
            val native = Capsule().createOutline(capsule.size, LayoutDirection.Ltr, Density(1f))
            val nativePath = Path().apply {
                when (native) {
                    is Outline.Generic -> addPath(native.path)
                    is Outline.Rounded -> addRoundRect(native.roundRect)
                    is Outline.Rectangle -> addRect(native.rect)
                }
            }
            val path = android.graphics.Path(nativePath.asAndroidPath()).apply {
                transform(android.graphics.Matrix().apply {
                    setScale(capsule.bounds.width / capsule.size.width, capsule.bounds.height / capsule.size.height)
                    postTranslate(capsule.bounds.left, capsule.bounds.top)
                })
            }
            val body = Region().apply { setPath(path, Region(0, 0, 500, 160)) }
            // Leave a one-pixel raster margin; the original circular body must not be carved away.
            return Region(body).apply {
                for (dx in -1..1) for (dy in -1..1) {
                    op(Region(body).apply { translate(dx, dy) }, Region.Op.INTERSECT)
                }
            }
        }

        assertNull("The resting gap must keep both controls separate", liquidContactShape(nav, search(8f), 8f))
        assertNull("Layout rounding must not activate contact", liquidContactShape(nav, search(7.999f), 8f))
        assertNull("Distant controls have no connection", liquidContactShape(nav, search(40f), 8f))
        assertNull("Vertical separation has no connection", liquidContactShape(nav, search(0f, 90f), 8f))

        for (distance in listOf(4f, 8f)) for (gap in listOf(distance * 0.875f, distance * 0.375f, 0f, -distance * 0.5f)) {
            for (verticalOffset in listOf(-6f, 0f, 6f)) {
                val other = search(gap, verticalOffset)
                val visibleShape = liquidContactShape(nav, other, distance) as LiquidContactShape
                val connected = visibleShape.createOutline(
                    Size(500f, 160f), LayoutDirection.Ltr, Density(1f),
                ) as Outline.Generic
                val fill = region(connected)
                val bounds = connected.path.getBounds()
                assertEquals(nav.bounds.left, bounds.left, 0.01f)
                assertEquals(other.bounds.right, bounds.right, 0.01f)
                assertTrue("Navigation body remains filled", fill.contains(174, 56))
                assertTrue("The native flat top remains intact", fill.contains(174, 21))
                assertTrue("Search body remains filled", fill.contains(other.bounds.center.x.toInt(), other.bounds.center.y.toInt()))
                for (body in listOf(nav, other)) {
                    val missing = bodyInterior(body).apply { op(fill, Region.Op.DIFFERENCE) }
                    assertTrue("Contact must preserve the native body at gap=$gap, offset=$verticalOffset", missing.isEmpty)
                }
                assertTrue("Native outer corners remain rounded", !fill.contains(21, 21))
                val neckX = ((nav.bounds.right + other.bounds.left) / 2f).toInt()
                assertTrue("Contact must not flatten into a merged capsule", !fill.contains(neckX, 25))
                if (gap > distance / 2f) {
                    val contours = android.graphics.PathMeasure(connected.path.asAndroidPath(), false)
                    var count = 1
                    while (contours.nextContour()) count++
                    assertEquals("Approaching bodies stay closed without a zero-width connector", 2, count)
                }
                if (gap < distance / 2f) {
                    val neckY = ((nav.bounds.center.y + other.bounds.center.y) / 2f).toInt()
                    assertTrue("Nearby edges form a continuous neck", fill.contains(neckX, neckY))
                    if (verticalOffset == 0f) {
                        val column = (0 until 160).filter { fill.contains(neckX, it) }
                        assertEquals("A centered contact must keep mirrored upper and lower necks",
                            nav.bounds.center.y * 2f, (column.first() + column.last() + 1).toFloat(), 1f)
                    }
                }
                val reversed = region(outline(other, nav, distance))
                reversed.op(fill, Region.Op.XOR)
                assertTrue("Physical input order must not change RTL geometry", reversed.isEmpty)

                val originalPath = android.graphics.Path(connected.path.asAndroidPath())
                val originalPoints = originalPath.approximate(0.001f)
                val scale = Offset(0.91f, 0.84f)
                val translation = Offset(7.5f, 3.25f)
                val expectedPath = android.graphics.Path(originalPath).apply {
                    transform(android.graphics.Matrix().apply {
                        setScale(scale.x, scale.y)
                        postTranslate(translation.x, translation.y)
                    })
                }
                val expectedFill = Region().apply {
                    setPath(expectedPath, Region(0, 0, 500, 160))
                }
                val hidden = visibleShape.withOutlineTransform(scale, translation).createOutline(
                    Size(500f, 160f), LayoutDirection.Ltr, Density(1f),
                ) as Outline.Generic
                assertArrayEquals(
                    "The hidden source applies the affine transform to the native contour",
                    expectedPath.approximate(0.001f), hidden.path.asAndroidPath().approximate(0.001f), 0f,
                )
                val hiddenDifference = region(hidden).apply { op(expectedFill, Region.Op.XOR) }
                assertTrue("The hidden source preserves the transformed filled contour", hiddenDifference.isEmpty)
                assertArrayEquals(
                    "Transforming the shared source must not mutate the visible native path",
                    originalPoints, connected.path.asAndroidPath().approximate(0.001f), 0f,
                )
                assertEquals("The visible outline bounds remain unchanged", bounds, connected.path.getBounds())
                val visibleDifference = region(connected).apply { op(fill, Region.Op.XOR) }
                assertTrue("The visible filled contour remains unchanged", visibleDifference.isEmpty)

                val rtlShape = liquidContactShape(other, nav, distance) as LiquidContactShape
                val rtlVisible = rtlShape.createOutline(
                    Size(500f, 160f), LayoutDirection.Rtl, Density(1f),
                ) as Outline.Generic
                val rtlHidden = rtlShape.withOutlineTransform(scale, translation).createOutline(
                    Size(500f, 160f), LayoutDirection.Rtl, Density(1f),
                ) as Outline.Generic
                assertArrayEquals(
                    "Reversed RTL inputs retain the same transformed native contour",
                    expectedPath.approximate(0.001f), rtlHidden.path.asAndroidPath().approximate(0.001f), 0f,
                )
                assertArrayEquals(
                    "The RTL visible path also remains independent of its hidden transform",
                    originalPoints, rtlVisible.path.asAndroidPath().approximate(0.001f), 0f,
                )
            }
        }
        val contained = LiquidContactCapsule(Size(60f, 60f), Rect(220f, 26f, 280f, 86f))
        assertTrue("Containment keeps a valid silhouette", region(outline(nav, contained)).contains(250, 56))
    }

    @Test
    fun connectedLensSourceCarriesPressLightAcrossTheNeighbor() {
        val light = mutableFloatStateOf(0f)
        val direction = mutableStateOf(LayoutDirection.Ltr)
        var contact: BottomControlContact? = null
        var density = 1f
        val backing = Color(0xFF303030)
        compose.setContent {
            density = LocalDensity.current.density
            val backdrop = rememberCanvasBackdrop { drawRect(backing) }
            CompositionLocalProvider(
                LocalLayoutDirection provides direction.value,
                LocalGlassAppearance provides GlassAppearance(),
                LocalGlassColors provides defaultGlassColors(isDark = true),
            ) {
                Column(Modifier.background(backing).testTag("contactPressLightPreview"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    BottomNavigationContact(backdrop, Modifier.size(330.dp, 60.dp).testTag("visibleContactLight")) {
                        contact = LocalBottomControlContact.current
                        val nav = rememberBottomControlContactMember(
                            visualBounds = { Rect(Offset.Zero, it) },
                            materialPressProgress = { 1f },
                            highlight = {
                                GlassPressHighlight(light.floatValue,
                                    Offset(if (direction.value == LayoutDirection.Ltr) 0.92f else 0.08f, 0.5f))
                            },
                            lensSourceActive = { true },
                            lensSourceScale = { Offset(0.93f, 0.82f) },
                            lensSourcePressProgress = { 1f },
                        )
                        Box(Modifier.size(260.dp, 60.dp).onGloballyPositioned { nav?.updateCoordinates(it) })
                        Spacer(Modifier.width(1.dp))
                        val search = rememberBottomControlContactMember(
                            visualBounds = { Rect(Offset.Zero, it) },
                            materialPressProgress = { 0f },
                            highlight = { GlassPressHighlight() },
                        )
                        Box(Modifier.size(60.dp).onGloballyPositioned { search?.updateCoordinates(it) })
                    }
                    Canvas(Modifier.size(330.dp, 60.dp).testTag("sampledContactLight")) {
                        drawRect(backing)
                        contact?.let { drawLayer(it.materialBackdrop.graphicsLayer) }
                    }
                }
            }
        }
        for (layoutDirection in listOf(LayoutDirection.Ltr, LayoutDirection.Rtl)) {
            compose.runOnIdle {
                direction.value = layoutDirection
                light.floatValue = 0f
            }
            compose.waitForIdle()
            val visibleBefore = compose.onNodeWithTag("visibleContactLight").captureToImage().toPixelMap()
            val sampledBefore = compose.onNodeWithTag("sampledContactLight").captureToImage().toPixelMap()
            lateinit var transform: BottomContactLensTransform
            lateinit var points: List<Offset>
            compose.runOnIdle {
                val current = checkNotNull(contact)
                assertTrue("The test must exercise an actual shared contact surface", current.connected.value)
                transform = checkNotNull(current.lensTransform.value)
                val nav = checkNotNull(current.members[0].capsule()).bounds
                val search = checkNotNull(current.members[1].capsule()).bounds
                val toRight = search.center.x > nav.center.x
                val inset = 6f * density
                points = listOf(
                    Offset(if (toRight) nav.right - inset else nav.left + inset, nav.center.y),
                    Offset(if (toRight) search.left + inset else search.right - inset, search.center.y),
                    search.center,
                )
                light.floatValue = 1f
            }
            compose.waitForIdle()
            val visibleAfter = compose.onNodeWithTag("visibleContactLight").captureToImage().toPixelMap()
            val sampledAfter = compose.onNodeWithTag("sampledContactLight").captureToImage().toPixelMap()
            if (layoutDirection == LayoutDirection.Ltr) {
                val preview = compose.onNodeWithTag("contactPressLightPreview").captureToImage()
                val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
                java.io.File(context.cacheDir, "nav-contact-highlight-preview.png").outputStream().use {
                    preview.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            fun delta(before: androidx.compose.ui.graphics.PixelMap, after: androidx.compose.ui.graphics.PixelMap, point: Offset): Float {
                val x = point.x.toInt().coerceIn(0, before.width - 1)
                val y = point.y.toInt().coerceIn(0, before.height - 1)
                return (after[x, y].red - before[x, y].red) * 255f
            }
            points.forEachIndexed { index, point ->
                val visible = delta(visibleBefore, visibleAfter, point)
                val sampled = delta(sampledBefore, sampledAfter, transform.point(point))
                val label = "$layoutDirection point=$index, visible=$visible, sampled=$sampled"
                android.util.Log.i("ContactLightTest", "$label at=$point, before=${visibleBefore[point.x.toInt(), point.y.toInt()]}, after=${visibleAfter[point.x.toInt(), point.y.toInt()]}")
                assertTrue("The visible press wash reaches the neighboring body: $label", visible >= 9f)
                assertTrue("The sampled press wash must continue beyond Nav: $label", sampled >= 9f)
                assertEquals("The sampled press light follows the shared affine transform: $label", visible, sampled, 2f)
            }
        }
    }

    @Test
    fun outerTabPressAnchorsOnlyTheRefractedSelectionAndOtherwiseScalesTheWholePanel() {
        showBar()

        fun iconBounds(): Map<String, Rect> = items.value.associate { item ->
            item.key to visibleTabIconBounds(item)
        }

        fun assertRestingCenters(expected: Map<String, Rect>) {
            val actual = iconBounds()
            expected.forEach { (key, bounds) ->
                assertEquals("Release must restore $key", bounds.center.x, actual.getValue(key).center.x, 0.75f)
            }
        }

        try {
            listOf(
                GlassAppearance(),
                GlassAppearance(liquidGlassEnabled = false),
                GlassAppearance(reduceTransparency = true),
            ).forEach { appearance ->
                listOf(LayoutDirection.Ltr, LayoutDirection.Rtl).forEach { direction ->
                    listOf(home, library).forEach { selected ->
                        compose.runOnIdle {
                            glassAppearance.value = appearance
                            barLayoutDirection.value = direction
                            selectedKey.value = selected.key
                        }
                        compose.waitForIdle()
                        val before = iconBounds()
                        val bar = compose.onNodeWithTag("bottomBar")
                        val barBounds = bar.fetchSemanticsNode().boundsInRoot
                        compose.mainClock.autoAdvance = false
                        bar.performTouchInput {
                            down(Offset(before.getValue(selected.key).center.x - barBounds.left, center.y))
                        }
                        listOf(16L, 96L, 320L).forEach { duration ->
                            compose.mainClock.advanceTimeBy(duration)
                            if (appearance.usesRefraction) {
                                assertEquals(
                                    "The refracted selection keeps its horizontal center",
                                    before.getValue(selected.key).center.x,
                                    iconBounds().getValue(selected.key).center.x, 0.75f,
                                )
                            }
                        }
                        val pressed = iconBounds()
                        // Press expansion is a fixed 16dp, independent of the surface height.
                        val scale = 1f + with(compose.density) { 16.dp.toPx() } / barBounds.width
                        before.filterKeys { !appearance.usesRefraction || it != selected.key }.forEach { (key, bounds) ->
                            val expectedCenter = barBounds.center.x +
                                (bounds.center.x - barBounds.center.x) * scale
                            assertEquals("Tab $key follows the panel", expectedCenter, pressed.getValue(key).center.x, 0.75f)
                            assertEquals("Tab $key has only the panel scale", bounds.width * scale, pressed.getValue(key).width, 0.75f)
                        }
                        bar.performTouchInput { up() }
                        compose.mainClock.advanceTimeBy(1000L)
                        assertRestingCenters(before)
                        compose.mainClock.autoAdvance = true
                    }
                }
            }
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test
    fun heldDragReturnsTheDepartedIconToPanelMotionBeforeSelectionCommits() {
        glassAppearance.value = GlassAppearance()
        showBar()

        try {
            listOf(LayoutDirection.Ltr, LayoutDirection.Rtl).forEach { direction ->
                listOf(home, library).forEach { outgoing ->
                    val incoming = if (outgoing == home) library else home
                    compose.runOnIdle {
                        barLayoutDirection.value = direction
                        selectedKey.value = outgoing.key
                        selections.clear()
                    }
                    compose.waitForIdle()
                    val bar = compose.onNodeWithTag("bottomBar")
                    val barBounds = bar.fetchSemanticsNode().boundsInRoot
                    val outgoingBefore = visibleTabIconBounds(outgoing)
                    val incomingBefore = visibleTabIconBounds(incoming)
                    val middleBefore = visibleTabIconBounds(radio)
                    // Press expansion is a fixed 16dp, independent of the surface height.
                    val scale = 1f + with(compose.density) { 16.dp.toPx() } / barBounds.width
                    val startX = outgoingBefore.center.x - barBounds.left
                    val toRight = incomingBefore.center.x > outgoingBefore.center.x
                    val destinationX = if (toRight) 2f * barBounds.width else -barBounds.width
                    compose.mainClock.autoAdvance = false
                    bar.performTouchInput { down(Offset(startX, center.y)) }
                    compose.mainClock.advanceTimeBy(500L)

                    fun dragAwayAndAssert() {
                        bar.performTouchInput { moveTo(Offset(destinationX, center.y)) }
                        compose.mainClock.advanceTimeBy(700L)
                        val commonShift = visibleTabIconBounds(radio).center.x - middleBefore.center.x
                        val expectedOutgoing = barBounds.center.x +
                            (outgoingBefore.center.x - barBounds.center.x) * scale + commonShift
                        assertEquals("Departed icon follows the panel while held", expectedOutgoing, visibleTabIconBounds(outgoing).center.x, 0.75f)
                        assertEquals("Incoming icon is already centered before commit", incomingBefore.center.x + commonShift, visibleTabIconBounds(incoming).center.x, 0.75f)
                        compose.runOnIdle {
                            assertEquals(outgoing.key, selectedKey.value)
                            assertTrue("Dragging must not navigate", selections.isEmpty())
                        }
                    }

                    dragAwayAndAssert()
                    bar.performTouchInput { moveTo(Offset(startX, center.y)) }
                    compose.mainClock.advanceTimeBy(700L)
                    val commonShift = visibleTabIconBounds(radio).center.x - middleBefore.center.x
                    assertEquals("Reversing reanchors the original icon", outgoingBefore.center.x + commonShift, visibleTabIconBounds(outgoing).center.x, 0.75f)
                    dragAwayAndAssert()
                    bar.performTouchInput { up() }
                    compose.mainClock.autoAdvance = true
                    compose.waitForIdle()
                    compose.runOnIdle { assertEquals(listOf(incoming.key), selections) }
                }
            }
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test
    fun destinationUpdatesNeverRequestAnotherNavigation() {
        showBar()

        listOf(library.key, radio.key, home.key).forEach { destination ->
            compose.runOnIdle { selectedKey.value = destination }
            compose.runOnIdle { assertTrue(selections.isEmpty()) }
        }
    }

    @Test
    fun heldDragReachesTheOppositeTabWithoutLeavingTheBar() {
        showBar()
        try {
            listOf(
                GlassAppearance(),
                GlassAppearance(liquidGlassEnabled = false),
                GlassAppearance(reduceTransparency = true),
            ).forEach { appearance ->
                listOf(LayoutDirection.Ltr, LayoutDirection.Rtl).forEach { direction ->
                    listOf(home, library).forEach { outgoing ->
                        val incoming = if (outgoing == home) library else home
                        compose.runOnIdle {
                            glassAppearance.value = appearance
                            barLayoutDirection.value = direction
                            selectedKey.value = outgoing.key
                            selections.clear()
                        }
                        compose.waitForIdle()
                        val bar = compose.onNodeWithTag("bottomBar")
                        val barBounds = bar.fetchSemanticsNode().boundsInRoot
                        fun center(item: GlasmioTabItem<String>): Offset {
                            val icon = visibleTabIconBounds(item)
                            return Offset(icon.center.x - barBounds.left, barBounds.height / 2f)
                        }
                        val start = center(outgoing)
                        compose.mainClock.autoAdvance = false
                        bar.performTouchInput { down(start) }
                        compose.mainClock.advanceTimeBy(500L)
                        val end = center(incoming)
                        assertTrue(end.x in 0f..barBounds.width)
                        bar.performTouchInput { moveTo(end) }
                        compose.mainClock.advanceTimeBy(700L)
                        bar.performTouchInput { moveTo(start) }
                        compose.mainClock.advanceTimeBy(700L)
                        bar.performTouchInput { moveTo(end) }
                        compose.mainClock.advanceTimeBy(700L)
                        compose.runOnIdle { assertTrue("Dragging must not commit a destination", selections.isEmpty()) }
                        bar.performTouchInput { up() }
                        compose.mainClock.autoAdvance = true
                        compose.waitForIdle()
                        compose.runOnIdle { assertEquals(listOf(incoming.key), selections) }
                    }
                }
            }
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test
    fun reorderedTabsAndCompactTransitionsOnlyNavigateAfterAnActualDrag() {
        selectedKey.value = library.key
        showBar()

        compose.runOnIdle { items.value = listOf(library, home, radio) }
        compose.runOnIdle { assertTrue(selections.isEmpty()) }
        listOf(0.5f, 1f, 0.5f, 0f).forEach { progress ->
            compose.runOnIdle { compactProgress.floatValue = progress }
            compose.runOnIdle { assertTrue(selections.isEmpty()) }
        }

        compose.onNodeWithTag("bottomBar").performTouchInput {
            swipe(
                start = Offset(width / 6f, center.y),
                end = Offset(width * 5f / 6f, center.y),
                durationMillis = 300,
            )
        }
        compose.runOnIdle { assertEquals(listOf(radio.key), selections) }
    }

    @Test
    fun tabTapAndIndicatorDragEachSelectTheirDestinationOnce() {
        showBar()

        compose.onNodeWithContentDescription(radio.label).performClick()
        compose.runOnIdle { assertEquals(listOf(radio.key), selections) }

        // The selected tab's indicator handles the drag; the other tabs use click actions.
        compose.onNodeWithTag("bottomBar").performTouchInput {
            swipe(
                start = center,
                end = Offset(width * 5f / 6f, center.y),
                durationMillis = 300,
            )
        }

        compose.runOnIdle {
            assertEquals(listOf(radio.key, library.key), selections)
            assertEquals(library.key, selectedKey.value)
        }
    }

    @Test
    fun compactTapAndDragExpandWithoutSelectingATab() {
        compactProgress.floatValue = 1f
        showBar()

        compose.onNodeWithContentDescription(home.label).performClick()
        compose.runOnIdle {
            assertEquals(1, expansions)
            assertTrue(selections.isEmpty())
            compactProgress.floatValue = 1f
        }

        compose.onNodeWithContentDescription(home.label).performTouchInput {
            swipe(
                start = center,
                end = Offset(center.x + width, center.y),
                durationMillis = 300,
            )
        }

        compose.runOnIdle {
            assertEquals(2, expansions)
            assertTrue(selections.isEmpty())
        }
    }

    @Test
    fun morphingPressHighlightStaysInsideTheCurrentOutline() {
        val press = mutableFloatStateOf(0f)
        val width = mutableStateOf(48.dp)
        val height = mutableStateOf(48.dp)
        val roundedRectangle = mutableStateOf(false)
        val backing = Color(0xFF2E233B)
        compose.setContent {
            val scope = rememberCoroutineScope()
            val highlight = remember(scope) { InteractiveHighlight(scope) }
            val shape = if (roundedRectangle.value) ContinuousRoundedRectangle(26.dp) else Capsule()
            CompositionLocalProvider(
                LocalGlassAppearance provides GlassAppearance(reduceTransparency = true),
            ) {
                val glass = rememberMorphingNavigationGlass(
                    backdrop = emptyBackdrop(),
                    active = true,
                    shape = shape,
                    tint = Color.Black,
                    navigationMaterial = false,
                    pressProgress = { 0f },
                    layerBlock = {},
                    lensSource = true,
                )
                Box(
                    Modifier.size(width.value, height.value).background(backing)
                        .testTag("morphPressSurface")
                        .then(glass)
                        .then(highlight.highlightModifier(
                            progressProvider = { press.floatValue },
                            positionProvider = { size -> Offset(size.width / 2f, size.height / 2f) },
                        )),
                )
            }
        }

        listOf(false, true).forEach { useRoundedRectangle ->
            listOf(48 to 48, 80 to 48, 160 to 64, 80 to 48, 48 to 48).forEach { (w, h) ->
                compose.runOnIdle {
                    roundedRectangle.value = useRoundedRectangle
                    width.value = w.dp
                    height.value = h.dp
                    press.floatValue = 0f
                }
                val surface = compose.onNodeWithTag("morphPressSurface")
                val resting = surface.captureToImage().toPixelMap()
                compose.runOnIdle { press.floatValue = 1f }
                val pressed = surface.captureToImage().toPixelMap()
                listOf(
                    1 to 1,
                    pressed.width - 2 to 1,
                    1 to pressed.height - 2,
                    pressed.width - 2 to pressed.height - 2,
                ).forEach { (x, y) ->
                    val corner = pressed[x, y]
                    assertEquals("Press wash must leave the exterior red unchanged", backing.red, corner.red, 1f / 255f)
                    assertEquals("Press wash must leave the exterior green unchanged", backing.green, corner.green, 1f / 255f)
                    assertEquals("Press wash must leave the exterior blue unchanged", backing.blue, corner.blue, 1f / 255f)
                }
                val x = pressed.width / 2
                val y = pressed.height / 2
                assertTrue("Clipping must preserve the interior press light", pressed[x, y].red > resting[x, y].red + 0.02f)
            }
        }
    }

    @Test
    fun navigationShadowLiftRaisesOnlyDarkSamplesAndPreservesAlpha() {
        val swatches = listOf(
            Color.Black, Color(0xFF202020), Color(0xFF404040), Color(0xFF666666),
            Color(0xFF600010), Color(0xFF001060),
            Color(0xFF808080), Color(0xFFF0F0F0), Color.White, Color(0xFF2080C0),
            Color.Black.copy(alpha = 0.4f), Color(0xFF202020).copy(alpha = 0.4f),
            Color(0xFF808080).copy(alpha = 0.4f), Color.Transparent,
        )
        compose.setContent {
            val filtered = remember {
                RenderNode("Navigation shadow lift swatches").apply {
                    setRenderEffect(navigationShadowLiftEffect())
                }
            }
            val paint = remember { android.graphics.Paint() }
            DisposableEffect(filtered) { onDispose { filtered.discardDisplayList() } }
            Canvas(Modifier.size(288.dp, 192.dp).testTag("navigationShadowLift")) {
                val canvas = drawContext.canvas.nativeCanvas
                assertTrue("The shadow lift comparison requires GPU rendering", canvas.isHardwareAccelerated)
                val stripeHeight = 48.dp.toPx()
                val swatchWidth = size.width / swatches.size
                filtered.setPosition(0, 0, size.width.toInt(), stripeHeight.toInt())
                val recording = filtered.beginRecording()
                try {
                    swatches.forEachIndexed { index, color ->
                        paint.color = color.toArgb()
                        recording.drawRect(
                            index * swatchWidth, 0f, (index + 1) * swatchWidth, stripeHeight, paint,
                        )
                    }
                } finally {
                    filtered.endRecording()
                }
                repeat(4) { row ->
                    val rowTop = row * stripeHeight
                    drawRect(
                        if (row % 2 == 0) Color.Black else Color.White,
                        topLeft = Offset(0f, rowTop),
                        size = Size(size.width, stripeHeight),
                    )
                    if (row < 2) {
                        canvas.save()
                        canvas.translate(0f, rowTop)
                        canvas.drawRenderNode(filtered)
                        canvas.restore()
                    } else {
                        swatches.forEachIndexed { index, color ->
                            drawRect(
                                color,
                                topLeft = Offset(index * swatchWidth, rowTop),
                                size = Size(swatchWidth, stripeHeight),
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        val pixels = compose.onNodeWithTag("navigationShadowLift").captureToImage().toPixelMap()
        fun sample(index: Int, row: Int) = pixels[
            ((index + 0.5f) * pixels.width / swatches.size).toInt(),
            ((row + 0.5f) * pixels.height / 4f).toInt(),
        ]
        fun tone(color: Color) = (color.red + color.green + color.blue) / 3f
        fun assertColor(message: String, expected: Color, actual: Color, tolerance: Float = 1f / 255f) {
            assertEquals("$message red", expected.red, actual.red, tolerance)
            assertEquals("$message green", expected.green, actual.green, tolerance)
            assertEquals("$message blue", expected.blue, actual.blue, tolerance)
            assertEquals("$message alpha", expected.alpha, actual.alpha, tolerance)
        }
        val lifts = (0..3).map { index ->
            val lift = tone(sample(index, 0)) - tone(sample(index, 2))
            assertTrue("Dark sample $index is lifted: $lift", lift > 1f / 255f)
            lift
        }
        assertTrue("Pure black receives a visible lift", lifts.first() >= 70f / 255f)
        lifts.zipWithNext().forEachIndexed { index, (darker, lighter) ->
            assertTrue("The lift tapers toward midtones at $index", darker >= lighter)
        }
        listOf(4, 5).forEach { index ->
            assertTrue(
                "Dark saturated sample $index is lifted by luminance",
                tone(sample(index, 0)) - tone(sample(index, 2)) >= 30f / 255f,
            )
        }
        listOf(6, 7, 8, 9, 12, 13).forEach { index ->
            repeat(2) { backing ->
                assertColor("Unchanged sample $index over backing $backing", sample(index, backing + 2), sample(index, backing))
            }
        }
        // Black and white backings expose any accidental change to premultiplied alpha.
        listOf(10, 11).forEach { index ->
            val black = sample(index, 0)
            val white = sample(index, 1)
            val referenceBlack = sample(index, 2)
            val referenceWhite = sample(index, 3)
            assertTrue("Translucent dark sample $index is lifted", tone(black) > tone(referenceBlack) + 1f / 255f)
            listOf(
                (white.red - black.red) to (referenceWhite.red - referenceBlack.red),
                (white.green - black.green) to (referenceWhite.green - referenceBlack.green),
                (white.blue - black.blue) to (referenceWhite.blue - referenceBlack.blue),
            ).forEachIndexed { channel, (actual, expected) ->
                assertEquals("Translucent sample $index keeps alpha in channel $channel", expected, actual, 2f / 255f)
            }
            val opaque = sample(index - 10, 0)
            assertColor(
                "Translucent sample $index keeps straight color",
                Color(opaque.red * 0.4f, opaque.green * 0.4f, opaque.blue * 0.4f),
                black,
                2f / 255f,
            )
        }
    }

    @Test
    fun shadowLiftRemainsVisibleThroughStaticAndMorphNavigationMaterials() {
        val backings = listOf(
            Color.Black, Color(0xFF202020), Color(0xFF404040),
            Color(0xFF600010), Color(0xFF001060),
            Color(0xFF2080C0), Color(0xFF808080), Color.White,
        )
        val backingIndex = mutableStateOf(0)
        compose.setContent {
            key(backingIndex.value) {
                val backing = backings[backingIndex.value]
                val appearance = GlassAppearance(liquidGlassEnabled = false)
                val tint = defaultGlassColors(false).container
                val backdrop = rememberCanvasBackdrop { drawRect(backing) }
                val shape = Capsule()
                val staticShadows = rememberNavigationInnerShadows(true)
                val referenceShadows = rememberNavigationInnerShadows(true)
                val coordinates = remember { mutableStateOf<LayoutCoordinates?>(null) }
                CompositionLocalProvider(LocalGlassAppearance provides appearance) {
                    val renderer = rememberGlassMorphRenderer(backdrop, active = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(true, false).forEach { lifted ->
                            Canvas(
                                Modifier.size(80.dp, 48.dp)
                                    .testTag(if (lifted) "shadowLiftStatic" else "shadowLiftMaterialReference")
                                    .navigationGlassBackground(
                                        backdrop = backdrop,
                                        shape = { shape },
                                        containerColor = tint,
                                        navigationMaterial = lifted,
                                        innerShadows = staticShadows,
                                        appearance = appearance,
                                        // Bypass only the lift gate while retaining the old full material.
                                        surfaceOverlay = if (lifted) null else ({
                                            drawNavigationGlassMaterial(tint, isDark = false)
                                            referenceShadows?.draw(this, shape, isDark = false)
                                        }),
                                    ),
                            ) { }
                        }
                        Canvas(
                            Modifier.size(80.dp, 48.dp).testTag("shadowLiftMorph")
                                .onGloballyPositioned { coordinates.value = it },
                        ) {
                            val current = coordinates.value ?: return@Canvas
                            val origin = current.positionInRoot()
                            renderer.draw(
                                scope = this,
                                coordinates = current,
                                viewport = Rect(origin, size),
                                origin = origin,
                                bounds = Rect(Offset.Zero, size),
                                radius = 24.dp.toPx(),
                                shape = shape,
                                tint = tint,
                                highlightAlpha = 0f,
                                navigationOutline = false,
                                navigationMaterial = true,
                            )
                        }
                    }
                }
            }
        }
        backings.indices.forEach { index ->
            compose.runOnIdle { backingIndex.value = index }
            compose.waitForIdle()
            fun center(tag: String): Color {
                val image = compose.onNodeWithTag(tag).captureToImage().toPixelMap()
                return image[image.width / 2, image.height / 2]
            }
            val reference = center("shadowLiftMaterialReference")
            listOf("shadowLiftStatic", "shadowLiftMorph").forEach { tag ->
                val actual = center(tag)
                val delta = (actual.red + actual.green + actual.blue -
                    reference.red - reference.green - reference.blue) / 3f
                val label = "$tag over $index after tint and inner shadows"
                if (index < 5) {
                    val minimumLift = if (index == 0) 20f / 255f else 8f / 255f
                    assertTrue("$label remains visibly lifted: $delta", delta >= minimumLift)
                } else {
                    assertEquals("$label red", reference.red, actual.red, 2f / 255f)
                    assertEquals("$label green", reference.green, actual.green, 2f / 255f)
                    assertEquals("$label blue", reference.blue, actual.blue, 2f / 255f)
                }
            }
        }
    }

    @Test
    fun liquidMenuInnerShadowHolePreservesCoverageDuringMotion() {
        val source = Rect(870f, 155f, 1072f, 287f)
        val target = Rect(358f, 155f, 1072f, 611f)
        val motion = LiquidMenuMorphMotion().apply { initialize(source, target, 102f, 3f) }
        val workspace = LiquidMenuContourWorkspace()
        val clip = Region(-1024, -1024, 2048, 2048)
        listOf(true, false).forEach { opening ->
            motion.retarget(opening)
            repeat(240) { frame ->
                motion.advanceBy(1f / 240f)
                val value = motion.frame
                val world = LiquidMenuContour(value.bounds, Offset(value.radiusX, value.radiusY),
                    value.sourceLobe, Offset(value.sourceRadiusX, value.sourceRadiusY), value.smoothing)
                val bounds = world.exteriorBounds()
                val local = LiquidMenuContour(value.bounds.translate(-bounds.topLeft),
                    Offset(value.radiusX, value.radiusY), value.sourceLobe?.translate(-bounds.topLeft),
                    Offset(value.sourceRadiusX, value.sourceRadiusY), value.smoothing, workspace)
                val outline = android.graphics.Path(local.path(bounds.size).asAndroidPath())
                val actual = Region().apply {
                    setPath(expandedLiquidMenuShadowHole(outline, 120f), clip)
                }
                // Rasterize each dilation component independently before their integer
                // region union, so the reference never intersects floating-point curves.
                val expected = Region()
                fun include(component: android.graphics.Path) {
                    expected.op(Region().apply { setPath(component, clip) }, Region.Op.UNION)
                }
                for (x in floatArrayOf(-120f, 120f)) for (y in floatArrayOf(-120f, 120f)) {
                    include(android.graphics.Path(outline).apply { offset(x, y) })
                }
                val extent = android.graphics.RectF().also { outline.computeBounds(it, true) }
                include(android.graphics.Path().apply {
                    addRect(extent.centerX() - 120f, extent.top - 120f,
                        extent.centerX() + 120f, extent.bottom + 120f, android.graphics.Path.Direction.CW)
                })
                include(android.graphics.Path().apply {
                    addRect(extent.left - 120f, extent.centerY() - 120f,
                        extent.right + 120f, extent.centerY() + 120f, android.graphics.Path.Direction.CW)
                })
                val difference = Region(actual).apply { op(expected, Region.Op.XOR) }
                // Ignore at most two pixels of boundary raster rounding, while keeping
                // the broad interior holes that caused visible one-frame triangles.
                val core = Region(difference)
                for (x in intArrayOf(-2, 0, 2)) for (y in intArrayOf(-2, 0, 2)) {
                    if (x != 0 || y != 0) core.op(Region(difference).apply { translate(x, y) }, Region.Op.INTERSECT)
                }
                assertTrue("The shadow hole stays complete: opening=$opening frame=$frame bounds=${core.bounds}",
                    core.isEmpty)
            }
        }
    }

    @Test
    fun navigationInnerShadowsPreserveContrastInsideSmallShapes() {
        val backingColors = listOf(Color.White, Color(0xFFF0F0F0), Color(0xFF202020), Color(0xFF404040))
        val bounds = listOf(Rect(8f, 8f, 52f, 52f), Rect(88f, 8f, 168f, 56f))
        compose.setContent {
            val renderers = remember { List(bounds.size) { NavigationInnerShadowRenderer() } }
            DisposableEffect(renderers) {
                onDispose { renderers.forEach { it.release() } }
            }
            Canvas(Modifier.width(176.dp).height(256.dp).testTag("navigationInnerShadows")) {
                val stripeHeight = 64.dp.toPx()
                backingColors.forEachIndexed { backingIndex, color ->
                    val stripeTop = backingIndex * stripeHeight
                    drawRect(
                        color = color,
                        topLeft = Offset(0f, stripeTop),
                        size = Size(size.width, stripeHeight),
                    )
                    bounds.forEachIndexed { shapeIndex, shapeBounds ->
                        renderers[shapeIndex].draw(
                            scope = this,
                            shape = if (shapeIndex == 0) CircleShape else Capsule(),
                            bounds = Rect(
                                left = shapeBounds.left.dp.toPx(),
                                top = stripeTop + shapeBounds.top.dp.toPx(),
                                right = shapeBounds.right.dp.toPx(),
                                bottom = stripeTop + shapeBounds.bottom.dp.toPx(),
                            ),
                            isDark = backingIndex >= 2,
                        )
                    }
                }
            }
        }
        compose.waitForIdle()

        val pixels = compose.onNodeWithTag("navigationInnerShadows").captureToImage().toPixelMap()
        fun pixelAt(xDp: Float, yDp: Float) = pixels[
            (xDp * pixels.width / 176f).toInt(),
            (yDp * pixels.height / 256f).toInt(),
        ]
        fun tone(color: Color) = (color.red + color.green + color.blue) / 3f
        fun assertColor(message: String, expected: Color, actual: Color) {
            val tolerance = 1f / 255f
            assertEquals("$message red", expected.red, actual.red, tolerance)
            assertEquals("$message green", expected.green, actual.green, tolerance)
            assertEquals("$message blue", expected.blue, actual.blue, tolerance)
            assertEquals("$message alpha", expected.alpha, actual.alpha, tolerance)
        }
        backingColors.forEachIndexed { backingIndex, backingColor ->
            val isDark = backingIndex >= 2
            val stripeTop = backingIndex * 64f
            bounds.forEachIndexed { shapeIndex, shapeBounds ->
                val centerX = shapeBounds.center.x
                val topEdge = pixelAt(centerX, stripeTop + shapeBounds.top + 2f)
                val innerBand = pixelAt(centerX, stripeTop + shapeBounds.top + 10f)
                val bottomEdge = pixelAt(centerX, stripeTop + shapeBounds.bottom - 2f)
                val label = "Shape $shapeIndex over backing $backingIndex, dark=$isDark"
                val topContrast = (tone(topEdge) - tone(innerBand)) * 255f
                val bottomContrast = (tone(bottomEdge) - tone(innerBand)) * 255f
                val innerDarkening = (tone(backingColor) - tone(innerBand)) * 255f
                val minimumContrast = if (isDark) 3f else 5f
                // Negative spread keeps the bright rims narrow instead of whitening the whole face.
                assertTrue("$label top rim contrast: $topContrast", topContrast >= minimumContrast - 0.01f)
                assertTrue("$label bottom rim contrast: $bottomContrast", bottomContrast >= minimumContrast - 0.01f)
                assertTrue("$label inner darkening: $innerDarkening", innerDarkening >= 2f - 0.01f)
                if (isDark) {
                    val bottomLift = (tone(bottomEdge) - tone(backingColor)) * 255f
                    assertEquals("$label dark Plus rim lift", 9f, bottomLift, 2f)
                    assertEquals("$label dark LinearBurn band", 6f, innerDarkening, 2f)
                }
                assertEquals("$label stays opaque", 1f, innerBand.alpha, 1f / 255f)
                assertColor(
                    "$label outside bounds",
                    backingColor,
                    pixelAt(centerX, stripeTop + 4f),
                )
                assertColor(
                    "$label outside rounded corner",
                    backingColor,
                    pixelAt(shapeBounds.left + 2f, stripeTop + shapeBounds.top + 2f),
                )
            }
        }
    }

    @Test
    fun halfResolutionNavigationInnerShadowsMatchNativeMasks() {
        val backings = listOf(Color.White, Color(0xFFF0F0F0), Color(0xFF202020), Color(0xFF404040))
        val scenarios = listOf("circle", "capsule", "contact").flatMap { shape ->
            backings.indices.map { backing -> shape to backing }
        }
        val scenarioIndex = mutableStateOf(0)
        fun boundsFor(shape: String) = when (shape) {
            "circle" -> Rect(12f, 24f, 72f, 84f)
            "capsule" -> Rect(12f, 26f, 326f, 86f)
            else -> Rect(12f, 24f, 326f, 88f)
        }
        compose.setContent {
            val (shapeName, backingIndex) = scenarios[scenarioIndex.value]
            val renderers = remember {
                listOf(NavigationInnerShadowRenderer(1f), NavigationInnerShadowRenderer(0.5f))
            }
            DisposableEffect(renderers) { onDispose { renderers.forEach { it.release() } } }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                renderers.forEachIndexed { index, renderer ->
                    Canvas(
                        Modifier.size(338.dp, 112.dp)
                            .testTag(if (index == 0) "nativeShadowMask" else "candidateShadowMask"),
                    ) {
                        assertTrue("Shadow mask comparisons require GPU rendering",
                            drawContext.canvas.nativeCanvas.isHardwareAccelerated)
                        drawRect(backings[backingIndex])
                        val boundsDp = boundsFor(shapeName)
                        val bounds = Rect(
                            boundsDp.left.dp.toPx(), boundsDp.top.dp.toPx(),
                            boundsDp.right.dp.toPx(), boundsDp.bottom.dp.toPx(),
                        )
                        val shape = when (shapeName) {
                            "circle" -> CircleShape
                            "capsule" -> Capsule()
                            else -> checkNotNull(liquidContactShape(
                                LiquidContactCapsule(
                                    Size(236.dp.toPx(), 60.dp.toPx()),
                                    Rect(0f, 2.dp.toPx(), 248.dp.toPx(), 64.dp.toPx()),
                                ),
                                LiquidContactCapsule(
                                    Size(60.dp.toPx(), 60.dp.toPx()),
                                    Rect(250.dp.toPx(), 0f, 314.dp.toPx(), 64.dp.toPx()),
                                ),
                                8.dp.toPx(),
                            ))
                        }
                        renderer.draw(this, shape, bounds, isDark = backingIndex >= 2)
                    }
                }
            }
        }
        val failures = mutableListOf<String>()
        scenarios.forEachIndexed { index, (shapeName, backingIndex) ->
            compose.runOnIdle { scenarioIndex.value = index }
            compose.waitForIdle()
            val reference = compose.onNodeWithTag("nativeShadowMask").captureToImage().toPixelMap()
            val actual = compose.onNodeWithTag("candidateShadowMask").captureToImage().toPixelMap()
            assertEquals(reference.width, actual.width)
            assertEquals(reference.height, actual.height)
            val bounds = boundsFor(shapeName)
            val scale = reference.width / 338f
            var maximum = 0
            var total = 0L
            var count = 0
            var shadowPixels = 0
            fun error(a: Color, b: Color): Int = maxOf(
                abs((a.red * 255f).roundToInt() - (b.red * 255f).roundToInt()),
                abs((a.green * 255f).roundToInt() - (b.green * 255f).roundToInt()),
                abs((a.blue * 255f).roundToInt() - (b.blue * 255f).roundToInt()),
                abs((a.alpha * 255f).roundToInt() - (b.alpha * 255f).roundToInt()),
            )
            repeat(reference.height) { y ->
                repeat(reference.width) { x ->
                    val difference = error(reference[x, y], actual[x, y])
                    maximum = maxOf(maximum, difference)
                    // Average only the shape's bounds; empty canvas must not dilute a mismatch.
                    if (x >= bounds.left * scale && x < bounds.right * scale &&
                        y >= bounds.top * scale && y < bounds.bottom * scale
                    ) {
                        total += difference
                        count++
                        if (error(reference[x, y], backings[backingIndex]) > 2) shadowPixels++
                    }
                }
            }
            val mean = total.toDouble() / count
            val label = "halfResolution, $shapeName, dark=${backingIndex >= 2}, backing=$backingIndex: " +
                "meanMaxRGBA=$mean/255, maximum=$maximum/255, shadowPixels=$shadowPixels"
            android.util.Log.i("GlassShadowScaleTest", label)
            if (mean > 1.0 || maximum > 3 || shadowPixels <= count / 20) failures += label
        }
        assertTrue("Half-resolution shadow masks must preserve the native result:\n${failures.joinToString("\n")}",
            failures.isEmpty())
    }

    @Test
    fun lensSourceGpuDropShadowMatchesDefaultAcrossGeometryChanges() {
        val backings = listOf(Color.White, Color(0xFFF0F0F0), Color(0xFF202020), Color(0xFF404040))
        val scenarios = listOf("circle", "capsule", "contact").flatMap { shape ->
            backings.indices.map { backing -> shape to backing }
        }
        val scenarioIndex = mutableStateOf(0)
        val motionTarget = mutableFloatStateOf(0f)
        compose.setContent {
            val (shapeName, backingIndex) = scenarios[scenarioIndex.value]
            val motion = animateFloatAsState(motionTarget.floatValue, tween(96), label = "shadowGeometry").value
            val scale = 1f + 0.12f * motion
            val childWidth = when (shapeName) {
                "circle" -> 56f
                "capsule" -> 184f
                else -> 198f
            }
            val density = LocalDensity.current
            val shape = remember(shapeName, scale, density) {
                when (shapeName) {
                    "circle" -> CircleShape
                    "capsule" -> Capsule()
                    else -> with(density) {
                        fun px(value: Float) = (value * scale).dp.toPx()
                        checkNotNull(liquidContactShape(
                            LiquidContactCapsule(
                                Size(px(136f), px(52f)), Rect(0f, px(2f), px(140f), px(56f)),
                            ),
                            LiquidContactCapsule(
                                Size(px(56f), px(56f)), Rect(px(142f), 0f, px(198f), px(56f)),
                            ),
                            px(8f),
                        ))
                    }
                }
            }
            val shapeProvider = remember(shape) { { shape } }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(false, true).forEach { candidate ->
                    // Shadow.Default extends 48dp past the child, plus its 4dp vertical offset.
                    // Capture the host without clipping that exterior or filling the child.
                    Box(
                        Modifier.size(338.dp, 192.dp)
                            .testTag(if (candidate) "gpuLensShadowHost" else "defaultLensShadowHost"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Canvas(Modifier.matchParentSize()) {
                            assertTrue("Drop shadow comparisons require GPU rendering",
                                drawContext.canvas.nativeCanvas.isHardwareAccelerated)
                            drawRect(backings[backingIndex])
                        }
                        val shadowModifier = if (candidate) {
                            Modifier.navigationLensSourceDropShadow(shapeProvider).drawBackdrop(
                                backdrop = emptyBackdrop(), shape = shapeProvider, effects = {},
                                highlight = null, shadow = null,
                            )
                        } else {
                            Modifier.drawBackdrop(
                                backdrop = emptyBackdrop(), shape = shapeProvider, effects = {},
                                highlight = null,
                            )
                        }
                        Box(
                            Modifier.offset((4.25f * motion).dp, (2.5f * motion).dp)
                                .size((childWidth * scale).dp, (56f * scale).dp)
                                .then(shadowModifier),
                        )
                    }
                }
            }
        }
        fun error(a: Int, b: Int) = maxOf(
            abs((a ushr 24) - (b ushr 24)),
            abs((a ushr 16 and 255) - (b ushr 16 and 255)),
            abs((a ushr 8 and 255) - (b ushr 8 and 255)),
            abs((a and 255) - (b and 255)),
        )
        val failures = mutableListOf<String>()
        scenarios.forEachIndexed { scenario, (shapeName, backingIndex) ->
            compose.runOnIdle { scenarioIndex.value = scenario }
            listOf(0f, 1f, -1f, 0f).forEachIndexed { stage, target ->
                compose.runOnIdle { motionTarget.floatValue = target }
                compose.waitForIdle()
                val reference = compose.onNodeWithTag("defaultLensShadowHost").captureToImage()
                val actual = compose.onNodeWithTag("gpuLensShadowHost").captureToImage()
                assertEquals(reference.width, actual.width)
                assertEquals(reference.height, actual.height)
                val referencePixels = IntArray(reference.width * reference.height)
                val actualPixels = IntArray(referencePixels.size)
                reference.readPixels(referencePixels)
                actual.readPixels(actualPixels)
                val backing = backings[backingIndex].toArgb()
                var maximum = 0
                var maximumIndex = 0
                var total = 0L
                var count = 0
                var referenceShadowPixels = 0
                var aboveTolerance = 0
                referencePixels.indices.forEach { pixel ->
                    val expected = referencePixels[pixel]
                    val observed = actualPixels[pixel]
                    val difference = error(expected, observed)
                    if (difference > maximum) {
                        maximum = difference
                        maximumIndex = pixel
                    }
                    if (difference > 1) aboveTolerance++
                    val referenceShadow = error(expected, backing) > 0
                    if (referenceShadow) referenceShadowPixels++
                    if (referenceShadow || error(observed, backing) > 0) {
                        // The transparent interior and empty host must not dilute shadow error.
                        total += difference
                        count++
                    }
                }
                val mean = if (count > 0) total.toDouble() / count else Double.POSITIVE_INFINITY
                val label = "$shapeName, backing=$backingIndex, stage=$stage, motion=$target: " +
                    "meanMaxRGBA=$mean/255, maximum=$maximum/255, above1=$aboveTolerance, " +
                    "worst=(${maximumIndex % reference.width},${maximumIndex / reference.width}), " +
                    "shadowPixels=$referenceShadowPixels"
                android.util.Log.i("GlassLensShadowGpuTest", label)
                if (mean > 0.25 || maximum > 1 || referenceShadowPixels == 0) failures += label
            }
        }
        assertTrue("The GPU lens-source shadow must preserve Shadow.Default:\n${failures.joinToString("\n")}",
            failures.isEmpty())
    }

    @Test
    fun navigationDropShadowBlurCachePreservesLiveUpdates() {
        data class Stage(
            val name: String,
            val alpha: Float = 0.8f,
            val scale: Offset = Offset(1.08f, 1.06f),
            val translation: Offset = Offset(3.5f, -2.25f),
            val enlarged: Boolean = false,
            val direct: Boolean = false,
            val release: Boolean = false,
        )
        val stages = listOf(
            Stage("initial"),
            Stage("cachedAlpha", alpha = 0.4f),
            Stage("cachedNearOpaque", alpha = 0.99f),
            Stage("cachedTransform", alpha = 1f, scale = Offset(0.92f, 1.12f), translation = Offset(-5.25f, 3.5f)),
            Stage("directResize", alpha = 1f, enlarged = true, direct = true),
            Stage("cachedAfterDirect", enlarged = true),
            Stage("originalSize"),
            Stage("zeroAlpha", alpha = 0f),
            Stage("cachedAfterZero"),
            Stage("released", release = true),
        )
        val circle = mutableStateOf(false)
        val stageIndex = mutableStateOf(0)
        val backing = Color(0xFFF0F0F0)
        lateinit var renderers: List<NavigationDropShadowRenderer>
        compose.setContent {
            renderers = remember { List(2) { NavigationDropShadowRenderer(cacheBlurResult = it == 1) } }
            val transforms = remember { List(2) { GraphicsLayerBlockScope() } }
            DisposableEffect(renderers) { onDispose { renderers.forEach { it.release() } } }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                renderers.forEachIndexed { index, renderer ->
                    Canvas(Modifier.size(338.dp, 176.dp)
                        .testTag(if (index == 0) "legacyBlurShadow" else "cachedBlurShadow")) {
                        assertTrue("Blur cache comparisons require GPU rendering",
                            drawContext.canvas.nativeCanvas.isHardwareAccelerated)
                        drawRect(backing)
                        // Read the stage inside drawing, including release/reuse with identical values.
                        val stage = stages[stageIndex.value]
                        val height = (if (stage.enlarged) 64.dp else 56.dp).toPx()
                        val width = if (circle.value) height else {
                            (if (stage.enlarged) 212.dp else 184.dp).toPx()
                        }
                        val shape = if (circle.value) CircleShape else Capsule()
                        val horizontalInset = (size.width - width) / 2f
                        val verticalInset = (size.height - height) / 2f
                        // Inset changes the renderer's logical size without clipping its exterior.
                        inset(horizontalInset, verticalInset, horizontalInset, verticalInset) {
                            if (stage.direct) {
                                // Drawing new geometry directly must refresh the blur reused by transformed draws.
                                renderer.draw(this, shape)
                            } else {
                                val transform = transforms[index]
                                transform.reset(this, size)
                                transform.alpha = stage.alpha
                                transform.scaleX = stage.scale.x
                                transform.scaleY = stage.scale.y
                                transform.translationX = stage.translation.x.dp.toPx()
                                transform.translationY = stage.translation.y.dp.toPx()
                                renderer.drawTransformed(this, shape, transform)
                            }
                        }
                    }
                }
            }
        }
        fun error(a: Int, b: Int) = maxOf(
            abs((a ushr 24) - (b ushr 24)),
            abs((a ushr 16 and 255) - (b ushr 16 and 255)),
            abs((a ushr 8 and 255) - (b ushr 8 and 255)),
            abs((a and 255) - (b and 255)),
        )
        val failures = mutableListOf<String>()
        listOf(false, true).forEach { isCircle ->
            var previousReference: IntArray? = null
            var previousActual: IntArray? = null
            stages.forEachIndexed { index, stage ->
                compose.runOnIdle {
                    if (stage.release) renderers.forEach { it.release() }
                    circle.value = isCircle
                    stageIndex.value = index
                }
                compose.waitForIdle()
                val reference = compose.onNodeWithTag("legacyBlurShadow").captureToImage()
                val actual = compose.onNodeWithTag("cachedBlurShadow").captureToImage()
                assertEquals(reference.width, actual.width)
                assertEquals(reference.height, actual.height)
                val referencePixels = IntArray(reference.width * reference.height)
                val actualPixels = IntArray(referencePixels.size)
                reference.readPixels(referencePixels)
                actual.readPixels(actualPixels)
                if (stage.name == "cachedAlpha" || stage.name == "cachedNearOpaque" || stage.name == "cachedTransform") {
                    assertTrue("Cached alpha/transform updates must change the reference pixels",
                        !checkNotNull(previousReference).contentEquals(referencePixels))
                    assertTrue("Cached alpha/transform updates must change the candidate pixels",
                        !checkNotNull(previousActual).contentEquals(actualPixels))
                }
                previousReference = referencePixels
                previousActual = actualPixels
                var maximum = 0
                var total = 0L
                var count = 0
                var referenceShadowPixels = 0
                var actualShadowPixels = 0
                val backingPixel = backing.toArgb()
                referencePixels.indices.forEach { pixel ->
                    val difference = error(referencePixels[pixel], actualPixels[pixel])
                    maximum = maxOf(maximum, difference)
                    val referenceShadow = error(referencePixels[pixel], backingPixel) > 0
                    val actualShadow = error(actualPixels[pixel], backingPixel) > 0
                    if (referenceShadow) referenceShadowPixels++
                    if (actualShadow) actualShadowPixels++
                    if (referenceShadow || actualShadow) {
                        total += difference
                        count++
                    }
                }
                val mean = if (count > 0) total.toDouble() / count else 0.0
                val label = "circle=$isCircle, stage=${stage.name}: meanMaxRGBA=$mean/255, " +
                    "maximum=$maximum/255, referencePixels=$referenceShadowPixels, cachedPixels=$actualShadowPixels"
                android.util.Log.i("GlassShadowCacheTest", label)
                val visible = stage.direct || stage.alpha > 0f
                if (mean > 0.25 || maximum > 1 ||
                    (visible && (referenceShadowPixels == 0 || actualShadowPixels == 0)) ||
                    (!visible && (referenceShadowPixels != 0 || actualShadowPixels != 0))
                ) failures += label
            }
        }
        assertTrue("Cached shadow blur must preserve live updates and resource recovery:\n${failures.joinToString("\n")}",
            failures.isEmpty())
    }

    @Test
    fun navigationDropShadowsStayOutsideTransparentShapes() {
        val scenarios = listOf(false, true).flatMap { circle ->
            listOf(1f, 0.25f).map { renderScale -> circle to renderScale }
        }
        val scenarioIndex = mutableStateOf(0)
        compose.setContent {
            val (circle, renderScale) = scenarios[scenarioIndex.value]
            val renderer = remember(renderScale) { NavigationDropShadowRenderer(renderScale) }
            DisposableEffect(renderer) { onDispose { renderer.release() } }
            Canvas(Modifier.size(176.dp, 144.dp).testTag("navigationDropShadow")) {
                assertTrue("The shadow comparison requires GPU rendering", drawContext.canvas.nativeCanvas.isHardwareAccelerated)
                drawRect(Color.White)
                val width = if (circle) 48.dp.toPx() else 112.dp.toPx()
                val bounds = Rect(
                    left = (size.width - width) / 2f,
                    top = 40.dp.toPx(),
                    right = (size.width + width) / 2f,
                    bottom = 88.dp.toPx(),
                )
                // No glass fill covers the shadow: the transparent interior must stay untouched.
                renderer.draw(this, if (circle) CircleShape else Capsule(), bounds)
            }
        }
        scenarios.forEachIndexed { index, (circle, renderScale) ->
            compose.runOnIdle { scenarioIndex.value = index }
            compose.waitForIdle()
            val pixels = compose.onNodeWithTag("navigationDropShadow").captureToImage().toPixelMap()
            fun pixelAt(xDp: Float, yDp: Float) = pixels[
                (xDp * pixels.width / 176f).toInt(),
                (yDp * pixels.height / 144f).toInt(),
            ]
            fun darkness(color: Color) = (1f - (color.red + color.green + color.blue) / 3f) * 255f
            val halfWidth = if (circle) 24f else 56f
            val label = "Drop shadow, circle=$circle, scale=$renderScale"
            listOf(
                Offset(88f, 42f), Offset(88f, 64f), Offset(88f, 86f),
                Offset(88f - halfWidth + 2f, 64f), Offset(88f + halfWidth - 2f, 64f),
            ).forEach { point ->
                val color = pixelAt(point.x, point.y)
                assertEquals("$label transparent interior at $point", 0f, darkness(color), 0.01f)
                assertEquals("$label interior alpha at $point", 1f, color.alpha, 1f / 255f)
            }
            val lowerShadow = darkness(pixelAt(88f, 90f))
            val upperShadow = darkness(pixelAt(88f, 38f))
            // A 2% black shadow has at most five levels of contrast on this white backing.
            assertTrue("$label visible lower shadow: $lowerShadow", lowerShadow >= 2f - 0.01f)
            assertTrue("$label keeps the specified subtle opacity: $lowerShadow", lowerShadow <= 5f + 0.01f)
            assertTrue("$label downward offset: lower=$lowerShadow, upper=$upperShadow", lowerShadow - upperShadow >= 1f - 0.01f)
            assertEquals("$label distant backing", 0f, darkness(pixelAt(88f, 132f)), 1f + 0.01f)
        }
    }

    @Test
    fun navigationInnerShadowsKeepBrightRimsAboveNeutralTint() {
        val lightBackings = listOf(Color.White, Color(0xFFF0F0F0))
        val darkBackings = listOf(Color(0xFF202020), Color(0xFF204080))
        val sizes = listOf(44 to 44, 80 to 48)
        val dark = mutableStateOf(false)
        compose.setContent {
            val isDark = dark.value
            val backingColors = if (isDark) darkBackings else lightBackings
            CompositionLocalProvider(
                LocalGlassColors provides defaultGlassColors(isDark = isDark),
                LocalGlassAppearance provides GlassAppearance(liquidGlassEnabled = false),
            ) {
                Column {
                    backingColors.forEachIndexed { backingIndex, backingColor ->
                        val backdrop = rememberCanvasBackdrop { drawRect(backingColor) }
                        Row(
                            modifier = Modifier.padding(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            sizes.forEachIndexed { shapeIndex, (width, height) ->
                                GlassSurface(
                                    modifier = Modifier.size(width.dp, height.dp)
                                        .testTag("navigationMaterial-$backingIndex-$shapeIndex"),
                                    backdrop = backdrop,
                                    shape = if (shapeIndex == 0) CircleShape else Capsule(),
                                    style = GlassSurfaceStyle.Navigation,
                                ) {}
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()

        val lightReferences = mutableListOf<List<Color>>()
        listOf(false, true, false).forEachIndexed { appearanceIndex, isDark ->
            compose.runOnIdle { dark.value = isDark }
            compose.waitForIdle()
            lightBackings.indices.forEach { backingIndex ->
                sizes.forEachIndexed { shapeIndex, (_, height) ->
                    val pixels = compose.onNodeWithTag("navigationMaterial-$backingIndex-$shapeIndex")
                        .captureToImage().toPixelMap()
                    fun pixelAt(yDp: Int) = pixels[
                        pixels.width / 2,
                        (yDp * pixels.height / height.toFloat()).toInt(),
                    ]
                    fun tone(color: Color) = (color.red + color.green + color.blue) / 3f
                    val topEdge = pixelAt(0)
                    val innerBand = pixelAt(10)
                    val bottomEdge = pixelAt(height - 2)
                    val center = pixelAt(height / 2)
                    val label = "Material $shapeIndex over backing $backingIndex, dark=$isDark"
                    val topContrast = (tone(topEdge) - tone(innerBand)) * 255f
                    val bottomContrast = (tone(bottomEdge) - tone(innerBand)) * 255f
                    val innerTone = tone(innerBand) * 255f
                    val minimumContrast = if (isDark) 3f else 5f
                    // Tint must not flatten the contrast created by the inner shadows above it.
                    assertTrue("$label top rim contrast: $topContrast", topContrast >= minimumContrast)
                    assertTrue("$label bottom rim contrast: $bottomContrast", bottomContrast >= minimumContrast)
                    if (isDark) {
                        if (backingIndex == 0) {
                            assertEquals("$label sampled red", 42f, center.red * 255f, 4f)
                            assertEquals("$label sampled green", 42f, center.green * 255f, 4f)
                            assertEquals("$label sampled blue", 43f, center.blue * 255f, 4f)
                        } else {
                            // The original dark mask attenuates the luminosity sample's channel difference.
                            val blueRedDifference = (center.blue - center.red) * 255f
                            assertEquals("$label luminosity preserves color difference", 86f, blueRedDifference, 3f)
                        }
                    } else {
                        assertTrue("$label inner tone: $innerTone", innerTone < 251f)
                    }
                    assertEquals("$label stays opaque", 1f, innerBand.alpha, 1f / 255f)
                    val samples = listOf(topEdge, innerBand, bottomEdge, center)
                    if (appearanceIndex == 0) {
                        lightReferences += samples
                    } else if (!isDark) {
                        val original = lightReferences[backingIndex * sizes.size + shapeIndex]
                        samples.zip(original).forEachIndexed { sampleIndex, (actual, expected) ->
                            val tolerance = 1f / 255f
                            assertEquals("$label restored red $sampleIndex", expected.red, actual.red, tolerance)
                            assertEquals("$label restored green $sampleIndex", expected.green, actual.green, tolerance)
                            assertEquals("$label restored blue $sampleIndex", expected.blue, actual.blue, tolerance)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun miniPlayerReturnsDirectlyToTheFinalNavigationPosition() {
        lateinit var sheet: GlasmioBottomSheetState
        lateinit var layers: PlayerSheetLayers
        compose.setContent {
            sheet = rememberGlasmioBottomSheetState(
                dismissedBound = 0.dp,
                collapsedBound = 48.dp,
                expandedBound = 400.dp,
                initialAnchor = collapsedAnchor,
            )
            layers = rememberPlayerSheetLayers(sheet)
        }
        compose.runOnIdle {
            layers.updateHost(Rect(0f, 0f, 280f, 400f))
            val original = Rect(12f, 304f, 268f, 352f)
            val compact = Rect(68f, 352f, 212f, 400f)
            val originalArtwork = Rect(20f, 312f, 52f, 344f)
            val compactArtwork = Rect(76f, 360f, 108f, 392f)
            for (openingProgress in listOf(0.4f, 1f)) {
                sheet.snapTo(sheet.collapsedBound)
                layers.updateFrozenBounds()
                layers.miniBounds = original
                layers.miniContentBounds = original
                layers.miniArtworkBounds = originalArtwork
                layers.prepareTransition()
                sheet.expandSoft()
                sheet.snapTo(sheet.collapsedBound + (sheet.expandedBound - sheet.collapsedBound) * openingProgress)
                layers.updateFrozenBounds()

                layers.miniBounds = compact
                layers.miniContentBounds = compact
                layers.miniArtworkBounds = compactArtwork
                val openingFrame = playerContainerRect(layers.sourceContainer, layers.hostBounds, sheet.progress)
                sheet.collapseSoft()
                layers.updateFrozenBounds()
                assertEquals("Reversing the player must stay continuous", openingFrame,
                    playerContainerRect(layers.sourceContainer, layers.hostBounds, sheet.progress))

                sheet.snapTo(sheet.collapsedBound + (sheet.expandedBound - sheet.collapsedBound) * openingProgress / 2)
                layers.updateFrozenBounds()
                val returningFrame = playerContainerRect(layers.sourceContainer, layers.hostBounds, sheet.progress)
                assertEquals("The return goes straight to the final position",
                    if (openingProgress == 1f) 176f else 267.2f, returningFrame.top, 0.001f)
                assertEquals("The content follows its glass", layers.sourceContainer, layers.sourceMiniContent)
                if (openingProgress == 1f) {
                    assertEquals("The interrupted position is no longer a return endpoint", compact, layers.sourceContainer)
                    assertEquals("The cover also uses its final position directly", compactArtwork, layers.sourceArtwork)
                }

                sheet.snapTo(sheet.collapsedBound + 0.01.dp)
                layers.updateFrozenBounds()
                assertTrue("The last moving frame already reaches the bottom", abs(layers.sourceContainer.top - compact.top) < 0.01f)
                assertTrue("The cover has no delayed relocation", abs(layers.sourceArtwork.top - compactArtwork.top) < 0.01f)
                sheet.snapTo(sheet.collapsedBound)
                layers.updateFrozenBounds()
                assertEquals(compact, layers.sourceContainer)
            }
        }
    }

    @Test
    fun openingPlayerSettlesInterruptedMiniNavigationWithoutResumingItAfterReturn() {
        val compact = mutableStateOf(false)
        lateinit var sheet: GlasmioBottomSheetState
        lateinit var progress: State<Float>
        compose.mainClock.autoAdvance = false
        compose.setContent {
            sheet = rememberGlasmioBottomSheetState(
                dismissedBound = 0.dp,
                collapsedBound = 48.dp,
                expandedBound = 400.dp,
                initialAnchor = collapsedAnchor,
            )
            progress = rememberMiniPlayerCompactProgress(compact.value, sheet)
        }
        for (target in listOf(true, false)) {
            compose.runOnIdle { compact.value = target }
            compose.mainClock.advanceTimeBy(64)
            compose.runOnIdle {
                assertTrue("Normal navigation still animates", progress.value > 0f && progress.value < 1f)
                sheet.expand(tween(400))
            }
            compose.mainClock.advanceTimeByFrame()
            compose.runOnIdle {
                assertEquals("Opening settles the hidden mini player at its destination", if (target) 1f else 0f, progress.value, 0f)
                sheet.collapse(tween(100))
            }
            compose.mainClock.advanceTimeBy(160)
            compose.runOnIdle {
                assertTrue(sheet.isCollapsed)
                assertEquals("Returning must not resume interrupted navigation", if (target) 1f else 0f, progress.value, 0f)
            }
        }
    }

    @Test
    fun artworkLayoutSizeResumesUpdatesAfterFrozenTransition() {
        lateinit var sheet: GlasmioBottomSheetState
        lateinit var layers: PlayerSheetLayers
        lateinit var layoutSize: State<IntSize>
        compose.setContent {
            sheet = rememberGlasmioBottomSheetState(
                dismissedBound = 0.dp,
                collapsedBound = 48.dp,
                expandedBound = 400.dp,
                initialAnchor = expandedAnchor,
            )
            layers = rememberPlayerSheetLayers(sheet)
            layoutSize = remember(layers) {
                derivedStateOf(structuralEqualityPolicy()) { layers.targetArtworkLayoutSize }
            }
        }
        compose.runOnIdle {
            val original = PlayerArtworkGeometry(Rect(20f, 24f, 140f, 164f), IntSize(120, 140))
            val updated = original.copy(layoutSize = IntSize(160, 180))
            layers.updateHost(Rect(0f, 0f, 280f, 400f))
            layers.fullArtworkGeometry = { original }
            assertEquals(original.layoutSize, layoutSize.value)

            sheet.beginDrag()
            layers.updateFrozenBounds()
            // Invalidate the live geometry dependency while the frozen branch is active.
            layers.fullArtworkGeometry = { updated }
            assertEquals("Dragging preserves the captured layout", original.layoutSize, layoutSize.value)

            sheet.snapTo(sheet.expandedBound)
            layers.updateFrozenBounds()
            assertEquals("Release restores live layout updates", updated.layoutSize, layoutSize.value)

            layers.fullArtworkGeometry = {
                updated.copy(layoutSize = IntSize(layers.hostBounds.width.roundToInt(), 200))
            }
            assertEquals("Later geometry updates stay observable", IntSize(280, 200), layoutSize.value)
            layers.updateHost(Rect(8f, 12f, 328f, 412f))
            assertEquals("Host resizing stays observable", IntSize(320, 200), layoutSize.value)
        }
    }

    @Test
    fun miniPlayerInnerShadowsSurvivePlayerExpansionAndCollapse() {
        val scenarios = listOf(false, true).flatMap { isDark ->
            listOf(false, true).map { liquidGlassEnabled -> isDark to liquidGlassEnabled }
        }
        val scenarioIndex = mutableStateOf(0)
        var activeSheet: GlasmioBottomSheetState? = null
        val compactMiniProgress = mutableFloatStateOf(0f)
        compose.setContent {
            val scenario = scenarios[scenarioIndex.value]
            key(scenario) {
                val (isDark, liquidGlassEnabled) = scenario
                val backing = if (isDark) Color(0xFF343434) else Color(0xFFB3B3B3)
                val backdrop = rememberCanvasBackdrop { drawRect(backing) }
                val sheet = rememberGlasmioBottomSheetState(
                    dismissedBound = 0.dp,
                    collapsedBound = 48.dp,
                    expandedBound = 400.dp,
                    initialAnchor = collapsedAnchor,
                )
                val layers = rememberPlayerSheetLayers(sheet)
                activeSheet = sheet
                CompositionLocalProvider(
                    LocalGlassColors provides defaultGlassColors(isDark),
                    LocalGlassAppearance provides GlassAppearance(liquidGlassEnabled = liquidGlassEnabled),
                    LocalPlayerSheet provides layers,
                ) {
                    BottomSheet(
                        state = sheet,
                        modifier = Modifier.size(280.dp, 400.dp),
                        collapsedDragHeight = 48.dp,
                        transitionBackdrop = backdrop,
                        collapsedContent = {
                            GlassSurface(
                                modifier = Modifier.size(280.dp, 48.dp)
                                    .testTag("retainedMiniMaterial")
                                    .onGloballyPositioned {
                                        layers.miniBounds = Rect(
                                            it.positionInRoot(),
                                            Size(it.size.width.toFloat(), it.size.height.toFloat()),
                                        )
                                    },
                                backdrop = backdrop,
                                shape = Capsule(),
                                style = GlassSurfaceStyle.Navigation,
                                morphProgress = compactMiniProgress,
                                cancelPressFeedback = sheet.isTransitioning,
                                pressHighlight = remember(layers) { { layers.collapsedPressHighlight } },
                                onVisualBoundsChanged = layers::updateMiniPressHighlight,
                            ) {
                                Box(Modifier.size(280.dp, 48.dp).onGloballyPositioned {
                                    layers.miniLayoutCoordinates = it
                                })
                            }
                        },
                    ) {}
                }
            }
        }
        scenarios.forEachIndexed { index, scenario ->
            compose.runOnIdle { scenarioIndex.value = index }
            compose.waitForIdle()
            val before = compose.onNodeWithTag("retainedMiniMaterial").captureToImage().toPixelMap()
            val sampleYs = listOf(3f, 10f, 16f, 24f, 32f, 38f, 45f)
            val scale = before.width / 280f
            fun tone(color: Color) = (color.red + color.green + color.blue) / 3f
            val initialTones = sampleYs.map { tone(before[before.width / 2, (it * scale).toInt()]) }
            assertTrue(
                "$scenario initially contains visible inner bands",
                initialTones.max() - initialTones.min() >= 3f / 255f,
            )
            repeat(2) { cycle ->
                compose.runOnIdle { checkNotNull(activeSheet).expand(tween(300)) }
                compose.waitForIdle()
                compose.runOnIdle { assertTrue(checkNotNull(activeSheet).isExpanded) }
                compose.runOnIdle { checkNotNull(activeSheet).collapse(tween(300)) }
                compose.waitForIdle()
                compose.runOnIdle { assertTrue(checkNotNull(activeSheet).isCollapsed) }
                val after = compose.onNodeWithTag("retainedMiniMaterial").captureToImage().toPixelMap()
                listOf(70f, 140f, 210f).forEach { xDp ->
                    sampleYs.forEach { yDp ->
                        val x = (xDp * scale).toInt()
                        val y = (yDp * scale).toInt()
                        val expected = before[x, y]
                        val actual = after[x, y]
                        val label = "$scenario after cycle $cycle at ($xDp, $yDp)"
                        val tolerance = 1f / 255f
                        assertEquals("$label red", expected.red, actual.red, tolerance)
                        assertEquals("$label green", expected.green, actual.green, tolerance)
                        assertEquals("$label blue", expected.blue, actual.blue, tolerance)
                        assertEquals("$label alpha", expected.alpha, actual.alpha, tolerance)
                    }
                }
            }
        }
    }

    @Test
    fun morphMaterialMatchesTheOriginalTileCompositionInBothAppearances() {
        val dark = mutableStateOf(false)
        val expanded = mutableStateOf(false)
        val backingIndex = mutableStateOf(0)
        val brightBackings = listOf(Color(0xFFF0F0F0), Color.White)
        val capsuleBounds = Rect(12f, 80f, 132f, 128f)
        val expandedBounds = Rect(8f, 12f, 136f, 196f)
        compose.setContent {
            val isDark = dark.value
            val boundsDp = if (expanded.value) expandedBounds else capsuleBounds
            val cornerRadiusDp = if (expanded.value) 18f else 24f
            val backing = when (backingIndex.value) {
                0 -> if (isDark) Color(0xFF343434) else Color(0xFFB3B3B3)
                else -> brightBackings[backingIndex.value - 1]
            }
            val tint = defaultGlassColors(isDark).container
            val tintMultiplier = 1.25f
            val backdrop = rememberCanvasBackdrop { drawRect(backing) }
            CompositionLocalProvider(
                LocalGlassAppearance provides GlassAppearance(liquidGlassEnabled = false),
            ) {
                val renderer = rememberGlassMorphRenderer(backdrop, active = true, renderScale = 0.25f)
                val referenceShadows = remember { NavigationInnerShadowRenderer(combineMasks = false) }
                val materialPaint = remember { Paint() }
                val coordinates = remember { mutableStateOf<LayoutCoordinates?>(null) }
                DisposableEffect(referenceShadows) {
                    onDispose { referenceShadows.release() }
                }
                Row {
                    listOf(true, false).forEach { actual ->
                        Canvas(
                            Modifier.size(144.dp, 208.dp)
                                .testTag(if (actual) "morphMaterialActual" else "morphMaterialReference")
                                .onGloballyPositioned { if (actual) coordinates.value = it },
                        ) {
                            assertTrue("The material comparison requires GPU rendering", drawContext.canvas.nativeCanvas.isHardwareAccelerated)
                            drawRect(Color(0xFF6C4769))
                            val bounds = Rect(
                                boundsDp.left.dp.toPx(), boundsDp.top.dp.toPx(),
                                boundsDp.right.dp.toPx(), boundsDp.bottom.dp.toPx(),
                            )
                            val radius = cornerRadiusDp.dp.toPx()
                            val shape = ContinuousRoundedRectangle(cornerRadiusDp.dp)
                            if (actual) {
                                val currentCoordinates = coordinates.value ?: return@Canvas
                                val origin = currentCoordinates.positionInRoot()
                                renderer.draw(
                                    scope = this,
                                    coordinates = currentCoordinates,
                                    viewport = Rect(origin, size),
                                    origin = origin,
                                    bounds = bounds,
                                    radius = radius,
                                    shape = shape,
                                    tint = tint,
                                    tintMultiplier = tintMultiplier,
                                    highlightAlpha = 0f,
                                    navigationOutline = false,
                                    navigationMaterial = true,
                                    isDark = isDark,
                                )
                            } else {
                                val outline = shape.createOutline(bounds.size, layoutDirection, this)
                                val path = Path().apply {
                                    when (outline) {
                                        is Outline.Rectangle -> addRect(outline.rect)
                                        is Outline.Rounded -> addRoundRect(outline.roundRect)
                                        is Outline.Generic -> addPath(outline.path)
                                    }
                                    translate(bounds.topLeft)
                                }
                                // A uniform gray sample is unchanged by saturation and blur.
                                // Keep the former saveLayer/tint/shadow order inside every tile.
                                clipGlassShape(bounds, radius, shape, path) {
                                    val canvas = drawContext.canvas
                                    canvas.saveLayer(bounds, materialPaint)
                                    try {
                                        drawRect(backing)
                                        drawNavigationGlassMaterial(tint, isDark, tintMultiplier)
                                        referenceShadows.draw(this, shape, bounds, isDark)
                                    } finally {
                                        canvas.restore()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        val scenarios = listOf(false, true).flatMap { isDark ->
            listOf(false, true).flatMap { isExpanded ->
                (0..brightBackings.size).map { backing -> Triple(isDark, isExpanded, backing) }
            }
        }
        scenarios.forEach { (isDark, isExpanded, backing) ->
            compose.runOnIdle {
                dark.value = isDark
                expanded.value = isExpanded
                backingIndex.value = backing
            }
            compose.waitForIdle()
            val actual = compose.onNodeWithTag("morphMaterialActual").captureToImage().toPixelMap()
            val reference = compose.onNodeWithTag("morphMaterialReference").captureToImage().toPixelMap()
            assertEquals(reference.width, actual.width)
            assertEquals(reference.height, actual.height)
            val bounds = if (isExpanded) expandedBounds else capsuleBounds
            val radius = if (isExpanded) 18f else 24f
            val scale = actual.width / 144f
            val samples = listOf(
                "face" to bounds.center,
                "top rim" to Offset(bounds.center.x, bounds.top + 2f),
                "upper inner band" to Offset(bounds.center.x, bounds.top + 10f),
                "bottom rim" to Offset(bounds.center.x, bounds.bottom - 2f),
                "left rim" to Offset(bounds.left + 2f, bounds.center.y),
                "right rim" to Offset(bounds.right - 2f, bounds.center.y),
                "corner interior" to Offset(bounds.left + radius * 0.75f, bounds.top + radius * 0.75f),
                "outside" to Offset(bounds.left - 2f, bounds.center.y),
            )
            val label = "Morph material, expanded=$isExpanded, dark=$isDark, backing=$backing"
            samples.forEach { (sample, point) ->
                val x = (point.x * scale).toInt()
                val y = (point.y * scale).toInt()
                val expected = reference[x, y]
                val observed = actual[x, y]
                val tolerance = 2f / 255f
                assertEquals("$label $sample red", expected.red, observed.red, tolerance)
                assertEquals("$label $sample green", expected.green, observed.green, tolerance)
                assertEquals("$label $sample blue", expected.blue, observed.blue, tolerance)
                assertEquals("$label $sample alpha", expected.alpha, observed.alpha, tolerance)
            }
            // Check the whole silhouette too; only a small AA fringe may differ.
            var totalError = 0f
            var outliers = 0
            val left = (bounds.left * scale).toInt()
            val top = (bounds.top * scale).toInt()
            val right = (bounds.right * scale).toInt()
            val bottom = (bounds.bottom * scale).toInt()
            for (y in top until bottom) {
                for (x in left until right) {
                    val expected = reference[x, y]
                    val observed = actual[x, y]
                    val error = maxOf(
                        abs(expected.red - observed.red), abs(expected.green - observed.green),
                        abs(expected.blue - observed.blue), abs(expected.alpha - observed.alpha),
                    )
                    totalError += error
                    if (error > 4f / 255f) outliers++
                }
            }
            val area = (right - left) * (bottom - top)
            assertTrue("$label mean pixel error: ${totalError / area}", totalError / area <= 1f / 255f)
            val fringePixels = 4 * ((right - left) + (bottom - top))
            assertTrue("$label differing pixels: $outliers", outliers <= fringePixels)
            fun tone(color: Color) = (color.red + color.green + color.blue) / 3f
            val centerX = (bounds.center.x * scale).toInt()
            val rim = reference[centerX, ((bounds.bottom - 2f) * scale).toInt()]
            val band = reference[centerX, ((bounds.top + 10f) * scale).toInt()]
            assertTrue("$label reference must contain visible inner shadows", tone(rim) - tone(band) >= 3f / 255f)
        }
    }

    private fun visibleTabIconBounds(item: GlasmioTabItem<String>): Rect =
        compose.onAllNodesWithContentDescription(item.label, useUnmergedTree = true)
            .fetchSemanticsNodes().last { node ->
                // The unmerged tree retains the invisible lens source beneath clearing semantics.
                // The visible selected overlay follows its transparent row placeholder.
                generateSequence(node.parent) { it.parent }.none { it.config.isClearingSemantics }
            }.boundsInRoot

    private fun showBar() {
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalGlassAppearance provides glassAppearance.value,
                    LocalLayoutDirection provides barLayoutDirection.value,
                ) {
                    GlasmioNavigationBar(
                        items = items.value,
                        selectedKey = selectedKey.value,
                        onSelected = { key ->
                            selections += key
                            selectedKey.value = key
                        },
                        onExpand = {
                            expansions += 1
                            compactProgress.floatValue = 0f
                        },
                        compactProgress = compactProgress,
                        compactSize = 48.dp,
                        modifier = Modifier.width(320.dp).testTag("bottomBar"),
                        backdrop = emptyBackdrop(),
                    )
                }
            }
        }
        compose.waitForIdle()
    }
}
