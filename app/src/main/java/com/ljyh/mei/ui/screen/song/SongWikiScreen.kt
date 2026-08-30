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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
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
import com.ljyh.mei.data.model.melox.SongWiki
import com.ljyh.mei.data.model.melox.SongWikiMemoryItem
import com.ljyh.mei.data.model.melox.SongWikiMemoryKind
import com.ljyh.mei.data.model.melox.SongWikiSongReference
import com.ljyh.mei.data.model.toMediaItem
import com.ljyh.mei.data.repository.MeloXRepository
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
import dagger.hilt.android.lifecycle.HiltViewModel
import java.text.NumberFormat
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SongWikiUiState(
    val songId: Long? = null,
    val isLoading: Boolean = false,
    val wiki: SongWiki? = null,
    val error: String? = null,
)

@HiltViewModel
class SongWikiViewModel @Inject constructor(
    private val repository: MeloXRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(SongWikiUiState())
    val state = _state.asStateFlow()

    fun load(songId: Long) {
        if (_state.value.songId == songId && _state.value.wiki != null) return
        viewModelScope.launch {
            _state.value = SongWikiUiState(songId = songId, isLoading = true)
            runCatching { repository.songWiki(songId) }
                .onSuccess { _state.value = SongWikiUiState(songId = songId, wiki = it) }
                .onFailure {
                    _state.value = SongWikiUiState(songId = songId, error = it.message)
                }
        }
    }
}

@Composable
fun SongWikiScreen(
    songId: Long,
    viewModel: SongWikiViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
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
                                val item = song.asMediaMetadata().toMediaItem()
                                playerConnection?.playQueue(
                                    ListQueue("song-wiki", pageTitle, listOf(item.mediaId to item)),
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
                                    Screen.PlayList.navigate(navController) { addPath(playlist.id.toString()) }
                                },
                            )
                        }
                    }
                }
                wiki.contributionUrl?.let { url ->
                    item {
                        GlassButton(
                            onClick = { uriHandler.openUri(url) },
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
