package com.ljyh.mei.playback

import com.ljyh.mei.data.session.SessionStamp
import com.ljyh.mei.data.session.SessionStore

/** Each runtime owns reporting metadata, signing and delivery. */
interface PlaybackReportSink {
    val sessions: SessionStore
    fun requireOwner(owner: SessionStamp)
    fun submit(action: String, fields: Map<String, Any>, owner: SessionStamp)
}
