package com.ljyh.mei.data.repository

import com.ljyh.mei.data.model.AlbumPhoto
import com.ljyh.mei.data.model.UserAccount
import com.ljyh.mei.data.model.UserAlbumList
import com.ljyh.mei.data.model.UserPlaylist
import com.ljyh.mei.data.model.api.GetAlbumList
import com.ljyh.mei.data.model.api.GetUserPhotoAlbum
import com.ljyh.mei.data.model.api.GetUserPlaylist
import com.ljyh.mei.data.model.weapi.UserSubcount
import com.ljyh.mei.data.network.api.ApiService
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.data.network.api.EApiService
import com.ljyh.mei.data.network.api.WeApiService
import com.ljyh.mei.data.network.safeApiCall
import com.ljyh.mei.data.session.SessionStamp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException


class UserRepository(private val apiService: ApiService,private val eApiService: EApiService, private val weApiService: WeApiService) {
    suspend fun getUserAccount(): Resource<UserAccount> {
        return withContext(Dispatchers.IO) {
            safeApiCall { apiService.getAccountDetail() }
        }
    }

    suspend fun getUserPlaylist(uid: String, limit: Int): Resource<UserPlaylist> {
        return withContext(Dispatchers.IO) {
            safeApiCall {
                apiService.getUserPlaylist(
                    GetUserPlaylist(
                        uid = uid,
                        limit = limit.toString()
                    )
                )
            }
        }
    }

    suspend fun getAllUserPlaylists(uid: String, session: com.ljyh.mei.data.session.SessionStamp? = null, validate: () -> Unit = {}): Resource<UserPlaylist> = withContext(Dispatchers.IO) {
        safeApiCall {
            val playlists = linkedMapOf<Long, UserPlaylist.Playlist>()
            var offset = 0
            var page: UserPlaylist
            do {
                currentCoroutineContext().ensureActive()
                validate()
                page = apiService.getUserPlaylist(GetUserPlaylist(uid = uid, limit = "100", offset = offset.toString()), session)
                validate()
                if (page.code != 200) throw IOException("Official playlist request failed (${page.code})")
                val previousSize = playlists.size
                page.playlist.forEach { playlists[it.id] = it }
                if (page.more && playlists.size == previousSize) throw IOException("Playlist pagination did not advance")
                offset += page.playlist.size
            } while (page.more)
            page.copy(playlist = playlists.values.toList(), more = false)
        }
    }
    suspend fun getPhotoAlbum(id: String, session: SessionStamp, validate: () -> Unit = {}): Resource<AlbumPhoto> {
        return withContext(Dispatchers.IO) {
            safeApiCall {
                currentCoroutineContext().ensureActive()
                validate()
                val response = apiService.getUserPhotoAlbum(
                    GetUserPhotoAlbum(
                        userId = id
                    ), session,
                )
                currentCoroutineContext().ensureActive()
                validate()
                if (response.code != 200) throw IOException("Official photo request failed (${response.code})")
                response
            }
        }
    }

    suspend fun getAlbumList(session: SessionStamp, validate: () -> Unit = {}): Resource<UserAlbumList> {
        return withContext(Dispatchers.IO) {
            safeApiCall {
                val albums = linkedMapOf<Long, UserAlbumList.Data>()
                var offset = 0
                var page: UserAlbumList
                do {
                    currentCoroutineContext().ensureActive()
                    validate()
                    page = apiService.getCollectAlbumList(GetAlbumList(limit = "100", offset = offset.toString()), session)
                    validate()
                    if (page.code != 200) throw IOException("Official album request failed (${page.code})")
                    val previousSize = albums.size
                    page.data.forEach { albums[it.id] = it }
                    if (page.hasMore && albums.size == previousSize) throw IOException("Album pagination did not advance")
                    offset += page.data.size
                } while (page.hasMore)
                page.copy(data = albums.values.toList(), hasMore = false)
            }
        }
    }

    suspend fun getUsrSubcount(): Resource<UserSubcount> {
        return withContext(Dispatchers.IO) {
            safeApiCall {
                weApiService.getUserSubcount()
            }
        }
    }
}
