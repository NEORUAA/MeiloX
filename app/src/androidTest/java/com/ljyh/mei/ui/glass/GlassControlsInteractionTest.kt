package com.ljyh.mei.ui.glass

import io.github.neoruaa.glasmio.core.GlassAppearance
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.core.LocalGlassAppearance
import io.github.neoruaa.glasmio.core.LocalGlassColors
import io.github.neoruaa.glasmio.core.LocalGlassDimensions
import io.github.neoruaa.glasmio.core.defaultGlassColors
import io.github.neoruaa.glasmio.glass.GlasmioMenuAnchorState
import io.github.neoruaa.glasmio.glass.GlasmioBackdropHost
import io.github.neoruaa.glasmio.glass.GlasmioToolbarActionStyle
import io.github.neoruaa.glasmio.glass.GlassSurface
import io.github.neoruaa.glasmio.glass.GraphicsLayerBlockScope
import io.github.neoruaa.glasmio.glass.LiquidMenuContour
import io.github.neoruaa.glasmio.glass.LiquidMenuFieldTexture
import io.github.neoruaa.glasmio.glass.LiquidMenuMorphMotion
import io.github.neoruaa.glasmio.glass.LiquidMenuMorphSurface
import io.github.neoruaa.glasmio.glass.LiquidMenuNativeField
import io.github.neoruaa.glasmio.glass.LocalBlurBackdrop
import io.github.neoruaa.glasmio.glass.LocalGlassBackdrop
import io.github.neoruaa.glasmio.glass.LocalIosToolbarActionGroup
import io.github.neoruaa.glasmio.glass.ToolbarActionGroup
import io.github.neoruaa.glasmio.glass.ToolbarMenuPortal
import io.github.neoruaa.glasmio.glass.applyGlassDragScale
import io.github.neoruaa.glasmio.glass.glasmioMenuAnchor
import io.github.neoruaa.glasmio.glass.rememberGlasmioMenuAnchorState
import io.github.neoruaa.glasmio.glass.rememberGlassMorphRenderer
import io.github.neoruaa.glasmio.glass.rememberSharedGlassBackdrop
import io.github.neoruaa.glasmio.glass.rememberStationaryBackdrop
import io.github.neoruaa.glasmio.glass.trackBackdropPosition
import io.github.neoruaa.glasmio.icons.SfIcon
import io.github.neoruaa.glasmio.icons.SfSymbol
import io.github.neoruaa.glasmio.scaffold.GlasmioPinnedListPage
import io.github.neoruaa.glasmio.scaffold.GlasmioPinnedPage
import io.github.neoruaa.glasmio.scaffold.GlasmioNavigationScaffold
import io.github.neoruaa.glasmio.scaffold.navigation.GlasmioNavHeaderMotion
import io.github.neoruaa.glasmio.ui.GlasmioButton
import io.github.neoruaa.glasmio.ui.GlasmioCascadingMenu
import io.github.neoruaa.glasmio.ui.GlasmioCascadingMenuItem
import io.github.neoruaa.glasmio.ui.GlasmioIconButton
import io.github.neoruaa.glasmio.ui.GlasmioMenu
import io.github.neoruaa.glasmio.ui.GlasmioMenuItem
import io.github.neoruaa.glasmio.ui.GlasmioSegmentedControl
import io.github.neoruaa.glasmio.ui.GlasmioSwitch
import io.github.neoruaa.glasmio.ui.GlasmioTopBar

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Alignment
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
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
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.filters.SdkSuppress
import io.github.neoruaa.glasmio.glass.render.backdrops.emptyBackdrop
import io.github.neoruaa.glasmio.glass.render.backdrops.rememberCanvasBackdrop
import io.github.neoruaa.glasmio.glass.render.backdrops.layerBackdrop
import io.github.neoruaa.glasmio.glass.render.backdrops.rememberLayerBackdrop
import io.github.neoruaa.glasmio.glass.render.drawBackdrop
import com.kyant.shapes.Capsule
import io.github.neoruaa.glasmio.glass.PressHighlightRenderer
import io.github.neoruaa.glasmio.glass.InteractiveHighlight
import com.ljyh.mei.ui.screen.main.library.component.LibraryCollectionRefresh
import com.ljyh.mei.ui.component.MeiloXSwitch
import com.ljyh.mei.ui.screen.setting.SettingsCard
import com.ljyh.mei.ui.screen.setting.SettingsGroupedList
import com.ljyh.mei.ui.screen.social.privateConversationItems
import com.ljyh.mei.data.model.melox.MessageContact
import com.ljyh.mei.data.model.melox.PrivateConversation
import com.ljyh.mei.ui.theme.MusicTheme
import com.ljyh.mei.R
import com.ljyh.mei.data.model.api.CommentSortType
import com.ljyh.mei.ui.navigation.MeiRoute
import com.ljyh.mei.ui.navigation.MeiloXPageEntry
import com.ljyh.mei.ui.navigation.MeiloXPageHeaders
import com.ljyh.mei.ui.navigation.MeiloXPinnedPage
import com.ljyh.mei.ui.screen.comment.component.CommentSortAction
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
    fun appSwitchesFollowTheActiveAccentAndRetainExplicitTints() {
        val accent = mutableStateOf(Color.Red)
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalGlassColors provides defaultGlassColors(isDark = false, accent = accent.value),
                    LocalGlassAppearance provides GlassAppearance(reduceTransparency = true),
                ) {
                    Column(Modifier.background(Color.White).padding(16.dp)) {
                        MeiloXSwitch(true, {}, Modifier.testTag("accentSwitch"), backdrop = emptyBackdrop())
                        Spacer(Modifier.height(16.dp))
                        MeiloXSwitch(true, {}, Modifier.testTag("customSwitch"), backdrop = emptyBackdrop(), onTintColor = Color.Magenta)
                    }
                }
            }
        }
        fun assertTrackColor(tag: String, expected: Color) {
            val image = compose.onNodeWithTag(tag).captureToImage().toPixelMap()
            val actual = image[image.width / 8, image.height / 2]
            assertEquals(expected.red, actual.red, 0.02f)
            assertEquals(expected.green, actual.green, 0.02f)
            assertEquals(expected.blue, actual.blue, 0.02f)
        }
        compose.waitForIdle()
        assertTrackColor("accentSwitch", Color.Red)
        assertTrackColor("customSwitch", Color.Magenta)
        compose.runOnIdle { accent.value = Color.Blue }
        compose.waitForIdle()
        assertTrackColor("accentSwitch", Color.Blue)
        assertTrackColor("customSwitch", Color.Magenta)
    }

    @Test
    fun appSettingsGroupsSuppressFirstSeparatorsAndRetainInternalBoundaries() {
        val showFirst = mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalGlassColors provides defaultGlassColors(isDark = false).copy(
                        elevatedBackground = Color.White,
                        separator = Color.Red,
                    ),
                ) {
                    Column(Modifier.width(240.dp).background(Color.Black)) {
                        SettingsGroupedList {
                            if (showFirst.value) {
                                SettingsCard(Modifier.fillMaxWidth().height(52.dp).testTag("settingsFirst")) {}
                            }
                            SettingsCard(Modifier.fillMaxWidth().height(52.dp).testTag("settingsSecond")) {}
                        }
                        Text("Footer", Modifier.testTag("settingsFooter"))
                        SettingsGroupedList {
                            SettingsCard(Modifier.fillMaxWidth().height(52.dp).testTag("nextGroupFirst")) {}
                        }
                    }
                }
            }
        }
        fun assertTopSeparator(tag: String, visible: Boolean) {
            val image = compose.onNodeWithTag(tag).captureToImage().toPixelMap()
            val actual = image[image.width / 2, 0]
            assertTrue("The $tag top edge retains its row background", actual.red > 0.95f)
            val separatorVisible = actual.green < 0.95f && actual.blue < 0.95f
            assertEquals("Only an internal row boundary has a separator: $tag", visible, separatorVisible)
        }
        compose.waitForIdle()
        assertTopSeparator("settingsFirst", false)
        assertTopSeparator("settingsSecond", true)
        assertTopSeparator("nextGroupFirst", false)
        compose.onNodeWithTag("settingsFooter").assertIsDisplayed()
        compose.runOnIdle { showFirst.value = false }
        compose.waitForIdle()
        assertTopSeparator("settingsSecond", false)
        assertTopSeparator("nextGroupFirst", false)
        compose.onNodeWithTag("settingsFooter").assertIsDisplayed()
    }

    @Test
    fun privateConversationsRemainTouchableAfterScrollingToOldMessages() {
        val currentUser = MessageContact(100, "Current user", null, null, null)
        val conversations = List(50) { index ->
            val contact = MessageContact(1_000L + index, "Contact $index", null, null, null)
            PrivateConversation(
                id = "conversation-$index",
                fromUser = if (index % 2 == 0) contact else currentUser,
                toUser = if (index % 2 == 0) currentUser else contact,
                lastMessageTime = 1_700_000_000_000L - index * 86_400_000L,
                summary = "Message $index",
                unreadCount = if (index == 0) 3 else 0,
            )
        }
        val openedUsers = mutableListOf<Long>()
        compose.setContent {
            MusicTheme(seedColor = Color.Red, isDark = false) {
                Box(Modifier.width(360.dp).height(600.dp)) {
                    GlasmioPinnedListPage(
                        title = "Private messages",
                        bottomPadding = 0.dp,
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(0.dp),
                    ) {
                        privateConversationItems(conversations, currentUser.id, openedUsers::add)
                    }
                }
            }
        }

        for (index in listOf(0, 24, 49, 0)) {
            compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Contact $index"))
            compose.onNodeWithText("Contact $index").assertIsDisplayed().performTouchInput { click() }
            compose.runOnIdle { assertEquals(1_000L + index, openedUsers.lastOrNull()) }
        }
        compose.runOnIdle { assertEquals(listOf(1_000L, 1_024L, 1_049L, 1_000L), openedUsers) }
    }

    @Test
    fun pinnedPageKeepsToolbarFixedDuringBodyOverscroll() {
        // Cover the fallback for loading, empty and short pages, plus native list overscroll.
        val scenario = mutableStateOf(-1 to false)
        compose.setContent {
            MusicTheme(seedColor = Color.Red, isDark = false) {
                CompositionLocalProvider(
                    LocalGlassAppearance provides GlassAppearance(reduceTransparency = true),
                ) {
                    Box(Modifier.width(360.dp).height(600.dp)) {
                        key(scenario.value) {
                            val (rowCount, atBottom) = scenario.value
                            val actions: @Composable RowScope.() -> Unit = {
                                GlasmioIconButton({}, Modifier.testTag("overscrollRefresh")) {
                                    SfIcon(SfSymbol.ArrowClockwise, null)
                                }
                            }
                            if (rowCount < 0) {
                                GlasmioPinnedPage(
                                    title = "Pinned toolbar",
                                    bottomPadding = 0.dp,
                                    modifier = Modifier.testTag("overscrollPage"),
                                    onNavigateBack = {},
                                    actions = actions,
                                ) { padding ->
                                    Box(Modifier.padding(padding).size(48.dp).testTag("overscrollBody"))
                                }
                            } else {
                                GlasmioPinnedListPage(
                                    title = "Pinned toolbar",
                                    bottomPadding = 0.dp,
                                    modifier = Modifier.testTag("overscrollPage"),
                                    listState = remember { LazyListState(if (atBottom) (rowCount - 1).coerceAtLeast(0) else 0) },
                                    showsLargeTitle = false,
                                    onNavigateBack = {},
                                    actions = actions,
                                ) {
                                    items(rowCount) { index ->
                                        Box(
                                            Modifier.fillMaxWidth().height(48.dp).then(
                                                if (index == if (atBottom) rowCount - 1 else 0) {
                                                    Modifier.testTag("overscrollBody")
                                                } else Modifier,
                                            ),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        for (rowCount in listOf(-1, 0, 1, 40)) for (atBottom in listOf(false, true)) {
            compose.runOnIdle { scenario.value = rowCount to atBottom }
            compose.waitForIdle()
            val title = compose.onNodeWithText("Pinned toolbar")
            val refresh = toolbarAction("overscrollRefresh")
            val page = compose.onNodeWithTag("overscrollPage")
            val titleBefore = title.getUnclippedBoundsInRoot()
            val refreshBefore = refresh.getUnclippedBoundsInRoot()
            val body = compose.onNodeWithTag("overscrollBody")
            val bodyBefore = if (rowCount == 0) null else body.getUnclippedBoundsInRoot()
            val direction = if (atBottom) -1f else 1f
            var fingerDown = false
            compose.mainClock.autoAdvance = false
            try {
                page.performTouchInput {
                    down(Offset(center.x, height * 0.55f))
                    moveBy(Offset(0f, direction * height * 0.05f))
                    moveBy(Offset(0f, direction * height * 0.15f))
                }
                fingerDown = true
                compose.mainClock.advanceTimeBy(64)
                assertEquals(titleBefore, title.getUnclippedBoundsInRoot())
                assertEquals(refreshBefore, refresh.getUnclippedBoundsInRoot())
                if (bodyBefore != null) {
                    val displacement = body.getUnclippedBoundsInRoot().top.value - bodyBefore.top.value
                    assertTrue("Body rebounds for rows=$rowCount, bottom=$atBottom", displacement * direction > 1f)
                }
                page.performTouchInput { up() }
                fingerDown = false
                compose.mainClock.advanceTimeBy(2_000)
                assertEquals(titleBefore, title.getUnclippedBoundsInRoot())
                assertEquals(refreshBefore, refresh.getUnclippedBoundsInRoot())
                if (bodyBefore != null) {
                    assertEquals(bodyBefore.top.value, body.getUnclippedBoundsInRoot().top.value, 1f)
                }
            } finally {
                if (fingerDown) page.performTouchInput { cancel() }
                compose.mainClock.autoAdvance = true
            }
        }
    }

    @Test
    fun toolbarSymbolsMatchNativePointSizeWeightAndOpticalProportions() {
        val style = mutableStateOf(GlasmioToolbarActionStyle.Merged)
        compose.setContent {
            MusicTheme(seedColor = Color.Red, isDark = false) {
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, fontScale = 1f),
                    LocalGlassBackdrop provides emptyBackdrop(),
                    LocalBlurBackdrop provides emptyBackdrop(),
                    LocalGlassAppearance provides GlassAppearance(reduceTransparency = true),
                    LocalGlassColors provides LocalGlassColors.current.copy(container = Color.White, content = Color.Black),
                ) {
                    GlasmioTopBar(
                        title = "Symbols",
                        modifier = Modifier.width(360.dp).background(Color.White),
                        actionsStyle = style.value,
                        actions = {
                            GlasmioIconButton({}, Modifier.testTag("nativeSearch")) { SfIcon(SfSymbol.Search, null) }
                            GlasmioIconButton({}, Modifier.testTag("nativeMore")) { SfIcon("ellipsis", null) }
                            GlasmioIconButton({}, Modifier.testTag("nativeHouse")) { SfIcon("house", null) }
                            GlasmioIconButton({}, Modifier.testTag("regularSearch")) {
                                SfIcon(SfSymbol.Search, null, weight = FontWeight.Normal)
                            }
                        },
                    )
                }
            }
        }
        fun ink(tag: String): Triple<Float, Float, Int> {
            val image = compose.onNodeWithTag(tag).captureToImage().toPixelMap()
            var left = image.width
            var top = image.height
            var right = -1
            var bottom = -1
            var count = 0
            for (y in 0 until image.height) for (x in 0 until image.width) {
                // Exclude the glass outline outside the central 32 dp icon region.
                val unit = image.width / 44f
                if (kotlin.math.abs(x + 0.5f - image.width / 2f) > 16f * unit ||
                    kotlin.math.abs(y + 0.5f - image.height / 2f) > 16f * unit) continue
                val pixel = image[x, y]
                if (pixel.red < 0.5f && pixel.green < 0.5f && pixel.blue < 0.5f) {
                    left = minOf(left, x); right = maxOf(right, x)
                    top = minOf(top, y); bottom = maxOf(bottom, y)
                    count++
                }
            }
            assertTrue("$tag draws a visible symbol", count > 0)
            val unit = image.width / 44f
            assertEquals("$tag remains horizontally centered", image.width / 2f, (left + right + 1) / 2f, unit)
            return Triple((right - left + 1) / unit, (bottom - top + 1) / unit, count)
        }
        listOf(GlasmioToolbarActionStyle.Merged, GlasmioToolbarActionStyle.Separated).forEach { value ->
            compose.runOnIdle { style.value = value }
            compose.waitForIdle()
            val search = ink("nativeSearch")
            val more = ink("nativeMore")
            val house = ink("nativeHouse")
            // UIKit iOS 27 Body 17 pt / Medium / Large, measured after applying the
            // actual toolbar UIImageView's preferredSymbolConfiguration (alpha >= 128).
            assertEquals(21f, search.first, 0.67f)
            assertEquals(21f, search.second, 0.67f)
            assertEquals(19.667f, more.first, 0.67f)
            assertEquals(4f, more.second, 0.67f)
            assertTrue("Native optical widths are not normalized to one bounding box", search.first > more.first)
            assertTrue("Wide symbols can extend past the 24 dp layout slot", house.first > 24f)
            assertTrue("Explicit Regular weight is preserved", search.third > ink("regularSearch").third)
        }
    }

    @Test
    fun liquidMenuFieldPreservesSignedDistanceAndNormalsOnGpu() {
        assertTrue("The installed build includes the native field fast path", LiquidMenuNativeField.warmUp())
        val contour = LiquidMenuContour(Rect(0f, 0f, 180f, 140f), Offset(34f, 34f), null, Offset.Zero, 8f)
        val data = requireNotNull(contour.sampledField())
        val pixel = (0 until data.width * data.height).first { index ->
            data.rgba[index * 4] < -2f && data.rgba[index * 4 + 1] < -0.25f && data.rgba[index * 4 + 2] < -0.25f
        }
        val texture = LiquidMenuFieldTexture().apply { update(data, Offset.Zero) }
        val shader = android.graphics.RuntimeShader("""
            uniform shader field;
            uniform float2 samplePoint;
            half4 main(float2 p) {
                float3 value = field.eval(samplePoint).rgb;
                return half4(-value.r * 0.01, -value.g, -value.b, 1.0);
            }
        """)
        shader.setInputBuffer("field", requireNotNull(texture.input))
        shader.setFloatUniform("samplePoint", pixel % data.width + 0.5f, pixel / data.width + 0.5f)
        compose.setContent {
            Canvas(Modifier.size(24.dp).testTag("signedField")) { drawRect(ShaderBrush(shader)) }
        }
        val image = compose.onNodeWithTag("signedField").captureToImage().toPixelMap()
        val actual = image[image.width / 2, image.height / 2]
        assertEquals(-data.rgba[pixel * 4] * 0.01f, actual.red, 2f / 255f)
        assertEquals(-data.rgba[pixel * 4 + 1], actual.green, 2f / 255f)
        assertEquals(-data.rgba[pixel * 4 + 2], actual.blue, 2f / 255f)
    }

    @Test
    fun liquidMenuSamplingCoversSpringOvershootBeyondItsRestingHost() {
        val overshoot = mutableFloatStateOf(1.03f)
        val navigationMaterial = mutableStateOf(true)
        val negativeOrigin = mutableStateOf(false)
        compose.setContent {
            val backdrop = rememberCanvasBackdrop { drawRect(Color(0xFF315A8C)) }
            CompositionLocalProvider(LocalGlassAppearance provides GlassAppearance()) {
                Column(Modifier.width(240.dp).testTag("overshootScene")) {
                    repeat(2) { index ->
                        val host = remember { mutableStateOf<LayoutCoordinates?>(null) }
                        val renderer = rememberGlassMorphRenderer(backdrop, true, blurRadius = 4.dp)
                        Box(Modifier.size(240.dp, 190.dp)) {
                            Box(Modifier.padding(24.dp).size(
                                if (index == 0) 160.dp else 200.dp,
                                if (index == 0) 120.dp else 160.dp,
                            ).onGloballyPositioned { host.value = it }.drawBehind {
                                val coordinates = host.value ?: return@drawBehind
                                renderer.draw(
                                    scope = this,
                                    coordinates = coordinates,
                                    viewport = Rect(0f, 0f, 720.dp.toPx(), 812.dp.toPx()),
                                    origin = coordinates.positionInRoot(),
                                    bounds = Rect(
                                        if (negativeOrigin.value) Offset(-8.dp.toPx(), -6.dp.toPx()) else Offset.Zero,
                                        Size(160.dp.toPx() * overshoot.floatValue, 120.dp.toPx() * overshoot.floatValue),
                                    ),
                                    radius = 16.dp.toPx(),
                                    shape = com.kyant.shapes.RoundedRectangle(16.dp),
                                    tint = Color(0x66334455),
                                    highlightAlpha = 0f,
                                    navigationOutline = false,
                                    navigationMaterial = navigationMaterial.value,
                                    refractionAmount = 0.dp,
                                )
                            })
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        listOf(true, false).forEach { material ->
            listOf(1.03f, 1.12f).forEach { scale ->
                listOf(false, true).forEach { negative ->
                compose.runOnIdle {
                    navigationMaterial.value = material
                    overshoot.floatValue = scale
                    negativeOrigin.value = negative
                }
                compose.waitForIdle()
                val image = compose.onNodeWithTag("overshootScene").captureToImage().toPixelMap()
                val density = image.width / 240f
                val points = if (negative) listOf(80f to 60f, -4f to 60f, 80f to -3f)
                    else listOf(80f to 60f, 161f to 60f, 80f to 121f)
                points.forEach { (x, y) ->
                    val actual = image[((24f + x) * density).toInt(), ((24f + y) * density).toInt()]
                    val control = image[((24f + x) * density).toInt(), ((214f + y) * density).toInt()]
                    val label = "material=$material overshoot=$scale negativeOrigin=$negative at($x,$y)"
                    assertEquals("$label red", control.red, actual.red, 2f / 255f)
                    assertEquals("$label green", control.green, actual.green, 2f / 255f)
                    assertEquals("$label blue", control.blue, actual.blue, 2f / 255f)
                    assertEquals("$label alpha", control.alpha, actual.alpha, 2f / 255f)
                }
                }
            }
        }
    }

    @Test
    fun liquidMenuSamplingStaysFixedDuringRearScaleAndPressDrag() {
        val scale = mutableFloatStateOf(1f)
        val darkBackground = mutableStateOf(false)
        var highlight: InteractiveHighlight? = null
        compose.setContent {
            val density = LocalDensity.current
            val page = rememberLayerBackdrop()
            val base = rememberCanvasBackdrop { drawRect(Color.Black) }
            val backdrop = rememberSharedGlassBackdrop(base, page)
            val motion = remember { LiquidMenuMorphMotion() }
            val screenOrigin = remember { mutableStateOf<Offset?>(null) }
            val scope = rememberCoroutineScope()
            val press = remember(scope) {
                // Keep the press light constant at these interior sample points, so a
                // pixel difference measures sampling movement rather than moving light.
                InteractiveHighlight(scope) { _, _ -> Offset.Zero }
            }
            SideEffect { highlight = press }
            CompositionLocalProvider(
                LocalGlassAppearance provides GlassAppearance(),
                LocalGlassColors provides LocalGlassColors.current.copy(container = Color.Transparent),
            ) {
                Box(Modifier.size(320.dp, 300.dp).testTag("liquidSamplingScene")) {
                    Canvas(Modifier.fillMaxSize().layerBackdrop(page).trackBackdropPosition(page)) {
                        if (darkBackground.value) drawRect(Color.Black) else drawRect(Brush.linearGradient(
                            listOf(Color.Red, Color.Green, Color.Blue),
                            start = Offset.Zero,
                            end = Offset(size.width, size.height),
                        ))
                    }
                    Box(Modifier.padding(40.dp).size(240.dp, 200.dp)
                        .testTag("liquidSamplingHost")
                        .onGloballyPositioned { coordinates ->
                            val origin = coordinates.localToScreen(Offset.Zero)
                            if (screenOrigin.value != origin) {
                                val target = Rect(origin, Size(coordinates.size.width.toFloat(), coordinates.size.height.toFloat()))
                                val source = Rect(origin, with(density) { Size(80.dp.toPx(), 40.dp.toPx()) })
                                motion.initialize(source, target, with(density) { 34.dp.toPx() }, density.density)
                                motion.retarget(true)
                                motion.advanceBy(5f)
                                screenOrigin.value = origin
                            }
                        },
                    ) {
                        screenOrigin.value?.let { origin ->
                            LiquidMenuMorphSurface(
                                motion = motion,
                                screenOrigin = origin,
                                backdrop = backdrop,
                                exportChildBackdrop = false,
                                sourcePortal = null,
                                shaderPrograms = null,
                                menuScale = { scale.floatValue },
                                transformOrigin = TransformOrigin(0.85f, 0.15f),
                                highlight = press,
                            ) { }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        val scene = compose.onNodeWithTag("liquidSamplingScene")
        val host = compose.onNodeWithTag("liquidSamplingHost")
        fun checkScales(reference: androidx.compose.ui.graphics.PixelMap, phase: String) {
            val density = reference.width / 320f
            listOf(0.94f, 0.76f, 1.03f, 1f).forEach { value ->
                compose.runOnIdle { scale.floatValue = value }
                compose.waitForIdle()
                val actual = scene.captureToImage().toPixelMap()
                listOf(120f, 140f, 160f).forEach { xDp ->
                    listOf(100f, 110f, 120f).forEach { yDp ->
                        val x = (xDp * density).toInt()
                        val y = (yDp * density).toInt()
                        val expected = reference[x, y]
                        val sampled = actual[x, y]
                        val label = "$phase scale=$value at ($xDp,$yDp)"
                        assertEquals("$label red", expected.red, sampled.red, 4f / 255f)
                        assertEquals("$label green", expected.green, sampled.green, 4f / 255f)
                        assertEquals("$label blue", expected.blue, sampled.blue, 4f / 255f)
                    }
                }
            }
        }
        checkScales(scene.captureToImage().toPixelMap(), "rear menu")
        host.performTouchInput { down(Offset(40f, 40f)) }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(requireNotNull(highlight).pressProgress > 0.99f) }
        val pressed = scene.captureToImage().toPixelMap()
        host.performTouchInput { moveBy(Offset(60f, 30f)) }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(requireNotNull(highlight).offset.getDistance() > 30f) }
        try {
            checkScales(pressed, "pressed drag")
            // A black scene isolates leaked additive press light: the exterior shadow
            // can only darken it, while a rectangular light would brighten all corners.
            compose.runOnIdle { darkBackground.value = true }
            compose.waitForIdle()
            listOf(0.94f, 0.76f, 1.03f, 1f).forEach { value ->
                compose.runOnIdle { scale.floatValue = value }
                compose.waitForIdle()
                val image = scene.captureToImage().toPixelMap()
                val density = image.width / 320f
                val sceneBounds = scene.fetchSemanticsNode().boundsInRoot
                val hostBounds = host.fetchSemanticsNode().boundsInRoot
                val press = requireNotNull(highlight)
                val layer = GraphicsLayerBlockScope().apply {
                    reset(androidx.compose.ui.unit.Density(density), hostBounds.size)
                    transformOrigin = TransformOrigin(0.85f, 0.15f)
                    applyGlassDragScale(press.pressProgress, press.offset)
                    scaleX *= value
                    scaleY *= value
                }
                val pivot = Offset(hostBounds.width * 0.85f, hostBounds.height * 0.15f)
                val origin = hostBounds.topLeft - sceneBounds.topLeft + Offset(
                    pivot.x * (1f - layer.scaleX) + layer.translationX,
                    pivot.y * (1f - layer.scaleY) + layer.translationY,
                )
                val inset = 4f * density
                listOf(inset, hostBounds.width - inset).forEach { x ->
                    listOf(inset, hostBounds.height - inset).forEach { y ->
                        val point = origin + Offset(x * layer.scaleX, y * layer.scaleY)
                        val pixel = image[point.x.toInt(), point.y.toInt()]
                        val label = "Press light stays inside the curved skin: scale=$value at $point"
                        assertEquals("$label red", 0f, pixel.red, 2f / 255f)
                        assertEquals("$label green", 0f, pixel.green, 2f / 255f)
                        assertEquals("$label blue", 0f, pixel.blue, 2f / 255f)
                    }
                }
            }
        } finally {
            host.performTouchInput { up() }
        }
    }

    @Test
    fun popupSamplingStaysFixedWhileItsLayerExpandsAndCollapses() {
        val visible = mutableStateOf(false)
        val scale = mutableFloatStateOf(1f)
        compose.setContent {
            val page = rememberLayerBackdrop()
            val base = rememberCanvasBackdrop { drawRect(Color.Black) }
            val backdrop = rememberSharedGlassBackdrop(base, page)
            val host = remember { mutableStateOf<LayoutCoordinates?>(null) }
            val hostCoordinates = remember { { host.value } }
            val stationary = rememberStationaryBackdrop(backdrop, hostCoordinates)
            val popupPosition = remember {
                object : PopupPositionProvider {
                    override fun calculatePosition(
                        anchorBounds: IntRect,
                        windowSize: IntSize,
                        layoutDirection: LayoutDirection,
                        popupContentSize: IntSize,
                    ): IntOffset = anchorBounds.topLeft
                }
            }
            val animatedLayer: androidx.compose.ui.graphics.GraphicsLayerScope.() -> Unit = remember {
                {
                    val progress = scale.floatValue
                    transformOrigin = TransformOrigin(0.85f, 0.15f)
                    scaleX = progress
                    scaleY = progress
                    translationX = 16.dp.toPx() * (1f - progress)
                    translationY = -10.dp.toPx() * (1f - progress)
                }
            }
            Box(Modifier.size(320.dp, 240.dp).testTag("stationaryPopupScene")) {
                Canvas(Modifier.fillMaxSize().layerBackdrop(page).trackBackdropPosition(page)) {
                    drawRect(
                        Brush.linearGradient(
                            listOf(Color.Red, Color.Green, Color.Blue),
                            start = Offset.Zero,
                            end = Offset(size.width, size.height),
                        ),
                    )
                }
                if (visible.value) Popup(
                    popupPositionProvider = popupPosition,
                    properties = PopupProperties(clippingEnabled = false),
                ) {
                    Box(Modifier.size(320.dp, 240.dp).background(Color.Magenta)) {
                        Box(
                            Modifier.align(Alignment.Center).size(220.dp, 160.dp)
                                .onGloballyPositioned { host.value = it },
                        ) {
                            Box(
                                Modifier.fillMaxSize().drawBackdrop(
                                    backdrop = stationary,
                                    shape = { RectangleShape },
                                    effects = {},
                                    highlight = null,
                                    shadow = null,
                                    layerBlock = animatedLayer,
                                ),
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        val scene = compose.onNodeWithTag("stationaryPopupScene")
        val reference = scene.captureToImage().toPixelMap()
        compose.runOnIdle { visible.value = true }
        compose.waitForIdle()
        val popup = compose.onNode(isPopup())
        // State is read only from the layer block. A graphics-layer update must also
        // refresh the backdrop recording, without relying on a layout or recomposition.
        listOf(1f, 0.76f, 0.43f, 0.24f, 0.6f, 1.10f, 1f).forEach { progress ->
            compose.runOnIdle { scale.floatValue = progress }
            compose.waitForIdle()
            val actual = popup.captureToImage().toPixelMap()
            val density = reference.width / 320f
            listOf(214f, 230f, 246f).forEach { xDp ->
                listOf(65f, 75f, 85f).forEach { yDp ->
                    val x = (xDp * density).toInt()
                    val y = (yDp * density).toInt()
                    val expected = reference[x, y]
                    val sampled = actual[x, y]
                    val label = "scale=$progress at ($xDp, $yDp)"
                    val tolerance = 4f / 255f
                    assertEquals("$label red", expected.red, sampled.red, tolerance)
                    assertEquals("$label green", expected.green, sampled.green, tolerance)
                    assertEquals("$label blue", expected.blue, sampled.blue, tolerance)
                }
            }
        }
    }

    @Test
    fun artistSubmenuKeepsOneWindowAndClosesWithItsParent() {
        val visible = mutableStateOf(true)
        var artistClicks = 0
        var dismissals = 0
        compose.setContent {
            MusicTheme(seedColor = Color.Red, isDark = false) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    if (visible.value) GlasmioCascadingMenu(
                        anchorBounds = Rect(24f, 160f, 224f, 204f),
                        title = "Player destinations",
                        expandedDescription = "Expanded",
                        collapsedDescription = "Collapsed",
                        backdrop = emptyBackdrop(),
                        onDismiss = { dismissals++; visible.value = false },
                        items = listOf(
                            GlasmioCascadingMenuItem(
                                title = "Go to artist",
                                subtitle = "Artist A, Artist B",
                                children = listOf(
                                    GlasmioCascadingMenuItem("Artist A", onClick = { artistClicks++ }),
                                    GlasmioCascadingMenuItem("Artist B"),
                                ),
                            ),
                            GlasmioCascadingMenuItem("Go to album"),
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
    fun popupContentSurvivesAnchorCaptionResizeDuringCollapse() {
        val expanded = mutableStateOf(false)
        val anchorWidth = mutableStateOf(44.dp)
        var contentMounts = 0
        var contentDisposals = 0
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    GlasmioMenu(
                        expanded = expanded.value,
                        onExpandedChange = { expanded.value = it },
                        itemCount = 1,
                        backdrop = emptyBackdrop(),
                        fullScreen = true,
                        centerHorizontally = true,
                        expandFromAnchor = Alignment.CenterHorizontally,
                        anchor = { open ->
                            Box(Modifier.width(anchorWidth.value).height(24.dp)
                                .testTag("resizingPopupAnchor").clickable(onClick = open))
                        },
                    ) { backdrop, close ->
                        DisposableEffect(Unit) {
                            contentMounts++
                            onDispose { contentDisposals++ }
                        }
                        GlasmioMenuItem(
                            title = "Select quality",
                            onClick = { anchorWidth.value = 144.dp; close() },
                            backdrop = backdrop,
                        )
                    }
                }
            }
        }
        compose.onNodeWithTag("resizingPopupAnchor").performClick()
        compose.onNodeWithText("Select quality").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(1, contentMounts)
            assertEquals(0, contentDisposals)
        }
        try {
            compose.mainClock.autoAdvance = false
            compose.onNodeWithText("Select quality").performClick()
            repeat(5) {
                compose.mainClock.advanceTimeByFrame()
                compose.runOnIdle {
                    assertEquals("Caption resizing must not recreate the collapsing menu", 1, contentMounts)
                    assertEquals("The popup content must remain resident until closure", 0, contentDisposals)
                }
            }
            compose.mainClock.advanceTimeBy(1_000)
            compose.runOnIdle { assertEquals(1, contentDisposals) }
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test
    fun commentSortMenuOpensFromItsSharedToolbarAnchorAndRetargetsSelection() {
        val sort = mutableStateOf(CommentSortType.RECOMMEND)
        val selections = mutableListOf<CommentSortType>()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        fun label(resource: Int) = context.getString(resource)
        compose.setContent {
            GlasmioTheme {
                GlasmioBackdropHost(
                    modifier = Modifier.fillMaxSize(),
                    sampledContent = { Box(Modifier.fillMaxSize().background(Color.White)) },
                    overlayContent = {
                        val route = remember { MeiRoute("comment-sort-regression") }
                        val headers = remember { MeiloXPageHeaders() }
                        val motion = remember { GlasmioNavHeaderMotion(route.route) }
                        GlasmioNavigationScaffold(motion, header = { headers.entry(it).spec }) {
                            MeiloXPageEntry(route, headers) {
                                val action = CommentSortAction(sort.value) {
                                    selections += it
                                    sort.value = it
                                }
                                MeiloXPinnedPage(title = "Comments", actions = { listOf(action) }) { padding ->
                                    Text("Read-only comment fixture", Modifier.padding(padding))
                                }
                            }
                        }
                    },
                )
            }
        }

        fun openFrom(resource: Int) {
            compose.onNode(hasContentDescription(label(resource)) and hasClickAction()).performClick()
            compose.onAllNodes(isPopup()).assertCountEquals(1)
            for (option in listOf(R.string.comment_sort_recommended, R.string.comment_sort_hot, R.string.comment_sort_latest)) {
                compose.onNode(hasText(label(option)) and hasAnyAncestor(isPopup())).assertIsDisplayed()
            }
        }
        fun select(resource: Int) {
            compose.onNode(hasText(label(resource)) and hasAnyAncestor(isPopup())).performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodes(isPopup()).fetchSemanticsNodes().isEmpty()
            }
            compose.onNodeWithContentDescription(label(resource)).assertIsDisplayed()
        }

        openFrom(R.string.comment_sort_recommended)
        select(R.string.comment_sort_hot)
        compose.runOnIdle { assertEquals(listOf(CommentSortType.HOT), selections) }
        openFrom(R.string.comment_sort_hot)
        select(R.string.comment_sort_latest)
        compose.runOnIdle { assertEquals(listOf(CommentSortType.HOT, CommentSortType.TIME), selections) }
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
                        GlasmioSegmentedControl(
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
                    GlasmioSegmentedControl(
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
                            GlasmioSegmentedControl(
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
                        GlasmioSegmentedControl(
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
                    GlasmioSegmentedControl(
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
                        GlasmioSwitch(checked.value, { checked.value = it }, Modifier.testTag("toggle"), backdrop = emptyBackdrop())
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
        val actionStyle = mutableStateOf(GlasmioToolbarActionStyle.Merged)
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
                        GlasmioTopBar(
                            title = "Actions",
                            actionsStyle = actionStyle.value,
                            actions = {
                                GlasmioIconButton(
                                    onClick = { actionCalls += "first" },
                                    modifier = Modifier.testTag("firstAction"),
                                ) { SfIcon(SfSymbol.Search, null, modifier = Modifier.testTag("firstIcon")) }
                                GlasmioIconButton(
                                    onClick = { actionCalls += "second" },
                                    onLongClick = { actionCalls += "secondLong" },
                                    enabled = actionsEnabled.value,
                                    modifier = Modifier.testTag("secondAction"),
                                ) { SfIcon(SfSymbol.Ellipsis, null, modifier = Modifier.testTag("secondIcon")) }
                            },
                        )
                        GlasmioTopBar(
                            title = "Search",
                            actionsSeparationProgress = separation.value,
                            navigation = {
                                GlasmioIconButton({}, Modifier.testTag("backAction")) {
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
                                GlasmioIconButton({}, Modifier.testTag("searchMenu")) {
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
        listOf(GlasmioToolbarActionStyle.Merged, GlasmioToolbarActionStyle.Separated).forEach { style ->
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
                if (style == GlasmioToolbarActionStyle.Merged) {
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
    fun toolbarPortalKeepsContentScaleAndInsetsAsThePressSettles() {
        val portal = mutableStateOf<ToolbarMenuPortal?>(null)
        val motion = LiquidMenuMorphMotion()
        var anchor: GlasmioMenuAnchorState? = null
        var previewOrigin = Offset.Zero
        var previewSize = Size.Zero
        var density = 1f
        compose.setContent {
            val backdrop = rememberCanvasBackdrop { drawRect(Color.White) }
            val source = rememberGlasmioMenuAnchorState()
            val scope = rememberCoroutineScope()
            val highlight = remember(scope) { InteractiveHighlight(scope) }
            SideEffect { anchor = source }
            CompositionLocalProvider(
                LocalGlassAppearance provides GlassAppearance(reduceTransparency = true),
                LocalGlassBackdrop provides backdrop,
                LocalBlurBackdrop provides backdrop,
            ) {
                Column(Modifier.width(320.dp)) {
                    GlasmioTopBar(title = "Portal", actions = {
                        Row(Modifier.testTag("portalSourceContent"), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                            GlasmioIconButton({}, Modifier.glasmioMenuAnchor(source).testTag("portalPressSource")) {
                                SfIcon("arrow.up.arrow.down", null, tint = Color.Green)
                            }
                            GlasmioIconButton({}) {
                                Box(Modifier.size(36.dp).background(Color.Blue, androidx.compose.foundation.shape.CircleShape))
                            }
                        }
                    })
                    Box(Modifier.size(240.dp, 160.dp).testTag("portalScalePreview")
                        .onGloballyPositioned {
                            previewOrigin = it.localToScreen(Offset.Zero)
                            previewSize = Size(it.size.width.toFloat(), it.size.height.toFloat())
                        }) {
                        density = LocalDensity.current.density
                        portal.value?.let {
                            LiquidMenuMorphSurface(
                                motion, previewOrigin, backdrop, false, it, null,
                                menuScale = { 1f }, transformOrigin = TransformOrigin.Center,
                                highlight = highlight,
                            ) { }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        fun avatar(image: androidx.compose.ui.graphics.PixelMap): IntRect {
            var left = image.width
            var top = image.height
            var right = -1
            var bottom = -1
            for (y in 0 until image.height) for (x in 0 until image.width) {
                val color = image[x, y]
                if (color.blue > 0.8f && maxOf(color.red, color.green) < 0.2f) {
                    left = minOf(left, x)
                    top = minOf(top, y)
                    right = maxOf(right, x)
                    bottom = maxOf(bottom, y)
                }
            }
            assertTrue("The source avatar remains visible", right >= left && bottom >= top)
            return IntRect(left, top, right + 1, bottom + 1)
        }
        val referenceImage = compose.onNodeWithTag("portalSourceContent").captureToImage()
        val reference = avatar(referenceImage.toPixelMap())
        val sourceSize = Size(referenceImage.width.toFloat(), referenceImage.height.toFloat())
        val trigger = toolbarAction("portalPressSource")
        compose.mainClock.autoAdvance = false
        var held = false
        try {
            trigger.performTouchInput { down(center) }
            held = true
            compose.mainClock.advanceTimeBy(320L)
            compose.waitForIdle()
            compose.runOnIdle {
                val acquired = requireNotNull(requireNotNull(anchor).acquire())
                assertTrue("The fixture acquires a physically expanded source", acquired.width > sourceSize.width * 1.05f)
                portal.value = requireNotNull(anchor).portal
                motion.initialize(Rect(previewOrigin, sourceSize), Rect(previewOrigin, previewSize), 34f * density, density)
                motion.retarget(true)
                motion.advanceBy(5f)
                motion.retarget(false)
                motion.advanceBy(5f)
            }
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            fun check(phase: String) {
                val actual = avatar(compose.onNodeWithTag("portalScalePreview").captureToImage().toPixelMap())
                assertEquals("$phase avatar width", reference.width.toFloat(), actual.width.toFloat(), 2f)
                assertEquals("$phase avatar height", reference.height.toFloat(), actual.height.toFloat(), 2f)
                assertEquals("$phase horizontal inset", reference.left.toFloat(), actual.left.toFloat(), 2f)
                assertEquals("$phase vertical inset", reference.top.toFloat(), actual.top.toFloat(), 2f)
            }
            check("Held press")
            trigger.performTouchInput { up() }
            held = false
            compose.mainClock.advanceTimeBy(1_200L)
            compose.waitForIdle()
            check("Settled press before source release")
        } finally {
            if (held) trigger.performTouchInput { cancel() }
            compose.runOnIdle { anchor?.release(); portal.value = null }
            compose.mainClock.autoAdvance = true
        }
    }

    @Test
    fun toolbarPopupMorphsTheWholeSourceAndReversesWithoutDetaching() {
        val groupedOpen = mutableStateOf(false)
        val singleOpen = mutableStateOf(false)
        val singleLabel = mutableStateOf("Recommended")
        val singleLabelColor = mutableStateOf(Color.Magenta)
        val groupedStyle = mutableStateOf(GlasmioToolbarActionStyle.Merged)
        var groupedAnchor: GlasmioMenuAnchorState? = null
        var singleAnchor: GlasmioMenuAnchorState? = null
        var groupedGroup: ToolbarActionGroup? = null
        var groupedClosed = 0
        var groupedAlpha = 0f
        var singleAlpha = 0f
        compose.setContent {
            MaterialTheme {
                val backdrop = rememberCanvasBackdrop { drawRect(Color(0xFFBAC8DD)) }
                val groupedSource = rememberGlasmioMenuAnchorState()
                val singleSource = rememberGlasmioMenuAnchorState()
                SideEffect {
                    groupedAnchor = groupedSource
                    singleAnchor = singleSource
                }
                CompositionLocalProvider(
                    LocalGlassAppearance provides GlassAppearance(),
                    LocalGlassBackdrop provides backdrop,
                    LocalBlurBackdrop provides backdrop,
                ) {
                    Column(Modifier.width(360.dp)) {
                        GlasmioTopBar(
                            title = "Grouped",
                            actionsStyle = groupedStyle.value,
                            actions = {
                                val group = LocalIosToolbarActionGroup.current
                                SideEffect { groupedGroup = group }
                                GlasmioIconButton({}) { SfIcon(SfSymbol.Search, null) }
                                GlasmioMenu(
                                    expanded = groupedOpen.value,
                                    onExpandedChange = { groupedOpen.value = it },
                                    itemCount = 1,
                                    menuAnchorState = groupedSource,
                                    onMenuAlphaChanged = { groupedAlpha = it },
                                    onClosed = { groupedClosed++ },
                                    anchor = { open ->
                                        GlasmioIconButton(open, Modifier.glasmioMenuAnchor(groupedSource).testTag("groupedPopupAnchor")) {
                                            SfIcon(SfSymbol.Ellipsis, null)
                                        }
                                    },
                                ) { backdrop, close ->
                                    GlasmioMenuItem("Grouped item", close, Modifier.testTag("groupedPopupItem"), backdrop = backdrop)
                                }
                            },
                        )
                        GlasmioTopBar(
                            title = "Single",
                            actions = {
                                GlasmioMenu(
                                    expanded = singleOpen.value,
                                    onExpandedChange = { singleOpen.value = it },
                                    itemCount = 1,
                                    menuAnchorState = singleSource,
                                    onMenuAlphaChanged = { singleAlpha = it },
                                    anchor = { open ->
                                        GlasmioButton(open, Modifier.width(132.dp).glasmioMenuAnchor(singleSource).testTag("singlePopupAnchor")) {
                                            androidx.compose.material3.Text(singleLabel.value, color = singleLabelColor.value)
                                        }
                                    },
                                ) { backdrop, close ->
                                    GlasmioMenuItem("Single item", close, Modifier.testTag("singlePopupItem"), backdrop = backdrop)
                                }
                            },
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        var fusedBounds = Rect.Zero
        compose.runOnIdle {
            val group = requireNotNull(groupedGroup)
            val run = group.sharedRuns().single()
            fusedBounds = requireNotNull(groupedAnchor).bounds!!
            assertEquals("The source covers the complete fused surface", group.visualBoundsInWindow(run), fusedBounds)
            assertTrue("Both action slots belong to the source", fusedBounds.width > run.first().bounds!!.width * 1.9f)
            val preview = requireNotNull(groupedAnchor).previewBounds!!
            assertTrue("The native pivot follows the trigger preview inside the fused source", preview.width < fusedBounds.width * 0.6f)
            assertTrue("The preview remains inside the complete source", fusedBounds.contains(preview.center))
        }
        compose.mainClock.autoAdvance = false
        fun advanceUntil(condition: () -> Boolean) {
            // Android measures a new Popup outside the Compose test clock. Let each
            // layout frame finish before checking the handoff or advancing its springs.
            compose.waitUntil(timeoutMillis = 5_000L) {
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
                compose.runOnIdle(condition)
            }
        }
        fun awaitCollapsed(tag: String, sourceRestored: () -> Boolean) {
            advanceUntil(sourceRestored)
            // Source restoration precedes Android removing the popup's semantic root.
            compose.waitUntil(timeoutMillis = 5_000L) {
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
                compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isEmpty()
            }
        }
        try {
            toolbarAction("groupedPopupAnchor").performClick()
            advanceUntil {
                val group = requireNotNull(groupedGroup)
                assertEquals("No detachment runs while the popup is being measured", 0f, group.separationProgress.value, 0.001f)
                assertEquals("The source stays fused throughout the handoff", 1, group.sharedRuns().size)
                requireNotNull(groupedAnchor).isAcquired
            }
            compose.onNodeWithTag("groupedPopupItem").assertExists()
            compose.mainClock.advanceTimeBy(96L)
            var openingAlpha = 0f
            compose.runOnIdle {
                val source = requireNotNull(groupedAnchor)
                val group = requireNotNull(groupedGroup)
                assertTrue("The menu acquires the whole source as soon as it opens", source.isAcquired)
                assertTrue("A fused source retains its foreground portal", source.portal != null)
                assertEquals(source.bounds!!.size, source.portal!!.sourceBounds.size)
                assertEquals(fusedBounds, source.bounds)
                assertTrue("Every fused action is hidden behind the menu", group.members.all {
                    group.isMenuSourceHidden(listOf(it))
                })
                assertEquals("Opening never separates the source", 0f, group.separationProgress.value, 0.001f)
                assertTrue("The menu begins appearing immediately", groupedAlpha > 0f)
                openingAlpha = groupedAlpha
                groupedOpen.value = false
            }
            compose.mainClock.advanceTimeBy(80L)
            compose.onNodeWithTag("groupedPopupItem").assertExists()
            var closingAlpha = 0f
            compose.runOnIdle {
                assertTrue("Closing reverses the existing transition", groupedAlpha < openingAlpha)
                assertTrue("The source stays acquired until the menu reaches it", requireNotNull(groupedAnchor).isAcquired)
                assertEquals("Interrupted closing does not finish the lifecycle", 0, groupedClosed)
                closingAlpha = groupedAlpha
            }
            val closingBounds = compose.onNodeWithTag("groupedPopupItem").getUnclippedBoundsInRoot()
            compose.runOnIdle { groupedOpen.value = true }
            compose.waitForIdle()
            val reopenedBounds = compose.onNodeWithTag("groupedPopupItem").getUnclippedBoundsInRoot()
            listOf(
                closingBounds.left to reopenedBounds.left,
                closingBounds.top to reopenedBounds.top,
                closingBounds.right to reopenedBounds.right,
                closingBounds.bottom to reopenedBounds.bottom,
            ).forEach { (before, after) ->
                assertEquals("Retargeting preserves the physical menu before the next frame", before.value, after.value, 0.5f)
            }
            compose.mainClock.advanceTimeBy(80L)
            compose.onNodeWithTag("groupedPopupItem").assertExists()
            compose.runOnIdle {
                assertTrue("Reopening continues from the closing animation", groupedAlpha > closingAlpha)
                assertTrue(requireNotNull(groupedAnchor).isAcquired)
                assertEquals(0, groupedClosed)
                groupedOpen.value = false
            }
            awaitCollapsed("groupedPopupItem") { !requireNotNull(groupedAnchor).isAcquired && groupedClosed == 1 }
            compose.onNodeWithTag("groupedPopupItem").assertDoesNotExist()
            compose.runOnIdle {
                assertFalse("A completed collapse restores the source", requireNotNull(groupedAnchor).isAcquired)
                assertTrue(requireNotNull(groupedGroup).members.none {
                    requireNotNull(groupedGroup).isMenuSourceHidden(listOf(it))
                })
                assertEquals(1, groupedClosed)
                assertEquals(0f, requireNotNull(groupedGroup).separationProgress.value, 0.001f)
                groupedStyle.value = GlasmioToolbarActionStyle.Separated
            }

            advanceUntil { requireNotNull(groupedGroup).separationProgress.value >= 0.999f }
            toolbarAction("groupedPopupAnchor").performClick()
            advanceUntil { requireNotNull(groupedAnchor).isAcquired }
            compose.onNodeWithTag("groupedPopupItem").assertExists()
            compose.mainClock.advanceTimeBy(64L)
            compose.runOnIdle {
                assertTrue("A separated action morphs only its own surface", requireNotNull(groupedAnchor).bounds!!.width < fusedBounds.width * 0.6f)
                assertEquals("The menu preserves the existing separated style", 1f, requireNotNull(groupedGroup).separationProgress.value, 0.001f)
                groupedOpen.value = false
            }
            awaitCollapsed("groupedPopupItem") { !requireNotNull(groupedAnchor).isAcquired && groupedClosed == 2 }

            toolbarAction("singlePopupAnchor").performClick()
            advanceUntil { requireNotNull(singleAnchor).isAcquired }
            compose.onNodeWithTag("singlePopupItem").assertExists()
            compose.mainClock.advanceTimeBy(1_000L)
            compose.runOnIdle {
                assertTrue("A standalone action immediately starts showing its popup", singleAlpha > 0f)
                assertTrue("The standalone source uses the same handoff", requireNotNull(singleAnchor).isAcquired)
                assertTrue("A standalone label retains a clean foreground portal", requireNotNull(singleAnchor).portal != null)
                singleLabel.value = "Latest"
                singleLabelColor.value = Color.Green
                singleOpen.value = false
            }
            compose.mainClock.advanceTimeBy(240L)
            compose.waitForIdle()
            compose.runOnIdle {
                assertTrue("The closing source is still owned by the liquid surface", requireNotNull(singleAnchor).isAcquired)
            }
            val closingImage = compose.onNode(isPopup()).captureToImage().toPixelMap()
            var labelPixels = 0
            for (y in 0 until closingImage.height) for (x in 0 until closingImage.width) {
                val pixel = closingImage[x, y]
                if (pixel.green > 0.4f && maxOf(pixel.red, pixel.blue) < pixel.green * 0.5f) labelPixels++
            }
            assertTrue("The returning capsule draws its live label before releasing the source", labelPixels > 20)
            awaitCollapsed("singlePopupItem") { !requireNotNull(singleAnchor).isAcquired }
            compose.onNodeWithTag("singlePopupItem").assertDoesNotExist()
            compose.runOnIdle { assertFalse(requireNotNull(singleAnchor).isAcquired) }
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
                                GlasmioSwitch(
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
