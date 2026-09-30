package com.ljyh.mei.playback

import com.ljyh.mei.constants.MusicQuality
import com.ljyh.mei.data.model.DownloadSource
import com.ljyh.mei.data.model.DownloadSources
import com.ljyh.mei.data.model.DownloadUrlResponse
import com.ljyh.mei.data.model.api.GetDownloadUrl
import com.ljyh.mei.parasite.HostDownloadApi
import com.ljyh.mei.data.session.SessionStore
import com.ljyh.mei.data.session.SessionChangedException
import com.ljyh.mei.data.session.SessionStamp
import java.io.IOException
import java.net.URI
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Resolves fresh download grants only; playback URLs are never a permission fallback. */
internal suspend fun resolveOfficialDownloadSources(
    api: HostDownloadApi,
    sessions: SessionStore,
    ids: List<String>,
    quality: MusicQuality,
    owner: SessionStamp,
    now: () -> Long = System::currentTimeMillis,
): DownloadSources {
    val requested = ids.map { value ->
        value.trim().toLongOrNull()?.takeIf { it > 0 }?.toString()
            ?: throw IllegalArgumentException("Invalid download song identity")
    }.distinct()
    val sources = mutableListOf<DownloadSource>()
    val rejected = linkedMapOf<String, Int>()
    suspend fun requireOwner() {
        currentCoroutineContext().ensureActive()
        sessions.requirePlaybackSession(owner)
        if (!owner.identity.authenticated || owner.identity.anonymous) throw SessionChangedException()
    }
    requireOwner()
    for (id in requested) {
        requireOwner()
        val dispatchedAt = now()
        // This suffix is the song's cloud owner, not the logged-in account ID.
        val response = api.getDownloadUrl(GetDownloadUrl("${id}_0", quality.text), owner)
        requireOwner()
        if (response.code != 200) throw IOException("Official download request failed (${response.code})")
        val data = response.data ?: throw IOException("Missing official download source")
        val code = data.code ?: throw IOException("Missing official download status")
        if (code != 200) {
            if (code !in setOf(-103, -105, -120, -125, -130, -140, 404)) {
                throw IOException("Official download authorization failed ($code)")
            }
            rejected[id] = code
            continue
        }
        sources += data.validated(id, quality.text, dispatchedAt, now())
    }
    requireOwner()
    if (sources.any { it.expiresAtMs != null && it.expiresAtMs <= now() }) {
        throw IOException("Official download grant expired during batch resolution")
    }
    return DownloadSources(sources, rejected)
}

private fun DownloadUrlResponse.Data.validated(
    expectedId: String,
    requestedLevel: String,
    dispatchedAt: Long,
    now: Long,
): DownloadSource {
    fun requireField(condition: Boolean) {
        if (!condition) throw IOException("Invalid official download source")
    }
    requireField(id?.toString() == expectedId && (freeTrialInfo == null || freeTrialInfo.isJsonNull))
    val address = url.orEmpty()
    val uri = runCatching { URI(address) }.getOrNull()
    requireField(uri != null && uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank() && uri.userInfo == null)
    val format = type.orEmpty().lowercase()
    requireField(format in setOf("mp3", "flac", "aac", "m4a", "ogg", "wav", "opus"))
    requireField((size ?: 0) > 0 && md5.orEmpty().matches(Regex("[a-fA-F0-9]{32}")))
    val expiresAt = expi?.let {
        requireField(it > 0 && it <= (Long.MAX_VALUE - dispatchedAt) / 1_000)
        (dispatchedAt + it * 1_000).also { deadline -> requireField(deadline > now) }
    }
    return DownloadSource(checkNotNull(id), address, format, level.orEmpty(), requestedLevel,
        checkNotNull(size), checkNotNull(md5).lowercase(), expiresAt)
}
