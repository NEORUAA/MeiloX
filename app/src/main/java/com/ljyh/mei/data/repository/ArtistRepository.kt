package com.ljyh.mei.data.repository

import com.ljyh.mei.data.model.api.AllArtistSongs
import com.ljyh.mei.data.model.api.ArtistAlbum
import com.ljyh.mei.data.model.api.ArtistDetail
import com.ljyh.mei.data.model.api.ArtistSong
import com.ljyh.mei.data.model.api.GetAllArtistSongs
import com.ljyh.mei.data.model.api.GetArtistAlbum
import com.ljyh.mei.data.model.api.GetArtistDetail
import com.ljyh.mei.data.model.api.GetArtistSong
import com.ljyh.mei.data.model.toMediaMetadata
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.data.network.api.ApiService
import com.ljyh.mei.data.network.safeApiCall
import com.ljyh.mei.parasite.HostSessionBridge
import com.ljyh.mei.parasite.HostSessionStamp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal interface ArtistSource {
    suspend fun detail(id: String, owner: HostSessionStamp): Resource<ArtistDetail>
    suspend fun albums(id: String, owner: HostSessionStamp): Resource<ArtistAlbum>
    suspend fun hotSongs(id: String, owner: HostSessionStamp): Resource<ArtistSong>
    suspend fun songs(id: String, offset: Int, owner: HostSessionStamp): Resource<AllArtistSongs>
    suspend fun followed(id: String, owner: HostSessionStamp): Resource<Boolean>
    suspend fun follow(id: String, followed: Boolean, owner: HostSessionStamp): Resource<Unit>
}

class ArtistRepository(private val apiService: ApiService, private val sessions: HostSessionBridge) : ArtistSource {
    private suspend fun <T> request(id: String, owner: HostSessionStamp, action: suspend () -> T): Resource<T> =
        withContext(Dispatchers.IO) {
            safeApiCall {
                require((id.toLongOrNull() ?: 0) > 0) { "Invalid artist identity" }
                sessions.requireCurrent(owner)
                action().also {
                    currentCoroutineContext().ensureActive()
                    sessions.requireCurrent(owner)
                }
            }
        }

    override suspend fun detail(id: String, owner: HostSessionStamp) = request(id, owner) {
        apiService.getArtistDetail(GetArtistDetail(id), owner).also { result ->
            check(result.code == 200) { "Artist detail failed (${result.code})" }
            result.data?.artist?.let { artist ->
                check(artist.id.toString() == id && artist.name.isNotBlank()) { "Invalid artist detail" }
            }
        }
    }

    override suspend fun albums(id: String, owner: HostSessionStamp) = request(id, owner) {
        apiService.getArtistAlbums(GetArtistAlbum(), id, owner).also { result ->
            check(result.code == 200) { "Artist albums failed (${result.code})" }
            check(result.artist.id.toString() == id) { "Artist album identity mismatch" }
            val albums = checkNotNull(result.hotAlbums) { "Missing artist albums" }
            check(albums.map { it.id }.distinct().size == albums.size) { "Duplicate artist albums" }
            albums.forEach { album ->
                check(album.id > 0 && album.name.isNotBlank() && album.artists.all { it.id > 0 && it.name.isNotBlank() }) { "Invalid artist album" }
            }
        }
    }

    override suspend fun hotSongs(id: String, owner: HostSessionStamp) = request(id, owner) {
        apiService.getArtistSongs(GetArtistSong(), id, owner).also { result ->
            check(result.code == 200) { "Artist hot songs failed (${result.code})" }
            check(result.artist.id.toString() == id) { "Artist hot-song identity mismatch" }
            validateSongs(checkNotNull(result.hotSongs) { "Missing artist hot songs" })
        }
    }

    override suspend fun songs(id: String, offset: Int, owner: HostSessionStamp) = request(id, owner) {
        require(offset >= 0)
        apiService.getAllArtistSongs(GetAllArtistSongs(id, offset), owner).also { result ->
            check(result.code == 200) { "Artist songs failed (${result.code})" }
            val songs = checkNotNull(result.songs) { "Missing artist songs" }
            validateSongs(songs)
            check(!result.more || songs.isNotEmpty()) { "Artist song cursor did not advance" }
        }
    }

    override suspend fun followed(id: String, owner: HostSessionStamp) = request(id, owner) {
        if (!owner.identity.authenticated) return@request false
        val result = apiService.getArtistCollection(mapOf("artistId" to id), owner)
        check(result.code == 200) { "Artist collection failed (${result.code})" }
        val artist = checkNotNull(result.data?.artist) { "Missing official artist collection" }
        check(artist.id.toString() == id) { "Official artist collection identity mismatch" }
        checkNotNull(artist.followed) { "Missing official artist collection flag" }
    }

    override suspend fun follow(id: String, followed: Boolean, owner: HostSessionStamp): Resource<Unit> = request(id, owner) {
        check(owner.identity.authenticated && !owner.identity.anonymous) { "Official sign-in required" }
        val response = if (followed) apiService.subscribeArtist(mapOf("artistId" to id), owner)
        else apiService.unsubscribeArtist(mapOf("artistIds" to "[$id]"), owner)
        check(response.code == 200) { "Artist collection update failed (${response.code})" }
    }

    private fun validateSongs(songs: List<ArtistSong.HotSong>) {
        check(songs.map { it.id }.distinct().size == songs.size) { "Duplicate artist songs" }
        songs.forEach { song ->
            val metadata = song.toMediaMetadata()
            check(metadata.id > 0 && metadata.title.isNotBlank() && metadata.duration >= 0 &&
                metadata.album.id > 0 && metadata.album.title.isNotBlank() &&
                metadata.artists.all { it.id > 0 && it.name.isNotBlank() }) { "Invalid artist song" }
        }
    }
}
