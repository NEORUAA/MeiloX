package com.ljyh.mei.playback

import com.ljyh.mei.data.session.SessionStamp
import com.ljyh.mei.data.session.SessionStore

/** Immutable metadata captured at the actual start, not looked up after a track change. */
data class PlaybackReportDetails(
    val startedAtMs: Long,
    val title: String = "",
    val artist: String = "",
    val durationMs: Long? = null,
)

/** Each runtime owns reporting metadata, signing and delivery. */
interface PlaybackReportSink {
    val sessions: SessionStore
    fun requireOwner(owner: SessionStamp)
    suspend fun submit(action: String, fields: Map<String, Any>, owner: SessionStamp, details: PlaybackReportDetails)
}
