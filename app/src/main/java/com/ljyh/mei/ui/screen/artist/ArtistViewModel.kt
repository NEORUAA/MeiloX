package com.ljyh.mei.ui.screen.artist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ljyh.mei.data.model.api.ArtistAlbum
import com.ljyh.mei.data.model.api.ArtistDetail
import com.ljyh.mei.data.model.api.ArtistSong
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.data.repository.ArtistRepository
import com.ljyh.mei.data.repository.ArtistSource
import com.ljyh.mei.parasite.HostSessionBridge
import com.ljyh.mei.parasite.HostSessionStamp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ArtistState(
    val id: String? = null,
    val session: HostSessionStamp? = null,
    val revision: Long = 0,
    val detail: Resource<ArtistDetail> = Resource.Loading,
    val albums: Resource<ArtistAlbum> = Resource.Loading,
    val songs: Resource<ArtistSong> = Resource.Loading,
    val followed: Boolean? = null,
    val followLoading: Boolean = false,
    val mutation: Resource<Boolean>? = null,
)

class ArtistViewModel internal constructor(
    private val source: ArtistSource,
    private val sessions: HostSessionBridge,
) : ViewModel() {
    @Inject constructor(repository: ArtistRepository, sessions: HostSessionBridge) : this(repository as ArtistSource, sessions)

    private val mutableState = MutableStateFlow(ArtistState())
    val state = mutableState.asStateFlow()
    private val lock = Any()
    private var revision = 0L
    private var requestedId: String? = null
    private var loadJob: Job? = null
    private var followJob: Job? = null
    private var reloadAfterMutation = false
    private val invalidation = sessions.onInvalidated { generation ->
        synchronized(lock) {
            if ((state.value.session?.generation ?: -1) < generation) clear()
        }
    }

    init {
        viewModelScope.launch {
            combine(sessions.changes, sessions.recoveryRequired) { _, _ -> Unit }.collect {
                val owner = runCatching { sessions.snapshot() }.getOrNull()
                if (owner == null || sessions.recoveryRequired.value) synchronized(lock) { clear() }
                else if (state.value.session != owner) requestedId?.let(::load)
            }
        }
    }

    private fun clear() {
        loadJob?.cancel()
        followJob?.cancel()
        reloadAfterMutation = false
        mutableState.value = ArtistState(id = requestedId, revision = ++revision,
            detail = if (sessions.recoveryRequired.value) Resource.Error("Official session recovery is required") else Resource.Loading)
    }

    fun load(id: String) {
        requestedId = id
        if (sessions.recoveryRequired.value) return
        val owner = runCatching { sessions.snapshot() }.getOrNull() ?: return
        val expected = runCatching {
            sessions.withCurrent(owner) {
                synchronized(lock) {
                    if (state.value.id == id && state.value.session == owner && state.value.mutation is Resource.Loading) {
                        reloadAfterMutation = true
                        null
                    } else {
                        clear()
                        state.value.copy(session = owner, followLoading = true).also { mutableState.value = it }
                    }
                }
            }
        }.getOrNull() ?: return
        loadJob = viewModelScope.launch {
            launch { fetch({ source.detail(id, owner) }) { result -> publish(expected) { it.copy(detail = result) } } }
            launch { fetch({ source.albums(id, owner) }) { result -> publish(expected) { it.copy(albums = result) } } }
            launch { fetch({ source.hotSongs(id, owner) }) { result -> publish(expected) { it.copy(songs = result) } } }
        }
        loadFollow(expected, notifyFailure = false)
    }

    private fun loadFollow(expected: ArtistState, notifyFailure: Boolean) {
        val owner = expected.session ?: return
        val id = expected.id ?: return
        followJob = viewModelScope.launch {
            fetch({ source.followed(id, owner) }) { result ->
                publish(expected) { it.copy(
                    followed = (result as? Resource.Success)?.data,
                    followLoading = false,
                    mutation = if (notifyFailure && result is Resource.Error) result else null,
                ) }
            }
        }
    }

    fun toggleFollow(expected: ArtistState) {
        val owner = expected.session ?: return
        val id = expected.id ?: return
        var retry = false
        val accepted = runCatching {
            sessions.withCurrent(owner) {
                synchronized(lock) {
                    val current = state.value
                    if (!matches(expected) || current.followLoading || current.followed != expected.followed || sessions.recoveryRequired.value) false
                    else if (!owner.identity.authenticated) {
                        mutableState.value = current.copy(mutation = Resource.Error("请先登录网易云"))
                        false
                    } else {
                        retry = current.followed == null
                        mutableState.value = current.copy(followLoading = true, mutation = if (retry) null else Resource.Loading)
                        true
                    }
                }
            }
        }.getOrDefault(false)
        if (!accepted) return
        if (retry) {
            loadFollow(expected, notifyFailure = true)
            return
        }
        followJob = viewModelScope.launch {
            fetch({ source.follow(id, expected.followed != true, owner) }) { result ->
                publish(expected) {
                    if (result is Resource.Success) it.copy(followed = expected.followed != true, followLoading = false, mutation = Resource.Success(expected.followed != true))
                    else it.copy(followed = null, followLoading = false, mutation = result as? Resource.Error ?: Resource.Error("Incomplete artist mutation"))
                }
            }
            val reload = synchronized(lock) {
                (matches(expected) && reloadAfterMutation).also { if (it) reloadAfterMutation = false }
            }
            if (reload) load(id)
        }
    }

    fun consumeMutation(result: Resource<Boolean>) = synchronized(lock) {
        if (state.value.mutation === result) mutableState.value = state.value.copy(mutation = null)
    }

    fun withCurrent(expected: ArtistState, action: () -> Unit) {
        val owner = expected.session ?: return
        runCatching {
            sessions.withCurrent(owner) { synchronized(lock) { if (matches(expected)) action() } }
        }
    }

    private fun matches(expected: ArtistState) = !sessions.recoveryRequired.value && state.value.revision == expected.revision &&
        state.value.id == expected.id && state.value.session == expected.session

    private fun publish(expected: ArtistState, update: (ArtistState) -> ArtistState) =
        withCurrent(expected) { mutableState.value = update(state.value) }

    private suspend fun <T> fetch(action: suspend () -> Resource<T>, accept: (Resource<T>) -> Unit) {
        val result = try { action() } catch (error: CancellationException) { throw error }
        catch (_: Exception) { Resource.Error("Official artist request failed") }
        currentCoroutineContext().ensureActive()
        accept(result)
    }

    override fun onCleared() {
        invalidation.close()
        synchronized(lock) { clear() }
        super.onCleared()
    }
}
