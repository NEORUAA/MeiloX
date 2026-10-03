package com.ljyh.mei.data.model.api

import com.google.gson.annotations.SerializedName

data class GetAllArtistSongs(
    @SerializedName("id") val id: String,
    @SerializedName("offset") val offset: Int = 0,
    @SerializedName("limit") val limit: Int = 100,
    @SerializedName("order") val order: String = "hot",
    @SerializedName("private_cloud") val privateCloud: Boolean = true,
    @SerializedName("work_type") val workType: Int = 1,
)

data class AllArtistSongs(
    @SerializedName("code") val code: Int,
    @SerializedName("songs") val songs: List<ArtistSong.HotSong>?,
    @SerializedName("more") val more: Boolean,
)
