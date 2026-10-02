package com.ljyh.mei.runtime

import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import com.ljyh.mei.data.model.SongSourceIdentity
import com.ljyh.mei.data.session.SessionIdentity
import com.ljyh.mei.playback.CacheManager
import com.ljyh.mei.playback.PlaybackUrl
import com.ljyh.mei.playback.playbackCacheKey
import com.ljyh.mei.playback.playbackCacheKeyPrefix
import java.util.Locale

/** Reuse legacy spans only after this account resolves the same authorized catalog file. */
internal object PlaybackCachePolicy {
    private val md5 = Regex("[a-f0-9]{32}")

    fun findVerifiedLegacyKey(cache: Cache, mediaId: String, quality: String, owner: SessionIdentity): String? {
        val identity = catalog(mediaId) ?: return null
        val prefix = playbackCacheKeyPrefix(identity.key, quality)
        val proof = proofKey(identity.key, quality, owner)
        return cache.keys.firstOrNull { key ->
            key.startsWith(prefix) && md5.matches(key.removePrefix(prefix)) && cache.getContentMetadata(key).let { metadata ->
                val length = metadata.get(proof, 0L)
                length > 0 && !metadata.contains(rejectedKey(proof)) &&
                    ContentMetadata.getContentLength(metadata) == length && CacheManager.isContentFullyCached(cache, key)
            }
        }
    }

    fun authorize(cache: Cache, identity: SongSourceIdentity, source: PlaybackUrl, owner: SessionIdentity): String {
        if (identity.isCloud || source.sourceSize <= 0) return source.cacheKey
        val fingerprint = source.sourceMd5?.trim()?.lowercase(Locale.ROOT) ?: return source.cacheKey
        if (!md5.matches(fingerprint)) return source.cacheKey
        val key = playbackCacheKey(identity.key, source.actualQuality, fingerprint, source.sourceSize)
        val metadata = cache.getContentMetadata(key)
        val proof = proofKey(identity.key, source.actualQuality, owner)
        if (metadata.contains(rejectedKey(proof)) || ContentMetadata.getContentLength(metadata) != source.sourceSize ||
            cache.getCachedBytes(key, 0, source.sourceSize) <= 0) return source.cacheKey
        try {
            cache.applyContentMetadataMutations(key, ContentMetadataMutations().set(proof, source.sourceSize))
        } catch (_: Cache.CacheException) {
            return source.cacheKey
        }
        return key
    }

    fun retireLegacyKeys(cache: Cache, mediaId: String, owner: SessionIdentity): Int {
        val identity = catalog(mediaId) ?: return 0
        val prefix = playbackCacheKeyPrefix(identity.key)
        var retired = 0
        for (key in cache.keys.filter { it.startsWith(prefix) }) {
            // Quality is part of the exact legacy key; retirement covers this owner's levels.
            val fields = key.removePrefix(prefix).split(':')
            if (fields.size != 2 || !md5.matches(fields[1])) continue
            val quality = fields[0]
            val proof = proofKey(identity.key, quality, owner)
            val metadata = cache.getContentMetadata(key)
            if (metadata.contains(proof)) {
                cache.applyContentMetadataMutations(key, ContentMetadataMutations().remove(proof).set(rejectedKey(proof), 1L))
                retired++
            }
        }
        return retired
    }

    private fun catalog(mediaId: String): SongSourceIdentity? =
        runCatching { SongSourceIdentity.fromKey(mediaId).takeUnless { it.isCloud } }.getOrNull()

    private fun proofKey(mediaId: String, quality: String, owner: SessionIdentity) =
        "meilox-standalone-verified:${playbackCacheKeyPrefix(mediaId, quality, owner)}"

    private fun rejectedKey(proof: String) = "$proof:rejected"
}
