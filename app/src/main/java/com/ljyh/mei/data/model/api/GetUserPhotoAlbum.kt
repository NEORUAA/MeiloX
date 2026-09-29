package com.ljyh.mei.data.model.api

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

data class GetUserPhotoAlbum(
    @SerializedName("userId")
    val userId: String,
    @SerializedName("page")
    var page: String=""

) {
    init {
        page= Gson().toJson(Page())
    }
    data class Page(
        @SerializedName("cursor")
        val cursor: String? = null,
        @SerializedName("size")
        val size: Int = 10
    )
}
