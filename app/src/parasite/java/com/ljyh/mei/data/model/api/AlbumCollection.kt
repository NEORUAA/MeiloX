package com.ljyh.mei.data.model.api

import com.google.gson.annotations.SerializedName

data class AlbumCollectionResponse(
    @SerializedName("code") val code: Int,
    @SerializedName("data") val data: AlbumCollection?,
) {
    data class AlbumCollection(
        @SerializedName("id") val id: Long,
        @SerializedName("collect") val collected: Boolean?,
    )
}
