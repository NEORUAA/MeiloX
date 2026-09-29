package com.ljyh.mei.standalone

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.ljyh.mei.data.network.netease.NcblSessionContext
import com.ljyh.mei.data.network.netease.NcblSongInfo
import com.ljyh.mei.data.network.netease.NeteaseClientLogClient
import com.ljyh.mei.data.session.SessionChangedException
import com.ljyh.mei.data.session.SessionStamp
import com.ljyh.mei.data.session.SessionStore
import com.ljyh.mei.di.NETEASE_EAPI_PROFILE_HEADER
import com.ljyh.mei.di.PLAYBACK_HISTORY_PROFILE
import com.ljyh.mei.di.readBoundedPlaybackResponseBody
import com.ljyh.mei.playback.PlaybackReportSink
import com.ljyh.mei.playback.PlaybackReportDetails
import com.ljyh.mei.utils.log.logPlaybackHistory
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

@Singleton
internal class StandalonePlaybackReports internal constructor(
    override val sessions: SessionStore,
    private val weblog: Call.Factory,
    private val ncbl: NeteaseClientLogClient,
    private val report: (String) -> Unit,
) : PlaybackReportSink {
    @Inject constructor(
        sessions: StandaloneSessionStore,
        transport: StandaloneTransport,
        ncbl: NeteaseClientLogClient,
    ) : this(sessions, transport.business, ncbl, { logPlaybackHistory(android.util.Log.INFO, "%s", it) })

    private data class PlaybackKey(val owner: SessionStamp, val songId: Long, val startedAtMs: Long)
    private val contextLock = Any()
    private val contexts = LinkedHashMap<PlaybackKey, NcblSessionContext>()

    init {
        sessions.onInvalidated { revision ->
            synchronized(contextLock) { contexts.keys.removeAll { it.owner.generation < revision } }
        }
    }

    override fun requireOwner(owner: SessionStamp) {
        sessions.requireCurrent(owner)
        if (!owner.identity.authenticated || owner.identity.anonymous || sessions.recoveryRequired.value) {
            throw SessionChangedException()
        }
    }

    override suspend fun submit(
        action: String, fields: Map<String, Any>, owner: SessionStamp, details: PlaybackReportDetails,
    ) = coroutineScope {
        require(action == "startplay" || action == "play")
        requireOwner(owner)
        launch { submitChannel("weblog", owner) { submitWeblog(action, fields, owner) } }
        launch { submitChannel("ncbl", owner) { submitNcbl(action, fields, owner, details) } }
        Unit
    }

    private suspend fun submitChannel(channel: String, owner: SessionStamp, submit: suspend () -> Boolean) {
        try {
            requireOwner(owner)
            val accepted = submit()
            requireOwner(owner)
            report("standalone_playback_report channel=$channel accepted=$accepted")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            report("standalone_playback_report channel=$channel failed=${error.javaClass.simpleName}")
        }
    }

    private suspend fun submitWeblog(action: String, fields: Map<String, Any>, owner: SessionStamp): Boolean {
        val gson = Gson()
        val logs = gson.toJson(listOf(mapOf("action" to action, "json" to fields)))
        val request = Request.Builder()
            .url("https://interface.music.163.com/api/feedback/weblog")
            .header("X-Netease-Crypto", "eapi")
            .header(NETEASE_EAPI_PROFILE_HEADER, PLAYBACK_HISTORY_PROFILE)
            .tag(SessionStamp::class.java, owner)
            .post(gson.toJson(mapOf("logs" to logs)).toRequestBody("application/json".toMediaType()))
            .build()
        return weblog.newCall(request).await().use { response ->
            val body = readBoundedPlaybackResponseBody(response.body, response.code)
            requireOwner(owner)
            if (!response.isSuccessful || JsonParser.parseString(body.decodeToString()).asJsonObject.get("code")?.asInt != 200) {
                throw IOException("Standalone playback report was not accepted")
            }
            true
        }
    }

    private suspend fun submitNcbl(
        action: String, fields: Map<String, Any>, owner: SessionStamp, details: PlaybackReportDetails,
    ): Boolean {
        val songId = (fields["id"] as? Number)?.toLong() ?: return false
        val key = PlaybackKey(owner, songId, details.startedAtMs)
        val eventTimeMs = (fields["logtime"] as? Number)?.toLong() ?: return false
        if (action == "startplay") {
            val source = fields["source"] as? String ?: return false
            val sourceId = fields["sourceId"]?.toString()?.toLongOrNull() ?: return false
            val context = ncbl.beginSession(
                NcblSongInfo(songId, details.title, details.artist, details.durationMs),
                source, sourceId, details.startedAtMs, owner,
            ) ?: return false
            sessions.withCurrent(owner) {
                synchronized(contextLock) {
                    contexts[key] = context
                    // Bound contexts whose playback was discarded before an end event.
                    while (contexts.size > 128) contexts.remove(contexts.keys.first())
                }
            }
            return try {
                ncbl.submitStart(context, eventTimeMs).fileAccepted
            } catch (error: CancellationException) {
                synchronized(contextLock) { contexts.remove(key) }
                throw error
            }
        }
        val context = synchronized(contextLock) { contexts.remove(key) } ?: return false
        val seconds = (fields["time"] as? Number)?.toLong()?.coerceIn(0, Long.MAX_VALUE / 1_000) ?: 0L
        return ncbl.submitEnd(
            context, seconds * 1_000, eventTimeMs, fields["end"] as? String ?: "ui",
        ).fileAccepted
    }
}
