package com.ljyh.mei.data.repository

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.ljyh.mei.data.model.melox.RecognizedSong
import com.ljyh.mei.data.network.api.AudioMatchService
import com.ljyh.mei.data.session.SessionStamp
import com.ljyh.mei.data.session.SessionStore
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SongRecognitionRepository @Inject constructor(
    private val service: AudioMatchService,
    private val sessions: SessionStore,
) {
    suspend fun match(fingerprint: String, duration: Int, session: SessionStamp): List<RecognizedSong> {
        require(fingerprint.isNotBlank())
        require(duration in 1..15)
        requireSession(session)
        val response = service.match(duration = duration, fingerprint = fingerprint, expectedSession = session)
        requireSession(session)
        val code = response.primitiveString("code")?.toIntOrNull() ?: 200
        check(code in 200..299) { "NetEase audio match failed ($code)" }
        return response.objectOrNull("data")
            ?.array("result")
            .orEmpty()
            .mapNotNull(::parseCandidate)
            .distinctBy(RecognizedSong::id)
            .also { requireSession(session) }
    }

    private suspend fun requireSession(session: SessionStamp) {
        currentCoroutineContext().ensureActive()
        sessions.requireCurrent(session)
        check(!sessions.recoveryRequired.value) { "Session recovery is required" }
    }

    private fun parseCandidate(element: JsonElement): RecognizedSong? {
        val candidate = element.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return null
        val song = candidate.objectOrNull("song") ?: return null
        val id = song.primitiveString("id")?.toLongOrNull() ?: return null
        val album = song.objectOrNull("al") ?: song.objectOrNull("album")
        val artists = (song.array("ar").ifEmpty { song.array("artists") }).mapNotNull {
            it.takeIf(JsonElement::isJsonObject)?.asJsonObject?.primitiveString("name")
        }
        return RecognizedSong(
            id = id,
            name = song.primitiveString("name") ?: "Unknown song",
            artists = artists,
            album = album?.primitiveString("name") ?: "Unknown album",
            coverUrl = album?.primitiveString("picUrl"),
            durationMs = song.primitiveString("dt")?.toLongOrNull()
                ?: song.primitiveString("duration")?.toLongOrNull()
                ?: 0,
            startTimeMs = candidate.primitiveString("startTime")?.toLongOrNull(),
        )
    }
}

private fun JsonObject.objectOrNull(name: String): JsonObject? =
    get(name)?.takeIf(JsonElement::isJsonObject)?.asJsonObject

private fun JsonObject.array(name: String): List<JsonElement> =
    get(name)?.takeIf(JsonElement::isJsonArray)?.asJsonArray?.toList().orEmpty()

private fun JsonObject.primitiveString(name: String): String? =
    get(name)?.takeUnless(JsonElement::isJsonNull)?.let { runCatching { it.asString }.getOrNull() }
