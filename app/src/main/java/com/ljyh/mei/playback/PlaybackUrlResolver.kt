package com.ljyh.mei.playback

import com.ljyh.mei.data.model.api.GetSongUrlV1
import com.ljyh.mei.data.network.api.ApiService
import com.ljyh.mei.parasite.HostSessionBridge
import com.ljyh.mei.parasite.HostSessionChangedException
import com.ljyh.mei.parasite.HostSessionStamp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

internal fun HostSessionBridge.requirePlaybackSession(owner: HostSessionStamp) {
    requireCurrent(owner)
    if (recoveryRequired.value) throw HostSessionChangedException()
}

internal data class PlaybackUrl(val url: String, val actualQuality: String, val cacheKey: String)

/** Signed URLs belong to one official authorization generation, never just a song ID. */
@Singleton
class PlaybackUrlResolver internal constructor(
    private val api: ApiService,
    private val sessions: HostSessionBridge,
    private val now: () -> Long,
) {
    @Inject constructor(api: ApiService, sessions: HostSessionBridge) : this(api, sessions, System::currentTimeMillis)

    private data class Key(val mediaId: String, val quality: String, val owner: HostSessionStamp)
    private data class Entry(val source: PlaybackUrl, val expiresAt: Long)
    private val cache = ConcurrentHashMap<Key, Entry>()
    private val invalidation = sessions.onInvalidated { cache.clear() }

    internal suspend fun resolve(mediaId: String, quality: String, owner: HostSessionStamp): PlaybackUrl {
        require((mediaId.toLongOrNull() ?: 0) > 0) { "Invalid playback song identity" }
        val requested = normalizePlaybackQuality(quality)
        for (attempted in playbackQualityFallbacks(requested)) {
            currentCoroutineContext().ensureActive()
            sessions.requirePlaybackSession(owner)
            val key = Key(mediaId, attempted, owner)
            cache[key]?.let { cached ->
                if (cached.expiresAt > now()) {
                    sessions.requirePlaybackSession(owner)
                    return cached.source
                }
                cache.remove(key, cached)
            }
            val started = now()
            val response = try {
                api.getSongUrlV1(GetSongUrlV1("[$mediaId]", attempted), owner)
            } catch (error: CancellationException) { throw error }
            catch (error: HostSessionChangedException) { throw error }
            catch (error: Exception) { throw IOException("Official playback URL request failed", error) }
            currentCoroutineContext().ensureActive()
            sessions.requirePlaybackSession(owner)
            if (response.code != 200) throw IOException("Song URL API returned code ${response.code}")
            if (response.data == null) throw IOException("Missing official playback sources")
            val source = response.fullSourceFor(mediaId) ?: continue
            val actual = effectivePlaybackQuality(source.level, attempted)
            val resolved = PlaybackUrl(
                checkNotNull(source.url), actual,
                playbackCacheKey(mediaId, actual, source.md5, source.size.toLong(), owner.identity),
            )
            val ttl = source.expi?.coerceAtLeast(0)?.toLong()?.times(1_000) ?: 300_000L
            val entry = Entry(resolved, started + (ttl - 30_000L).coerceAtLeast(0))
            return sessions.withCurrent(owner) {
                if (sessions.recoveryRequired.value) throw HostSessionChangedException()
                for (level in setOf(requested, attempted, actual)) cache[Key(mediaId, level, owner)] = entry
                resolved
            }
        }
        throw SourceNotFoundException("No playable full source for $mediaId at quality $requested")
    }

    fun invalidate(mediaId: String) { cache.keys.removeAll { it.mediaId == mediaId.trim() } }
}
