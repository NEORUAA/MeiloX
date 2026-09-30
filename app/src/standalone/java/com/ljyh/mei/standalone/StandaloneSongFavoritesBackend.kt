package com.ljyh.mei.standalone

import com.google.gson.annotations.SerializedName
import com.ljyh.mei.data.model.SongSourceIdentity
import com.ljyh.mei.data.repository.SongFavoritesBackend
import com.ljyh.mei.data.session.SessionStamp
import javax.inject.Inject
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Tag

internal class StandaloneSongFavoritesBackend @Inject constructor(retrofit: Retrofit) : SongFavoritesBackend {
    private val api = retrofit.create(StandaloneSongFavoritesApi::class.java)

    override suspend fun isLiked(source: SongSourceIdentity, owner: SessionStamp): Boolean {
        source.requireAccount(owner.identity)
        val id = source.songId
        val response = api.check(mapOf("trackIds" to "[$id]"), owner)
        check(response.code == 200) { "Liked song query failed (${response.code})" }
        val ids = checkNotNull(response.ids) { "Missing liked song identities" }
        check(ids.all { it == id }) { "Unexpected liked song identity" }
        return id in ids
    }

    override suspend fun setLiked(source: SongSourceIdentity, liked: Boolean, owner: SessionStamp): Boolean {
        source.requireAccount(owner.identity)
        val response = api.update(StandaloneSongLike(trackId = source.songId.toString(), like = liked), owner)
        check(response.code == 200) { "Song like failed (${response.code})" }
        check((response.playlistId ?: 0) > 0) { "Missing liked playlist" }
        return liked
    }
}

internal interface StandaloneSongFavoritesApi {
    @POST("/api/song/like/check")
    suspend fun check(@Body body: Map<String, String>, @Tag owner: SessionStamp): StandaloneSongLikeIds

    @POST("/api/radio/like")
    suspend fun update(@Body body: StandaloneSongLike, @Tag owner: SessionStamp): StandaloneSongLikeResult
}

internal data class StandaloneSongLikeIds(
    @SerializedName("code") val code: Int,
    @SerializedName("ids") val ids: List<Long?>?,
)

internal data class StandaloneSongLike(
    @SerializedName("alg") val alg: String = "itembased",
    @SerializedName("trackId") val trackId: String,
    @SerializedName("like") val like: Boolean,
    @SerializedName("time") val time: String = "3",
)

internal data class StandaloneSongLikeResult(
    @SerializedName("code") val code: Int,
    @SerializedName("playlistId") val playlistId: Long?,
)
