package com.ljyh.mei.ui.screen.artist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ljyh.mei.data.model.api.ArtistAlbum
import com.ljyh.mei.data.model.api.ArtistDetail
import com.ljyh.mei.data.model.api.ArtistSong
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.data.repository.ArtistRepository
import com.ljyh.mei.data.repository.MeloXRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

class ArtistViewModel @Inject constructor(
    private val repository: ArtistRepository,
    private val meloXRepository: MeloXRepository,
) : ViewModel() {

    private val _artistDetail = MutableStateFlow<Resource<ArtistDetail>>(Resource.Loading)
    val artistDetail: StateFlow<Resource<ArtistDetail>> = _artistDetail

    private val _artistAlbums = MutableStateFlow<Resource<ArtistAlbum>>(Resource.Loading)
    val artistAlbums: StateFlow<Resource<ArtistAlbum>> = _artistAlbums

    private val _artistSongs = MutableStateFlow<Resource<ArtistSong>>(Resource.Loading)
    val artistSongs: StateFlow<Resource<ArtistSong>> = _artistSongs

    private val _followMutation = MutableStateFlow<Resource<Boolean>?>(null)
    val followMutation: StateFlow<Resource<Boolean>?> = _followMutation

    fun getArtistDetail(id: String) {
        viewModelScope.launch {
            _artistDetail.value = repository.getArtistDetail(id)
        }
    }

    fun getArtistAlbums(id: String) {
        viewModelScope.launch {
            _artistAlbums.value = repository.getArtistAlbums(id)
        }
    }

    fun getArtistSongs(id: String) {
        viewModelScope.launch {
            _artistSongs.value = repository.getArtistSongs(id)
        }
    }

    fun setArtistFollowed(id: Long, followed: Boolean) {
        if (_followMutation.value is Resource.Loading) return
        viewModelScope.launch {
            _followMutation.value = Resource.Loading
            _followMutation.value = try {
                meloXRepository.setArtistFollowed(id, followed)
                Resource.Success(followed)
            } catch (error: Exception) {
                Resource.Error(error.message ?: "Unable to update artist follow state")
            }
        }
    }
}
