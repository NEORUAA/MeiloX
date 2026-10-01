package com.ljyh.mei.runtime

import com.google.gson.JsonObject
import com.ljyh.mei.data.network.api.MeloXDirectService

/** The official SDK owns signing and retries; there is no module transport fallback. */
internal object MeloXRequestPolicy {
    fun service(delegate: MeloXDirectService, @Suppress("UNUSED_PARAMETER") useEapi: Boolean): MeloXDirectService = delegate

    suspend fun request(primary: suspend () -> JsonObject,
        @Suppress("UNUSED_PARAMETER") fallback: suspend () -> JsonObject): JsonObject = primary()
}
