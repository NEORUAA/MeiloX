package com.ljyh.mei.playback

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.ljyh.mei.data.model.room.HistoryItem
import com.ljyh.mei.data.model.room.SourceType
import com.ljyh.mei.di.repository.HistoryRepository
import com.ljyh.mei.source.api.*
import com.ljyh.mei.source.core.BoundSourceFeatures
import com.ljyh.mei.source.core.SourceFeatureClient
import com.ljyh.mei.source.core.SourceRouter
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** Queue algorithms consume typed source results, never a platform's HTTP DTOs. */
interface QueueSourceActions {
    suspend fun open(providerId: String? = null): QueueSourceSession
    suspend fun tracks(refs: List<TrackRef>): List<Track>
    fun canBatchTracks(providerId: String): Boolean = false
}
interface QueueSourceSession {
    val providerId: String
    suspend fun radio(parameters: Map<String, String>, similar: Boolean): List<Track>
    suspend fun scenes(): List<PrivateFmScene>
    suspend fun canonicalShuffleIds(playlistId: String, collection: LibraryCollection?): List<TrackRef>? = null
    suspend fun shuffle(playlistId: String, songId: String, algorithm: String, collection: LibraryCollection? = null): SourceShufflePage
    suspend fun shuffle(playlistId: String, songId: String, algorithm: String, collection: LibraryCollection?,
        canonicalIds: List<TrackRef>?): SourceShufflePage = shuffle(playlistId, songId, algorithm, collection)
    suspend fun ensureCurrent()
}
data class SourceShufflePage(val order: List<TrackRef>, val tracks: List<Track>, val unavailable: Set<TrackRef>,
    val canonicalOrder: List<TrackRef>? = null)

@Singleton
class SourcePlaybackFeatures @Inject constructor(
    private val client: SourceFeatureClient,
    private val router: SourceRouter,
    private val history: HistoryRepository,
) : QueueSourceActions {
    override suspend fun open(providerId: String?): QueueSourceSession {
        val scope = "queue:${UUID.randomUUID()}"
        return FeatureQueueSession(if (providerId == null) client.current(scope) else client.bind(providerId, scope), history)
    }
    override suspend fun tracks(refs: List<TrackRef>): List<Track> = router.getTracks(refs)
    override fun canBatchTracks(providerId: String): Boolean = router.registry.provider(providerId)?.let {
        it.status == SourceStatus.READY && (it as? BatchCatalogCapability)?.supportsBatchTrackRequests == true
    } == true
}

private class FeatureQueueSession(private val owner: BoundSourceFeatures, private val history: HistoryRepository) : QueueSourceSession {
    private var resolvedShufflePlaylistId: String? = null
    private var officialShuffleTimeoutMs: Long? = null
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

    override suspend fun canonicalShuffleIds(playlistId: String, collection: LibraryCollection?): List<TrackRef>? {
        if (!owner.supports("discovery", "shuffleInput")) return null
        val result = owner.invoke("discovery", "shuffleInput", JsonObject().apply {
            addProperty("playlistId", playlistId)
            collection?.let { addProperty("collection", it.name) }
        }).objectValue()
        val rows = result.get("ids")?.takeIf { it.isJsonArray }?.asJsonArray ?: invalid()
        val refs = rows.map { value ->
            if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) invalid()
            TrackRef(providerId, value.asString.also { if (it.isBlank() || it.length > 2048) invalid() })
        }.distinct()
        resolvedShufflePlaylistId = result.optionalText("playlistId", 2048).ifBlank { playlistId }
        officialShuffleTimeoutMs = result.get("executionTimeoutMs")?.let { value ->
            if (!value.isJsonPrimitive || !value.asJsonPrimitive.isNumber || !value.asString.matches(Regex("[0-9]+"))) invalid()
            value.asLong.takeIf { it in 1..20_000 } ?: invalid()
        }
        owner.ensureCurrent()
        return refs
    }

    override suspend fun shuffle(playlistId: String, songId: String, algorithm: String, collection: LibraryCollection?) =
        shuffle(playlistId, songId, algorithm, collection, null)

    override suspend fun shuffle(playlistId: String, songId: String, algorithm: String, collection: LibraryCollection?,
        canonicalIds: List<TrackRef>?): SourceShufflePage {
        try {
            val result = owner.invoke("discovery", "cloudShuffle", JsonObject().apply {
                addProperty("playlistId", playlistId); addProperty("songId", songId); addProperty("alg", algorithm)
                collection?.let { addProperty("collection", it.name) }
            }).objectValue()
            val page = parseSourceShufflePage(result, providerId, owner::track)
            owner.ensureCurrent()
            if (page.order.isNotEmpty()) {
                Timber.tag("SourceShuffle").d("route=server count=%d", page.order.size)
                return page
            }
            throw SourceException(SourceErrorCode.UNAVAILABLE)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            owner.ensureCurrent()
            if (error is SourceException && error.code in setOf(SourceErrorCode.AUTH_REQUIRED, SourceErrorCode.AUTH_EXPIRED)) throw error
            if (!owner.supports("discovery", "shuffleInput") || !owner.supports("discovery", "officialShuffle")) throw error
        }
        val ids = canonicalIds ?: canonicalShuffleIds(playlistId, collection) ?: throw SourceException(SourceErrorCode.UNAVAILABLE)
        return officialShuffle(playlistId, songId, algorithm, collection, ids)
    }

    private suspend fun officialShuffle(playlistId: String, songId: String, algorithm: String, collection: LibraryCollection?,
        ids: List<TrackRef>): SourceShufflePage {
        if (ids.isEmpty() || ids.any { it.providerId != providerId }) invalid()
        owner.ensureCurrent()
        val listeningHistory = withContext(Dispatchers.IO) {
            sourceShuffleHistory(history.getHistory(), providerId, System.currentTimeMillis())
        }
        owner.ensureCurrent()
        val parameters = JsonObject().apply {
            addProperty("playlistId", resolvedShufflePlaylistId ?: playlistId)
            addProperty("songId", songId); addProperty("alg", algorithm)
            collection?.let { addProperty("collection", it.name) }
            add("ids", JsonArray().apply { ids.forEach { add(it.trackId) } })
            addProperty("playingId", songId)
            add("history", listeningHistory)
            addProperty("timeBias", 0)
            addProperty("styleBias", 0)
        }
        val result = try {
            val timeout = officialShuffleTimeoutMs
            if (timeout == null) owner.invoke("discovery", "officialShuffle", parameters).objectValue()
            else withTimeout(timeout) { owner.invoke("discovery", "officialShuffle", parameters).objectValue() }
        } catch (timeout: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw SourceException(SourceErrorCode.TIMEOUT)
        }
        return parseSourceShufflePage(result, providerId, owner::track).copy(canonicalOrder = ids.toList()).also {
            owner.ensureCurrent()
            if (it.order.isEmpty()) throw SourceException(SourceErrorCode.UNAVAILABLE)
            Timber.tag("SourceShuffle").d("route=official count=%d", it.order.size)
        }
    }
}

/** Local playback starts belong to a source, without claiming an account-specific history. */
internal fun sourceShuffleHistory(history: List<HistoryItem>, providerId: String, nowMs: Long): JsonArray {
    val cutoff = nowMs - 2L * 365 * 86_400_000
    val records = history.asSequence().filter {
        it.song.sourceType != SourceType.LOCAL && it.song.providerId == providerId &&
            it.playedAt >= cutoff && it.playedAt <= nowMs
    }.groupBy { it.song.providerTrackId }
    return JsonArray().apply {
        records.entries.sortedByDescending { (_, rows) -> rows.maxOf { it.playedAt } }.take(10_000)
            .forEach { (id, rows) ->
            add(JsonObject().apply {
                addProperty("id", id)
                addProperty("play_times", rows.size)
                addProperty("timestamp", rows.maxOf { it.playedAt })
                // Room stores song duration, rather than the duration listened to in a session.
                addProperty("duration", 0)
            })
        }
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
