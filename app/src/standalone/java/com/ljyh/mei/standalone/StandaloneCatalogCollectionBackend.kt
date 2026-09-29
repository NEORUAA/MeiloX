package com.ljyh.mei.standalone

import com.google.gson.annotations.SerializedName
import com.google.gson.JsonElement
import com.ljyh.mei.data.model.api.BaseResponse
import com.ljyh.mei.data.model.api.GetAlbumList
import com.ljyh.mei.data.model.api.GetArtistSong
import com.ljyh.mei.data.repository.CatalogCollectionBackend
import com.ljyh.mei.data.session.SessionStamp
import com.ljyh.mei.data.session.SessionStore
import javax.inject.Inject
import javax.inject.Named
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Tag

internal class StandaloneCatalogCollectionBackend @Inject constructor(
    retrofit: Retrofit,
    @Named("WeApiRetrofit") weapi: Retrofit,
    private val sessions: SessionStore,
) : CatalogCollectionBackend {
    private val reads = retrofit.create(StandaloneCatalogCollectionApi::class.java)
    private val writes = weapi.create(StandaloneArtistCollectionApi::class.java)

    override suspend fun albumCollected(id: Long, owner: SessionStamp): Boolean {
        val seen = mutableSetOf<Long>()
        var offset = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            sessions.requireCurrent(owner)
            val response = reads.albums(GetAlbumList(limit = "100", offset = offset.toString()), owner)
            currentCoroutineContext().ensureActive()
            sessions.requireCurrent(owner)
            check(response.code == 200) { "Album collection state failed (${response.code})" }
            val more = collectionFlag(response.hasMore, "album collection cursor")
            val ids = checkNotNull(response.data) { "Missing album collection list" }.map {
                checkNotNull(it?.id?.takeIf { value -> value > 0 }) { "Invalid collected album identity" }
            }
            check(ids.distinct().size == ids.size) { "Duplicate collected albums" }
            if (id in ids) return true
            if (!more) return false
            val previousSize = seen.size
            seen.addAll(ids)
            check(seen.size > previousSize) { "Album collection cursor did not advance" }
            offset = Math.addExact(offset, ids.size)
        }
    }

    override suspend fun artistFollowed(id: Long, owner: SessionStamp): Boolean {
        val response = reads.artist(GetArtistSong(), id, owner)
        check(response.code == 200) { "Artist collection state failed (${response.code})" }
        val artist = checkNotNull(response.artist) { "Missing artist collection state" }
        check(artist.id == id) { "Artist collection identity mismatch" }
        return collectionFlag(artist.followed, "artist collection flag")
    }

    override suspend fun setArtistFollowed(id: Long, followed: Boolean, owner: SessionStamp): BaseResponse =
        writes.update(if (followed) "sub" else "unsub", mapOf("artistId" to id, "artistIds" to "[$id]"), owner)
}

internal interface StandaloneCatalogCollectionApi {
    @POST("/api/album/sublist")
    suspend fun albums(@Body body: GetAlbumList, @Tag owner: SessionStamp): StandaloneAlbumCollections

    @POST("/api/v1/artist/{id}")
    suspend fun artist(@Body body: GetArtistSong, @Path("id") id: Long, @Tag owner: SessionStamp): StandaloneArtistCollection
}

internal interface StandaloneArtistCollectionApi {
    @POST("/weapi/artist/{action}")
    suspend fun update(@Path("action") action: String, @Body body: Map<String, @JvmSuppressWildcards Any>, @Tag owner: SessionStamp): BaseResponse
}

internal data class StandaloneAlbumCollections(
    @SerializedName("code") val code: Int,
    @SerializedName("data") val data: List<Album?>?,
    @SerializedName("hasMore") val hasMore: JsonElement?,
) {
    data class Album(@SerializedName("id") val id: Long?)
}

internal data class StandaloneArtistCollection(
    @SerializedName("code") val code: Int,
    @SerializedName("artist") val artist: Artist?,
) {
    data class Artist(
        @SerializedName("id") val id: Long?,
        @SerializedName("followed") val followed: JsonElement?,
    )
}

private fun collectionFlag(value: JsonElement?, field: String): Boolean {
    check(value?.isJsonPrimitive == true && value.asJsonPrimitive.isBoolean) { "Missing or invalid $field" }
    return value.asBoolean
}
