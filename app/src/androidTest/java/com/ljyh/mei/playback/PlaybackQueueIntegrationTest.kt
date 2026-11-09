package com.ljyh.mei.playback

import android.os.Bundle
import android.net.Uri
import android.media.session.PlaybackState
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
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
import kotlin.random.Random
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
                    .setId("queue-regression-${System.nanoTime()}")
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

    @Test
    fun cloudShuffleExpandsPlaylistAndLikedQueuesBeyondTheVisiblePageWithoutMovingPausedPlayback() {
        for (library in listOf(false, true)) {
            val provider = ShuffleProvider()
            val router = SourceRouter(ProviderRegistry(listOf(provider)))
            val response = CompletableDeferred<SourceShufflePage>()
            val requests = CopyOnWriteArrayList<List<String>>()
            val session = object : QueueSourceSession {
                override val providerId = provider.id
                override suspend fun ensureCurrent() = Unit
                override suspend fun scenes(): List<PrivateFmScene> = error("Unexpected scenes request")
                override suspend fun radio(parameters: Map<String, String>, similar: Boolean): List<Track> = error("Unexpected radio request")
                override suspend fun shuffle(playlistId: String, songId: String, algorithm: String,
                    collection: LibraryCollection?): SourceShufflePage {
                    requests += listOf(playlistId, songId, algorithm, collection?.name.orEmpty())
                    return response.await()
                }
            }
            Fixture(sourceRouter = router, queueSession = { requested ->
                assertEquals(provider.id, requested)
                session
            }).use { fixture ->
                val initial = (0 until 30).map(provider::track)
                val queue = if (library) SourceContentQueue.forLibrary(router, provider.id,
                    initialTracks = initial, startIndex = 4, nextCursor = "30", mediaItemFactory = fixture::sourceItem)
                else SourceContentQueue(router, PlaylistRef(provider.id, "opaque:playlist"), "Playlist", initial,
                    startIndex = 4, nextCursor = "30", knownTotalCount = 90, mediaItemFactory = fixture::sourceItem)
                onMain {
                    fixture.manager.setCloudShuffleEnabled(true)
                    fixture.manager.playQueue(queue, startInShuffleMode = true, playWhenReady = false)
                }
                await("initial page") { fixture.player.mediaItemCount == 30 }
                await("first cloud request") { requests.isNotEmpty() }
                assertEquals(listOf(if (library) "" else "opaque:playlist", "track-4", "",
                    if (library) "LIKED_TRACKS" else ""), requests.single())
                val current = onMain {
                    fixture.player.seekTo(4, 300L)
                    requireNotNull(fixture.player.currentMediaItem?.queueEntryId)
                }
                val tracks = (0 until 90).map(provider::track)
                val serverOrder = listOf(tracks[4].ref) + tracks.drop(30).map { it.ref } +
                    tracks.take(30).filterNot { it.ref == tracks[4].ref }.map { it.ref }
                response.complete(SourceShufflePage(serverOrder, initial, emptySet()))
                await("expanded cloud queue") { fixture.player.mediaItemCount == 90 }
                await("frozen cloud paging") { !queue.hasNextPage() }
                onMain {
                    assertEquals(serverOrder, fixture.player.playbackOrderIndices().map {
                        requireNotNull(TrackRefCodec.decode(fixture.player.getMediaItemAt(it).mediaId))
                    })
                }
                await("out-of-page metadata request") { provider.catalogRequests.contains(tracks[30].ref) }
                await("out-of-page metadata hydration") { !isPlaceholderMediaItem(fixture.player.getMediaItemAt(30)) }
                onMain {
                    assertEquals(current, fixture.player.currentMediaItem?.queueEntryId)
                    assertEquals(300L, fixture.player.currentPosition)
                    assertFalse(fixture.player.playWhenReady)
                    assertEquals(serverOrder, fixture.player.playbackOrderIndices().map {
                        requireNotNull(TrackRefCodec.decode(fixture.player.getMediaItemAt(it).mediaId))
                    })
                    fixture.player.seekToDefaultPosition(fixture.player.playbackOrderIndices().last())
                    assertEquals(90, fixture.player.mediaItemCount)
                }
                assertTrue(provider.requests.isEmpty())
                provider.accountRevision.value = "account-b"
                await("cloud owner invalidation") { fixture.manager.playlistSource == null }
                onMain { fixture.manager.setShuffleModeEnabled(false) }
                await { !fixture.player.shuffleModeEnabled }
                onMain { fixture.manager.setShuffleModeEnabled(true) }
                await { fixture.player.shuffleModeEnabled }
                assertEquals(1, requests.size)
                onMain { assertEquals(90, fixture.player.mediaItemCount) }

                // Search/filtered lists deliberately have no full collection locator.
                onMain {
                    fixture.manager.playQueue(ListQueue("search", items = initial.take(3).map {
                        TrackRefCodec.mediaId(it.ref) to fixture.sourceItem(it)
                    }), startInShuffleMode = true, playWhenReady = false)
                }
                await { fixture.player.mediaItemCount == 3 }
                assertEquals(1, requests.size)
            }
        }
    }

    @Test
    fun failedCloudShuffleLoadsTheCompleteLocalQueueBeforeFallback() {
        val provider = ShuffleProvider()
        val router = SourceRouter(ProviderRegistry(listOf(provider)))
        val requested = CompletableDeferred<Unit>()
        val session = object : QueueSourceSession {
            override val providerId = provider.id
            override suspend fun ensureCurrent() = Unit
            override suspend fun scenes(): List<PrivateFmScene> = error("Unexpected scenes request")
            override suspend fun radio(parameters: Map<String, String>, similar: Boolean): List<Track> = error("Unexpected radio request")
            override suspend fun shuffle(playlistId: String, songId: String, algorithm: String,
                collection: LibraryCollection?): SourceShufflePage {
                requested.complete(Unit)
                throw SourceException(SourceErrorCode.NETWORK)
            }
        }
        Fixture(sourceRouter = router, queueSession = { session }).use { fixture ->
            val queue = SourceContentQueue(router, PlaylistRef(provider.id, "opaque:playlist"), "Playlist",
                (0 until 30).map(provider::track), nextCursor = "30", knownTotalCount = 90,
                mediaItemFactory = fixture::sourceItem)
            onMain {
                fixture.manager.setCloudShuffleEnabled(true)
                fixture.manager.playQueue(queue, startInShuffleMode = true, playWhenReady = false)
            }
            runBlocking { withTimeout(5_000) { requested.await() } }
            await("complete local fallback") { fixture.player.mediaItemCount == 90 }
            assertEquals(listOf(PageRequest(30, 100, "30")), provider.requests.toList())
            onMain {
                assertFalse(fixture.player.playWhenReady)
                assertTrue(fixture.player.shuffleModeEnabled)
                assertEquals(provider.track(0).ref, fixture.player.currentMediaItem?.let { TrackRefCodec.decode(it.mediaId) })
            }
        }
    }

    @Test
    fun consecutiveInitialCloudQueuesRequestAndCommitWhileReplacingAnAlreadyShuffledQueue() {
        for (library in listOf(false, true)) for (randomStart in listOf(false, true)) {
            val provider = ShuffleProvider()
            val router = SourceRouter(ProviderRegistry(listOf(provider)))
            val requests = CopyOnWriteArrayList<String>()
            val responses = List(2) { CompletableDeferred<SourceShufflePage>() }
            val session = object : QueueSourceSession {
                override val providerId = provider.id
                override suspend fun ensureCurrent() = Unit
                override suspend fun scenes(): List<PrivateFmScene> = error("Unexpected scenes request")
                override suspend fun radio(parameters: Map<String, String>, similar: Boolean): List<Track> = error("Unexpected radio request")
                override suspend fun canonicalShuffleIds(playlistId: String, collection: LibraryCollection?) =
                    (0 until 90).map { provider.track(it).ref }
                override suspend fun shuffle(playlistId: String, songId: String, algorithm: String,
                    collection: LibraryCollection?): SourceShufflePage {
                    val index = requests.size
                    requests += songId
                    return responses[index].await()
                }
            }
            Fixture(sourceRouter = router, queueSession = { session }, shuffleRandom = Random(42)).use { fixture ->
                onMain { fixture.manager.setCloudShuffleEnabled(true) }
                for (iteration in 0 until 2) {
                    val initial = (0 until 30).map(provider::track)
                    val queue = if (library) SourceContentQueue.forLibrary(router, provider.id,
                        initialTracks = initial, startIndex = 4, nextCursor = "30", mediaItemFactory = fixture::sourceItem)
                    else SourceContentQueue(router, PlaylistRef(provider.id, "same:playlist"), "Playlist", initial,
                        startIndex = 4, nextCursor = "30", mediaItemFactory = fixture::sourceItem)
                    if (iteration > 0) onMain { assertTrue(fixture.player.shuffleModeEnabled) }
                    onMain {
                        fixture.manager.playQueue(queue, startInShuffleMode = true,
                            randomStart = randomStart, playWhenReady = false)
                    }
                    await("repeat initial cloud request ${iteration + 1}, library=$library randomStart=$randomStart") {
                        requests.size == iteration + 1
                    }
                    val current = onMain {
                        assertTrue(fixture.manager.shuffleModeIntent.value)
                        assertTrue(fixture.player.shuffleModeEnabled)
                        assertEquals(90, fixture.player.mediaItemCount)
                        assertFalse(fixture.player.playWhenReady)
                        assertTrue(fixture.manager.playlistSource != null)
                        fixture.player.seekTo(fixture.player.currentMediaItemIndex, 350L)
                        requireNotNull(fixture.player.currentMediaItem?.queueEntryId)
                    }
                    val serverOrder = (0 until 90).map { provider.track(it).ref }.let {
                        if (iteration == 0) it.reversed() else it.drop(30) + it.take(30)
                    }
                    responses[iteration].complete(SourceShufflePage(serverOrder, initial, emptySet()))
                    val playing = TrackRef(provider.id, requests[iteration])
                    val expectedOrder = listOf(playing) + serverOrder.filterNot { it == playing }
                    await("repeat initial cloud commit ${iteration + 1}, library=$library randomStart=$randomStart") {
                        fixture.player.shuffleModeEnabled && fixture.player.mediaItemCount == 90 &&
                            fixture.player.playbackOrderIndices().map { TrackRefCodec.decode(fixture.player.getMediaItemAt(it).mediaId) } == expectedOrder
                    }
                    onMain {
                        assertEquals(current, fixture.player.currentMediaItem?.queueEntryId)
                        assertEquals(350L, fixture.player.currentPosition)
                        assertFalse(fixture.player.playWhenReady)
                        assertEquals(listOf(playing) + serverOrder.filterNot { it == playing },
                            fixture.player.playbackOrderIndices().map {
                                requireNotNull(TrackRefCodec.decode(fixture.player.getMediaItemAt(it).mediaId))
                            })
                    }
                }
                assertEquals(2, requests.size)
                assertTrue(provider.requests.isEmpty())
            }
        }
    }

    @Test
    fun initialCloudRandomStartUsesTheFullIdentitySetWhileClickedTracksKeepTheirSelection() {
        for (library in listOf(false, true)) for (randomStart in listOf(false, true)) for (fastIds in listOf(false, true)) {
            val provider = ShuffleProvider()
            val router = SourceRouter(ProviderRegistry(listOf(provider)))
            val requested = CompletableDeferred<String>()
            val response = CompletableDeferred<SourceShufflePage>()
            val identityRequests = CopyOnWriteArrayList<Pair<String, LibraryCollection?>>()
            val session = object : QueueSourceSession {
                override val providerId = provider.id
                override suspend fun ensureCurrent() = Unit
                override suspend fun scenes(): List<PrivateFmScene> = error("Unexpected scenes request")
                override suspend fun radio(parameters: Map<String, String>, similar: Boolean): List<Track> = error("Unexpected radio request")
                override suspend fun canonicalShuffleIds(playlistId: String, collection: LibraryCollection?): List<TrackRef>? {
                    identityRequests += playlistId to collection
                    return if (fastIds) (0 until 90).map { provider.track(it).ref } else null
                }
                override suspend fun shuffle(playlistId: String, songId: String, algorithm: String,
                    collection: LibraryCollection?): SourceShufflePage {
                    requested.complete(songId)
                    return response.await()
                }
            }
            val seed = (0..100).first { Random(it).nextInt(90) >= 30 }
            Fixture(sourceRouter = router, queueSession = { session }, shuffleRandom = Random(seed)).use { fixture ->
                val initial = (0 until 30).map(provider::track)
                val queue = if (library) SourceContentQueue.forLibrary(router, provider.id,
                    initialTracks = initial, startIndex = 4, nextCursor = "30", mediaItemFactory = fixture::sourceItem)
                else SourceContentQueue(router, PlaylistRef(provider.id, "opaque:playlist"), "Playlist", initial,
                    startIndex = 4, nextCursor = "30", mediaItemFactory = fixture::sourceItem)
                onMain {
                    fixture.manager.setCloudShuffleEnabled(true)
                    fixture.manager.playQueue(queue, startInShuffleMode = true, randomStart = randomStart, playWhenReady = false)
                }
                val playingId = runBlocking { withTimeout(5_000) { requested.await() } }
                val current = onMain {
                    assertTrue(fixture.manager.shuffleModeIntent.value)
                    assertEquals(fastIds, fixture.player.shuffleModeEnabled)
                    assertFalse(fixture.player.playWhenReady)
                    assertEquals(if (fastIds) 90 else 30, fixture.player.mediaItemCount)
                    if (randomStart && fastIds) assertTrue(fixture.player.currentMediaItemIndex >= 30)
                    else if (randomStart) assertTrue(fixture.player.currentMediaItemIndex < 30)
                    else assertEquals("track-4", playingId)
                    fixture.player.seekTo(fixture.player.currentMediaItemIndex, 350L)
                    requireNotNull(fixture.player.currentMediaItem?.queueEntryId)
                }
                assertEquals(if (fastIds) 1 else 2, identityRequests.size)
                assertTrue(identityRequests.all { it == if (library) "" to LibraryCollection.LIKED_TRACKS
                    else "opaque:playlist" to null })
                assertTrue(provider.requests.isEmpty())
                val serverOrder = (0 until 90).reversed().map { provider.track(it).ref }
                response.complete(SourceShufflePage(serverOrder, initial, emptySet()))
                val expectedOrder = listOf(TrackRef(provider.id, playingId)) + serverOrder.filterNot { it.trackId == playingId }
                await("full initial server order") {
                    fixture.player.mediaItemCount == 90 && fixture.player.shuffleModeEnabled &&
                        fixture.player.playbackOrderIndices().map { TrackRefCodec.decode(fixture.player.getMediaItemAt(it).mediaId) } == expectedOrder
                }
                onMain {
                    val playing = TrackRef(provider.id, playingId)
                    assertEquals(current, fixture.player.currentMediaItem?.queueEntryId)
                    assertEquals(350L, fixture.player.currentPosition)
                    assertFalse(fixture.player.playWhenReady)
                    assertEquals(listOf(playing) + serverOrder.filterNot { it == playing },
                        fixture.player.playbackOrderIndices().map {
                            requireNotNull(TrackRefCodec.decode(fixture.player.getMediaItemAt(it).mediaId))
                        })
                }
                assertTrue(provider.requests.isEmpty())
            }
        }
    }

    @Test
    fun completeSourceIdentitiesKeepRepeatedShuffleImmediateAndTheServerOrderAuthoritative() {
        for (library in listOf(false, true)) for (learnFromResult in listOf(false, true)) {
            val provider = ShuffleProvider()
            val router = SourceRouter(ProviderRegistry(listOf(provider)))
            val canonical = (0 until 90).map { provider.track(it).ref }
            val responses = List(3) { CompletableDeferred<SourceShufflePage>() }
            val requests = CopyOnWriteArrayList<List<TrackRef>?>()
            var identityRequests = 0
            var opened = 0
            val session = object : QueueSourceSession {
                override val providerId = provider.id
                override suspend fun ensureCurrent() = Unit
                override suspend fun scenes(): List<PrivateFmScene> = error("Unexpected scenes request")
                override suspend fun radio(parameters: Map<String, String>, similar: Boolean): List<Track> = error("Unexpected radio request")
                override suspend fun canonicalShuffleIds(playlistId: String, collection: LibraryCollection?): List<TrackRef>? {
                    identityRequests++
                    return if (learnFromResult) null else canonical
                }
                override suspend fun shuffle(playlistId: String, songId: String, algorithm: String,
                    collection: LibraryCollection?): SourceShufflePage = error("Expected the complete identity overload")
                override suspend fun shuffle(playlistId: String, songId: String, algorithm: String,
                    collection: LibraryCollection?, canonicalIds: List<TrackRef>?): SourceShufflePage {
                    val index = requests.size
                    requests += canonicalIds
                    return responses[index].await()
                }
            }
            Fixture(sourceRouter = router, queueSession = { opened++; session }, shuffleRandom = Random(42)).use { fixture ->
                fun order() = fixture.player.playbackOrderIndices().map {
                    requireNotNull(TrackRefCodec.decode(fixture.player.getMediaItemAt(it).mediaId))
                }
                val initial = (0 until 30).map(provider::track)
                val queue = if (library) SourceContentQueue.forLibrary(router, provider.id, initialTracks = initial,
                    startIndex = 4, nextCursor = "30", mediaItemFactory = fixture::sourceItem)
                else SourceContentQueue(router, PlaylistRef(provider.id, "opaque:playlist"), "Playlist", initial,
                    startIndex = 4, nextCursor = "30", mediaItemFactory = fixture::sourceItem)
                onMain {
                    fixture.manager.setCloudShuffleEnabled(true)
                    fixture.manager.playQueue(queue, playWhenReady = false)
                }
                await("sequential source prefix") { fixture.player.mediaItemCount == 30 }
                val current = onMain {
                    fixture.player.seekTo(4, 350L)
                    requireNotNull(fixture.player.currentMediaItem?.queueEntryId)
                }
                val firstOrder = listOf(canonical[4]) + canonical.reversed().filterNot { it == canonical[4] }
                onMain { fixture.manager.setShuffleModeEnabled(true) }
                await("first source shuffle request") { requests.size == 1 }
                if (!learnFromResult) onMain {
                    assertTrue(fixture.player.shuffleModeEnabled)
                    assertEquals(canonical.toSet(), order().toSet())
                    assertFalse(order() == listOf(canonical[4]) + canonical.filterNot { it == canonical[4] })
                }
                responses[0].complete(SourceShufflePage(firstOrder, initial, emptySet(),
                    canonicalOrder = if (learnFromResult) canonical else null))
                await("first authoritative source order") { order() == firstOrder }
                val identitiesAfterLearning = identityRequests
                val ownersAfterLearning = opened
                for (repeat in 1..2) {
                    val prefix = onMain {
                        if (repeat == 1) fixture.manager.setShuffleModeEnabled(false)
                        else {
                            fixture.player.seekToDefaultPosition(fixture.player.playbackOrderIndices()[1])
                            fixture.player.seekTo(fixture.player.currentMediaItemIndex, 350L)
                            fixture.manager.setCloudShuffleEnabled(false)
                        }
                        if (repeat == 1) listOf(requireNotNull(fixture.player.currentMediaItem?.let { TrackRefCodec.decode(it.mediaId) }))
                        else order().take(2)
                    }
                    if (repeat == 1) await("shuffle disabled") { !fixture.player.shuffleModeEnabled }
                    else await("complete cached local order") { fixture.player.shuffleModeEnabled }
                    val playingEntry = onMain {
                        if (repeat == 1) fixture.manager.setShuffleModeEnabled(true)
                        else fixture.manager.setCloudShuffleEnabled(true)
                        requireNotNull(fixture.player.currentMediaItem?.queueEntryId)
                    }
                    await("cached complete shuffle request $repeat") { requests.size == repeat + 1 }
                    onMain {
                        assertTrue(fixture.player.shuffleModeEnabled)
                        assertEquals(canonical.toSet(), order().toSet())
                        assertEquals(prefix, order().take(prefix.size))
                        assertEquals(canonical, requests[repeat])
                        assertEquals(350L, fixture.player.currentPosition)
                        assertFalse(fixture.player.playWhenReady)
                        if (repeat == 1) assertEquals(current, playingEntry)
                    }
                    assertEquals(identitiesAfterLearning, identityRequests)
                    assertEquals(ownersAfterLearning, opened)
                    val sourceOrder = if (repeat == 1) canonical.drop(30) + canonical.take(30) else canonical.reversed()
                    val expected = prefix + sourceOrder.filterNot { it in prefix }
                    responses[repeat].complete(SourceShufflePage(sourceOrder, initial, emptySet()))
                    await("source order replaces the immediate local order $repeat") { order() == expected }
                    onMain {
                        assertEquals(playingEntry, fixture.player.currentMediaItem?.queueEntryId)
                        assertEquals(350L, fixture.player.currentPosition)
                        assertFalse(fixture.player.playWhenReady)
                    }
                }
                assertTrue(provider.requests.isEmpty())
                provider.accountRevision.value = "account-b"
                await("cached source owner invalidation") { fixture.manager.playlistSource == null }
                assertFalse(runBlocking { queue.isSourceCurrent() })
            }
        }
    }

    @Test
    fun cancelledIdentityPreparationCannotOverwriteANewerCompleteServerOrder() {
        val provider = ShuffleProvider()
        val router = SourceRouter(ProviderRegistry(listOf(provider)))
        val canonical = (0 until 90).map { provider.track(it).ref }
        val oldIdentityResponse = CompletableDeferred<Unit>()
        val oldIdentityReturned = CompletableDeferred<Unit>()
        val requested = CompletableDeferred<Unit>()
        val response = CompletableDeferred<SourceShufflePage>()
        var identityRequests = 0
        val session = object : QueueSourceSession {
            override val providerId = provider.id
            override suspend fun ensureCurrent() = Unit
            override suspend fun scenes(): List<PrivateFmScene> = error("Unexpected scenes request")
            override suspend fun radio(parameters: Map<String, String>, similar: Boolean): List<Track> = error("Unexpected radio request")
            override suspend fun canonicalShuffleIds(playlistId: String, collection: LibraryCollection?): List<TrackRef> {
                if (++identityRequests == 1) withContext(NonCancellable) {
                    oldIdentityResponse.await()
                    oldIdentityReturned.complete(Unit)
                }
                return canonical
            }
            override suspend fun shuffle(playlistId: String, songId: String, algorithm: String,
                collection: LibraryCollection?): SourceShufflePage {
                requested.complete(Unit)
                return response.await()
            }
        }
        Fixture(sourceRouter = router, queueSession = { session }, shuffleRandom = Random(42)).use { fixture ->
            val initial = (0 until 30).map(provider::track)
            val queue = SourceContentQueue(router, PlaylistRef(provider.id, "opaque:playlist"), "Playlist", initial,
                startIndex = 4, nextCursor = "30", mediaItemFactory = fixture::sourceItem)
            onMain {
                fixture.manager.setCloudShuffleEnabled(false)
                fixture.manager.playQueue(queue, playWhenReady = false)
            }
            await("sequential source before cancelled preparation") { fixture.player.mediaItemCount == 30 }
            val current = onMain {
                fixture.player.seekTo(4, 350L)
                fixture.manager.setShuffleModeEnabled(true)
                requireNotNull(fixture.player.currentMediaItem?.queueEntryId)
            }
            await("old identity request") { identityRequests == 1 }
            onMain { fixture.manager.setCloudShuffleEnabled(true) }
            runBlocking { withTimeout(5_000) { requested.await() } }
            onMain { assertTrue(fixture.player.shuffleModeEnabled); assertEquals(90, fixture.player.mediaItemCount) }
            val sourceOrder = canonical.reversed()
            val expected = listOf(canonical[4]) + sourceOrder.filterNot { it == canonical[4] }
            response.complete(SourceShufflePage(sourceOrder, initial, emptySet()))
            await("new server order") { fixture.player.playbackOrderIndices().map {
                TrackRefCodec.decode(fixture.player.getMediaItemAt(it).mediaId)
            } == expected }
            oldIdentityResponse.complete(Unit)
            runBlocking { withTimeout(5_000) { oldIdentityReturned.await() }; delay(50) }
            onMain {
                assertEquals(expected, fixture.player.playbackOrderIndices().map {
                    TrackRefCodec.decode(fixture.player.getMediaItemAt(it).mediaId)
                })
                assertEquals(current, fixture.player.currentMediaItem?.queueEntryId)
                assertEquals(350L, fixture.player.currentPosition)
                assertFalse(fixture.player.playWhenReady)
            }
            assertEquals(2, identityRequests)
            assertTrue(provider.requests.isEmpty())
        }
    }

    @Test
    fun sourceShufflePublishesTheFrozenFinalWindowWithItsMetadataAndReusesReadyTracks() {
        for (readyTracks in listOf(false, true)) for (extras in listOf(false, true)) {
            val provider = ShuffleProvider(metadataArtwork = true)
            val router = SourceRouter(ProviderRegistry(listOf(provider)))
            val canonical = (0 until 90).map { provider.track(it).ref }
            val session = PendingCloudShuffle(provider.id, canonicalIds = canonical)
            val random = Random(42)
            // Initial full local shuffle consumes the same random stream before pending extras.
            canonical.indices.filterNot { it == 4 }.shuffled(random)
            Fixture(sourceRouter = router, queueSession = { session }, shuffleRandom = Random(42), batchTracks = true).use { fixture ->
                val initial = (0 until 30).map(provider::track)
                val queue = SourceContentQueue(router, PlaylistRef(provider.id, "opaque:playlist"), "Playlist", initial,
                    startIndex = 4, nextCursor = "30", mediaItemFactory = fixture::sourceItem)
                onMain {
                    fixture.manager.setCloudShuffleEnabled(true)
                    fixture.manager.playQueue(queue, startInShuffleMode = true, playWhenReady = false)
                }
                runBlocking { withTimeout(5_000) { session.requested.await() } }
                await("initial full local metadata window") { fixture.metadataWindowReady() }
                val before = onMain {
                    fixture.player.seekTo(4, 350L)
                    fixture.items() to fixture.sourceOrder()
                }
                val current = requireNotNull(before.first[4].queueEntryId)
                val unavailable = if (extras) setOf(canonical[7]) else emptySet()
                val ready = before.first.filterNot(::isPlaceholderMediaItem).mapNotNull { TrackRefCodec.decode(it.mediaId) }.toSet()
                val unresolved = canonical.filterNot { it in ready || it in unavailable }
                val sourceOrder = if (extras) unresolved.take(5) else unresolved + canonical.filterNot { it in unresolved }
                val pending = sourceOrder.filterNot { it == canonical[4] || it in unavailable }.toMutableList()
                canonical.filterNot { it == canonical[4] || it in unavailable || it in pending }.shuffled(random).forEach {
                    pending.add(random.nextInt(pending.size + 1), it)
                }
                val expected = listOf(canonical[4]) + pending
                val missing = expected.take(13).filterNot { it in ready }
                assertTrue(missing.isNotEmpty())
                val requested = CompletableDeferred<List<TrackRef>>()
                val metadata = CompletableDeferred<Unit>()
                val batchesBefore = provider.catalogBatches.size
                provider.beforeTracks = { refs ->
                    requested.complete(refs)
                    if (!readyTracks) metadata.await()
                }
                try {
                    session.response.complete(SourceShufflePage(sourceOrder,
                        if (readyTracks) (0 until 90).map(provider::track) else emptyList(), unavailable))
                    if (!readyTracks) {
                        val actualRequest = runBlocking { withTimeout(5_000) { requested.await() } }
                        assertEquals(missing, actualRequest)
                        onMain {
                            assertEquals(before.second, fixture.sourceOrder())
                            assertTrue(fixture.player.shuffleModeEnabled)
                            assertEquals(current, fixture.player.currentMediaItem?.queueEntryId)
                            assertEquals(350L, fixture.player.currentPosition)
                            assertFalse(fixture.player.playWhenReady)
                        }
                        metadata.complete(Unit)
                    }
                    await("final staged source order") { fixture.sourceOrder() == expected }
                    onMain {
                        assertTrue(fixture.player.playbackOrderIndices().take(13).all {
                            !isPlaceholderMediaItem(fixture.player.getMediaItemAt(it))
                        })
                        assertEquals(current, fixture.player.currentMediaItem?.queueEntryId)
                        assertEquals(350L, fixture.player.currentPosition)
                        assertFalse(fixture.player.playWhenReady)
                        val retained = before.first.filterNot { TrackRefCodec.decode(it.mediaId) in unavailable }
                            .associate { it.mediaId to it.queueEntryId }
                        assertEquals(retained, fixture.items().associate { it.mediaId to it.queueEntryId })
                    }
                    if (readyTracks) assertEquals(batchesBefore, provider.catalogBatches.size)
                    assertTrue(provider.requests.isEmpty())
                } finally {
                    metadata.complete(Unit)
                }
            }
        }
    }

    @Test
    fun stagedSourceMetadataRebasesCurrentPlaybackAndRejectsStaleCommits() {
        for (change in listOf("advance", "cancel", "account", "network", "auth")) {
            val provider = ShuffleProvider(metadataArtwork = true)
            val router = SourceRouter(ProviderRegistry(listOf(provider)))
            val canonical = (0 until 90).map { provider.track(it).ref }
            val session = PendingCloudShuffle(provider.id, canonicalIds = canonical)
            Fixture(sourceRouter = router, queueSession = { session }, shuffleRandom = Random(42), batchTracks = true).use { fixture ->
                val initial = (0 until 30).map(provider::track)
                val queue = SourceContentQueue(router, PlaylistRef(provider.id, "opaque:playlist"), "Playlist", initial,
                    startIndex = 4, nextCursor = "30", mediaItemFactory = fixture::sourceItem)
                onMain {
                    fixture.manager.setCloudShuffleEnabled(true)
                    fixture.manager.playQueue(queue, startInShuffleMode = true, playWhenReady = false)
                }
                runBlocking { withTimeout(5_000) { session.requested.await() } }
                await("initial metadata before staged $change") { fixture.metadataWindowReady() }
                val before = onMain {
                    fixture.player.seekTo(4, 350L)
                    fixture.items() to fixture.sourceOrder()
                }
                val ready = before.first.filterNot(::isPlaceholderMediaItem).mapNotNull { TrackRefCodec.decode(it.mediaId) }.toSet()
                val sourceOrder = canonical.filterNot { it in ready } + canonical.filter { it in ready }
                val requested = CompletableDeferred<Unit>()
                val metadata = CompletableDeferred<Unit>()
                val returned = CompletableDeferred<Unit>()
                provider.beforeTracks = {
                    requested.complete(Unit)
                    withContext(NonCancellable) { metadata.await() }
                    returned.complete(Unit)
                    when (change) {
                        "network" -> throw SourceException(SourceErrorCode.NETWORK)
                        "auth" -> throw SourceException(SourceErrorCode.AUTH_EXPIRED)
                    }
                }
                try {
                    session.response.complete(SourceShufflePage(sourceOrder, emptyList(), emptySet()))
                    runBlocking { withTimeout(5_000) { requested.await() } }
                    onMain { assertEquals(before.second, fixture.sourceOrder()) }
                    val current = onMain {
                        when (change) {
                            "advance" -> repeat(3) { fixture.player.seekToNextMediaItem() }
                            "cancel" -> fixture.manager.setShuffleModeEnabled(false)
                            "account" -> provider.accountRevision.value = "account-b"
                        }
                        fixture.player.seekTo(fixture.player.currentMediaItemIndex, 350L)
                        requireNotNull(fixture.player.currentMediaItem?.queueEntryId)
                    }
                    if (change == "cancel") await("cancelled metadata staging") { !fixture.player.shuffleModeEnabled }
                    if (change == "account") await("invalid metadata staging owner") { fixture.manager.playlistSource == null }
                    metadata.complete(Unit)
                    runBlocking { withTimeout(5_000) { returned.await() } }
                    if (change == "advance" || change == "network") {
                        val prefix = before.second.take(if (change == "advance") 4 else 1)
                        val expected = prefix + sourceOrder.filterNot { it in prefix }
                        await("rebased or network fallback final order") { fixture.sourceOrder() == expected }
                        if (change == "advance") onMain {
                            val order = fixture.player.playbackOrderIndices()
                            val position = order.indexOf(fixture.player.currentMediaItemIndex)
                            assertTrue(order.drop(position).take(13).all { !isPlaceholderMediaItem(fixture.player.getMediaItemAt(it)) })
                        }
                    } else {
                        runBlocking { delay(50) }
                        onMain {
                            assertEquals(before.first.map { it.mediaId }, fixture.items().map { it.mediaId })
                            assertEquals(before.second, fixture.player.playbackOrderIndices(true).map {
                                requireNotNull(TrackRefCodec.decode(fixture.player.getMediaItemAt(it).mediaId))
                            })
                        }
                    }
                    onMain {
                        assertEquals(current, fixture.player.currentMediaItem?.queueEntryId)
                        assertEquals(350L, fixture.player.currentPosition)
                        assertFalse(fixture.player.playWhenReady)
                    }
                    assertTrue(provider.requests.isEmpty())
                } finally {
                    metadata.complete(Unit)
                }
            }
        }
    }

    @Test
    fun localBatchShuffleKeepsTheOldOrderUntilItsGlobalPreviewIsReadyAndIsolatesMissingRows() {
        for (change in listOf("local", "cloud", "partial", "cancel", "account", "network", "auth", "legacy")) {
            val provider = ShuffleProvider(metadataArtwork = true)
            val router = SourceRouter(ProviderRegistry(listOf(provider)))
            val canonical = (0 until 90).map { provider.track(it).ref }
            val session = PendingCloudShuffle(provider.id, canonicalIds = canonical)
            Fixture(sourceRouter = router, queueSession = { session }, shuffleRandom = Random(42),
                batchTracks = change != "legacy").use { fixture ->
                val initial = (0 until 30).map(provider::track)
                val queue = SourceContentQueue(router, PlaylistRef(provider.id, "opaque:playlist"), "Playlist", initial,
                    startIndex = 4, nextCursor = "30", mediaItemFactory = fixture::sourceItem)
                onMain {
                    fixture.manager.setCloudShuffleEnabled(change == "cloud")
                    fixture.manager.playQueue(queue, playWhenReady = false)
                }
                await("sequential source before local batch $change") { fixture.player.mediaItemCount == 30 }
                val before = onMain {
                    fixture.player.seekTo(4, 350L)
                    fixture.items() to fixture.sourceOrder()
                }
                val uniform = listOf(canonical[4]) + canonical.filterNot { it == canonical[4] }.shuffled(Random(42))
                val requested = CompletableDeferred<List<TrackRef>>()
                val metadata = CompletableDeferred<Unit>()
                val returned = CompletableDeferred<Unit>()
                provider.beforeTracks = { refs ->
                    requested.complete(refs)
                    withContext(NonCancellable) { metadata.await() }
                    returned.complete(Unit)
                    when (change) {
                        "network" -> throw SourceException(SourceErrorCode.NETWORK)
                        "auth" -> throw SourceException(SourceErrorCode.AUTH_EXPIRED)
                    }
                }
                try {
                    onMain { fixture.manager.setShuffleModeEnabled(true) }
                    val batch = runBlocking { withTimeout(5_000) { requested.await() } }
                    if (change == "legacy") onMain {
                        assertTrue(fixture.player.shuffleModeEnabled)
                        assertEquals(uniform, fixture.sourceOrder())
                    } else onMain {
                        assertEquals(before.second, fixture.sourceOrder())
                        assertEquals(30, fixture.player.mediaItemCount)
                        assertFalse(fixture.player.shuffleModeEnabled)
                        assertFalse(session.requested.isCompleted)
                        assertEquals(uniform.take(13).filterNot { it in initial.map(Track::ref) }, batch)
                    }
                    if (change == "partial") provider.omittedTracks = setOf(batch.first())
                    when (change) {
                        "cancel" -> onMain { fixture.manager.setShuffleModeEnabled(false) }
                        "account" -> provider.accountRevision.value = "account-b"
                    }
                    if (change == "cancel") await("cancelled local batch intent") { !fixture.manager.shuffleModeIntent.value }
                    if (change == "account") await("invalid local batch owner") { fixture.manager.playlistSource == null }
                    metadata.complete(Unit)
                    runBlocking { withTimeout(5_000) { returned.await() } }
                    if (change in listOf("local", "cloud", "partial", "legacy")) {
                        val expected = uniform.filterNot { it in provider.omittedTracks } + uniform.filter { it in provider.omittedTracks }
                        await("ready local global permutation $change") { fixture.player.shuffleModeEnabled && fixture.sourceOrder() == expected }
                        await("ready local first screen $change") { fixture.player.playbackOrderIndices().take(13).all {
                            !isPlaceholderMediaItem(fixture.player.getMediaItemAt(it))
                        } }
                        onMain {
                            assertEquals(canonical.toSet(), fixture.sourceOrder().toSet())
                            assertEquals(canonical, fixture.items().map { TrackRefCodec.decode(it.mediaId) })
                            assertEquals(before.first[4].queueEntryId, fixture.player.currentMediaItem?.queueEntryId)
                            assertEquals(350L, fixture.player.currentPosition)
                            assertFalse(fixture.player.playWhenReady)
                        }
                        if (change == "cloud") runBlocking { withTimeout(5_000) { session.requested.await() } }
                    } else {
                        runBlocking { delay(50) }
                        onMain {
                            assertEquals(before.first.map { it.queueEntryId }, fixture.items().map { it.queueEntryId })
                            assertEquals(before.second, fixture.sourceOrder())
                            assertEquals(350L, fixture.player.currentPosition)
                            assertFalse(fixture.player.playWhenReady)
                        }
                    }
                    assertTrue(provider.requests.isEmpty())
                } finally {
                    metadata.complete(Unit)
                    session.response.complete(SourceShufflePage(emptyList(), emptyList(), emptySet()))
                }
            }
        }
    }

    @Test
    fun initialBatchRandomUsesOneFrozenGlobalPermutationBeforePreparingTheSelectedSong() {
        for (change in listOf("complete", "partial", "randomMissing", "boundedMissing", "explicitMissing", "cancel", "account", "network", "auth", "legacy")) {
            val provider = ShuffleProvider(metadataArtwork = true)
            val router = SourceRouter(ProviderRegistry(listOf(provider)))
            val canonical = (0 until 90).map { provider.track(it).ref }
            val session = PendingCloudShuffle(provider.id, canonicalIds = canonical)
            val randomStart = change != "explicitMissing"
            val seed = (0..100).first { Random(it).nextInt(90) >= 30 }
            val random = Random(seed)
            val selected = if (randomStart) random.nextInt(90) else 4
            val uniform = listOf(selected) + canonical.indices.filterNot { it == selected }.shuffled(random)
            val requested = CompletableDeferred<List<TrackRef>>()
            val metadata = CompletableDeferred<Unit>()
            val returned = CompletableDeferred<Unit>()
            provider.beforeTracks = { refs ->
                requested.complete(refs)
                withContext(NonCancellable) { metadata.await() }
                returned.complete(Unit)
                when (change) {
                    "network" -> throw SourceException(SourceErrorCode.NETWORK)
                    "auth" -> throw SourceException(SourceErrorCode.AUTH_EXPIRED)
                }
            }
            Fixture(sourceRouter = router, queueSession = { session }, shuffleRandom = Random(seed),
                batchTracks = change != "legacy").use { fixture ->
                val before = onMain {
                    fixture.player.seekTo(0, 350L)
                    fixture.player.playWhenReady = false
                    fixture.items() to fixture.sourceCreations.size
                }
                val farCached = canonical.take(30).first { canonical.indexOf(it) !in uniform.take(30) }
                val queue = SourceContentQueue(router, PlaylistRef(provider.id, "opaque:playlist"), "Playlist",
                    (0 until 30).map(provider::track), startIndex = 4, nextCursor = "30", mediaItemFactory = {
                        if (change == "boundedMissing" && it.ref == farCached) fixture.sourceItem(it) else null
                    })
                try {
                    onMain {
                        fixture.manager.setCloudShuffleEnabled(false)
                        fixture.manager.playQueue(queue, startInShuffleMode = true, randomStart = randomStart, playWhenReady = false)
                    }
                    val batch = runBlocking { withTimeout(5_000) { requested.await() } }
                    assertEquals(if (change == "legacy") listOf(canonical[selected]) else uniform.take(13).map(canonical::get), batch)
                    onMain {
                        assertEquals(before.first.map { it.queueEntryId }, fixture.items().map { it.queueEntryId })
                        assertEquals(before.second, fixture.sourceCreations.size)
                    }
                    when (change) {
                        "partial" -> provider.omittedTracks = setOf(batch[1])
                        "randomMissing", "explicitMissing" -> provider.omittedTracks = setOf(batch.first())
                        "boundedMissing" -> provider.omittedTracks = canonical.toSet()
                        "cancel" -> onMain { fixture.manager.setShuffleModeEnabled(false) }
                        "account" -> provider.accountRevision.value = "account-b"
                    }
                    if (change == "cancel") await("cancelled initial preview") { !fixture.manager.shuffleModeIntent.value }
                    metadata.complete(Unit)
                    runBlocking { withTimeout(5_000) { returned.await() } }
                    if (change in listOf("complete", "partial", "randomMissing", "legacy")) {
                        val original = uniform.map(canonical::get)
                        val expected = original.filterNot { it in provider.omittedTracks } + original.filter { it in provider.omittedTracks }
                        await("committed frozen initial random $change") { fixture.player.mediaItemCount == 90 && fixture.sourceOrder() == expected }
                        onMain {
                            assertEquals(expected.first(), TrackRefCodec.decode(requireNotNull(fixture.player.currentMediaItem).mediaId))
                            assertEquals(canonical.toSet(), fixture.sourceOrder().toSet())
                            if (change != "legacy") assertTrue(fixture.player.playbackOrderIndices().take(13).all {
                                !isPlaceholderMediaItem(fixture.player.getMediaItemAt(it))
                            })
                            assertFalse(fixture.player.playWhenReady)
                            assertEquals(0L, fixture.player.currentPosition)
                        }
                    } else {
                        runBlocking { delay(50) }
                        onMain {
                            assertEquals(before.first.map { it.queueEntryId }, fixture.items().map { it.queueEntryId })
                            assertEquals(before.second, fixture.sourceCreations.size)
                            assertEquals(350L, fixture.player.currentPosition)
                            assertFalse(fixture.player.playWhenReady)
                        }
                        if (change == "boundedMissing") {
                            assertEquals(2, provider.catalogBatches.size)
                            assertTrue(provider.catalogBatches.sumOf { it.size } <= 30)
                        }
                    }
                    assertTrue(provider.requests.isEmpty())
                } finally {
                    metadata.complete(Unit)
                }
            }
        }
    }

    @Test
    fun localShuffleUsesCompleteSourceIdentitiesWithoutFetchingEveryMetadataPage() {
        for (library in listOf(false, true)) for (initialShuffle in listOf(false, true)) {
            val provider = ShuffleProvider()
            val router = SourceRouter(ProviderRegistry(listOf(provider)))
            val identityRequests = CopyOnWriteArrayList<Pair<String, LibraryCollection?>>()
            val session = object : QueueSourceSession {
                override val providerId = provider.id
                override suspend fun ensureCurrent() = Unit
                override suspend fun scenes(): List<PrivateFmScene> = error("Unexpected scenes request")
                override suspend fun radio(parameters: Map<String, String>, similar: Boolean): List<Track> = error("Unexpected radio request")
                override suspend fun shuffle(playlistId: String, songId: String, algorithm: String,
                    collection: LibraryCollection?): SourceShufflePage = error("Local shuffle must not request an algorithm")
                override suspend fun canonicalShuffleIds(playlistId: String, collection: LibraryCollection?): List<TrackRef> {
                    identityRequests += playlistId to collection
                    return (0 until 90).map { provider.track(it).ref }
                }
            }
            val seed = (0..100).first { Random(it).nextInt(90) >= 30 }
            Fixture(sourceRouter = router, queueSession = { session }, shuffleRandom = Random(seed)).use { fixture ->
                val initial = (0 until 30).map(provider::track)
                val queue = if (library) SourceContentQueue.forLibrary(router, provider.id,
                    initialTracks = initial, startIndex = 4, nextCursor = "30", mediaItemFactory = fixture::sourceItem)
                else SourceContentQueue(router, PlaylistRef(provider.id, "opaque:playlist"), "Playlist", initial,
                    startIndex = 4, nextCursor = "30", mediaItemFactory = fixture::sourceItem)
                onMain {
                    fixture.manager.setCloudShuffleEnabled(false)
                    fixture.manager.playQueue(queue, startInShuffleMode = initialShuffle,
                        randomStart = initialShuffle, playWhenReady = false)
                }
                if (!initialShuffle) {
                    await("partial identity source") { fixture.player.mediaItemCount == 30 }
                    onMain { fixture.player.seekTo(4, 350L); fixture.manager.setShuffleModeEnabled(true) }
                }
                await("complete identity shuffle") { fixture.player.mediaItemCount == 90 && fixture.player.shuffleModeEnabled }
                onMain {
                    assertFalse(fixture.player.playWhenReady)
                    if (initialShuffle) assertTrue(fixture.player.currentMediaItemIndex >= 30)
                    else {
                        assertEquals(provider.track(4).ref, fixture.player.currentMediaItem?.let { TrackRefCodec.decode(it.mediaId) })
                        assertEquals(350L, fixture.player.currentPosition)
                    }
                    assertEquals(90, fixture.player.playbackOrderIndices().toSet().size)
                    assertFalse(queue.hasNextPage())
                }
                assertEquals(listOf(if (library) "" to LibraryCollection.LIKED_TRACKS else "opaque:playlist" to null),
                    identityRequests.toList())
                assertTrue(provider.requests.isEmpty())
            }
        }
    }

    @Test
    fun localShuffleLoadsEverythingBeforeReplacingPlaybackAndDistinguishesRandomStartFromClickedTracks() {
        for (randomStart in listOf(false, true)) {
            val provider = ShuffleProvider()
            val router = SourceRouter(ProviderRegistry(listOf(provider)))
            val started = CompletableDeferred<Unit>()
            val respond = CompletableDeferred<Unit>()
            provider.beforePage = {
                started.complete(Unit)
                respond.await()
            }
            val seed = (0..100).first { Random(it).nextInt(90) >= 30 }
            Fixture(sourceRouter = router, shuffleRandom = Random(seed)).use { fixture ->
                val before = onMain {
                    fixture.player.seekTo(3, 400L)
                    fixture.player.playWhenReady = false
                    fixture.items().map(MediaItem::mediaId)
                }
                val queue = SourceContentQueue(router, PlaylistRef(provider.id, "opaque:playlist"), "Playlist",
                    (0 until 30).map(provider::track), startIndex = 4, nextCursor = "30", knownTotalCount = 90,
                    mediaItemFactory = fixture::sourceItem)
                onMain {
                    fixture.manager.setCloudShuffleEnabled(false)
                    fixture.manager.playQueue(queue, startInShuffleMode = true, playWhenReady = false, randomStart = randomStart)
                }
                runBlocking { withTimeout(5_000) { started.await() } }
                onMain {
                    assertEquals(before, fixture.items().map(MediaItem::mediaId))
                    assertEquals(3, fixture.player.currentMediaItemIndex)
                    assertEquals(400L, fixture.player.currentPosition)
                    assertFalse(fixture.player.playWhenReady)
                }
                respond.complete(Unit)
                await("complete local initial queue") { fixture.player.mediaItemCount == 90 }
                assertEquals(listOf(PageRequest(30, 100, "30")), provider.requests.toList())
                onMain {
                    val selected = if (randomStart) Random(seed).nextInt(90) else 4
                    assertEquals(provider.track(selected).ref, fixture.player.currentMediaItem?.let { TrackRefCodec.decode(it.mediaId) })
                    assertTrue(fixture.player.shuffleModeEnabled)
                    assertFalse(fixture.player.playWhenReady)
                }
            }
        }
    }

    @Test
    fun failedLocalShuffleKeepsPlaybackAndFeedsCachedTracksToSequentialPaging() {
        val provider = ShuffleProvider(245)
        provider.beforePage = { if (it.offset == 130) throw SourceException(SourceErrorCode.NETWORK) }
        val router = SourceRouter(ProviderRegistry(listOf(provider)))
        Fixture(sourceRouter = router).use { fixture ->
            val queue = SourceContentQueue(router, PlaylistRef(provider.id, "opaque:playlist"), "Playlist",
                (0 until 30).map(provider::track), startIndex = 4, nextCursor = "30", knownTotalCount = 245,
                mediaItemFactory = fixture::sourceItem)
            onMain {
                fixture.manager.setCloudShuffleEnabled(false)
                fixture.manager.playQueue(queue, playWhenReady = false)
            }
            await("sequential initial queue") { fixture.player.mediaItemCount == 30 }
            val current = onMain {
                fixture.player.seekTo(4, 400L)
                requireNotNull(fixture.player.currentMediaItem?.queueEntryId)
            }
            onMain { fixture.manager.setShuffleModeEnabled(true) }
            await("failed complete load") { queue.state.value is com.ljyh.mei.playback.queue.Queue.QueueState.Error }
            onMain {
                fixture.manager.setShuffleModeEnabled(false)
                assertEquals(30, fixture.player.mediaItemCount)
                assertEquals(current, fixture.player.currentMediaItem?.queueEntryId)
                assertEquals(400L, fixture.player.currentPosition)
                assertFalse(fixture.player.playWhenReady)
                assertFalse(fixture.player.shuffleModeEnabled)
                fixture.player.seekToDefaultPosition(27)
            }
            await("cached sequential tail") { fixture.player.mediaItemCount == 60 }
            onMain { assertEquals((0 until 60).map { TrackRefCodec.mediaId(provider.track(it).ref) }, fixture.items().map(MediaItem::mediaId)) }
            assertEquals(listOf(PageRequest(30, 100, "30"), PageRequest(130, 100, "130")), provider.requests.toList())
        }
    }

    @Test
    fun localShuffleCancellationAndImmediateRetryWaitForTheRetiredPageOwner() {
        val provider = ShuffleProvider(245)
        val started = CompletableDeferred<Unit>()
        val respond = CompletableDeferred<Unit>()
        var blocked = false
        provider.beforePage = {
            if (it.offset == 130 && !blocked) {
                blocked = true
                withContext(NonCancellable) { started.complete(Unit); respond.await() }
            }
        }
        val router = SourceRouter(ProviderRegistry(listOf(provider)))
        Fixture(sourceRouter = router).use { fixture ->
            val queue = SourceContentQueue(router, PlaylistRef(provider.id, "opaque:playlist"), "Playlist",
                (0 until 30).map(provider::track), startIndex = 4, nextCursor = "30", knownTotalCount = 245,
                mediaItemFactory = fixture::sourceItem)
            onMain {
                fixture.manager.setCloudShuffleEnabled(false)
                fixture.manager.playQueue(queue, playWhenReady = false)
            }
            await("retry initial queue") { fixture.player.mediaItemCount == 30 }
            val current = onMain {
                fixture.player.seekTo(4, 400L)
                requireNotNull(fixture.player.currentMediaItem?.queueEntryId)
            }
            onMain { fixture.manager.setShuffleModeEnabled(true) }
            runBlocking { withTimeout(5_000) { started.await() } }
            onMain { assertTrue(fixture.manager.shuffleModeIntent.value) }
            onMain { fixture.manager.setShuffleModeEnabled(false) }
            await("cancelled local shuffle intent") { !fixture.manager.shuffleModeIntent.value }
            onMain { fixture.manager.setShuffleModeEnabled(true) }
            await("retried local shuffle intent") { fixture.manager.shuffleModeIntent.value }
            onMain {
                assertEquals(30, fixture.player.mediaItemCount)
                assertFalse(fixture.player.shuffleModeEnabled)
            }
            respond.complete(Unit)
            await("complete retried local shuffle") { fixture.player.mediaItemCount == 245 && fixture.player.shuffleModeEnabled }
            onMain {
                assertEquals(current, fixture.player.currentMediaItem?.queueEntryId)
                assertEquals(400L, fixture.player.currentPosition)
                assertFalse(fixture.player.playWhenReady)
                assertEquals((0 until 245).map { TrackRefCodec.mediaId(provider.track(it).ref) }, fixture.items().map(MediaItem::mediaId))
            }
            assertEquals(listOf(30, 130, 130, 230), provider.requests.map { it.offset })
        }
    }

    @Test
    fun failedOrCancelledInitialShuffleKeepsThePreviousPagedQueueOwnerAvailableForCompleteShuffle() {
        for (cancel in listOf(false, true)) {
            val provider = ShuffleProvider()
            val router = SourceRouter(ProviderRegistry(listOf(provider)))
            val started = CompletableDeferred<Unit>()
            val respond = CompletableDeferred<Unit>()
            Fixture(sourceRouter = router).use { fixture ->
                val initial = (0 until 30).map(provider::track)
                fun queue(id: String) = SourceContentQueue(router, PlaylistRef(provider.id, id), id, initial,
                    startIndex = 4, nextCursor = "30", knownTotalCount = 90, mediaItemFactory = fixture::sourceItem)
                val previous = queue("previous")
                val replacement = queue("replacement")
                onMain {
                    fixture.manager.setCloudShuffleEnabled(false)
                    fixture.manager.playQueue(previous, playWhenReady = false)
                }
                await("previous paged queue") { fixture.player.mediaItemCount == 30 }
                val current = onMain {
                    fixture.player.seekTo(4, 400L)
                    requireNotNull(fixture.player.currentMediaItem?.queueEntryId)
                }
                provider.beforePage = {
                    withContext(NonCancellable) { started.complete(Unit); respond.await() }
                    if (!cancel) throw SourceException(SourceErrorCode.NETWORK)
                }
                onMain { fixture.manager.playQueue(replacement, startInShuffleMode = true, playWhenReady = false) }
                runBlocking { withTimeout(5_000) { started.await() } }
                onMain { assertEquals(previous.playlistSource, fixture.manager.playlistSource) }
                if (cancel) {
                    onMain { fixture.manager.setShuffleModeEnabled(false) }
                    await("cancelled replacement intent") { !fixture.manager.shuffleModeIntent.value }
                }
                respond.complete(Unit)
                if (!cancel) {
                    await("failed replacement") { replacement.state.value is com.ljyh.mei.playback.queue.Queue.QueueState.Error }
                    await("failed replacement intent") { !fixture.manager.shuffleModeIntent.value }
                }
                provider.beforePage = {}
                onMain {
                    assertEquals(previous.playlistSource, fixture.manager.playlistSource)
                    assertEquals(30, fixture.player.mediaItemCount)
                    assertEquals(current, fixture.player.currentMediaItem?.queueEntryId)
                    assertEquals(400L, fixture.player.currentPosition)
                    assertFalse(fixture.player.playWhenReady)
                    assertFalse(fixture.player.shuffleModeEnabled)
                    fixture.manager.setShuffleModeEnabled(true)
                }
                await("complete previous queue after replacement failure") {
                    fixture.player.mediaItemCount == 90 && fixture.player.shuffleModeEnabled
                }
                onMain {
                    assertEquals(previous.playlistSource, fixture.manager.playlistSource)
                    assertEquals(current, fixture.player.currentMediaItem?.queueEntryId)
                    assertEquals(400L, fixture.player.currentPosition)
                    assertFalse(fixture.player.playWhenReady)
                }
                assertEquals(listOf(30, 30), provider.requests.map { it.offset })
            }
        }
    }

    @Test
    fun initialAndEnabledCloudShuffleCommitOnlyAfterTheCompleteResponseAndKeepOnlyTheCurrentLinearEntry() {
        for (library in listOf(false, true)) for (initialShuffle in listOf(false, true)) for (result in listOf(
            "shuffled", "canonical", "empty", "unavailable",
        )) {
            val provider = ShuffleProvider()
            val fallbackStarted = CompletableDeferred<Unit>()
            val fallbackResponse = CompletableDeferred<Unit>()
            if (result == "unavailable") provider.beforePage = {
                fallbackStarted.complete(Unit)
                fallbackResponse.await()
            }
            val router = SourceRouter(ProviderRegistry(listOf(provider)))
            val session = PendingCloudShuffle(provider.id)
            Fixture(sourceRouter = router, queueSession = { session }, shuffleRandom = Random(42)).use { fixture ->
                val initial = (0 until 30).map(provider::track)
                val queue = if (library) SourceContentQueue.forLibrary(router, provider.id, title = "Liked",
                    initialTracks = initial, startIndex = 14, nextCursor = "30", mediaItemFactory = fixture::sourceItem)
                else SourceContentQueue(router, PlaylistRef(provider.id, "playlist"), "Playlist", initial,
                    startIndex = 14, nextCursor = "30", knownTotalCount = 90, mediaItemFactory = fixture::sourceItem)
                onMain {
                    fixture.manager.setCloudShuffleEnabled(true)
                    fixture.manager.playQueue(queue, startInShuffleMode = initialShuffle, playWhenReady = false)
                }
                await("linear cloud queue") { fixture.player.mediaItemCount == 30 }
                val current = onMain {
                    fixture.player.seekTo(14, 350L)
                    requireNotNull(fixture.player.currentMediaItem?.queueEntryId)
                }
                if (!initialShuffle) onMain { fixture.manager.setShuffleModeEnabled(true) }
                runBlocking { withTimeout(5_000) { session.requested.await() } }
                onMain {
                    assertTrue(fixture.manager.shuffleModeIntent.value)
                    assertFalse(fixture.player.shuffleModeEnabled)
                    assertEquals((0 until 30).toList(), fixture.player.playbackOrderIndices())
                    assertEquals(current, fixture.player.currentMediaItem?.queueEntryId)
                    assertEquals(350L, fixture.player.currentPosition)
                    assertFalse(fixture.player.playWhenReady)
                }
                val tracks = (0 until 90).map(provider::track)
                val serverOrder = when (result) {
                    "shuffled" -> tracks.reversed().map { it.ref }
                    "canonical" -> tracks.map { it.ref }
                    else -> emptyList()
                }
                if (result == "unavailable") {
                    session.response.completeExceptionally(SourceException(SourceErrorCode.UNAVAILABLE))
                    runBlocking { withTimeout(5_000) { fallbackStarted.await() } }
                    onMain {
                        assertTrue(fixture.manager.shuffleModeIntent.value)
                        assertFalse(fixture.player.shuffleModeEnabled)
                        assertEquals(30, fixture.player.mediaItemCount)
                        assertEquals(current, fixture.player.currentMediaItem?.queueEntryId)
                        assertEquals(350L, fixture.player.currentPosition)
                        assertFalse(fixture.player.playWhenReady)
                    }
                    fallbackResponse.complete(Unit)
                } else session.response.complete(SourceShufflePage(serverOrder,
                    if (serverOrder.isEmpty()) emptyList() else initial, emptySet()))
                await("committed complete cloud or fallback queue") {
                    fixture.player.mediaItemCount == 90 && fixture.player.shuffleModeEnabled
                }
                onMain {
                    val order = fixture.player.playbackOrderIndices()
                    assertEquals(14, order.first())
                    assertEquals(current, fixture.player.currentMediaItem?.queueEntryId)
                    assertEquals(350L, fixture.player.currentPosition)
                    assertFalse(fixture.player.playWhenReady)
                    assertEquals((0 until 90).toSet(), order.toSet())
                    val actual = order.map { requireNotNull(TrackRefCodec.decode(fixture.player.getMediaItemAt(it).mediaId)) }
                    val currentRef = provider.track(14).ref
                    if (serverOrder.isNotEmpty()) assertEquals(listOf(currentRef) + serverOrder.filterNot { it == currentRef }, actual)
                    else assertFalse(actual == listOf(currentRef) + tracks.map { it.ref }.filterNot { it == currentRef })
                }
                assertEquals(if (serverOrder.isEmpty()) listOf(PageRequest(30, 100, "30")) else emptyList<PageRequest>(),
                    provider.requests.toList())
            }
        }
    }

    @Test
    fun cloudAndFallbackKeepExistingHistoryWithoutProtectingNewlyAppendedEntries() {
        for (result in listOf("shuffled", "canonical", "empty", "unavailable")) {
            val provider = ShuffleProvider()
            val router = SourceRouter(ProviderRegistry(listOf(provider)))
            val session = PendingCloudShuffle(provider.id)
            Fixture(sourceRouter = router, queueSession = { session }, shuffleRandom = Random(42)).use { fixture ->
                val initial = (0 until 30).map(provider::track)
                val queue = SourceContentQueue(router, PlaylistRef(provider.id, "playlist"), "Playlist", initial,
                    startIndex = 14, nextCursor = "30", knownTotalCount = 90, mediaItemFactory = fixture::sourceItem)
                onMain {
                    fixture.manager.setCloudShuffleEnabled(false)
                    fixture.manager.playQueue(queue, playWhenReady = false)
                }
                await("history source queue") { fixture.player.mediaItemCount == 30 }
                val history = listOf(2, 8, 14)
                val oldOrder = history + (0 until 30).filterNot { it in history }
                val historyKeys = onMain {
                    fixture.player.setPlaybackOrder(oldOrder)
                    fixture.player.shuffleModeEnabled = true
                    fixture.player.seekTo(14, 350L)
                    history.map { requireNotNull(fixture.player.getMediaItemAt(it).queueEntryId) }
                }
                onMain { fixture.manager.setCloudShuffleEnabled(true) }
                runBlocking { withTimeout(5_000) { session.requested.await() } }
                onMain {
                    assertEquals(oldOrder, fixture.player.playbackOrderIndices())
                    assertTrue(fixture.player.shuffleModeEnabled)
                }
                val tracks = (0 until 90).map(provider::track)
                val serverOrder = when (result) {
                    "shuffled" -> tracks.reversed().map { it.ref }
                    "canonical" -> tracks.map { it.ref }
                    else -> emptyList()
                }
                if (result == "unavailable") session.response.completeExceptionally(SourceException(SourceErrorCode.UNAVAILABLE))
                else session.response.complete(SourceShufflePage(serverOrder, if (serverOrder.isEmpty()) emptyList() else initial, emptySet()))
                await("complete shuffle with preserved history") { fixture.player.mediaItemCount == 90 }
                onMain {
                    val order = fixture.player.playbackOrderIndices()
                    val currentPosition = order.indexOf(fixture.player.currentMediaItemIndex)
                    assertEquals(2, currentPosition)
                    assertEquals(historyKeys, order.take(currentPosition + 1).map { fixture.player.getMediaItemAt(it).queueEntryId })
                    assertEquals((30 until 90).toSet(), order.drop(currentPosition + 1).filter { it >= 30 }.toSet())
                    assertEquals(350L, fixture.player.currentPosition)
                    assertFalse(fixture.player.playWhenReady)
                    if (serverOrder.isNotEmpty()) {
                        val refs = order.map { requireNotNull(TrackRefCodec.decode(fixture.player.getMediaItemAt(it).mediaId)) }
                        val protected = history.map { provider.track(it).ref }
                        assertEquals(protected + serverOrder.filterNot { it in protected }, refs)
                    }
                }
            }
        }
    }

    @Test
    fun pendingCloudShuffleDoesNotApplyLateResultsAfterCancellationAccountChangeOrExternalDisable() {
        for (stop in listOf("cancel", "account", "external")) {
            val provider = ShuffleProvider()
            val router = SourceRouter(ProviderRegistry(listOf(provider)))
            val session = PendingCloudShuffle(provider.id, allowLateResponse = true)
            Fixture(sourceRouter = router, queueSession = { session }).use { fixture ->
                try {
                    val queue = SourceContentQueue(router, PlaylistRef(provider.id, "playlist"), "Playlist",
                        (0 until 30).map(provider::track), startIndex = 14, nextCursor = "30", knownTotalCount = 90,
                        mediaItemFactory = fixture::sourceItem)
                    onMain {
                        fixture.manager.setCloudShuffleEnabled(stop != "external")
                        fixture.manager.playQueue(queue, startInShuffleMode = stop != "external", playWhenReady = false)
                    }
                    await("pending cancellable cloud queue") { fixture.player.mediaItemCount == 30 }
                    val current = onMain {
                        fixture.player.seekTo(14, 350L)
                        requireNotNull(fixture.player.currentMediaItem?.queueEntryId)
                    }
                    if (stop == "external") {
                        onMain {
                            fixture.player.setPlaybackOrder(listOf(14) + (0 until 30).filter { it != 14 })
                            fixture.player.shuffleModeEnabled = true
                            fixture.manager.setCloudShuffleEnabled(true)
                        }
                    }
                    runBlocking { withTimeout(5_000) { session.requested.await() } }
                    when (stop) {
                        "cancel" -> onMain { fixture.manager.setShuffleModeEnabled(false) }
                        "account" -> provider.accountRevision.value = "account-b"
                        else -> onMain { fixture.player.shuffleModeEnabled = false }
                    }
                    await("stopped cloud intent") { !fixture.manager.shuffleModeIntent.value && !fixture.player.shuffleModeEnabled }
                    val tracks = (0 until 90).map(provider::track)
                    session.response.complete(SourceShufflePage(tracks.reversed().map { it.ref }, tracks, emptySet()))
                    runBlocking { withTimeout(5_000) { session.returned.await() } }
                    onMain {
                        assertEquals(30, fixture.player.mediaItemCount)
                        assertFalse(fixture.player.shuffleModeEnabled)
                        assertFalse(fixture.manager.shuffleModeIntent.value)
                        assertEquals(current, fixture.player.currentMediaItem?.queueEntryId)
                        assertEquals(350L, fixture.player.currentPosition)
                        assertFalse(fixture.player.playWhenReady)
                        if (stop == "account") assertEquals(null, fixture.manager.playlistSource)
                    }
                } finally {
                    session.response.complete(SourceShufflePage(emptyList(), emptyList(), emptySet()))
                }
            }
        }
    }

    @Test
    fun anInFlightCanonicalPageDoesNotBecomeProtectedHistoryDuringCloudOrFallback() {
        for (result in listOf("cloud", "empty", "unavailable")) for (selectNewEntry in listOf(false, true)) {
            val provider = ShuffleProvider()
            val router = SourceRouter(ProviderRegistry(listOf(provider)))
            val session = PendingCloudShuffle(provider.id)
            val pageStarted = CompletableDeferred<Unit>()
            val pageResponse = CompletableDeferred<Unit>()
            provider.beforePage = { page ->
                if (page.offset == 30) { pageStarted.complete(Unit); pageResponse.await() }
            }
            Fixture(sourceRouter = router, queueSession = { session }, shuffleRandom = Random(42)).use { fixture ->
                val initial = (0 until 30).map(provider::track)
                val queue = SourceContentQueue(router, PlaylistRef(provider.id, "playlist"), "Playlist", initial,
                    nextCursor = "30", knownTotalCount = 90, mediaItemFactory = fixture::sourceItem)
                onMain {
                    fixture.manager.setCloudShuffleEnabled(false)
                    fixture.manager.playQueue(queue, playWhenReady = false)
                }
                await("partial shuffled source") { fixture.player.mediaItemCount == 30 }
                val history = (0 until 28).filterNot { it == 14 } + 14
                val historyKeys = onMain {
                    fixture.player.setPlaybackOrder(history + listOf(28, 29))
                    fixture.player.shuffleModeEnabled = true
                    fixture.player.seekTo(14, 350L)
                    history.map { requireNotNull(fixture.player.getMediaItemAt(it).queueEntryId) }
                }
                runBlocking { withTimeout(5_000) { pageStarted.await() } }
                onMain { fixture.manager.setCloudShuffleEnabled(true) }
                runBlocking { withTimeout(5_000) { session.requested.await() } }
                pageResponse.complete(Unit)
                await("in-flight canonical page completion") { fixture.player.mediaItemCount == 60 }
                val expectedHistory = onMain {
                    if (selectNewEntry) {
                        fixture.player.seekTo(35, 350L)
                        historyKeys + requireNotNull(fixture.player.currentMediaItem?.queueEntryId)
                    } else historyKeys
                }
                val tracks = (0 until 90).map(provider::track)
                val serverOrder = if (result == "cloud") tracks.reversed().map { it.ref } else emptyList()
                if (result == "unavailable") session.response.completeExceptionally(SourceException(SourceErrorCode.UNAVAILABLE))
                else session.response.complete(SourceShufflePage(serverOrder, if (serverOrder.isEmpty()) emptyList() else initial, emptySet()))
                await("complete cloud after in-flight page") { fixture.player.mediaItemCount == 90 }
                onMain {
                    val order = fixture.player.playbackOrderIndices()
                    val currentPosition = order.indexOf(fixture.player.currentMediaItemIndex)
                    assertEquals(expectedHistory.size - 1, currentPosition)
                    assertEquals(expectedHistory, order.take(currentPosition + 1).map { fixture.player.getMediaItemAt(it).queueEntryId })
                    assertEquals(350L, fixture.player.currentPosition)
                    assertFalse(fixture.player.playWhenReady)
                    if (serverOrder.isNotEmpty()) {
                        val protected = (history + if (selectNewEntry) listOf(35) else emptyList()).map { provider.track(it).ref }
                        val actual = order.map { requireNotNull(TrackRefCodec.decode(fixture.player.getMediaItemAt(it).mediaId)) }
                        assertEquals(protected + serverOrder.filterNot { it in protected }, actual)
                    }
                }
                assertEquals(if (serverOrder.isEmpty()) listOf(30, 60) else listOf(30), provider.requests.map { it.offset })
            }
        }
    }

    private class PendingCloudShuffle(override val providerId: String,
        private val allowLateResponse: Boolean = false,
        private val canonicalIds: List<TrackRef>? = null) : QueueSourceSession {
        val requested = CompletableDeferred<Unit>()
        val response = CompletableDeferred<SourceShufflePage>()
        val returned = CompletableDeferred<Unit>()
        override suspend fun ensureCurrent() = Unit
        override suspend fun scenes(): List<PrivateFmScene> = error("Unexpected scenes request")
        override suspend fun radio(parameters: Map<String, String>, similar: Boolean): List<Track> = error("Unexpected radio request")
        override suspend fun canonicalShuffleIds(playlistId: String, collection: LibraryCollection?) = canonicalIds
        override suspend fun shuffle(playlistId: String, songId: String, algorithm: String,
            collection: LibraryCollection?): SourceShufflePage {
            requested.complete(Unit)
            return if (allowLateResponse) withContext(NonCancellable) {
                response.await().also { returned.complete(Unit) }
            } else response.await().also { returned.complete(Unit) }
        }
    }

    private class ShuffleProvider(private val total: Int = 90, private val metadataArtwork: Boolean = false) : MusicProvider,
        CatalogCapability, BatchCatalogCapability, PlaylistCapability, LibraryCapability, AccountRevisionCapability {
        override val id = "shuffle.external"
        override val displayName = "Shuffle external"
        override val capabilities = setOf(SourceCapability.TRACK, SourceCapability.PLAYLIST, SourceCapability.LIBRARY)
        override val collections = setOf(LibraryCollection.LIKED_TRACKS)
        override val accountRevision = MutableStateFlow("account-a")
        val requests = CopyOnWriteArrayList<PageRequest>()
        val catalogRequests = CopyOnWriteArrayList<TrackRef>()
        val catalogBatches = CopyOnWriteArrayList<List<TrackRef>>()
        var beforePage: suspend (PageRequest) -> Unit = {}
        var beforeTracks: suspend (List<TrackRef>) -> Unit = {}
        var omittedTracks = emptySet<TrackRef>()
        fun track(index: Int) = Track(TrackRef(id, "track-$index"), "Track $index", emptyList(),
            artworkUrl = if (metadataArtwork) "https://example.test/track-$index.jpg" else "")
        override suspend fun search(query: String, page: PageRequest) = SourcePage<Track>(emptyList())
        override suspend fun getTrack(ref: TrackRef): Track {
            catalogRequests += ref
            return track(ref.trackId.removePrefix("track-").toInt())
        }
        override suspend fun getTracks(refs: List<TrackRef>): List<Track> {
            catalogBatches += refs.toList()
            beforeTracks(refs)
            catalogRequests += refs
            return refs.filterNot { it in omittedTracks }.map { track(it.trackId.removePrefix("track-").toInt()) }
        }
        override suspend fun playlist(ref: PlaylistRef) = SourcePlaylist(ref, "Playlist", trackCount = total)
        override suspend fun playlistTracks(ref: PlaylistRef, page: PageRequest): SourcePage<Track> {
            requests += page
            beforePage(page)
            val end = minOf(total, page.offset + page.limit)
            return SourcePage((page.offset until end).map(::track), end < total, if (end < total) "$end" else null)
        }
        override suspend fun library(collection: LibraryCollection, page: PageRequest): SourcePage<SourceItem> =
            playlistTracks(PlaylistRef(id, "liked"), page).let { result ->
                SourcePage(result.items.map(SourceItem::TrackItem), result.hasMore, result.nextCursor)
            }
    }

    private class Fixture(placeholders: Boolean = false, sourceRouter: SourceRouter? = null,
        queueSession: (suspend (String?) -> QueueSourceSession)? = null,
        shuffleRandom: Random = Random.Default, batchTracks: Boolean = false) : AutoCloseable {
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
            val sourceFactory = if (sourceRouter != null) DefaultMediaSourceFactory(
                ResolvingDataSource.Factory(DefaultDataSource.Factory(context)) { dataSpec ->
                    dataSpec.withUri(Uri.fromFile(silence))
                },
            ) else DefaultMediaSourceFactory(context)
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
            val sources = object : QueueSourceActions {
                override fun canBatchTracks(providerId: String): Boolean = batchTracks &&
                    (sourceRouter?.registry?.provider(providerId) as? BatchCatalogCapability)?.supportsBatchTrackRequests == true
                override suspend fun open(providerId: String?): QueueSourceSession =
                    queueSession?.invoke(providerId) ?: error("Unexpected recommendation request")
                override suspend fun tracks(refs: List<TrackRef>): List<Track> {
                    if (sourceRouter != null) return sourceRouter.getTracks(refs)
                    val ids = refs.map { it.trackId }
                    requests += ids
                    return response.get()(ids).map { id -> Track(TrackRef(ProviderIds.NETEASE, id), "Loaded $id",
                        listOf(SourceArtist("Artist", ArtistRef(ProviderIds.NETEASE, "1"))),
                        SourceAlbum("Album", AlbumRef(ProviderIds.NETEASE, "1")), 1000) }
                }
            }
            PlaybackQueueManager(player, sources, scope, sourceRouter = sourceRouter,
                serverShuffleAllowed = { queueSession != null }, shuffleRandom = shuffleRandom)
        }

        fun item(id: String): MediaItem = MediaItem.Builder().setMediaId(id).setUri(silence.toURI().toString()).build()
        fun sourceItem(track: Track): MediaItem = track.toMediaMetadata().toMediaItem().buildUpon()
            .setUri(silence.toURI().toString()).build()
        fun items(): List<MediaItem> = (0 until player.mediaItemCount).map(player::getMediaItemAt)
        fun sourceOrder(): List<TrackRef> = player.playbackOrderIndices().map {
            requireNotNull(TrackRefCodec.decode(player.getMediaItemAt(it).mediaId))
        }
        fun metadataWindowReady(): Boolean = player.playbackOrderIndices().let { order ->
            order.isNotEmpty() && (order.take(13) + order.takeLast(12)).all { !isPlaceholderMediaItem(player.getMediaItemAt(it)) }
        }

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
        private fun await(stage: String = "queue state", matches: () -> Boolean) = runBlocking {
            try {
                withTimeout(5_000) { while (!onMain(matches)) delay(10) }
            } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
                throw AssertionError("Timed out waiting for $stage", error)
            }
        }

        private fun <T> onMain(block: () -> T): T {
            val result = AtomicReference<T>()
            InstrumentationRegistry.getInstrumentation().runOnMainSync { result.set(block()) }
            return result.get()
        }
    }
}
