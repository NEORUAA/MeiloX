package com.ljyh.mei.ui.screen.song

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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
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
import com.ljyh.mei.data.model.melox.SongWiki
import com.ljyh.mei.data.model.melox.SongWikiMemoryItem
import com.ljyh.mei.data.model.melox.SongWikiMemoryKind
import com.ljyh.mei.data.model.melox.SongWikiSongReference
import com.ljyh.mei.data.model.toMediaItem
import com.ljyh.mei.data.repository.MeloXRepository
import com.ljyh.mei.data.session.SessionStamp
import com.ljyh.mei.data.session.SessionStore
import com.ljyh.mei.playback.queue.ListQueue
import com.ljyh.mei.ui.glass.GlassButton
import com.ljyh.mei.ui.glass.GlassCard
import com.ljyh.mei.ui.glass.GlassEmphasis
import com.ljyh.mei.ui.glass.GlassIconButton
import com.ljyh.mei.ui.glass.IosPinnedListPage
import com.ljyh.mei.ui.glass.LocalGlassColors
import com.ljyh.mei.ui.glass.SfIcon
import com.ljyh.mei.ui.glass.SfSymbol
import com.ljyh.mei.ui.local.LocalNavController
import com.ljyh.mei.ui.local.LocalPlayerAwareWindowInsets
import com.ljyh.mei.ui.local.LocalPlayerConnection
import com.ljyh.mei.ui.screen.Screen
import java.text.NumberFormat
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class SongWikiUiState(
    val songId: Long? = null,
    val isLoading: Boolean = false,
    val wiki: SongWiki? = null,
    val error: String? = null,
    val session: SessionStamp? = null,
)

class SongWikiViewModel internal constructor(
    private val loadWiki: suspend (Long, SessionStamp) -> SongWiki,
    private val sessions: SessionStore,
) : ViewModel() {
    @Inject constructor(repository: MeloXRepository, sessions: SessionStore) : this(repository::songWiki, sessions)

    private val _state = MutableStateFlow(SongWikiUiState())
    val state = _state.asStateFlow()
    private val lock = Any()
    private var revision = 0L
    private var requestedId: Long? = null
    private var loadJob: Job? = null
    private val invalidation = sessions.onInvalidated { generation ->
        synchronized(lock) {
            if ((_state.value.session?.generation ?: -1) < generation) clear()
        }
    }

    init {
        viewModelScope.launch {
            combine(sessions.changes, sessions.recoveryRequired) { _, recovery -> recovery }.collect { recovery ->
                val owner = if (recovery) null else runCatching { sessions.snapshot() }.getOrNull()
                if (owner == null) synchronized(lock) { clear() }
                else if (_state.value.session != owner) synchronized(lock) { requestedId }?.let(::load)
            }
        }
    }

    private fun clear() {
        revision++
        loadJob?.cancel()
        loadJob = null
        _state.value = SongWikiUiState(songId = requestedId,
            error = if (sessions.recoveryRequired.value) "Session recovery is required" else null)
    }

    fun withPlaylist(expected: SongWikiUiState, songId: Long, playlistId: Long, action: () -> Unit) =
        withCurrent(expected, songId) {
            if (expected.wiki?.relatedPlaylists?.any { it.id == playlistId } == true) action()
        }

    fun withContribution(expected: SongWikiUiState, songId: Long, url: String, action: () -> Unit) =
        withCurrent(expected, songId) {
            if (expected.wiki?.contributionUrl == url) action()
        }

    private fun withCurrent(expected: SongWikiUiState, songId: Long, action: () -> Unit) {
        val owner = expected.session ?: return
        runCatching {
            sessions.withCurrent(owner) {
                synchronized(lock) {
                    if (!sessions.recoveryRequired.value && _state.value === expected &&
                        requestedId == songId && expected.songId == songId && expected.wiki != null) action()
                }
            }
        }
    }

    fun load(songId: Long) {
        synchronized(lock) { requestedId = songId }
        if (sessions.recoveryRequired.value) return
        val owner = runCatching { sessions.snapshot() }.getOrNull() ?: return
        val job = runCatching {
            sessions.withCurrent(owner) {
                synchronized(lock) {
                    if (sessions.recoveryRequired.value || (_state.value.songId == songId && _state.value.session == owner &&
                            (_state.value.wiki != null || _state.value.isLoading))) return@synchronized null
                    clear()
                    val expected = revision
                    _state.value = SongWikiUiState(songId = songId, isLoading = true, session = owner)
                    viewModelScope.launch(start = CoroutineStart.LAZY) {
                        try {
                            currentCoroutineContext().ensureActive()
                            sessions.requireCurrent(owner)
                            check(!sessions.recoveryRequired.value) { "Session recovery is required" }
                            val wiki = loadWiki(songId, owner)
                            currentCoroutineContext().ensureActive()
                            publish(owner, expected, SongWikiUiState(songId = songId, wiki = wiki, session = owner))
                        } catch (error: CancellationException) {
                            publish(owner, expected, SongWikiUiState(songId = songId, session = owner))
                            throw error
                        } catch (error: Exception) {
                            publish(owner, expected, SongWikiUiState(songId = songId, error = error.message, session = owner))
                        } finally {
                            synchronized(lock) { if (revision == expected) loadJob = null }
                        }
                    }.also { loadJob = it }
                }
            }
        }.getOrNull()
        job?.start()
    }

    private fun publish(owner: SessionStamp, expected: Long, value: SongWikiUiState) {
        try {
            sessions.withCurrent(owner) {
                synchronized(lock) {
                    if (revision == expected) {
                        if (sessions.recoveryRequired.value) clear() else _state.value = value
                    }
                }
            }
        } catch (_: Exception) {
            synchronized(lock) { if (revision == expected) clear() }
        }
    }

    override fun onCleared() {
        invalidation.close()
        synchronized(lock) { clear() }
    }
}

@Composable
fun SongWikiScreen(
    songId: Long,
    viewModel: SongWikiViewModel = viewModel(),
) {
    val state = viewModel.state.collectAsState().value
    val navController = LocalNavController.current
    val playerConnection = LocalPlayerConnection.current
    val uriHandler = LocalUriHandler.current
    val insets = LocalPlayerAwareWindowInsets.current.asPaddingValues()
    val pageTitle = stringResource(R.string.song_wiki)
    LaunchedEffect(songId) { viewModel.load(songId) }

    IosPinnedListPage(
        title = pageTitle,
        onNavigateBack = navController::navigateUp,
        bottomPadding = insets.calculateBottomPadding(),
        actions = {
            GlassIconButton({ viewModel.load(songId) }) {
                SfIcon(SfSymbol.ArrowClockwise, stringResource(R.string.refresh))
            }
        },
    ) {
        when {
            state.isLoading -> item {
                Box(Modifier.fillMaxWidth().padding(64.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            state.error != null -> item {
                GlassCard(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.fillMaxWidth().padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        SfIcon(SfSymbol.Warning, null, size = 34.dp)
                        Text(state.error.orEmpty(), color = MaterialTheme.colorScheme.error)
                        GlassButton(
                            onClick = { viewModel.load(songId) },
                            emphasis = GlassEmphasis.Prominent,
                        ) { Text(stringResource(R.string.retry)) }
                    }
                }
            }
            state.wiki?.isEmpty == true -> item {
                WikiEmptyCard()
            }
            state.wiki != null -> {
                val wiki = state.wiki!!
                if (wiki.memories.isNotEmpty()) item {
                    WikiSection(stringResource(R.string.song_wiki_memories)) {
                        wiki.memories.forEach { memory -> WikiMemoryRow(memory) }
                    }
                }
                if (wiki.tagGroups.isNotEmpty()) item {
                    WikiSection(stringResource(R.string.song_wiki_tags)) {
                        wiki.tagGroups.forEach { group ->
                            WikiLabeledRow(
                                group.title ?: stringResource(R.string.song_wiki_information),
                                group.values.joinToString("、"),
                            )
                        }
                    }
                }
                if (wiki.attributes.isNotEmpty()) item {
                    WikiSection(stringResource(R.string.song_wiki_information)) {
                        wiki.attributes.forEach { attribute ->
                            WikiLabeledRow(
                                attribute.title ?: stringResource(R.string.song_wiki_information),
                                attribute.value,
                            )
                        }
                    }
                }
                wiki.associationGroups.forEach { group ->
                    item(key = group.id) {
                        WikiSection(
                            listOfNotNull(
                                group.title ?: stringResource(R.string.song_wiki_associations),
                                group.countText,
                            ).joinToString(" · "),
                        ) {
                            group.details.forEach { detail ->
                                Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                                    detail.title?.let { Text(it, fontWeight = FontWeight.SemiBold) }
                                    detail.subtitle?.let {
                                        Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    detail.body?.let {
                                        Text(
                                            it,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(top = 3.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                if (wiki.reviews.isNotEmpty()) item {
                    WikiSection(stringResource(R.string.song_wiki_reviews)) {
                        wiki.reviews.forEach { review ->
                            Column(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
                                Text(review.body)
                                review.attribution?.let {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 5.dp),
                                    )
                                }
                            }
                        }
                    }
                }
                if (wiki.similarSongs.isNotEmpty()) item {
                    WikiSection(stringResource(R.string.song_wiki_similar_songs)) {
                        wiki.similarSongs.forEach { song ->
                            WikiSongRow(song) {
                                val owner = state.session ?: return@WikiSongRow
                                val item = song.asMediaMetadata().toMediaItem()
                                playerConnection?.playQueue(
                                    ListQueue("song-wiki", pageTitle, listOf(item.mediaId to item)),
                                    expectedSession = owner,
                                )
                            }
                        }
                    }
                }
                if (wiki.relatedPlaylists.isNotEmpty()) item {
                    WikiSection(stringResource(R.string.song_wiki_related_playlists)) {
                        wiki.relatedPlaylists.forEach { playlist ->
                            WikiArtworkRow(
                                artworkUrl = playlist.artworkUrl,
                                title = playlist.title,
                                subtitle = if (playlist.playCount > 0) {
                                    stringResource(
                                        R.string.song_wiki_play_count,
                                        NumberFormat.getIntegerInstance().format(playlist.playCount),
                                    )
                                } else null,
                                symbolName = "chevron.right",
                                onClick = {
                                    viewModel.withPlaylist(state, songId, playlist.id) {
                                        Screen.PlayList.navigate(navController) { addPath(playlist.id.toString()) }
                                    }
                                },
                            )
                        }
                    }
                }
                wiki.contributionUrl?.let { url ->
                    item {
                        GlassButton(
                            onClick = { viewModel.withContribution(state, songId, url) { uriHandler.openUri(url) } },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            SfIcon("square.and.pencil", null, size = 19.dp)
                            Text(
                                stringResource(R.string.song_wiki_contribute),
                                modifier = Modifier.padding(start = 9.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WikiEmptyCard() {
    GlassCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SfIcon("book.pages", null, size = 42.dp)
            Text(stringResource(R.string.song_wiki_empty), fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(R.string.song_wiki_empty_description),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun WikiSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                content()
            }
        }
    }
}

@Composable
private fun WikiMemoryRow(memory: SongWikiMemoryItem) {
    val label = when (memory.kind) {
        SongWikiMemoryKind.FirstListen -> stringResource(R.string.song_wiki_first_listen)
        SongWikiMemoryKind.TotalPlay -> stringResource(R.string.song_wiki_total_play)
    }
    val numberFormat = NumberFormat.getIntegerInstance()
    val value = when (memory.kind) {
        SongWikiMemoryKind.FirstListen -> memory.date.orEmpty()
        SongWikiMemoryKind.TotalPlay -> listOfNotNull(
            memory.playCount?.let {
                stringResource(R.string.song_wiki_play_times, numberFormat.format(it))
            },
            memory.durationMinutes?.takeIf { it > 0 }?.let {
                stringResource(R.string.song_wiki_play_minutes, numberFormat.format(it))
            },
            memory.text,
        ).joinToString(" · ")
    }
    WikiLabeledRow(label, value)
}

@Composable
private fun WikiLabeledRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, modifier = Modifier.weight(0.42f), fontWeight = FontWeight.Medium)
        Text(
            value,
            modifier = Modifier.weight(0.58f),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun WikiSongRow(song: SongWikiSongReference, onClick: () -> Unit) {
    WikiArtworkRow(
        artworkUrl = song.artworkUrl,
        title = song.title,
        subtitle = listOfNotNull(song.artist, song.note).joinToString(" · ").ifBlank { null },
        symbolName = "play.fill",
        onClick = onClick,
    )
}

@Composable
private fun WikiArtworkRow(
    artworkUrl: String?,
    title: String,
    subtitle: String?,
    symbolName: String,
    onClick: () -> Unit,
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        onClick = onClick,
    ) {
        Row(Modifier.fillMaxWidth().padding(9.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = artworkUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(50.dp).clip(ContinuousRoundedRectangle(11.dp)),
            )
            Column(Modifier.weight(1f).padding(horizontal = 11.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                subtitle?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            SfIcon(
                symbolName,
                null,
                size = 19.dp,
                tint = if (symbolName.startsWith("chevron.")) {
                    LocalGlassColors.current.separator
                } else {
                    LocalGlassColors.current.content
                },
            )
        }
    }
}

private fun SongWikiSongReference.asMediaMetadata() = MediaMetadata(
    id = id,
    title = title,
    coverUrl = artworkUrl.orEmpty(),
    artists = artist.orEmpty().split(" / ").filter(String::isNotBlank).map {
        MediaMetadata.Artist(it.hashCode().toLong(), it)
    },
    duration = 0,
    album = MediaMetadata.Album(0, ""),
)
