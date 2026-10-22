package com.ljyh.mei.ui.component.player.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.LayerOutsets
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.click
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ljyh.mei.R
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.ui.component.player.component.classic.component.PlayerHeader
import com.ljyh.mei.ui.component.player.component.applemusic.AppleMusicMarqueeText
import com.ljyh.mei.ui.component.player.component.applemusic.AppleMusicDimensions
import com.ljyh.mei.ui.component.player.component.applemusic.Title
import com.ljyh.mei.ui.component.player.component.applemusic.playerInfoMotion
import com.ljyh.mei.ui.component.player.component.applemusic.queueViewportFade
import com.ljyh.mei.ui.component.player.component.applemusic.appleMusicLyricsControlsFadeRange
import com.ljyh.mei.ui.component.player.component.applemusic.playerQueueViewport
import com.ljyh.mei.ui.component.player.component.applemusic.AppleMusicDetailPages
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppleMusicTitleRenderingTest {
    @get:Rule val compose = createComposeRule()

    @Test fun favoritesKeepTheirTouchTargetAndAnimateBothDirectionsAcrossTrackChanges() {
        compose.mainClock.autoAdvance = false
        val classic = mutableStateOf(false)
        val local = mutableStateOf(false)
        val trackId = mutableStateOf(1L)
        val liked = mutableStateOf(false)
        val actionsOpacity = mutableStateOf(1f)
        val actionsProgress = mutableStateOf(0f)
        val likedTracks = mutableListOf<Long>()
        var moreClicks = 0
        var textRight = 0f
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val favorite = compose.onNodeWithContentDescription(
            context.getString(R.string.app_tab_library_songs), useUnmergedTree = true,
        )
        val more = compose.onNodeWithContentDescription(
            context.getString(R.string.more_actions_title), useUnmergedTree = true,
        )
        compose.setContent {
            val id = trackId.value
            MaterialTheme {
                val modifier = Modifier.width(360.dp).background(Color.Black).testTag("metadata")
                if (classic.value) {
                    PlayerHeader(
                        modifier = modifier,
                        mediaMetadata = MediaMetadata(
                            id, "Track $id", "", listOf(MediaMetadata.Artist(1, "Artist")),
                            1_000, MediaMetadata.Album(1, "Album"), isLocal = local.value,
                        ),
                        isLiked = liked.value,
                        onClick = {}, onMoreClick = { moreClicks++ },
                        onLikeClick = { likedTracks += id },
                        onTextPositioned = { textRight = it.boundsInRoot().right },
                    )
                } else {
                    Title(
                        title = "Track $id", subTitle = "Artist", isLiked = liked.value,
                        showLikeButton = !local.value,
                        onLikeClick = { likedTracks += id },
                        onMoreClick = { moreClicks++ }, onTitleClick = {},
                        marqueeEnabled = false,
                        onTextPositioned = { textRight = it.boundsInRoot().right },
                        actionsModifier = Modifier.playerInfoMotion(
                            actionsOpacity, actionsProgress, { Offset.Zero }, { Offset.Zero }, true,
                        ),
                        modifier = modifier,
                    )
                }
            }
        }
        for (useClassic in listOf(false, true)) {
            compose.runOnIdle {
                classic.value = useClassic
                local.value = false
                trackId.value = 1
                liked.value = false
                likedTracks.clear()
            }
            compose.mainClock.advanceTimeBy(700)
            compose.waitForIdle()
            val openRight = textRight
            val moreX = more.fetchSemanticsNode().boundsInRoot.center.x
            favorite.performTouchInput { click(center) }
            compose.runOnIdle { assertEquals(listOf(1L), likedTracks) }

            compose.runOnIdle { trackId.value = 2 }
            compose.mainClock.advanceTimeByFrame()
            favorite.performTouchInput { click(center) }
            compose.runOnIdle { assertEquals(listOf(1L, 2L), likedTracks) }

            compose.runOnIdle { liked.value = true }
            compose.mainClock.advanceTimeByFrame()
            val rootBounds = compose.onNodeWithTag("metadata").fetchSemanticsNode().boundsInRoot
            val favoriteBounds = favorite.fetchSemanticsNode().boundsInRoot.translate(-rootBounds.topLeft)
            val previousCenter = favoriteBounds.center
            compose.runOnIdle { local.value = true; liked.value = false }
            compose.mainClock.advanceTimeBy(32)
            compose.waitForIdle()
            val exitingRight = textRight
            val pixels = image().toPixelMap()
            var peak = 0f
            for (y in favoriteBounds.top.roundToInt() until favoriteBounds.bottom.roundToInt()) {
                for (x in favoriteBounds.left.roundToInt() until favoriteBounds.right.roundToInt()) {
                    peak = maxOf(peak, pixels[x, y].red)
                }
            }
            val centerIntensity = pixels[previousCenter.x.roundToInt(), previousCenter.y.roundToInt()].red
            assertTrue("The outgoing filled star must not become an outline", centerIntensity > peak * 0.6f)
            compose.onNodeWithTag("metadata").performTouchInput { click(previousCenter) }
            compose.runOnIdle { assertEquals("Local tracks must reject the outgoing favorite", 2, likedTracks.size) }
            more.performTouchInput { click(center) }
            compose.mainClock.advanceTimeBy(700)
            compose.waitForIdle()
            val closedRight = textRight
            assertTrue(
                "Disappearing favorites must resize the title gradually: " +
                    "useClassic=$useClassic, openRight=$openRight, exitingRight=$exitingRight, closedRight=$closedRight",
                exitingRight > openRight,
            )
            assertTrue(
                "Disappearing favorites must keep resizing: " +
                    "useClassic=$useClassic, openRight=$openRight, exitingRight=$exitingRight, closedRight=$closedRight",
                exitingRight < closedRight,
            )

            compose.runOnIdle { local.value = false; trackId.value = 3 }
            compose.mainClock.advanceTimeBy(32)
            compose.waitForIdle()
            assertTrue("Reappearing favorites must not jump to their full width", textRight > openRight)
            assertTrue("Text and favorite must start moving together", textRight < closedRight)
            val enteringPixels = image().toPixelMap()
            var leadingInk = false
            var trailingInk = false
            for (y in favoriteBounds.top.roundToInt() until favoriteBounds.bottom.roundToInt()) {
                for (x in favoriteBounds.left.roundToInt() until favoriteBounds.right.roundToInt()) {
                    if (enteringPixels[x, y].red > 0.04f) {
                        if (x < previousCenter.x) leadingInk = true else trailingInk = true
                    }
                }
            }
            assertTrue("The moving text boundary must not crop either side of the favorite", leadingInk && trailingInk)
            assertEquals("More must remain anchored", moreX, more.fetchSemanticsNode().boundsInRoot.center.x, 1f)
            compose.mainClock.advanceTimeBy(700)
            favorite.performTouchInput { click(center) }
            compose.runOnIdle { assertEquals(listOf(1L, 2L, 3L), likedTracks) }

            compose.runOnIdle { local.value = true }
            compose.mainClock.advanceTimeBy(48)
            compose.runOnIdle { local.value = false; trackId.value = 4 }
            compose.mainClock.advanceTimeBy(700)
            favorite.performTouchInput { click(center) }
            compose.runOnIdle { assertEquals(listOf(1L, 2L, 3L, 4L), likedTracks) }
        }
        compose.runOnIdle { assertEquals(2, moreClicks) }
    }

    @Test fun detailPagesPreloadOnceAndRetainTheirStateAcrossSwitches() {
        val queueSelected = mutableStateOf(false)
        val preload = mutableStateOf(false)
        val entryCount = mutableStateOf(30)
        var lyricMounts = 0
        var queueMounts = 0
        var disposals = 0
        var queueScroll: LazyListState? = null
        compose.setContent {
            AppleMusicDetailPages(
                queueSelected = queueSelected.value,
                active = true,
                animate = true,
                preloadQueue = preload.value,
                modifier = Modifier.width(160.dp).height(240.dp),
                lyrics = {
                    DisposableEffect(Unit) {
                        lyricMounts++
                        onDispose { disposals++ }
                    }
                    Box(Modifier.fillMaxSize().testTag("retained_lyrics"))
                },
                queue = {
                    DisposableEffect(Unit) {
                        queueMounts++
                        onDispose { disposals++ }
                    }
                    val listState = rememberLazyListState()
                    queueScroll = listState
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().testTag("retained_queue")) {
                        items(entryCount.value) { Box(Modifier.fillMaxWidth().height(40.dp)) }
                    }
                },
            )
        }
        compose.runOnIdle {
            assertEquals(1, lyricMounts)
            assertEquals(0, queueMounts)
            preload.value = true
        }
        compose.runOnIdle {
            assertEquals(1, queueMounts)
            queueSelected.value = true
        }
        compose.onNodeWithTag("retained_queue").performScrollToIndex(10)
        compose.runOnIdle {
            assertEquals(10, queueScroll!!.firstVisibleItemIndex)
            queueSelected.value = false
        }
        compose.runOnIdle {
            // Timeline changes may still arrive while this page is hidden.
            entryCount.value = 31
        }
        compose.runOnIdle { queueSelected.value = true }
        compose.runOnIdle {
            assertEquals(31, queueScroll!!.layoutInfo.totalItemsCount)
            assertEquals(10, queueScroll!!.firstVisibleItemIndex)
            assertEquals(1, lyricMounts)
            assertEquals(1, queueMounts)
            assertEquals(0, disposals)
        }
    }

    @Test fun detailCrossfadeReversesWithoutAJumpAndBlocksInactiveInput() {
        compose.mainClock.autoAdvance = false
        val queueSelected = mutableStateOf(false)
        val active = mutableStateOf(true)
        var lyricClicks = 0
        var queueClicks = 0
        compose.setContent {
            Box(Modifier.width(160.dp).height(240.dp).background(Color.Black).testTag("detail_host")) {
                AppleMusicDetailPages(
                    queueSelected = queueSelected.value,
                    active = active.value,
                    animate = true,
                    preloadQueue = true,
                    modifier = Modifier.fillMaxSize(),
                    lyrics = {
                        Box(Modifier.fillMaxSize().background(Color.Red).clickable { lyricClicks++ })
                    },
                    queue = {
                        Box(Modifier.fillMaxSize().background(Color.Blue).clickable { queueClicks++ })
                    },
                )
            }
        }
        fun centerColor(): Color {
            val bitmap = compose.onNodeWithTag("detail_host").captureToImage()
            return bitmap.toPixelMap()[bitmap.width / 2, bitmap.height / 2]
        }
        compose.mainClock.advanceTimeBy(64)
        compose.onNodeWithTag("detail_host").performTouchInput { click(center) }
        compose.runOnIdle {
            assertEquals(1, lyricClicks)
            assertEquals(0, queueClicks)
            queueSelected.value = true
        }
        compose.mainClock.advanceTimeBy(80)
        val beforeIncoming = centerColor()
        assertTrue("The outgoing page must start fading immediately", beforeIncoming.red in 0f..0.99f)
        assertEquals("The incoming page must wait 100ms before fading in", 0f, beforeIncoming.blue, 0.01f)
        compose.mainClock.advanceTimeBy(160)
        val outgoing = centerColor()
        assertTrue(outgoing.red > 0f && outgoing.blue > 0f)
        compose.runOnIdle { queueSelected.value = false }
        compose.mainClock.advanceTimeByFrame()
        val reversed = centerColor()
        assertEquals(outgoing.red, reversed.red, 0.02f)
        assertEquals(outgoing.blue, reversed.blue, 0.02f)
        compose.onNodeWithTag("detail_host").performTouchInput { click(center) }
        compose.runOnIdle {
            assertEquals(0, queueClicks)
            assertEquals(1, lyricClicks)
        }
        compose.mainClock.advanceTimeBy(700)
        assertEquals(1f, centerColor().red, 0.01f)
        compose.onNodeWithTag("detail_host").performTouchInput { click(center) }
        compose.runOnIdle {
            assertEquals(2, lyricClicks)
            queueSelected.value = true
        }
        compose.mainClock.advanceTimeBy(450)
        val afterOutgoing = centerColor()
        assertEquals("The outgoing page must finish within 400ms", 0f, afterOutgoing.red, 0.01f)
        assertTrue("The incoming page must still be fading before 500ms", afterOutgoing.blue > 0f && afterOutgoing.blue < 1f)
        compose.mainClock.advanceTimeBy(150)
        assertEquals(1f, centerColor().blue, 0.01f)
        compose.onNodeWithTag("detail_host").performTouchInput { click(center) }
        compose.runOnIdle {
            assertEquals(1, queueClicks)
            active.value = false
        }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("detail_host").performTouchInput { click(center) }
        compose.runOnIdle { assertEquals(1, queueClicks) }
    }

    @Test fun queueViewportClipsBeforeControlsUnderFullHeightParentConstraints() {
        val entryOffset = mutableStateOf(0.dp)
        val additive = mutableStateOf(false)
        val background = Color(0.1f, 0.2f, 0.3f)
        compose.setContent {
            val density = LocalDensity.current
            Box(
                Modifier.width(160.dp).height(320.dp).background(background)
                    .testTag("queue_host"),
                propagateMinConstraints = true,
            ) {
                Box(Modifier.fillMaxWidth().playerQueueViewport(180.dp)) {
                    Box(
                        Modifier.fillMaxSize()
                            .graphicsLayer {
                                translationY = with(density) { entryOffset.value.toPx() }
                                blendMode = if (additive.value) BlendMode.Plus else BlendMode.SrcOver
                                compositingStrategy = CompositingStrategy.Offscreen
                            }
                            .background(Color.White),
                    )
                }
            }
        }
        for ((offset, plus) in listOf(0.dp to false, 48.dp to false, 48.dp to true, 0.dp to true)) {
            compose.runOnIdle {
                entryOffset.value = offset
                additive.value = plus
            }
            val bitmap = compose.onNodeWithTag("queue_host").captureToImage()
            val scale = bitmap.width / 160f
            val pixels = bitmap.toPixelMap()
            assertTrue("Queue content must remain visible above the controls", pixels[bitmap.width / 2, (100 * scale).roundToInt()].red > 0.95f)
            for (y in listOf(181, 240, 310)) {
                val actual = pixels[bitmap.width / 2, (y * scale).roundToInt()]
                assertEquals("Rows must not cover the control background at $y", background.red, actual.red, 0.01f)
                assertEquals(background.green, actual.green, 0.01f)
                assertEquals(background.blue, actual.blue, 0.01f)
            }
        }
    }

    @Test fun queueControlsFadeStaysVisibleAtTheEndOfAShortQueue() {
        compose.setContent {
            val listState = rememberLazyListState()
            val viewport = remember { mutableStateOf<LayoutCoordinates?>(null) }
            LazyColumn(
                state = listState,
                overscrollEffect = null,
                modifier = Modifier.width(160.dp).height(200.dp).background(Color.Black)
                    .testTag("queue_controls_viewport")
                    .onGloballyPositioned { viewport.value = it },
            ) {
                items(3) {
                    Box(
                        Modifier.fillMaxWidth().height(56.dp)
                            .queueViewportFade(
                                viewport.value, listState, { 0 },
                                controlsFadeRange = { height ->
                                    appleMusicLyricsControlsFadeRange(
                                        viewportHeight = height,
                                        shift = 0f,
                                        translationY = 0f,
                                        controlsTop = height * 0.8f,
                                    )
                                },
                            )
                            .background(Color.White),
                    )
                }
            }
        }
        val bitmap = compose.onNodeWithTag("queue_controls_viewport").captureToImage()
        val scale = bitmap.width / 160f
        val pixels = bitmap.toPixelMap()
        assertTrue("Content above the control gradient must remain opaque", pixels[bitmap.width / 2, (120 * scale).roundToInt()].red > 0.95f)
        assertEquals("The queue must use the lyrics' fade even when it cannot scroll", 0.5f, pixels[bitmap.width / 2, (145 * scale).roundToInt()].red, 0.05f)
    }

    @Test fun queueRemovalRevealsRowsPreviouslyMaskedAtTheBottom() {
        compose.mainClock.autoAdvance = false
        val entries = mutableStateListOf(0, 1, 2, 3, 4)
        compose.setContent {
            val listState = rememberLazyListState()
            val viewport = remember { mutableStateOf<LayoutCoordinates?>(null) }
            LazyColumn(
                state = listState,
                overscrollEffect = null,
                modifier = Modifier.width(160.dp).height(200.dp).background(Color.Black)
                    .testTag("queue_viewport")
                    .onGloballyPositioned { viewport.value = it },
            ) {
                entries.forEach { entry ->
                    item(key = entry) {
                        Box(Modifier.animateItem()) {
                            Box(
                                Modifier.fillMaxWidth().height(56.dp)
                                    .queueViewportFade(viewport.value, listState, { 0 })
                                    .background(if (entry == 3) Color.Blue else Color.White),
                            )
                        }
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        fun blueAt(y: Int): Float {
            val bitmap = compose.onNodeWithTag("queue_viewport").captureToImage()
            val scale = bitmap.width / 160f
            return bitmap.toPixelMap()[bitmap.width / 2, (y * scale).roundToInt()].blue
        }
        assertTrue("The lower part of row 3 must initially be in the bottom fade", blueAt(184) < 0.5f)
        compose.runOnIdle { entries.removeAt(0) }
        compose.mainClock.advanceTimeBy(2_000)
        assertTrue("The shifted row must lose its old bottom mask without scrolling", blueAt(120) > 0.95f)
    }

    @Test fun queueFadeCacheTracksScrollingAndStickyContentBoundsWithAdditiveBlending() {
        val contentTop = mutableStateOf(0.dp)
        val background = Color(0.1f, 0.2f, 0.3f)
        lateinit var listState: LazyListState
        var pixelsPerDp = 1f
        var endOffset = 0
        compose.setContent {
            val density = LocalDensity.current
            pixelsPerDp = density.density
            endOffset = with(density) { 600.dp.roundToPx() - 200.dp.roundToPx() }
            listState = rememberLazyListState(
                initialFirstVisibleItemScrollOffset = with(density) { 40.dp.roundToPx() },
            )
            val viewport = remember { mutableStateOf<LayoutCoordinates?>(null) }
            LazyColumn(
                state = listState,
                overscrollEffect = null,
                modifier = Modifier.width(160.dp).height(200.dp).background(background)
                    .testTag("queue_cached_fade")
                    .onGloballyPositioned { viewport.value = it },
            ) {
                item(key = "continuous_row") {
                    Box(
                        Modifier.fillMaxWidth().height(600.dp)
                            .queueViewportFade(
                                viewport.value, listState, { 0 }, additive = true,
                                contentTop = { with(density) { contentTop.value.toPx() } },
                            )
                            .background(Color(0.2f, 0.2f, 0.2f)),
                    )
                }
            }
        }
        fun assertContributions(phase: String, vararg samples: Pair<Int, Float>) {
            val bitmap = compose.onNodeWithTag("queue_cached_fade").captureToImage()
            val scale = bitmap.width / 160f
            val pixels = bitmap.toPixelMap()
            for ((y, contribution) in samples) {
                val actual = pixels[bitmap.width / 2, (y * scale).roundToInt()]
                assertEquals("$phase red at $y", background.red + contribution, actual.red, 0.025f)
                assertEquals("$phase green at $y", background.green + contribution, actual.green, 0.025f)
                assertEquals("$phase blue at $y", background.blue + contribution, actual.blue, 0.025f)
            }
        }

        assertContributions("Initial scroll", 4 to 0.1f, 100 to 0.2f, 170 to 0.1f)
        compose.runOnIdle { listState.requestScrollToItem(0, (120f * pixelsPerDp).roundToInt()) }
        assertContributions("Same row after scrolling", 4 to 0.1f, 100 to 0.2f, 170 to 0.1f)
        compose.runOnIdle { contentTop.value = 160.dp }
        assertContributions("Moved sticky boundary", 100 to 0f, 164 to 0.1f, 172 to 0.2f, 190 to 0.1f)
        compose.runOnIdle {
            contentTop.value = 32.dp
            listState.requestScrollToItem(0, endOffset)
        }
        assertContributions("End of the same row", 16 to 0f, 36 to 0.1f, 100 to 0.2f, 190 to 0.2f)
    }

    private fun showMarquee(text: String) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(190.dp).background(Color.Black).testTag("metadata")) {
                    AppleMusicMarqueeText(
                        text = text,
                        style = TextStyle(fontSize = 17.sp, lineHeight = 21.sp),
                        color = Color.White,
                        edgeWidth = 26.dp,
                        enabled = true,
                        modifier = Modifier.padding(start = 26.dp),
                    )
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
    }

    private fun image() = compose.onNodeWithTag("metadata").captureToImage()

    @Test fun overflowingMetadataWaitsThenScrolls() {
        showMarquee("A long title with enough different words to overflow the available width")
        val initial = image()
        compose.mainClock.advanceTimeBy(2_500)
        assertTrue(samePixels(initial, image()))
        compose.mainClock.advanceTimeBy(2_000)
        assertFalse("Overflowing metadata must scroll after the initial wait", samePixels(initial, image()))
    }

    @Test fun shortMetadataStaysStill() {
        showMarquee("Short title")
        val initial = image()
        compose.mainClock.advanceTimeBy(8_000)
        assertTrue("Fitting metadata must not scroll or fade", samePixels(initial, image()))
    }

    @Test fun albumMarqueeDrawsIntoLeadingFade() =
        assertLeadingFadeVisible(AppleMusicDimensions.albumMarqueeEdgeWidth, LayoutDirection.Ltr)

    @Test fun headerMarqueeDrawsIntoLeadingFade() =
        assertLeadingFadeVisible(AppleMusicDimensions.headerMarqueeEdgeWidth, LayoutDirection.Ltr)

    @Test fun rtlAlbumMarqueeDrawsIntoLeadingFade() =
        assertLeadingFadeVisible(AppleMusicDimensions.albumMarqueeEdgeWidth, LayoutDirection.Rtl)

    @Test fun rtlHeaderMarqueeDrawsIntoLeadingFade() =
        assertLeadingFadeVisible(AppleMusicDimensions.headerMarqueeEdgeWidth, LayoutDirection.Rtl)

    private fun assertLeadingFadeVisible(edgeWidth: Dp, direction: LayoutDirection) {
        compose.mainClock.autoAdvance = false
        val opacity = mutableStateOf(1f)
        val progress = mutableStateOf(0f)
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalLayoutDirection provides direction) {
                    val destination = Offset(0f, with(LocalDensity.current) { 48.dp.toPx() })
                    val outsets = if (direction == LayoutDirection.Rtl) {
                        LayerOutsets(right = edgeWidth)
                    } else LayerOutsets(left = edgeWidth)
                    Box(
                        Modifier.width(360.dp).height(160.dp).background(Color.Black)
                            .testTag("metadata").padding(horizontal = 40.dp),
                    ) {
                        Title(
                            title = "M".repeat(80),
                            subTitle = "M".repeat(80),
                            isLiked = false,
                            onLikeClick = {}, onMoreClick = {}, onTitleClick = {},
                            titleStyle = TextStyle(fontSize = 18.sp, lineHeight = 24.sp),
                            subTitleStyle = TextStyle(fontSize = 17.sp, lineHeight = 24.sp),
                            needShadow = false,
                            marqueeEdgeWidth = edgeWidth,
                            textModifier = Modifier.playerInfoMotion(
                                opacity, progress, { Offset.Zero }, { destination }, true,
                                layerOutsets = outsets,
                            ),
                            modifier = Modifier.width(280.dp),
                        )
                    }
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        fun hasLeadingInk(): Boolean {
            val bitmap = image()
            val pixels = bitmap.toPixelMap()
            val scale = bitmap.width / 360f
            val start = if (direction == LayoutDirection.Ltr) 40f - edgeWidth.value else 320f
            val end = if (direction == LayoutDirection.Ltr) 40f else 320f + edgeWidth.value
            for (x in (start * scale).roundToInt() until (end * scale).roundToInt()) {
                for (y in 0 until bitmap.height) {
                    if (pixels[x, y].red > 0.05f) return true
                }
            }
            return false
        }
        assertFalse("Resting metadata must retain its original leading alignment", hasLeadingInk())
        compose.mainClock.advanceTimeBy(5_000)
        assertTrue("Moving metadata must enter the leading fade outside its parent bounds", hasLeadingInk())
        compose.runOnIdle {
            progress.value = 0.5f
            opacity.value = 0.5f
        }
        compose.mainClock.advanceTimeByFrame()
        assertTrue("The leading fade must remain visible during metadata motion", hasLeadingInk())
    }

    @Test fun pressingMetadataDoesNotDarkenItAndStillInvokesTheAction() {
        compose.mainClock.autoAdvance = false
        var clicks = 0
        compose.setContent {
            MaterialTheme {
                Title(
                    title = "Short title",
                    subTitle = "Artist",
                    isLiked = false,
                    onLikeClick = {},
                    onMoreClick = {},
                    onTitleClick = { clicks++ },
                    needShadow = false,
                    marqueeEnabled = false,
                    modifier = Modifier.width(360.dp).background(Color.Black).testTag("metadata"),
                )
            }
        }
        val initial = image()
        compose.onNodeWithText("Short title").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(300)
        assertTrue("The title press must not add a dark overlay", samePixels(initial, image()))
        compose.onNodeWithText("Short title").performTouchInput { up() }
        compose.runOnIdle { assertEquals(1, clicks) }
    }

    @Test fun movingMetadataKeepsAdditiveBlendingOutsideItsOriginalRow() {
        val opacity = mutableStateOf(1f)
        val progress = mutableStateOf(0f)
        val background = Color(0.10f, 0.25f, 0.40f)
        compose.setContent {
            MaterialTheme {
                val destination = Offset(0f, with(LocalDensity.current) { 96.dp.toPx() })
                val motion = Modifier.playerInfoMotion(opacity, progress, { Offset.Zero }, { destination }, true)
                Box(
                    Modifier.width(360.dp).height(220.dp)
                        .background(background).testTag("metadata-blend-background"),
                ) {
                    Title(
                        title = "Title",
                        subTitle = "MMMM",
                        isLiked = false,
                        onLikeClick = {},
                        onMoreClick = {},
                        onTitleClick = {},
                        titleStyle = TextStyle(fontSize = 22.sp, lineHeight = 28.sp),
                        subTitleStyle = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
                        needShadow = false,
                        marqueeEnabled = false,
                        textModifier = motion,
                        actionsModifier = motion,
                        modifier = Modifier.width(360.dp),
                    )
                }
            }
        }

        val artist = compose.onNodeWithText("MMMM", useUnmergedTree = true)
        val originalTop = artist.getUnclippedBoundsInRoot().top.value
        for ((fraction, alpha) in listOf(0f to 1f, 0.5f to 0.5f, 1f to 0.25f, 1f to 1f, 0f to 0.75f)) {
            compose.runOnIdle {
                progress.value = fraction
                opacity.value = alpha
            }
            val root = compose.onNodeWithTag("metadata-blend-background")
            val rootBounds = root.getUnclippedBoundsInRoot()
            val bounds = artist.getUnclippedBoundsInRoot()
            assertEquals("Artist must follow the metadata motion", originalTop + 96f * fraction, bounds.top.value, 1f)
            val pixels = root.captureToImage().toPixelMap()
            val pixelsPerDp = pixels.width / (rootBounds.right - rootBounds.left).value
            val left = ((bounds.left - rootBounds.left).value * pixelsPerDp).roundToInt().coerceIn(0, pixels.width)
            val right = ((bounds.right - rootBounds.left).value * pixelsPerDp).roundToInt().coerceIn(0, pixels.width)
            val top = ((bounds.top - rootBounds.top).value * pixelsPerDp).roundToInt().coerceIn(0, pixels.height)
            val bottom = ((bounds.bottom - rootBounds.top).value * pixelsPerDp).roundToInt().coerceIn(0, pixels.height)
            var glyph = background
            for (y in top until bottom) for (x in left until right) {
                if (pixels[x, y].red > glyph.red) glyph = pixels[x, y]
            }
            // Sample the background with the artist so a flattened fade cannot pass.
            val contribution = 0.4f * alpha
            assertEquals("Artist red at motion $fraction", background.red + contribution, glyph.red, 0.03f)
            assertEquals("Artist green at motion $fraction", background.green + contribution, glyph.green, 0.03f)
            assertEquals("Artist blue at motion $fraction", background.blue + contribution, glyph.blue, 0.03f)
        }
    }

    private fun samePixels(first: ImageBitmap, second: ImageBitmap): Boolean {
        if (first.width != second.width || first.height != second.height) return false
        val a = first.toPixelMap()
        val b = second.toPixelMap()
        for (y in 0 until a.height) for (x in 0 until a.width) {
            if (a[x, y] != b[x, y]) return false
        }
        return true
    }
}
