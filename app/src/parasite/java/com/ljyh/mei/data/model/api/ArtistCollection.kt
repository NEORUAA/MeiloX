package com.ljyh.mei.data.model.api

import com.google.gson.annotations.SerializedName

data class ArtistCollectionResponse(
    @SerializedName("code") val code: Int,
    @SerializedName("data") val data: Data?,
) {
    data class Data(@SerializedName("artistDetail") val artist: Artist?)
    data class Artist(
        @SerializedName("id") val id: Long,
        @SerializedName("followed") val followed: Boolean?,
    )
}
