package com.ljyh.mei.runtime

import com.google.gson.JsonObject
import com.ljyh.mei.data.network.api.MeloXDirectService
import com.ljyh.mei.data.session.SessionChangedException
import com.ljyh.mei.data.session.SessionStamp
import kotlinx.coroutines.CancellationException

/** Original dynamic signing/retry behavior belongs only to the Cookie runtime. */
internal object MeloXRequestPolicy {
    fun service(delegate: MeloXDirectService, useEapi: Boolean): MeloXDirectService =
        object : MeloXDirectService by delegate {
            override suspend fun post(path: String, body: Map<String, Any>, headers: Map<String, String>,
                expectedSession: SessionStamp?): JsonObject = delegate.post(
                path.replaceFirst("/api/", if (useEapi) "/eapi/" else "/weapi/"), body, headers, expectedSession,
            )
        }

    suspend fun request(primary: suspend () -> JsonObject, fallback: suspend () -> JsonObject): JsonObject =
        try {
            primary()
        } catch (error: Exception) {
            if (error is CancellationException || error is SessionChangedException) throw error
            fallback()
        }
}
