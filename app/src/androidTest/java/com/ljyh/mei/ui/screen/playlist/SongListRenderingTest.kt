package com.ljyh.mei.ui.screen.playlist

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
