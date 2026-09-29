package com.ljyh.mei.data.model.api

import com.google.gson.annotations.SerializedName

data class GetDownloadUrl(
    @SerializedName("id") val id: String,
    @SerializedName("level") val level: String,
    @SerializedName("immerseType") val immerseType: String = if (level == "sky") "c51" else "ste",
)
