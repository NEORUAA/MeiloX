package com.ljyh.mei.playback

import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.ljyh.mei.data.model.Tracks
import com.ljyh.mei.data.model.api.GetSongDetails
import com.ljyh.mei.data.model.createPlaceholder
import com.ljyh.mei.data.network.api.ApiService
import com.ljyh.mei.data.network.api.WeApiService
import com.ljyh.mei.playback.queue.ListQueue
import com.ljyh.mei.playback.queue.PlaylistQueueSource
import java.io.File
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaybackQueueIntegrationTest {
    @Test
    fun refreshingPlaybackSourcesRecreatesQueuedSourcesAndPreservesPausedShuffleState() {
        Fixture().use { fixture ->
            onMain {
                fixture.player.seekTo(3, 400L)
                fixture.player.playWhenReady = false
                fixture.player.prepare()
            }
            runBlocking {
                withTimeout(3_000) {
                    while (onMain { fixture.player.playbackState != Player.STATE_READY }) delay(10)
                }
            }
            val before = onMain {
                Triple(fixture.items(), fixture.player.playbackOrderIndices(true), fixture.player.currentPosition)
            }
            fixture.sourceCreations.clear()
            onMain {
                fixture.player.refreshMediaItemSource(3, refreshQueuedSources = true)
                fixture.player.prepare()
            }
            runBlocking {
                withTimeout(3_000) {
                    while (onMain { fixture.player.playbackState != Player.STATE_READY }) delay(10)
                }
            }
            onMain {
                assertEquals("Every queued source must be recreated", before.first.map(MediaItem::mediaId), fixture.sourceCreations.toList())
                assertEquals(before.first, fixture.items())
                assertEquals(before.first.map { it.queueEntryId }, fixture.items().map { it.queueEntryId })
                assertEquals(before.second, fixture.player.playbackOrderIndices(true))
                assertEquals(3, fixture.player.currentMediaItemIndex)
                assertEquals(before.third, fixture.player.currentPosition)
                assertTrue(fixture.player.shuffleModeEnabled)
                assertEquals(Player.REPEAT_MODE_ALL, fixture.player.repeatMode)
                assertFalse(fixture.player.playWhenReady)
                assertEquals(null, fixture.player.playerError)
            }
        }
    }

    @Test
    fun selectingAnExistingSongReplacesTheQueueWithTheVisibleList() {
        Fixture().use { fixture ->
            val source = PlaylistQueueSource(42L, "visible-list")
            onMain {
                fixture.player.repeatMode = Player.REPEAT_MODE_ONE
                val visible = ListQueue(
                    id = "visible", title = "Visible playlist", playlistSource = source,
                    items = listOf("4", "2", "5").map { it to fixture.item(it) },
                )
                fixture.manager.playQueue(
                    requireNotNull(visible.startingAt("2")),
                    startInShuffleMode = true,
                    playWhenReady = false,
                )
            }
            runBlocking {
                withTimeout(3_000) {
                    while (onMain { fixture.player.mediaItemCount != 3 }) delay(10)
                }
            }
            onMain {
                assertEquals(listOf("4", "2", "5"), fixture.items().map(MediaItem::mediaId))
                assertEquals("2", fixture.player.currentMediaItem?.mediaId)
                assertTrue(fixture.player.shuffleModeEnabled)
                assertEquals(Player.REPEAT_MODE_ALL, fixture.player.repeatMode)
                assertFalse(fixture.player.playWhenReady)
                assertEquals(source, fixture.manager.playlistSource)
            }
        }
    }

    @Test
    fun selectingAnExistingSongPreservesSingleRepeatAndDisabledRepeat() {
        listOf(Player.REPEAT_MODE_ONE, Player.REPEAT_MODE_OFF).forEach { repeatMode ->
            Fixture().use { fixture ->
                onMain {
                    fixture.player.repeatMode = repeatMode
                    val visible = ListQueue(
                        id = "visible",
                        items = listOf("4", "2", "5").map { it to fixture.item(it) },
                    )
                    fixture.manager.playQueue(
                        requireNotNull(visible.startingAt("2")),
                        playWhenReady = false,
                        repeatMode = fixture.player.repeatMode,
                    )
                }
                runBlocking {
                    withTimeout(3_000) {
                        while (onMain { fixture.player.mediaItemCount != 3 }) delay(10)
                    }
                }
                onMain {
                    assertEquals(listOf("4", "2", "5"), fixture.items().map(MediaItem::mediaId))
                    assertEquals("2", fixture.player.currentMediaItem?.mediaId)
                    assertEquals(repeatMode, fixture.player.repeatMode)
                    assertFalse(fixture.player.playWhenReady)
                }
            }
        }
    }

    @Test
    fun visibleEntryHydrationRetriesMissingResultsAndSurvivesReorderingWithDuplicates() {
        Fixture(placeholders = true).use { fixture ->
            val entryId = onMain { requireNotNull(fixture.player.getMediaItemAt(5).queueEntryId) }
            fixture.response.set { emptyList() }
            runBlocking {
                withTimeout(3_000) {
                    onMain { fixture.manager.loadMetadataForQueueEntries(listOf("autoplay_section", entryId)) }.join()
                }
            }
            onMain { assertTrue(isPlaceholderMediaItem(fixture.player.getMediaItemAt(5))) }

            val requested = CountDownLatch(1)
            val respond = CountDownLatch(1)
            fixture.response.set { ids ->
                requested.countDown()
                check(respond.await(3, TimeUnit.SECONDS))
                ids
            }
            val job = onMain { fixture.manager.loadMetadataForQueueEntries(listOf(entryId)) }
            try {
                assertTrue(requested.await(3, TimeUnit.SECONDS))
                val expectedOrder = onMain {
                    fixture.player.moveMediaItem(5, 10)
                    fixture.player.playbackOrderIndices().map { fixture.player.getMediaItemAt(it).queueEntryId }
                }
                respond.countDown()
                runBlocking { withTimeout(3_000) { job.join() } }
                onMain {
                    val duplicates = fixture.items().filter { it.mediaId == "6" }
                    assertEquals(2, duplicates.size)
                    assertTrue(duplicates.all { !isPlaceholderMediaItem(it) })
                    assertEquals(2, duplicates.map { it.queueEntryId }.toSet().size)
                    assertEquals(entryId, fixture.player.getMediaItemAt(10).queueEntryId)
                    assertEquals("1", fixture.player.currentMediaItem?.mediaId)
                    assertEquals(expectedOrder, fixture.player.playbackOrderIndices().map {
                        fixture.player.getMediaItemAt(it).queueEntryId
                    })
                }
                assertEquals(listOf(listOf("6"), listOf("6")), fixture.requests.toList())
            } finally {
                respond.countDown()
            }
        }
    }

    private class Fixture(placeholders: Boolean = false) : AutoCloseable {
        private val context = InstrumentationRegistry.getInstrumentation().targetContext
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private val silence = File.createTempFile("queue-regression-", ".wav", context.cacheDir).apply {
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
                .put("RIFF".toByteArray()).putInt(16_036).put("WAVEfmt ".toByteArray())
                .putInt(16).putShort(1).putShort(1).putInt(8_000).putInt(16_000)
                .putShort(2).putShort(16).put("data".toByteArray()).putInt(16_000).array()
            writeBytes(header + ByteArray(16_000))
        }
        val requests = CopyOnWriteArrayList<List<String>>()
        val sourceCreations = CopyOnWriteArrayList<String>()
        val response = AtomicReference<(List<String>) -> List<String>>({ it })
        val player = onMain {
            val sourceFactory = DefaultMediaSourceFactory(context)
            StableDeckPlayer(
                ExoPlayer.Builder(context)
                    .setMediaSourceFactory(object : MediaSource.Factory by sourceFactory {
                        override fun createMediaSource(mediaItem: MediaItem): MediaSource {
                            sourceCreations += mediaItem.mediaId
                            return sourceFactory.createMediaSource(mediaItem)
                        }
                    }).build(),
                ExoPlayer.Builder(context).build(),
                AudioAttributes.DEFAULT,
            ).apply {
                setMediaItems((0 until 12).map { index ->
                    if (placeholders && index in setOf(5, 8)) createPlaceholder("6")
                    else item((index + 1).toString())
                })
                repeatMode = Player.REPEAT_MODE_ALL
                shuffleModeEnabled = true
                setPlaybackOrder(listOf(0, 2, 4, 6, 8, 10, 1, 3, 5, 7, 9, 11))
            }
        }
        val manager = onMain {
            val api = Proxy.newProxyInstance(ApiService::class.java.classLoader, arrayOf(ApiService::class.java)) { _, method, args ->
                check(method.name == "getSongDetail") { "Unexpected API call: ${method.name}" }
                val ids = JsonParser.parseString((args!![0] as GetSongDetails).c).asJsonArray
                    .map { it.asJsonObject["id"].asString }
                requests += ids
                val songs = response.get()(ids).map { id ->
                    mapOf("id" to id.toLong(), "name" to "Loaded $id", "dt" to 1_000,
                        "al" to mapOf("id" to 1, "name" to "Album", "picUrl" to ""),
                        "ar" to listOf(mapOf("id" to 1, "name" to "Artist")))
                }
                val gson = Gson()
                gson.fromJson(gson.toJson(mapOf("code" to 200, "songs" to songs, "privileges" to emptyList<Any>())), Tracks::class.java)
            } as ApiService
            val weApi = Proxy.newProxyInstance(WeApiService::class.java.classLoader, arrayOf(WeApiService::class.java)) { _, method, _ ->
                error("Unexpected WeAPI call: ${method.name}")
            } as WeApiService
            PlaybackQueueManager(player, api, weApi, scope, serverShuffleAllowed = { false })
        }

        fun item(id: String): MediaItem = MediaItem.Builder().setMediaId(id).setUri(silence.toURI().toString()).build()
        fun items(): List<MediaItem> = (0 until player.mediaItemCount).map(player::getMediaItemAt)

        override fun close() {
            onMain {
                manager.release()
                scope.cancel()
                player.release()
            }
            silence.delete()
        }
    }

    companion object {
        private fun <T> onMain(block: () -> T): T {
            val result = AtomicReference<T>()
            InstrumentationRegistry.getInstrumentation().runOnMainSync { result.set(block()) }
            return result.get()
        }
    }
}
