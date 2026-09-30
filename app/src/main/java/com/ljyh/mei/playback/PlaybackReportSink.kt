package com.ljyh.mei.playback

import com.ljyh.mei.data.model.SongSourceIdentity
import com.ljyh.mei.data.session.SessionStamp
import com.ljyh.mei.data.session.SessionStore

/** Immutable metadata captured at the actual start, not looked up after a track change. */
data class PlaybackReportDetails(
    val startedAtMs: Long,
    val title: String = "",
    val artist: String = "",
    val durationMs: Long? = null,
    val songSource: SongSourceIdentity? = null,
)

/** Each runtime owns reporting metadata, signing and delivery. */
interface PlaybackReportSink {
    val sessions: SessionStore
    fun requireOwner(owner: SessionStamp)
    suspend fun submit(action: String, fields: Map<String, Any>, owner: SessionStamp, details: PlaybackReportDetails)
}

/** Source ownership is internal metadata, never a weblog field or credential copy. */
internal fun PlaybackReportSink.requireReportSource(
    fields: Map<String, Any>, owner: SessionStamp, details: PlaybackReportDetails,
) {
    requireOwner(owner)
    val songId = requireNotNull((fields["id"] as? Number)?.toString()?.toLongOrNull()?.takeIf { it > 0 })
    val source = details.songSource ?: SongSourceIdentity(songId)
    source.requireAccount(owner.identity)
    require(source.songId == songId) { "Mismatched playback report source" }
}
