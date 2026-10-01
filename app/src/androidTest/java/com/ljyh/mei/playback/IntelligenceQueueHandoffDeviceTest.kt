package com.ljyh.mei.playback

import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import com.ljyh.mei.data.network.api.ApiService
import com.ljyh.mei.data.network.api.WeApiService
import com.ljyh.mei.data.session.SessionChangedException
import com.ljyh.mei.data.session.SessionIdentity
import com.ljyh.mei.data.session.SessionStore
import com.ljyh.mei.playback.queue.ListQueue
import com.ljyh.mei.playback.queue.Queue
import java.lang.reflect.Proxy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Test

/** Actual queue manager/platform items; no playback, accounts or network are exercised. */
@OptIn(ExperimentalCoroutinesApi::class)
@androidx.annotation.OptIn(UnstableApi::class)
class IntelligenceQueueHandoffDeviceTest {
    private val sessions = SessionStore().apply { bind { SessionIdentity(17, true, false) } }
    private val owner = sessions.snapshot()
    private val mutations = mutableListOf<String>()
    private var afterMutation: (String) -> Unit = { }
    private var items = emptyList<MediaItem>()
    private var playing = false
    private var shuffle = false
    private val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, args ->
        when (method.name) {
            "addListener", "removeListener" -> null
            "getMediaItemCount" -> items.size
            "getMediaItemAt" -> items[args!![0] as Int]
            "getCurrentMediaItem" -> items.firstOrNull()
            "getCurrentMediaItemIndex" -> 0
            "getApplicationLooper" -> Looper.getMainLooper()
            "getCurrentTimeline" -> Timeline.EMPTY
            "getPlaybackState" -> Player.STATE_IDLE
            "getShuffleModeEnabled" -> shuffle
            "getPlayWhenReady", "isPlaying" -> playing
            "stop", "clearMediaItems", "setShuffleModeEnabled", "setMediaItems", "setRepeatMode", "prepare", "setPlayWhenReady" -> {
                when (method.name) {
                    "clearMediaItems" -> items = emptyList()
                    "setMediaItems" -> { @Suppress("UNCHECKED_CAST") val value = args!![0] as List<MediaItem>; items = value }
                    "setPlayWhenReady" -> playing = args!![0] as Boolean
                    "setShuffleModeEnabled" -> shuffle = args!![0] as Boolean
                }
                mutations += method.name
                afterMutation(method.name)
                null
            }
            else -> error("Unexpected player operation: ${method.name}")
        }
    } as Player
    private inline fun <reified T> unused(): T = Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) {
        _, _, _ -> error("Unexpected network request")
    } as T
    private val item = MediaItem.Builder().setMediaId("11").setUri("https://example.invalid/11").build()
    private val queue = ListQueue("intelligence", "Fixture", listOf("11" to item))
    private val publish: (() -> Unit) -> Unit = { action ->
        sessions.withCurrent(owner) {
            if (sessions.recoveryRequired.value) throw SessionChangedException()
            action()
        }
    }

    @Test fun currentOwnerStartsTheOriginalQueue() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val manager = PlaybackQueueManager(player, unused<ApiService>(), unused<WeApiService>(), backgroundScope, sessions)
        try {
            manager.playQueue(queue, publishQueue = publish)
            runCurrent()
            assertEquals(listOf(item), items)
            assertTrue(playing)
            assertTrue(mutations.contains("prepare"))
        } finally { manager.release(); Dispatchers.resetMain() }
    }

    @Test fun ordinaryQueuesKeepTheirDefaultPublicationPath() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val manager = PlaybackQueueManager(player, unused<ApiService>(), unused<WeApiService>(), backgroundScope, sessions)
        try {
            sessions.invalidate()
            manager.playQueue(queue)
            runCurrent()
            assertEquals(listOf(item), items)
            assertTrue(playing)
        } finally { manager.release(); Dispatchers.resetMain() }
    }

    @Test fun invalidationBeforeScheduledBuildPreventsAllPlayerMutations() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val manager = PlaybackQueueManager(player, unused<ApiService>(), unused<WeApiService>(), backgroundScope, sessions)
        try {
            manager.playQueue(queue, publishQueue = publish)
            sessions.invalidate()
            runCurrent()
            assertTrue(mutations.isEmpty())
        } finally { manager.release(); Dispatchers.resetMain() }
    }

    @Test fun invalidationDuringInitialStatusPreventsQueueReplacement() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val manager = PlaybackQueueManager(player, unused<ApiService>(), unused<WeApiService>(), backgroundScope, sessions)
        val pending = CompletableDeferred<Queue.Status>()
        val delayed = object : Queue by queue { override suspend fun getInitialStatus() = pending.await() }
        try {
            manager.playQueue(delayed, publishQueue = publish)
            runCurrent()
            sessions.invalidate()
            pending.complete(queue.getInitialStatus())
            runCurrent()
            assertTrue(mutations.isEmpty())
        } finally { manager.release(); Dispatchers.resetMain() }
    }

    @Test fun reentrantInvalidationAfterReplacementCannotPrepareOrPlay() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val manager = PlaybackQueueManager(player, unused<ApiService>(), unused<WeApiService>(), backgroundScope, sessions)
        try {
            afterMutation = { if (it == "setMediaItems") sessions.invalidate() }
            manager.playQueue(queue, publishQueue = publish)
            runCurrent()
            assertTrue(mutations.contains("setMediaItems"))
            assertFalse(mutations.contains("prepare"))
            assertFalse(mutations.contains("setPlayWhenReady"))
        } finally { manager.release(); Dispatchers.resetMain() }
    }

    @Test fun recoveryBeforeBuildCannotPrepareOrPlay() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val manager = PlaybackQueueManager(player, unused<ApiService>(), unused<WeApiService>(), backgroundScope, sessions)
        try {
            manager.playQueue(queue, publishQueue = publish)
            sessions.setRecoveryRequired(true)
            runCurrent()
            assertTrue(mutations.isEmpty())
        } finally { manager.release(); Dispatchers.resetMain() }
    }
}
