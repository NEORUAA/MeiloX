package com.ljyh.mei.runtime

import androidx.media3.datasource.cache.Cache
import com.ljyh.mei.data.model.SongSourceIdentity
import com.ljyh.mei.data.session.SessionIdentity
import com.ljyh.mei.playback.PlaybackUrl

/** Official-host storage cannot inherit standalone's unowned legacy entries. */
internal object PlaybackCachePolicy {
    fun findVerifiedLegacyKey(cache: Cache, mediaId: String, quality: String, owner: SessionIdentity): String? = null
    fun authorize(cache: Cache, identity: SongSourceIdentity, source: PlaybackUrl, owner: SessionIdentity): String = source.cacheKey
    fun retireLegacyKeys(cache: Cache, mediaId: String, owner: SessionIdentity): Int = 0
}
