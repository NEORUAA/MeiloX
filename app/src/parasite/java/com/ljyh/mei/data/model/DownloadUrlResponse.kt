package com.ljyh.mei.data.model

import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName

/** Official download authorization is one object rather than the player endpoint's array. */
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
