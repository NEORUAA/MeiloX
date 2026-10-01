package com.ljyh.mei.data.network.api

import com.google.gson.JsonObject
import com.ljyh.mei.data.session.SessionStamp
import retrofit2.http.GET
import retrofit2.http.Query
import retrofit2.http.Tag

interface AudioMatchService {
    @GET("/api/music/audio/match")
    suspend fun match(
        @Query("sessionId") sessionId: String = "0123456789abcdef",
        @Query("algorithmCode") algorithmCode: String = "shazam_v2",
        @Query("duration") duration: Int,
        @Query("rawdata") fingerprint: String,
        @Query("times") times: Int = 1,
        @Query("decrypt") decrypt: Int = 1,
        @Tag expectedSession: SessionStamp,
    ): JsonObject
}
