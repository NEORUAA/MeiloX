package com.ljyh.mei.playback

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
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

/** Exercises the production service without changing preferences, the queue, or audible playback. */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class MusicServiceQualityRecoveryTest {
    @Test
    fun injectedDecoderFailureRecreatesTheCurrentPausedSourceAndPreservesItsQueue() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("issue38Recovery") == "true")
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
            lateinit var fakeIdentity: String
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
                val originalSource = currentService.mediaUriProvider.resolvedMediaSource(mediaId)
                assertTrue("A local resolved source cannot verify online recovery", originalSource == null || originalSource.cacheKey != null)
                val uri = originalSource?.uri ?: item.localConfiguration?.uri
                assertNotNull("The current source must have a URI", uri)

                player.removeListener(readyListener)
                player.addListener(recoveryListener)
                listener = recoveryListener
                val provider = currentService.mediaUriProvider
                previousObserver = provider.onSourceResolved
                fakeIdentity = "issue38-probe-${UUID.randomUUID()}"
                provider.onSourceResolved = { id, source ->
                    // Forward without entering Main synchronously: provider callbacks own a lock.
                    previousObserver?.invoke(id, source)
                    if (id == mediaId && source.sourceIdentity != null && source.sourceIdentity != fakeIdentity) {
                        actualSource.set(source)
                        sourceResolved.countDown()
                    }
                }
                observerInstalled = true
                val quality = originalSource?.actualQuality
                    ?: preferenceBefore?.let(::normalizePlaybackQuality)
                    ?: MusicQuality.EXHIGH.text
                provider.rememberCachedSource(
                    mediaId, quality, playbackCacheKey(mediaId, quality, fakeIdentity, 1L), uri!!,
                )
                injected = true
                currentService.onPlayerError(
                    PlaybackException("Injected source decoder failure", null, PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED),
                )
            }

            val recoveryDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
            assertTrue("Production recovery must resolve fresh real source bytes", sourceResolved.await(20, TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                if (recreatedSourceBuffered.get() && currentService.player.playbackState == Player.STATE_READY) {
                    readyAfterRecovery.countDown()
                }
            }
            val remainingNanos = (recoveryDeadline - System.nanoTime()).coerceAtLeast(0L)
            assertTrue("The recreated source must reach READY within 20 seconds", readyAfterRecovery.await(remainingNanos, TimeUnit.NANOSECONDS))
            // This main-thread turn follows the service's asynchronous source observer.
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
                assertEquals(
                    "The menu selection must show the effective server quality",
                    MusicQuality.entries.firstOrNull { it.text == source.actualQuality },
                    currentService.currentMusicQuality.value,
                )
                assertNotNull(currentService.currentMusicQuality.value)
            }
            val preferenceAfter = runBlocking { context.dataStore.data.first()[MusicQualityKey] }
            assertEquals("Recovery must never rewrite the user's preferred quality", preferenceBefore, preferenceAfter)
        } finally {
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
            context.unbindService(connection)
        }
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
