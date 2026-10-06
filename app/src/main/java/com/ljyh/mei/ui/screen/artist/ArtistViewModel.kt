package com.ljyh.mei.ui.screen.artist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ljyh.mei.AppContext
import com.ljyh.mei.data.model.api.ArtistAlbum
import com.ljyh.mei.data.model.api.ArtistDetail
import com.ljyh.mei.data.model.api.ArtistSong
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.data.repository.ArtistRepository
import com.ljyh.mei.data.repository.MeloXRepository
import com.ljyh.mei.di.repository.ColorRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ArtistViewModel internal constructor(
    private val repository: ArtistRepository,
    private val colorRepository: ColorRepository,
    private val updateArtistFollowed: suspend (Long, Boolean) -> Unit,
) : ViewModel() {
    @Inject
    constructor(
        repository: ArtistRepository,
        meloXRepository: MeloXRepository,
        colorRepository: ColorRepository,
    ) : this(repository, colorRepository, meloXRepository::setArtistFollowed)

    fun getCachedColor(url: String) = colorRepository.getFromMemory(url)

    suspend fun getOrExtractColor(url: String) =
        colorRepository.getColorOrExtract(AppContext.instance, url)

    fun getCachedCoverIsDark(url: String) = colorRepository.getCachedCoverIsDark(url)

    suspend fun getCoverIsDarkOrExtract(url: String) =
        colorRepository.getCoverIsDarkOrExtract(AppContext.instance, url)

    private val _artistDetail = MutableStateFlow<Resource<ArtistDetail>>(Resource.Loading)
    val artistDetail: StateFlow<Resource<ArtistDetail>> = _artistDetail
    private var artistDetailJob: Job? = null

    private val _artistAlbums = MutableStateFlow<Resource<ArtistAlbum>>(Resource.Loading)
    val artistAlbums: StateFlow<Resource<ArtistAlbum>> = _artistAlbums

    private val _artistSongs = MutableStateFlow<Resource<ArtistSong>>(Resource.Loading)
    val artistSongs: StateFlow<Resource<ArtistSong>> = _artistSongs

    private val _followMutation = MutableStateFlow<Resource<Boolean>?>(null)
    val followMutation: StateFlow<Resource<Boolean>?> = _followMutation
    private val _confirmedFollowed = MutableStateFlow<Boolean?>(null)
    val confirmedFollowed: StateFlow<Boolean?> = _confirmedFollowed
    private var followRevision = 0L
    private var albumsRequestGeneration = 0L
    private var songsRequestGeneration = 0L
    private var songsFollowRevision: Long? = null

    fun getArtistDetail(id: String) {
        if (artistDetailJob?.isActive == true) return
        _artistDetail.value = Resource.Loading
        artistDetailJob = viewModelScope.launch {
            _artistDetail.value = repository.getArtistDetail(id)
        }
    }

    fun getArtistAlbums(id: String) {
        val generation = ++albumsRequestGeneration
        val revision = followRevision
        val canRefreshFollowed = _followMutation.value !is Resource.Loading
        _artistAlbums.value = Resource.Loading
        viewModelScope.launch {
            val result = repository.getArtistAlbums(id)
            if (generation != albumsRequestGeneration) return@launch
            _artistAlbums.value = result
            val currentSongs = _artistSongs.value.takeIf { songsFollowRevision == revision }
                ?: Resource.Loading
            publishFollowedRead(
                resolveArtistFollowed(id, currentSongs, result, null),
                revision,
                canRefreshFollowed,
            )
        }
    }

    fun getArtistSongs(id: String) {
        val generation = ++songsRequestGeneration
        val revision = followRevision
        val canRefreshFollowed = _followMutation.value !is Resource.Loading
        songsFollowRevision = null
        _artistSongs.value = Resource.Loading
        viewModelScope.launch {
            val result = repository.getArtistSongs(id)
            if (generation != songsRequestGeneration) return@launch
            _artistSongs.value = result
            songsFollowRevision = revision.takeIf {
                canRefreshFollowed && revision == followRevision &&
                    _followMutation.value !is Resource.Loading
            }
            publishFollowedRead(
                resolveArtistFollowed(id, result, Resource.Loading, null),
                revision,
                canRefreshFollowed,
            )
        }
    }

    private fun publishFollowedRead(followed: Boolean?, revision: Long, canRefresh: Boolean) {
        // Fresh reads may refresh confirmed state; reads from before or during a mutation may not.
        if (followed != null && canRefresh && revision == followRevision &&
            _followMutation.value !is Resource.Loading
        ) {
            _confirmedFollowed.value = followed
        }
    }

    fun setArtistFollowed(id: Long, followed: Boolean) {
        if (_followMutation.value is Resource.Loading) return
        followRevision++
        _followMutation.value = Resource.Loading
        viewModelScope.launch {
            _followMutation.value = try {
                updateArtistFollowed(id, followed)
                _confirmedFollowed.value = followed
                Resource.Success(followed)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Resource.Error(error.message ?: "Unable to update artist follow state")
            }
        }
    }
}

internal fun resolveArtistFollowed(
    id: String,
    songs: Resource<ArtistSong>,
    albums: Resource<ArtistAlbum>,
    confirmedFollowed: Boolean?,
): Boolean? {
    // Artist subscriptions are independent of the linked user account's follow status.
    return confirmedFollowed
        ?: (songs as? Resource.Success)?.data?.artist
            ?.takeIf { it.id.toString() == id }?.followed
        ?: (albums as? Resource.Success)?.data?.artist
            ?.takeIf { it.id.toString() == id }?.followed
}
