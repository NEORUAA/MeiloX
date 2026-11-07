package com.ljyh.mei.data.repository


import androidx.room.Room
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ljyh.mei.data.model.room.Playlist
import com.ljyh.mei.di.AppDatabase
import com.ljyh.mei.R
import io.github.neoruaa.glasmio.glass.LocalBlurBackdrop
import io.github.neoruaa.glasmio.glass.LocalGlassBackdrop
import io.github.neoruaa.glasmio.core.LocalGlassColors
import io.github.neoruaa.glasmio.core.defaultGlassColors
import com.ljyh.mei.ui.screen.playlist.PlaylistOrderItem
import com.ljyh.mei.ui.screen.playlist.PlaylistOrderSheet
import com.ljyh.mei.ui.screen.playlist.PlaylistOrderState
import io.github.neoruaa.glasmio.glass.render.backdrops.emptyBackdrop
import org.junit.Rule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real Room merge in a separate in-memory database. */
@RunWith(AndroidJUnit4::class)
class PlaylistLibraryInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun largeOrderSheetScrollsToUnavailableTracksAndCancelDoesNotSave() {
        val visible = mutableStateOf(true)
        val state = PlaylistOrderState(
            playlistId = "fixture",
            items = (1L..2_000L).map { PlaylistOrderItem(it, null) },
            originalIds = (1L..2_000L).toList(), loading = false,
        )
        var saves = 0
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalGlassColors provides defaultGlassColors(false),
                    LocalGlassBackdrop provides emptyBackdrop(),
                    LocalBlurBackdrop provides emptyBackdrop(),
                ) {
                    if (visible.value) PlaylistOrderSheet(state, { _, _ -> }, {}, { saves++ }, {}, { visible.value = false })
                }
            }
        }
        compose.onNodeWithTag("playlist-order-list").performScrollToIndex(1_999)
        compose.onNodeWithTag("playlist-order-2000").assertIsDisplayed()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithText(context.getString(R.string.playlist_order_cancel)).performClick()
        compose.runOnIdle { assertFalse(visible.value); assertEquals(0, saves) }
    }

    @Test
    fun remoteRefreshPreservesLocalUsageIncludingPlaysAfterTheRequestStarted() = runBlocking<Unit>(Dispatchers.IO) {
        val database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java,
        ).build()
        try {
            val dao = database.playlistDao()
            val original = Playlist(
                "playlist", "Old title", "old-cover", "owner", "Owner", "avatar", 10,
                description = "Local description", createdAt = 100, updatedAt = 200,
                playCount = 300, lastPlayTime = 400, localPlayCount = 5,
            )
            dao.insertPlaylist(original)
            val remote = original.copy(title = "New title", count = 11, playCount = 600,
                lastPlayTime = 0, localPlayCount = 0, createdAt = 900, updatedAt = 900, description = null)
            dao.touchPlaylist(original.id, 800)
            dao.mergeRemotePlaylists(listOf(remote, remote.copy(id = "new", author = "other")))
            val merged = checkNotNull(dao.getPlaylist(original.id))
            assertEquals("New title", merged.title)
            assertEquals(11, merged.count)
            assertEquals(600L, merged.playCount)
            assertEquals(6, merged.localPlayCount)
            assertEquals(800L, merged.lastPlayTime)
            assertEquals(original.createdAt, merged.createdAt)
            assertEquals(original.updatedAt, merged.updatedAt)
            assertEquals(original.description, merged.description)
            assertNotNull(dao.getPlaylist("new"))
        } finally {
            database.close()
        }
    }
}
