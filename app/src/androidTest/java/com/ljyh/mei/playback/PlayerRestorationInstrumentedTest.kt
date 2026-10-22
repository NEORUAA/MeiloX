package com.ljyh.mei.playback

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.os.IBinder
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ljyh.mei.MainActivity
import com.ljyh.mei.R
import com.ljyh.mei.data.model.room.Song
import com.ljyh.mei.data.model.room.SourceType
import com.ljyh.mei.data.model.toMediaItem
import com.ljyh.mei.data.model.toMediaMetadata
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real playback and UI checks restricted to an explicitly opted-in, isolated application. */
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class PlayerRestorationInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var service: MusicService
    private lateinit var item: MediaItem
    private var connection: ServiceConnection? = null
    private var silence: File? = null

    @Before
    fun prepareIsolatedPausedPlayback() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("issue62Restoration") == "true")
        assumeTrue(context.packageName == "com.neoruaa.meilox.issue62")
        val connected = CountDownLatch(1)
        val reference = AtomicReference<MusicService?>()
        val binding = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                reference.set((binder as? MusicService.MusicBinder)?.service)
                connected.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }
        assertTrue(context.bindService(Intent(context, MusicService::class.java), binding, Context.BIND_AUTO_CREATE))
        connection = binding
        assertTrue("The real music service must bind", connected.await(10, TimeUnit.SECONDS))
        service = checkNotNull(reference.get())

        val dataSize = 30 * 16_000
        val file = File.createTempFile("issue62-", ".wav", context.cacheDir)
        silence = file
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray()).putInt(dataSize + 36).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(8_000).putInt(16_000)
            .putShort(2).putShort(16).put("data".toByteArray()).putInt(dataSize).array()
        file.writeBytes(header + ByteArray(dataSize))
        val song = Song(
            id = FIXTURE_ID, title = TITLE, artist = listOf("Local regression fixture"),
            album = "Playback restoration", cover = "", duration = 30_000,
            path = file.absolutePath, sourceType = SourceType.LOCAL,
        )
        runBlocking(Dispatchers.IO) { service.songRepository.insertSong(song) }
        item = song.toMediaMetadata().toMediaItem()
        onMain {
            service.queueTitle = "Issue 62 restoration"
            service.player.repeatMode = Player.REPEAT_MODE_OFF
            service.player.shuffleModeEnabled = false
            service.player.playWhenReady = false
            service.player.setMediaItem(item, 2_000)
            service.player.prepare()
        }
        awaitReady()
        assertMiniPlayer()
    }

    @After
    fun removeOnlyTheIsolatedFixture() {
        compose.mainClock.autoAdvance = true
        val keepFixture = ::service.isInitialized &&
            InstrumentationRegistry.getArguments().getString("keepRestorationFixture") == "true"
        try {
            if (::service.isInitialized) {
                onMain {
                    service.player.pause()
                    if (!keepFixture) service.player.clearMediaItems()
                }
                if (keepFixture) {
                    val persistence = PlaybackPersistence(context)
                    val snapshot = onMain { persistence.capture(service.player, service.queueTitle, false) }
                    runBlocking(Dispatchers.IO) { persistence.save(snapshot) }
                } else {
                    runBlocking(Dispatchers.IO) { service.songRepository.deleteById(FIXTURE_ID) }
                }
            }
        } finally {
            connection?.let(context::unbindService)
            if (!keepFixture) silence?.delete()
        }
    }

    @Test
    fun restoredQueueReappearsDuringAndAfterDismissalAndCanResumeFromTheMiniPlayer() {
        for (restoreDelayMs in listOf(0L, 64L, 1_200L)) {
            compose.mainClock.autoAdvance = false
            val emptyTimeline = CountDownLatch(1)
            val restoredTimeline = CountDownLatch(1)
            val listener = object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) {
                    if (events.contains(Player.EVENT_TIMELINE_CHANGED)) {
                        if (player.currentMediaItem == null) emptyTimeline.countDown()
                        else restoredTimeline.countDown()
                    }
                }
            }
            try {
                onMain {
                    service.player.addListener(listener)
                    service.player.clearMediaItems()
                }
                // Drain the real player event without allowing Compose to finish the animation.
                assertTrue("Clearing the queue must publish its timeline", emptyTimeline.await(10, TimeUnit.SECONDS))
                if (restoreDelayMs > 0) compose.mainClock.advanceTimeBy(restoreDelayMs)
                onMain {
                    assertEquals(null, service.player.currentMediaItem)
                    service.player.setMediaItem(item, 2_000)
                    service.player.prepare()
                }
                assertTrue("Restoration must publish before animation resumes", restoredTimeline.await(10, TimeUnit.SECONDS))
                compose.mainClock.advanceTimeBy(2_000)
            } finally {
                onMain { service.player.removeListener(listener) }
                compose.mainClock.autoAdvance = true
            }
            awaitReady()
            assertMiniPlayer()
            onMain {
                assertEquals(item.mediaId, service.player.currentMediaItem?.mediaId)
                assertFalse(service.player.playWhenReady)
            }
        }
        resumeAndPauseThroughUi()
        saveScreenshot("issue62-restored-mini-player.png")
    }

    @Test
    fun pausedPlaybackSurvivesBackgroundReturnAndActivityRecreation() {
        val originalPosition = onMain { service.player.currentPosition }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        assertMiniPlayer()
        compose.activityRule.scenario.recreate()
        assertMiniPlayer()
        onMain {
            assertEquals(item.mediaId, service.player.currentMediaItem?.mediaId)
            assertEquals(originalPosition, service.player.currentPosition)
            assertFalse(service.player.playWhenReady)
        }
        resumeAndPauseThroughUi()
        saveScreenshot("issue62-recreated-mini-player.png")
    }

    private fun awaitReady() {
        compose.waitUntil(10_000) {
            onMain { service.player.playbackState == Player.STATE_READY && service.player.playerError == null }
        }
    }

    private fun assertMiniPlayer() {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(TITLE).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(TITLE).assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.player_play)).assertIsDisplayed()
    }

    private fun resumeAndPauseThroughUi() {
        val before = onMain { service.player.currentPosition }
        compose.onNodeWithContentDescription(context.getString(R.string.player_play)).performClick()
        compose.waitUntil(10_000) {
            onMain { service.player.isPlaying && service.player.currentPosition > before + 200 }
        }
        compose.onNodeWithContentDescription(context.getString(R.string.player_pause))
            .assertIsDisplayed().performClick()
        compose.waitUntil(5_000) { onMain { !service.player.playWhenReady } }
        assertMiniPlayer()
    }

    private fun saveScreenshot(name: String) {
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(context.getExternalFilesDir(null), name).outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun <T> onMain(block: () -> T): T {
        val result = AtomicReference<T>()
        instrumentation.runOnMainSync { result.set(block()) }
        return result.get()
    }

    companion object {
        private const val FIXTURE_ID = "local_620001"
        private const val TITLE = "Issue 62 recovery"
    }
}
