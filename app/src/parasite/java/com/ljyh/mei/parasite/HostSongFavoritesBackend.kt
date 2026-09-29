package com.ljyh.mei.parasite

import com.ljyh.mei.data.model.api.SongLike
import com.ljyh.mei.data.model.api.SongLikeIds
import com.ljyh.mei.data.model.api.SongLikeResult
import com.ljyh.mei.data.repository.SongFavoritesBackend
import com.ljyh.mei.data.session.SessionStamp
import com.ljyh.mei.data.session.SessionStore
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Tag

internal class HostSongFavoritesBackend @Inject constructor(
    retrofit: Retrofit,
    private val sessions: SessionStore,
) : SongFavoritesBackend {
    private val api = retrofit.create(HostSongFavoritesApi::class.java)

    override suspend fun isLiked(id: Long, owner: SessionStamp): Boolean {
        val response = api.snapshot(owner)
        currentCoroutineContext().ensureActive()
        sessions.requireCurrent(owner)
        check(response.code == 200) { "Official liked songs failed (${response.code})" }
        val ids = response.ids.orEmpty()
        check(ids.all { it > 0 }) { "Invalid official liked song identity" }
        return id in ids
    }

    override suspend fun setLiked(id: Long, liked: Boolean, owner: SessionStamp): Boolean {
        val response = api.update(SongLike(id, liked), owner)
        currentCoroutineContext().ensureActive()
        sessions.requireCurrent(owner)
        return when (response.code) {
            200 -> {
                check((response.playlistId ?: 0) > 0) { "Missing official liked playlist" }
                liked
            }
            // A completed host operation may still need an authoritative state read.
            502, 404 -> isLiked(id, owner)
            else -> throw IOException("Official song like failed (${response.code})")
        }
    }
}

internal interface HostSongFavoritesApi {
    @POST("/api/song/like/get")
    suspend fun snapshot(@Tag owner: SessionStamp): SongLikeIds

    @POST("/api/song/like")
    suspend fun update(@Body body: SongLike, @Tag owner: SessionStamp): SongLikeResult
}
