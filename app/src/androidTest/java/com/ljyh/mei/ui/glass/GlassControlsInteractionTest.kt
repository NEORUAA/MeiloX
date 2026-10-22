package com.ljyh.mei.ui.glass

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kyant.backdrop.backdrops.emptyBackdrop
import com.kyant.backdrop.backdrops.rememberCanvasBackdrop
import com.kyant.shapes.Capsule
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
