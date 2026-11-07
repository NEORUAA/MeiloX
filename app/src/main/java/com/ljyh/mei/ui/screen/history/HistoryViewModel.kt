package com.ljyh.mei.ui.screen.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.mediaId
import com.ljyh.mei.data.model.room.HistoryItem
import com.ljyh.mei.data.model.toMediaMetadata
import com.ljyh.mei.source.shared.SharedOnlineRepository
import com.ljyh.mei.source.api.SourceException
import com.ljyh.mei.source.api.SourceErrorCode
import com.ljyh.mei.di.repository.HistoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
class HistoryViewModel internal constructor(
    private val localRepository: HistoryRepository,
    private val remoteRepository: com.ljyh.mei.source.shared.SharedOnlineSession,
) : ViewModel() {
    @Inject constructor(localRepository: HistoryRepository, repository: SharedOnlineRepository,
        savedStateHandle: androidx.lifecycle.SavedStateHandle) :
        this(localRepository, repository.screen("listening-history", savedStateHandle["providerId"]))
    private val _state = MutableStateFlow(HistoryUiState(isRefreshing = true))
    val state: StateFlow<HistoryUiState> = _state

    private var localEntries: List<ListeningHistoryEntry> = emptyList()
    private var remoteEntries: List<ListeningHistoryEntry>? = null
    private var refreshJob: Job? = null

    init {
        viewModelScope.launch {
            localRepository.getHistoryStream().collect { history ->
                localEntries = history.toListeningHistoryEntries()
                publish()
            }
        }
        viewModelScope.launch { remoteRepository.sessionChanges.collect { owner ->
            if (owner.isBlank()) {
                refreshJob?.cancel()
                remoteEntries = null
                _state.value = _state.value.copy(isRefreshing = false, error = null)
                publish()
            }
        } }
        refresh()
    }

    fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            _state.value = _state.value.copy(isRefreshing = true, error = null)
            try {
                val features = remoteRepository.features()
                if (!features.supports("stats", "recentSongs")) {
                    remoteEntries = null
                } else {
                    remoteEntries = remoteRepository.recentSongRecords().map { record ->
                        val metadata = record.track.toMediaMetadata()
                        ListeningHistoryEntry("remote-${metadata.mediaId}", metadata, record.playedAt)
                    }
                }
                _state.value = _state.value.copy(isRefreshing = false, error = null)
                publish()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val unavailableAccount = error is SourceException && error.code in setOf(SourceErrorCode.AUTH_REQUIRED, SourceErrorCode.AUTH_EXPIRED, SourceErrorCode.UNSUPPORTED, SourceErrorCode.MISSING_PROVIDER)
                if (unavailableAccount) remoteEntries = null
                _state.value = _state.value.copy(isRefreshing = false, error = if (unavailableAccount) null else error.message)
                publish()
            }
        }
    }

    fun clearHistory() {
        viewModelScope.launch { localRepository.clearHistory() }
    }

    private fun publish() {
        _state.value = _state.value.copy(
            items = mergeHistoryEntries(remoteEntries, localEntries),
            canClearLocalHistory = remoteEntries == null && localEntries.isNotEmpty(),
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
    val selected = LinkedHashMap<String, Candidate>()
    candidates.forEachIndexed { index, entry ->
        val existing = selected[entry.song.mediaId]
        if (existing == null || entry.playedAt.isNewerThan(existing.entry.playedAt)) {
            selected[entry.song.mediaId] = Candidate(
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
