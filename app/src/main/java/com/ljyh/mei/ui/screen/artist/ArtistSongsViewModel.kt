package com.ljyh.mei.ui.screen.artist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.toMediaMetadata
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.data.repository.ArtistRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ArtistSongsState(
    val songs: List<MediaMetadata> = emptyList(),
    val offset: Int = 0,
    val hasMore: Boolean = true,
    val isLoading: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class ArtistSongsViewModel @Inject constructor(
    private val repository: ArtistRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(ArtistSongsState())
    val state = _state.asStateFlow()

    fun loadMore(id: String) {
        val previous = _state.value
        if (previous.isLoading || !previous.hasMore) return
        _state.value = previous.copy(isLoading = true, error = null)
        viewModelScope.launch {
            when (val result = repository.getAllArtistSongs(id, previous.offset)) {
                is Resource.Success -> {
                    val page = result.data.songs.orEmpty()
                    _state.value = previous.copy(
                        songs = (previous.songs + page.map { it.toMediaMetadata() }).distinctBy { it.id },
                        offset = previous.offset + page.size,
                        hasMore = result.data.more && page.isNotEmpty(),
                        error = null,
                    )
                }
                is Resource.Error -> _state.value = previous.copy(error = result.message)
                is Resource.Loading -> _state.value = previous
            }
        }
    }
}
