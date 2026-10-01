package com.ljyh.mei.data.repository

import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.ljyh.mei.data.model.melox.ListenTogetherCommand
import com.ljyh.mei.data.network.api.MeloXDirectService
import com.ljyh.mei.data.session.SessionChangedException
import com.ljyh.mei.data.session.SessionIdentity
import com.ljyh.mei.data.session.SessionStamp
import com.ljyh.mei.data.session.SessionStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response

/** Real Repository/platform JSON; no real rooms, invitations, commands or heartbeats. */
class ListenTogetherRequestDeviceTest {
    private var identity = SessionIdentity(17, true, false)
    private val sessions = SessionStore().apply { bind { identity } }
    private val eapi = Transport()
    private val weapi = Transport()
    private val repository = MeloXRepository(
        eapi, weapi, InstrumentationRegistry.getInstrumentation().targetContext, sessions,
        CloudUploadCoordinator(eapi, weapi, sessions, object : CloudBinaryUploader {
            override suspend fun upload(file: CloudUploadFile, authorization: CloudUploadAuthorization,
                owner: SessionStamp, onProgress: (Long, Long) -> Unit) = error("Unrelated upload")
        }), CloudLibraryBackend(weapi, sessions),
    )
    private data class Request(val path: String, val body: Map<String, Any>, val owner: SessionStamp?, val headers: Map<String, String>)
    private class Transport : MeloXDirectService {
        val requests = mutableListOf<Request>()
        var result: suspend (Request) -> JsonObject = { json("""{"code":200,"data":{
            "inRoom":true,"joinable":true,"timeSpan":7,
            "roomInfo":{"roomId":"room-a","creatorId":"9","roomUsers":[{"userId":"17","nickname":"Self"}]}
        }}""") }
        override suspend fun post(path: String, body: Map<String, Any>, headers: Map<String, String>, expectedSession: SessionStamp?): JsonObject {
            val request = Request(path, body, expectedSession, headers)
            requests += request
            return result(request)
        }
        override suspend fun postPlaybackRaw(path: String, body: Map<String, Any>, headers: Map<String, String>): Response<ResponseBody> =
            error("Unrelated playback report")
    }
    private fun operations(owner: SessionStamp): List<suspend () -> Any?> = listOf(
        { repository.listenTogetherStatus(owner) },
        { repository.createListenTogetherRoom(owner) },
        { repository.checkListenTogetherRoom(owner, "room-a") },
        { repository.acceptListenTogetherRoom(owner, "room-a", "9") },
        { repository.reportListenTogetherCommand(owner, "room-a", ListenTogetherCommand.GoTo, -3, true, null, 11, 2) },
        { repository.listenTogetherPlayback(owner, "room-a") },
        { repository.reportListenTogetherPlaylist(owner, "room-a", 3, listOf(11, 22), listOf(22, 11)) },
        { repository.sendListenTogetherHeartbeat(owner, "room-a", 11, false, -4) },
        { repository.endListenTogetherRoom(owner, "room-a") },
    )

    @Test fun allNineRoutesCarryTheCapturedSessionWithoutChangingTransportChoice() = runBlocking {
        val owner = sessions.snapshot()
        operations(owner).forEach { it() }
        assertEquals(listOf("/api/listen/together/status/get"), weapi.requests.map { it.path })
        assertEquals(listOf("room/create", "room/check", "play/invitation/accept", "play/command/report",
            "sync/playlist/get", "sync/list/command/report", "heartbeat", "end/v2"),
            eapi.requests.map { it.path.removePrefix("/api/listen/together/") })
        assertTrue((weapi.requests + eapi.requests).all { it.owner == owner && it.headers.isEmpty() })
        assertEquals(mapOf("refer" to "songplay_more"), eapi.requests[0].body)
        assertEquals(mapOf("refer" to "inbox_invite", "roomId" to "room-a", "inviterId" to "9"), eapi.requests[2].body)
    }

    @Test fun roomStatusCreationAndAcceptancePreserveResponseMapping() = runBlocking {
        val owner = sessions.snapshot()
        val status = repository.listenTogetherStatus(owner)
        assertTrue(status.isInRoom)
        assertEquals("room-a", status.room?.id)
        assertEquals("9", status.room?.creatorId)
        assertEquals("room-a", repository.createListenTogetherRoom(owner).id)
        assertEquals(true to null, repository.checkListenTogetherRoom(owner, "room-a"))
        assertEquals("9", repository.acceptListenTogetherRoom(owner, "room-a", "9").creatorId)
    }

    @Test fun playlistVersionBelongsToTheSessionNotTheRoomCreator() = runBlocking {
        repository.reportListenTogetherPlaylist(sessions.snapshot(), "room-a", 3, listOf(11, 22), listOf(22, 11))
        val body = JsonParser.parseString(eapi.requests.single().body["playlistParam"] as String).asJsonObject
        assertEquals("REPLACE", body.get("commandType").asString)
        assertEquals(17L, body.getAsJsonArray("version")[0].asJsonObject.get("userId").asLong)
        assertEquals(3L, body.getAsJsonArray("version")[0].asJsonObject.get("version").asLong)
        assertEquals(listOf("11", "22"), body.getAsJsonArray("displayList").map { it.asString })
        assertEquals(listOf("22", "11"), body.getAsJsonArray("randomList").map { it.asString })
    }

    @Test fun commandAndHeartbeatKeepClampsSequenceAndPlaybackStatus() = runBlocking {
        val owner = sessions.snapshot()
        repository.reportListenTogetherCommand(owner, "room-a", ListenTogetherCommand.GoTo, -1, true, null, 11, 2)
        val command = JsonParser.parseString(eapi.requests.single().body["commandInfo"] as String).asJsonObject
        assertEquals("GOTO", command.get("commandType").asString)
        assertEquals(0L, command.get("progress").asLong)
        assertEquals("PLAY", command.get("playStatus").asString)
        assertEquals("-1", command.get("formerSongId").asString)
        assertEquals("11", command.get("targetSongId").asString)
        assertEquals(2L, command.get("clientSeq").asLong)
        assertEquals(7, repository.sendListenTogetherHeartbeat(owner, "room-a", 11, false, -1))
        assertEquals(mapOf("roomId" to "room-a", "songId" to 11L, "playStatus" to "PAUSE", "progress" to 0L), eapi.requests.last().body)
    }

    @Test fun playbackRetainsRandomListAndCommandFallbacks() = runBlocking {
        eapi.result = { json("""{"code":200,"data":{"playlist":{"playMode":"RANDOM",
            "displayList":{"result":[11]},"randomList":{"result":[22,"11",22]}},
            "playCommand":{"commandType":"GOTO","targetSongId":"22","formerSongId":"11","progress":321,"clientSeq":2,"serverSeq":3}}}""") }
        val playback = repository.listenTogetherPlayback(sessions.snapshot(), "room-a")
        assertEquals(listOf(22L, 11L), playback.songIds)
        assertEquals(22L, playback.command?.targetSongId)
        assertEquals(true, playback.command?.isPlaying)
        assertEquals(321L, playback.command?.progressMs)
        eapi.result = { json("""{"code":200,"data":{"playCommand":{"commandType":"GOTO","playStatus":"PAUSED"}}}""") }
        assertEquals(false, repository.listenTogetherPlayback(sessions.snapshot(), "room-a").command?.isPlaying)
    }

    @Test fun staleGuestsAndRecoveryRejectEveryRouteBeforeDispatch() = runBlocking {
        val old = sessions.snapshot()
        sessions.invalidate()
        operations(old).forEach { assertTrue(runCatching { it() }.exceptionOrNull() is SessionChangedException) }
        identity = SessionIdentity(0, false, true)
        operations(sessions.snapshot()).forEach { assertTrue(runCatching { it() }.isFailure) }
        identity = SessionIdentity(17, true, false)
        sessions.setRecoveryRequired(true)
        operations(sessions.snapshot()).forEach { assertTrue(runCatching { it() }.isFailure) }
        assertTrue(eapi.requests.isEmpty())
        assertTrue(weapi.requests.isEmpty())
    }

    @Test fun lateAcknowledgementsCannotBecomeNewSessionResults() = runBlocking {
        eapi.result = { identity = SessionIdentity(18, true, false); sessions.invalidate(); json("""{"code":200}""") }
        val old = sessions.snapshot()
        assertTrue(runCatching { repository.endListenTogetherRoom(old, "room-a") }.exceptionOrNull() is SessionChangedException)
        assertEquals(old, eapi.requests.single().owner)
        assertEquals("room-a", eapi.requests.single().body["roomId"])
    }

    @Test fun businessFailureRemainsAnActionFailure() = runBlocking {
        eapi.result = { json("""{"code":403,"message":"Room fixture denied"}""") }
        assertEquals("Room fixture denied", runCatching { repository.createListenTogetherRoom(sessions.snapshot()) }.exceptionOrNull()?.message)
    }

    @Test fun cancellationRejectsANonCancellableLateResult() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val reply = CompletableDeferred<JsonObject>()
        eapi.result = { started.complete(Unit); withContext(NonCancellable) { reply.await() } }
        val action = async { repository.acceptListenTogetherRoom(sessions.snapshot(), "room-a", "9") }
        started.await()
        action.cancel()
        reply.complete(json("""{"code":200,"data":{"roomInfo":{"roomId":"room-a","creatorId":"9"}}}"""))
        action.join()
        assertTrue(action.isCancelled)
        assertEquals(1, eapi.requests.size)
    }

    companion object {
        private fun json(value: String) = JsonParser.parseString(value).asJsonObject
    }
}
