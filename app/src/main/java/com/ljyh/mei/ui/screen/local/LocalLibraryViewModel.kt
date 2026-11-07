package com.ljyh.mei.ui.screen.local

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.toMediaMetadata
import com.ljyh.mei.data.model.room.Song
import com.ljyh.mei.source.api.*
import com.ljyh.mei.source.core.SourceRouter
import com.ljyh.mei.source.core.SourcePreferences
import com.ljyh.mei.di.AppDatabase
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LocalMatchState(
    val song: Song? = null,
    val query: String = "",
    val isSearching: Boolean = false,
    val candidates: List<MediaMetadata> = emptyList(),
    val error: String? = null,
    val isApplying: Boolean = false,
)

@HiltViewModel
class LocalLibraryViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AppDatabase,
    private val sources: SourceRouter,
    private val sourcePreferences: SourcePreferences,
) : ViewModel() {
    val localSongs = database.songDao().getLocalSongs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val _scanState = MutableStateFlow(LocalScanState())
    val scanState = _scanState.asStateFlow()
    private val _matchState = MutableStateFlow(LocalMatchState())
    val matchState = _matchState.asStateFlow()
    private var scanJob: Job? = null
    @Volatile private var scanRevision = 0L
    private var matchJob: Job? = null
    private var matchRevision = 0L
    private var matchOwner: MusicProvider? = null
    private var matchAccount: String? = null

    fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, audioPermission) == PackageManager.PERMISSION_GRANTED

    fun scan() {
        if (scanJob?.isActive == true) return
        if (!hasAudioPermission()) {
            _scanState.value = LocalScanState(error = "请允许访问音乐和音频后再扫描")
            return
        }
        val revision = ++scanRevision
        scanJob = viewModelScope.launch {
            try {
                LocalLibraryScanner(context, database).scan {
                    if (revision == scanRevision) _scanState.value = it
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (revision == scanRevision) {
                    _scanState.value = _scanState.value.copy(isScanning = false, error = error.message ?: "扫描失败，请重试")
                }
            }
        }
    }

    fun cancelScan() {
        scanRevision++
        scanJob?.cancel()
        scanJob = null
        _scanState.value = _scanState.value.copy(isScanning = false, message = "扫描已停止，已发现的歌曲已保留")
    }

    fun openMatch(song: Song) {
        dismissMatch()
        val query = listOf(song.title, song.artist.joinToString(" ")).filter(String::isNotBlank).joinToString(" ")
        _matchState.value = LocalMatchState(song = song, query = query)
        searchMatches(query)
    }

    fun searchMatches(query: String) {
        val song = _matchState.value.song ?: return
        matchJob?.cancel()
        val revision = ++matchRevision
        val normalized = query.trim()
        matchOwner = null
        matchAccount = null
        _matchState.value = _matchState.value.copy(query = query, isSearching = normalized.isNotBlank(), candidates = emptyList(), error = null)
        if (normalized.isBlank()) return
        matchJob = viewModelScope.launch {
            try {
                sources.registry.awaitInitialized()
                val providerId = sourcePreferences.snapshot().preferredSearchProvider
                val owner = sources.registry.requireProvider(providerId)
                val account = (owner as? AccountRevisionCapability)?.accountRevision?.first()
                val result = sources.search(providerId, normalized, ContentType.TRACK, PageRequest(limit = 30))
                currentCoroutineContext().ensureActive()
                ensureMatchOwner(owner, account)
                if (revision != matchRevision) return@launch
                matchOwner = owner
                matchAccount = account
                _matchState.value = _matchState.value.copy(isSearching = false,
                    candidates = rankLocalMatches(song, result.items.map { (it as? SourceItem.TrackItem)?.track
                        ?: throw SourceException(SourceErrorCode.INVALID_RESPONSE) }.map { it.toMediaMetadata() }))
            } catch (error: CancellationException) {
                if (currentCoroutineContext().isActive && revision == matchRevision) {
                    _matchState.value = _matchState.value.copy(isSearching = false, candidates = emptyList(), error = "音源或账号已变更")
                }
                throw error
            } catch (error: Exception) {
                if (revision == matchRevision) _matchState.value = _matchState.value.copy(isSearching = false, error = error.message ?: "查询失败，请重试")
            }
        }
    }

    fun applyMatch(candidate: MediaMetadata) {
        val state = _matchState.value
        val song = state.song ?: return
        val owner = matchOwner ?: return
        val account = matchAccount
        if (state.isApplying || candidate !in state.candidates) return
        matchJob?.cancel()
        val revision = ++matchRevision
        _matchState.value = state.copy(isSearching = false, isApplying = true, error = null)
        matchJob = viewModelScope.launch {
            try {
                ensureMatchOwner(owner, account)
                val matched = song.withMatchedMetadata(candidate)
                database.songDao().updateLocalDisplayMetadata(song.id, matched.title, matched.artist, matched.album, matched.cover)
                if (revision == matchRevision) dismissMatch()
            } catch (error: CancellationException) {
                if (currentCoroutineContext().isActive && revision == matchRevision) {
                    _matchState.value = _matchState.value.copy(isApplying = false, candidates = emptyList(), error = "音源或账号已变更")
                }
                throw error
            } catch (error: Exception) {
                if (revision == matchRevision) _matchState.value = _matchState.value.copy(isApplying = false, error = error.message ?: "更新歌曲信息失败")
            }
        }
    }

    private suspend fun ensureMatchOwner(owner: MusicProvider, account: String?) {
        if (sources.registry.provider(owner.id) !== owner ||
            (owner as? AccountRevisionCapability)?.accountRevision?.first() != account) {
            throw CancellationException("Matching source changed")
        }
    }

    fun dismissMatch() {
        matchRevision++
        matchOwner = null
        matchAccount = null
        matchJob?.cancel()
        matchJob = null
        _matchState.value = LocalMatchState()
    }

    companion object {
        val audioPermission: String get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else Manifest.permission.READ_EXTERNAL_STORAGE
    }
}
