package com.ljyh.mei.playback

import com.ljyh.mei.data.model.api.GetSongUrlV1
import com.ljyh.mei.data.model.SongSourceIdentity
import com.ljyh.mei.data.network.api.ApiService
import com.ljyh.mei.data.session.SessionStore
import com.ljyh.mei.data.session.SessionChangedException
import com.ljyh.mei.data.session.SessionStamp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

internal fun SessionStore.requirePlaybackSession(owner: SessionStamp) {
    requireCurrent(owner)
    if (recoveryRequired.value) throw SessionChangedException()
}

internal data class PlaybackUrl(
    val url: String,
    val actualQuality: String,
    val cacheKey: String,
    val sourceMd5: String?,
    val sourceSize: Long,
)

/** Signed URLs belong to one official authorization generation, never just a song ID. */
@Singleton
class PlaybackUrlResolver internal constructor(
    private val api: ApiService,
    private val sessions: SessionStore,
    private val now: () -> Long,
) {
    @Inject constructor(api: ApiService, sessions: SessionStore) : this(api, sessions, System::currentTimeMillis)

    private data class Key(val mediaId: String, val quality: String, val owner: SessionStamp)
    private data class Entry(val source: PlaybackUrl, val expiresAt: Long)
    private val cache = ConcurrentHashMap<Key, Entry>()
    private val invalidation = sessions.onInvalidated { cache.clear() }

    internal suspend fun resolve(mediaId: String, quality: String, owner: SessionStamp): PlaybackUrl {
        val identity = SongSourceIdentity.fromKey(mediaId)
        identity.requireAccount(owner.identity)
        val sourceKey = identity.key
        val requested = normalizePlaybackQuality(quality)
        for (attempted in playbackQualityFallbacks(requested)) {
            currentCoroutineContext().ensureActive()
            sessions.requirePlaybackSession(owner)
            val key = Key(sourceKey, attempted, owner)
            cache[key]?.let { cached ->
                if (cached.expiresAt > now()) {
                    sessions.requirePlaybackSession(owner)
                    return cached.source
                }
                cache.remove(key, cached)
            }
            val started = now()
            val response = try {
                api.getSongUrlV1(GetSongUrlV1(SongSourceIdentity.playerIds(listOf(identity)), attempted), owner)
            } catch (error: CancellationException) { throw error }
            catch (error: SessionChangedException) { throw error }
            catch (error: Exception) { throw IOException("Official playback URL request failed", error) }
            currentCoroutineContext().ensureActive()
            sessions.requirePlaybackSession(owner)
            if (response.code != 200) throw IOException("Song URL API returned code ${response.code}")
            if (response.data == null) throw IOException("Missing official playback sources")
            val source = response.fullSourceFor(identity.songId.toString()) ?: continue
            val actual = effectivePlaybackQuality(source.level, attempted)
            val resolved = PlaybackUrl(
                checkNotNull(source.url), actual,
                playbackCacheKey(sourceKey, actual, source.md5, source.size.toLong(), owner.identity),
                source.md5, source.size.toLong(),
            )
            val ttl = source.expi?.coerceAtLeast(0)?.toLong()?.times(1_000) ?: 300_000L
            val entry = Entry(resolved, started + (ttl - 30_000L).coerceAtLeast(0))
            return sessions.withCurrent(owner) {
                if (sessions.recoveryRequired.value) throw SessionChangedException()
                for (level in setOf(requested, attempted, actual)) cache[Key(sourceKey, level, owner)] = entry
                resolved
            }
        }
        throw SourceNotFoundException("No playable full source for $mediaId at quality $requested")
    }

    fun invalidate(mediaId: String) { cache.keys.removeAll { it.mediaId == mediaId.trim() } }
}
