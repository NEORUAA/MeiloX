package com.ljyh.mei.ui.screen.artist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ljyh.mei.data.model.api.ArtistAlbum
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.data.repository.ArtistRepository
import com.ljyh.mei.ui.model.Album
import com.ljyh.mei.ui.model.toAlbum
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ArtistAlbumsState(
    val albums: List<Album> = emptyList(),
    val offset: Int = 0,
    val hasMore: Boolean = true,
    val isLoading: Boolean = false,
    val error: String? = null,
    val query: String = "",
) {
    val visibleAlbums: List<Album>
        get() = if (query.isEmpty()) albums else albums.filter {
            it.title.contains(query, ignoreCase = true)
        }
}

@HiltViewModel
class ArtistAlbumsViewModel internal constructor(
    private val fetchPage: suspend (String, Int) -> Resource<ArtistAlbum>,
) : ViewModel() {
    @Inject
    constructor(repository: ArtistRepository) : this(repository::getArtistAlbums)

    private val _state = MutableStateFlow(ArtistAlbumsState())
    val state = _state.asStateFlow()
    private var artistId: String? = null
    private var requestGeneration = 0L
    private var loadJob: Job? = null

    fun loadMore(id: String) {
        selectArtist(id)
        if (_state.value.query.isNotEmpty()) return
        load(id, pageBudget = 1)
    }

    fun updateQuery(id: String, query: String) {
        selectArtist(id)
        val normalized = query.trim()
        if (_state.value.query == normalized) return
        cancelLoad()
        _state.value = _state.value.copy(query = normalized, isLoading = false, error = null)
        if (normalized.isNotEmpty()) load(id, ArtistCatalogSearchPageBudget, debounce = true)
    }

    fun continueSearch(id: String) {
        selectArtist(id)
        if (_state.value.query.isNotEmpty()) load(id, ArtistCatalogSearchPageBudget)
    }

    private fun selectArtist(id: String) {
        if (artistId == id) return
        cancelLoad()
        artistId = id
        _state.value = ArtistAlbumsState()
    }

    private fun cancelLoad() {
        requestGeneration += 1
        loadJob?.cancel()
    }

    private fun load(id: String, pageBudget: Int, debounce: Boolean = false) {
        if (_state.value.isLoading || !_state.value.hasMore) return
        val generation = requestGeneration
        val query = _state.value.query
        _state.value = _state.value.copy(isLoading = true, error = null)
        loadJob = viewModelScope.launch {
            try {
                if (debounce) delay(ArtistCatalogSearchDebounceMillis)
                repeat(pageBudget) {
                    val previous = _state.value
                    val result = fetchPage(id, previous.offset)
                    if (!isActive || generation != requestGeneration || artistId != id ||
                        _state.value.query != query
                    ) return@launch
                    when (result) {
                        is Resource.Success -> {
                            val page = result.data.hotAlbums.orEmpty()
                            _state.value = previous.copy(
                                albums = (previous.albums + page.map { it.toAlbum() }).distinctBy { it.id },
                                offset = previous.offset + page.size,
                                hasMore = result.data.more && page.isNotEmpty(),
                                error = null,
                            )
                            if (!_state.value.hasMore) return@launch
                        }
                        is Resource.Error -> {
                            _state.value = previous.copy(error = result.message)
                            return@launch
                        }
                        is Resource.Loading -> return@launch
                    }
                }
            } finally {
                if (generation == requestGeneration && artistId == id && _state.value.query == query) {
                    _state.value = _state.value.copy(isLoading = false)
                }
            }
        }
    }
}
