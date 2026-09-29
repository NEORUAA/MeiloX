package com.ljyh.mei.ui.screen.artist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.toMediaMetadata
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.data.repository.ArtistRepository
import com.ljyh.mei.data.repository.ArtistSource
import com.ljyh.mei.data.session.SessionStore
import com.ljyh.mei.data.session.SessionStamp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ArtistSongsState(
    val id: String? = null,
    val session: SessionStamp? = null,
    val revision: Long = 0,
    val songs: List<MediaMetadata> = emptyList(),
    val offset: Int = 0,
    val hasMore: Boolean = true,
    val isLoading: Boolean = false,
    val error: String? = null,
)

class ArtistSongsViewModel internal constructor(
    private val source: ArtistSource,
    private val sessions: SessionStore,
) : ViewModel() {
    @Inject constructor(repository: ArtistRepository, sessions: SessionStore) : this(repository as ArtistSource, sessions)
    private val _state = MutableStateFlow(ArtistSongsState())
    val state = _state.asStateFlow()
    private val lock = Any()
    private var revision = 0L
    private var requestedId: String? = null
    private var pageJob: Job? = null
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
                else if (state.value.session != owner) requestedId?.let(::open)
            }
        }
    }

    private fun clear() {
        pageJob?.cancel()
        _state.value = ArtistSongsState(id = requestedId, revision = ++revision,
            error = if (sessions.recoveryRequired.value) "Official session recovery is required" else null)
    }

    fun open(id: String) {
        requestedId = id
        if (sessions.recoveryRequired.value) return
        val owner = runCatching { sessions.snapshot() }.getOrNull() ?: return
        val changed = runCatching {
            sessions.withCurrent(owner) {
                synchronized(lock) {
                    if (state.value.id == id && state.value.session == owner) false
                    else {
                        clear()
                        _state.value = state.value.copy(session = owner)
                        true
                    }
                }
            }
        }.getOrDefault(false)
        if (changed) loadMore(id)
    }

    fun loadMore(id: String) {
        if (state.value.id != id || state.value.session == null) {
            open(id)
            return
        }
        val previous = _state.value
        val owner = previous.session ?: return
        if (previous.isLoading || !previous.hasMore) return
        val accepted = runCatching {
            sessions.withCurrent(owner) {
                synchronized(lock) {
                    if (state.value != previous || sessions.recoveryRequired.value) false
                    else { _state.value = previous.copy(isLoading = true, error = null); true }
                }
            }
        }.getOrDefault(false)
        if (!accepted) return
        pageJob = viewModelScope.launch {
            try {
                val result = source.songs(id, previous.offset, owner)
                currentCoroutineContext().ensureActive()
                when (result) {
                    is Resource.Success -> {
                        check(result.data.code == 200) { "Artist songs failed (${result.data.code})" }
                        val page = checkNotNull(result.data.songs) { "Missing artist songs" }.map { it.toMediaMetadata() }
                        val newIds = page.map { it.id }.toSet() - previous.songs.map { it.id }.toSet()
                        check(!result.data.more || newIds.isNotEmpty()) { "Artist song cursor did not advance" }
                        val nextOffset = previous.offset.toLong() + page.size
                        check(nextOffset <= Int.MAX_VALUE) { "Artist song cursor overflow" }
                        publish(previous) { previous.copy(
                            songs = (previous.songs + page).distinctBy { it.id },
                            offset = nextOffset.toInt(), hasMore = result.data.more, error = null,
                        ) }
                    }
                    is Resource.Error -> publish(previous) { previous.copy(error = result.message) }
                    Resource.Loading -> publish(previous) { previous.copy(error = "Incomplete artist page") }
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                publish(previous) { previous.copy(error = error.message ?: "Artist songs failed") }
            }
        }
    }

    fun withCurrent(expected: ArtistSongsState, action: () -> Unit) {
        val owner = expected.session ?: return
        runCatching {
            sessions.withCurrent(owner) {
                synchronized(lock) {
                    if (!sessions.recoveryRequired.value && state.value.id == expected.id && state.value.revision == expected.revision && state.value.session == owner) action()
                }
            }
        }
    }

    private fun publish(expected: ArtistSongsState, update: () -> ArtistSongsState) =
        withCurrent(expected) { _state.value = update() }

    override fun onCleared() {
        invalidation.close()
        synchronized(lock) { clear() }
        super.onCleared()
    }
}
