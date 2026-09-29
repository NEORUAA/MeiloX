package com.ljyh.mei.standalone

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.ljyh.mei.data.session.SessionChangedException
import com.ljyh.mei.data.session.SessionStamp
import com.ljyh.mei.di.NETEASE_EAPI_PROFILE_HEADER
import com.ljyh.mei.di.PLAYBACK_HISTORY_PROFILE
import com.ljyh.mei.di.readBoundedPlaybackResponseBody
import com.ljyh.mei.playback.PlaybackReportSink
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

@Singleton
internal class StandalonePlaybackReports @Inject constructor(
    override val sessions: StandaloneSessionStore,
    private val transport: StandaloneTransport,
) : PlaybackReportSink {
    override fun requireOwner(owner: SessionStamp) {
        sessions.requireCurrent(owner)
        if (!owner.identity.authenticated || owner.identity.anonymous || sessions.recoveryRequired.value) {
            throw SessionChangedException()
        }
    }

    override fun submit(action: String, fields: Map<String, Any>, owner: SessionStamp) {
        require(action == "startplay" || action == "play")
        requireOwner(owner)
        val gson = Gson()
        val logs = gson.toJson(listOf(mapOf("action" to action, "json" to fields)))
        val request = Request.Builder()
            .url("https://interface.music.163.com/api/feedback/weblog")
            .header("X-Netease-Crypto", "eapi")
            .header(NETEASE_EAPI_PROFILE_HEADER, PLAYBACK_HISTORY_PROFILE)
            .tag(SessionStamp::class.java, owner)
            .post(gson.toJson(mapOf("logs" to logs)).toRequestBody("application/json".toMediaType()))
            .build()
        transport.business.newCall(request).execute().use { response ->
            val body = readBoundedPlaybackResponseBody(response.body, response.code)
            requireOwner(owner)
            if (!response.isSuccessful || JsonParser.parseString(body.decodeToString()).asJsonObject.get("code")?.asInt != 200) {
                throw IOException("Standalone playback report was not accepted")
            }
        }
    }
}
