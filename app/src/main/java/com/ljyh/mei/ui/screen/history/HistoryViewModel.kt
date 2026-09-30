package com.ljyh.mei.ui.screen.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.melox.AccountSong
import com.ljyh.mei.data.model.room.HistoryItem
import com.ljyh.mei.data.model.toMediaMetadata
import com.ljyh.mei.data.model.toMediaItem
import com.ljyh.mei.data.session.SessionIdentity
import kotlinx.coroutines.flow.combine
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
    val session: SessionStamp? = null,
)

class HistoryViewModel internal constructor(
    private val localRepository: HistoryRepository,
    private val loadRecent: suspend () -> List<AccountSong>,
    private val accounts: AccountStore,
) : ViewModel() {
    @Inject constructor(localRepository: HistoryRepository, remoteRepository: MeloXRepository, accounts: AccountStore) :
        this(localRepository, { remoteRepository.recentSongs() }, accounts)
    private val _state = MutableStateFlow(HistoryUiState(isRefreshing = true))
    val state: StateFlow<HistoryUiState> = _state

    private var localEntries: List<ListeningHistoryEntry> = emptyList()
    private var remoteEntries: List<ListeningHistoryEntry>? = null
    private var loadedSession: SessionStamp? = null
    private var currentSession: SessionStamp? = null
    private var refreshJob: Job? = null

    init {
        viewModelScope.launch {
            localRepository.getHistoryStream().collect { history ->
                localEntries = history.toListeningHistoryEntries()
                publish()
            }
        }
        viewModelScope.launch {
            combine(accounts.state, accounts.sessions.recoveryRequired) { account, recovery ->
                account.session to recovery
            }.distinctUntilChanged().collect { (stamp, recovery) ->
                refreshJob?.cancel()
                currentSession = stamp
                loadedSession = null
                remoteEntries = null
                _state.value = _state.value.copy(isRefreshing = false, error = null)
                publish()
                if (stamp?.identity?.authenticated == true && !recovery) refresh()
            }
        }
    }

    fun refresh() {
        refreshJob?.cancel()
        val stamp = runCatching {
            accounts.requireAuthenticated().also {
                if (it.identity.anonymous || accounts.sessions.recoveryRequired.value) throw SessionChangedException()
            }
        }.getOrNull()
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
                val songs = loadRecent()
                val entries = songs.map(AccountSong::toListeningHistoryEntry)
                currentCoroutineContext().ensureActive()
                accounts.sessions.withCurrent(stamp) {
                    if (accounts.sessions.recoveryRequired.value) throw SessionChangedException()
                    loadedSession = stamp
                    remoteEntries = entries
                    _state.value = _state.value.copy(isRefreshing = false, error = null)
                }
                publish()
            } catch (error: CancellationException) {
                throw error
            } catch (_: SessionChangedException) {
                currentCoroutineContext().ensureActive()
                if (currentSession == stamp) _state.value = _state.value.copy(isRefreshing = false)
                publish()
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                runCatching {
                    accounts.sessions.withCurrent(stamp) {
                        if (accounts.sessions.recoveryRequired.value) throw SessionChangedException()
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

    /** Retained click callbacks must not replay another account's private metadata. */
    fun withCurrent(expected: HistoryUiState, entries: List<ListeningHistoryEntry>, action: () -> Unit) {
        runCatching {
            require(expected.session == _state.value.session)
            val visible = _state.value.items.associateBy(ListeningHistoryEntry::key)
            require(entries.all { visible[it.key] == it })
            val stamp = expected.session
            if (stamp == null) {
                require(entries.none { it.song.source?.isCloud == true })
                action()
            } else accounts.sessions.withCurrent(stamp) {
                require(accounts.state.value.session == stamp)
                require(ownedLocalHistoryEntries(entries, stamp.identity).size == entries.size)
                action()
            }
        }
    }

    private fun publish() {
        val stamp = currentSession?.takeIf {
            accounts.state.value.session == it && runCatching { accounts.sessions.requireCurrent(it) }.isSuccess
        }
        val local = ownedLocalHistoryEntries(localEntries, stamp?.identity)
        if (stamp != null) {
            val remote = remoteEntries.takeIf { loadedSession == stamp && !accounts.sessions.recoveryRequired.value }
            val merged = mergeHistoryEntries(remote, local)
            val published = runCatching {
                accounts.sessions.withCurrent(stamp) {
                    _state.value = _state.value.copy(items = merged, canClearLocalHistory = remote == null && local.isNotEmpty(), session = stamp)
                }
            }.isSuccess
            if (published) return
        }
        _state.value = _state.value.copy(
            items = mergeHistoryEntries(null, ownedLocalHistoryEntries(localEntries, null)),
            canClearLocalHistory = local.any { it.song.source?.isCloud != true },
            session = null,
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
        val key = entry.song.historyIdentityKey()
        val existing = selected[key]
        if (existing == null || entry.playedAt.isNewerThan(existing.entry.playedAt)) {
            selected[key] = Candidate(
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

internal fun List<HistoryItem>.toListeningHistoryEntries(): List<ListeningHistoryEntry> = mapNotNull { item ->
    runCatching { ListeningHistoryEntry(
        key = "local-${item.historyId}",
        song = item.song.toMediaMetadata(),
        playedAt = item.playedAt,
    ) }.getOrNull()
}

internal fun ownedLocalHistoryEntries(entries: List<ListeningHistoryEntry>, account: SessionIdentity?): List<ListeningHistoryEntry> =
    entries.filter { entry -> runCatching {
        entry.song.historyIdentityKey()
        entry.song.source?.let { source ->
            source.requireAccount(account ?: SessionIdentity(0, false, true))
        }
    }.isSuccess }

private fun MediaMetadata.historyIdentityKey(): String = source?.let {
    require(it.entryId == id && !isLocal && !isPodcast)
    it.key
} ?: id.toString()

/** Ordinary/local entries keep baseline detail hydration; private files never become placeholders. */
internal fun ListeningHistoryEntry.toHistoryQueueEntry(): Pair<String, androidx.media3.common.MediaItem?> =
    song.id.toString() to if (song.source?.isCloud == true) song.toMediaItem() else null

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
