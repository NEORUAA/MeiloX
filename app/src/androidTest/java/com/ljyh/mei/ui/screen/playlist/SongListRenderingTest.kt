package com.ljyh.mei.ui.screen.playlist

import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.ui.test.performScrollToIndex
import com.ljyh.mei.ui.screen.playlist.component.PlaylistHeader
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import com.ljyh.mei.ui.screen.artist.ArtistPopularSongs
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
import androidx.compose.ui.test.onAllNodesWithContentDescription
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
import com.ljyh.mei.ui.screen.main.library.component.LibraryLandscapeSongRow
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
    fun homeAndCatalogArtworkShareDimensionsAcrossRotation() {
        val landscape = mutableStateOf(false)
        var played = 0
        var opened = 0
        val song = MediaMetadata(1L, "Detail track", "", emptyList(), 259000L,
            MediaMetadata.Album(3L, "Album"))
        compose.setContent {
            val configuration = android.content.res.Configuration(androidx.compose.ui.platform.LocalConfiguration.current).apply {
                orientation = if (landscape.value) android.content.res.Configuration.ORIENTATION_LANDSCAPE
                    else android.content.res.Configuration.ORIENTATION_PORTRAIT
                screenWidthDp = if (landscape.value) 844 else 390
                screenHeightDp = if (landscape.value) 390 else 844
            }
            CompositionLocalProvider(androidx.compose.ui.platform.LocalConfiguration provides configuration,
                LocalDensity provides Density(0.5f, 1f)) {
                GlasmioTheme {
                    Column(Modifier.requiredWidth(390.dp).padding(horizontal = 24.dp)) {
                        com.ljyh.mei.ui.component.item.HomeSongListRow("", "Home track", "Artist",
                            onClick = { played++ }, onMore = { opened++ },
                            moreModifier = Modifier.testTag("HomeSongMore"))
                        com.ljyh.mei.ui.component.item.LandscapeMediaRow("", "Catalog track", "Artist", "", {},
                            landscape = landscape.value)
                        DetailTrackRow(song, 1, true, false, false, false, {}, null, {})
                    }
                }
            }
        }
        fun assertArtworkSize(expectedPx: Float) {
            val covers = compose.onAllNodesWithContentDescription("Album Art", useUnmergedTree = true)
                .fetchSemanticsNodes().map { it.boundsInRoot }
            assertEquals(3, covers.size)
            covers.forEach {
                assertEquals(expectedPx, it.width, 0.1f)
                assertEquals(expectedPx, it.height, 0.1f)
            }
        }
        assertArtworkSize(24f)
        compose.runOnIdle { landscape.value = true }
        assertArtworkSize(24f)
        compose.onNodeWithText("Home track").performClick()
        compose.onNodeWithTag("HomeSongMore").performClick()
        compose.runOnIdle {
            assertEquals(1, played)
            assertEquals(1, opened)
        }
    }

    @Test
    fun portraitCatalogRowsMatchPlaylistSpacingAndAlbumActionAlignment() {
        var opened = 0
        val song = MediaMetadata(1L, "Playlist track", "", emptyList(), 259000L,
            MediaMetadata.Album(3L, "Album"))
        compose.setContent {
            val configuration = android.content.res.Configuration(androidx.compose.ui.platform.LocalConfiguration.current).apply {
                orientation = android.content.res.Configuration.ORIENTATION_PORTRAIT
                screenWidthDp = 390
                screenHeightDp = 844
            }
            CompositionLocalProvider(androidx.compose.ui.platform.LocalConfiguration provides configuration,
                LocalDensity provides Density(0.5f, 1f)) {
                GlasmioTheme {
                    Column(Modifier.requiredWidth(390.dp).padding(horizontal = 24.dp)) {
                        DetailTrackRow(song, 1, true, false, false, false, {}, null, { opened++ })
                        com.ljyh.mei.ui.screen.playlist.component.DetailTrackDivider()
                        com.ljyh.mei.ui.component.item.LandscapeMediaRow(
                            image = "", title = "Catalog track", artist = "Artist", detail = "",
                            onClick = {}, onMore = { opened++ }, landscape = false,
                        )
                        DetailTrackRow(song.copy(title = "Album track"), 1, false, false, false,
                            false, {}, null, { opened++ })
                    }
                }
            }
        }
        val covers = compose.onAllNodesWithContentDescription("Album Art", useUnmergedTree = true)
            .fetchSemanticsNodes().map { it.boundsInRoot }
        assertEquals(2, covers.size)
        assertEquals(24f, covers[0].width, 0.1f)
        assertEquals(4f, covers[1].top - covers[0].bottom, 0.1f)
        val moreLabel = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.more_actions_title)
        val buttons = compose.onAllNodesWithContentDescription(moreLabel)
        val bounds = buttons.fetchSemanticsNodes().map { it.boundsInRoot }
        assertEquals(3, bounds.size)
        bounds.forEach {
            assertEquals(bounds.first().left, it.left, 0.1f)
            assertEquals(22f, it.width, 0.1f)
        }
        for (index in 0..2) buttons[index].performClick()
        compose.runOnIdle { assertEquals(3, opened) }
    }

    @Test
    fun landscapeLibrarySongColumnsAdaptWithoutChangingMenuActions() {
        val width = mutableStateOf(800.dp)
        var played = 0
        var opened = 0
        val song = MediaMetadata(
            1L, "Library song", "", listOf(MediaMetadata.Artist(2L, "Library artist")),
            259000L, MediaMetadata.Album(3L, "Library album"),
        )
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(0.5f, 1f)) {
                GlasmioTheme {
                    Box(Modifier.requiredWidth(width.value)) {
                        LibraryLandscapeSongRow(song, { played++ }, { opened++ })
                    }
                }
            }
        }
        compose.onNodeWithText("Library album").assertDoesNotExist()
        compose.onNodeWithText("4:19", useUnmergedTree = true).assertIsDisplayed()
        val title = compose.onNodeWithText("Library song", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val artist = compose.onNodeWithText("Library artist", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertEquals(title.top.value, artist.top.value, 0.1f)
        assertTrue(title.right < artist.left)
        compose.runOnIdle { width.value = 1100.dp }
        compose.onNodeWithText("Library album", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription(
            InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.more_actions_title),
        ).performClick()
        compose.runOnIdle {
            assertEquals(1, opened)
            assertEquals(0, played)
        }
    }

    @Test
    fun landscapePopularSongMoreTargetsLeaveTheFavoriteGutterClear() {
        val tracks =
            (1L..6L).map { id ->
                MediaMetadata(
                    id,
                    "Song $id",
                    "",
                    emptyList(),
                    259000L,
                    MediaMetadata.Album(1L, "Album"),
                )
            }
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(0.5f, 1f)) {
                GlasmioTheme {
                    Box(Modifier.requiredWidth(900.dp)) {
                        ArtistPopularSongs(tracks, setOf("4"), null, false, {}, { _, _ -> })
                    }
                }
            }
        }
        val more =
            compose
                .onNodeWithTag("ArtistSongMore:1", useUnmergedTree = true)
                .getUnclippedBoundsInRoot()
        val favorite =
            compose
                .onNodeWithTag("ArtistSongFavorite:4", useUnmergedTree = true)
                .getUnclippedBoundsInRoot()
        assertTrue(more.right < favorite.left)
        assertEquals(40f, (more.right - more.left).value, 0.1f)
    }

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
                            items(30) { Text("Song $it", Modifier.fillMaxWidth().height(64.dp).testTag("SplitSong:$it")) }
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
        assertEquals(left.width, right.width, 1f)
        assertEquals(left.center.x, hero.center.x, 1f)
        val firstSong = compose.onNodeWithTag("SplitSong:0", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        assertEquals(12f, firstSong.left - right.left, 1f)
        assertEquals(12f, right.right - firstSong.right, 1f)
        assertEquals(hero.top, firstSong.top, 1f)
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
    fun landscapeDownloadDurationPrecedesFormatWithMatchingTypography() {
        val song = com.ljyh.mei.data.model.room.Song(
            id = "42", title = "Offline song", artist = listOf("Offline artist"),
            album = "Offline album", cover = "", duration = 259000L,
            path = "/music/song.flac", fileFormat = "flac",
        )
        var played = 0
        var opened = 0
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(0.5f, 1f)) {
                GlasmioTheme {
                    Box(Modifier.requiredWidth(800.dp)) {
                        DownloadLibraryRow(
                            DownloadLibraryEntry("42", song, null),
                            onPlay = { played++ }, onMore = { opened++ }, landscape = true,
                        )
                    }
                }
            }
        }
        val duration = compose.onNodeWithText("4:19", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val format = compose.onNodeWithText("FLAC", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue(duration.right < format.left)
        assertEquals(duration.top.value, format.top.value, 0.1f)
        assertEquals((duration.bottom - duration.top).value, (format.bottom - format.top).value, 0.1f)
        compose.onNodeWithContentDescription(
            InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.more_actions_title),
        ).performClick()
        compose.runOnIdle {
            assertEquals(1, opened)
            assertEquals(0, played)
        }
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
