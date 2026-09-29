package com.ljyh.mei.data.repository

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.ljyh.mei.data.model.melox.CloudMusicPage
import com.ljyh.mei.data.model.melox.CloudSong
import com.ljyh.mei.data.network.api.MeloXDirectService
import com.ljyh.mei.data.session.SessionStamp
import com.ljyh.mei.data.session.SessionStore
import javax.inject.Inject
import javax.inject.Named
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Supplemental business routes; the selected runtime owns authentication and transport. */
class CloudLibraryBackend @Inject constructor(
    @param:Named("MeloXWeapi") private val api: MeloXDirectService,
    private val sessions: SessionStore,
) {
    suspend fun songs(owner: SessionStamp): CloudMusicPage {
        val songs = mutableListOf<CloudSong>()
        val ids = mutableSetOf<Long>()
        var expectedCount: Int? = null
        while (true) {
            val response = request(owner, "/api/v1/cloud/get", mapOf("offset" to songs.size, "limit" to 200))
            val count = response.integer("count")?.takeIf { it in 0..Int.MAX_VALUE }?.toInt()
                ?: error("Missing cloud library count")
            check(expectedCount == null || expectedCount == count) { "Cloud library changed while loading" }
            expectedCount = count
            val data = response.get("data")?.takeIf(JsonElement::isJsonArray)?.asJsonArray
                ?: error("Missing cloud library data")
            check(data.size() <= 200 && data.size().toLong() + songs.size <= count) { "Invalid cloud library page" }
            val used = response.integer("size")?.takeIf { it >= 0 } ?: error("Missing cloud storage usage")
            val maximum = response.integer("maxSize")?.takeIf { it >= 0 } ?: error("Missing cloud storage capacity")
            for (row in data) {
                val song = parseSong(row)
                check(ids.add(song.id)) { "Cloud library pagination repeated a song" }
                songs += song
            }
            val more = if (response.has("hasMore")) {
                response.get("hasMore").takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
                    ?: error("Invalid cloud library continuation")
            } else songs.size < count
            check(more == (songs.size < count) && (!more || data.size() > 0)) { "Incomplete cloud library page" }
            if (!more) {
                currentCoroutineContext().ensureActive()
                sessions.requireCurrent(owner)
                return CloudMusicPage(songs.toList(), count, used, maximum, false)
            }
        }
    }

    suspend fun delete(owner: SessionStamp, id: Long) {
        require(id > 0) { "Invalid cloud song identity" }
        request(owner, "/api/cloud/del", mapOf("songIds" to listOf(id)))
    }

    private suspend fun request(owner: SessionStamp, path: String, body: Map<String, Any>): JsonObject {
        check(owner.identity.authenticated && !owner.identity.anonymous && owner.identity.userId > 0) { "Sign-in required" }
        currentCoroutineContext().ensureActive()
        sessions.requireCurrent(owner)
        check(!sessions.recoveryRequired.value) { "Session recovery is required" }
        val response = api.post(path, body, expectedSession = owner)
        currentCoroutineContext().ensureActive()
        sessions.requireCurrent(owner)
        check(!sessions.recoveryRequired.value) { "Session recovery is required" }
        check(response.integer("code") == 200L) { "Cloud request was not accepted" }
        return response
    }
}

private fun parseSong(element: JsonElement): CloudSong {
    val value = element.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: error("Invalid cloud library entry")
    // A catalog simpleSong ID is not evidence of the cloud entry's deletion identity.
    val id = value.integer("songId")?.takeIf { it > 0 } ?: error("Missing cloud song identity")
    val simple = value.objectValue("simpleSong")
    val album = simple?.objectValue("al") ?: simple?.objectValue("album")
    val artists = sequenceOf("ar", "artists").mapNotNull { key ->
        simple?.get(key)?.takeIf(JsonElement::isJsonArray)?.asJsonArray?.takeIf { it.size() > 0 }
    }.firstOrNull()?.mapNotNull { it.takeIf(JsonElement::isJsonObject)?.asJsonObject?.text("name") }
    return CloudSong(
        id = id,
        name = value.text("songName") ?: simple?.text("name") ?: "Unknown song",
        artist = value.text("artist") ?: artists?.joinToString(" / ").orEmpty(),
        album = value.text("album") ?: album?.text("name") ?: "Unknown album",
        coverUrl = album?.text("picUrl"),
        durationMs = (simple?.integer("dt") ?: simple?.integer("duration") ?: 0).coerceAtLeast(0),
        fileSize = (value.integer("fileSize") ?: 0).coerceAtLeast(0),
        bitrate = (value.integer("bitrate") ?: 0).coerceIn(0, Int.MAX_VALUE.toLong()).toInt(),
        addTime = (value.integer("addTime") ?: 0).coerceAtLeast(0),
    )
}

private fun JsonObject.integer(key: String): Long? = get(key)?.takeIf(JsonElement::isJsonPrimitive)?.asString?.toLongOrNull()
private fun JsonObject.text(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
    ?.asString?.takeIf(String::isNotBlank)
private fun JsonObject.objectValue(key: String): JsonObject? = get(key)?.takeIf(JsonElement::isJsonObject)?.asJsonObject
