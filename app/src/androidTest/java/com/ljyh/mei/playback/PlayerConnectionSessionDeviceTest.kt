package com.ljyh.mei.playback

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SilenceMediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.ljyh.mei.data.model.Tracks
import com.ljyh.mei.data.model.api.Intelligence
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.data.network.api.ApiService
import com.ljyh.mei.data.network.api.WeApiService
import com.ljyh.mei.data.repository.PlayerIntelligenceSource
import com.ljyh.mei.data.session.SessionIdentity
import com.ljyh.mei.data.session.SessionStamp
import com.ljyh.mei.data.session.SessionStore
import com.ljyh.mei.di.AppDatabase
import com.ljyh.mei.playback.queue.ListQueue
import com.ljyh.mei.ui.component.player.state.IntelligencePlaybackState
import java.lang.reflect.Proxy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Actual connection, service binder and muted decks; no host, network or persistent data. */
@OptIn(ExperimentalCoroutinesApi::class)
@androidx.annotation.OptIn(UnstableApi::class)
class PlayerConnectionSessionDeviceTest {
    private class Fixture(scope: CoroutineScope, identity: SessionIdentity) : AutoCloseable {
        private val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sessions = SessionStore().apply {
            bind {
                assertFalse("Backend reader called under publication monitor", Thread.holdsLock(this))
                identity
            }
        }
        val owner = sessions.snapshot()
        val sentinel = MediaItem.Builder().setMediaId("sentinel").setUri("https://example.invalid/sentinel").build()
        val selected = MediaItem.Builder().setMediaId("11").setUri("https://example.invalid/11").build()
        val queue = ListQueue("catalog", "Owned queue", listOf("11" to selected))
        private val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        private fun deck() = ExoPlayer.Builder(context)
            .setAudioAttributes(AudioAttributes.DEFAULT, false)
            .setMediaSourceFactory(object : MediaSource.Factory {
                override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider) = this
                override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy) = this
                override fun getSupportedTypes() = intArrayOf(C.CONTENT_TYPE_OTHER)
                override fun createMediaSource(mediaItem: MediaItem) = SilenceMediaSource(60_000_000L)
                    .apply { updateMediaItem(mediaItem) }
            })
            .build().apply { volume = 0f }
        val player = StableDeckPlayer(deck(), deck(), AudioAttributes.DEFAULT)
        private inline fun <reified T> unused(): T = Proxy.newProxyInstance(
            T::class.java.classLoader, arrayOf(T::class.java),
        ) { _, _, _ -> error("Unexpected network request") } as T
        @Volatile var detailRequest: (Array<out Any?>) -> Any? = { error("Unexpected metadata request") }
        private val api = Proxy.newProxyInstance(ApiService::class.java.classLoader, arrayOf(ApiService::class.java)) {
            _, method, args ->
            check(method.name == "getSongDetail")
            detailRequest(args!!)
        } as ApiService
        val service = MusicService().apply {
            this.player = this@Fixture.player
            accountSessions = this@Fixture.sessions
            this.scope = scope
            queueTitle = "Sentinel"
            queueManager = PlaybackQueueManager(player, api, unused<WeApiService>(), scope, accountSessions) { false }
        }
        val connection = PlayerConnection(context, service.MusicBinder(), database, scope)

        init { player.setMediaItems(listOf(sentinel), 0, 1234L) }

        fun assertUntouched() {
            assertEquals("Sentinel", service.queueTitle)
            assertEquals("Sentinel", connection.queueTitle.value)
            assertEquals(listOf("sentinel"), (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId })
            assertFalse(player.playWhenReady)
        }

        override fun close() {
            connection.dispose()
            service.queueManager.release()
            player.release()
            database.close()
        }
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result = runCatching(block) }
        return requireNotNull(result).getOrThrow()
    }

    private fun connectionTest(
        identity: SessionIdentity = SessionIdentity(17, true, false),
        block: TestScope.(Fixture) -> Unit,
    ) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = onMain { Fixture(backgroundScope, identity) }
        try { onMain { block(fixture) } }
        finally { onMain { fixture.close() }; Dispatchers.resetMain() }
    }

    @Test fun currentOwnerStartsTheOriginalQueue() = connectionTest { f ->
        f.connection.playQueue(f.queue, shuffle = true, expectedSession = f.owner)
        runCurrent()
        assertEquals("Owned queue", f.service.queueTitle)
        assertEquals("11", f.player.currentMediaItem?.mediaId)
        assertTrue(f.player.shuffleModeEnabled)
        assertTrue(f.player.playWhenReady)
    }

    @Test fun retiredOwnerIsRejectedBeforeTitlePublication() = connectionTest { f ->
        f.sessions.invalidate()
        f.connection.playQueue(f.queue, expectedSession = f.owner)
        runCurrent()
        f.assertUntouched()
    }

    @Test fun recoveringOwnerIsRejectedBeforeTitlePublication() = connectionTest { f ->
        f.sessions.setRecoveryRequired(true)
        f.connection.playQueue(f.queue, expectedSession = f.owner)
        runCurrent()
        f.assertUntouched()
    }

    @Test fun retirementAfterClickCannotReplaceTheQueue() = connectionTest { f ->
        f.connection.playQueue(f.queue, expectedSession = f.owner)
        f.sessions.invalidate()
        runCurrent()
        assertEquals("sentinel", f.player.currentMediaItem?.mediaId)
        assertFalse(f.player.playWhenReady)
    }

    @Test fun recoveryAfterClickCannotReplaceTheQueue() = connectionTest { f ->
        f.connection.playQueue(f.queue, expectedSession = f.owner)
        f.sessions.setRecoveryRequired(true)
        runCurrent()
        assertEquals("sentinel", f.player.currentMediaItem?.mediaId)
        assertFalse(f.player.playWhenReady)
    }

    @Test fun ordinaryQueuesRetainTheirRecoveryIndependentPath() = connectionTest { f ->
        f.sessions.invalidate()
        f.sessions.setRecoveryRequired(true)
        f.connection.playQueue(f.queue)
        runCurrent()
        assertEquals("11", f.player.currentMediaItem?.mediaId)
        assertTrue(f.player.playWhenReady)
    }

    @Test fun ownedHistoryQueuesRemainPlayableDuringRecovery() = connectionTest { f ->
        f.sessions.setRecoveryRequired(true)
        f.connection.playQueue(f.queue, expectedSession = f.owner, allowSessionRecovery = true)
        runCurrent()
        assertEquals("11", f.player.currentMediaItem?.mediaId)
        assertTrue(f.player.playWhenReady)
    }

    @Test fun recoveryIndependentHistoryStillRejectsARetiredOwner() = connectionTest { f ->
        f.sessions.invalidate()
        f.sessions.setRecoveryRequired(true)
        f.connection.playQueue(f.queue, expectedSession = f.owner, allowSessionRecovery = true)
        runCurrent()
        f.assertUntouched()
    }

    @Test fun historyRetirementAfterClickCannotCommitAnOfflineQueue() = connectionTest { f ->
        f.sessions.setRecoveryRequired(true)
        f.connection.playQueue(f.queue, expectedSession = f.owner, allowSessionRecovery = true)
        f.sessions.invalidate()
        runCurrent()
        assertEquals("sentinel", f.player.currentMediaItem?.mediaId)
        assertFalse(f.player.playWhenReady)
    }

    @Test fun historyRecoveryExceptionDoesNotAuthorizeOnlinePlaceholderHydration() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val f = onMain { Fixture(scope, SessionIdentity(17, true, false)) }
        val requests = java.util.concurrent.atomic.AtomicInteger()
        f.detailRequest = { requests.incrementAndGet(); error("Recovery must not dispatch metadata") }
        try {
            val status = onMain {
                @Suppress("UNCHECKED_CAST")
                val state = PlaybackQueueManager::class.java.getDeclaredField("_queueState")
                    .apply { isAccessible = true }.get(f.service.queueManager) as StateFlow<PlaybackQueueManager.QueueState>
                f.sessions.setRecoveryRequired(true)
                f.connection.playQueue(
                    ListQueue("history", "History", listOf("11" to null)),
                    expectedSession = f.owner, allowSessionRecovery = true,
                )
                state
            }
            // Wait through both the connection handoff and the manager's IO validation.
            withTimeout(5_000L) { status.first { it is PlaybackQueueManager.QueueState.Error } }
            onMain {
                assertEquals(0, requests.get())
                assertEquals("sentinel", f.player.currentMediaItem?.mediaId)
                assertFalse(f.player.playWhenReady)
            }
        } finally { onMain { f.close() }; scope.cancel() }
    }

    @Test fun currentRecommendationCanToggleTheExistingPlayer() = connectionTest { f ->
        f.connection.togglePlayPause(f.owner)
        assertTrue(f.player.playWhenReady)
        f.connection.togglePlayPause(f.owner)
        assertFalse(f.player.playWhenReady)
        assertEquals("sentinel", f.player.currentMediaItem?.mediaId)
    }

    @Test fun retiredRecommendationCannotToggleTheExistingPlayer() = connectionTest { f ->
        f.sessions.invalidate()
        f.connection.togglePlayPause(f.owner)
        f.assertUntouched()
    }

    @Test fun recoveringRecommendationCannotToggleTheExistingPlayer() = connectionTest { f ->
        f.sessions.setRecoveryRequired(true)
        f.connection.togglePlayPause(f.owner)
        f.assertUntouched()
    }

    @Test fun retiredFmSeedDoesNotCaptureANewSession() = connectionTest { f ->
        f.sessions.invalidate()
        f.connection.fmStart("11", expectedSession = f.owner)
        runCurrent()
        f.assertUntouched()
    }

    @Test fun recoveringFmSeedNeverDispatches() = connectionTest { f ->
        f.sessions.setRecoveryRequired(true)
        f.connection.fmStart("11", expectedSession = f.owner)
        runCurrent()
        f.assertUntouched()
    }

    @Test fun currentOwnerCanReuseTheExistingQueueWithoutBuildingAnother() = connectionTest { f ->
        f.player.setMediaItems(listOf(f.sentinel, f.selected), 0, 1234L)
        f.connection.onTrackClicked("11", expectedSession = f.owner) { error("Existing item must not rebuild") }
        assertEquals(1, f.player.currentMediaItemIndex)
        assertTrue(f.player.playWhenReady)
        assertEquals("Sentinel", f.service.queueTitle)
    }

    @Test fun retiredPageCannotSeekAnExistingItem() = connectionTest { f ->
        f.player.setMediaItems(listOf(f.sentinel, f.selected), 0, 1234L)
        f.sessions.invalidate()
        f.connection.onTrackClicked("11", expectedSession = f.owner) { error("Retired click must not build") }
        assertEquals(0, f.player.currentMediaItemIndex)
        assertEquals(1234L, f.player.currentPosition)
        assertFalse(f.player.playWhenReady)
    }

    @Test fun recoveringPageCannotSeekAnExistingItem() = connectionTest { f ->
        f.player.setMediaItems(listOf(f.sentinel, f.selected), 0, 1234L)
        f.sessions.setRecoveryRequired(true)
        f.connection.onTrackClicked("11", expectedSession = f.owner) { error("Recovering click must not build") }
        assertEquals(0, f.player.currentMediaItemIndex)
        assertFalse(f.player.playWhenReady)
    }

    @Test fun reentrantRetirementDuringSeekCannotStartPlayback() = connectionTest { f ->
        f.player.setMediaItems(listOf(f.sentinel, f.selected), 0, 1234L)
        f.player.onSeekRequested = { f.sessions.invalidate() }
        f.connection.onTrackClicked("11", expectedSession = f.owner) { error("Existing item must not rebuild") }
        assertFalse(f.player.playWhenReady)
    }

    @Test fun retirementDuringQueueAssemblyCannotPublishOrStart() = connectionTest { f ->
        f.connection.onTrackClicked("11", expectedSession = f.owner) {
            f.sessions.invalidate()
            f.queue
        }
        runCurrent()
        f.assertUntouched()
    }

    @Test fun newTrackRetainsItsPageOwnerAfterTheClickReturns() = connectionTest { f ->
        f.connection.onTrackClicked("11", expectedSession = f.owner) { f.queue }
        f.sessions.invalidate()
        runCurrent()
        assertEquals("sentinel", f.player.currentMediaItem?.mediaId)
        assertFalse(f.player.playWhenReady)
    }

    @Test fun ordinaryExistingItemsRetainTheirRecoveryIndependentPath() = connectionTest { f ->
        f.player.setMediaItems(listOf(f.sentinel, f.selected), 0, 1234L)
        f.sessions.invalidate()
        f.sessions.setRecoveryRequired(true)
        f.connection.onTrackClicked("11") { error("Existing item must not rebuild") }
        assertEquals(1, f.player.currentMediaItemIndex)
        assertTrue(f.player.playWhenReady)
    }

    private fun placeholderRequestTest(
        identity: SessionIdentity, retireInRequest: Boolean = false, fmSeed: Boolean = false,
    ) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val f = onMain { Fixture(scope, identity) }
        val started = CompletableDeferred<SessionStamp?>()
        val build = CompletableDeferred<Job>()
        try {
            f.detailRequest = { args ->
                started.complete(args[1] as? SessionStamp)
                build.complete(PlaybackQueueManager::class.java.getDeclaredField("activeQueueBuildJob")
                    .apply { isAccessible = true }.get(f.service.queueManager) as Job)
                if (retireInRequest) f.sessions.invalidate()
                Gson().fromJson("""{"code":200,"privileges":[],"songs":[
                    {"id":11,"name":"Fixture","dt":60000,"ar":[],"tns":[],
                    "al":{"id":1,"name":"Fixture","picUrl":"https://example.invalid/cover"}}
                ]}""", Tracks::class.java)
            }
            onMain {
                if (fmSeed) f.connection.fmStart("11", expectedSession = f.owner)
                else f.connection.playQueue(ListQueue("rank", "Fixture", listOf("11" to null)), expectedSession = f.owner)
            }
            withTimeout(5_000L) { assertEquals(f.owner, started.await()); build.await().join() }
            onMain {
                assertEquals(if (retireInRequest) "sentinel" else "11", f.player.currentMediaItem?.mediaId)
                assertEquals(!retireInRequest, f.player.playWhenReady)
            }
        } finally {
            onMain { f.close() }
            scope.cancel()
        }
    }

    @Test fun ownedPlaceholderMetadataRetainsTheTriggeringOwner() =
        placeholderRequestTest(SessionIdentity(17, true, false))

    @Test fun guestCatalogMetadataDoesNotRequireFmAuthentication() =
        placeholderRequestTest(SessionIdentity(0, false, true))

    @Test fun retiredPlaceholderResponseCannotReplaceTheQueue() =
        placeholderRequestTest(SessionIdentity(17, true, false), retireInRequest = true)

    @Test fun fmSeedHydrationRetainsTheDisplayedOwnerAndRejectsRetiredSuccess() =
        placeholderRequestTest(SessionIdentity(17, true, false), retireInRequest = true, fmSeed = true)

    @Test fun intelligenceConsumptionPublishesOutsideTheSessionMonitor() = connectionTest { f ->
        val source = object : PlayerIntelligenceSource {
            override suspend fun getSongDetail(id: String, owner: SessionStamp) =
                Resource.Success(Tracks(200, emptyList(), emptyList()))
            override suspend fun getIntelligenceList(id: String, playlistId: String, startSongId: String, owner: SessionStamp) =
                Resource.Success(Intelligence(200, emptyList(), ""))
        }
        val intelligence = IntelligencePlaybackState(backgroundScope, f.sessions, source)
        try {
            intelligence.start("11", "22", "11")
            runCurrent()
            val expected = intelligence.state.value
            assertTrue(intelligence.consume(expected) { owner -> f.connection.playQueue(f.queue, expectedSession = owner) })
            runCurrent()
            assertEquals("11", f.player.currentMediaItem?.mediaId)
            assertTrue(f.player.playWhenReady)
            assertFalse(intelligence.consume(expected) { error("Already consumed") })
        } finally { intelligence.close() }
    }
}
