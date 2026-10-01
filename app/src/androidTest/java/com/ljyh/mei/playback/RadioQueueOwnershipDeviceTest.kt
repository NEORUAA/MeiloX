package com.ljyh.mei.playback

import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import com.google.gson.Gson
import com.ljyh.mei.data.model.Tracks
import com.ljyh.mei.data.model.weapi.Radio
import com.ljyh.mei.data.network.api.ApiService
import com.ljyh.mei.data.network.api.WeApiService
import com.ljyh.mei.data.session.SessionChangedException
import com.ljyh.mei.data.session.SessionIdentity
import com.ljyh.mei.data.session.SessionStore
import com.ljyh.mei.playback.queue.ListQueue
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Test

/** Real queue manager/platform items with synthetic transports, never account or library writes. */
@OptIn(ExperimentalCoroutinesApi::class)
@androidx.annotation.OptIn(UnstableApi::class)
class RadioQueueOwnershipDeviceTest {
    private var identity = SessionIdentity(17, true, false)
    private val sessions = SessionStore().apply {
        bind {
            assertFalse("Backend reader called under session monitor", Thread.holdsLock(this))
            identity
        }
    }
    private val owner = sessions.snapshot()
    private var items = emptyList<MediaItem>()
    private val mutations = mutableListOf<String>()
    private var playing = false
    private var afterMutation: (String) -> Unit = { }
    private val requests = mutableListOf<List<Any?>>()
    private val pending = mutableListOf<Continuation<Radio>>()
    private var deferRadio = false
    private var radioCode = 200
    private var seedRequest: (Array<out Any?>) -> Any? = { error("Unexpected seed request") }
    private val seed = MediaItem.Builder().setMediaId("11").setUri("https://example.invalid/11").build()
    private val radio: Radio get() = Gson().fromJson("""
        {"code":$radioCode,"data":[
          {"id":11,"name":"seed","album":{"id":1,"name":"fixture","picUrl":""},"artists":[],"duration":1000},
          {"id":22,"name":"next","album":{"id":1,"name":"fixture","picUrl":""},"artists":[],"duration":1000}
        ],"extTransMap":{},"popAdjust":false,"tag":""}
    """.trimIndent(), Radio::class.java)
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
            "getShuffleModeEnabled" -> false
            "getPlayWhenReady", "isPlaying" -> playing
            "stop", "clearMediaItems", "setShuffleModeEnabled", "setRepeatMode", "addMediaItem", "addMediaItems",
            "prepare", "play", "setMediaItems", "setPlayWhenReady", "removeMediaItem" -> {
                when (method.name) {
                    "clearMediaItems" -> items = emptyList()
                    "addMediaItem" -> items = items + (args!![0] as MediaItem)
                    "addMediaItems" -> { @Suppress("UNCHECKED_CAST") val value = args!![0] as List<MediaItem>; items = items + value }
                    "setMediaItems" -> { @Suppress("UNCHECKED_CAST") val value = args!![0] as List<MediaItem>; items = value }
                    "play" -> playing = true
                    "setPlayWhenReady" -> playing = args!![0] as Boolean
                    "removeMediaItem" -> items = items.filterIndexed { index, _ -> index != args!![0] }
                }
                mutations += method.name
                afterMutation(method.name)
                null
            }
            else -> error("Unexpected player operation: ${method.name}")
        }
    } as Player
    private val api = Proxy.newProxyInstance(ApiService::class.java.classLoader, arrayOf(ApiService::class.java)) { _, method, args ->
        check(method.name == "getSongDetail")
        seedRequest(args!!)
    } as ApiService
    private val weApi = Proxy.newProxyInstance(WeApiService::class.java.classLoader, arrayOf(WeApiService::class.java)) { _, method, args ->
        check(method.name == "getRadio")
        requests += args!!.dropLast(1)
        if (deferRadio) {
            @Suppress("UNCHECKED_CAST") val continuation = args.last() as Continuation<Radio>
            pending += continuation
            COROUTINE_SUSPENDED
        } else radio
    } as WeApiService

    private suspend fun TestScope.fixture(test: suspend (PlaybackQueueManager) -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val manager = PlaybackQueueManager(player, api, weApi, backgroundScope, sessions)
        try { test(manager) } finally { manager.release(); Dispatchers.resetMain() }
    }

    private fun buildJob(manager: PlaybackQueueManager): Job = PlaybackQueueManager::class.java
        .getDeclaredField("activeQueueBuildJob").apply { isAccessible = true }.get(manager) as Job

    @Test fun seedFirstPlaybackRetainsPayloadAndFiltersOnlyTheSeed() = runTest {
        fixture { manager ->
            manager.startFmMode(seed, owner)
            runCurrent()
            assertEquals(listOf("11", "22"), items.map { it.mediaId })
            assertTrue(playing)
            assertTrue(manager.isFmMode)
            assertEquals(listOf(emptyMap<String, String>(), owner), requests.single())
        }
    }

    @Test fun scheduledStartCannotBorrowAReauthorizedSession() = runTest {
        fixture { manager ->
            manager.startFmMode(seed, owner)
            sessions.invalidate()
            runCurrent()
            assertTrue(mutations.isEmpty())
            assertTrue(requests.isEmpty())
        }
    }

    @Test fun deferredRefillCannotAdoptTheSessionAtExecutionTime() = runTest {
        fixture { manager ->
            items = listOf(seed)
            manager.restoreFmMode(true, owner)
            manager.requestFmRecommendations()
            sessions.invalidate()
            runCurrent()
            assertTrue(requests.isEmpty())
            assertTrue(mutations.isEmpty())
        }
    }

    @Test fun seedAndRecommendationRequestsShareTheOwner() = runTest {
        fixture { manager ->
            seedRequest = { args ->
                assertEquals(owner, args[1])
                Tracks(200, emptyList(), emptyList())
            }
            items = listOf(seed)
            manager.startFmModeById("11", owner)
            buildJob(manager).join()
            runCurrent()
            assertEquals(listOf("11", "11", "22"), items.map { it.mediaId })
            assertFalse(mutations.contains("clearMediaItems"))
            assertEquals(owner, requests.single()[1])
        }
    }

    @Test fun lateSeedCannotStartAnotherAccountsRecommendations() = runTest {
        fixture { manager ->
            val started = CompletableDeferred<Continuation<Tracks>>()
            seedRequest = { args ->
                assertEquals(owner, args[1])
                @Suppress("UNCHECKED_CAST") val continuation = args.last() as Continuation<Tracks>
                started.complete(continuation)
                COROUTINE_SUSPENDED
            }
            manager.startFmModeById("11", owner)
            val job = buildJob(manager)
            val continuation = started.await()
            identity = SessionIdentity(18, true, false)
            sessions.invalidate()
            continuation.resume(Tracks(200, emptyList(), emptyList()))
            job.join()
            runCurrent()
            assertTrue(requests.isEmpty())
            assertTrue(mutations.isEmpty())
        }
    }

    @Test fun lateFmResultCannotAppendAfterSameAccountReauthorization() = runTest {
        fixture { manager ->
            deferRadio = true
            manager.startFmMode(seed, owner)
            runCurrent()
            sessions.invalidate()
            pending.single().resume(radio)
            runCurrent()
            assertEquals(listOf(seed), items)
            assertFalse(mutations.contains("addMediaItems"))
        }
    }

    @Test fun oldFinallyCannotReleaseTheNewQueuesFetchReservation() = runTest {
        fixture { manager ->
            deferRadio = true
            manager.startFmMode(seed, owner)
            runCurrent()
            sessions.invalidate()
            manager.invalidateSession()
            manager.startFmMode(seed, sessions.snapshot())
            runCurrent()
            pending[0].resume(radio)
            runCurrent()
            manager.requestFmRecommendations()
            runCurrent()
            assertEquals(2, requests.size)
            pending[1].resume(radio)
            runCurrent()
            assertEquals(listOf("11", "22"), items.map { it.mediaId })
        }
    }

    @Test fun ordinaryQueueReplacementRetiresNonCooperativeFmWork() = runTest {
        fixture { manager ->
            deferRadio = true
            manager.startFmMode(seed, owner)
            runCurrent()
            val replacement = MediaItem.Builder().setMediaId("33").setUri("https://example.invalid/33").build()
            manager.playQueue(ListQueue("ordinary", "Fixture", listOf("33" to replacement)))
            runCurrent()
            pending.single().resume(radio)
            runCurrent()
            assertEquals(listOf(replacement), items)
            assertFalse(manager.isFmMode)
        }
    }

    @Test fun duplicateCallbacksShareOneFetchAndRecoveryRejectsItsResult() = runTest {
        fixture { manager ->
            deferRadio = true
            manager.startFmMode(seed, owner)
            runCurrent()
            repeat(4) { manager.requestFmRecommendations() }
            runCurrent()
            assertEquals(1, requests.size)
            sessions.setRecoveryRequired(true)
            pending.single().resume(radio)
            runCurrent()
            assertEquals(listOf(seed), items)
            manager.requestFmRecommendations()
            runCurrent()
            assertEquals(1, requests.size)
        }
    }

    @Test fun reentrantInvalidationAfterSeedInsertionPreventsPreparePlayAndFetch() = runTest {
        fixture { manager ->
            afterMutation = { if (it == "addMediaItem") sessions.invalidate() }
            manager.startFmMode(seed, owner)
            runCurrent()
            assertTrue(mutations.contains("addMediaItem"))
            assertFalse(mutations.contains("prepare"))
            assertFalse(playing)
            assertTrue(requests.isEmpty())
        }
    }

    @Test fun restoredFmKeepsTheQueueButDoesNotBindAnInvalidOwner() = runTest {
        fixture { manager ->
            items = listOf(seed)
            sessions.invalidate()
            manager.restoreFmMode(true, owner)
            manager.requestFmRecommendations()
            runCurrent()
            assertTrue(requests.isEmpty())
            assertEquals(listOf(seed), items)
            manager.restoreFmMode(true, sessions.snapshot())
            manager.requestFmRecommendations()
            runCurrent()
            assertEquals(listOf("11", "11", "22"), items.map { it.mediaId })
            assertFalse(mutations.contains("prepare"))
        }
    }

    @Test fun deferredTrashDoesNotMutateAnotherSessionOrQueue() = runTest {
        fixture { manager ->
            manager.startFmMode(seed, owner)
            runCurrent()
            mutations.clear()
            manager.fmTrashCurrent()
            sessions.invalidate()
            runCurrent()
            assertTrue(mutations.isEmpty())
        }
    }

    @Test fun rejectionAndReleaseDoNotAppendRecommendations() = runTest {
        fixture { manager ->
            radioCode = 403
            manager.startFmMode(seed, owner)
            runCurrent()
            assertEquals(listOf(seed), items)
            radioCode = 200
            deferRadio = true
            manager.requestFmRecommendations()
            runCurrent()
            manager.release()
            pending.single().resume(radio)
            runCurrent()
            assertEquals(listOf(seed), items)
        }
    }

    @Test fun recoveringOrGuestStartDoesNotRetireAnExistingQueue() = runTest {
        fixture { manager ->
            items = listOf(seed)
            sessions.setRecoveryRequired(true)
            try { manager.startFmMode(seed, owner); fail("Recovery accepted") } catch (_: SessionChangedException) { }
            sessions.setRecoveryRequired(false)
            identity = SessionIdentity(0, false, true)
            try { manager.startFmMode(seed, sessions.snapshot()); fail("Guest accepted") } catch (_: SessionChangedException) { }
            runCurrent()
            assertTrue(mutations.isEmpty())
            assertEquals(listOf(seed), items)
        }
    }
}
