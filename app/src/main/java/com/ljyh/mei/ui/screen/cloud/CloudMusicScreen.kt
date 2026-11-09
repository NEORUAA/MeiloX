package com.ljyh.mei.ui.screen.cloud

import com.ljyh.mei.ui.navigation.meiloxHeaderAction
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import com.kyant.capsule.ContinuousRoundedRectangle
import com.ljyh.mei.R
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.source.shared.OnlineCloudPage as CloudMusicPage
import com.ljyh.mei.source.shared.OnlineCloudSong as CloudSong
import com.ljyh.mei.data.model.toMediaMetadata
import com.ljyh.mei.data.model.toMediaItem
import com.ljyh.mei.source.shared.SharedOnlineRepository
import com.ljyh.mei.source.shared.SharedOnlineSession
import com.ljyh.mei.playback.queue.ListQueue
import com.ljyh.mei.ui.component.globalProfileHeaderAction
import io.github.neoruaa.glasmio.ui.GlasmioButton
import io.github.neoruaa.glasmio.ui.GlasmioCard
import io.github.neoruaa.glasmio.glass.GlassEmphasis
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import com.ljyh.mei.ui.navigation.MeiloXPinnedListPage
import io.github.neoruaa.glasmio.icons.SfIcon
import io.github.neoruaa.glasmio.icons.SfSymbol
import com.ljyh.mei.ui.local.LocalNavController
import com.ljyh.mei.ui.local.LocalPlayerAwareWindowInsets
import com.ljyh.mei.ui.local.LocalPlayerConnection
import com.ljyh.mei.ui.screen.main.library.component.groupedLazyItems
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class CloudMusicUiState(
    val isLoading: Boolean = true,
    val page: CloudMusicPage? = null,
    val deletingIds: Set<String> = emptySet(),
    val isUploading: Boolean = false,
    val uploadProgress: Float = 0f,
    val error: String? = null,
    val isLoadingMore: Boolean = false,
    val loadMoreError: String? = null,
)

@HiltViewModel
class CloudMusicViewModel internal constructor(
    private val loadPage: suspend (Int) -> CloudMusicPage,
    private val deleteSong: suspend (String) -> Unit,
    private val uploadSong: suspend (Uri, (Long, Long) -> Unit) -> Unit,
    private val pickAndUpload: suspend () -> Unit = { throw com.ljyh.mei.source.api.SourceException(com.ljyh.mei.source.api.SourceErrorCode.UNSUPPORTED) },
    private val loadFreshPage: suspend (Int) -> CloudMusicPage = loadPage,
) : ViewModel() {
    @Inject
    constructor(repository: SharedOnlineRepository, savedStateHandle: androidx.lifecycle.SavedStateHandle) : this(
        repository.screen("cloud-music", savedStateHandle["providerId"]),
    )
    internal constructor(repository: SharedOnlineSession) : this(
        { offset -> repository.cloudSongs(offset = offset, limit = if (offset == 0) 30 else 200) },
        repository::deleteCloudSong,
        { _, _ -> repository.uploadCloudSong() },
        repository::uploadCloudSong,
        { offset -> repository.cloudSongs(offset = offset, limit = if (offset == 0) 30 else 200, refresh = true) },
    ) {
        viewModelScope.launch { repository.sessionChanges.collect { owner ->
            if (owner.isBlank()) synchronized(lock) {
                readRevision++; uploadRevision++
                readJob?.cancel()
                _state.value = CloudMusicUiState(isLoading = false)
            }
        } }
    }

    private val _state = MutableStateFlow(CloudMusicUiState())
    val state = _state.asStateFlow()
    private val lock = Any()
    private var readRevision = 0L
    private var readJob: Job? = null
    private var uploadRevision = 0L

    init { startRead(refresh = false) }

    fun refresh() = startRead(refresh = true)

    private fun startRead(refresh: Boolean) {
        val job = synchronized(lock) {
            val expected = ++readRevision
            readJob?.cancel()
            _state.value = _state.value.copy(
                isLoading = true,
                isLoadingMore = false,
                loadMoreError = null,
                error = null,
            )
            viewModelScope.launch(start = CoroutineStart.LAZY) { readPage(expected, 0, append = false, refresh = refresh) }
                .also { readJob = it }
        }
        job.start()
    }

    fun loadMore() {
        val job = synchronized(lock) {
            val current = _state.value
            val page = current.page ?: return
            if (current.isLoading || current.isLoadingMore || !page.hasMore) return
            val expected = ++readRevision
            _state.value = current.copy(isLoadingMore = true, loadMoreError = null)
            viewModelScope.launch(start = CoroutineStart.LAZY) {
                readPage(expected, page.nextOffset, append = true)
            }.also { readJob = it }
        }
        job.start()
    }

    private suspend fun readPage(expected: Long, offset: Int, append: Boolean, refresh: Boolean = false) {
        try {
            val incoming = if (refresh) loadFreshPage(offset) else loadPage(offset)
            currentCoroutineContext().ensureActive()
            publishRead(expected) { current ->
                if (append) current.withCloudPage(incoming)
                else current.copy(
                    isLoading = false,
                    page = incoming.copy(songs = incoming.songs.distinctBy(CloudSong::id)),
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            publishRead(expected) { current ->
                if (append) current.copy(isLoadingMore = false, loadMoreError = error.message)
                else current.copy(isLoading = false, error = error.message)
            }
        } finally {
            synchronized(lock) {
                if (readRevision == expected) readJob = null
            }
        }
    }

    private fun publishRead(expected: Long, update: (CloudMusicUiState) -> CloudMusicUiState) {
        synchronized(lock) {
            if (readRevision == expected) _state.value = update(_state.value)
        }
    }

    fun delete(song: CloudSong) {
        val job = synchronized(lock) {
            if (song.id in _state.value.deletingIds) return
            _state.value = _state.value.copy(deletingIds = _state.value.deletingIds + song.id)
            viewModelScope.launch(start = CoroutineStart.LAZY) {
                try {
                    deleteSong(song.id)
                    currentCoroutineContext().ensureActive()
                    synchronized(lock) {
                        val current = _state.value
                        _state.value = current.copy(page = current.page?.let { page ->
                            page.copy(songs = page.songs.filterNot { it.id == song.id })
                        })
                    }
                    refresh()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    synchronized(lock) { _state.value = _state.value.copy(error = error.message) }
                } finally {
                    synchronized(lock) {
                        _state.value = _state.value.copy(deletingIds = _state.value.deletingIds - song.id)
                    }
                }
            }
        }
        job.start()
    }

    fun upload(uri: Uri) = upload { progress -> uploadSong(uri, progress) }
    fun upload() = upload { _ -> pickAndUpload() }

    internal fun upload(operation: suspend ((Long, Long) -> Unit) -> Unit) {
        val job = synchronized(lock) {
            if (_state.value.isUploading) return
            val expected = ++uploadRevision
            _state.value = _state.value.copy(isUploading = true, uploadProgress = 0f, error = null)
            viewModelScope.launch(start = CoroutineStart.LAZY) {
                try {
                    operation { sent, total ->
                        publishUpload(expected) {
                            it.copy(uploadProgress = if (total > 0) (sent.toFloat() / total).coerceIn(0f, 1f) else 0f)
                        }
                    }
                    currentCoroutineContext().ensureActive()
                    publishUpload(expected) { it.copy(isUploading = false, uploadProgress = 1f) }
                    refresh()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    publishUpload(expected) { it.copy(error = error.message) }
                } finally {
                    publishUpload(expected) { it.copy(isUploading = false) }
                }
            }
        }
        job.start()
    }

    private fun publishUpload(expected: Long, update: (CloudMusicUiState) -> CloudMusicUiState) {
        synchronized(lock) {
            if (uploadRevision == expected && _state.value.isUploading) {
                _state.value = update(_state.value)
            }
        }
    }

}

@Composable
fun CloudMusicScreen(
    viewModel: CloudMusicViewModel = hiltViewModel(),
    isNavigationTab: Boolean = false,
) {
    val state by viewModel.state.collectAsState()
    val playerConnection = LocalPlayerConnection.current
    val navController = LocalNavController.current
    val bottom = LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateBottomPadding()
    val songs = state.page?.songs.orEmpty()
    val cloudTitle = stringResource(R.string.cloud_music)

    MeiloXPinnedListPage(
        title = cloudTitle,
        subtitle = state.page?.let { page ->
            stringResource(R.string.cloud_music_quota, formatBytes(page.usedSize), formatBytes(page.maxSize))
        },
        bottomPadding = bottom,
        verticalArrangement = Arrangement.spacedBy(0.dp),
        onNavigateBack = if (isNavigationTab) null else ({ navController.navigateUp() }),
        actions = {
            listOfNotNull(
                meiloxHeaderAction(
                    key = "cloud.upload",
                    contentDescription = stringResource(R.string.cloud_upload),
                    onClick = { viewModel.upload() },
                    enabled = !state.isUploading,
                ) { SfIcon("arrow.up.circle.fill", null, size = 18.dp) },
                meiloxHeaderAction(
                    key = "cloud.refresh",
                    contentDescription = stringResource(R.string.refresh),
                    onClick = viewModel::refresh,
                ) { SfIcon(SfSymbol.ArrowClockwise, null, size = 18.dp) },
                if (isNavigationTab) globalProfileHeaderAction() else null,
            )
        },
    ) {
        var hasPreviousContent = false
        val hasLoadingContent = state.isLoading && state.page == null
        val hasErrorContent = state.error != null
        if (state.isUploading) {
            item {
                GlasmioCard(
                    Modifier.fillMaxWidth().padding(
                        top = 10.dp,
                        bottom = if (hasLoadingContent || hasErrorContent || songs.isNotEmpty()) 10.dp else 0.dp,
                    ),
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (state.uploadProgress > 0f) CircularProgressIndicator(progress = { state.uploadProgress }, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        else CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        Text(
                            if (state.uploadProgress > 0f) stringResource(R.string.cloud_upload_progress, (state.uploadProgress * 100).toInt())
                            else stringResource(R.string.cloud_upload),
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                }
            }
            hasPreviousContent = true
        }
        if (hasLoadingContent) {
            item {
                Box(
                    Modifier.fillMaxWidth().padding(
                        top = if (hasPreviousContent) 0.dp else 10.dp,
                        bottom = if (hasErrorContent || songs.isNotEmpty()) 10.dp else 0.dp,
                    ),
                ) {
                    Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
            hasPreviousContent = true
        }
        state.error?.let { error ->
            item {
                Text(
                    error,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(
                        top = if (hasPreviousContent) 0.dp else 10.dp,
                        bottom = if (songs.isNotEmpty()) 10.dp else 0.dp,
                    ),
                )
            }
            hasPreviousContent = true
        }
        if (songs.isNotEmpty()) {
            groupedLazyItems(
                items = songs,
                key = { "cloud-${it.id}" },
                contentType = "cloud-song",
                firstItemTopPadding = if (hasPreviousContent) 0.dp else 10.dp,
            ) { song, index ->
                GlasmioListRow(
                    title = song.name,
                    subtitle = listOf(song.artist, song.album)
                        .filter(String::isNotBlank)
                        .joinToString(" · "),
                    showTopSeparator = index > 0,
                    leading = {
                        AsyncImage(
                            model = song.coverUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(44.dp).clip(ContinuousRoundedRectangle(10.dp)),
                        )
                    },
                    trailing = {
                        GlasmioButton(
                            onClick = { viewModel.delete(song) },
                            enabled = song.id !in state.deletingIds,
                            emphasis = GlassEmphasis.Regular,
                        ) { Text(stringResource(R.string.delete)) }
                    },
                    onClick = {
                        val queue = songs.map { item ->
                            val mediaItem = item.asMediaMetadata().toMediaItem()
                            mediaItem.mediaId to mediaItem
                        }
                        playerConnection?.playQueue(ListQueue("cloud", cloudTitle, queue, index))
                    },
                )
            }
        }
        if (state.page?.hasMore == true || state.isLoadingMore || state.loadMoreError != null) {
            item(key = "cloud-pagination") {
                CloudMusicPaginationFooter(state, viewModel::loadMore)
            }
        }
    }
}

@Composable
internal fun CloudMusicPaginationFooter(state: CloudMusicUiState, onLoadMore: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (state.isLoadingMore) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        } else {
            state.loadMoreError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            GlasmioButton(onClick = onLoadMore, enabled = !state.isLoading) {
                Text(stringResource(if (state.loadMoreError != null) R.string.retry else R.string.library_load_more))
            }
        }
    }
}

internal fun CloudMusicUiState.withCloudPage(incoming: CloudMusicPage): CloudMusicUiState {
    val songs = (page?.songs.orEmpty() + incoming.songs).distinctBy(CloudSong::id)
    return copy(
        page = incoming.copy(songs = songs, count = maxOf(page?.count ?: 0, incoming.count, songs.size)),
        isLoadingMore = false,
        loadMoreError = null,
    )
}

private fun CloudSong.asMediaMetadata() = track.toMediaMetadata()

private fun formatBytes(value: Long): String = when {
    value >= 1_073_741_824 -> "%.1f GB".format(value / 1_073_741_824.0)
    value >= 1_048_576 -> "%.1f MB".format(value / 1_048_576.0)
    else -> "%.1f KB".format(value / 1024.0)
}
