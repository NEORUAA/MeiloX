package com.ljyh.mei.ui.screen.playlist

import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.ui.test.performScrollToIndex
import com.ljyh.mei.ui.screen.playlist.component.PlaylistHeader
import androidx.compose.ui.platform.testTag
import com.ljyh.mei.ui.screen.playlist.component.DetailTrackRow
import com.ljyh.mei.data.model.MediaMetadata

import kotlin.math.abs
import io.github.neoruaa.glasmio.scaffold.GlasmioHeaderAction
import io.github.neoruaa.glasmio.scaffold.GlasmioHeaderGroup
import io.github.neoruaa.glasmio.scaffold.GlasmioHeaderSpec
import io.github.neoruaa.glasmio.scaffold.GlasmioNavigationScaffold
import io.github.neoruaa.glasmio.scaffold.GlasmioNavigationFrame
import io.github.neoruaa.glasmio.scaffold.GlasmioHeaderMotion
import io.github.neoruaa.glasmio.core.GlassAppearance
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.glass.GlasmioBackdropHost
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.Alignment
import androidx.compose.runtime.remember
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ljyh.mei.R
import com.ljyh.mei.data.model.room.DownloadStatus
import com.ljyh.mei.data.model.room.DownloadTask
import com.ljyh.mei.ui.screen.main.library.component.DownloadLibraryEntry
import com.ljyh.mei.ui.screen.main.library.component.DownloadLibraryRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SongListRenderingTest {
    @get:Rule val compose = createComposeRule()

    private val loading = mutableStateOf(false)
    private val hasLoadedContent = mutableStateOf(true)
    private val rowCount = mutableIntStateOf(150)
    private val paginationError = mutableStateOf(false)
    private lateinit var contentState: LazyListState
    private lateinit var layoutState: LazyListState

    @Test
    fun albumDurationIsShownOnlyWhenTheCallerRequestsIt() {
        val durationVisible = mutableStateOf(false)
        compose.setContent {
            GlasmioTheme {
                DetailTrackRow(
                    track =
                        MediaMetadata(
                            1L,
                            "Song",
                            "",
                            emptyList(),
                            259000L,
                            MediaMetadata.Album(1L, "Album"),
                        ),
                    trackNumber = 1,
                    showArtwork = false,
                    isCurrentTrack = false,
                    isPlaying = false,
                    isFavorite = false,
                    onClick = {},
                    selected = null,
                    onMoreClick = {},
                    showDuration = durationVisible.value,
                )
            }
        }
        compose.onNodeWithText("4:19").assertDoesNotExist()
        compose.runOnIdle { durationVisible.value = true }
        compose.onNodeWithText("4:19").assertIsDisplayed()
    }

    @Test
    fun albumLandscapeHeaderKeepsArtworkAndActionGeometryWhileLoading() {
        val loading = mutableStateOf(false)
        val horizontal = mutableStateOf(true)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(0.5f, 1f)) {
                GlasmioTheme {
                    Box(Modifier.requiredWidth(900.dp)) {
                        PlaylistHeader(
                            title = "Album title",
                            creator = "Artist",
                            metadata = "2026 · 12 tracks",
                            count = 12,
                            playCount = 0,
                            subscribeCount = 0,
                            cover = "",
                            coverList = emptyList(),
                            isSubscribed = false,
                            onPlayAll = {},
                            onSubscribed = {},
                            actionIcon = Icons.Default.FavoriteBorder,
                            actionLabel = "Subscribe",
                            detailAction = true,
                            horizontal = horizontal.value,
                            isLoading = loading.value,
                        )
                    }
                }
            }
        }
        fun bounds(tag: String) =
            compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val cover = bounds("DetailHeaderCover")
        val information = bounds("DetailHeaderInformation")
        val actions = bounds("DetailHeaderActions")
        assertEquals(cover.width, cover.height, 1f)
        assertTrue(cover.right < information.left)
        compose.runOnIdle { loading.value = true }
        assertEquals(cover, bounds("DetailHeaderCover"))
        assertEquals(actions, bounds("DetailHeaderActions"))
        compose.runOnIdle {
            loading.value = false
            horizontal.value = false
        }
        assertTrue(bounds("DetailHeaderInformation").top >= bounds("DetailHeaderCover").bottom)
    }

    @Test
    fun splitPlaylistKeepsTheHeroStationaryWhileTheSongPaneScrolls() {
        lateinit var state: LazyListState
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(0.5f, 1f)) {
                GlasmioTheme {
                    state = rememberLazyListState()
                    Box(Modifier.requiredWidth(900.dp).requiredHeight(500.dp)) {
                        SongListDetailPage(
                            title = "Playlist",
                            subtitle = null,
                            listState = state,
                            backgroundColor = Color.Black,
                            backgroundBrush = null,
                            bottomPadding = 48.dp,
                            horizontalContentPadding = 24.dp,
                            splitHeader = true,
                            hero = { Text("Playlist artwork", Modifier.testTag("PinnedHero")) },
                            onNavigateBack = {},
                            actionsSeparationProgress = 0f,
                            actions = { emptyList() },
                        ) {
                            items(30) { Text("Song $it", Modifier.fillMaxWidth().height(64.dp)) }
                        }
                    }
                }
            }
        }
        val hero =
            compose
                .onNodeWithTag("PinnedHero", useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
        val left = compose.onNodeWithTag("DetailHeroPane").fetchSemanticsNode().boundsInRoot
        val right = compose.onNodeWithTag("DetailTrackPane").fetchSemanticsNode().boundsInRoot
        assertTrue(left.right <= right.left)
        compose.onNodeWithTag("DetailTrackPane").performScrollToIndex(12)
        assertEquals(
            hero,
            compose
                .onNodeWithTag("PinnedHero", useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot,
        )
        compose.runOnIdle { assertTrue(state.firstVisibleItemIndex > 0) }
    }

    @Test
    fun playlistSearchReservesTheRenderedDoneActionInBothDirectionsAndLargeType() {
        val fontScale = mutableStateOf(1f)
        val direction = mutableStateOf(LayoutDirection.Ltr)
        val doneTextWidthPx = mutableIntStateOf(0)
        var physicalDensity = 1f
        val motion = object : GlasmioHeaderMotion {
            override val frame = mutableStateOf(GlasmioNavigationFrame(0, "selection", "selection"))
        }
        compose.setContent {
            physicalDensity = LocalDensity.current.density
            CompositionLocalProvider(
                LocalDensity provides Density(physicalDensity, fontScale.value),
                LocalLayoutDirection provides direction.value,
            ) {
                GlasmioTheme(appearance = GlassAppearance(reduceTransparency = true)) {
                    GlasmioBackdropHost(sampledContent = {}, overlayContent = {
                        val doneLabel = stringResource(R.string.done)
                        GlasmioNavigationScaffold(motion, header = {
                            GlasmioHeaderSpec("", trailing = listOf(GlasmioHeaderGroup("actions", listOf(
                                GlasmioHeaderAction("done", "done", "Selection done", {}) {
                                    Text(doneLabel, modifier = Modifier.onSizeChanged { doneTextWidthPx.intValue = it.width })
                                },
                            ))))
                        }) {
                            BoxWithConstraints(Modifier.fillMaxSize()) {
                                val endInset = playlistSearchEndInset(true, false, doneTextWidthPx.intValue)
                                PlaylistHeaderSearchEditor(
                                    query = "Preview", onQueryChange = {}, onClose = {},
                                    contentAlpha = 1f, expansion = 1f,
                                    focusRequester = remember { FocusRequester() },
                                    modifier = Modifier.align(Alignment.TopEnd).padding(
                                        top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 9.dp,
                                        end = endInset,
                                    ).width((maxWidth - 68.dp - endInset).coerceAtLeast(44.dp)).height(44.dp),
                                )
                            }
                        }
                    })
                }
            }
        }
        for (layout in listOf(LayoutDirection.Ltr, LayoutDirection.Rtl)) {
            for (scale in listOf(1f, 2f)) {
                compose.runOnIdle { direction.value = layout; fontScale.value = scale }
                fun actualGap(): Float {
                    val editor = compose.onNodeWithTag("PlaylistHeaderSearchEditor", useUnmergedTree = true)
                        .fetchSemanticsNode().boundsInRoot
                    val done = compose.onNodeWithContentDescription("Selection done").fetchSemanticsNode().boundsInRoot
                    return if (layout == LayoutDirection.Ltr) done.left - editor.right else editor.left - done.right
                }
                compose.waitUntil(5_000) { abs(actualGap() - 8f * physicalDensity) <= physicalDensity }
                assertEquals("Editor must leave an 8dp visible gap at font scale $scale in $layout",
                    8f * physicalDensity, actualGap(), physicalDensity)
                val done = compose.onNodeWithContentDescription("Selection done").fetchSemanticsNode().boundsInRoot
                assertEquals("The shared Done action keeps its 44dp hit height", 44f * physicalDensity,
                    done.height, physicalDensity)
            }
        }
    }

    @Test
    fun savedViewportSurvivesLoadingBeforeTheRestoredRowsArrive() {
        val restoration = StateRestorationTester(compose)
        showList(restoration)
        scrollContentTo(80, 13)
        compose.runOnIdle { loading.value = true }
        restoration.emulateSavedInstanceStateRestore()

        assertViewport(80, 13)
        compose.runOnIdle { loading.value = false }
        assertViewport(80, 13)
    }

    @Test
    fun refreshingAndScrollingThePlaceholderLeaveTheContentViewportIntact() {
        showList(StateRestorationTester(compose))
        scrollContentTo(25, 17)
        repeat(4) { refresh ->
            compose.runOnIdle { loading.value = true }
            compose.runOnIdle { assertNotSame(contentState, layoutState) }
            runBlocking(Dispatchers.Main) { layoutState.scrollToItem(0, 120 + refresh * 20) }

            assertViewport(25, 17)
            compose.runOnIdle { loading.value = false }
            assertViewport(25, 17)
        }
    }

    @Test
    fun initialLoadErrorRemainsRetryableWithoutClampingTheRestoredViewport() {
        val restoration = StateRestorationTester(compose)
        showList(restoration)
        scrollContentTo(80, 13)
        compose.runOnIdle {
            loading.value = true
            hasLoadedContent.value = false
        }
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle { loading.value = false }

        compose.onNodeWithText("Retry").assertIsDisplayed().performClick()
        assertViewport(80, 13)
        compose.runOnIdle {
            hasLoadedContent.value = true
            loading.value = false
        }
        assertViewport(80, 13)
    }

    @Test
    fun aStaleHugeViewportFallsBackToLoadedRowsThatStillScroll() {
        rowCount.intValue = 500
        val restoration = StateRestorationTester(compose)
        showList(restoration)
        scrollContentTo(400, 13)
        compose.runOnIdle {
            loading.value = true
            rowCount.intValue = 200
        }
        restoration.emulateSavedInstanceStateRestore()
        assertViewport(400, 13)
        compose.runOnIdle {
            contentState.requestScrollToItem(0)
            loading.value = false
        }

        assertViewport(0, 0)
        scrollContentTo(80, 13)
    }

    @Test
    fun aPartialPageFailureLeavesLoadedRowsAvailableForManualRetry() {
        showList(StateRestorationTester(compose))
        scrollContentTo(80, 13)
        compose.runOnIdle {
            loading.value = true
            rowCount.intValue = 50
        }
        compose.runOnIdle {
            contentState.requestScrollToItem(0)
            paginationError.value = true
            loading.value = false
        }

        compose.onNodeWithText("Track 0").assertIsDisplayed()
        runBlocking(Dispatchers.Main) { layoutState.scrollToItem(rowCount.intValue) }
        compose.onNodeWithText("Retry more").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(100, rowCount.intValue)
            assertTrue(contentState.firstVisibleItemIndex < rowCount.intValue)
        }
        scrollContentTo(80, 13)
    }

    @Test
    fun downloadRowsShowDeterminateProgressAndTaskStates() {
        val tasks = listOf(
            DownloadTask("preview-downloading", songTitle = "下载进行中", songArtist = "本地音乐 · 下载任务", status = DownloadStatus.DOWNLOADING, progress = 42),
            DownloadTask("preview-paused", songTitle = "下载已暂停", songArtist = "本地音乐 · 下载任务", status = DownloadStatus.PAUSED, progress = 64),
            DownloadTask("preview-failed", songTitle = "下载失败待重试", songArtist = "本地音乐 · 下载任务", status = DownloadStatus.FAILED),
        )
        compose.setContent {
            MaterialTheme {
                Column(
                    Modifier.fillMaxWidth().background(Color(0xFFF2F2F7)).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("下载任务", style = MaterialTheme.typography.headlineSmall)
                    tasks.forEach { task ->
                        DownloadLibraryRow(
                            entry = DownloadLibraryEntry(task.songId, song = null, task = task),
                            onPlay = {},
                            onMore = {},
                        )
                    }
                }
            }
        }

        compose.onNodeWithText("42%").assertIsDisplayed()
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithText(targetContext.getString(R.string.download_paused)).assertIsDisplayed()
        compose.onNodeWithText(targetContext.getString(R.string.download_failed)).assertIsDisplayed()
        compose.onNode(SemanticsMatcher.expectValue(
            SemanticsProperties.ProgressBarRangeInfo,
            ProgressBarRangeInfo(0.42f, 0f..1f),
        )).assertIsDisplayed()
        compose.onNode(SemanticsMatcher.expectValue(
            SemanticsProperties.ProgressBarRangeInfo,
            ProgressBarRangeInfo(0.64f, 0f..1f),
        )).assertIsDisplayed()


    }

    private fun showList(restoration: StateRestorationTester) {
        restoration.setContent {
            MaterialTheme {
                val isLoading = loading.value
                val loaded = hasLoadedContent.value
                val count = rowCount.intValue
                val hasPaginationError = paginationError.value
                contentState = rememberLazyListState()
                layoutState = rememberSongListLayoutState(contentState,
                    isContentReady = !isLoading && loaded)
                key(layoutState) {
                    LazyColumn(Modifier.width(320.dp).height(240.dp), state = layoutState) {
                        if (isLoading) {
                            item(key = "playlist-loading") { Box(Modifier.fillMaxWidth().height(620.dp)) }
                        } else if (!loaded) {
                            item(key = "playlist-error") {
                                androidx.compose.material3.TextButton(onClick = { loading.value = true }) { Text("Retry") }
                            }
                        } else {
                            items(count, key = { "track-$it" }) { index ->
                                Text("Track $index", modifier = Modifier.fillMaxWidth().height(48.dp))
                            }
                            if (hasPaginationError) {
                                item(key = "playlist-pagination-error") {
                                    androidx.compose.material3.TextButton(onClick = {
                                        rowCount.intValue += 50
                                        paginationError.value = false
                                    }) { Text("Retry more") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun scrollContentTo(index: Int, offset: Int) {
        compose.waitForIdle()
        runBlocking(Dispatchers.Main) { contentState.scrollToItem(index, offset) }
        assertViewport(index, offset)
    }

    private fun assertViewport(index: Int, offset: Int) {
        compose.runOnIdle {
            assertEquals(index, contentState.firstVisibleItemIndex)
            assertEquals(offset, contentState.firstVisibleItemScrollOffset)
        }
    }
}
