package com.ljyh.mei.ui.screen.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.melox.AccountSong
import com.ljyh.mei.data.model.room.HistoryItem
import com.ljyh.mei.data.model.toMediaMetadata
import com.ljyh.mei.data.repository.MeloXRepository
import com.ljyh.mei.di.repository.HistoryRepository
import com.ljyh.mei.data.session.AccountStore
import com.ljyh.mei.data.session.SessionStamp
import com.ljyh.mei.data.session.SessionChangedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ListeningHistoryEntry(
    val key: String,
    val song: MediaMetadata,
    val playedAt: Long?,
)

data class HistoryUiState(
    val items: List<ListeningHistoryEntry> = emptyList(),
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val canClearLocalHistory: Boolean = false,
)

class HistoryViewModel @Inject constructor(
    private val localRepository: HistoryRepository,
    private val remoteRepository: MeloXRepository,
    private val accounts: AccountStore,
) : ViewModel() {
    private val _state = MutableStateFlow(HistoryUiState(isRefreshing = true))
    val state: StateFlow<HistoryUiState> = _state

    private var localEntries: List<ListeningHistoryEntry> = emptyList()
    private var remoteEntries: List<ListeningHistoryEntry>? = null
    private var loadedSession: SessionStamp? = null
    private var refreshJob: Job? = null

    init {
        viewModelScope.launch {
            localRepository.getHistoryStream().collect { history ->
                localEntries = history.toListeningHistoryEntries()
                publish()
            }
        }
        viewModelScope.launch {
            accounts.state.map { it.session }.distinctUntilChanged().collect {
                refreshJob?.cancel()
                loadedSession = null
                remoteEntries = null
                _state.value = _state.value.copy(isRefreshing = false, error = null)
                publish()
                if (it?.identity?.authenticated == true) refresh()
            }
        }
    }

    fun refresh() {
        refreshJob?.cancel()
        val stamp = runCatching { accounts.requireAuthenticated() }.getOrNull()
        if (stamp == null) {
            loadedSession = null
            remoteEntries = null
            _state.value = _state.value.copy(isRefreshing = false, error = null)
            publish()
            return
        }
        refreshJob = viewModelScope.launch {
            if (loadedSession != stamp) {
                remoteEntries = null
            }
            _state.value = _state.value.copy(isRefreshing = true, error = null)
            publish()
            try {
                accounts.sessions.requireCurrent(stamp)
                val songs = remoteRepository.recentSongs()
                val entries = songs.map(AccountSong::toListeningHistoryEntry)
                currentCoroutineContext().ensureActive()
                accounts.sessions.withCurrent(stamp) {
                    loadedSession = stamp
                    remoteEntries = entries
                    _state.value = _state.value.copy(isRefreshing = false, error = null)
                }
                publish()
            } catch (error: CancellationException) {
                throw error
            } catch (_: SessionChangedException) {
                // Session changes reset cloud entries without deleting device-local history.
            } catch (error: Exception) {
                runCatching {
                    accounts.sessions.withCurrent(stamp) {
                        _state.value = _state.value.copy(isRefreshing = false, error = error.message ?: error.javaClass.simpleName)
                    }
                    publish()
                }
            }
        }
    }

    fun clearHistory() {
        viewModelScope.launch { localRepository.clearHistory() }
    }

    private fun publish() {
        val stamp = loadedSession
        if (stamp != null) {
            val merged = mergeHistoryEntries(remoteEntries, localEntries)
            val published = runCatching {
                accounts.sessions.withCurrent(stamp) {
                    _state.value = _state.value.copy(items = merged, canClearLocalHistory = false)
                }
            }.isSuccess
            if (published) return
        }
        _state.value = _state.value.copy(
            items = mergeHistoryEntries(null, localEntries),
            canClearLocalHistory = localEntries.isNotEmpty(),
        )
    }
}

internal fun mergeHistoryEntries(
    remote: List<ListeningHistoryEntry>?,
    local: List<ListeningHistoryEntry>,
): List<ListeningHistoryEntry> {
    data class Candidate(
        val entry: ListeningHistoryEntry,
        val firstIndex: Int,
    )

    val candidates = buildList {
        addAll(local)
        remote?.let(::addAll)
    }
    val selected = LinkedHashMap<Long, Candidate>()
    candidates.forEachIndexed { index, entry ->
        val existing = selected[entry.song.id]
        if (existing == null || entry.playedAt.isNewerThan(existing.entry.playedAt)) {
            selected[entry.song.id] = Candidate(
                entry = entry,
                firstIndex = existing?.firstIndex ?: index,
            )
        }
    }
    return selected.values
        .sortedWith { left, right ->
            when {
                left.entry.playedAt == null && right.entry.playedAt == null ->
                    left.firstIndex.compareTo(right.firstIndex)
                left.entry.playedAt == null -> 1
                right.entry.playedAt == null -> -1
                left.entry.playedAt != right.entry.playedAt ->
                    right.entry.playedAt.compareTo(left.entry.playedAt)
                else -> left.firstIndex.compareTo(right.firstIndex)
            }
        }
        .map(Candidate::entry)
}

private fun Long?.isNewerThan(other: Long?): Boolean = when {
    this == null -> false
    other == null -> true
    else -> this > other
}

private fun List<HistoryItem>.toListeningHistoryEntries(): List<ListeningHistoryEntry> = map { item ->
    ListeningHistoryEntry(
        key = "local-${item.historyId}",
        song = item.song.toMediaMetadata(),
        playedAt = item.playedAt,
    )
}

private fun AccountSong.toListeningHistoryEntry(): ListeningHistoryEntry = ListeningHistoryEntry(
    key = "cloud-$id",
    song = MediaMetadata(
        id = id,
        title = name,
        coverUrl = coverUrl.orEmpty(),
        artists = artists.mapIndexed { index, artist ->
            MediaMetadata.Artist(
                id = artistIds.getOrNull(index) ?: artist.hashCode().toUInt().toLong(),
                name = artist,
            )
        },
        duration = durationMs,
        album = MediaMetadata.Album(
            id = albumId.takeIf { it > 0 } ?: album.hashCode().toUInt().toLong(),
            title = album,
        ),
    ),
    playedAt = playedAt,
)
