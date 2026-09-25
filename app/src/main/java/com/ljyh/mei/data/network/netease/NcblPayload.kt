package com.ljyh.mei.data.network.netease

import com.google.gson.JsonObject

internal data class NcblLogEvent(
    val action: String,
    val songId: Long,
    val timeSeconds: Long,
    val startLogTimeSeconds: Long,
    val logTimeMs: Long,
    val record: ByteArray,
)

internal object NcblPayload {
    fun start(session: NcblSessionContext, eventTimeMs: Long): NcblLogEvent = event(
        session = session,
        action = "_plv",
        playedSeconds = 0L,
        eventTimeMs = eventTimeMs,
        endReason = null,
    )

    fun end(
        session: NcblSessionContext,
        playedDurationMs: Long,
        eventTimeMs: Long,
        endReason: String,
    ): NcblLogEvent = event(
        session = session,
        action = "_pld",
        playedSeconds = playedDurationMs.coerceAtLeast(0L) / 1_000L,
        eventTimeMs = eventTimeMs,
        endReason = endReason,
    )

    private fun event(
        session: NcblSessionContext,
        action: String,
        playedSeconds: Long,
        eventTimeMs: Long,
        endReason: String?,
    ): NcblLogEvent {
        val fields = JsonObject().apply {
            addProperty("id", session.song.id)
            addProperty("type", "song")
            addProperty("source", session.source)
            addProperty("sourceId", session.sourceId)
            addProperty("startlogtime", session.startLogTimeSeconds)
            addProperty("logtime", eventTimeMs)
            addProperty("time", playedSeconds)
            addProperty("realtime", playedSeconds)
            addProperty("_sessid", session.sessionId)
            addProperty("_eventcode", action)
            addProperty("_log_thoroughfare", "ua")
            addProperty("abtest", "")
            addProperty("buildType", session.device.buildType)
            addProperty("currentProcessName", session.device.processName)
            addProperty("lca", 1)
            addProperty("pid", session.device.pid)
        }

        session.song.name.takeIf(String::isNotBlank)?.let { fields.addProperty("songName", it) }
        session.song.artist.takeIf(String::isNotBlank)?.let { fields.addProperty("artistName", it) }
        session.song.durationMs
            ?.takeIf { it > 0L }
            ?.let { fields.addProperty("resource_time", it / 1_000L) }
        if (action == "_pld") {
            endReason?.let { fields.addProperty("end", it) }
        }

        val recordTimeSeconds = eventTimeMs / 1_000L
        val record = "$recordTimeSeconds\u0001$action\u0001$fields".toByteArray(Charsets.UTF_8)
        return NcblLogEvent(
            action = action,
            songId = session.song.id,
            timeSeconds = playedSeconds,
            startLogTimeSeconds = session.startLogTimeSeconds,
            logTimeMs = eventTimeMs,
            record = record,
        )
    }
}
