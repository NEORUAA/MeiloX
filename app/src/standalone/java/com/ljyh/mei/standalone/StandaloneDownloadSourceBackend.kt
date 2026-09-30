package com.ljyh.mei.standalone

import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName
import com.ljyh.mei.constants.MusicQuality
import com.ljyh.mei.data.model.DownloadSource
import com.ljyh.mei.data.model.DownloadSources
import com.ljyh.mei.data.model.SongSourceIdentity
import com.ljyh.mei.data.model.api.GetSongUrlV1
import com.ljyh.mei.data.session.SessionStamp
import com.ljyh.mei.data.session.SessionStore
import com.ljyh.mei.playback.DownloadSourceBackend
import com.ljyh.mei.playback.playbackQualityFallbacks
import com.ljyh.mei.playback.requireDownloadOwner
import java.io.IOException
import java.net.URI
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Tag

internal class StandaloneDownloadSourceBackend @Inject constructor(
    retrofit: Retrofit,
    private val sessions: SessionStore,
) : DownloadSourceBackend {
    private val api = retrofit.create(StandaloneDownloadApi::class.java)

    override suspend fun resolve(ids: List<String>, quality: MusicQuality, owner: SessionStamp) =
        resolveStandaloneDownloadSources(api, sessions, ids, quality, owner)
}

internal suspend fun resolveStandaloneDownloadSources(
    api: StandaloneDownloadApi,
    sessions: SessionStore,
    ids: List<String>,
    quality: MusicQuality,
    owner: SessionStamp,
    now: () -> Long = System::currentTimeMillis,
): DownloadSources {
    val requested = ids.map(SongSourceIdentity::fromKey).distinct()
    require(requested.map { it.entryId }.distinct().size == requested.size) { "Conflicting download identities" }
    requested.forEach { it.requireAccount(owner.identity) }
    val found = linkedMapOf<String, DownloadSource>()
    val rejected = linkedMapOf<String, Int>()
    suspend fun requireOwner() {
        currentCoroutineContext().ensureActive()
        sessions.requireDownloadOwner(owner)
    }
    requireOwner()
    for (level in playbackQualityFallbacks(quality.text)) {
        val missing = requested.filterNot { it.entryId.toString() in found }
        if (missing.isEmpty()) break
        // Responses only echo audio IDs, so distinct cloud owners must not share one batch.
        val batches = missing.filterNot { it.isCloud }.chunked(200) + missing.filter { it.isCloud }.map(::listOf)
        for (batch in batches) {
            requireOwner()
            val dispatchedAt = now()
            val response = api.sources(GetSongUrlV1(SongSourceIdentity.playerIds(batch), level), owner)
            requireOwner()
            if (response.code != 200) throw IOException("Standalone player request failed")
            val rows = response.data ?: throw IOException("Missing standalone player sources")
            val byId = linkedMapOf<String, StandalonePlayerSources.Data>()
            for (row in rows) {
                val id = row?.id?.toString() ?: throw IOException("Missing standalone player identity")
                if (batch.none { it.songId.toString() == id } || byId.put(id, row) != null) throw IOException("Unexpected standalone player identity")
            }
            if (byId.keys != batch.map { it.songId.toString() }.toSet()) throw IOException("Incomplete standalone player sources")
            for (identity in batch) {
                val id = identity.entryId.toString()
                val row = checkNotNull(byId[identity.songId.toString()])
                val code = row.code ?: throw IOException("Missing standalone player status")
                if (code != 200 && code !in setOf(-103, -105, -120, -125, -130, -140, 404)) {
                    throw IOException("Standalone player authorization failed")
                }
                val trial = row.freeTrialInfo?.let { !it.isJsonNull } == true
                if (code != 200 || row.url.isNullOrBlank() || trial) {
                    rejected[id] = code
                    continue
                }
                found[id] = row.validated(identity.songId.toString(), quality.text, dispatchedAt, now()).copy(id = identity.entryId)
                rejected.remove(id)
            }
        }
    }
    requireOwner()
    if (found.values.any { it.expiresAtMs?.let { deadline -> deadline <= now() } == true }) {
        throw IOException("Standalone source expired during resolution")
    }
    return DownloadSources(requested.mapNotNull { found[it.entryId.toString()] }, rejected)
}

private fun StandalonePlayerSources.Data.validated(
    expectedId: String, requestedLevel: String, dispatchedAt: Long, now: Long,
): DownloadSource {
    fun requireField(valid: Boolean) {
        if (!valid) throw IOException("Invalid standalone download source")
    }
    requireField(id?.toString() == expectedId)
    val address = url.orEmpty()
    val uri = runCatching { URI(address) }.getOrNull()
    requireField(uri != null && uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank() && uri.userInfo == null)
    val format = type.orEmpty().lowercase(Locale.ROOT)
    requireField(format in setOf("mp3", "flac", "aac", "m4a", "ogg", "wav", "opus"))
    requireField((size ?: 0) > 0 && md5.orEmpty().matches(Regex("[a-fA-F0-9]{32}")))
    val deadline = expi?.let {
        requireField(it > 0 && it <= (Long.MAX_VALUE - dispatchedAt) / 1_000)
        (dispatchedAt + it * 1_000).also { value -> requireField(value > now) }
    }
    return DownloadSource(checkNotNull(id), address, format, level.orEmpty(), requestedLevel,
        checkNotNull(size), checkNotNull(md5).lowercase(Locale.ROOT), deadline)
}

internal interface StandaloneDownloadApi {
    @Headers("X-Netease-Crypto: eapi")
    @POST("/api/song/enhance/player/url/v1")
    suspend fun sources(@Body body: GetSongUrlV1, @Tag owner: SessionStamp): StandalonePlayerSources
}

internal data class StandalonePlayerSources(
    @SerializedName("code") val code: Int?,
    @SerializedName("data") val data: List<Data?>?,
) {
    data class Data(
        @SerializedName("id") val id: Long?,
        @SerializedName("code") val code: Int?,
        @SerializedName("url") val url: String?,
        @SerializedName("type") val type: String?,
        @SerializedName("level") val level: String?,
        @SerializedName("size") val size: Long?,
        @SerializedName("md5") val md5: String?,
        @SerializedName("expi") val expi: Long?,
        @SerializedName("freeTrialInfo") val freeTrialInfo: JsonElement?,
    )
}
