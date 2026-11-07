package com.ljyh.mei.playback

import android.os.Bundle
import android.media.session.PlaybackState
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.session.MediaSession
import androidx.media3.session.PlatformLyricsSessionAccess
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.ljyh.mei.data.model.Tracks
import com.ljyh.mei.data.model.createPlaceholder
import com.ljyh.mei.data.model.toMediaItem
import com.ljyh.mei.data.model.toMediaMetadata
import com.ljyh.mei.playback.queue.ListQueue
import com.ljyh.mei.playback.queue.PlaylistQueueSource
import com.ljyh.mei.playback.queue.SourceContentQueue
import com.ljyh.mei.source.api.*
import com.ljyh.mei.source.core.ProviderRegistry
import com.ljyh.mei.source.core.SourceRouter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaybackQueueIntegrationTest {
    @Test
    fun legacyMediaControlsCanSkipSelectPauseAndSeekWithoutReplacingTheQueue() {
        Fixture().use { fixture ->
            val session = onMain {
                MediaSession.Builder(InstrumentationRegistry.getInstrumentation().targetContext, fixture.player)
                    .setCallback(object : MediaSession.Callback {
                        override fun onConnectAsync(session: MediaSession, controller: MediaSession.ControllerInfo) =
                            Futures.immediateFuture(MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                                .setAvailablePlayerCommands(externalTransportCommands(
                                    Player.Commands.Builder().addAllReadOnlyCommands().build(),
                                )).build())

                        override fun onSetMediaItems(
                            mediaSession: MediaSession,
                            controller: MediaSession.ControllerInfo,
                            mediaItems: List<MediaItem>,
                            startIndex: Int,
                            startPositionMs: Long,
                        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
                            externalQueueSelection(fixture.player, mediaItems, startPositionMs)?.let { Futures.immediateFuture(it) }
                                ?: super.onSetMediaItems(mediaSession, controller, mediaItems, startIndex, startPositionMs)
                    }).build().also { session ->
                        fixture.player.isExternalMediaRequest = {
                            session.controllerForCurrentRequest?.controllerVersion == MediaSession.ControllerInfo.LEGACY_CONTROLLER_VERSION
                        }
                    }
            }
            fun await(matches: () -> Boolean) = runBlocking {
                withTimeout(5_000) { while (!onMain(matches)) delay(10) }
            }
            try {
                val platform = onMain { PlatformLyricsSessionAccess.get(session) }
                val transport = platform.controller.transportControls
                val source = PlaylistQueueSource(42L, "Playlist")
                val before = onMain {
                    fixture.manager.restorePlaylistSource(source)
                    fixture.player.prepare()
                    fixture.items() to fixture.player.playbackOrderIndices(true)
                }
                await { fixture.player.playbackState == Player.STATE_READY }
                onMain {
                    val actions = requireNotNull(platform.controller.playbackState).actions
                    assertTrue(actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L)
                    assertTrue(actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L)
                    assertTrue(actions and PlaybackState.ACTION_SEEK_TO != 0L)
                }
                onMain { transport.skipToNext() }
                await { fixture.player.currentMediaItemIndex == before.second[1] }
                onMain { transport.skipToPrevious() }
                await { fixture.player.currentMediaItemIndex == before.second[0] }
                onMain { transport.skipToQueueItem(4) }
                await { fixture.player.currentMediaItemIndex == 4 }
                onMain { transport.playFromMediaId("6", Bundle.EMPTY) }
                await { fixture.player.currentMediaItemIndex == 5 && fixture.player.isPlaying }
                onMain { transport.pause() }
                await { !fixture.player.playWhenReady }
                onMain { transport.seekTo(200L) }
                await { fixture.player.currentPosition == 200L }
                onMain {
                    assertEquals(before.first, fixture.items())
                    assertEquals(before.second, fixture.player.playbackOrderIndices(true))
                    assertEquals(source, fixture.manager.playlistSource)
                    assertTrue(fixture.player.shuffleModeEnabled)
                    assertEquals(Player.REPEAT_MODE_ALL, fixture.player.repeatMode)
                    assertEquals(null, externalQueueSelection(fixture.player,
                        listOf(MediaItem.Builder().setMediaId("missing").build()), C.TIME_UNSET))
                }
            } finally {
                onMain { session.release() }
            }
        }
    }

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

    @Test
    fun sourceContentQueueAppendsOnePageNearTheTailAndStopsOnAccountChange() {
        Fixture().use { fixture ->
            val requests = CopyOnWriteArrayList<PageRequest>()
            val secondStarted = CompletableDeferred<Unit>()
            val secondResponse = CompletableDeferred<Unit>()
            val secondFinished = CompletableDeferred<Unit>()
            val sessionRevision = MutableStateFlow("account-a")
            fun track(index: Int) = Track(TrackRef("queue.external", "track-$index"), "Track $index", emptyList())
            val provider = object : MusicProvider, PlaylistCapability, AccountRevisionCapability {
                override val id = "queue.external"
                override val displayName = "Queue external"
                override val capabilities = setOf(SourceCapability.PLAYLIST)
                override val accountRevision = sessionRevision
                override suspend fun playlist(ref: PlaylistRef) = SourcePlaylist(ref, "Source playlist", trackCount = 633)
                override suspend fun playlistTracks(ref: PlaylistRef, page: PageRequest): SourcePage<Track> {
                    check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
                    requests += page
                    if (page.offset == 60) {
                        withContext(NonCancellable) {
                            secondStarted.complete(Unit)
                            secondResponse.await()
                            secondFinished.complete(Unit)
                        }
                    }
                    return SourcePage((page.offset until page.offset + page.limit).map(::track), true,
                        (page.offset + page.limit).toString())
                }
            }
            val queue = SourceContentQueue(SourceRouter(ProviderRegistry(listOf(provider))),
                PlaylistRef(provider.id, "playlist"), "Source playlist", (0 until 30).map(::track),
                nextCursor = "30", knownTotalCount = 633, initialAccountRevision = "account-a",
                mediaItemFactory = fixture::sourceItem)
            fun await(matches: () -> Boolean) = runBlocking {
                withTimeout(5_000) { while (!onMain(matches)) delay(10) }
            }
            try {
                onMain { fixture.manager.playQueue(queue, playWhenReady = false) }
                await { fixture.player.mediaItemCount == 30 }
                assertTrue(requests.isEmpty())
                onMain { fixture.player.seekToDefaultPosition(27) }
                await { fixture.player.mediaItemCount == 60 }
                assertEquals(listOf(PageRequest(30, 30, "30")), requests.toList())
                onMain {
                    assertEquals(TrackRef(provider.id, "track-27"), fixture.player.currentMediaItem?.let {
                        TrackRefCodec.decode(it.mediaId)
                    })
                    assertFalse(fixture.player.playWhenReady)
                    fixture.player.seekToDefaultPosition(57)
                }
                runBlocking { withTimeout(5_000) { secondStarted.await() } }
                sessionRevision.value = "account-b"
                secondResponse.complete(Unit)
                runBlocking { withTimeout(5_000) { secondFinished.await() } }
                await { !queue.hasNextPage() }
                onMain {
                    assertEquals(60, fixture.player.mediaItemCount)
                    assertEquals(57, fixture.player.currentMediaItemIndex)
                    assertFalse(fixture.player.playWhenReady)
                }
                assertTrue(fixture.requests.isEmpty())
            } finally {
                secondResponse.complete(Unit)
            }
        }
    }

    @Test
    fun restoredSourceQueueWaitsForItsPluginAndContinuesWithoutReplacingRestoredPlayback() {
        val registry = ProviderRegistry(emptyList())
        val router = SourceRouter(registry)
        Fixture(sourceRouter = router).use { fixture ->
            val requests = CopyOnWriteArrayList<PageRequest>()
            fun track(index: Int) = Track(TrackRef("restore.external", "track-$index"), "Track $index", emptyList())
            fun provider() = object : MusicProvider, PlaylistCapability, AccountRevisionCapability {
                override val id = "restore.external"
                override val displayName = "Restore external"
                override val capabilities = setOf(SourceCapability.PLAYLIST)
                override val accountRevision = MutableStateFlow("account-a")
                override suspend fun playlist(ref: PlaylistRef) = SourcePlaylist(ref, "Restored", trackCount = 9)
                override suspend fun playlistTracks(ref: PlaylistRef, page: PageRequest): SourcePage<Track> {
                    check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
                    requests += page
                    val end = page.offset + page.limit
                    return SourcePage((page.offset until end).map(::track), end < 9,
                        if (end < 9) "private-cursor-$end" else null)
                }
            }
            val originalProvider = provider()
            registry.register(originalProvider)
            val initial = (0 until 6).map(::track)
            val queue = SourceContentQueue(router, PlaylistRef(originalProvider.id, "playlist"), "Restored", initial,
                pageSize = 3, nextCursor = "private-cursor-6", knownTotalCount = 9, mediaItemFactory = fixture::sourceItem)
            runBlocking { queue.getInitialStatus() }
            val context = requireNotNull(queue.snapshotContext(initial.map { it.ref }))
            queue.release()
            registry.unregister(originalProvider.instanceId)
            onMain {
                fixture.player.withInternalQueueUpdate {
                    fixture.player.shuffleModeEnabled = false
                    fixture.player.setMediaItems(initial.map(fixture::sourceItem), 4, 300L)
                    fixture.player.repeatMode = Player.REPEAT_MODE_OFF
                    fixture.player.playWhenReady = false
                    fixture.player.prepare()
                }
                fixture.manager.restoreSourceContentQueue(context, "Restored")
                assertEquals(context, fixture.manager.captureSourceContentContext())
                assertEquals(6, fixture.player.mediaItemCount)
            }
            assertTrue(requests.isEmpty())
            registry.register(provider())
            runBlocking {
                withTimeout(5_000) { while (onMain { fixture.player.mediaItemCount != 9 }) delay(10) }
            }
            assertEquals(listOf(PageRequest(0, 3), PageRequest(3, 3, "private-cursor-3"),
                PageRequest(6, 3, "private-cursor-6")), requests.toList())
            onMain {
                assertEquals(4, fixture.player.currentMediaItemIndex)
                assertEquals(300L, fixture.player.currentPosition)
                assertFalse(fixture.player.playWhenReady)
                assertEquals(Player.REPEAT_MODE_OFF, fixture.player.repeatMode)
                assertEquals(initial[4].ref, fixture.player.currentMediaItem?.let { TrackRefCodec.decode(it.mediaId) })
            }
            assertTrue(fixture.requests.isEmpty())
        }
    }

    private class Fixture(placeholders: Boolean = false, sourceRouter: SourceRouter? = null) : AutoCloseable {
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
                            val playable = if (sourceRouter != null) mediaItem.buildUpon()
                                .setUri(silence.toURI().toString()).build() else mediaItem
                            return sourceFactory.createMediaSource(playable)
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
            val sources = object : QueueSourceActions {
                override suspend fun open(providerId: String?): QueueSourceSession = error("Unexpected recommendation request")
                override suspend fun tracks(refs: List<TrackRef>): List<Track> {
                    if (sourceRouter != null) return sourceRouter.getTracks(refs)
                    val ids = refs.map { it.trackId }
                    requests += ids
                    return response.get()(ids).map { id -> Track(TrackRef(ProviderIds.NETEASE, id), "Loaded $id",
                        listOf(SourceArtist("Artist", ArtistRef(ProviderIds.NETEASE, "1"))),
                        SourceAlbum("Album", AlbumRef(ProviderIds.NETEASE, "1")), 1000) }
                }
            }
            PlaybackQueueManager(player, sources, scope, sourceRouter = sourceRouter, serverShuffleAllowed = { false })
        }

        fun item(id: String): MediaItem = MediaItem.Builder().setMediaId(id).setUri(silence.toURI().toString()).build()
        fun sourceItem(track: Track): MediaItem = track.toMediaMetadata().toMediaItem().buildUpon()
            .setUri(silence.toURI().toString()).build()
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
