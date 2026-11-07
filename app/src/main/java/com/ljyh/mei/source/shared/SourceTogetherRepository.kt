package com.ljyh.mei.source.shared

import androidx.media3.common.MediaItem
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.ljyh.mei.data.model.melox.ListenTogetherCommand
import com.ljyh.mei.data.model.melox.ListenTogetherRoom
import com.ljyh.mei.data.model.melox.ListenTogetherUser
import com.ljyh.mei.data.model.toMediaItem
import com.ljyh.mei.data.model.toMediaMetadata
import com.ljyh.mei.source.api.*
import com.ljyh.mei.source.core.BoundSourceFeatures
import com.ljyh.mei.source.core.SourceFeatureClient
import com.ljyh.mei.source.core.SourceRouter
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class SourceTogetherCommand(val type: String?, val target: TrackRef?, val former: TrackRef?, val progressMs: Long,
    val isPlaying: Boolean?, val clientSequence: Long, val serverSequence: Long)
data class SourceTogetherPlayback(val tracks: List<TrackRef>, val playMode: String?, val command: SourceTogetherCommand?)
data class SourceTogetherInvitation(val roomId: String, val inviterId: String, val song: TrackRef?)
data class SourceTogetherStatus(val inRoom: Boolean, val room: ListenTogetherRoom?)

@Singleton
class SourceTogetherRepository @Inject constructor(private val client: SourceFeatureClient, private val router: SourceRouter) {
    suspend fun open(providerId: String?): SourceTogetherSession {
        val scopeId = "together:${UUID.randomUUID()}"
        val owner = if (providerId == null) client.current(scopeId) else client.bind(providerId, scopeId)
        if (!owner.supports("together", "listenTogetherStatus")) throw SourceException(SourceErrorCode.UNSUPPORTED)
        val account = router.account(owner.providerId) ?: throw SourceException(SourceErrorCode.AUTH_REQUIRED)
        owner.ensureCurrent()
        return SourceTogetherSession(owner, account.ref.accountId.value, router)
    }
}
class SourceTogetherSession internal constructor(val owner: BoundSourceFeatures, val userId: String, private val router: SourceRouter) {
    val providerId get() = owner.providerId
    private suspend fun invoke(action: String, parameters: com.google.gson.JsonObject = featureParameters {}) = owner.invoke("together", action, parameters)
    suspend fun status(): SourceTogetherStatus {
        val value = invoke("listenTogetherStatus").featureObject()
        return SourceTogetherStatus(value.featureBoolean("isInRoom"), value.get("room")?.takeUnless { it.isJsonNull }?.let(::parseTogetherRoom))
    }
    suspend fun create(): ListenTogetherRoom = parseTogetherRoom(invoke("createListenTogetherRoom"))
    suspend fun join(roomId: String, inviterId: String): ListenTogetherRoom {
        val check = invoke("checkListenTogetherRoom", featureParameters { addProperty("roomId", roomId) }).featureObject()
        if (!check.featureBoolean("first")) throw SourceException(SourceErrorCode.UNAVAILABLE, check.featureText("second", true, 1000) ?: "Room is unavailable")
        return parseTogetherRoom(invoke("acceptListenTogetherRoom", featureParameters { addProperty("roomId", roomId); addProperty("inviterId", inviterId) }))
    }
    suspend fun invitation(text: String): SourceTogetherInvitation? {
        val result = invoke("parseInvitation", featureParameters { addProperty("text", text) })
        if (result.isJsonNull) return null
        val value = result.featureObject()
        return SourceTogetherInvitation(value.featureText("roomId")!!, value.featureText("inviterId")!!,
            value.featureText("songId", true)?.let { TrackRef(providerId, it) })
    }
    suspend fun invitationUrl(roomId: String, song: TrackRef): String {
        requireOwned(song)
        val result = invoke("invitationLink", featureParameters { addProperty("roomId", roomId); addProperty("inviterId", userId); addProperty("songId", song.trackId) })
        if (!result.isJsonPrimitive || !result.asJsonPrimitive.isString || !result.asString.startsWith("https://") || result.asString.length > 8192) invalidFeature()
        return result.asString
    }
    suspend fun playback(roomId: String): SourceTogetherPlayback = parseTogetherPlayback(providerId, invoke("listenTogetherPlayback", featureParameters { addProperty("roomId", roomId) }))
    suspend fun media(refs: List<TrackRef>): List<MediaItem> {
        refs.forEach(::requireOwned); owner.ensureCurrent()
        val tracks = owner.whileCurrent { router.getTracks(refs) }
        owner.ensureCurrent()
        if (tracks.map { it.ref } != refs) throw SourceException(SourceErrorCode.NOT_FOUND)
        return tracks.map { it.toMediaMetadata().toMediaItem() }
    }
    suspend fun reportPlaylist(roomId: String, version: Long, refs: List<TrackRef>) {
        refs.forEach(::requireOwned)
        owner.requireTrue("together", "reportListenTogetherPlaylist", featureParameters {
            addProperty("roomId", roomId); addProperty("userId", userId); addProperty("version", version)
            add("displaySongIds", JsonArray().apply { refs.forEach { add(it.trackId) } }); add("randomSongIds", JsonArray().apply { refs.forEach { add(it.trackId) } })
        })
    }
    suspend fun command(roomId: String, command: ListenTogetherCommand, former: TrackRef?, target: TrackRef, sequence: Long, progress: Long, playing: Boolean) {
        requireOwned(target); former?.let(::requireOwned)
        owner.requireTrue("together", "reportListenTogetherCommand", featureParameters {
            addProperty("roomId", roomId); addProperty("command", command.name); addProperty("formerSongId", former?.trackId)
            addProperty("targetSongId", target.trackId); addProperty("clientSequence", sequence); addProperty("progressMs", progress); addProperty("isPlaying", playing)
        })
    }
    suspend fun heartbeat(roomId: String, song: TrackRef, playing: Boolean, progress: Long) {
        requireOwned(song)
        val result = invoke("sendListenTogetherHeartbeat", featureParameters {
            addProperty("roomId", roomId); addProperty("songId", song.trackId); addProperty("isPlaying", playing); addProperty("progressMs", progress)
        })
        if (!result.isJsonNull && (!result.isJsonPrimitive || result.asString.toLongOrNull()?.let { it >= 0 } != true)) invalidFeature()
    }
    suspend fun end(roomId: String) = owner.requireTrue("together", "endListenTogetherRoom", featureParameters { addProperty("roomId", roomId) })
    private fun requireOwned(ref: TrackRef) { if (ref.providerId != providerId) throw SourceException(SourceErrorCode.UNSUPPORTED, "This room cannot synchronize tracks from another source") }
}
internal fun parseTogetherRoom(element: JsonElement): ListenTogetherRoom {
    val row = element.featureObject()
    return ListenTogetherRoom(row.featureText("id")!!, row.featureText("creatorId")!!,
        row.featureList("users", 100).map { user -> user.featureObject().let { ListenTogetherUser(it.featureText("id")!!, it.featureText("nickname", maximum = 1000)!!, it.featureText("avatarUrl", true, 8192)) } },
        row.get("createTime")?.takeUnless { it.isJsonNull }?.let { row.featureLong("createTime") },
        row.get("effectiveDurationMs")?.takeUnless { it.isJsonNull }?.let { row.featureLong("effectiveDurationMs") })
}
internal fun parseTogetherPlayback(providerId: String, element: JsonElement): SourceTogetherPlayback {
    val row = element.featureObject()
    fun ref(value: String?) = value?.let { TrackRef(providerId, it) }
    val command = row.get("command")?.takeUnless { it.isJsonNull }?.featureObject()?.let { value ->
        SourceTogetherCommand(value.featureText("commandType", true, 100), ref(value.featureText("targetSongId", true)), ref(value.featureText("formerSongId", true)),
            value.featureLong("progressMs"), value.get("isPlaying")?.takeUnless { it.isJsonNull }?.let { value.featureBoolean("isPlaying") },
            value.featureLong("clientSequence"), value.featureLong("serverSequence"))
    }
    return SourceTogetherPlayback(row.featureList("songIds", 1000).map { if (!it.isJsonPrimitive || !it.asJsonPrimitive.isString || it.asString.isBlank() || it.asString.length > 2048) invalidFeature() else TrackRef(providerId, it.asString) },
        row.featureText("playMode", true, 100), command)
}
