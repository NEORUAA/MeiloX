package com.ljyh.mei.ui.screen.cloud

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
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
import com.ljyh.mei.data.model.melox.CloudMusicPage
import com.ljyh.mei.data.model.melox.CloudSong
import com.ljyh.mei.data.model.toMediaItem
import com.ljyh.mei.data.repository.MeloXRepository
import com.ljyh.mei.playback.queue.ListQueue
import com.ljyh.mei.ui.component.GlobalProfileAvatarButton
import com.ljyh.mei.ui.glass.GlassButton
import com.ljyh.mei.ui.glass.GlassIconButton
import com.ljyh.mei.ui.glass.GlassCard
import com.ljyh.mei.ui.glass.GlassEmphasis
import com.ljyh.mei.ui.glass.IosListRow
import com.ljyh.mei.ui.glass.IosPinnedListPage
import com.ljyh.mei.ui.glass.SfIcon
import com.ljyh.mei.ui.glass.SfSymbol
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
    val deletingIds: Set<Long> = emptySet(),
    val isUploading: Boolean = false,
    val uploadProgress: Float = 0f,
    val error: String? = null,
    val isLoadingMore: Boolean = false,
    val loadMoreError: String? = null,
)

@HiltViewModel
class CloudMusicViewModel internal constructor(
    private val loadPage: suspend (Int) -> CloudMusicPage,
    private val deleteSong: suspend (Long) -> Unit,
    private val uploadSong: suspend (Uri, (Long, Long) -> Unit) -> Unit,
) : ViewModel() {
    @Inject
    constructor(repository: MeloXRepository) : this(
        { offset -> repository.cloudSongs(offset = offset) },
        repository::deleteCloudSong,
        repository::uploadCloudSong,
    )

    private val _state = MutableStateFlow(CloudMusicUiState())
    val state = _state.asStateFlow()
    private val lock = Any()
    private var readRevision = 0L
    private var readJob: Job? = null
    private var uploadRevision = 0L

    init { refresh() }

    fun refresh() {
        val job = synchronized(lock) {
            val expected = ++readRevision
            readJob?.cancel()
            _state.value = _state.value.copy(
                isLoading = true,
                isLoadingMore = false,
                loadMoreError = null,
                error = null,
            )
            viewModelScope.launch(start = CoroutineStart.LAZY) { readPage(expected, 0, append = false) }
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

    private suspend fun readPage(expected: Long, offset: Int, append: Boolean) {
        try {
            val incoming = loadPage(offset)
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
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            viewModel.upload(uri)
        }
    }

    IosPinnedListPage(
        title = cloudTitle,
        subtitle = state.page?.let { page ->
            stringResource(R.string.cloud_music_quota, formatBytes(page.usedSize), formatBytes(page.maxSize))
        },
        bottomPadding = bottom,
        verticalArrangement = Arrangement.spacedBy(0.dp),
        onNavigateBack = if (isNavigationTab) null else ({ navController.navigateUp() }),
        actions = {
            GlassIconButton(onClick = { picker.launch(arrayOf("audio/*")) }, enabled = !state.isUploading) {
                SfIcon("arrow.up.circle.fill", stringResource(R.string.cloud_upload), size = 18.dp)
            }
            GlassIconButton(onClick = viewModel::refresh) {
                SfIcon(SfSymbol.ArrowClockwise, stringResource(R.string.refresh), size = 18.dp)
            }
            if (isNavigationTab) GlobalProfileAvatarButton()
        },
    ) {
        var hasPreviousContent = false
        val hasLoadingContent = state.isLoading && state.page == null
        val hasErrorContent = state.error != null
        if (state.isUploading) {
            item {
                GlassCard(
                    Modifier.fillMaxWidth().padding(
                        top = 10.dp,
                        bottom = if (hasLoadingContent || hasErrorContent || songs.isNotEmpty()) 10.dp else 0.dp,
                    ),
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(progress = { state.uploadProgress }, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        Text(
                            stringResource(R.string.cloud_upload_progress, (state.uploadProgress * 100).toInt()),
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
                IosListRow(
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
                        GlassButton(
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
            GlassButton(onClick = onLoadMore, enabled = !state.isLoading) {
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

private fun CloudSong.asMediaMetadata() = MediaMetadata(
    id = id,
    title = name,
    coverUrl = coverUrl.orEmpty(),
    artists = listOf(MediaMetadata.Artist(artist.hashCode().toLong(), artist)),
    duration = durationMs,
    album = MediaMetadata.Album(album.hashCode().toLong(), album),
)

private fun formatBytes(value: Long): String = when {
    value >= 1_073_741_824 -> "%.1f GB".format(value / 1_073_741_824.0)
    value >= 1_048_576 -> "%.1f MB".format(value / 1_048_576.0)
    else -> "%.1f KB".format(value / 1024.0)
}
