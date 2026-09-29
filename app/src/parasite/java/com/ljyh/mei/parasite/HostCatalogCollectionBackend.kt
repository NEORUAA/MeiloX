package com.ljyh.mei.parasite

import com.google.gson.JsonObject
import com.ljyh.mei.data.model.api.AlbumCollectionResponse
import com.ljyh.mei.data.model.api.ArtistCollectionResponse
import com.ljyh.mei.data.model.api.BaseResponse
import com.ljyh.mei.data.repository.CatalogCollectionBackend
import com.ljyh.mei.data.session.SessionStamp
import javax.inject.Inject
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Tag

internal class HostCatalogCollectionBackend @Inject constructor(retrofit: Retrofit) : CatalogCollectionBackend {
    private val api = retrofit.create(HostCatalogCollectionApi::class.java)

    override suspend fun albumCollected(id: Long, owner: SessionStamp): Boolean {
        val request = JsonObject().apply { addProperty("albumId", id.toString()) }
        val response = api.album(mapOf("request" to request.toString()), owner)
        check(response.code == 200) { "Album collection state failed (${response.code})" }
        val album = checkNotNull(response.data) { "Missing official album collection state" }
        check(album.id == id) { "Official album identity mismatch" }
        return checkNotNull(album.collected) { "Missing official album collection flag" }
    }

    override suspend fun artistFollowed(id: Long, owner: SessionStamp): Boolean {
        val response = api.artist(mapOf("artistId" to id.toString()), owner)
        check(response.code == 200) { "Artist collection failed (${response.code})" }
        val artist = checkNotNull(response.data?.artist) { "Missing official artist collection" }
        check(artist.id == id) { "Official artist collection identity mismatch" }
        return checkNotNull(artist.followed) { "Missing official artist collection flag" }
    }

    override suspend fun setArtistFollowed(id: Long, followed: Boolean, owner: SessionStamp): BaseResponse =
        if (followed) api.subscribeArtist(mapOf("artistId" to id.toString()), owner)
        else api.unsubscribeArtist(mapOf("artistIds" to "[$id]"), owner)
}

internal interface HostCatalogCollectionApi {
    @POST("/api/tv-artist-page/album/get")
    suspend fun album(@Body body: Map<String, String>, @Tag owner: SessionStamp): AlbumCollectionResponse

    @POST("/api/tv-artist-page/artistdetail")
    suspend fun artist(@Body body: Map<String, String>, @Tag owner: SessionStamp): ArtistCollectionResponse

    @POST("/api/v1/artist/sub/")
    suspend fun subscribeArtist(@Body body: Map<String, String>, @Tag owner: SessionStamp): BaseResponse

    @POST("/api/artist/unsub")
    suspend fun unsubscribeArtist(@Body body: Map<String, String>, @Tag owner: SessionStamp): BaseResponse
}
