package com.ljyh.mei.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import com.google.gson.Gson
import com.ljyh.mei.data.model.weapi.Radio
import com.ljyh.mei.playback.queue.PlaylistQueueSource
import com.ljyh.mei.source.api.*
import java.io.IOException
import java.lang.reflect.Proxy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SimilarFmPlaybackTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var fixture: Fixture

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        fixture = Fixture(CoroutineScope(SupervisorJob() + dispatcher))
    }

    @After
    fun tearDown() {
        fixture.manager.release()
        fixture.scope.cancel()
        Dispatchers.resetMain()
    }

    @Test
    fun recommendationSeedsAndRecentTracksPreserveOpaqueSourceIdentities() {
        val request = requireNotNull(SimilarFmRequest.fromSongIds(listOf("42", "qq:opaque", "42")))
        assertEquals(mapOf("seedTrackIds" to "[\"42\",\"qq:opaque\",\"42\"]", "recentTrackIds" to "[]"), request.body())
        assertEquals("[\"101\",\"opaque\"]", request.body(listOf("101", "opaque"))["recentTrackIds"])
        listOf(emptyList(), listOf(""), listOf(" "), listOf(null)).forEach { assertNull(SimilarFmRequest.fromSongIds(it)) }
    }

    @Test(timeout = 10_000)
    fun browseActionsPinTheirOwnerInsteadOfUsingTheCurrentlyPlayingSource() = runTest(dispatcher) {
        fixture.manager.startFmModeById(null, providerId = "browse-source")
        runCurrent()
        assertEquals(listOf("browse-source"), fixture.openedProviders)
        fixture.requests.single().response.complete(radio(101))
        runCurrent()
        val similar = fixture.manager.startSimilarFmMode(listOf("42"), providerId = "other-source")
        runCurrent()
        assertEquals(listOf("browse-source", "other-source"), fixture.openedProviders)
        assertEquals(listOf("101"), fixture.ids())
        fixture.requests.last().response.complete(radio(201))
        runCurrent()
        assertTrue(similar.await())
        assertEquals(listOf("201"), fixture.ids())
    }

    @Test(timeout = 10_000)
    fun personalModeSwitchReplacesAndStartsTheNewQueueAsSoonAsTheResponseArrives() = runTest(dispatcher) {
        fixture.manager.startFmModeById(null)
        runCurrent()
        assertEquals(listOf("old"), fixture.ids())
        fixture.requests.last().response.complete(radio(101, 102))
        runCurrent()
        assertEquals(listOf("101", "102"), fixture.ids())
        fixture.manager.setPrivateFmMode(PrivateFmMode.EXPLORE)
        runCurrent()
        assertEquals(listOf("101", "102"), fixture.ids())
        assertEquals("explore", fixture.requests.last().body["mode"])
        assertFalse(fixture.requests.last().similar)
        fixture.requests.last().response.complete(radio(201, 202))
        runCurrent()
        assertEquals(listOf("201", "202"), fixture.ids())
        assertTrue(fixture.played)
        assertFalse(fixture.manager.privateFmBusy.value)
        assertNull(fixture.manager.privateFmError.value)
    }

    @Test(timeout = 10_000)
    fun aLateResponseFromThePreviousSceneCannotReplaceTheNewScene() = runTest(dispatcher) {
        fixture.manager.restoreFmMode(true)
        fixture.manager.setPrivateFmMode(PrivateFmMode.SCENE, PrivateFmScene("FOCUS", "Example focus"))
        runCurrent()
        val first = fixture.requests.last()
        fixture.manager.setPrivateFmMode(PrivateFmMode.SCENE, PrivateFmScene("COMMUTE", "Example commute"))
        runCurrent()
        val second = fixture.requests.last()
        assertEquals("FOCUS", first.body["sceneCode"])
        assertEquals("COMMUTE", second.body["sceneCode"])
        second.response.complete(radio(301, 302))
        runCurrent()
        first.response.complete(radio(201, 202))
        runCurrent()
        assertEquals(listOf("301", "302"), fixture.ids())
        assertEquals("COMMUTE", fixture.manager.privateFmScene.value?.code)
    }

    @Test(timeout = 10_000)
    fun failedPersonalRefreshKeepsThePlayingSongAndPublishesAnError() = runTest(dispatcher) {
        fixture.manager.restoreFmMode(true)
        fixture.manager.setPrivateFmMode(PrivateFmMode.FAMILIAR)
        runCurrent()
        fixture.requests.last().response.complete(radio(code = 405))
        runCurrent()
        assertEquals(listOf("old"), fixture.ids())
        assertTrue(fixture.manager.privateFmError.value!!.contains("405"))
        assertFalse(fixture.manager.privateFmBusy.value)
    }

    @Test(timeout = 10_000)
    fun restoringASavedModeDoesNotRestartTheRestoredSong() = runTest(dispatcher) {
        fixture.manager.restoreFmMode(true)
        fixture.manager.setPrivateFmMode(PrivateFmMode.FAMILIAR, refreshActiveSession = false)
        runCurrent()
        assertEquals(PrivateFmMode.FAMILIAR, fixture.manager.privateFmMode.value)
        assertTrue(fixture.requests.isEmpty())
        assertEquals(listOf("old"), fixture.ids())
    }

    @Test(timeout = 10_000)
    fun startRequestsRecommendationsBeforeReplacingTheExistingQueue() = runTest(dispatcher) {
        val start = fixture.manager.startSimilarFmMode(listOf("42", "19", "7"))
        runCurrent()
        assertEquals(listOf("old"), fixture.ids())
        assertFalse(start.isCompleted)
        assertEquals("[\"42\",\"19\",\"7\"]", fixture.requests.single().body["seedTrackIds"])

        fixture.requests.single().response.complete(radio(101, 102, 103))
        runCurrent()
        assertTrue(start.await())
        assertEquals(listOf("101", "102", "103"), fixture.ids())
        assertEquals(listOf("42", "19", "7"), fixture.manager.similarFmSourceIds)
        assertTrue(fixture.manager.isFmMode)
        assertTrue(fixture.played)
    }

    @Test(timeout = 10_000)
    fun failedEmptyAndRejectedResponsesKeepTheExistingQueue() = runTest(dispatcher) {
        listOf(radio(code = 405), radio()).forEach { response ->
            val start = fixture.manager.startSimilarFmMode(listOf("42"))
            runCurrent()
            fixture.requests.last().response.complete(response)
            runCurrent()
            assertFalse(start.await())
            assertEquals(listOf("old"), fixture.ids())
            assertFalse(fixture.manager.isFmMode)
        }
        val start = fixture.manager.startSimilarFmMode(listOf("42"))
        runCurrent()
        fixture.requests.last().response.completeExceptionally(IOException("offline"))
        runCurrent()
        assertFalse(start.await())
        assertEquals(listOf("old"), fixture.ids())
    }

    @Test(timeout = 10_000)
    fun newerPlaybackWinsEvenIfTheOldTransportFinishesAfterCancellation() = runTest(dispatcher) {
        val old = fixture.manager.startSimilarFmMode(listOf("42"))
        runCurrent()
        val latest = fixture.manager.startSimilarFmMode(listOf("19"))
        runCurrent()
        fixture.requests[1].response.complete(radio(201, 202))
        runCurrent()
        assertTrue(latest.await())
        fixture.requests[0].response.complete(radio(101, 102))
        runCurrent()
        assertTrue(old.isCancelled)
        assertEquals(listOf("201", "202"), fixture.ids())
        assertEquals(listOf("19"), fixture.manager.similarFmSourceIds)
    }

    @Test(timeout = 10_000)
    fun aNewNavigationIntentCanRejectThePendingQueueCommit() = runTest(dispatcher) {
        var currentIntent = true
        val start = fixture.manager.startSimilarFmMode(listOf("42")) { currentIntent }
        runCurrent()
        currentIntent = false
        fixture.requests.single().response.complete(radio(101, 102))
        runCurrent()
        assertFalse(start.await())
        assertEquals(listOf("old"), fixture.ids())
    }

    @Test(timeout = 10_000)
    fun explicitQueueSelectionRejectsTheLateSimilarResponseAndKeepsItsSource() = runTest(dispatcher) {
        val source = PlaylistQueueSource(88L, "existing-source")
        fixture.manager.restorePlaylistSource(source)
        val start = fixture.manager.startSimilarFmMode(listOf("42"))
        runCurrent()

        // Selection must cancel the pending start before resolving or hydrating the entry.
        fixture.manager.seekToQueueEntry("selected-existing-entry")
        runCurrent()
        fixture.requests.single().response.complete(radio(101, 102))
        runCurrent()
        assertTrue(start.isCancelled)
        assertEquals(listOf("old"), fixture.ids())
        assertEquals(source, fixture.manager.playlistSource)
        assertFalse(fixture.manager.isFmMode)
    }

    @Test(timeout = 10_000)
    fun cancelingOnlyTheWaiterDoesNotCancelAnAcceptedServiceRequest() = runTest(dispatcher) {
        val start = fixture.manager.startSimilarFmMode(listOf("42"))
        val waiter = launch { start.await() }
        runCurrent()
        waiter.cancel()
        fixture.requests.single().response.complete(radio(101, 102))
        runCurrent()
        assertTrue(start.await())
        assertEquals(listOf("101", "102"), fixture.ids())
    }

    @Test(timeout = 10_000)
    fun refillKeepsTheSimilarSourcesAndDoesNotAppendQueuedDuplicates() = runTest(dispatcher) {
        val start = fixture.manager.startSimilarFmMode(listOf("42", "19"))
        runCurrent()
        fixture.requests.single().response.complete(radio(101, 102, 103))
        runCurrent()
        assertTrue(start.await())
        val refill = launch { fixture.manager.fetchAndAppendFmRecommendations() }
        runCurrent()
        assertTrue(fixture.requests.last().similar)
        assertEquals("[\"42\",\"19\"]", fixture.requests.last().body["seedTrackIds"])
        assertEquals("[]", fixture.requests.last().body["recentTrackIds"])
        fixture.requests.last().response.complete(radio(102, 104, 104))
        runCurrent()
        refill.join()
        assertEquals(listOf("101", "102", "103", "104"), fixture.ids())
    }

    @Test(timeout = 10_000)
    fun restoreKeepsSimilarContextAndPersonalFmExplicitlyClearsIt() = runTest(dispatcher) {
        fixture.manager.restoreFmMode(true, listOf("42", "19"))
        val oldRefill = launch { fixture.manager.fetchAndAppendFmRecommendations() }
        runCurrent()
        assertTrue(fixture.requests.single().similar)

        fixture.manager.startFmModeById(null)
        runCurrent()
        assertEquals("default", fixture.requests.last().body["mode"])
        assertFalse(fixture.requests.last().similar)
        assertNull(fixture.manager.similarFmSourceIds)
        fixture.requests.last().response.complete(radio(201))
        runCurrent()
        fixture.requests.first().response.complete(radio(101))
        runCurrent()
        oldRefill.join()
        assertEquals(listOf("201"), fixture.ids())

        fixture.manager.restoreFmMode(true, listOf("broken"))
        assertTrue(fixture.manager.isFmMode)
        assertEquals(listOf("broken"), fixture.manager.similarFmSourceIds)
        fixture.manager.restoreFmMode(true, listOf(""))
        assertFalse(fixture.manager.isFmMode)
        assertNull(fixture.manager.similarFmSourceIds)
    }

    @Test(timeout = 10_000)
    fun queueAutoplayUsesTheOpaqueSeedAndKeepsItsOwnerWhileTheCurrentTrackChanges() = runTest(dispatcher) {
        val seed = TrackRef("qq-source", "track/opaque:42")
        fixture.items.clear()
        fixture.items += item(TrackRefCodec.encode(seed))
        assertTrue(fixture.manager.setQueueAutoplayEnabled(true))
        val nextItem = item(TrackRefCodec.encode(TrackRef("other-source", "another-track")))
        fixture.items[0] = nextItem
        runCurrent()
        assertEquals(listOf("qq-source"), fixture.openedProviders)
        assertEquals("[\"track/opaque:42\"]", fixture.requests.single().body["seedTrackIds"])
        assertTrue(fixture.requests.single().similar)
        fixture.requests.single().response.complete(radio(101))
        runCurrent()
        assertEquals(listOf(nextItem.mediaId, "101"), fixture.ids())
        assertEquals(listOf(seed.trackId), fixture.manager.similarFmSourceIds)
        assertEquals(0, fixture.destructivePlaybackCalls)
    }

    @Test(timeout = 10_000)
    fun queueAutoplayAppendsWithoutChangingThePausedQueueSourcePositionOrModes() = runTest(dispatcher) {
        fixture.items.clear()
        fixture.items.addAll(listOf(item("11"), item("42"), item("19")))
        fixture.currentIndex = 1
        fixture.position = 12_345L
        fixture.shuffle = true
        fixture.repeat = Player.REPEAT_MODE_ONE
        val source = PlaylistQueueSource(88L, "existing-source")
        fixture.manager.restorePlaylistSource(source)

        assertTrue(fixture.manager.setQueueAutoplayEnabled(true))
        runCurrent()
        assertEquals(listOf("11", "42", "19"), fixture.ids())
        assertEquals("[\"42\"]", fixture.requests.single().body["seedTrackIds"])
        fixture.requests.single().response.complete(radio(42, 101, 101, 102))
        runCurrent()

        assertEquals(listOf("11", "42", "19", "101", "102"), fixture.ids())
        assertEquals(1, fixture.currentIndex)
        assertEquals(12_345L, fixture.position)
        assertFalse(fixture.playWhenReady)
        assertTrue(fixture.shuffle)
        assertEquals(Player.REPEAT_MODE_ONE, fixture.repeat)
        assertEquals(source, fixture.manager.playlistSource)
        assertEquals(0, fixture.destructivePlaybackCalls)
        assertTrue(fixture.manager.queueAutoplayEnabled.value)
        assertTrue(fixture.manager.isFmMode)

        fixture.manager.setShuffleModeEnabled(false)
        runCurrent()
        assertFalse(fixture.shuffle)
        fixture.manager.setShuffleModeEnabled(true)
        runCurrent()
        assertTrue(fixture.shuffle)
    }

    @Test(timeout = 10_000)
    fun queueAutoplayRejectsAnOldRefillAcrossOffOnAndKeepsAlreadyAddedSongsWhenDisabled() = runTest(dispatcher) {
        fixture.items.clear()
        fixture.items += item("42")
        fixture.playWhenReady = true
        assertTrue(fixture.manager.setQueueAutoplayEnabled(true))
        runCurrent()
        assertTrue(fixture.manager.setQueueAutoplayEnabled(false))
        assertFalse(fixture.manager.isFmMode)
        assertTrue(fixture.manager.setQueueAutoplayEnabled(true))
        runCurrent()
        assertEquals(2, fixture.requests.size)

        fixture.requests.first().response.complete(radio(101))
        runCurrent()
        assertEquals(listOf("42"), fixture.ids())
        fixture.requests.last().response.complete(radio(201))
        runCurrent()
        assertEquals(listOf("42", "201"), fixture.ids())

        assertTrue(fixture.manager.setQueueAutoplayEnabled(false))
        fixture.manager.fetchAndAppendFmRecommendations()
        runCurrent()
        assertEquals(2, fixture.requests.size)
        assertEquals(listOf("42", "201"), fixture.ids())
        assertTrue(fixture.playWhenReady)
        assertEquals(0, fixture.destructivePlaybackCalls)
    }

    @Test(timeout = 10_000)
    fun queueAutoplayRejectsUnsupportedSeedsAndDoesNotReplaceIntentionalFm() = runTest(dispatcher) {
        assertFalse(fixture.manager.setQueueAutoplayEnabled(true))
        fixture.items.clear()
        assertFalse(fixture.manager.setQueueAutoplayEnabled(true))
        fixture.items += item("local_42")
        assertFalse(fixture.manager.setQueueAutoplayEnabled(true))
        fixture.items[0] = item(TrackRefCodec.encode(TrackRef(ProviderIds.LOCAL, "opaque-local")))
        assertFalse(fixture.manager.setQueueAutoplayEnabled(true))
        fixture.items.clear()
        fixture.items += item("42")
        fixture.manager.restoreFmMode(true)
        assertTrue(fixture.manager.setQueueAutoplayEnabled(false))
        assertTrue(fixture.manager.isFmMode)
        assertFalse(fixture.manager.setQueueAutoplayEnabled(true))
        runCurrent()
        assertTrue(fixture.requests.isEmpty())
        assertEquals(listOf("42"), fixture.ids())
        assertEquals(0, fixture.destructivePlaybackCalls)
    }

    @Test
    fun deletingRecommendationBoundariesKeepsLaterRefillsAndNeverSelectsTheOriginalQueue() {
        var previous = listOf("original", "first", "second")
        var start: String? = "first"
        fun updateQueue(current: List<String>): String? {
            start = repairQueueAutoplayStartEntryId(start, previous, current)
            previous = current
            return start
        }
        assertEquals("second", updateQueue(listOf("original", "second")))
        assertEquals("second", updateQueue(listOf("original", "second", "refill-first", "refill-second")))
        assertEquals("refill-first", updateQueue(listOf("original", "refill-first", "refill-second")))
        assertEquals("refill-second", updateQueue(listOf("original", "refill-second")))
        assertNull(updateQueue(listOf("original")))
        assertEquals(listOf("original"), previous)
    }

    private class Fixture(val scope: CoroutineScope) {
        val items = mutableListOf(item("old"))
        val requests = mutableListOf<PendingRadio>()
        var played = false
        var shuffle = false
        var repeat = Player.REPEAT_MODE_OFF
        var currentIndex = 0
        var position = 0L
        var playWhenReady = false
        var destructivePlaybackCalls = 0
        private val player = Proxy.newProxyInstance(
            Player::class.java.classLoader,
            arrayOf(Player::class.java),
        ) { _, method, arguments ->
            when (method.name) {
                "getMediaItemCount" -> items.size
                "getCurrentMediaItemIndex" -> currentIndex
                "getCurrentMediaItem" -> items.getOrNull(currentIndex)
                "getCurrentPosition" -> position
                "getPlayWhenReady" -> playWhenReady
                "setPlayWhenReady" -> { playWhenReady = arguments!![0] as Boolean; null }
                "getMediaItemAt" -> items[arguments!![0] as Int]
                "getCurrentTimeline" -> Timeline.EMPTY
                "getShuffleModeEnabled" -> shuffle
                "setShuffleModeEnabled" -> { shuffle = arguments!![0] as Boolean; null }
                "getRepeatMode" -> repeat
                "setRepeatMode" -> { repeat = arguments!![0] as Int; null }
                "clearMediaItems" -> { destructivePlaybackCalls++; items.clear(); null }
                "setMediaItems" -> {
                    destructivePlaybackCalls++
                    items.clear()
                    @Suppress("UNCHECKED_CAST")
                    items.addAll(arguments!![0] as List<MediaItem>)
                    null
                }
                "addMediaItems" -> {
                    @Suppress("UNCHECKED_CAST")
                    items.addAll(arguments!![0] as List<MediaItem>)
                    null
                }
                "addMediaItem" -> { items.add(arguments!![0] as MediaItem); null }
                "play" -> { destructivePlaybackCalls++; played = true; null }
                "stop", "prepare" -> { destructivePlaybackCalls++; null }
                "addListener", "removeListener" -> null
                else -> error("Unexpected player call: ${method.name}")
            }
        } as Player
        val openedProviders = mutableListOf<String?>()
        private val sources = object : QueueSourceActions {
            override suspend fun open(providerId: String?): QueueSourceSession {
                openedProviders += providerId
                val selected = providerId ?: ProviderIds.NETEASE
                return object : QueueSourceSession {
                    override val providerId = selected
                    override suspend fun ensureCurrent() = Unit
                    override suspend fun scenes(): List<PrivateFmScene> = error("Unexpected scenes request")
                    override suspend fun shuffle(playlistId: String, songId: String, algorithm: String, collection: LibraryCollection?): SourceShufflePage = error("Unexpected shuffle request")
                    override suspend fun radio(parameters: Map<String, String>, similar: Boolean): List<Track> {
                        val request = PendingRadio(parameters, similar)
                        requests += request
                        // Exercise generation checks even when a transport does not cancel promptly.
                        val response = withContext(NonCancellable) { request.response.await() }
                        check(response.code == 200) { "漫游推荐加载失败 (${response.code})" }
                        return response.data.map { Track(TrackRef(this.providerId, it.id.toString()), "Fixture", emptyList()) }
                    }
                }
            }
            override suspend fun tracks(refs: List<TrackRef>): List<Track> = error("Unexpected catalog request")
        }
        val manager = PlaybackQueueManager(player, sources, scope,
            radioItemFactory = { item(it.ref.trackId) })

        fun ids(): List<String> = items.map(MediaItem::mediaId)
    }

    private class PendingRadio(val body: Map<String, String>, val similar: Boolean) {
        val response = CompletableDeferred<Radio>()
    }

    companion object {
        private fun item(id: String): MediaItem = MediaItem.Builder().setMediaId(id).build()

        private fun radio(vararg ids: Long, code: Int = 200): Radio = Gson().fromJson(
            """{"code":$code,"data":[${ids.joinToString { "{\"id\":$it}" }}]}""",
            Radio::class.java,
        )
    }
}
