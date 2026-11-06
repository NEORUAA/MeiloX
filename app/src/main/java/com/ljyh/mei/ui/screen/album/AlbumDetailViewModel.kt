package com.ljyh.mei.ui.screen.album

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ljyh.mei.constants.MusicQuality
import com.ljyh.mei.data.model.AlbumDetail
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.source.netease.repository.LibraryAccount
import com.ljyh.mei.source.netease.repository.LibraryCollectionsRepository
import com.ljyh.mei.source.netease.repository.PlaylistRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class AlbumSubscriptionError { Load, Update }

data class AlbumSubscriptionState(
    val subscribed: Boolean? = null,
    val isLoading: Boolean = true,
    val isUpdating: Boolean = false,
    val error: AlbumSubscriptionError? = null,
)

@HiltViewModel
class AlbumDetailViewModel @Inject constructor(
    private val repository: PlaylistRepository,
    private val collections: LibraryCollectionsRepository,
) : ViewModel() {
    private val _albumDetail = MutableStateFlow<Resource<AlbumDetail>>(Resource.Loading)
    val albumDetail = _albumDetail.asStateFlow()
    private val _subscription = MutableStateFlow(AlbumSubscriptionState())
    val subscription = _subscription.asStateFlow()
    private var activeAccount: LibraryAccount? = null
    private var albumId: Long? = null
    private var detailJob: Job? = null
    private var subscriptionJob: Job? = null

    init {
        viewModelScope.launch {
            collections.accounts.collect { account ->
                subscriptionJob?.cancel()
                activeAccount = account
                _subscription.value = AlbumSubscriptionState(isLoading = account != null)
                albumId?.let(::refreshSubscription)
                if (account == null) collections.restoreAccountIfNeeded()
            }
        }
    }

    fun getAlbumDetail(id: String) {
        detailJob?.cancel()
        detailJob = viewModelScope.launch {
            _albumDetail.value = Resource.Loading
            _albumDetail.value = repository.getAlbumDetail(id)
        }
    }

    fun refreshSubscription(id: Long) {
        albumId = id
        val account = activeAccount ?: return
        if (_subscription.value.isUpdating) return
        subscriptionJob?.cancel()
        _subscription.value = AlbumSubscriptionState()
        subscriptionJob = viewModelScope.launch {
            try {
                val subscribed = collections.albumSubscription(account, id)
                if (activeAccount == account && albumId == id) {
                    _subscription.value = AlbumSubscriptionState(subscribed = subscribed, isLoading = false)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (activeAccount == account && albumId == id) {
                    _subscription.value = AlbumSubscriptionState(isLoading = false, error = AlbumSubscriptionError.Load)
                }
            }
        }
    }

    fun toggleSubscription(id: Long) {
        val account = activeAccount ?: return
        val previous = _subscription.value
        if (previous.isLoading || previous.isUpdating) return
        val subscribed = previous.subscribed ?: return refreshSubscription(id)
        _subscription.value = previous.copy(isUpdating = true, error = null)
        subscriptionJob = viewModelScope.launch {
            try {
                collections.setAlbumSubscription(account, id, !subscribed)
                if (activeAccount == account && albumId == id) {
                    _subscription.value = AlbumSubscriptionState(subscribed = !subscribed, isLoading = false)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (activeAccount == account && albumId == id) {
                    _subscription.value = previous.copy(isUpdating = false, error = AlbumSubscriptionError.Update)
                }
            }
        }
    }

    suspend fun resolveSongUrls(ids: List<String>, quality: MusicQuality) = repository.getSongUrlV1(ids, quality)
}
