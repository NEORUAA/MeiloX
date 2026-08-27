package com.ljyh.mei.data.network.api

import com.google.gson.JsonObject
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Url

/** Dynamic transport for MeloX routes that are not part of Mei's legacy API surface. */
interface MeloXDirectService {
    @POST
    suspend fun post(
        @Url path: String,
        @Body body: Map<String, @JvmSuppressWildcards Any> = emptyMap(),
        @Header("X-Netease-Crypto") cryptoMode: String? = null,
        @Header("X-Netease-Anti-Cheat-Token") antiCheatToken: String? = null,
        @Header("X-Netease-Yd-Device-Token") ydDeviceToken: String? = null,
        @Header("X-Netease-Login-Chain-Id") loginChainId: String? = null,
        @Header("X-Netease-NMCID") nmcid: String? = null,
        @Header("X-Netease-NMDI") nmdi: String? = null,
        @Header("X-Netease-NMTID") nmtid: String? = null,
        @Header("X-Netease-Without-Account") withoutAccount: Boolean? = null,
    ): JsonObject

    @POST
    suspend fun postResponse(
        @Url path: String,
        @Body body: Map<String, @JvmSuppressWildcards Any> = emptyMap(),
        @Header("X-Netease-Crypto") cryptoMode: String? = null,
        @Header("X-Netease-Anti-Cheat-Token") antiCheatToken: String? = null,
        @Header("X-Netease-Yd-Device-Token") ydDeviceToken: String? = null,
        @Header("X-Netease-Login-Chain-Id") loginChainId: String? = null,
        @Header("X-Netease-NMCID") nmcid: String? = null,
        @Header("X-Netease-NMDI") nmdi: String? = null,
        @Header("X-Netease-NMTID") nmtid: String? = null,
        @Header("X-Netease-Without-Account") withoutAccount: Boolean? = null,
    ): Response<JsonObject>
}
