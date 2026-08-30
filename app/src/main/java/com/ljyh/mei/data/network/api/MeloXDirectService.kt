package com.ljyh.mei.data.network.api

import com.google.gson.JsonObject
import retrofit2.http.Body
import retrofit2.http.HeaderMap
import retrofit2.http.POST
import retrofit2.http.Url

/** Dynamic transport for MeloX routes that are not part of Mei's legacy API surface. */
interface MeloXDirectService {
    @POST
    suspend fun post(
        @Url path: String,
        @Body body: Map<String, @JvmSuppressWildcards Any> = emptyMap(),
        @HeaderMap headers: Map<String, String> = emptyMap(),
    ): JsonObject
}
