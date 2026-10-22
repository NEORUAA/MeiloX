package com.ljyh.mei.ui.screen.main.home

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ljyh.mei.data.model.eapi.HomePageResourceShow
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.data.repository.HomePageOwner
import com.ljyh.mei.data.repository.HomeRepository
import com.ljyh.mei.data.repository.MeloXRepository
import com.ljyh.mei.di.repository.ColorRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: HomeRepository,
    private val colorRepository: ColorRepository,
    private val meloXRepository: MeloXRepository,
) : ViewModel() {
    private val _homePageResourceShow =
        MutableStateFlow<Resource<List<HomePageResourceShow.Data.Block>>>(Resource.Loading)
    val homePageResourceShow: StateFlow<Resource<List<HomePageResourceShow.Data.Block>>> = _homePageResourceShow
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing
    private val _refreshErrors = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val refreshErrors = _refreshErrors.asSharedFlow()
    private var observedOwner: HomePageOwner? = null
    private var displayedOwner: HomePageOwner? = null
    private var requestJob: Job? = null
    private var requestVersion = 0L

    init {
        viewModelScope.launch {
            repository.owners.collect { owner ->
                observedOwner = owner
                homePageResourceShow()
            }
        }
    }

    fun homePageResourceShow(refresh: Boolean = false) {
        val owner = observedOwner ?: return
        if (!refresh && displayedOwner == owner && _homePageResourceShow.value is Resource.Success) return
        requestJob?.cancel()
        val version = ++requestVersion
        val keepContent = refresh && displayedOwner == owner && _homePageResourceShow.value is Resource.Success
        if (!keepContent) {
            displayedOwner = null
            _homePageResourceShow.value = Resource.Loading
        }
        _isRefreshing.value = keepContent
        requestJob = viewModelScope.launch {
            try {
                val result = repository.getHomePageResourceShow(refresh, owner)
                val currentOwner = repository.owners.first()
                currentCoroutineContext().ensureActive()
                if (version == requestVersion && observedOwner == owner && currentOwner == owner) {
                    if (keepContent && result is Resource.Error) {
                        _refreshErrors.tryEmit(Unit)
                    } else {
                        displayedOwner = owner
                        _homePageResourceShow.value = result
                    }
                }
            } finally {
                if (version == requestVersion) _isRefreshing.value = false
            }
        }
    }
    fun getCachedColor(url: String) = colorRepository.getFromMemory(url)

    suspend fun loadCachedColor(url: String) = colorRepository.getCachedColor(url)

    suspend fun podcastProgram(id: Long) = meloXRepository.podcastProgram(id)

    suspend fun getOrExtractColor(url: String, bitmap: Bitmap) = colorRepository.getColorOrExtract(url, bitmap)
}
