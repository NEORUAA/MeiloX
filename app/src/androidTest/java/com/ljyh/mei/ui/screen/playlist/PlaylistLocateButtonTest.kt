package com.ljyh.mei.ui.screen.playlist

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ljyh.mei.R
import com.ljyh.mei.source.api.ProviderIds
import com.ljyh.mei.source.api.TrackRef
import com.ljyh.mei.ui.screen.playlist.component.PlaylistLocateButton
import io.github.neoruaa.glasmio.glass.GlasmioBackdropHost
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaylistLocateButtonTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun buttonHidesAfterIdleAndReappearsWhenThePlaylistScrolls() {
        showPlaylist()
        locateButton().assertIsDisplayed()

        compose.mainClock.advanceTimeBy(3_100)
        locateButton().assertDoesNotExist()

        compose.onNodeWithTag("playlist").performTouchInput { swipeUp() }
        locateButton().assertIsDisplayed()

        compose.mainClock.advanceTimeBy(5_000)
        locateButton().assertDoesNotExist()
    }

    @Test
    fun tappingLocateWaitsForTheTargetRowToBeAddedToTheList() {
        showPlaylist(initialTrackCount = 36)
        assertLocatesTrack()
    }

    @Test
    fun tappingLocateWorksWithAnAlreadyLoadedSongList() {
        showPlaylist()
        assertLocatesTrack()
    }

    private fun assertLocatesTrack() {
        compose.onNodeWithText("Song 67").assertDoesNotExist()

        locateButton().performClick()

        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText("Song 67").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Song 67").assertIsDisplayed()
    }

    private fun showPlaylist(initialTrackCount: Int = 100) {
        compose.setContent {
            val listState = rememberLazyListState()
            var trackCount by remember { mutableIntStateOf(initialTrackCount) }
            GlasmioBackdropHost(
                modifier = Modifier.fillMaxSize(),
                sampledContent = {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().testTag("playlist"),
                        contentPadding = PaddingValues(top = 60.dp, bottom = 100.dp),
                    ) {
                        item { Text("Cover", Modifier.height(160.dp)) }
                        item { Text("Song table", Modifier.height(48.dp)) }
                        items(trackCount) { index ->
                            Text("Song $index", Modifier.height(56.dp))
                        }
                    }
                },
                overlayContent = {
                    PlaylistLocateButton(
                        listState = listState,
                        track = TrackRef(ProviderIds.NETEASE, "67"),
                        leadingItems = 2,
                        modifier = Modifier.align(Alignment.BottomEnd),
                        findTrackIndex = {
                            trackCount = 100
                            67
                        },
                    )
                },
            )
        }
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("Song 0").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun locateButton() = compose.onNodeWithContentDescription(
        InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.playlist_locate_current_track),
    )
}
