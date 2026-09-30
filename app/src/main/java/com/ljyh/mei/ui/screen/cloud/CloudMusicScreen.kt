package com.ljyh.mei.ui.screen.cloud

import android.content.Intent
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
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
import com.ljyh.mei.data.repository.CloudMusicSource
import com.ljyh.mei.data.session.SessionChangedException
import com.ljyh.mei.data.session.SessionStamp
import com.ljyh.mei.data.session.SessionStore
import com.ljyh.mei.playback.queue.ListQueue
import com.ljyh.mei.ui.component.GlobalProfileAvatarButton
import com.ljyh.mei.ui.glass.GlassButton
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
import javax.inject.Inject
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class CloudMusicUiState(
    val session: SessionStamp? = null,
    val isLoading: Boolean = true,
    val page: CloudMusicPage? = null,
    val deletingIds: Set<Long> = emptySet(),
    val isUploading: Boolean = false,
    val uploadProgress: Float = 0f,
    val error: String? = null,
)

class CloudMusicViewModel internal constructor(
    private val repository: CloudMusicSource,
    private val sessions: SessionStore,
) : ViewModel() {
    @Inject constructor(repository: MeloXRepository, sessions: SessionStore) : this(repository as CloudMusicSource, sessions)

    private val _state = MutableStateFlow(CloudMusicUiState())
    val state = _state.asStateFlow()
    private val stateLock = Any()
    private val readVersion = AtomicLong()
    private val uploadVersion = AtomicLong()
    private var accountScope: CoroutineScope? = null
    private var readJob: Job? = null
    private var selectionOwner: SessionStamp? = null
    private var cleared = false
    private val invalidation = sessions.onInvalidated { revision ->
        synchronized(stateLock) {
            if (!cleared && (state.value.session?.generation ?: -1) < revision) {
                readVersion.incrementAndGet()
                uploadVersion.incrementAndGet()
                _state.value = pendingState()
            }
        }
    }

    init {
        viewModelScope.launch {
            combine(sessions.changes, sessions.recoveryRequired) { _, _ -> Unit }.collect {
                val owner = runCatching { sessions.snapshot() }.getOrNull()
                if (owner == null || sessions.recoveryRequired.value || state.value.session != owner) {
                    accountScope?.cancel()
                    readVersion.incrementAndGet()
                    uploadVersion.incrementAndGet()
                    if (owner == null || sessions.recoveryRequired.value) {
                        synchronized(stateLock) { _state.value = pendingState() }
                    } else {
                        try {
                            sessions.withCurrent(owner) {
                                synchronized(stateLock) {
                                    accountScope = CoroutineScope(viewModelScope.coroutineContext +
                                        SupervisorJob(viewModelScope.coroutineContext[Job]))
                                    _state.value = CloudMusicUiState(session = owner,
                                        isLoading = owner.canUseCloud,
                                        error = if (owner.canUseCloud) null else "Sign-in required")
                                }
                            }
                            if (owner.canUseCloud) refresh(owner)
                        } catch (_: IOException) {
                        }
                    }
                }
            }
        }
    }

    fun refresh() { state.value.session?.let(::refresh) }

    private fun refresh(owner: SessionStamp) {
        val scope = accountScope ?: return
        var version = 0L
        if (!publish(owner) {
            version = readVersion.incrementAndGet()
            it.copy(isLoading = true, error = null)
        }) return
        readJob?.cancel()
        readJob = scope.launch {
            try {
                sessions.requireCurrent(owner)
                val page = repository.cloudSongs(owner)
                currentCoroutineContext().ensureActive()
                publish(owner) { if (readVersion.get() == version) it.copy(isLoading = false, page = page) else null }
            } catch (error: CancellationException) {
                throw error
            } catch (_: SessionChangedException) {
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                publish(owner) { if (readVersion.get() == version) it.copy(isLoading = false, error = error.message) else null }
            } finally {
                publish(owner) { if (readVersion.get() == version) it.copy(isLoading = false) else null }
            }
        }
    }

    fun delete(song: CloudSong, owner: SessionStamp?) {
        val scope = accountScope ?: return
        if (!publish(owner) {
            if (song.id in it.deletingIds || it.page?.songs?.none { row -> row.id == song.id } != false) null
            else it.copy(deletingIds = it.deletingIds + song.id, error = null)
        }) return
        scope.launch {
            try {
                sessions.requireCurrent(checkNotNull(owner))
                repository.deleteCloudSong(owner, song.id)
                currentCoroutineContext().ensureActive()
                if (publish(owner) {
                    it.copy(page = it.page?.let { page ->
                        val remaining = page.songs.filterNot { row -> row.id == song.id }
                        page.copy(songs = remaining, count = remaining.size)
                    })
                }) refresh(owner)
            } catch (error: CancellationException) {
                throw error
            } catch (_: SessionChangedException) {
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                publish(owner) { it.copy(error = error.message) }
            } finally {
                publish(owner) { it.copy(deletingIds = it.deletingIds - song.id) }
            }
        }
    }

    fun beginUploadSelection(owner: SessionStamp?): Boolean = publish(owner) {
        if (it.isUploading || selectionOwner != null) null else {
            selectionOwner = owner
            it
        }
    }

    /** Not saved across process death; an orphan document result cannot choose an account. */
    fun takeUploadSelection(): SessionStamp? {
        val owner = synchronized(stateLock) { selectionOwner.also { selectionOwner = null } } ?: return null
        return owner.takeIf { publish(it) { current -> current } }
    }

    fun upload(uri: String, owner: SessionStamp) {
        val scope = accountScope ?: return
        var version = 0L
        if (uri.isBlank() || !publish(owner) {
            if (it.isUploading) null else {
                version = uploadVersion.incrementAndGet()
                it.copy(isUploading = true, uploadProgress = 0f, error = null)
            }
        }) return
        scope.launch {
            try {
                val operation = currentCoroutineContext()
                sessions.requireCurrent(owner)
                repository.uploadCloudSong(owner, uri) { sent, total ->
                    operation.ensureActive()
                    check(total > 0 && sent in 0..total) { "Invalid cloud upload progress" }
                    publish(owner) {
                        if (uploadVersion.get() == version && it.isUploading)
                            it.copy(uploadProgress = (sent.toDouble() / total).toFloat()) else null
                    }
                }
                operation.ensureActive()
                if (publish(owner) { it.copy(isUploading = false, uploadProgress = 1f) }) refresh(owner)
            } catch (error: CancellationException) {
                throw error
            } catch (_: SessionChangedException) {
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                publish(owner) { it.copy(error = error.message) }
            } finally {
                publish(owner) { if (uploadVersion.get() == version) it.copy(isUploading = false) else null }
            }
        }
    }

    fun withCurrentPage(owner: SessionStamp?, page: CloudMusicPage?, action: () -> Unit) {
        publish(owner) {
            if (page == null || it.page !== page) null else {
                action()
                it
            }
        }
    }

    private fun publish(owner: SessionStamp?, update: (CloudMusicUiState) -> CloudMusicUiState?): Boolean {
        if (owner == null || !owner.canUseCloud) return false
        return try {
            sessions.withCurrent(owner) {
                synchronized(stateLock) {
                    if (cleared || sessions.recoveryRequired.value || state.value.session != owner) false
                    else update(state.value)?.let { _state.value = it; true } ?: false
                }
            }
        } catch (_: IOException) { false }
    }

    private fun pendingState() = CloudMusicUiState(isLoading = !sessions.recoveryRequired.value,
        error = if (sessions.recoveryRequired.value) "Session recovery is required" else null)

    override fun onCleared() {
        invalidation.close()
        synchronized(stateLock) {
            cleared = true
            selectionOwner = null
            readVersion.incrementAndGet()
            uploadVersion.incrementAndGet()
        }
        accountScope?.cancel()
        super.onCleared()
    }
}

private val SessionStamp.canUseCloud: Boolean
    get() = identity.authenticated && !identity.anonymous && identity.userId > 0

@Composable
fun CloudMusicScreen(
    viewModel: CloudMusicViewModel = viewModel(),
    isNavigationTab: Boolean = false,
) {
    // Event callbacks must retain the rendered owner, not read a newer account's State.
    val state = viewModel.state.collectAsState().value
    val playerConnection = LocalPlayerConnection.current
    val navController = LocalNavController.current
    val bottom = LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateBottomPadding()
    val songs = state.page?.songs.orEmpty()
    val cloudTitle = stringResource(R.string.cloud_music)
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val owner = viewModel.takeUploadSelection()
        if (uri != null && owner != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            viewModel.upload(uri.toString(), owner)
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
            GlassButton(onClick = {
                if (viewModel.beginUploadSelection(state.session)) {
                    try { picker.launch(arrayOf("audio/*")) }
                    catch (error: Exception) { viewModel.takeUploadSelection(); throw error }
                }
            }, enabled = !state.isUploading) {
                SfIcon("arrow.up.circle.fill", stringResource(R.string.cloud_upload), size = 18.dp)
            }
            GlassButton(onClick = viewModel::refresh) {
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
                            onClick = { viewModel.delete(song, state.session) },
                            enabled = song.id !in state.deletingIds,
                            emphasis = GlassEmphasis.Regular,
                        ) { Text(stringResource(R.string.delete)) }
                    },
                    onClick = {
                        val queue = songs.map { item ->
                            val mediaItem = item.asMediaMetadata().toMediaItem()
                            mediaItem.mediaId to mediaItem
                        }
                        viewModel.withCurrentPage(state.session, state.page) {
                            playerConnection?.playQueue(ListQueue("cloud", cloudTitle, queue, index))
                        }
                    },
                )
            }
        }
    }
}

private fun CloudSong.asMediaMetadata() = MediaMetadata(
    id = id,
    title = name,
    coverUrl = coverUrl.orEmpty(),
    artists = listOf(MediaMetadata.Artist(artist.hashCode().toLong(), artist)),
    duration = durationMs,
    album = MediaMetadata.Album(album.hashCode().toLong(), album),
    source = source,
)

private fun formatBytes(value: Long): String = when {
    value >= 1_073_741_824 -> "%.1f GB".format(value / 1_073_741_824.0)
    value >= 1_048_576 -> "%.1f MB".format(value / 1_048_576.0)
    else -> "%.1f KB".format(value / 1024.0)
}
