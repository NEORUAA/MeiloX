package com.ljyh.mei.ui.glass

import android.graphics.RenderNode
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
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
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.util.lerp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kyant.backdrop.backdrops.emptyBackdrop
import com.kyant.backdrop.backdrops.rememberCanvasBackdrop
import com.kyant.capsule.ContinuousRoundedRectangle
import com.kyant.shapes.Capsule
import com.ljyh.mei.ui.component.sheet.BottomSheet
import com.ljyh.mei.ui.component.sheet.BottomSheetState
import com.ljyh.mei.ui.component.sheet.LocalPlayerSheet
import com.ljyh.mei.ui.component.sheet.PlayerArtworkGeometry
import com.ljyh.mei.ui.component.sheet.PlayerSheetLayers
import com.ljyh.mei.ui.component.sheet.collapsedAnchor
import com.ljyh.mei.ui.component.sheet.expandedAnchor
import com.ljyh.mei.ui.component.sheet.rememberBottomSheetState
import com.ljyh.mei.ui.component.sheet.rememberPlayerSheetLayers
import com.ljyh.mei.ui.liquidglass.InteractiveHighlight
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GlassBottomBarInteractionTest {
    @get:Rule val compose = createComposeRule()

    private val home = GlassTabItem("home", "Home", SfSymbol.House)
    private val radio = GlassTabItem("radio", "Radio", SfSymbol.RadioFilled)
    private val library = GlassTabItem("library", "Library", SfSymbol.MusicNoteList)
    private val items = mutableStateOf(listOf(home, radio, library))
    private val selectedKey = mutableStateOf(home.key)
    private val compactProgress = mutableFloatStateOf(0f)
    private val glassAppearance = mutableStateOf(GlassAppearance(reduceTransparency = true))
    private val barLayoutDirection = mutableStateOf(LayoutDirection.Ltr)
    private val selections = mutableListOf<String>()
    private var expansions = 0

    @Test
    fun outerTabPressAnchorsOnlyTheRefractedSelectionAndOtherwiseScalesTheWholePanel() {
        showBar()

        fun iconBounds(): Map<String, Rect> = items.value.associate { item ->
            // The separate selected item is painted after its transparent row placeholder.
            item.key to compose.onAllNodesWithContentDescription(item.label, useUnmergedTree = true)
                .fetchSemanticsNodes().last().boundsInRoot
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
                        // The 64dp panel expands by 16dp, independently of density.
                        val scale = 1f + barBounds.height / (4f * barBounds.width)
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

        fun iconBounds(item: GlassTabItem<String>): Rect =
            compose.onAllNodesWithContentDescription(item.label, useUnmergedTree = true)
                .fetchSemanticsNodes().last().boundsInRoot

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
                    val outgoingBefore = iconBounds(outgoing)
                    val incomingBefore = iconBounds(incoming)
                    val middleBefore = iconBounds(radio)
                    val scale = 1f + barBounds.height / (4f * barBounds.width)
                    val startX = outgoingBefore.center.x - barBounds.left
                    val toRight = incomingBefore.center.x > outgoingBefore.center.x
                    val destinationX = if (toRight) 2f * barBounds.width else -barBounds.width
                    compose.mainClock.autoAdvance = false
                    bar.performTouchInput { down(Offset(startX, center.y)) }
                    compose.mainClock.advanceTimeBy(500L)

                    fun dragAwayAndAssert() {
                        bar.performTouchInput { moveTo(Offset(destinationX, center.y)) }
                        compose.mainClock.advanceTimeBy(700L)
                        val commonShift = iconBounds(radio).center.x - middleBefore.center.x
                        val expectedOutgoing = barBounds.center.x +
                            (outgoingBefore.center.x - barBounds.center.x) * scale + commonShift
                        assertEquals("Departed icon follows the panel while held", expectedOutgoing, iconBounds(outgoing).center.x, 0.75f)
                        assertEquals("Incoming icon is already centered before commit", incomingBefore.center.x + commonShift, iconBounds(incoming).center.x, 0.75f)
                        compose.runOnIdle {
                            assertEquals(outgoing.key, selectedKey.value)
                            assertTrue("Dragging must not navigate", selections.isEmpty())
                        }
                    }

                    dragAwayAndAssert()
                    bar.performTouchInput { moveTo(Offset(startX, center.y)) }
                    compose.mainClock.advanceTimeBy(700L)
                    val commonShift = iconBounds(radio).center.x - middleBefore.center.x
                    assertEquals("Reversing reanchors the original icon", outgoingBefore.center.x + commonShift, iconBounds(outgoing).center.x, 0.75f)
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
                        fun center(item: GlassTabItem<String>): Offset {
                            val icon = compose.onAllNodesWithContentDescription(item.label, useUnmergedTree = true)
                                .fetchSemanticsNodes().last().boundsInRoot
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
    fun opaqueLensSourceTintPreservesSelectedTabPixelsWithoutInnerFilters() {
        val progress = mutableFloatStateOf(0f)
        val press = mutableFloatStateOf(0f)
        val paletteIndex = mutableStateOf(0)
        val palettes = listOf(
            defaultGlassColors(false).copy(accent = Color(0xFFFF3B30), content = Color(0xFF263648)),
            defaultGlassColors(true).copy(accent = Color(0xFF51B6FF), content = Color.White),
            defaultGlassColors(false).copy(accent = Color(0xFFFF3B30).copy(alpha = 0.6f)),
            defaultGlassColors(false).copy(content = Color(0xFF263648).copy(alpha = 0.55f)),
        )
        val backing = Color(0xFF2E233B)
        compose.setContent {
            val colors = palettes[paletteIndex.value]
            val selectedFilter = remember(colors) {
                derivedStateOf {
                    val compactTint = androidx.compose.ui.graphics.lerp(colors.accent, colors.content, progress.floatValue)
                    ColorFilter.tint(androidx.compose.ui.graphics.lerp(compactTint, colors.content, press.floatValue))
                }
            }
            val optimizedFilter = navigationLensSourceColorFilter(colors, selectedFilter)
            val opaque = colors.accent.alpha == 1f && colors.content.alpha == 1f
            assertTrue("Only fully opaque source colors can skip the inner filter",
                if (opaque) optimizedFilter == null else optimizedFilter === selectedFilter)
            val sourceScale = remember {
                { lerp(1f, 1.2f, press.floatValue) * (1f - progress.floatValue).coerceAtLeast(0.82f) }
            }
            val labelAlpha = remember { { (1f - progress.floatValue * 1.35f).coerceIn(0f, 1f) } }
            val lateScale = remember {
                { lerp(1f, (22f / 28f) / 0.82f, compactIconLateProgress(progress.floatValue)) }
            }
            val density = LocalDensity.current
            CompositionLocalProvider(LocalGlassColors provides colors, LocalLiquidTabScale provides sourceScale) {
                MaterialTheme {
                    Row {
                        listOf(false, true).forEachIndexed { index, optimized ->
                            if (index > 0) Spacer(Modifier.width(16.dp))
                            Box(
                                Modifier.size(160.dp, 80.dp).background(backing)
                                    .testTag(if (optimized) "optimizedLensSourceTab" else "referenceLensSourceTab"),
                                contentAlignment = Alignment.Center,
                            ) {
                                val width = lerp(160f, 48f, progress.floatValue).dp - 8.dp
                                Box(
                                    Modifier.width(width).height(56.dp).graphicsLayer {
                                        colorFilter = ColorFilter.tint(colors.accent)
                                    },
                                ) {
                                    MorphingSelectedNavigationItem(
                                        item = library,
                                        compactProgress = progress,
                                        fullWidthPx = with(density) { 160.dp.toPx() },
                                        compactWidthPx = with(density) { 48.dp.toPx() },
                                        itemCount = 3,
                                        selectedIndex = 1,
                                        isLtr = true,
                                        horizontalInsetPx = with(density) { 4.dp.toPx() },
                                        selectedContentLateScale = lateScale,
                                        labelAlpha = labelAlpha,
                                        selectedIconColorFilter = if (optimized) optimizedFilter else selectedFilter,
                                        contentColor = colors.content,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        palettes.indices.forEach { palette ->
            listOf(0f, 0.5f, 0.99f).forEach { compact ->
                listOf(0f, 0.5f, 0.99f).forEach { pressed ->
                    compose.runOnIdle {
                        paletteIndex.value = palette
                        progress.floatValue = compact
                        press.floatValue = pressed
                    }
                    compose.waitForIdle()
                    val reference = compose.onNodeWithTag("referenceLensSourceTab").captureToImage().toPixelMap()
                    val actual = compose.onNodeWithTag("optimizedLensSourceTab").captureToImage().toPixelMap()
                    assertEquals(reference.width, actual.width)
                    assertEquals(reference.height, actual.height)
                    var maximum = 0
                    var total = 0L
                    var count = 0
                    var contentPixels = 0
                    var labelPixels = 0
                    var maximumPoint = Offset.Zero
                    var maximumExpected = Color.Transparent
                    var maximumObserved = Color.Transparent
                    fun compare(expected: Float, observed: Float, x: Int, y: Int) {
                        val error = abs((expected * 255f).roundToInt() - (observed * 255f).roundToInt())
                        if (error > maximum) {
                            maximum = error
                            maximumPoint = Offset(x.toFloat(), y.toFloat())
                            maximumExpected = reference[x, y]
                            maximumObserved = actual[x, y]
                        }
                        total += error
                        count++
                    }
                    repeat(reference.height) { y ->
                        repeat(reference.width) { x ->
                            val expected = reference[x, y]
                            val observed = actual[x, y]
                            compare(expected.red, observed.red, x, y)
                            compare(expected.green, observed.green, x, y)
                            compare(expected.blue, observed.blue, x, y)
                            compare(expected.alpha, observed.alpha, x, y)
                            val contentDifference = maxOf(
                                abs(expected.red - backing.red), abs(expected.green - backing.green),
                                abs(expected.blue - backing.blue),
                            )
                            if (contentDifference > 8f / 255f) {
                                contentPixels++
                                if (y > reference.height * 0.6f) labelPixels++
                            }
                        }
                    }
                    val label = "Lens source tab: palette=$palette, compact=$compact, press=$pressed"
                    assertTrue("$label must contain the real selected icon", contentPixels > 20)
                    if (compact == 0f) assertTrue("$label must contain the visible label", labelPixels > 10)
                    assertTrue(
                        "$label maximum RGBA error: $maximum at $maximumPoint, " +
                            "expected=$maximumExpected, actual=$maximumObserved",
                        maximum <= 2,
                    )
                    assertTrue("$label mean RGBA error: ${total.toDouble() / count}", total.toDouble() / count <= 1.0)
                }
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
    fun artworkLayoutSizeResumesUpdatesAfterFrozenTransition() {
        lateinit var sheet: BottomSheetState
        lateinit var layers: PlayerSheetLayers
        lateinit var layoutSize: State<IntSize>
        compose.setContent {
            sheet = rememberBottomSheetState(
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
        var activeSheet: BottomSheetState? = null
        val compactMiniProgress = mutableFloatStateOf(0f)
        compose.setContent {
            val scenario = scenarios[scenarioIndex.value]
            key(scenario) {
                val (isDark, liquidGlassEnabled) = scenario
                val backing = if (isDark) Color(0xFF343434) else Color(0xFFB3B3B3)
                val backdrop = rememberCanvasBackdrop { drawRect(backing) }
                val sheet = rememberBottomSheetState(
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

    private fun showBar() {
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalGlassAppearance provides glassAppearance.value,
                    LocalLayoutDirection provides barLayoutDirection.value,
                ) {
                    GlassBottomBar(
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
