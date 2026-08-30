package com.ljyh.mei.ui.screen.history

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ljyh.mei.constants.CookieKey
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.melox.AccountSong
import com.ljyh.mei.data.model.room.HistoryItem
import com.ljyh.mei.data.model.toMediaMetadata
import com.ljyh.mei.data.repository.MeloXRepository
import com.ljyh.mei.di.repository.HistoryRepository
import com.ljyh.mei.utils.dataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
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

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val localRepository: HistoryRepository,
    private val remoteRepository: MeloXRepository,
    @param:ApplicationContext private val context: Context,
) : ViewModel() {
    private val _state = MutableStateFlow(HistoryUiState(isRefreshing = true))
    val state: StateFlow<HistoryUiState> = _state

    private var localEntries: List<ListeningHistoryEntry> = emptyList()
    private var remoteEntries: List<ListeningHistoryEntry>? = null
    private var remoteRequestStartedAt: Long? = null
    private var loadedCookie: String? = null
    private var refreshJob: Job? = null

    init {
        viewModelScope.launch {
            localRepository.getHistoryStream().collect { history ->
                localEntries = history.toListeningHistoryEntries()
                publish()
            }
        }
        refresh()
    }

    fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            val cookie = context.dataStore.data.first()[CookieKey].orEmpty().trim()
            if (cookie.isEmpty()) {
                loadedCookie = null
                remoteEntries = null
                remoteRequestStartedAt = null
                _state.value = _state.value.copy(isRefreshing = false, error = null)
                publish()
                return@launch
            }
            if (loadedCookie != null && loadedCookie != cookie) {
                remoteEntries = null
                remoteRequestStartedAt = null
            }
            _state.value = _state.value.copy(isRefreshing = true, error = null)
            val requestStartedAt = System.currentTimeMillis()
            try {
                val songs = remoteRepository.recentSongs()
                loadedCookie = cookie
                remoteEntries = songs.map(AccountSong::toListeningHistoryEntry)
                remoteRequestStartedAt = remoteRequestStartedAt ?: requestStartedAt
                _state.value = _state.value.copy(isRefreshing = false, error = null)
                publish()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.value = _state.value.copy(
                    isRefreshing = false,
                    error = error.message ?: error.javaClass.simpleName,
                )
                publish()
            }
        }
    }

    fun clearHistory() {
        viewModelScope.launch { localRepository.clearHistory() }
    }

    private fun publish() {
        _state.value = _state.value.copy(
            items = mergeHistoryEntries(remoteEntries, localEntries, remoteRequestStartedAt),
            canClearLocalHistory = remoteEntries == null && localEntries.isNotEmpty(),
        )
    }
}

internal fun mergeHistoryEntries(
    remote: List<ListeningHistoryEntry>?,
    local: List<ListeningHistoryEntry>,
    remoteRequestStartedAt: Long?,
): List<ListeningHistoryEntry> {
    val deduplicatedLocal = local.distinctBy { it.song.id }
    if (remote == null) return deduplicatedLocal

    val deduplicatedRemote = remote.distinctBy { it.song.id }
    val (optimistic, olderLocal) = if (remoteRequestStartedAt == null) {
        emptyList<ListeningHistoryEntry>() to deduplicatedLocal
    } else {
        deduplicatedLocal.partition { (it.playedAt ?: Long.MIN_VALUE) >= remoteRequestStartedAt }
    }
    val optimisticIds = optimistic.mapTo(mutableSetOf()) { it.song.id }
    val remainingRemote = deduplicatedRemote.filterNot { it.song.id in optimisticIds }
    val representedIds = remainingRemote.mapTo(optimisticIds) { it.song.id }
    val fallback = olderLocal.filterNot { it.song.id in representedIds }
    return optimistic + remainingRemote + fallback
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
    playedAt = null,
)
