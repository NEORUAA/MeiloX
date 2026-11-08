package com.ljyh.mei.playback

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.ljyh.mei.source.api.*
import com.ljyh.mei.source.core.BoundSourceFeatures
import com.ljyh.mei.source.core.SourceFeatureClient
import com.ljyh.mei.source.core.SourceRouter
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Queue algorithms consume typed source results, never a platform's HTTP DTOs. */
interface QueueSourceActions {
    suspend fun open(providerId: String? = null): QueueSourceSession
    suspend fun tracks(refs: List<TrackRef>): List<Track>
}
interface QueueSourceSession {
    val providerId: String
    suspend fun radio(parameters: Map<String, String>, similar: Boolean): List<Track>
    suspend fun scenes(): List<PrivateFmScene>
    suspend fun shuffle(playlistId: String, songId: String, algorithm: String, collection: LibraryCollection? = null): SourceShufflePage
    suspend fun ensureCurrent()
}
data class SourceShufflePage(val order: List<TrackRef>, val tracks: List<Track>, val unavailable: Set<TrackRef>)

@Singleton
class SourcePlaybackFeatures @Inject constructor(
    private val client: SourceFeatureClient,
    private val router: SourceRouter,
) : QueueSourceActions {
    override suspend fun open(providerId: String?): QueueSourceSession {
        val scope = "queue:${UUID.randomUUID()}"
        return FeatureQueueSession(if (providerId == null) client.current(scope) else client.bind(providerId, scope))
    }
    override suspend fun tracks(refs: List<TrackRef>): List<Track> = router.getTracks(refs)
}

private class FeatureQueueSession(private val owner: BoundSourceFeatures) : QueueSourceSession {
    override val providerId: String get() = owner.providerId
    override suspend fun ensureCurrent() = owner.ensureCurrent()
    override suspend fun radio(parameters: Map<String, String>, similar: Boolean): List<Track> {
        val result = owner.invoke("discovery", "personalRadio", JsonObject().apply {
            parameters.forEach { (key, value) -> addProperty(key, value) }
            addProperty("similar", similar)
        }).objectValue()
        return result.arrayValue("tracks", 100).map { owner.track(it.objectValue()) }.also { owner.ensureCurrent() }
    }
    override suspend fun scenes(): List<PrivateFmScene> = owner.invoke("discovery", "privateFmModes").objectValue()
        .arrayValue("scenes", 100).map { value ->
            val row = value.objectValue()
            PrivateFmScene(row.requiredText("code", 1000), row.requiredText("title", 1000), row.optionalText("cascadeMode", 4000))
        }.distinctBy { it.code }

    override suspend fun shuffle(playlistId: String, songId: String, algorithm: String, collection: LibraryCollection?): SourceShufflePage {
        val result = owner.invoke("discovery", "cloudShuffle", JsonObject().apply {
            addProperty("playlistId", playlistId); addProperty("songId", songId); addProperty("alg", algorithm)
            collection?.let { addProperty("collection", it.name) }
        }).objectValue()
        return parseSourceShufflePage(result, providerId, owner::track).also { owner.ensureCurrent() }
    }
}

internal suspend fun parseSourceShufflePage(
    result: JsonObject,
    providerId: String,
    parseTrack: suspend (JsonObject) -> Track,
): SourceShufflePage {
    // The feature boundary bounds the payload; playlist length is not a page-size limit.
    fun rows(key: String): JsonArray = result.get(key)?.takeIf { it.isJsonArray }?.asJsonArray ?: invalid()
    fun refs(key: String): List<TrackRef> = rows(key).map { value ->
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) invalid()
        TrackRef(providerId, value.asString.also { if (it.isBlank() || it.length > 2048) invalid() })
    }
    return SourceShufflePage(
        refs("order").distinct(),
        rows("tracks").map { parseTrack(it.objectValue()) },
        refs("unavailable").toSet(),
    )
}

private fun com.google.gson.JsonElement.objectValue(): JsonObject = takeIf { isJsonObject }?.asJsonObject ?: invalid()
private fun JsonObject.arrayValue(key: String, maximum: Int): JsonArray = get(key)?.takeIf {
    it.isJsonArray && it.asJsonArray.size() <= maximum
}?.asJsonArray ?: invalid()
private fun JsonObject.requiredText(key: String, maximum: Int): String = get(key)?.takeIf {
    it.isJsonPrimitive && it.asJsonPrimitive.isString
}?.asString?.takeIf { it.isNotBlank() && it.length <= maximum } ?: invalid()
private fun JsonObject.optionalText(key: String, maximum: Int): String = get(key)?.takeUnless { it.isJsonNull }?.let {
    if (!it.isJsonPrimitive || !it.asJsonPrimitive.isString || it.asString.length > maximum) invalid()
    it.asString
}.orEmpty()
private fun invalid(): Nothing = throw SourceException(SourceErrorCode.INVALID_RESPONSE)
