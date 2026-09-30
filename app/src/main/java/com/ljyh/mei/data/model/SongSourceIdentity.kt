package com.ljyh.mei.data.model

import com.google.gson.JsonArray
import com.ljyh.mei.data.session.SessionChangedException
import com.ljyh.mei.data.session.SessionIdentity

/** Cloud entry, audio source, file owner and account affinity are separate public identities. */
data class SongSourceIdentity(
    val songId: Long,
    val cloudOwnerId: Long = 0,
    val accountId: Long = 0,
    val entryId: Long = songId,
) {
    val isCloud: Boolean get() = cloudOwnerId > 0
    val key: String get() {
        validate()
        return if (isCloud) "$CLOUD_PREFIX$entryId:$songId:$cloudOwnerId:$accountId" else songId.toString()
    }
    val downloadId: String get() { validate(); return "${songId}_$cloudOwnerId" }

    fun requireAccount(account: SessionIdentity) {
        validate()
        if (isCloud && (account.userId != accountId || !account.authenticated || account.anonymous)) {
            throw SessionChangedException()
        }
    }

    private fun validate() {
        require(songId > 0 && entryId > 0)
        require((cloudOwnerId > 0 && accountId > 0) ||
            (cloudOwnerId == 0L && accountId == 0L && entryId == songId)) { "Invalid song source identity" }
    }

    companion object {
        private const val CLOUD_NAMESPACE = "meilox-cloud-"
        private const val CLOUD_PREFIX = "meilox-cloud-v1:"

        internal fun cloudFromKeyOrNull(key: String): SongSourceIdentity? =
            if (key.trim().startsWith(CLOUD_NAMESPACE)) fromKey(key) else null

        fun fromKey(key: String): SongSourceIdentity {
            val raw = key.trim()
            if (!raw.startsWith(CLOUD_PREFIX)) {
                return SongSourceIdentity(requireNotNull(raw.toLongOrNull()?.takeIf { it > 0 }))
            }
            val fields = raw.removePrefix(CLOUD_PREFIX).split(':')
            require(fields.size == 4) { "Invalid cloud source identity" }
            val values = fields.map { requireNotNull(it.toLongOrNull()?.takeIf { id -> id > 0 && id.toString() == it }) }
            return SongSourceIdentity(values[1], values[2], values[3], values[0])
        }

        fun playerIds(sources: List<SongSourceIdentity>): String = JsonArray().apply {
            sources.forEach { source ->
                source.validate()
                if (source.isCloud) add(source.downloadId) else add(source.songId)
            }
        }.toString()
    }
}
