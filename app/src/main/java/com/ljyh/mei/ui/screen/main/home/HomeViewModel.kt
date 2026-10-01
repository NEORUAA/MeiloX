package com.ljyh.mei.ui.screen.main.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ljyh.mei.AppContext
import com.ljyh.mei.data.model.eapi.HomePageResourceShow
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.data.repository.HomeRepository
import com.ljyh.mei.di.repository.ColorRepository
import com.ljyh.mei.data.session.SessionStore
import com.ljyh.mei.data.session.SessionChangedException
import com.ljyh.mei.data.session.SessionStamp
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

class HomeViewModel @Inject constructor(
    private val repository: HomeRepository,
    private val colorRepository: ColorRepository,
    private val sessions: SessionStore,
) : ViewModel() {
    private val _homePageResourceShow =
        MutableStateFlow<Resource<List<HomePageResourceShow.Data.Block>>>(Resource.Loading)
    val homePageResourceShow: StateFlow<Resource<List<HomePageResourceShow.Data.Block>>> = _homePageResourceShow
    private val stateLock = Any()
    private val requestVersion = AtomicLong()
    private var requestJob: Job? = null
    @Volatile private var displayedSession: SessionStamp? = null
    private val invalidation = sessions.onInvalidated { revision ->
        // Clear personalized content before the next main-thread collection.
        synchronized(stateLock) {
            if ((displayedSession?.generation ?: -1L) < revision) {
                requestVersion.incrementAndGet()
                _homePageResourceShow.value = pendingState()
            }
        }
    }

    init {
        viewModelScope.launch {
            combine(sessions.changes, sessions.recoveryRequired) { _, _ -> Unit }
                .collect { homePageResourceShow() }
        }
    }

    fun homePageResourceShow(refresh: Boolean = false) {
        val stamp = if (sessions.recoveryRequired.value) null
            else runCatching { sessions.snapshot() }.getOrNull()
        if (stamp == null) {
            requestVersion.incrementAndGet()
            requestJob?.cancel()
            displayedSession = null
            _homePageResourceShow.value = pendingState()
            return
        }
        if (!refresh && displayedSession == stamp && _homePageResourceShow.value is Resource.Success) return
        requestJob?.cancel()
        val version = runCatching {
            sessions.withCurrent(stamp) {
                if (sessions.recoveryRequired.value) throw SessionChangedException()
                synchronized(stateLock) {
                    displayedSession = stamp
                    _homePageResourceShow.value = Resource.Loading
                    requestVersion.incrementAndGet()
                }
            }
        }.getOrElse { return }
        requestJob = viewModelScope.launch {
            try {
                val result = repository.getHomePageResourceShow(stamp, refresh)
                currentCoroutineContext().ensureActive()
                publish(stamp, version, result)
            } catch (error: CancellationException) {
                throw error
            } catch (_: SessionChangedException) {
                // The new session owns the next request and visible state.
            } catch (error: Exception) {
                runCatching { publish(stamp, version, Resource.Error(error.message ?: "Official home request failed")) }
            }
        }
    }

    private fun publish(
        stamp: SessionStamp,
        version: Long,
        result: Resource<List<HomePageResourceShow.Data.Block>>,
    ) {
        sessions.withCurrent(stamp) {
            synchronized(stateLock) {
                if (requestVersion.get() == version) {
                    displayedSession = stamp.takeUnless { sessions.recoveryRequired.value }
                    _homePageResourceShow.value = if (sessions.recoveryRequired.value) pendingState() else result
                }
            }
        }
    }

    private fun pendingState(): Resource<Nothing> = if (sessions.recoveryRequired.value) {
        Resource.Error("Official session recovery is required")
    } else Resource.Loading

    fun getCachedColor(url: String) = colorRepository.getFromMemory(url)

    suspend fun getOrExtractColor(url: String) = colorRepository.getColorOrExtract(AppContext.instance, url)

    override fun onCleared() {
        invalidation.close()
        requestVersion.incrementAndGet()
        super.onCleared()
    }
}
