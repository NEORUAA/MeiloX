package com.ljyh.mei.ui.glass

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import com.kyant.backdrop.backdrops.emptyBackdrop
import com.kyant.backdrop.backdrops.rememberCanvasBackdrop
import com.kyant.shapes.Capsule
import com.ljyh.mei.ui.liquidglass.PressHighlightRenderer
import com.ljyh.mei.ui.screen.main.library.component.LibraryCollectionRefresh
import com.ljyh.mei.ui.theme.MusicTheme
import com.ljyh.mei.utils.color.ColorExtractionUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GlassControlsInteractionTest {
    @get:Rule val compose = createComposeRule()

    private val checked = mutableStateOf(false)
    private val enabled = mutableStateOf(true)
    private val revision = mutableStateOf(0)
    private val calls = mutableListOf<Pair<Int, Boolean>>()
    private val listState = LazyListState()
    private var parentClicks = 0

    @Test
    fun artistSubmenuKeepsOneWindowAndClosesWithItsParent() {
        val visible = mutableStateOf(true)
        var artistClicks = 0
        var dismissals = 0
        compose.setContent {
            MusicTheme(seedColor = Color.Red, isDark = false) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    if (visible.value) IosCascadingMenu(
                        anchorBounds = Rect(24f, 160f, 224f, 204f),
                        title = "Player destinations",
                        expandedDescription = "Expanded",
                        collapsedDescription = "Collapsed",
                        backdrop = emptyBackdrop(),
                        onDismiss = { dismissals++; visible.value = false },
                        items = listOf(
                            IosCascadingMenuItem(
                                title = "Go to artist",
                                subtitle = "Artist A, Artist B",
                                children = listOf(
                                    IosCascadingMenuItem("Artist A", onClick = { artistClicks++ }),
                                    IosCascadingMenuItem("Artist B"),
                                ),
                            ),
                            IosCascadingMenuItem("Go to album"),
                        ),
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        val header = hasText("Go to artist") and hasClickAction()
        try {
            repeat(2) {
                val before = compose.onNode(header).getUnclippedBoundsInRoot()
                compose.onNode(header).performClick()
                compose.mainClock.advanceTimeByFrame()
                val after = compose.onNode(header).getUnclippedBoundsInRoot()
                assertEquals("Header handoff keeps its horizontal origin", before.left.value, after.left.value, 1f)
                assertEquals("Header handoff keeps its vertical origin", before.top.value, after.top.value, 1f)
                repeat(12) {
                    compose.onAllNodes(isPopup()).assertCountEquals(1)
                    compose.onNode(header).assertIsDisplayed()
                    compose.mainClock.advanceTimeByFrame()
                }
                compose.mainClock.advanceTimeBy(1_000)
                if (it == 0) {
                    compose.onNode(header).performClick()
                    compose.mainClock.advanceTimeBy(1_000)
                    compose.onNodeWithText("Artist A").assertDoesNotExist()
                }
            }
            compose.onNodeWithText("Artist A").performClick()
            compose.mainClock.advanceTimeBy(1_000)
            compose.onAllNodes(isPopup()).assertCountEquals(0)
            compose.mainClock.advanceTimeByFrame()
            compose.runOnIdle {
                assertEquals(1, artistClicks)
                assertEquals(1, dismissals)
            }
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test
    fun musicThemeKeepsStaticSeedsAndInheritsDynamicContrastOnlyWhenEnabled() {
        val seed = mutableStateOf(Color.Red)
        val dark = mutableStateOf(false)
        val useDynamicAccent = mutableStateOf(false)
        var defaultScheme: ColorScheme? = null
        var parentScheme: ColorScheme? = null
        var childScheme: ColorScheme? = null
        compose.setContent {
            MusicTheme(seedColor = Color.Red, isDark = dark.value) {
                val scheme = MaterialTheme.colorScheme
                SideEffect { defaultScheme = scheme }
            }
            MusicTheme(seedColor = seed.value, isDark = dark.value, dynamicAccent = useDynamicAccent.value) {
                val scheme = MaterialTheme.colorScheme
                SideEffect { parentScheme = scheme }
                MusicTheme(seedColor = seed.value, isDark = !dark.value) {
                    val nestedScheme = MaterialTheme.colorScheme
                    SideEffect { childScheme = nestedScheme }
                }
            }
        }
        compose.waitForIdle()

        fun assertReadable(foreground: Color, background: Color) {
            val foregroundLuminance = foreground.luminance().toDouble()
            val backgroundLuminance = background.luminance().toDouble()
            val contrast = (maxOf(foregroundLuminance, backgroundLuminance) + 0.05) /
                (minOf(foregroundLuminance, backgroundLuminance) + 0.05)
            assertTrue("Dynamic theme contrast was $contrast", contrast >= 4.5)
        }
        fun assertScheme(scheme: ColorScheme, color: Color, isDark: Boolean, dynamicAccent: Boolean) {
            val expected = if (dynamicAccent) ColorExtractionUtils.themeAccentColor(color, isDark) else color
            assertEquals("Primary must retain the selected theme policy", expected.toArgb(), scheme.primary.toArgb())
            val expectedContent = if (dynamicAccent) ColorExtractionUtils.accentContentColor(expected) else Color.White
            assertEquals(expectedContent, scheme.onPrimary)
            if (dynamicAccent) {
                val backgrounds = if (isDark) listOf(Color.Black, Color(0xFF3A3A3C))
                    else listOf(Color.White, Color(0xFFF2F2F7))
                backgrounds.forEach { assertReadable(scheme.primary, it) }
                assertReadable(scheme.onPrimary, scheme.primary)
            }
        }

        try {
            compose.mainClock.autoAdvance = false
            for (color in listOf(Color.Red, Color(0x8034E1FF), Color(0xFF170A20))) {
                for (isDark in listOf(false, true)) {
                    for (dynamicAccent in listOf(false, true, false)) {
                        compose.runOnIdle {
                            seed.value = color
                            dark.value = isDark
                            useDynamicAccent.value = dynamicAccent
                        }
                        compose.mainClock.advanceTimeBy(1000L)
                        compose.runOnIdle {
                            assertScheme(requireNotNull(defaultScheme), Color.Red, isDark, false)
                            assertScheme(requireNotNull(parentScheme), color, isDark, dynamicAccent)
                            assertScheme(requireNotNull(childScheme), color, !isDark, dynamicAccent)
                        }
                    }
                }
            }
            compose.runOnIdle {
                seed.value = Color(0xFF170A20)
                dark.value = true
                useDynamicAccent.value = true
            }
            compose.mainClock.advanceTimeBy(1000L)
            compose.runOnIdle {
                seed.value = Color.Red
                useDynamicAccent.value = false
            }
            // The first frame after changing color sources must not expose the old raw cover.
            compose.mainClock.advanceTimeByFrame()
            compose.runOnIdle {
                assertScheme(requireNotNull(parentScheme), Color.Red, isDark = true, dynamicAccent = false)
                assertScheme(requireNotNull(childScheme), Color.Red, isDark = false, dynamicAccent = false)
            }
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test
    fun segmentedHorizontalIntentLocksOutLaterVerticalDrift() {
        val selection = mutableStateOf(0)
        val selections = mutableListOf<Int>()
        val state = LazyListState(1)
        showScrollableSegments(selection, selections, state)
        val host = compose.onNodeWithTag("scrollHost")
        val hostBounds = host.fetchSemanticsNode().boundsInRoot
        val first = compose.onNodeWithText("Tab 0").fetchSemanticsNode().boundsInRoot.center - hostBounds.topLeft
        val last = compose.onNodeWithText("Tab 2").fetchSemanticsNode().boundsInRoot.center - hostBounds.topLeft
        host.performTouchInput { down(first) }
        host.performTouchInput { moveTo(last + Offset(0f, 24f)) }
        compose.waitForIdle()
        host.performTouchInput { moveBy(Offset(0f, -120f)) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, state.firstVisibleItemIndex)
            assertEquals(0, state.firstVisibleItemScrollOffset)
            assertTrue(selections.isEmpty())
        }
        host.performTouchInput { up() }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(listOf(2), selections) }
    }

    @Test
    fun segmentedCancellationAndVerticalIntentNeverCommitSelection() {
        val selection = mutableStateOf(0)
        val selections = mutableListOf<Int>()
        val state = LazyListState(1)
        val position = Animatable(0f, 0.001f)
        showScrollableSegments(selection, selections, state, position)
        val host = compose.onNodeWithTag("scrollHost")
        val hostBounds = host.fetchSemanticsNode().boundsInRoot
        val first = compose.onNodeWithText("Tab 0").fetchSemanticsNode().boundsInRoot.center - hostBounds.topLeft
        val last = compose.onNodeWithText("Tab 2").fetchSemanticsNode().boundsInRoot.center - hostBounds.topLeft
        host.performTouchInput { down(first); moveTo(last) }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(position.value > 1f) }
        host.performTouchInput { cancel() }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(selections.isEmpty())
            assertEquals(0f, position.value, 0.001f)
        }
        host.performTouchInput { down(first); moveBy(Offset(2f, -120f)) }
        compose.waitForIdle()
        host.performTouchInput { up() }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(state.firstVisibleItemIndex > 1 || state.firstVisibleItemScrollOffset > 0)
            assertTrue(selections.isEmpty())
            assertEquals(0, selection.value)
            assertEquals(0f, position.value, 0.001f)
        }
    }

    private fun showScrollableSegments(
        selection: androidx.compose.runtime.MutableState<Int>,
        selections: MutableList<Int>,
        state: LazyListState,
        position: Animatable<Float, androidx.compose.animation.core.AnimationVector1D>? = null,
    ) {
        compose.setContent {
            MaterialTheme {
                LazyColumn(Modifier.width(320.dp).height(240.dp).testTag("scrollHost"), state = state) {
                    item { Box(Modifier.fillMaxWidth().height(48.dp)) }
                    item("segments") {
                        GlassSegmentedControl(
                            items = List(3) { it to "Tab $it" },
                            selected = selection.value,
                            onSelected = { selection.value = it; selections += it },
                            modifier = Modifier.fillMaxWidth(),
                            backdrop = emptyBackdrop(),
                            positionAnimation = position,
                        )
                    }
                    items(30) { Box(Modifier.fillMaxWidth().height(48.dp)) }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    @SdkSuppress(minSdkVersion = 33)
    fun widePressLightCoversMoreOfTheSelectionWithoutChangingItsHeight() {
        compose.setContent {
            Column {
                listOf(false, true).forEach { wide ->
                    val renderer = remember(wide) { PressHighlightRenderer() }
                    Canvas(Modifier.width(200.dp).height(32.dp).testTag(if (wide) "wideLight" else "normalLight")) {
                        drawRect(Color.Black)
                        renderer.draw(
                            this, 1f, Offset(size.width / 2f, size.height / 2f),
                            horizontalRadius = if (wide) size.width * 0.65f else null,
                        )
                    }
                }
            }
        }
        fun outsideTheOldLight(tag: String): Color {
            val image = compose.onNodeWithTag(tag).captureToImage().toPixelMap()
            return image[image.width * 4 / 5, image.height / 2]
        }
        assertTrue(outsideTheOldLight("wideLight").red > outsideTheOldLight("normalLight").red + 0.10f)
    }

    @Test
    fun categoryPositionContinuesSlidingWhenThePageHostIsReplaced() {
        val selected = mutableStateOf(0)
        val position = Animatable(0f, 0.001f)
        compose.setContent {
            MaterialTheme {
                key(selected.value) {
                    GlassSegmentedControl(
                        items = List(6) { it to "Tab $it" },
                        selected = selected.value,
                        onSelected = { selected.value = it },
                        modifier = Modifier.width(320.dp),
                        backdrop = emptyBackdrop(),
                        positionAnimation = position,
                    )
                }
            }
        }
        compose.waitForIdle()
        try {
            compose.mainClock.autoAdvance = false
            compose.onNodeWithText("Tab 5").performClick()
            compose.mainClock.advanceTimeBy(64)
            compose.runOnIdle { assertTrue(position.value > 0f && position.value < 5f) }
            compose.mainClock.advanceTimeBy(1000)
            compose.runOnIdle { assertEquals(5f, position.value, 0.001f) }
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test
    fun categoryRefreshChangesRetainTheContentOwnerAndListOffset() {
        val selected = mutableStateOf(0)
        val state = LazyListState(1, 16)
        var disposals = 0
        compose.setContent {
            MaterialTheme {
                LibraryCollectionRefresh(
                    enabled = selected.value == 1,
                    isRefreshing = false,
                    topPadding = 0.dp,
                    onRefresh = {},
                ) {
                    DisposableEffect(Unit) { onDispose { disposals++ } }
                    LazyColumn(Modifier.width(320.dp).height(240.dp), state = state) {
                        items(2) { Box(Modifier.fillMaxWidth().height(48.dp)) }
                        item("library-pages") {
                            GlassSegmentedControl(
                                items = listOf(0 to "Songs", 1 to "Playlists"),
                                selected = selected.value,
                                onSelected = { selected.value = it },
                                modifier = Modifier.fillMaxWidth(),
                                backdrop = emptyBackdrop(),
                            )
                        }
                        items(30) { Box(Modifier.fillMaxWidth().height(48.dp)) }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Playlists").performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(0, disposals)
            assertEquals(1, state.firstVisibleItemIndex)
            assertEquals(16, state.firstVisibleItemScrollOffset)
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 33)
    fun segmentedFullPressRemovesTheOpaqueRestingPillFromTheLens() {
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalGlassColors provides defaultGlassColors(isDark = true),
                    LocalGlassAppearance provides GlassAppearance(),
                ) {
                    Box(
                        Modifier.width(320.dp).height(72.dp).background(Color.Black).testTag("scene"),
                        contentAlignment = Alignment.Center,
                    ) {
                        GlassSegmentedControl(
                            items = listOf(0 to "First", 1 to "Second"),
                            selected = 0,
                            onSelected = {},
                            modifier = Modifier.width(280.dp).testTag("segments"),
                            backdrop = rememberCanvasBackdrop { drawRect(Color.Black) },
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        val scene = compose.onNodeWithTag("scene")
        val control = compose.onNodeWithTag("segments")
        val sceneBounds = scene.fetchSemanticsNode().boundsInRoot
        val bounds = control.fetchSemanticsNode().boundsInRoot
        val sampleX = (bounds.left - sceneBounds.left + bounds.width * 0.38f).toInt()
        val sampleY = (bounds.center.y - sceneBounds.top).toInt()
        val resting = scene.captureToImage().toPixelMap()[sampleX, sampleY]
        try {
            compose.mainClock.autoAdvance = false
            control.performTouchInput { down(Offset(width * 0.25f, center.y)) }
            compose.mainClock.advanceTimeBy(1000)
            val pressed = scene.captureToImage().toPixelMap()[sampleX, sampleY]
            assertTrue("The original opaque pill must disappear during a full press", pressed.red < resting.red - 0.08f)
            control.performTouchInput { up() }
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test
    fun segmentedHeldDragCommitsOnlyOnReleaseAfterWidthAndDirectionChanges() {
        val count = mutableStateOf(3)
        val selection = mutableStateOf(0)
        val direction = mutableStateOf(LayoutDirection.Ltr)
        val width = mutableStateOf(280.dp)
        val appearance = mutableStateOf(GlassAppearance())
        val selections = mutableListOf<Int>()
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalLayoutDirection provides direction.value,
                    LocalGlassAppearance provides appearance.value,
                ) {
                    GlassSegmentedControl(
                        items = List(count.value) { it to "Tab $it" },
                        selected = selection.value,
                        onSelected = { selection.value = it; selections += it },
                        modifier = Modifier.width(width.value).testTag("segments"),
                        backdrop = emptyBackdrop(),
                    )
                }
            }
        }
        try {
            listOf(GlassAppearance(), GlassAppearance(liquidGlassEnabled = false), GlassAppearance(reduceTransparency = true))
                .forEach { mode ->
                    listOf(2, 3, 6).forEach { itemCount ->
                        listOf(LayoutDirection.Ltr, LayoutDirection.Rtl).forEach { layoutDirection ->
                            compose.runOnIdle {
                                count.value = itemCount
                                selection.value = 0
                                direction.value = layoutDirection
                                appearance.value = mode
                                width.value = 280.dp
                                selections.clear()
                            }
                            compose.waitForIdle()
                            compose.runOnIdle { width.value = 320.dp }
                            compose.waitForIdle()
                            val control = compose.onNodeWithTag("segments")
                            val bounds = control.fetchSemanticsNode().boundsInRoot
                            val first = compose.onNodeWithText("Tab 0").fetchSemanticsNode().boundsInRoot.center - bounds.topLeft
                            val last = compose.onNodeWithText("Tab ${itemCount - 1}").fetchSemanticsNode().boundsInRoot.center - bounds.topLeft
                            compose.mainClock.autoAdvance = false
                            control.performTouchInput { down(first) }
                            compose.mainClock.advanceTimeBy(1000)
                            control.performTouchInput { moveTo(last) }
                            compose.mainClock.advanceTimeBy(1000)
                            compose.runOnIdle { assertTrue(selections.isEmpty()) }
                            control.performTouchInput { moveTo(first) }
                            compose.mainClock.advanceTimeBy(1000)
                            compose.runOnIdle { assertTrue(selections.isEmpty()) }
                            control.performTouchInput { moveTo(last); up() }
                            compose.mainClock.autoAdvance = true
                            compose.waitForIdle()
                            compose.runOnIdle { assertEquals(listOf(itemCount - 1), selections) }
                        }
                    }
                }
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test
    fun externalToggleUpdatesKeepTheRestingThumbHeightWhileTouchStillExpandsIt() {
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalGlassColors provides defaultGlassColors(isDark = true),
                    LocalGlassAppearance provides GlassAppearance(reduceTransparency = true),
                ) {
                    Box(
                        Modifier.size(120.dp, 72.dp).background(Color.Black).testTag("toggleScene"),
                        contentAlignment = Alignment.Center,
                    ) {
                        GlassToggle(checked.value, { checked.value = it }, Modifier.testTag("toggle"), backdrop = emptyBackdrop())
                    }
                }
            }
        }
        compose.waitForIdle()
        fun thumbHeight(): Int {
            val image = compose.onNodeWithTag("toggleScene").captureToImage().toPixelMap()
            val whiteRows = (0 until image.height).filter { y ->
                (0 until image.width).any { x ->
                    val pixel = image[x, y]
                    pixel.red > 0.96f && pixel.green > 0.96f && pixel.blue > 0.96f
                }
            }
            return whiteRows.last() - whiteRows.first() + 1
        }
        val restingHeight = thumbHeight()
        try {
            compose.mainClock.autoAdvance = false
            listOf(true, false).forEach { target ->
                compose.runOnIdle { checked.value = target }
                repeat(6) {
                    compose.mainClock.advanceTimeBy(32L)
                    val restoredHeight = thumbHeight()
                    assertTrue(
                        "Restored state only moves the thumb: target=$target, resting=$restingHeight, restored=$restoredHeight",
                        kotlin.math.abs(restoredHeight - restingHeight) <= 2,
                    )
                }
                compose.mainClock.advanceTimeBy(1000L)
            }
            // Finish queued frame/draw work before starting a separate physical press.
            // Keep the pointer down while the test clock drives the press to idle.
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()
            val settledHeight = thumbHeight()
            val toggleBounds = toggle().fetchSemanticsNode().boundsInRoot
            toggle().performTouchInput { down(center) }
            compose.waitForIdle()
            val pressedHeight = thumbHeight()
            // This measures the bright core, not the full scaled outline: pressing also
            // adds an inner shadow. The 24dp core must visibly grow to the 28dp track.
            val trackHeight = toggleBounds.height.toInt()
            assertTrue(
                "A real held touch still expands the thumb: resting=$restingHeight, settled=$settledHeight, pressed=$pressedHeight, bounds=$toggleBounds",
                pressedHeight > restingHeight + 2 && pressedHeight >= trackHeight - 2,
            )
            toggle().performTouchInput { cancel() }
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test
    fun toggleTapsUseLatestStateAndCallbackWithoutClickingTheParent() {
        showToggle()
        toggle().performTouchInput { down(center); up() }
        compose.runOnIdle {
            assertTrue(checked.value)
            revision.value = 1
        }
        toggle().performTouchInput { down(center); up() }
        toggle().performTouchInput { down(center); up() }

        compose.runOnIdle {
            assertTrue(checked.value)
            assertEquals(listOf(0 to true, 1 to false, 1 to true), calls)
            assertEquals(0, parentClicks)
        }
    }

    @Test
    fun toggleDragUsesFinalPositionAndDoesNotScrollTheParentList() {
        showToggle()
        toggle().performTouchInput {
            down(center)
            moveBy(Offset(width * 0.45f, -height * 3f))
            up()
        }
        compose.runOnIdle { assertTrue(checked.value) }
        toggle().performTouchInput {
            down(center)
            moveBy(Offset(-width * 0.45f, height * 3f))
            up()
        }

        compose.runOnIdle {
            assertFalse(checked.value)
            assertEquals(listOf(0 to true, 0 to false), calls)
            assertEquals(0, parentClicks)
            assertEquals(0, listState.firstVisibleItemIndex)
            assertEquals(0, listState.firstVisibleItemScrollOffset)
        }
    }

    @Test
    fun canceledAndDisabledToggleGesturesNeverCommit() {
        showToggle()
        toggle().performTouchInput {
            down(center)
            moveBy(Offset(width * 0.45f, 0f))
            cancel()
        }
        compose.runOnIdle {
            assertFalse(checked.value)
            assertTrue(calls.isEmpty())
            enabled.value = false
        }
        toggle().performTouchInput { down(center); up() }
        toggle().performTouchInput {
            down(center)
            moveBy(Offset(width * 0.45f, 0f))
            up()
        }
        compose.runOnIdle {
            assertFalse(checked.value)
            assertTrue(calls.isEmpty())
            assertEquals(0, parentClicks)
        }
    }

    @Test
    fun disablingAnActiveDragCancelsItAndExternalStateStillControlsTheToggle() {
        showToggle()
        toggle().performTouchInput { down(center); moveBy(Offset(width * 0.45f, 0f)) }
        compose.runOnIdle { enabled.value = false }
        toggle().performTouchInput { up() }
        compose.runOnIdle {
            assertFalse(checked.value)
            assertTrue(calls.isEmpty())
            checked.value = true
            enabled.value = true
        }
        toggle().performTouchInput { down(center); up() }
        compose.runOnIdle {
            assertFalse(checked.value)
            assertEquals(listOf(0 to false), calls)
        }
    }

    @Test
    fun toggleSemanticsExposeStateAndRespectDisabledActions() {
        showToggle()
        toggle()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
            .assertIsOff()
            .assertHasClickAction()
            .performClick()
            .assertIsOn()
        compose.runOnIdle { enabled.value = false }
        toggle().assertIsNotEnabled().performSemanticsAction(SemanticsActions.OnClick) { action ->
            assertFalse(action())
        }
        compose.runOnIdle {
            assertTrue(checked.value)
            assertEquals(listOf(0 to true), calls)
            assertEquals(0, parentClicks)
        }
    }

    @Test
    fun toolbarGroupingKeepsActionsIndependentAndSearchExpansionAnchored() {
        val actionStyle = mutableStateOf(IosToolbarActionStyle.Merged)
        val actionsEnabled = mutableStateOf(true)
        val searchWidth = mutableStateOf(44.dp)
        val separation = mutableStateOf<Float?>(null)
        val actionCalls = mutableListOf<String>()
        var searchGroup: ToolbarActionGroup? = null
        compose.setContent {
            MaterialTheme {
                val backdrop = rememberCanvasBackdrop { drawRect(Color(0xFFBAC8DD)) }
                CompositionLocalProvider(
                    LocalGlassAppearance provides GlassAppearance(),
                    LocalGlassBackdrop provides backdrop,
                    LocalBlurBackdrop provides backdrop,
                ) {
                    Column(Modifier.width(360.dp)) {
                        IosTopToolbar(
                            title = "Actions",
                            actionsStyle = actionStyle.value,
                            actions = {
                                GlassIconButton(
                                    onClick = { actionCalls += "first" },
                                    modifier = Modifier.testTag("firstAction"),
                                ) { SfIcon(SfSymbol.Search, null, modifier = Modifier.testTag("firstIcon")) }
                                GlassIconButton(
                                    onClick = { actionCalls += "second" },
                                    onLongClick = { actionCalls += "secondLong" },
                                    enabled = actionsEnabled.value,
                                    modifier = Modifier.testTag("secondAction"),
                                ) { SfIcon(SfSymbol.Ellipsis, null, modifier = Modifier.testTag("secondIcon")) }
                            },
                        )
                        IosTopToolbar(
                            title = "Search",
                            actionsSeparationProgress = separation.value,
                            navigation = {
                                GlassIconButton({}, Modifier.testTag("backAction")) {
                                    SfIcon(SfSymbol.ChevronBack, null)
                                }
                            },
                            actions = {
                                val group = LocalIosToolbarActionGroup.current
                                SideEffect { searchGroup = group }
                                BoxWithConstraints(Modifier.weight(1f, fill = false)) {
                                    val buttonSize = LocalGlassDimensions.current.iconButtonSize
                                    GlassSurface(
                                        modifier = Modifier.width(searchWidth.value).height(buttonSize)
                                            .testTag("searchSurface"),
                                        shape = Capsule(),
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Spacer(Modifier.weight(1f))
                                            Box(Modifier.size(buttonSize), contentAlignment = Alignment.Center) {
                                                SfIcon(SfSymbol.Search, null, modifier = Modifier.testTag("searchIcon"))
                                            }
                                        }
                                    }
                                }
                                GlassIconButton({}, Modifier.testTag("searchMenu")) {
                                    SfIcon(SfSymbol.Ellipsis, null, modifier = Modifier.testTag("searchMenuIcon"))
                                }
                            },
                        )
                    }
                }
            }
        }

        fun bounds(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot

        val originalActions = listOf("firstAction", "secondAction", "firstIcon", "secondIcon")
            .associateWith(::bounds)
        listOf(IosToolbarActionStyle.Merged, IosToolbarActionStyle.Separated).forEach { style ->
            compose.runOnIdle {
                actionStyle.value = style
                actionCalls.clear()
            }
            compose.waitForIdle()
            originalActions.forEach { (tag, original) -> assertEquals("Stable $tag bounds", original, bounds(tag)) }
            compose.mainClock.autoAdvance = false
            try {
                toolbarAction("firstAction").performTouchInput {
                    down(center)
                    moveBy(Offset(width * 0.4f, height * 0.2f))
                }
                compose.mainClock.advanceTimeBy(160L)
                assertTrue("The pressed icon follows the drag scale",
                    bounds("firstIcon").width > originalActions.getValue("firstIcon").width + 0.5f)
                if (style == IosToolbarActionStyle.Merged) {
                    assertTrue("A fused neighbor scales with the whole capsule",
                        bounds("secondIcon").width > originalActions.getValue("secondIcon").width + 0.5f)
                    assertTrue("A fused neighbor moves around the capsule center",
                        bounds("secondIcon").center.x > originalActions.getValue("secondIcon").center.x + 0.5f)
                } else {
                    assertEquals("A separate neighbor stays still", originalActions.getValue("secondIcon"), bounds("secondIcon"))
                }
                listOf("firstAction", "secondAction").forEach { tag ->
                    assertEquals("Pressing never moves the $tag layout slot", originalActions.getValue(tag), bounds(tag))
                }
                toolbarAction("firstAction").performTouchInput { up() }
                compose.mainClock.advanceTimeBy(1200L)
                originalActions.forEach { (tag, original) ->
                    assertEquals("Release restores $tag", original, bounds(tag))
                }
            } finally {
                compose.mainClock.autoAdvance = true
            }
            compose.runOnIdle {
                assertTrue("Dragging the first action never invokes its neighbor", actionCalls.all { it == "first" })
                actionCalls.clear()
            }
            toolbarAction("firstAction").performTouchInput { down(center); up() }
            toolbarAction("secondAction").performTouchInput { down(center); up() }
            toolbarAction("secondAction").performTouchInput { longClick() }
            compose.runOnIdle { assertEquals(listOf("first", "second", "secondLong"), actionCalls) }
        }
        compose.runOnIdle {
            actionsEnabled.value = false
        }
        toolbarAction("secondAction").assertIsNotEnabled()
            .performTouchInput { down(center); up(); longClick() }
        compose.runOnIdle { assertEquals(3, actionCalls.size) }

        val originalMenu = bounds("searchMenu")
        val originalBack = bounds("backAction")
        val originalSearch = bounds("searchSurface")
        val originalSearchIcon = bounds("searchIcon")
        val originalMenuIcon = bounds("searchMenuIcon")
        val originalSurfaceGap = originalMenu.left - originalSearch.right
        val originalIconGap = originalMenuIcon.left - originalSearchIcon.right
        listOf(0.35f to 44.dp, 1f to 44.dp, 1f to 120.dp, 1f to 208.dp).forEach { (progress, width) ->
            compose.runOnIdle {
                separation.value = progress
                searchWidth.value = width
            }
            compose.waitForIdle()
            if (progress == 1f) compose.runOnIdle {
                val group = requireNotNull(searchGroup)
                assertEquals("Both search actions remain registered", 2, group.members.size)
                assertTrue("Separated actions remove their shared background", group.sharedRuns().isEmpty())
                assertTrue("Each separated action draws its native surface", group.members.none { it.sharesSurface })
            }
            assertEquals("The menu stays anchored", originalMenu, bounds("searchMenu"))
            assertEquals("The back action stays anchored", originalBack, bounds("backAction"))
            assertEquals("The action layout gap stays constant", originalSurfaceGap,
                bounds("searchMenu").left - bounds("searchSurface").right, 0.75f)
            val searchIcon = bounds("searchIcon")
            val menuIcon = bounds("searchMenuIcon")
            if (progress == 1f) {
                assertEquals("The final icon gap returns to its original spacing", originalIconGap,
                    menuIcon.left - searchIcon.right, 0.75f)
            } else {
                assertEquals("Detachment preserves the search layout slot", originalSearch, bounds("searchSurface"))
                assertTrue("The search icon moves outward during detachment", searchIcon.center.x < originalSearchIcon.center.x - 0.5f)
                assertTrue("The menu icon moves outward during detachment", menuIcon.center.x > originalMenuIcon.center.x + 0.5f)
                assertTrue("Detachment briefly stretches the icons", searchIcon.width > originalSearchIcon.width + 0.5f)
                assertTrue("The icon gap briefly grows with the pull", menuIcon.left - searchIcon.right > originalIconGap + 0.5f)
            }
        }
        assertTrue("Search expands toward the back action", bounds("searchSurface").left < originalSearch.left)
        assertTrue("Search clears the back action", bounds("searchSurface").left > originalBack.right)
    }

    @Test
    fun toolbarPopupDetachmentReversesImmediatelyAndSingleActionsOpenWithoutDetaching() {
        val groupedOpen = mutableStateOf(false)
        val singleOpen = mutableStateOf(false)
        val groupedStyle = mutableStateOf(IosToolbarActionStyle.Merged)
        val reportsSeparation = mutableStateOf(true)
        val separationEnabled = mutableStateOf(true)
        var separation = 0f
        var groupedClosed = 0
        var groupedAlpha = 0f
        var singleAlpha = 0f
        compose.setContent {
            MaterialTheme {
                val backdrop = rememberCanvasBackdrop { drawRect(Color(0xFFBAC8DD)) }
                CompositionLocalProvider(
                    LocalGlassAppearance provides GlassAppearance(),
                    LocalGlassBackdrop provides backdrop,
                    LocalBlurBackdrop provides backdrop,
                ) {
                    Column(Modifier.width(360.dp)) {
                        IosTopToolbar(
                            title = "Grouped",
                            actionsStyle = groupedStyle.value,
                            actions = {
                                GlassIconButton({}) { SfIcon(SfSymbol.Search, null) }
                                IosPopupMenu(
                                    expanded = groupedOpen.value,
                                    onExpandedChange = { groupedOpen.value = it },
                                    itemCount = 1,
                                    onMenuAlphaChanged = { groupedAlpha = it },
                                    anchorSeparationEnabled = separationEnabled.value,
                                    onAnchorSeparationChanged = if (reportsSeparation.value) {
                                        { separation = it }
                                    } else null,
                                    onClosed = { groupedClosed++ },
                                    anchor = { open ->
                                        GlassIconButton(open, Modifier.testTag("groupedPopupAnchor")) {
                                            SfIcon(SfSymbol.Ellipsis, null)
                                        }
                                    },
                                ) { backdrop, close ->
                                    IosMenuItem("Grouped item", close, Modifier.testTag("groupedPopupItem"), backdrop = backdrop)
                                }
                            },
                        )
                        IosTopToolbar(
                            title = "Single",
                            actions = {
                                IosPopupMenu(
                                    expanded = singleOpen.value,
                                    onExpandedChange = { singleOpen.value = it },
                                    itemCount = 1,
                                    onMenuAlphaChanged = { singleAlpha = it },
                                    anchor = { open ->
                                        GlassIconButton(open, Modifier.testTag("singlePopupAnchor")) {
                                            SfIcon(SfSymbol.Ellipsis, null)
                                        }
                                    },
                                ) { backdrop, close ->
                                    IosMenuItem("Single item", close, Modifier.testTag("singlePopupItem"), backdrop = backdrop)
                                }
                            },
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        try {
            toolbarAction("groupedPopupAnchor").performClick()
            compose.mainClock.advanceTimeBy(96L)
            compose.onNodeWithTag("groupedPopupItem").assertDoesNotExist()
            var reversingFrom = 0f
            compose.runOnIdle {
                assertTrue("The shared anchor separates before the popup appears", separation > 0f && separation < 1f)
                reversingFrom = separation
            }
            toolbarAction("groupedPopupAnchor").performClick()
            compose.mainClock.advanceTimeBy(160L)
            compose.runOnIdle {
                assertTrue("A second tap reverses the neck without waiting for a menu collapse", separation < reversingFrom)
            }
            compose.mainClock.advanceTimeBy(160L)
            compose.onNodeWithTag("groupedPopupItem").assertDoesNotExist()
            compose.runOnIdle {
                assertEquals("The anchor rejoins before the 450ms popup close duration", 1, groupedClosed)
                assertEquals(0f, separation, 0.02f)
            }

            compose.runOnIdle { groupedStyle.value = IosToolbarActionStyle.Separated }
            compose.mainClock.advanceTimeBy(1200L)
            listOf(false, true).forEach { externalCallback ->
                compose.runOnIdle {
                    reportsSeparation.value = externalCallback
                    separationEnabled.value = !externalCallback
                }
                toolbarAction("groupedPopupAnchor").performClick()
                compose.mainClock.advanceTimeBy(64L)
                compose.onNodeWithTag("groupedPopupItem").assertExists()
                compose.runOnIdle {
                    assertTrue("Already separated actions immediately show their popup", groupedAlpha > 0f)
                    assertEquals("No redundant detachment runs", 0f, separation, 0.02f)
                    groupedOpen.value = false
                }
                compose.mainClock.advanceTimeBy(600L)
                compose.onNodeWithTag("groupedPopupItem").assertDoesNotExist()
            }

            toolbarAction("singlePopupAnchor").performClick()
            compose.mainClock.advanceTimeBy(64L)
            compose.onNodeWithTag("singlePopupItem").assertExists()
            compose.runOnIdle {
                assertTrue("A standalone action immediately starts showing its popup", singleAlpha > 0f)
            }
        } finally {
            compose.runOnIdle {
                groupedOpen.value = false
                singleOpen.value = false
            }
            compose.mainClock.autoAdvance = true
        }
    }

    private fun toggle() = compose.onNodeWithTag("toggle")

    private fun toolbarAction(tag: String) = compose.onNode(
        hasClickAction() and (hasTestTag(tag) or hasAnyAncestor(hasTestTag(tag))),
        useUnmergedTree = true,
    )

    private fun showToggle() {
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalGlassAppearance provides GlassAppearance(reduceTransparency = true),
                ) {
                    LazyColumn(Modifier.width(320.dp).height(240.dp), state = listState) {
                        item {
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable(enabled = enabled.value) {
                                        parentClicks++
                                        checked.value = !checked.value
                                    }
                                    .padding(32.dp),
                            ) {
                                val callbackRevision = revision.value
                                GlassToggle(
                                    checked = checked.value,
                                    onCheckedChange = { value ->
                                        calls += callbackRevision to value
                                        checked.value = value
                                    },
                                    modifier = Modifier.testTag("toggle"),
                                    enabled = enabled.value,
                                    backdrop = emptyBackdrop(),
                                )
                            }
                        }
                        items(20) { Box(Modifier.fillMaxWidth().height(48.dp)) }
                    }
                }
            }
        }
        compose.waitForIdle()
    }
}
