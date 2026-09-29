package com.ljyh.mei.ui.screen.album

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ljyh.mei.constants.MusicQuality
import com.ljyh.mei.data.model.AlbumDetail
import com.ljyh.mei.data.model.api.BaseResponse
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.data.repository.AccountLibraryRepository
import com.ljyh.mei.data.repository.AlbumDetailSource
import com.ljyh.mei.data.repository.PlaylistRepository
import com.ljyh.mei.parasite.HostSessionBridge
import com.ljyh.mei.parasite.HostSessionChangedException
import com.ljyh.mei.parasite.HostSessionStamp
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class AlbumDetailState(
    val session: HostSessionStamp? = null,
    val id: String? = null,
    val detail: Resource<AlbumDetail> = Resource.Loading,
    val collected: Boolean? = null,
    val changingCollection: Boolean = false,
    val mutation: Resource<BaseResponse>? = null,
)

class AlbumDetailViewModel internal constructor(
    private val repository: AlbumDetailSource,
    private val sessions: HostSessionBridge,
    private val onCollectionChanged: (HostSessionStamp) -> Unit,
) : ViewModel() {
    @Inject constructor(repository: PlaylistRepository, sessions: HostSessionBridge, library: AccountLibraryRepository) :
        this(repository as AlbumDetailSource, sessions, library::invalidateCollections)

    private val mutableState = MutableStateFlow(AlbumDetailState())
    val state = mutableState.asStateFlow()
    private val stateLock = Any()
    private var version = 0L
    private var loadedId: String? = null
    private var reloadAfterMutation = false
    private var loadJob: Job? = null
    private var mutationJob: Job? = null
    private val invalidation = sessions.onInvalidated { revision ->
        synchronized(stateLock) {
            if ((state.value.session?.generation ?: -1) < revision) {
                version++
                reloadAfterMutation = false
                mutableState.value = pendingState()
            }
        }
    }

    init {
        viewModelScope.launch {
            combine(sessions.changes, sessions.recoveryRequired) { _, _ -> Unit }.collect {
                val stamp = runCatching { sessions.snapshot() }.getOrNull()
                if (stamp == null) {
                    loadJob?.cancel()
                    mutationJob?.cancel()
                    synchronized(stateLock) {
                        version++
                        reloadAfterMutation = false
                        mutableState.value = pendingState()
                    }
                } else if (state.value.session != stamp) loadedId?.let(::getAlbumDetail)
            }
        }
    }

    private fun pendingState() = AlbumDetailState(id = loadedId, detail =
        if (sessions.recoveryRequired.value) Resource.Error("Official session recovery is required") else Resource.Loading)

    fun getAlbumDetail(id: String) {
        loadedId = id
        val stamp = runCatching { sessions.snapshot() }.getOrNull() ?: return
        val requestVersion = runCatching {
            sessions.withCurrent(stamp) {
                synchronized(stateLock) {
                    // A read started during a write can observe the pre-mutation server state.
                    if (state.value.id == id && state.value.session == stamp && state.value.changingCollection) {
                        reloadAfterMutation = true
                        null
                    } else {
                        reloadAfterMutation = false
                        mutableState.value = AlbumDetailState(session = stamp, id = id)
                        ++version
                    }
                }
            }
        }.getOrNull() ?: return
        loadJob?.cancel()
        mutationJob?.cancel()
        loadJob = viewModelScope.launch {
            try {
                sessions.requireCurrent(stamp)
                val (detail, collected) = coroutineScope {
                    val detail = async { repository.getAlbumDetail(id, stamp).valueOrThrow() }
                    val collection = async {
                        if (stamp.identity.authenticated) repository.getAlbumCollection(id, stamp).valueOrThrow() else false
                    }
                    detail.await() to collection.await()
                }
                check(detail.code == 200) { "Album request failed (${detail.code})" }
                check(detail.album.id.toString() == id) { "Official album identity mismatch" }
                currentCoroutineContext().ensureActive()
                publish(stamp, requestVersion) { it.copy(detail = Resource.Success(detail), collected = collected) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: HostSessionChangedException) {
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                runCatching { publish(stamp, requestVersion) { it.copy(detail = Resource.Error(error.message ?: "Album request failed")) } }
            }
        }
    }

    fun toggleCollection() {
        val current = state.value
        val stamp = current.session ?: return
        val id = current.id ?: return
        val before = current.collected ?: return
        if (current.detail !is Resource.Success || current.changingCollection) return
        val requestVersion = runCatching {
            sessions.withCurrent(stamp) {
                synchronized(stateLock) {
                    if (state.value != current) null
                    else if (!stamp.identity.authenticated) {
                        mutableState.value = current.copy(mutation = Resource.Error("Official login is required"))
                        null
                    } else {
                        mutableState.value = current.copy(collected = !before, changingCollection = true, mutation = null)
                        version
                    }
                }
            }
        }.getOrNull() ?: return
        mutationJob = viewModelScope.launch {
            try {
                sessions.requireCurrent(stamp)
                val result = repository.setAlbumCollection(id, !before, stamp).valueOrThrow()
                check(result.code == 200) { "Album collection request failed (${result.code})" }
                currentCoroutineContext().ensureActive()
                val accepted = publish(stamp, requestVersion) {
                    it.copy(collected = !before, changingCollection = false, mutation = Resource.Success(result))
                }
                if (accepted) runCatching { onCollectionChanged(stamp) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: HostSessionChangedException) {
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                runCatching { publish(stamp, requestVersion) {
                    it.copy(collected = before, changingCollection = false,
                        mutation = Resource.Error(error.message ?: "Album collection request failed"))
                } }
            } finally {
                val reload = synchronized(stateLock) {
                    (version == requestVersion && reloadAfterMutation).also { if (it) reloadAfterMutation = false }
                }
                if (reload && currentCoroutineContext()[Job]?.isActive == true) getAlbumDetail(id)
            }
        }
    }

    fun consumeMutation(result: Resource<BaseResponse>) {
        synchronized(stateLock) {
            if (state.value.mutation === result) mutableState.value = state.value.copy(mutation = null)
        }
    }

    fun requireCurrent(stamp: HostSessionStamp, id: String) {
        sessions.withCurrent(stamp) {
            synchronized(stateLock) {
                if (state.value.session != stamp || state.value.id != id || state.value.detail !is Resource.Success) {
                    throw CancellationException("Album changed")
                }
            }
        }
    }

    suspend fun resolveSongUrls(ids: List<String>, quality: MusicQuality, stamp: HostSessionStamp, albumId: String) =
        requireCurrent(stamp, albumId).let {
            repository.getSongUrlV1(ids, quality, stamp).also {
                currentCoroutineContext().ensureActive()
                requireCurrent(stamp, albumId)
            }
        }

    private fun publish(stamp: HostSessionStamp, requestVersion: Long, update: (AlbumDetailState) -> AlbumDetailState): Boolean =
        sessions.withCurrent(stamp) {
            synchronized(stateLock) {
                (version == requestVersion).also { if (it) mutableState.value = update(state.value) }
            }
        }

    override fun onCleared() {
        invalidation.close()
        synchronized(stateLock) { version++ }
        super.onCleared()
    }
}

private fun <T> Resource<T>.valueOrThrow(): T = when (this) {
    is Resource.Success -> data
    is Resource.Error -> error(message)
    Resource.Loading -> error("Official album request did not complete")
}
