package com.ljyh.mei.ui.component.player.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.LayerOutsets
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
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
import androidx.compose.ui.test.swipe
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ljyh.mei.R
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.ui.component.player.state.rememberPlayerLikeState
import com.ljyh.mei.ui.component.player.component.classic.component.PlayerHeader
import com.ljyh.mei.ui.component.player.component.applemusic.AppleMusicMarqueeText
import com.ljyh.mei.ui.component.player.component.applemusic.AppleMusicDimensions
import com.ljyh.mei.ui.component.player.component.applemusic.Title
import com.ljyh.mei.ui.component.player.component.applemusic.playerInfoMotion
import com.ljyh.mei.ui.component.player.component.applemusic.playerArtworkAppearance
import com.ljyh.mei.ui.component.player.component.applemusic.appleMusicArtworkFrame
import com.ljyh.mei.ui.component.player.component.applemusic.appleMusicArtworkTransition
import com.ljyh.mei.ui.component.player.component.applemusic.appleMusicQueueArtworkTarget
import com.ljyh.mei.ui.component.player.component.applemusic.queueViewportFade
import com.ljyh.mei.ui.component.player.component.applemusic.appleMusicLyricsControlsFadeRange
import com.ljyh.mei.ui.component.player.component.applemusic.playerQueueViewport
import com.ljyh.mei.ui.component.player.component.applemusic.AppleMusicDetailPages
import com.ljyh.mei.ui.component.player.component.applemusic.rememberPlayerQueueHeaderPosition
import com.ljyh.mei.ui.component.player.component.applemusic.rememberPlayerQueueHeaderAnchor
import com.ljyh.mei.ui.component.player.component.applemusic.PlayerQueueHeaderAnchor
import com.ljyh.mei.ui.component.player.component.applemusic.PlayerQueueCurrentHeaderKey
import com.ljyh.mei.ui.component.player.component.applemusic.PlayerQueueHeaderPosition
import com.ljyh.mei.ui.component.player.component.applemusic.PhoneQueueEntry
import com.ljyh.mei.ui.component.player.component.applemusic.QueueSongRow
import com.ljyh.mei.ui.component.player.component.applemusic.QueueRowPlacement
import com.ljyh.mei.ui.component.player.component.applemusic.QueueRowsSnapshot
import com.ljyh.mei.ui.component.player.component.applemusic.queueRowPlacement
import com.ljyh.mei.ui.component.player.component.applemusic.AppleMusicArtworkFrame
import com.ljyh.mei.ui.component.player.component.applemusic.playerArtworkMotion
import com.ljyh.mei.ui.component.sheet.LocalPlayerSheet
import com.ljyh.mei.ui.component.sheet.PlayerArtworkGeometry
import com.ljyh.mei.ui.component.sheet.expandedAnchor
import com.ljyh.mei.ui.component.sheet.playerArtwork
import com.ljyh.mei.ui.component.sheet.rememberBottomSheetState
import com.ljyh.mei.ui.component.sheet.rememberPlayerSheetLayers
import com.kyant.capsule.ContinuousRoundedRectangle
import com.ljyh.mei.ui.component.player.component.applemusic.animatePlayerQueueRows
import com.ljyh.mei.ui.component.player.component.applemusic.PlayerQueueTopOverscroll
import com.ljyh.mei.ui.component.player.component.applemusic.PlayerQueueSnapEffect
import com.ljyh.mei.ui.component.player.component.applemusic.playerQueueSnapTarget
import androidx.media3.common.MediaItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppleMusicTitleRenderingTest {
    @get:Rule val compose = createComposeRule()

    @Test fun favoritesSwitchImmediatelyAndKeepCurrentTrackCallbacks() {
        compose.mainClock.autoAdvance = false
        val classic = mutableStateOf(false)
        val local = mutableStateOf(false)
        val podcast = mutableStateOf(false)
        val trackId = mutableStateOf(1L)
        val liked = mutableStateOf<Resource<Boolean>>(Resource.Success(false))
        var renderedLiked = false
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
            val isLiked = rememberPlayerLikeState(liked.value).value
            SideEffect { renderedLiked = isLiked }
            MaterialTheme {
                val modifier = Modifier.width(360.dp).background(Color.Black).testTag("metadata")
                if (classic.value) {
                    PlayerHeader(
                        modifier = modifier,
                        mediaMetadata = MediaMetadata(
                            id, "Track $id", "", listOf(MediaMetadata.Artist(1, "Artist")),
                            1_000, MediaMetadata.Album(1, "Album"), isLocal = local.value, isPodcast = podcast.value,
                        ),
                        isLiked = isLiked,
                        onClick = {}, onMoreClick = { moreClicks++ },
                        onLikeClick = { likedTracks += id },
                        onTextPositioned = { textRight = it.boundsInRoot().right },
                    )
                } else {
                    Title(
                        title = "Track $id", subTitle = "Artist", isLiked = isLiked,
                        showLikeButton = !local.value && !podcast.value,
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
                podcast.value = false
                trackId.value = 1
                liked.value = Resource.Success(false)
                likedTracks.clear()
            }
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            val openRight = textRight
            val moreX = more.fetchSemanticsNode().boundsInRoot.center.x
            favorite.performTouchInput { click(center) }
            compose.runOnIdle { assertEquals(listOf(1L), likedTracks) }

            compose.runOnIdle { liked.value = Resource.Success(true) }
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            compose.runOnIdle { assertTrue(renderedLiked) }
            compose.runOnIdle {
                trackId.value = 2
                liked.value = Resource.Loading
            }
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            compose.runOnIdle { assertTrue("Loading the next track must retain the filled star", renderedLiked) }
            compose.runOnIdle { liked.value = Resource.Success(true) }
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            compose.runOnIdle { assertTrue("Two liked tracks must not show an empty star between them", renderedLiked) }
            favorite.performTouchInput { click(center) }
            compose.runOnIdle { assertEquals(listOf(1L, 2L), likedTracks) }

            compose.runOnIdle { liked.value = Resource.Success(false) }
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            compose.runOnIdle { assertFalse("A confirmed unlike must still update the star", renderedLiked) }

            for (isPodcast in listOf(false, true)) {
                val previousCenter = favorite.fetchSemanticsNode().boundsInRoot.center -
                    compose.onNodeWithTag("metadata").fetchSemanticsNode().boundsInRoot.topLeft
                compose.runOnIdle {
                    local.value = !isPodcast
                    podcast.value = isPodcast
                    liked.value = Resource.Success(false)
                }
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
                favorite.assertDoesNotExist()
                val closedRight = textRight
                assertTrue("Hidden favorites must release text space immediately", closedRight > openRight)
                assertEquals("More must remain anchored", moreX, more.fetchSemanticsNode().boundsInRoot.center.x, 1f)
                compose.onNodeWithTag("metadata").performTouchInput { click(previousCenter) }
                val expectedLikes = if (isPodcast) listOf(1L, 2L, 3L) else listOf(1L, 2L)
                compose.runOnIdle { assertEquals(expectedLikes, likedTracks) }
                more.performTouchInput { click(center) }

                compose.runOnIdle {
                    local.value = false
                    podcast.value = false
                    trackId.value = if (isPodcast) 4L else 3L
                    liked.value = Resource.Success(true)
                }
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
                favorite.assertExists()
                assertEquals("Online tracks must reserve favorite space immediately", openRight, textRight, 1f)
                assertEquals("More must remain anchored", moreX, more.fetchSemanticsNode().boundsInRoot.center.x, 1f)
                favorite.performTouchInput { click(center) }
                compose.runOnIdle {
                    assertEquals(if (isPodcast) listOf(1L, 2L, 3L, 4L) else listOf(1L, 2L, 3L), likedTracks)
                }
            }
        }
        compose.runOnIdle { assertEquals(4, moreClicks) }
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
        compose.mainClock.advanceTimeBy(48)
        val beforeIncoming = centerColor()
        assertTrue("The outgoing page must start fading immediately", beforeIncoming.red in 0f..0.99f)
        assertEquals("The incoming page must wait 50ms before fading in", 0f, beforeIncoming.blue, 0.01f)
        compose.mainClock.advanceTimeBy(104)
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
        compose.mainClock.advanceTimeBy(272)
        val afterOutgoing = centerColor()
        assertEquals("The outgoing page must finish within 250ms", 0f, afterOutgoing.red, 0.01f)
        assertTrue("The incoming page must still be fading before 300ms", afterOutgoing.blue > 0f && afterOutgoing.blue < 1f)
        compose.mainClock.advanceTimeBy(128)
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

    @Test fun queueCoverReadsTheCurrentLayoutDuringForwardAndReverseScrolling() {
        compose.mainClock.autoAdvance = false
        var listState: LazyListState? = null
        var scope: CoroutineScope? = null
        var viewport: LayoutCoordinates? = null
        var header: LayoutCoordinates? = null
        var drawnTop = Float.NaN
        compose.setContent {
            val state = rememberLazyListState()
            listState = state
            scope = rememberCoroutineScope()
            val headerHeight = with(LocalDensity.current) { 68.dp.toPx() }
            val position = rememberPlayerQueueHeaderPosition(state, PlayerQueueCurrentHeaderKey, 1, headerHeight)
            Box(Modifier.width(160.dp).height(240.dp).onGloballyPositioned { viewport = it }) {
                LazyColumn(state = state, modifier = Modifier.width(80.dp).fillMaxSize()) {
                    item(key = "history") { Box(Modifier.fillMaxWidth().height(160.dp)) }
                    item(key = PlayerQueueCurrentHeaderKey) {
                        Box(Modifier.fillMaxWidth().height(68.dp).onGloballyPositioned { header = it })
                    }
                    item(key = "upcoming") { Box(Modifier.fillMaxWidth().height(800.dp)) }
                }
                Box(Modifier.width(40.dp).height(68.dp).graphicsLayer {
                    translationX = 100.dp.toPx()
                    translationY = position.value.top
                    drawnTop = translationY
                }.background(Color.White))
            }
        }
        compose.mainClock.advanceTimeByFrame()
        for ((index, offset) in listOf(1 to 20, 0 to 0, 1 to 0)) {
            compose.runOnIdle { scope!!.launch { listState!!.animateScrollToItem(index, offset) } }
            repeat(40) {
                compose.mainClock.advanceTimeByFrame()
                compose.runOnIdle {
                    // Compare origins before viewport clipping when the header crosses the top edge.
                    val actual = header!!.localToRoot(Offset.Zero).y - viewport!!.localToRoot(Offset.Zero).y
                    assertEquals("Cover and metadata must use the same layout frame", actual, drawnTop, 1f)
                }
            }
        }
    }

    @Test fun queueCoverLayerKeepsObservingAcrossTrackReplacementAndInterruptedScroll() {
        compose.mainClock.autoAdvance = false
        val coverId = mutableStateOf(0)
        val currentIndex = mutableStateOf(2)
        val queueProgress = Animatable(1f)
        val detailProgress = Animatable(1f)
        lateinit var listState: LazyListState
        lateinit var scope: CoroutineScope
        lateinit var expectedFrame: State<AppleMusicArtworkFrame>
        var host: LayoutCoordinates? = null
        var cover: LayoutCoordinates? = null
        compose.setContent {
            val density = LocalDensity.current
            val state = rememberLazyListState()
            listState = state
            scope = rememberCoroutineScope()
            val sheet = rememberBottomSheetState(0.dp, 400.dp, 48.dp, expandedAnchor)
            val layers = rememberPlayerSheetLayers(sheet)
            val layoutSize = with(density) { 140.dp.roundToPx() }
            val normalBounds = with(density) { Rect(60.dp.toPx(), 80.dp.toPx(), 200.dp.toPx(), 220.dp.toPx()) }
            val lyricBounds = with(density) { Rect(100.dp.toPx(), 20.dp.toPx(), 168.dp.toPx(), 88.dp.toPx()) }
            val current = currentIndex.value
            val position = rememberPlayerQueueHeaderPosition(state, PlayerQueueCurrentHeaderKey, current, lyricBounds.height)
            val publishedPosition = remember { mutableStateOf<State<PlayerQueueHeaderPosition>?>(null) }
            SideEffect { publishedPosition.value = position }
            val queueTarget = remember(lyricBounds) {
                derivedStateOf {
                    val currentPosition = publishedPosition.value?.value ?: PlayerQueueHeaderPosition(0f, 0f)
                    appleMusicQueueArtworkTarget(
                        lyricBounds.translate(Offset(0f, currentPosition.top)),
                        lyricBounds.top, lyricBounds.top + currentPosition.viewportHeight,
                    )
                }
            }
            val headerBounds = remember(queueTarget) {
                derivedStateOf { androidx.compose.ui.geometry.lerp(lyricBounds, queueTarget.value.bounds, queueProgress.value) }
            }
            val frame = remember(headerBounds, normalBounds) {
                derivedStateOf {
                    appleMusicArtworkFrame(normalBounds, headerBounds.value, 12f, 8f, 16f, 1f, detailProgress.value)
                }
            }
            val headerOpacity = remember(queueTarget) {
                derivedStateOf { 1f - (1f - queueTarget.value.opacity) * queueProgress.value }
            }
            val opacity = remember(headerOpacity) {
                derivedStateOf { 1f - (1f - headerOpacity.value) * detailProgress.value }
            }
            expectedFrame = frame
            CompositionLocalProvider(LocalPlayerSheet provides layers) {
                Box(Modifier.width(240.dp).height(400.dp).onGloballyPositioned {
                    host = it
                    layers.updateHost(it.boundsInRoot())
                }) {
                    LazyColumn(state = state, modifier = Modifier.width(80.dp).height(240.dp)) {
                        items(count = current, key = { it }) { Box(Modifier.fillMaxWidth().height(56.dp)) }
                        item(key = PlayerQueueCurrentHeaderKey) { Box(Modifier.fillMaxWidth().height(68.dp)) }
                        items(count = 8 - current, key = { current + 1 + it }) { Box(Modifier.fillMaxWidth().height(56.dp)) }
                        item(key = "tail") { Box(Modifier.fillMaxWidth().height(240.dp)) }
                    }
                    AnimatedContent(
                        targetState = coverId.value,
                        transitionSpec = {
                            (fadeIn(tween(400)) + scaleIn(tween(400), initialScale = 0.92f))
                                .togetherWith(fadeOut(tween(400)))
                        },
                        label = "QueueCoverRegression",
                        modifier = Modifier.playerArtworkMotion { frame.value }.size(140.dp).playerArtwork(
                            geometry = { PlayerArtworkGeometry(frame.value.bounds, IntSize(layoutSize, layoutSize), opacity.value) },
                        ).graphicsLayer {
                            val current = frame.value
                            alpha = opacity.value
                            val scale = current.bounds.width / size.width.coerceAtLeast(1f)
                            shadowElevation = current.shadowElevation / scale.coerceAtLeast(0.001f)
                            shape = ContinuousRoundedRectangle(current.cornerRadius / scale.coerceAtLeast(0.001f))
                            clip = true
                        }.onGloballyPositioned { cover = it },
                    ) { id ->
                        Box(Modifier.fillMaxSize().background(if (id == 0) Color.Red else Color.Blue))
                    }
                }
            }
        }
        fun checkFrames(count: Int) = repeat(count) {
            compose.mainClock.advanceTimeByFrame()
            compose.runOnIdle {
                val actual = cover!!.localToRoot(Offset.Zero).y - host!!.localToRoot(Offset.Zero).y
                assertEquals("The rendered layer must follow the current frame", expectedFrame.value.bounds.top, actual, 1f)
            }
        }
        try {
            checkFrames(1)
            compose.runOnIdle { scope.launch { listState.animateScrollToItem(currentIndex.value) } }
            checkFrames(2)
            compose.runOnIdle {
                currentIndex.value = 5
                coverId.value = 1
            }
            checkFrames(1)
            compose.runOnIdle {
                scope.launch { listState.scroll(MutatePriority.UserInput) { scrollBy(-80f) } }
            }
            checkFrames(32)
            // No cover recomposition should be needed to restore later position updates.
            compose.runOnIdle { scope.launch { listState.scrollToItem(currentIndex.value, 20) } }
            checkFrames(2)
            compose.runOnIdle { scope.launch { queueProgress.animateTo(0f, tween(160)) } }
            checkFrames(12)
            compose.runOnIdle { assertEquals("Lyrics reaches its endpoint", 0f, queueProgress.value, 0f) }
            compose.runOnIdle { scope.launch { detailProgress.animateTo(0f, tween(160)) } }
            checkFrames(12)
            compose.runOnIdle { assertEquals("Album reaches its endpoint", 0f, detailProgress.value, 0f) }
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun queueHeaderKeepsItsSlotWhenTracksMoveBetweenHistoryAndUpcoming() {
        compose.mainClock.autoAdvance = false
        val order = mutableStateListOf(0, 1, 2, 3, 4, 5, 6, 7, 8)
        val current = mutableStateOf(2)
        val historyGap = mutableStateOf(0.dp)
        var listState: LazyListState? = null
        var scope: CoroutineScope? = null
        var viewport: LayoutCoordinates? = null
        var header: LayoutCoordinates? = null
        var coverTop = Float.NaN
        var positionObserver: State<PlayerQueueHeaderPosition>? = null
        var initialPositionObserver: State<PlayerQueueHeaderPosition>? = null
        var selectTrack: ((Int) -> Unit)? = null
        val snappingEnabled = mutableStateOf(false)
        val rowsMoving = mutableStateOf(false)
        val contentRevision = mutableStateOf(0)
        var currentAnchor: PlayerQueueHeaderAnchor? = null
        compose.setContent {
            val renderedRevision = contentRevision.value
            val index = order.indexOf(current.value)
            val state = rememberLazyListState(initialFirstVisibleItemIndex = index)
            listState = state
            val currentScope = rememberCoroutineScope()
            scope = currentScope
            val height = with(LocalDensity.current) { 68.dp.toPx() }
            val rowHeight = with(LocalDensity.current) { 56.dp.toPx() }
            val gap = with(LocalDensity.current) { historyGap.value.toPx() }
            val headerIndex = index + if (gap > 0f) 1 else 0
            val position = rememberPlayerQueueHeaderPosition(state, PlayerQueueCurrentHeaderKey, headerIndex, height)
            val anchor = rememberPlayerQueueHeaderAnchor(
                state, current.value.toString(), true,
                historyTailKey = order.getOrNull(index - 1)?.toString(), historyGap = gap,
                contentRevision = renderedRevision,
            )
            currentAnchor = anchor
            val scrollObserver = remember(anchor, currentScope) {
                PlayerQueueTopOverscroll(
                    currentScope, 24f, isAtTop = { false }, onListScroll = anchor::onScroll,
                ) { false }
            }
            PlayerQueueSnapEffect(
                state, snappingEnabled.value,
                canSnap = { !rowsMoving.value && !anchor.isPending },
                isReordering = { false },
                coverTop = {
                    state.layoutInfo.visibleItemsInfo.firstOrNull { it.key == PlayerQueueCurrentHeaderKey }
                        ?.offset?.toFloat() ?: if (state.firstVisibleItemIndex > headerIndex) -height - 1f
                        else state.layoutInfo.viewportEndOffset + 1f
                },
                snapTarget = { top, travel ->
                    val threshold = rowHeight / 7f
                    playerQueueSnapTarget(top, height, state.layoutInfo.viewportEndOffset.toFloat(),
                        when { travel >= threshold -> 1; travel <= -threshold -> -1; else -> 0 },
                        hasHistory = index > 0, canPinUpcoming = true)
                },
            )
            selectTrack = { track ->
                val next = order.indexOf(track)
                val oldTop = QueueRowsSnapshot(
                    order.map { PhoneQueueEntry(it.toString(), MediaItem.Builder().build(), it) },
                    current.value.toString(),
                    state.layoutInfo.visibleItemsInfo.associate { it.key.toString() to it.offset.toFloat() },
                    state.layoutInfo.viewportEndOffset.toFloat(), rowHeight, gap, null, false, 0f,
                ).headerTop(height, 0f)
                // Match the player event: preserve old geometry before publishing the new queue.
                anchor.update(track.toString(), true, order.getOrNull(next - 1)?.toString(), gap,
                    headerTop = (oldTop ?: if (state.firstVisibleItemIndex > headerIndex) -height
                        else state.layoutInfo.viewportEndOffset.toFloat())
                        .coerceIn(-height, state.layoutInfo.viewportEndOffset.toFloat()),
                    contentRevision = contentRevision.value + 1)
                current.value = track
                contentRevision.value++
            }
            val entries = order.map { PhoneQueueEntry(it.toString(), MediaItem.Builder().build(), it) }
            val residentPosition = remember { mutableStateOf<State<PlayerQueueHeaderPosition>?>(null) }
            SideEffect {
                residentPosition.value = position
                positionObserver = position
                if (initialPositionObserver == null) initialPositionObserver = position
            }
            Box(Modifier.width(160.dp).height(240.dp).background(Color.Black)
                .testTag("queue_artwork_attachment").nestedScroll(scrollObserver).onGloballyPositioned { viewport = it }) {
                LazyColumn(state = state, modifier = Modifier.width(80.dp).fillMaxSize().then(anchor).onGloballyPositioned {
                    anchor.restore(renderedRevision) { layout ->
                        QueueRowsSnapshot(
                            entries, current.value.toString(),
                            layout.visibleItemsInfo.associate { it.key.toString() to it.offset.toFloat() },
                            layout.viewportEndOffset.toFloat(), rowHeight, gap, null, false, 0f,
                        ).headerTop(height, 0f)
                    }
                }) {
                    items(count = index, key = { order[it].toString() }) {
                        Box(Modifier.animateItem().fillMaxWidth().height(56.dp).background(Color.Blue))
                    }
                    if (gap > 0f) {
                        item(key = "history_gap") { Box(Modifier.height(historyGap.value)) }
                    }
                    item(key = PlayerQueueCurrentHeaderKey, contentType = "current_header") {
                        Box(Modifier.fillMaxWidth().height(68.dp).onGloballyPositioned { header = it })
                    }
                    items(count = order.size - index - 1, key = { order[index + 1 + it].toString() }) { row ->
                        val track = order[index + 1 + row]
                        Box(Modifier.animateItem().fillMaxWidth().height(56.dp)
                            .testTag("upcoming_$track").clickable { selectTrack!!(track) }.background(Color.Green))
                    }
                    item(key = "tail") { Box(Modifier.fillMaxWidth().height(240.dp)) }
                }
                AnimatedContent(current.value, transitionSpec = {
                    fadeIn(tween(400)).togetherWith(fadeOut(tween(400)))
                }, modifier = Modifier.width(40.dp).height(68.dp).graphicsLayer {
                    translationX = 100.dp.toPx()
                    translationY = residentPosition.value?.value?.top ?: 0f
                    coverTop = translationY
                }) { track ->
                    Box(Modifier.fillMaxSize().background(if (track % 2 == 0) Color.White else Color.Red))
                }
            }
        }
        fun checkPinnedFrames(count: Int) {
            repeat(count) {
                compose.mainClock.advanceTimeByFrame()
                compose.runOnIdle {
                    val top = header!!.boundsInRoot().top - viewport!!.boundsInRoot().top
                    assertEquals("Track changes must not move the current header slot", 0f, top, 1f)
                    assertEquals("Artwork must remain attached to the header", top, coverTop, 1f)
                    assertSame("Playback changes must retain the resident position observer",
                        initialPositionObserver, positionObserver)
                    val visibleKeys = listState!!.layoutInfo.visibleItemsInfo.map { it.key }
                    assertEquals(1, visibleKeys.count { it == PlayerQueueCurrentHeaderKey })
                    assertFalse("The current track must not remain an ordinary row", current.value.toString() in visibleKeys)
                }
            }
        }
        checkPinnedFrames(2)
        for (slot in listOf(1, 3, 2, 1, 3)) {
            compose.runOnIdle { selectTrack!!(2) }
            checkPinnedFrames(24)
            compose.onNodeWithTag("upcoming_${2 + slot}").performTouchInput { click(center) }
            checkPinnedFrames(24)
        }
        compose.runOnIdle {
            selectTrack!!(4)
            selectTrack!!(1)
            selectTrack!!(6)
        }
        checkPinnedFrames(24)
        for (track in listOf(3, 1, 0, 8, 4, 2)) {
            compose.runOnIdle { current.value = track }
            checkPinnedFrames(24)
        }
        for (track in listOf(5, 1, 6, 2)) {
            compose.runOnIdle { current.value = track }
            checkPinnedFrames(1)
        }
        compose.runOnIdle {
            val shuffled = listOf(7, 2, 0, 8, 3, 1, 4, 6, 5)
            order.clear()
            order.addAll(shuffled)
        }
        checkPinnedFrames(24)
        compose.runOnIdle { order.removeAll(order.take(order.indexOf(current.value)).toSet()) }
        checkPinnedFrames(24)
        compose.runOnIdle {
            current.value = 6
        }
        checkPinnedFrames(24)
        compose.runOnIdle { scope!!.launch { listState!!.scrollToItem(0) } }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { current.value = 5 }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle {
            assertEquals("Playback must not pull the user out of history", 0, listState!!.firstVisibleItemIndex)
        }

        fun checkBrowsingFrames(count: Int, expectedTop: Float? = null) {
            repeat(count) {
                compose.mainClock.advanceTimeByFrame()
                compose.runOnIdle {
                    val item = listState!!.layoutInfo.visibleItemsInfo.firstOrNull { it.key == PlayerQueueCurrentHeaderKey }
                    if (item != null) {
                        val top = header!!.boundsInRoot().top - viewport!!.boundsInRoot().top
                        assertEquals("A changing cover must follow the browsing header", top, coverTop, 1f)
                        if (expectedTop != null) {
                            assertEquals("Playback must preserve the held header offset", expectedTop, top, 1f)
                        }
                    }
                    assertSame(initialPositionObserver, positionObserver)
                }
                val bitmap = compose.onNodeWithTag("queue_artwork_attachment").captureToImage()
                val scale = bitmap.width / 160f
                val pixels = bitmap.toPixelMap()
                val centerY = (coverTop + 34f * scale).roundToInt()
                if (centerY in 2 until bitmap.height - 2) {
                    assertTrue("The live cover must draw at the current header position",
                        pixels[(120f * scale).roundToInt(), centerY].red > 0.5f)
                }
                for (y in 0 until bitmap.height step 4) {
                    if (y < coverTop - 2f || y > coverTop + 68f * scale + 2f) {
                        assertEquals("No artwork may remain at an earlier scroll position", 0f,
                            pixels[(120f * scale).roundToInt(), y].red, 0.02f)
                    }
                }
            }
        }
        compose.runOnIdle { current.value = 4 }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { scope!!.launch { listState!!.scrollToItem(order.indexOf(4) - 3, 12) } }
        checkBrowsingFrames(2)
        compose.onNodeWithTag("queue_artwork_attachment").performTouchInput {
            down(Offset(width * 0.25f, height * 0.75f))
            moveBy(Offset(0f, -height * 0.1f))
        }
        compose.mainClock.advanceTimeByFrame()
        var heldTop = 0f
        compose.runOnIdle {
            assertTrue("The original pointer must still own the drag", listState!!.isScrollInProgress)
            heldTop = header!!.boundsInRoot().top - viewport!!.boundsInRoot().top
        }
        for (track in listOf(5, 3, 6, 4, 5, 4)) {
            compose.runOnIdle { current.value = track }
            checkBrowsingFrames(8, heldTop)
            compose.runOnIdle { assertTrue("Track changes must not cancel the drag", listState!!.isScrollInProgress) }
        }
        compose.runOnIdle {
            selectTrack!!(6)
            val consumed = listState!!.dispatchRawDelta(8f)
            currentAnchor!!.onScroll(-consumed)
            heldTop -= consumed
            currentAnchor!!.restore(contentRevision.value - 1) {
                error("An old-content layout must never consume the pending correction")
            }
            assertTrue("The old layout must leave the correction pending", currentAnchor!!.isPending)
        }
        checkBrowsingFrames(8, heldTop)
        compose.onNodeWithTag("queue_artwork_attachment").performTouchInput {
            moveBy(Offset(0f, -height * 0.1f))
        }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle {
            val movedTop = header!!.boundsInRoot().top - viewport!!.boundsInRoot().top
            assertTrue("The same pointer must continue moving the header", movedTop < heldTop - 4f)
        }
        compose.onNodeWithTag("queue_artwork_attachment").performTouchInput { up() }
        compose.runOnIdle { scope!!.launch { listState!!.scrollToItem(0) } }
        checkBrowsingFrames(2)
        for (track in listOf(2, 5, 1, 6)) {
            compose.runOnIdle {
                current.value = track
                scope!!.launch { listState!!.animateScrollToItem(order.indexOf(track), 20) }
            }
            checkBrowsingFrames(8)
        }
        compose.runOnIdle { scope!!.launch { listState!!.animateScrollToItem(order.size, 80) } }
        checkBrowsingFrames(40)
        compose.runOnIdle {
            current.value = 2
            scope!!.launch { listState!!.animateScrollToItem(0) }
        }
        checkBrowsingFrames(40)

        compose.runOnIdle {
            historyGap.value = 16.dp
            current.value = 5
        }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { scope!!.launch { listState!!.scrollToItem(order.indexOf(5) - 4) } }
        fun checkHistoryEndpoint(count: Int) {
            repeat(count) {
                checkBrowsingFrames(1)
                compose.runOnIdle {
                    val layout = listState!!.layoutInfo
                    val tailKey = order[order.indexOf(current.value) - 1]
                    val tail = layout.visibleItemsInfo.first { it.key == tailKey.toString() }
                    val gap = viewport!!.size.height / 240f * 16f
                    assertEquals("The history endpoint must stay at the viewport bottom",
                        layout.viewportEndOffset.toFloat(), tail.offset + tail.size + gap, 1f)
                    assertTrue("Previous must not pull the current header into history",
                        layout.visibleItemsInfo.none {
                            it.key == PlayerQueueCurrentHeaderKey && it.offset < layout.viewportEndOffset
                        })
                }
            }
        }
        checkHistoryEndpoint(2)
        for (track in listOf(6, 4, 1, 4, 6, 5)) {
            compose.runOnIdle { current.value = track }
            checkHistoryEndpoint(24)
        }

        // Intermediate offsets also must not manufacture a gesture when playback finishes.
        for (start in listOf(0f, 240f, -68f, 30f, 170f, -50f)) {
            compose.runOnIdle {
                rowsMoving.value = true
                snappingEnabled.value = true
                selectTrack!!(6)
            }
            compose.mainClock.advanceTimeByFrame()
            compose.runOnIdle {
                val scale = viewport!!.size.height / 240f
                scope!!.launch {
                    listState!!.scrollToItem(order.indexOf(6) + 1, (-start * scale).roundToInt())
                }
            }
            compose.mainClock.advanceTimeByFrame()
            compose.runOnIdle {
                assertEquals(start * viewport!!.size.height / 240f, coverTop, 1f)
                selectTrack!!(5)
            }
            repeat(24) { compose.mainClock.advanceTimeByFrame() }
            compose.runOnIdle {
                assertEquals("Playback must preserve the existing browsing position",
                    start * viewport!!.size.height / 240f, coverTop, 1f)
                rowsMoving.value = false
            }
            repeat(60) { compose.mainClock.advanceTimeByFrame() }
            compose.runOnIdle {
                assertEquals("Finishing playback motion must not request a snap",
                    start * viewport!!.size.height / 240f, coverTop, 1f)
            }
        }
        compose.runOnIdle {
            rowsMoving.value = true
            scope!!.launch { listState!!.scrollToItem(order.indexOf(current.value) + 1) }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("queue_artwork_attachment").performTouchInput {
            down(Offset(width * 0.25f, height * 0.5f))
            moveBy(Offset(0f, height * 0.15f))
        }
        compose.mainClock.advanceTimeByFrame()
        var heldBeforeSnap = 0f
        compose.runOnIdle {
            heldBeforeSnap = coverTop
            rowsMoving.value = false
        }
        repeat(24) { compose.mainClock.advanceTimeByFrame() }
        compose.runOnIdle {
            assertTrue(listState!!.isScrollInProgress)
            assertEquals("Completing row motion must not snap a held drag", heldBeforeSnap, coverTop, 1f)
        }
        compose.onNodeWithTag("queue_artwork_attachment").performTouchInput {
            advanceEventTime(500)
            up()
        }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { rowsMoving.value = true }
        repeat(12) { compose.mainClock.advanceTimeByFrame() }
        compose.runOnIdle {
            rowsMoving.value = false
        }
        repeat(80) { compose.mainClock.advanceTimeByFrame() }
        compose.runOnIdle { assertEquals("Release must settle the visible header", 0f, coverTop, 1f) }

        compose.runOnIdle {
            order.addAll(9..59)
            selectTrack!!(20)
        }
        checkBrowsingFrames(24, 0f)
        compose.runOnIdle { scope!!.launch { listState!!.scrollToItem(order.indexOf(20) + 12) } }
        checkBrowsingFrames(2)
        for (track in listOf(45, 2, 40)) {
            compose.runOnIdle { selectTrack!!(track) }
            checkBrowsingFrames(24)
            compose.runOnIdle {
                assertEquals("Distant selection must retain the hidden upcoming edge",
                    -viewport!!.size.height / 240f * 68f, coverTop, 1f)
            }
        }
        compose.runOnIdle { scope!!.launch { listState!!.scrollToItem(0) } }
        checkBrowsingFrames(2)
        compose.runOnIdle { selectTrack!!(20) }
        checkBrowsingFrames(24)
        compose.runOnIdle {
            assertEquals("Distant history selection must retain the hidden history edge",
                viewport!!.size.height.toFloat(), coverTop, 1f)
        }
    }

    @Test fun selectingTheOnlyHistorySongAnimatesTheHeaderBackBeforeRemovingItsSpace() {
        checkEmptyHistoryReturn(keepPlayingKey = false)
    }

    @Test fun removingTheOnlyHistorySongReturnsWithoutChangingThePlayingKey() {
        checkEmptyHistoryReturn(keepPlayingKey = true)
    }

    @Test fun emptyHistoryReturnWaitsForTheHeldPointerToRelease() {
        checkEmptyHistoryReturn(keepPlayingKey = false, holdDuringReturn = true)
    }

    private fun checkEmptyHistoryReturn(keepPlayingKey: Boolean, holdDuringReturn: Boolean = false) {
        compose.mainClock.autoAdvance = false
        val hasHistory = mutableStateOf(true)
        val retainSpace = mutableStateOf(false)
        val pendingEntryAlignment = mutableStateOf(false)
        var drawnTop = 0f
        var viewportHeight = 0f
        var finished = false
        var headingTop = 0f
        var headingHeight = 0f
        var viewportCoordinates: LayoutCoordinates? = null
        var sawPinnedHeading = false
        var sawPushedHeading = false
        var queueState: LazyListState? = null
        var queueScope: CoroutineScope? = null
        var currentPull: PlayerQueueTopOverscroll? = null
        var selectOnlyHistory: (() -> Unit)? = null
        compose.setContent {
            val state = rememberLazyListState()
            val dragged = state.interactionSource.collectIsDraggedAsState()
            val progress = remember { Animatable(0f) }
            val height = with(LocalDensity.current) { 68.dp.toPx() }
            val fullHeight = with(LocalDensity.current) { 240.dp.toPx() }
            val headingSize = with(LocalDensity.current) { 32.dp.toPx() }
            val pullLimit = with(LocalDensity.current) { 24.dp.toPx() }
            val scope = rememberCoroutineScope()
            val pull = remember(scope, pullLimit) {
                PlayerQueueTopOverscroll(
                    scope, pullLimit,
                    isAtTop = {
                        state.layoutInfo.visibleItemsInfo.any { it.key == PlayerQueueCurrentHeaderKey && it.offset == 0 }
                    },
                    onUserInput = { pendingEntryAlignment.value = false },
                ) {
                    !hasHistory.value && !retainSpace.value
                }
            }
            queueState = state
            queueScope = scope
            currentPull = pull
            val index = if (hasHistory.value) 5 else if (retainSpace.value) 4 else 2
            val position = rememberPlayerQueueHeaderPosition(
                state, PlayerQueueCurrentHeaderKey, index, height, visualOffset = { pull.offset },
            )
            val anchor = rememberPlayerQueueHeaderAnchor(
                state, if (hasHistory.value || keepPlayingKey) "1" else "0", true,
                historyTailKey = if (hasHistory.value) "0" else null,
                historyGap = with(LocalDensity.current) { 16.dp.toPx() },
            )
            selectOnlyHistory = {
                anchor.update(if (keepPlayingKey) "1" else "0", true, null, 0f,
                    headerTop = fullHeight, layoutChanged = keepPlayingKey)
                retainSpace.value = true
                hasHistory.value = false
            }
            PlayerQueueSnapEffect(
                state, active = holdDuringReturn,
                canSnap = { finished && !anchor.isPending },
                isReordering = { false },
                coverTop = { position.value.top },
                snapTarget = { top, _ -> if (!hasHistory.value && top > 0f) 0f else null },
            )
            LaunchedEffect(hasHistory.value) {
                if (!hasHistory.value) {
                    snapshotFlow { state.layoutInfo to anchor.isPending }.first { (layout, pending) ->
                        !pending && layout.visibleItemsInfo.any {
                            it.key == "queue_history_padding" && it.size == (fullHeight - headingSize).roundToInt()
                        }
                    }
                    animatePlayerQueueRows(progress, state, fullHeight) { dragged.value }
                    if (!holdDuringReturn) {
                        assertEquals(0, state.layoutInfo.visibleItemsInfo.first { it.key == PlayerQueueCurrentHeaderKey }.offset)
                    }
                    finished = true
                }
            }
            LaunchedEffect(retainSpace.value) {
                if (!retainSpace.value) return@LaunchedEffect
                snapshotFlow {
                    val layout = state.layoutInfo
                    !state.isScrollInProgress &&
                        (layout.visibleItemsInfo.firstOrNull { it.key == PlayerQueueCurrentHeaderKey }
                            ?.let { it.offset <= 0 } ?: (state.firstVisibleItemIndex > index))
                }.first { it }
                retainSpace.value = false
            }
            Box(Modifier.width(160.dp).height(240.dp).background(Color.Black)
                .testTag("empty_history_pull_host").nestedScroll(pull)) {
                LazyColumn(
                    state = state,
                    overscrollEffect = null,
                    modifier = Modifier.width(80.dp).fillMaxSize().graphicsLayer { translationY = pull.offset }
                        .then(anchor).onGloballyPositioned {
                        viewportCoordinates = it
                        anchor.restore { layout ->
                            layout.visibleItemsInfo.firstOrNull { it.key == PlayerQueueCurrentHeaderKey }
                                ?.offset?.toFloat()
                                ?: layout.visibleItemsInfo.firstOrNull { it.key == "queue_history_padding" }
                                    ?.let { (it.offset + it.size).toFloat() }
                        }
                    },
                ) {
                    if (hasHistory.value || retainSpace.value) {
                        stickyHeader(key = "history_heading") {
                            Box(Modifier.fillMaxWidth().height(32.dp).testTag("retained_history_heading")
                                .onGloballyPositioned {
                                    headingTop = it.localToRoot(Offset.Zero).y - viewportCoordinates!!.localToRoot(Offset.Zero).y
                                    headingHeight = it.size.height.toFloat()
                                })
                        }
                        item(key = "queue_history_padding") {
                            Box(Modifier.height(if (hasHistory.value) 136.dp else 208.dp))
                        }
                    }
                    if (hasHistory.value) {
                        item(key = "0") {
                            Box(Modifier.fillMaxWidth().height(56.dp).testTag("only_history_song").clickable {
                                selectOnlyHistory!!()
                            }.background(Color.Blue))
                        }
                    }
                    item(key = "history_gap") { Box(Modifier.height(if (hasHistory.value) 16.dp else 0.dp)) }
                    stickyHeader(key = "current_boundary") { Box(Modifier.height(0.dp)) }
                    item(key = PlayerQueueCurrentHeaderKey) {
                        Box(Modifier.fillMaxWidth().height(68.dp).testTag("empty_history_current_header"))
                    }
                    item(key = "upcoming") {
                        androidx.compose.foundation.layout.Column(Modifier.height(400.dp)) {
                            Box(Modifier.fillMaxWidth().height(32.dp).testTag("pull_modes"))
                            Box(Modifier.fillMaxWidth().height(48.dp).testTag("pull_continue"))
                            Box(Modifier.fillMaxWidth().height(320.dp).testTag("pull_songs"))
                        }
                    }
                    item(key = "tail") { Box(Modifier.height(240.dp)) }
                }
                Box(Modifier.width(40.dp).height(68.dp).graphicsLayer {
                    viewportHeight = fullHeight
                    drawnTop = position.value.top
                    translationX = 100.dp.toPx()
                    translationY = drawnTop
                }.background(Color.White))
            }
        }
        compose.mainClock.advanceTimeByFrame()
        if (holdDuringReturn) {
            compose.onNodeWithTag("empty_history_pull_host").performTouchInput {
                down(Offset(width * 0.25f, height * 0.75f))
                moveBy(Offset(0f, height * 0.08f))
            }
            compose.mainClock.advanceTimeByFrame()
            compose.runOnIdle {
                assertTrue(queueState!!.isScrollInProgress)
                selectOnlyHistory!!()
            }
            repeat(30) {
                compose.mainClock.advanceTimeByFrame()
                compose.runOnIdle {
                    assertEquals("Playback must not return a header while the pointer is held", viewportHeight, drawnTop, 1f)
                    assertTrue(queueState!!.isScrollInProgress)
                }
            }
            compose.runOnIdle { assertTrue(finished) }
            compose.onNodeWithTag("empty_history_pull_host").performTouchInput {
                advanceEventTime(500)
                up()
            }
            repeat(80) { compose.mainClock.advanceTimeByFrame() }
            compose.runOnIdle { assertEquals("Release must finish the deferred header settle", 0f, drawnTop, 1f) }
            return
        }
        compose.onNodeWithTag("only_history_song").performTouchInput { click(center) }
        var previous = viewportHeight
        repeat(30) { frame ->
            compose.mainClock.advanceTimeByFrame()
            compose.runOnIdle {
                assertTrue("The header must return continuously", drawnTop <= previous + 1f)
                assertTrue("Removing the final history row must not teleport the header",
                    previous - drawnTop < viewportHeight * 0.4f)
                if (frame == 5) assertTrue("The header must still be moving midway through the return",
                    drawnTop > 0f && drawnTop < viewportHeight)
                if (!finished && drawnTop > 1f) {
                    assertEquals("The retained heading must use the normal sticky push-off boundary",
                        minOf(0f, drawnTop - headingHeight), headingTop, 1f)
                    if (drawnTop > headingHeight) sawPinnedHeading = true else sawPushedHeading = true
                }
                previous = drawnTop
            }
        }
        compose.runOnIdle {
            assertTrue(finished)
            assertTrue(sawPinnedHeading)
            assertTrue(sawPushedHeading)
            assertEquals("Removing the retained space must leave the header at the top", 0f, drawnTop, 1f)
        }
        compose.onNodeWithTag("retained_history_heading").assertDoesNotExist()
        val pullTags = listOf("empty_history_current_header", "pull_modes", "pull_continue", "pull_songs")
        val beforeFirstPull = pullTags.map {
            compose.onNodeWithTag(it).getUnclippedBoundsInRoot().top.value
        }
        compose.runOnIdle { pendingEntryAlignment.value = true }
        compose.onNodeWithTag("empty_history_pull_host").performTouchInput {
            down(Offset(width * 0.25f, height * 0.75f))
            moveBy(Offset(0f, height * 0.15f))
        }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle {
            assertFalse("The first downward input must cancel pending entry alignment", pendingEntryAlignment.value)
            assertTrue("The first gesture must immediately start the shared rebound", currentPull!!.offset > 0f)
            assertEquals(0, queueState!!.layoutInfo.visibleItemsInfo.first { it.key == PlayerQueueCurrentHeaderKey }.offset)
        }
        pullTags.zip(beforeFirstPull).forEach { (tag, before) ->
            val top = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot().top.value
            compose.runOnIdle {
                assertEquals("Header, modes, Continue Playing and songs must rebound with the cover",
                    drawnTop, (top - before) * viewportHeight / 240f, 1f)
            }
        }
        compose.onNodeWithTag("empty_history_pull_host").performTouchInput { up() }
        repeat(45) { compose.mainClock.advanceTimeByFrame() }
        compose.runOnIdle { assertEquals(0f, drawnTop, 1f) }
        compose.runOnIdle { queueScope!!.launch { queueState!!.scrollToItem(3) } }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { assertTrue(queueState!!.canScrollBackward) }
        compose.onNodeWithTag("empty_history_pull_host").performTouchInput {
            down(Offset(width * 0.25f, height * 0.25f))
            moveBy(Offset(0f, height * 0.1f))
        }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle {
            assertTrue("Upcoming rows must first scroll back toward the real top", queueState!!.canScrollBackward)
            assertEquals("Scrolling upcoming rows must not stretch the viewport", 0f, currentPull!!.offset, 0f)
        }
        compose.onNodeWithTag("empty_history_pull_host").performTouchInput {
            moveBy(Offset(0f, height * 0.5f))
        }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle {
            assertEquals("The same drag must reach the real list start", 0,
                queueState!!.layoutInfo.visibleItemsInfo.first { it.key == PlayerQueueCurrentHeaderKey }.offset)
            assertTrue("The empty-history top edge should resist a downward pull",
                drawnTop > 0f && drawnTop < viewportHeight * 0.1f)
        }
        val titleTop = compose.onNodeWithTag("empty_history_current_header").getUnclippedBoundsInRoot().top.value
        val hostTop = compose.onNodeWithTag("empty_history_pull_host").getUnclippedBoundsInRoot().top.value
        compose.runOnIdle {
            assertEquals("The resident cover and real list must share the rebound offset",
                (titleTop - hostTop) * viewportHeight / 240f, drawnTop, 1f)
        }
        compose.onNodeWithTag("empty_history_pull_host").performTouchInput { up() }
        repeat(45) { compose.mainClock.advanceTimeByFrame() }
        compose.runOnIdle { assertEquals(0f, drawnTop, 1f) }
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

    @Test fun queueDrawingCopiesKeepHeaderGeometryAndAllowScrollingThroughThem() {
        compose.mainClock.autoAdvance = false
        val progress = mutableStateOf(0f)
        var state: LazyListState? = null
        var viewport: LayoutCoordinates? = null
        var header: LayoutCoordinates? = null
        var copiedRowClicks = 0
        compose.setContent {
            val list = rememberLazyListState()
            state = list
            MaterialTheme {
                Box(Modifier.width(200.dp).height(240.dp).testTag("queue_copy_host")
                    .onGloballyPositioned { viewport = it }) {
                    LazyColumn(state = list, modifier = Modifier.fillMaxSize()) {
                        item(key = PlayerQueueCurrentHeaderKey) {
                            Box(Modifier.fillMaxWidth().height(68.dp).onGloballyPositioned { header = it })
                        }
                        items(12) { index ->
                            QueueSongRow(
                                PhoneQueueEntry(index.toString(), MediaItem.EMPTY, index), null, Modifier, Modifier,
                                onClick = {}, onLongClick = {}, enabled = false,
                            )
                        }
                    }
                    Box(Modifier.matchParentSize()) {
                        QueueSongRow(
                            PhoneQueueEntry("drawing_copy", MediaItem.EMPTY, 0), null, Modifier, Modifier,
                            onClick = { copiedRowClicks++ }, onLongClick = {}, interactive = false,
                            modifier = Modifier.fillMaxWidth().graphicsLayer {
                                val placement = queueRowPlacement(QueueRowPlacement(104.dp.toPx()), QueueRowPlacement(160.dp.toPx()), progress.value)
                                translationY = placement.top
                                alpha = placement.opacity
                            },
                        )
                    }
                }
            }
        }
        for (fraction in listOf(0f, 0.3f, 0.7f, 1f, 0f)) {
            compose.runOnIdle { progress.value = fraction }
            compose.mainClock.advanceTimeByFrame()
            compose.runOnIdle {
                assertEquals("Drawing rows must not resize the lazy viewport", viewport!!.size.height, state!!.layoutInfo.viewportEndOffset)
                assertEquals(0, state!!.layoutInfo.afterContentPadding)
                assertEquals("The header must keep its original position", viewport!!.boundsInRoot().top, header!!.boundsInRoot().top, 0.5f)
            }
        }
        compose.mainClock.autoAdvance = true
        compose.onNodeWithTag("queue_copy_host").performTouchInput {
            swipe(Offset(center.x, height * 0.55f), Offset(center.x, height * 0.1f), 200)
        }
        compose.runOnIdle {
            assertTrue("Drawing-only row copies must let vertical gestures reach the lazy list",
                state!!.firstVisibleItemIndex > 0 || state!!.firstVisibleItemScrollOffset > 0)
            assertEquals(0, copiedRowClicks)
        }
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

    @Test fun inlineQueueMarqueeDrawsIntoLeadingFade() =
        assertLeadingFadeVisible(AppleMusicDimensions.headerMarqueeEdgeWidth, LayoutDirection.Ltr, queueLayer = true)

    @Test fun rtlInlineQueueMarqueeDrawsIntoLeadingFade() =
        assertLeadingFadeVisible(AppleMusicDimensions.headerMarqueeEdgeWidth, LayoutDirection.Rtl, queueLayer = true)

    private fun assertLeadingFadeVisible(edgeWidth: Dp, direction: LayoutDirection, queueLayer: Boolean = false) {
        compose.mainClock.autoAdvance = false
        val opacity = mutableStateOf(1f)
        val progress = mutableStateOf(0f)
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalLayoutDirection provides direction) {
                    val listState = rememberLazyListState()
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
                            textModifier = if (queueLayer) Modifier.queueViewportFade(
                                null, listState, { 0 }, additive = true, layerOutsets = outsets,
                            ) else Modifier.playerInfoMotion(
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

    @Test fun changingToAPendingPainterDoesNotCropTheOutgoingSquareArtwork() {
        compose.mainClock.autoAdvance = false
        val track = mutableStateOf(0)
        val painterReady = mutableStateOf(false)
        compose.setContent {
            Box(Modifier.size(96.dp).background(Color.Black).testTag("square_cover_host")) {
                AnimatedContent(
                    track.value, transitionSpec = { appleMusicArtworkTransition() },
                    modifier = Modifier.padding(16.dp).size(64.dp).background(Color.White),
                ) { item ->
                    if (item == 0 || painterReady.value) {
                        Box(Modifier.fillMaxSize().background(if (item == 0) Color.Green else Color.Blue))
                    }
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { track.value = 1 }
        compose.mainClock.advanceTimeBy(100)
        val pending = compose.onNodeWithTag("square_cover_host").captureToImage().toPixelMap()
        for (x in listOf(24, 72)) for (y in listOf(24, 72)) {
            val pixel = pending[(pending.width * x / 96), (pending.height * y / 96)]
            assertTrue("A missing incoming painter must not crop the outgoing cover to a strip", pixel.red < 0.8f)
            assertTrue(pixel.green > 0.8f)
        }
        compose.runOnIdle { painterReady.value = true }
        compose.mainClock.advanceTimeBy(400)
        val loaded = compose.onNodeWithTag("square_cover_host").captureToImage().toPixelMap()
        for (x in listOf(24, 72)) for (y in listOf(24, 72)) {
            assertTrue("The ready painter must fill the same square",
                loaded[loaded.width * x / 96, loaded.height * y / 96].blue > 0.8f)
        }
    }

    @Test fun offscreenQueueArtworkRetainsItsShadowWhileFadingBackToTheAlbum() {
        val progress = mutableStateOf(0.6f)
        val belowViewport = mutableStateOf(false)
        val opaqueReference = mutableStateOf(false)
        var drawnBounds = Rect.Zero
        compose.setContent {
            val density = LocalDensity.current
            val normal = with(density) { Rect(80.dp.toPx(), 160.dp.toPx(), 240.dp.toPx(), 320.dp.toPx()) }
            val frame = {
                val hidden = with(density) {
                    val top = if (belowViewport.value) 800.dp.toPx() else (-200).dp.toPx()
                    Rect(28.dp.toPx(), top, 76.dp.toPx(), top + 48.dp.toPx())
                }
                val target = appleMusicQueueArtworkTarget(
                    hidden, with(density) { 40.dp.toPx() }, with(density) { 360.dp.toPx() },
                )
                appleMusicArtworkFrame(
                    normal, target.bounds,
                    with(density) { 12.dp.toPx() }, with(density) { 8.dp.toPx() },
                    with(density) { 16.dp.toPx() }, 1f, progress.value,
                )
            }
            Box(Modifier.width(320.dp).height(480.dp).background(Color.White).testTag("artwork_shadow_host")) {
                Box(
                    Modifier.graphicsLayer {
                        val current = frame()
                        drawnBounds = current.bounds
                        transformOrigin = TransformOrigin(0f, 0f)
                        translationX = current.bounds.left
                        translationY = current.bounds.top
                        scaleX = current.bounds.width / size.width
                        scaleY = current.bounds.height / size.height
                    }
                        .size(160.dp)
                        .playerArtworkAppearance(frame) { if (opaqueReference.value) 1f else 1f - progress.value }
                        .background(Color.White),
                )
            }
        }
        fun shadowEnergy(): Float {
            val pixels = compose.onNodeWithTag("artwork_shadow_host").captureToImage().toPixelMap()
            val bounds = drawnBounds.inflate(1f)
            var energy = 0f
            for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
                if (!bounds.contains(Offset(x + 0.5f, y + 0.5f))) energy += 1f - pixels[x, y].red
            }
            return energy
        }
        for (below in listOf(false, true)) {
            compose.runOnIdle { belowViewport.value = below }
            // Include a reversal while the artwork is still partially transparent.
            for (fraction in listOf(0.6f, 0.1f, 0.4f, 0f)) {
                compose.runOnIdle {
                    progress.value = fraction
                    opaqueReference.value = true
                }
                val fullShadow = shadowEnergy()
                assertTrue("The reference frame must have a native shadow outside the image", fullShadow > 3f)
                compose.runOnIdle { opaqueReference.value = false }
                assertEquals("The shadow must fade during return, without waiting for full opacity",
                    fullShadow * (1f - fraction), shadowEnergy(), maxOf(3f, fullShadow * 0.15f))
            }
        }
    }

    @Test fun queueMetadataTracksLiveScrollBeforeTheInlineHandoff() {
        val progress = mutableStateOf(0.96f)
        val opacity = mutableStateOf(1f)
        var list: LazyListState? = null
        val favoriteDescription = InstrumentationRegistry.getInstrumentation().targetContext
            .getString(R.string.app_tab_library_songs)
        val moreDescription = InstrumentationRegistry.getInstrumentation().targetContext
            .getString(R.string.more_actions_title)
        compose.setContent {
            MaterialTheme {
                val density = LocalDensity.current
                val state = rememberLazyListState()
                list = state
                val rowHeight = with(density) { 68.dp.toPx() }
                val position = rememberPlayerQueueHeaderPosition(state, PlayerQueueCurrentHeaderKey, 0, rowHeight)
                val motion = Modifier.playerInfoMotion(
                    opacity, progress,
                    albumOrigin = { Offset(0f, with(density) { 180.dp.toPx() }) },
                    headerOrigin = { Offset(0f, position.value.top) },
                    fromAlbum = false,
                    placementOrigin = { Offset.Zero },
                )
                Box(Modifier.width(360.dp).height(320.dp)) {
                    LazyColumn(state = state, overscrollEffect = null, modifier = Modifier.fillMaxSize()) {
                        item(key = PlayerQueueCurrentHeaderKey) {
                            Box(Modifier.fillMaxWidth().height(68.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
                                if (progress.value == 1f) {
                                    Title(
                                        title = "Queue title", subTitle = "Artist", isLiked = false,
                                        onLikeClick = {}, onMoreClick = {}, onTitleClick = {},
                                        enabled = false, marqueeEnabled = false, needShadow = false,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                        }
                        items(20) { Box(Modifier.fillMaxWidth().height(56.dp)) }
                    }
                    if (progress.value != 1f) {
                        Box(Modifier.fillMaxWidth().height(68.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
                            Title(
                                title = "Queue title", subTitle = "Artist", isLiked = false,
                                onLikeClick = {}, onMoreClick = {}, onTitleClick = {},
                                enabled = false, marqueeEnabled = false, needShadow = false,
                                textModifier = motion, actionsModifier = motion,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
        fun tops() = listOf(
            compose.onNodeWithText("Queue title", useUnmergedTree = true).getUnclippedBoundsInRoot().top.value,
            compose.onNodeWithContentDescription(favoriteDescription, useUnmergedTree = true).getUnclippedBoundsInRoot().top.value,
            compose.onNodeWithContentDescription(moreDescription, useUnmergedTree = true).getUnclippedBoundsInRoot().top.value,
        )
        val initial = tops()
        var pixelsPerDp = 1f
        compose.runOnIdle {
            pixelsPerDp = compose.density.density
            list!!.dispatchRawDelta(40f * pixelsPerDp)
        }
        tops().zip(initial).forEach { (after, before) ->
            assertEquals("Every metadata element must follow scrolling before entry settles", -40f * 0.96f, after - before, 1f)
        }
        compose.runOnIdle { list!!.dispatchRawDelta(-28f * pixelsPerDp) }
        tops().zip(initial).forEach { (after, before) ->
            assertEquals("Reversing a held scroll must move every metadata element", -12f * 0.96f, after - before, 1f)
        }
        compose.runOnIdle { progress.value = 0.7f }
        compose.runOnIdle { progress.value = 0.9999f }
        val beforeHandoff = tops()
        compose.runOnIdle { progress.value = 1f }
        tops().zip(beforeHandoff).forEach { (after, before) ->
            assertEquals("The real list row must replace metadata without a position jump", before, after, 1f)
        }
        compose.runOnIdle { progress.value = 0.96f }
        tops().zip(initial).forEach { (after, before) ->
            assertEquals("Reopening must observe the current list position", -12f * 0.96f, after - before, 1f)
        }
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
