package com.ljyh.mei.data.model.api

import com.google.gson.annotations.SerializedName

data class GetSongUrlV1(
    @SerializedName("ids")
    var ids: String,
    @SerializedName("level")
    var level: String,
    @SerializedName("encodeType")
    var encodeType: String = "flac",
    @SerializedName("immerseType")
    var immerseType: String? = null,
    @SerializedName("supportDolby")
    var supportDolby: Boolean? = null
) {
    init {
        if (level == "sky") {
            immerseType = "c51"
        }
        if (level == "dolby") {
            supportDolby = true
        }
    }
}


data class GetSongUrl(
    @SerializedName("ids")
    var ids: String,
    @SerializedName("br")
    var br: Int = 999000,
) {
    init {
        ids = "[${ids}]"
    }
}
