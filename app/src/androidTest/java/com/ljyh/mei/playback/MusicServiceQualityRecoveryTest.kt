package com.ljyh.mei.playback

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.StuckPlayerException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.SimpleCache
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ljyh.mei.constants.MusicQuality
import com.ljyh.mei.constants.MusicQualityKey
import com.ljyh.mei.data.model.metadata
import com.ljyh.mei.utils.dataStore
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises paused production recovery while retaining the queue, preferences, and existing cache. */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class MusicServiceQualityRecoveryTest {
    @Test
    fun repeatedSourceFailuresStopAcrossRealQueueTransitionsAndRetainTheQueue() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("issue59Recovery") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val connected = CountDownLatch(1)
        val reference = AtomicReference<MusicService?>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                reference.set((binder as? MusicService.MusicBinder)?.service)
                connected.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }
        assertTrue(context.bindService(Intent(context, MusicService::class.java), connection, Context.BIND_AUTO_CREATE))
        var service: MusicService? = null
        var original: QueueSnapshot? = null
        var originalItems = emptyList<MediaItem>()
        var listener: Player.Listener? = null
        try {
            assertTrue("The saved playback service must bind", connected.await(10, TimeUnit.SECONDS))
            val currentService = checkNotNull(reference.get()).also { service = it }
            val queueRestored = CountDownLatch(1)
            val restoreListener = object : Player.Listener {
                override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                    if (timeline.windowCount >= 6) queueRestored.countDown()
                }
            }
            listener = restoreListener
            instrumentation.runOnMainSync {
                currentService.player.addListener(restoreListener)
                if (currentService.player.mediaItemCount >= 6) queueRestored.countDown()
            }
            assertTrue("The saved queue must finish restoring before the regression starts", queueRestored.await(10, TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                val player = currentService.player
                player.removeListener(restoreListener)
                assertFalse("Run the opt-in regression while paused", player.playWhenReady)
                assertTrue("The saved queue needs six entries to verify the retry limit", player.mediaItemCount >= 6)
                original = QueueSnapshot.capture(player)
                originalItems = List(player.mediaItemCount, player::getMediaItemAt)
                player.shuffleModeEnabled = false
                player.repeatMode = Player.REPEAT_MODE_ALL
                player.seekTo(0, 0L)
            }
            listener = null
            repeat(5) { failureIndex ->
                val transitioned = CountDownLatch(1)
                var failedEntry: String? = null
                var nextEntry: String? = null
                val transitionListener = object : Player.Listener {
                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        if (mediaItem?.queueEntryId == nextEntry && reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) {
                            transitioned.countDown()
                        }
                    }
                }
                listener = transitionListener
                instrumentation.runOnMainSync {
                    val player = currentService.player
                    failedEntry = player.currentMediaItem?.queueEntryId
                    nextEntry = player.getMediaItemAt(player.nextMediaItemIndex).queueEntryId
                    assertNotNull(failedEntry)
                    assertNotNull(nextEntry)
                    player.addListener(transitionListener)
                    currentService.onPlayerError(
                        PlaybackException("Injected unavailable source", null, PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS),
                    )
                }
                if (failureIndex < 4) {
                    assertTrue("Automatic skip must publish its real SEEK event", transitioned.await(10, TimeUnit.SECONDS))
                }
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync {
                    val player = currentService.player
                    player.removeListener(transitionListener)
                    assertEquals(original!!.entryIds, QueueSnapshot.capture(player).entryIds)
                    assertFalse(player.playWhenReady)
                    if (failureIndex < 4) {
                        assertEquals(nextEntry, player.currentMediaItem?.queueEntryId)
                    } else {
                        assertEquals("The fifth failure must stop before another request", Player.STATE_IDLE, player.playbackState)
                        assertEquals(failedEntry, player.currentMediaItem?.queueEntryId)
                    }
                }
                listener = null
            }
        } finally {
            try {
                service?.let { currentService ->
                    instrumentation.runOnMainSync {
                        val player = currentService.player
                        listener?.let(player::removeListener)
                        original?.let { saved ->
                            // Restoring shuffle must not start a new server shuffle after this test.
                            currentService.queueManager.cancelServerShuffle()
                            player.activeDeck.setMediaItems(originalItems, saved.index, saved.positionMs)
                            player.setPlaybackOrder(saved.shuffleOrder)
                            player.shuffleModeEnabled = saved.shuffleEnabled
                            player.repeatMode = saved.repeatMode
                            player.playWhenReady = false
                            player.prepare()
                            val restored = QueueSnapshot.capture(player)
                            assertEquals(saved.mediaIds, restored.mediaIds)
                            assertEquals(saved.entryIds, restored.entryIds)
                            assertEquals(saved.index, restored.index)
                            assertEquals(saved.shuffleOrder, restored.shuffleOrder)
                            assertEquals(saved.shuffleEnabled, restored.shuffleEnabled)
                            assertEquals(saved.repeatMode, restored.repeatMode)
                            assertTrue("The original paused position must be restored", abs(saved.positionMs - restored.positionMs) <= 500L)
                            assertFalse(player.playWhenReady)
                        }
                    }
                }
            } finally {
                context.unbindService(connection)
            }
        }
    }

    @Test
    fun injectedDecoderFailureRecreatesTheCurrentPausedSourceAndPreservesItsQueue() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("issue38Recovery") == "true")
        verifyPausedSourceRecovery(
            listOf(RecoveryFailure(
                PlaybackException("Injected source decoder failure", null, PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED),
                rejectsIdentity = true,
            )),
        )
    }

    @Test
    fun stalledSourceRetriesItsQualityThenRejectsOnlyTheRepeatedSource() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("issue57Recovery") == "true")
        verifyPausedSourceRecovery(
            listOf(
                RecoveryFailure(
                    PlaybackException(
                        "Injected loading stall",
                        StuckPlayerException(StuckPlayerException.STUCK_BUFFERING_NO_PROGRESS, 30_000),
                        PlaybackException.ERROR_CODE_TIMEOUT,
                    ),
                    rejectsIdentity = false,
                ),
                RecoveryFailure(
                    PlaybackException(
                        "Injected repeated loading stall",
                        StuckPlayerException(StuckPlayerException.STUCK_BUFFERING_NOT_LOADING, 4_000),
                        PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK,
                    ),
                    rejectsIdentity = true,
                ),
            ),
            verifyCache = true,
        )
    }

    private data class RecoveryFailure(val error: PlaybackException, val rejectsIdentity: Boolean)

    private fun verifyPausedSourceRecovery(failures: List<RecoveryFailure>, verifyCache: Boolean = false) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val connected = CountDownLatch(1)
        val serviceReference = AtomicReference<MusicService?>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                serviceReference.set((binder as? MusicService.MusicBinder)?.service)
                connected.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }
        val bound = context.bindService(
            Intent(context, MusicService::class.java), connection, Context.BIND_AUTO_CREATE,
        )
        assertTrue("The existing playback service must be bindable", bound)

        var service: MusicService? = null
        var previousObserver: ((String, ResolvedMediaSource) -> Unit)? = null
        var observerInstalled = false
        var listener: Player.Listener? = null
        var injected = false
        var preservedQueue: QueueSnapshot? = null
        var preservedItems: List<MediaItem> = emptyList()
        val fakeCacheKeys = mutableListOf<String>()
        val retainedProbeKeys = mutableListOf<String>()
        val cache = CacheManager.getSimpleCache(context)
        try {
            assertTrue("The service did not bind within the bounded wait", connected.await(10, TimeUnit.SECONDS))
            val currentService = serviceReference.get()
            assertNotNull("The application must expose its MusicBinder", currentService)
            service = currentService!!

            val initialReady = CountDownLatch(1)
            val readyListener = object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) initialReady.countDown()
                }
            }
            listener = readyListener
            instrumentation.runOnMainSync {
                currentService.player.addListener(readyListener)
                if (currentService.player.playbackState == Player.STATE_READY) initialReady.countDown()
            }
            assertTrue("A saved paused online song must become READY", initialReady.await(10, TimeUnit.SECONDS))

            val preferenceBefore = runBlocking { context.dataStore.data.first()[MusicQualityKey] }
            lateinit var before: QueueSnapshot
            lateinit var mediaId: String
            instrumentation.runOnMainSync {
                val player = currentService.player
                assertFalse("Run this opt-in regression with the current song paused", player.playWhenReady)
                assertEquals(Player.STATE_READY, player.playbackState)
                val item = player.currentMediaItem
                assertNotNull("A current song is required", item)
                mediaId = item!!.mediaId
                assertTrue("The current entry must be an online song", mediaId.toLongOrNull()?.let { it > 0 } == true)
                assertFalse("Local files cannot verify the online resolver", item.metadata?.isLocal == true)
                assertFalse("Podcast episodes have no music quality catalog", item.metadata?.isPodcast == true)
                before = QueueSnapshot.capture(player)
                preservedQueue = before
                preservedItems = List(player.mediaItemCount) { player.getMediaItemAt(it) }
                assertTrue("Every queue entry must retain its stable identity", before.entryIds.all { it != null })
                player.removeListener(readyListener)
            }
            listener = null
            val protectedSpans = if (verifyCache) cache.keys
                .filter { it.startsWith(playbackCacheKeyPrefix(mediaId)) }
                .flatMap { key -> cache.getCachedSpans(key).map { Triple(key, it.position, it.length) } }
            else emptyList()
            val provider = currentService.mediaUriProvider
            previousObserver = provider.onSourceResolved

            for (failure in failures) {
                val fakeIdentity = "quality-recovery-probe-${UUID.randomUUID()}"
                val originalSource = provider.resolvedMediaSource(mediaId)
                assertTrue("A local resolved source cannot verify online recovery", originalSource == null || originalSource.cacheKey != null)
                val quality = originalSource?.actualQuality
                    ?: preferenceBefore?.let(::normalizePlaybackQuality)
                    ?: MusicQuality.EXHIGH.text
                val fakeKey = playbackCacheKey(mediaId, quality, fakeIdentity, 1L)
                fakeCacheKeys += fakeKey
                if (verifyCache) writeIncompleteProbeSpan(cache, fakeKey)
                val actualSource = AtomicReference<ResolvedMediaSource?>()
                val sourceResolved = CountDownLatch(1)
                val readyAfterRecovery = CountDownLatch(1)
                val recreatedSourceBuffered = AtomicBoolean(false)
                val recoveryListener = object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_BUFFERING) recreatedSourceBuffered.set(true)
                        if (playbackState == Player.STATE_READY && recreatedSourceBuffered.get() && actualSource.get() != null) {
                            readyAfterRecovery.countDown()
                        }
                    }
                }
                instrumentation.runOnMainSync {
                    val player = currentService.player
                    val uri = originalSource?.uri ?: player.currentMediaItem?.localConfiguration?.uri
                    assertNotNull("The current source must have a URI", uri)
                    listener?.let(player::removeListener)
                    player.addListener(recoveryListener)
                    listener = recoveryListener
                    provider.onSourceResolved = { id, source ->
                        // Forward without entering Main synchronously: provider callbacks own a lock.
                        previousObserver?.invoke(id, source)
                        if (id == mediaId && source.sourceIdentity != null && source.sourceIdentity != fakeIdentity) {
                            actualSource.set(source)
                            sourceResolved.countDown()
                        }
                    }
                    observerInstalled = true
                    provider.rememberCachedSource(mediaId, quality, fakeKey, uri!!)
                    injected = true
                    currentService.onPlayerError(failure.error)
                }

                val recoveryDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
                assertTrue("Production recovery must resolve real source bytes", sourceResolved.await(20, TimeUnit.SECONDS))
                instrumentation.runOnMainSync {
                    if (recreatedSourceBuffered.get() && currentService.player.playbackState == Player.STATE_READY) {
                        readyAfterRecovery.countDown()
                    }
                }
                val remainingNanos = (recoveryDeadline - System.nanoTime()).coerceAtLeast(0L)
                assertTrue("The recreated source must reach READY within 20 seconds", readyAfterRecovery.await(remainingNanos, TimeUnit.NANOSECONDS))
                instrumentation.runOnMainSync {
                    val player = currentService.player
                    val after = QueueSnapshot.capture(player)
                    assertEquals("Recovery must retain queue order and every entry identity", before.mediaIds, after.mediaIds)
                    assertEquals(before.entryIds, after.entryIds)
                    assertEquals("Recovery must retain the current entry", before.index, after.index)
                    assertEquals(before.shuffleOrder, after.shuffleOrder)
                    assertEquals(before.shuffleEnabled, after.shuffleEnabled)
                    assertEquals(before.repeatMode, after.repeatMode)
                    assertTrue("Recovery must retain the paused playback position", abs(before.positionMs - after.positionMs) <= 500L)
                    assertFalse("Injected recovery must remain inaudible", player.playWhenReady)
                    assertEquals(Player.STATE_READY, player.playbackState)
                    assertEquals(null, player.playerError)
                    val source = actualSource.get()!!
                    if (!failure.rejectsIdentity) {
                        assertEquals("The first stall must retry the same effective quality", quality, source.actualQuality)
                    }
                    assertEquals(
                        "The menu selection must show the effective server quality",
                        MusicQuality.entries.firstOrNull { it.text == source.actualQuality },
                        currentService.currentMusicQuality.value,
                    )
                    assertNotNull(currentService.currentMusicQuality.value)
                    assertEquals("Only a repeated or decoder failure may reject the source", failure.rejectsIdentity,
                        provider.isRejectedCachedSource(mediaId, fakeKey))
                }
                if (verifyCache) {
                    assertEquals("Only rejected probe bytes may be removed", !failure.rejectsIdentity, cache.isCached(fakeKey, 0L, 16L))
                    retainedProbeKeys.forEach { key ->
                        assertTrue("Recovery must retain the previous retry's cached bytes", cache.isCached(key, 0L, 16L))
                    }
                    protectedSpans.forEach { (key, position, length) ->
                        assertTrue("Recovery must retain pre-existing source cache spans", cache.isCached(key, position, length))
                    }
                    if (!failure.rejectsIdentity) retainedProbeKeys += fakeKey
                }
                val preferenceAfter = runBlocking { context.dataStore.data.first()[MusicQualityKey] }
                assertEquals("Recovery must never rewrite the user's preferred quality", preferenceBefore, preferenceAfter)
            }
        } finally {
            try {
                service?.let { currentService ->
                    instrumentation.runOnMainSync {
                        listener?.let(currentService.player::removeListener)
                        if (observerInstalled) currentService.mediaUriProvider.onSourceResolved = previousObserver
                        if (injected) {
                            currentService.resetRejectedPlaybackSources()
                            preservedQueue?.let { original ->
                                val player = currentService.player
                                val after = QueueSnapshot.capture(player)
                                if (original.copy(positionMs = after.positionMs) != after ||
                                    abs(original.positionMs - after.positionMs) > 500L || player.playWhenReady
                                ) {
                                    currentService.resetPlaybackSourcesForQualityChange()
                                    player.activeDeck.setMediaItems(preservedItems, original.index, original.positionMs)
                                    player.setPlaybackOrder(original.shuffleOrder)
                                    player.shuffleModeEnabled = original.shuffleEnabled
                                    player.repeatMode = original.repeatMode
                                    player.playWhenReady = false
                                    player.prepare()
                                }
                            }
                        }
                    }
                }
            } finally {
                fakeCacheKeys.forEach(cache::removeResource)
                context.unbindService(connection)
            }
        }
    }

    private fun writeIncompleteProbeSpan(cache: SimpleCache, key: String) {
        val hole = cache.startReadWrite(key, 0L, 16L)
        try {
            assertTrue("A unique probe cache key must own a new hole", hole.isHoleSpan)
            val file = cache.startFile(key, 0L, 16L)
            file.writeBytes(ByteArray(16))
            cache.commitFile(file, 16L)
        } finally {
            cache.releaseHoleSpan(hole)
        }
        assertTrue(cache.isCached(key, 0L, 16L))
        assertFalse("A probe must never masquerade as a complete playable source", CacheManager.isContentFullyCached(cache, key))
    }

    private data class QueueSnapshot(
        val mediaIds: List<String>,
        val entryIds: List<String?>,
        val index: Int,
        val positionMs: Long,
        val shuffleOrder: List<Int>,
        val shuffleEnabled: Boolean,
        val repeatMode: Int,
    ) {
        companion object {
            fun capture(player: StableDeckPlayer) = QueueSnapshot(
                mediaIds = List(player.mediaItemCount) { player.getMediaItemAt(it).mediaId },
                entryIds = List(player.mediaItemCount) { player.getMediaItemAt(it).queueEntryId },
                index = player.currentMediaItemIndex,
                positionMs = player.currentPosition,
                shuffleOrder = player.playbackOrderIndices(true),
                shuffleEnabled = player.shuffleModeEnabled,
                repeatMode = player.repeatMode,
            )
        }
    }
}
