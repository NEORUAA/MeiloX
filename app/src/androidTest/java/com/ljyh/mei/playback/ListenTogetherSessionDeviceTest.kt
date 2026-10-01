package com.ljyh.mei.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.ljyh.mei.data.model.Tracks
import com.ljyh.mei.data.model.api.GetSongDetails
import com.ljyh.mei.data.model.melox.ListenTogetherCommand
import com.ljyh.mei.data.model.melox.ListenTogetherPlaybackCommand
import com.ljyh.mei.data.model.melox.ListenTogetherPlaybackSnapshot
import com.ljyh.mei.data.model.melox.ListenTogetherRoom
import com.ljyh.mei.data.model.melox.ListenTogetherStatus
import com.ljyh.mei.data.network.api.ApiService
import com.ljyh.mei.data.repository.ListenTogetherSource
import com.ljyh.mei.data.session.SessionIdentity
import com.ljyh.mei.data.session.SessionStamp
import com.ljyh.mei.data.session.SessionStore
import java.lang.reflect.Proxy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

/** Real platform metadata and Store; only synthetic rooms, transports and players. */
@OptIn(ExperimentalCoroutinesApi::class)
class ListenTogetherSessionDeviceTest {
    private data class Call(val name: String, val owner: SessionStamp, val room: String? = null,
        val arguments: List<Any?> = emptyList())

    private class Source : ListenTogetherSource {
        val calls = mutableListOf<Call>()
        val handlers = mutableMapOf<String, suspend (Call) -> Any?>()
        var active: ListenTogetherRoom? = null
        var nextRoom = room("room-a", "9")
        var playback = snapshot()
        @Suppress("UNCHECKED_CAST")
        private suspend fun <T> call(name: String, owner: SessionStamp, room: String? = null,
            arguments: List<Any?> = emptyList(), fallback: T): T {
            val request = Call(name, owner, room, arguments)
            calls += request
            return (handlers[name]?.invoke(request) ?: fallback) as T
        }
        override suspend fun listenTogetherStatus(session: SessionStamp) =
            call("status", session, fallback = ListenTogetherStatus(active != null, active, null))
        override suspend fun createListenTogetherRoom(session: SessionStamp): ListenTogetherRoom =
            call("create", session, fallback = nextRoom).also { active = it }
        override suspend fun checkListenTogetherRoom(session: SessionStamp, roomId: String) =
            call("check", session, roomId, fallback = true to null)
        override suspend fun acceptListenTogetherRoom(session: SessionStamp, roomId: String, inviterId: String): ListenTogetherRoom =
            call("accept", session, roomId, listOf(inviterId), nextRoom).also { active = it }
        override suspend fun reportListenTogetherCommand(session: SessionStamp, roomId: String, command: ListenTogetherCommand,
            progressMs: Long, isPlaying: Boolean, formerSongId: Long?, targetSongId: Long, clientSequence: Long) {
            call("command", session, roomId, listOf(command, progressMs, isPlaying, formerSongId, targetSongId, clientSequence), Unit)
        }
        override suspend fun listenTogetherPlayback(session: SessionStamp, roomId: String) =
            call("playback", session, roomId, fallback = playback)
        override suspend fun reportListenTogetherPlaylist(session: SessionStamp, roomId: String, version: Long,
            displaySongIds: List<Long>, randomSongIds: List<Long>) {
            call("playlist", session, roomId, listOf(version, displaySongIds, randomSongIds), Unit)
        }
        override suspend fun sendListenTogetherHeartbeat(session: SessionStamp, roomId: String, songId: Long,
            isPlaying: Boolean, progressMs: Long): Int? =
            call("heartbeat", session, roomId, listOf(songId, isPlaying, progressMs), 3)
        override suspend fun endListenTogetherRoom(session: SessionStamp, roomId: String) {
            call("end", session, roomId, fallback = Unit)
            active = null
        }
    }

    private class FakePlayer(ids: List<Long> = listOf(11, 22)) {
        var items = ids.map { MediaItem.Builder().setMediaId(it.toString()).build() }
        var index = 0
        var position = 42L
        var playing = false
        var shuffle = false
        var afterMutation: ((String) -> Unit)? = null
        val mutations = mutableListOf<String>()
        val listeners = mutableListOf<Player.Listener>()
        val removed = mutableListOf<Player.Listener>()
        val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, args ->
            val arguments = args.orEmpty()
            when (method.name) {
                "getCurrentMediaItem" -> items.getOrNull(index)
                "getMediaItemCount" -> items.size
                "getMediaItemAt" -> items[arguments[0] as Int]
                "getCurrentMediaItemIndex" -> index
                "getCurrentPosition" -> position
                "getPlaybackState" -> Player.STATE_READY
                "isPlaying", "getPlayWhenReady" -> playing
                "getShuffleModeEnabled" -> shuffle
                "addListener" -> { listeners += arguments[0] as Player.Listener; null }
                "removeListener" -> { val listener = arguments[0] as Player.Listener; listeners -= listener; removed += listener; null }
                "setMediaItems" -> {
                    @Suppress("UNCHECKED_CAST")
                    items = arguments[0] as List<MediaItem>
                    index = arguments[1] as Int
                    position = arguments[2] as Long
                    mutate(method.name)
                }
                "seekTo" -> { index = arguments[0] as Int; position = arguments[1] as Long; mutate(method.name) }
                "setShuffleModeEnabled" -> { shuffle = arguments[0] as Boolean; mutate(method.name) }
                "play" -> { playing = true; mutate(method.name) }
                "pause" -> { playing = false; mutate(method.name) }
                "prepare" -> mutate(method.name)
                else -> error("Unexpected synthetic player method: ${method.name}")
            }
        } as Player
        private fun mutate(name: String): Any? { mutations += name; afterMutation?.invoke(name); return null }
    }

    private class Fixture(val clock: TestScope) {
        var identity = SessionIdentity(17, true, false)
        val sessions = SessionStore().apply { bind { identity } }
        val source = Source()
        val player = FakePlayer()
        val details = mutableListOf<SessionStamp>()
        var detailReply: ((GetSongDetails, SessionStamp) -> Tracks)? = null
        val api = Proxy.newProxyInstance(ApiService::class.java.classLoader, arrayOf(ApiService::class.java)) { _, method, args ->
            check(method.name == "getSongDetail") { "Unrelated synthetic API: ${method.name}" }
            val body = args!![0] as GetSongDetails
            val owner = args[1] as SessionStamp
            details += owner
            detailReply?.invoke(body, owner) ?: tracks(body)
        } as ApiService
        val store = ListenTogetherStore(InstrumentationRegistry.getInstrumentation().targetContext,
            source, api, sessions, clock.backgroundScope, { clock.testScheduler.currentTime }, { clock.testScheduler.currentTime })
        fun changeAccount(id: Long = 18) {
            source.active = null
            identity = SessionIdentity(id, true, false)
            sessions.invalidate()
        }
        fun join() { store.join("room-a", "9", store.state.value.session); clock.runCurrent() }
    }

    private suspend fun TestScope.fixture(block: suspend Fixture.() -> Unit) {
        val value = Fixture(this)
        try {
            runCurrent()
            value.store.attachPlayer(value.player.player)
            runCurrent()
            value.source.calls.clear()
            value.block()
        } finally { value.store.close() }
    }

    @Test fun participantIdentityNeverFallsBackToTheCreator() = runTest { fixture {
        join()
        assertFalse(store.state.value.isHost)
        assertEquals("17", Uri.parse(store.state.value.invitationUrl).getQueryParameter("inviterId"))
        assertEquals("11", Uri.parse(store.state.value.invitationUrl).getQueryParameter("songId"))
        assertTrue(source.calls.all { it.owner == sessions.snapshot() })
    } }

    @Test fun creatorRoleComesFromTheRuntimeSession() = runTest { fixture {
        source.nextRoom = room("room-a", "17")
        join()
        assertTrue(store.state.value.isHost)
    } }

    @Test fun creationReportsQueueCommandAndHeartbeatWithOneOwner() = runTest { fixture {
        val owner = sessions.snapshot()
        store.create(owner)
        runCurrent()
        assertEquals(listOf("create", "playlist", "command", "heartbeat"), source.calls.take(4).map { it.name })
        assertEquals(listOf(1L, listOf(11L, 22L), listOf(11L, 22L)), source.calls.first { it.name == "playlist" }.arguments)
        assertEquals(2L, source.calls.first { it.name == "command" }.arguments.last())
        assertTrue(source.calls.all { it.owner == owner })
        assertEquals(listOf("11", "22"), player.items.map { it.mediaId })
    } }

    @Test fun joinSynchronizesOriginalQueueShuffleProgressAndPauseSemantics() = runTest { fixture {
        source.playback = snapshot(listOf(22, 11), 22, 777, true, "RANDOM")
        join()
        assertEquals(listOf("check", "accept", "playback", "heartbeat"), source.calls.take(4).map { it.name })
        assertEquals(listOf("22", "11"), player.items.map { it.mediaId })
        assertEquals(777L, player.position)
        assertTrue(player.shuffle)
        assertTrue(player.playing)
        assertEquals(listOf(sessions.snapshot()), details)
        assertEquals("22", Uri.parse(store.state.value.invitationUrl).getQueryParameter("songId"))
    } }

    @Test fun guestAndRecoveryNeverDispatchRoomActions() = runTest { fixture {
        identity = SessionIdentity(0, false, true)
        sessions.invalidate()
        runCurrent()
        store.create(store.state.value.session)
        store.joinInvitation("?roomId=room-a&inviterId=9", store.state.value.session)
        runCurrent()
        assertTrue(source.calls.isEmpty())
        assertNotNull(store.state.value.error)
        identity = SessionIdentity(17, true, false)
        sessions.setRecoveryRequired(true)
        sessions.invalidate()
        runCurrent()
        store.create()
        store.join("room-a", "9")
        runCurrent()
        assertTrue(source.calls.isEmpty())
    } }

    @Test fun accountTransitionSynchronouslyRetiresRoomAndStopsReports() = runTest { fixture {
        join()
        val old = sessions.snapshot()
        val count = source.calls.size
        changeAccount()
        assertNull(store.state.value.room)
        assertNull(store.state.value.invitationUrl)
        assertFalse(store.state.value.isHost)
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        assertTrue(source.calls.drop(count).none { it.owner == old })
        assertNull(store.state.value.room)
        assertEquals(18L, store.state.value.session?.identity?.userId)
    } }

    @Test fun sameAccountReauthorizationRetiresThePreviousGeneration() = runTest { fixture {
        join()
        val old = store.state.value
        source.active = null
        sessions.invalidate()
        assertNull(store.state.value.room)
        runCurrent()
        store.end(old)
        store.create(old.session)
        runCurrent()
        assertEquals(17L, store.state.value.session?.identity?.userId)
        assertNotEquals(old.session, store.state.value.session)
        assertTrue(source.calls.none { it.name == "end" || it.name == "create" })
    } }

    @Test fun lateRoomCheckCannotContinueToAccept() = runTest { fixture {
        val result = CompletableDeferred<Pair<Boolean, String?>>()
        source.handlers["check"] = { withContext(NonCancellable) { result.await() } }
        store.join("room-a", "9")
        runCurrent()
        changeAccount()
        result.complete(true to null)
        runCurrent()
        assertTrue(source.calls.none { it.name == "accept" })
        assertNull(store.state.value.room)
    } }

    @Test fun lateCreationCannotEstablishOrReportToAReplacementAccount() = runTest { fixture {
        val result = CompletableDeferred<ListenTogetherRoom>()
        source.handlers["create"] = { withContext(NonCancellable) { result.await() } }
        store.create()
        runCurrent()
        changeAccount()
        runCurrent()
        result.complete(room("room-a", "17"))
        runCurrent()
        assertNull(store.state.value.room)
        assertTrue(source.calls.none { it.name in listOf("playlist", "command", "heartbeat") })
    } }

    @Test fun lateAcceptanceCannotSynchronizeTheReplacementPlayer() = runTest { fixture {
        val result = CompletableDeferred<ListenTogetherRoom>()
        source.handlers["accept"] = { withContext(NonCancellable) { result.await() } }
        store.join("room-a", "9")
        runCurrent()
        changeAccount()
        runCurrent()
        result.complete(room("room-a", "9"))
        runCurrent()
        assertTrue(player.mutations.isEmpty())
        assertTrue(source.calls.none { it.name == "playback" || it.name == "heartbeat" })
    } }

    @Test fun latePlaybackCannotChangeQueueSeekOrPlay() = runTest { fixture {
        val result = CompletableDeferred<ListenTogetherPlaybackSnapshot>()
        source.handlers["playback"] = { withContext(NonCancellable) { result.await() } }
        store.join("room-a", "9")
        runCurrent()
        changeAccount()
        runCurrent()
        result.complete(snapshot(listOf(33), 33, 900, true))
        runCurrent()
        assertTrue(player.mutations.isEmpty())
        assertTrue(details.isEmpty())
        assertNull(store.state.value.room)
    } }

    @Test fun songDetailPagesCarryTheRoomOwnerAndStopAtInvalidation() = runTest { fixture {
        source.playback = snapshot((1L..101L).toList(), 101)
        detailReply = { body, _ -> changeAccount(); tracks(body) }
        store.join("room-a", "9")
        runCurrent()
        assertEquals(1, details.size)
        assertEquals(17L, details.single().identity.userId)
        assertTrue(player.mutations.isEmpty())
        assertNull(store.state.value.room)
    } }

    @Test fun replacingAPlayerRetiresItsPendingPlaybackAndListener() = runTest { fixture {
        val result = CompletableDeferred<ListenTogetherPlaybackSnapshot>()
        source.handlers["playback"] = { withContext(NonCancellable) { result.await() } }
        store.join("room-a", "9")
        runCurrent()
        val oldListener = player.listeners.single()
        source.active = null
        val next = FakePlayer()
        store.attachPlayer(next.player)
        runCurrent()
        result.complete(snapshot(listOf(33), 33, 900, true))
        runCurrent()
        oldListener.onPlayWhenReadyChanged(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        oldListener.onTimelineChanged(Timeline.EMPTY, Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED)
        runCurrent()
        assertTrue(player.mutations.isEmpty())
        assertTrue(next.mutations.isEmpty())
        assertTrue(player.listeners.isEmpty())
        assertTrue(source.calls.none { it.name == "command" || it.name == "playlist" })
    } }

    @Test fun detachCancelsTheRoomAndIgnoresRetainedCallbacks() = runTest { fixture {
        join()
        advanceTimeBy(1_001)
        runCurrent()
        val listener = player.listeners.single()
        val count = source.calls.size
        store.detachPlayer(player.player)
        listener.onPlayWhenReadyChanged(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        advanceTimeBy(5_000)
        runCurrent()
        assertNull(store.state.value.room)
        assertEquals(count, source.calls.size)
    } }

    @Test fun queuedPlaylistReportCannotSurviveAnAccountChange() = runTest { fixture {
        join()
        advanceTimeBy(1_001)
        runCurrent()
        player.listeners.single().onTimelineChanged(Timeline.EMPTY, Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED)
        runCurrent()
        changeAccount()
        advanceTimeBy(350)
        runCurrent()
        assertTrue(source.calls.none { it.name == "playlist" })
    } }

    @Test fun staleEndCallbackCannotEndAnotherRoomInTheSameAccount() = runTest { fixture {
        join()
        val old = store.state.value
        source.active = room("room-b", "17")
        store.refresh()
        runCurrent()
        store.end(old)
        runCurrent()
        assertEquals("room-b", store.state.value.room?.id)
        assertTrue(source.calls.none { it.name == "end" })
    } }

    @Test fun endFailureKeepsOriginalLocalEndNoticeAndStopsMonitoring() = runTest { fixture {
        join()
        source.handlers["end"] = { error("End fixture denied") }
        store.end(store.state.value)
        runCurrent()
        assertNull(store.state.value.room)
        assertTrue(store.state.value.notice!!.contains("End fixture denied"))
        val count = source.calls.size
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(count, source.calls.size)
    } }

    @Test fun roomEndedStatusStopsTheNextHeartbeat() = runTest { fixture {
        join()
        source.active = null
        val count = source.calls.count { it.name == "heartbeat" }
        advanceTimeBy(4_001)
        runCurrent()
        assertNull(store.state.value.room)
        assertNotNull(store.state.value.notice)
        assertEquals(count, source.calls.count { it.name == "heartbeat" })
    } }

    @Test fun reconnectingStateRecoversOnlyWithinItsOwner() = runTest { fixture {
        join()
        source.handlers["playback"] = { error("Network fixture") }
        advanceTimeBy(2_001)
        runCurrent()
        assertEquals(ListenTogetherConnection.Reconnecting, store.state.value.connection)
        source.handlers.remove("playback")
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(ListenTogetherConnection.Connected, store.state.value.connection)
        assertNotNull(store.state.value.lastSyncTimeMs)
        changeAccount()
        runCurrent()
        assertNull(store.state.value.error)
        assertEquals(ListenTogetherConnection.Idle, store.state.value.connection)
    } }

    @Test fun aRetiredMonitorsNetworkFailureCannotPublishOrCrashTheNewSession() = runTest { fixture {
        join()
        source.handlers["playback"] = { changeAccount(); error("Old network fixture") }
        advanceTimeBy(1_001)
        runCurrent()
        assertNull(store.state.value.room)
        assertNull(store.state.value.error)
        assertEquals(ListenTogetherConnection.Idle, store.state.value.connection)
        assertEquals(18L, store.state.value.session?.identity?.userId)
    } }

    @Test fun reentrantInvalidationDuringQueueReplacementPreventsPrepareAndPlay() = runTest { fixture {
        source.playback = snapshot(listOf(33), 33, 900, true)
        player.afterMutation = { if (it == "setMediaItems") changeAccount() }
        store.join("room-a", "9")
        runCurrent()
        assertEquals(listOf("setMediaItems"), player.mutations)
        assertNull(store.state.value.room)
        assertTrue(source.calls.none { it.name == "heartbeat" })
    } }

    @Test fun duplicateActionsAreSerializedAndInvalidInvitationsDoNotDispatch() = runTest { fixture {
        store.joinInvitation("invalid", store.state.value.session)
        assertNotNull(store.state.value.error)
        assertTrue(source.calls.isEmpty())
        store.dismissError()
        val result = CompletableDeferred<Pair<Boolean, String?>>()
        source.handlers["check"] = { withContext(NonCancellable) { result.await() } }
        store.join("room-a", "9")
        store.create()
        store.refresh()
        runCurrent()
        assertEquals(listOf("check"), source.calls.map { it.name })
        result.complete(true to null)
        runCurrent()
        assertNotNull(store.state.value.room)
    } }

    @Test fun closeRetiresAllListenersAndRoomWork() = runTest { fixture {
        join()
        val count = source.calls.size
        store.close()
        assertNull(store.state.value.room)
        assertTrue(player.listeners.isEmpty())
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(count, source.calls.size)
    } }

    companion object {
        private fun room(id: String, creator: String) = ListenTogetherRoom(id, creator, emptyList(), null, null)
        private fun snapshot(ids: List<Long> = listOf(11, 22), target: Long = 11, progress: Long = 300,
            playing: Boolean = false, mode: String = "ORDER") = ListenTogetherPlaybackSnapshot(ids, mode,
            ListenTogetherPlaybackCommand("PROGRESS", target, null, progress, playing, 1, 1))
        private fun tracks(body: GetSongDetails): Tracks {
            val songs = JsonParser.parseString(body.c).asJsonArray.map {
                val id = it.asJsonObject.get("id").asLong
                """{"id":$id,"name":"Synthetic $id","ar":[],"al":{"id":1,"name":"Fixture","picUrl":""},"dt":1000}"""
            }.joinToString(",")
            return Gson().fromJson("""{"code":200,"privileges":[],"songs":[$songs]}""", Tracks::class.java)
        }
    }
}
