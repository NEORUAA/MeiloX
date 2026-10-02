package com.ljyh.mei.data.network.api

import com.ljyh.mei.data.model.AlbumDetail
import com.ljyh.mei.data.model.AlbumPhoto
import com.ljyh.mei.data.model.Lyric
import com.ljyh.mei.data.model.PlaylistDetail
import com.ljyh.mei.data.model.SongUrl
import com.ljyh.mei.data.model.Tracks
import com.ljyh.mei.data.model.UserAccount
import com.ljyh.mei.data.model.UserAlbumList
import com.ljyh.mei.data.model.UserPlaylist
import com.ljyh.mei.data.model.api.AllArtistSongs
import com.ljyh.mei.data.model.api.ArtistAlbum
import com.ljyh.mei.data.model.api.ArtistDetail
import com.ljyh.mei.data.model.api.ArtistSong
import com.ljyh.mei.data.model.api.BaseMessageResponse
import com.ljyh.mei.data.model.api.BaseResponse
import com.ljyh.mei.data.model.api.CreatePlaylist
import com.ljyh.mei.data.model.api.CreatePlaylistResult
import com.ljyh.mei.data.model.api.DeletePlaylist
import com.ljyh.mei.data.model.api.GetAlbumList
import com.ljyh.mei.data.model.api.GetAllArtistSongs
import com.ljyh.mei.data.model.api.GetArtistAlbum
import com.ljyh.mei.data.model.api.GetArtistDetail
import com.ljyh.mei.data.model.api.GetArtistSong
import com.ljyh.mei.data.model.api.GetComment
import com.ljyh.mei.data.model.api.GetIntelligence
import com.ljyh.mei.data.model.api.GetLyric
import com.ljyh.mei.data.model.api.GetLyricV1
import com.ljyh.mei.data.model.api.GetCloudLyric
import com.ljyh.mei.data.model.api.GetPlaylistDetail
import com.ljyh.mei.data.model.api.GetSearch
import com.ljyh.mei.data.model.api.GetSearchSuggest
import com.ljyh.mei.data.model.api.GetSongDetails
import com.ljyh.mei.data.model.api.GetSongUrlV1
import com.ljyh.mei.data.model.api.GetUserPhotoAlbum
import com.ljyh.mei.data.model.api.GetUserPlaylist
import com.ljyh.mei.data.model.api.Intelligence
import com.ljyh.mei.data.model.api.SearchResult
import com.ljyh.mei.data.model.api.SearchSuggest
import com.ljyh.mei.data.model.api.SubscribePlaylist
import com.ljyh.mei.data.model.weapi.Comment
import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Tag
import com.ljyh.mei.data.session.SessionStamp

interface ApiService {
    @Headers("X-Netease-Crypto: eapi")
    @POST("/api/playlist/random/list/get")
    suspend fun getRandomPlaylist(
        @Body body: com.ljyh.mei.data.model.api.GetRandomPlaylist,
    ): com.ljyh.mei.data.model.api.RandomPlaylistResponse

    /*
    * 获取歌单详情
    * */
    @POST("/api/v6/playlist/detail")
    suspend fun getPlaylistDetail(@Body body: GetPlaylistDetail, @Tag expectedSession: SessionStamp? = null): PlaylistDetail

    /*
    * 获取歌曲详情
    * */
    @POST("/api/v3/song/detail")
    suspend fun getSongDetail(@Body body: GetSongDetails, @Tag expectedSession: SessionStamp? = null): Tracks


    /*
    * 获取用户信息
    * */
    @POST("/api/nuser/account/get")
    suspend fun getAccountDetail(@Tag expectedSession: SessionStamp? = null): UserAccount

    /*
    * 获取歌词
    * */
    @POST("/api/song/lyric")
    suspend fun getLyric(
        @Body body: GetLyric
    ): Lyric


    /*
    * 获取歌词 新接口
    * */
    @POST("/api/song/lyric/v1")
    suspend fun getLyricV1(
        @Body body: GetLyricV1,
        @Tag expectedSession: SessionStamp? = null,
    ): Lyric

    @POST("/api/cloud/lyric/get")
    suspend fun getCloudLyric(
        @Body body: GetCloudLyric,
        @Tag expectedSession: SessionStamp,
    ): com.google.gson.JsonObject

    /*
    * 获取用户歌单
    * */
    @POST("/api/user/playlist")
    suspend fun getUserPlaylist(
        @Body body: GetUserPlaylist,
        @Tag expectedSession: SessionStamp? = null,
    ): UserPlaylist

    @POST("/api/album/sublist")
    suspend fun getCollectAlbumList(
        @Body body: GetAlbumList,
        @Tag expectedSession: SessionStamp,
    ): UserAlbumList

    @POST("/api/v1/album/{id}")
    suspend fun getAlbumDetail(
        @Body body: Map<String, String> = emptyMap(),
        @Path("id") id: String,
        @Tag expectedSession: SessionStamp? = null,
    ): AlbumDetail

    @POST("/api/search/get/")
    suspend fun search(
        @Body body: GetSearch,
        @Tag expectedSession: SessionStamp? = null,
    ): SearchResult


    @POST("/api/search/suggest/web/")
    suspend fun searchSuggest(
        @Body body: GetSearchSuggest,
        @Tag expectedSession: SessionStamp? = null,
    ): SearchSuggest


    @Headers("X-Netease-Crypto: eapi")
    @POST("/api/song/enhance/player/url/v1")
    suspend fun getSongUrlV1(
        @Body body: GetSongUrlV1,
        @Tag expectedSession: SessionStamp,
    ): SongUrl

    @POST("/api/user/photo/album/get")
    suspend fun getUserPhotoAlbum(@Body body: GetUserPhotoAlbum, @Tag expectedSession: SessionStamp): AlbumPhoto

    @POST("/api/playlist/create")
    suspend fun createPlaylist(@Body body: CreatePlaylist, @Tag expectedSession: SessionStamp): CreatePlaylistResult



    @POST("/api/album/sub")
    suspend fun subscribeAlbum(
        @Body body: SubscribePlaylist,
        @Tag expectedSession: SessionStamp? = null,
    ): BaseResponse

    @POST("/api/album/unsub")
    suspend fun unsubscribeAlbum(
        @Body body: SubscribePlaylist,
        @Tag expectedSession: SessionStamp? = null,
    ): BaseResponse

    @POST("/api/playlist/remove")
    suspend fun deletePlaylist(@Body body: DeletePlaylist, @Tag expectedSession: SessionStamp): BaseMessageResponse


    @POST("/api/artist/head/info/get")
    suspend fun getArtistDetail(@Body body: GetArtistDetail, @Tag expectedSession: SessionStamp): ArtistDetail

    @POST("/api/artist/albums/{id}")
    suspend fun getArtistAlbums(@Body body: GetArtistAlbum, @Path("id") id: String, @Tag expectedSession: SessionStamp): ArtistAlbum

    @POST("/api/v1/artist/songs")
    suspend fun getAllArtistSongs(
        @Body body: GetAllArtistSongs,
        @Tag expectedSession: SessionStamp,
    ): AllArtistSongs

    @POST("/api/v1/artist/{id}")
    suspend fun getArtistSongs(@Body body: GetArtistSong, @Path("id") id: String, @Tag expectedSession: SessionStamp): ArtistSong

    @POST("/api/playmode/intelligence/list")
    suspend fun getIntelligenceList(@Body body: GetIntelligence, @Tag expectedSession: SessionStamp): Intelligence

    @POST("/api/v2/resource/comments")
    suspend fun getComment(@Body body: GetComment, @Tag expectedSession: SessionStamp): Comment

}
