package com.ljyh.mei.data.model

import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName

/** Download authorization is a single object, not the playback endpoint's array. */
data class DownloadUrlResponse(
    @SerializedName("code") val code: Int?,
    @SerializedName("data") val data: Data?,
) {
    data class Data(
        @SerializedName("id") val id: Long?,
        @SerializedName("code") val code: Int?,
        @SerializedName("url") val url: String?,
        @SerializedName("type") val type: String?,
        @SerializedName("level") val level: String?,
        @SerializedName("size") val size: Long?,
        @SerializedName("md5") val md5: String?,
        @SerializedName("expi") val expi: Long?,
        @SerializedName("freeTrialInfo") val freeTrialInfo: JsonElement?,
    )
}

data class DownloadSource(
    val id: Long,
    val url: String,
    val fileType: String,
    val level: String,
    val requestedLevel: String,
    val size: Long,
    val md5: String,
    val expiresAtMs: Long?,
)

data class DownloadSources(
    val sources: List<DownloadSource>,
    val rejectedCodes: Map<String, Int> = emptyMap(),
)
