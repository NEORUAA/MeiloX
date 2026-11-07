package com.ljyh.mei.ui.screen.playlist

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.ljyh.mei.data.model.mediaId
import com.ljyh.mei.data.model.toMediaItem
import com.ljyh.mei.data.model.toMediaMetadata
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.playback.queue.ListQueue
import com.ljyh.mei.source.api.Track
import com.ljyh.mei.source.shared.OnlineDailyRecommendations
import com.ljyh.mei.source.shared.SharedOnlineRepository
import com.ljyh.mei.ui.component.playlist.rememberFavoriteTrackIds
import com.ljyh.mei.ui.local.LocalNavController
import com.ljyh.mei.ui.local.LocalPlayerAwareWindowInsets
import com.ljyh.mei.ui.local.LocalPlayerConnection
import com.ljyh.mei.ui.navigation.MeiloXLoadingPage
import com.ljyh.mei.ui.navigation.MeiloXPinnedPage
import com.ljyh.mei.ui.screen.source.SourceContentViewModel
import com.ljyh.mei.ui.screen.source.SourceTrackActions
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DailyRecommendationsViewModel @Inject constructor(onlineRepository: SharedOnlineRepository, savedStateHandle: SavedStateHandle) : ViewModel() {
    private val repository = onlineRepository.screen("daily-recommendations", savedStateHandle["providerId"])
    private val mutableState = MutableStateFlow<Resource<OnlineDailyRecommendations>>(Resource.Loading)
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    init {
        viewModelScope.launch {
            repository.sessionChanges.collect { owner ->
                job?.cancel()
                mutableState.value = Resource.Error("音源已禁用、卸载或切换账号")
                if (owner.isNotBlank()) refresh()
            }
        }
    }
    fun refresh() {
        job?.cancel()
        job = viewModelScope.launch {
            mutableState.value = Resource.Loading
            try { mutableState.value = Resource.Success(repository.dailyRecommendations()) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { mutableState.value = Resource.Error(failure.message.orEmpty()) }
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun EveryDay(viewModel: DailyRecommendationsViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val songs by viewModel.state.collectAsState()
    val nav = LocalNavController.current
    val player = LocalPlayerConnection.current
    val actions: SourceContentViewModel = hiltViewModel(key = "daily-track-actions")
    val data = (songs as? Resource.Success)?.data
    val title = data?.title?.takeIf(String::isNotBlank) ?: "每日推荐"
    if (player == null) {
        MeiloXLoadingPage(title = title, onNavigateBack = { nav.popBackStack() })
        return
    }
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var selection by remember(data) { mutableStateOf<Pair<Track, Rect>?>(null) }
    val tracks = data?.tracks.orEmpty()
    val metadata = remember(tracks) { tracks.map { it.toMediaMetadata() } }
    val indices = remember(metadata, query) { metadata.indices.filter { metadata[it].matchesPlaylistSearch(query) } }
    val displayed = remember(metadata, indices) { indices.map(metadata::get) }
    val favorites = rememberFavoriteTrackIds(displayed)
    fun queue(start: Int = 0, shuffled: Boolean = false): ListQueue? {
        if (metadata.isEmpty()) return null
        val items = metadata.map { it.mediaId to it.toMediaItem() }.let { if (shuffled) it.shuffled() else it }
        return ListQueue("daily:${tracks.firstOrNull()?.ref?.providerId.orEmpty()}", title, items, startIndex = start.coerceIn(items.indices))
    }
    if (songs is Resource.Error) {
        MeiloXPinnedPage(title = title,
            bottomPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateBottomPadding(),
            onNavigateBack = { nav.popBackStack() }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                io.github.neoruaa.glasmio.ui.GlasmioGroupedList {
                    io.github.neoruaa.glasmio.ui.GlasmioListRow((songs as Resource.Error).message)
                    io.github.neoruaa.glasmio.ui.GlasmioListRow("重试", onClick = viewModel::refresh)
                }
            }
        }
    } else SongListDetailScreen(
        uiData = SongListDetailData(title, tracks.size, subscriberCount = -1,
            cover = tracks.firstOrNull()?.artworkUrl.orEmpty(), coverList = tracks.take(6).map { it.artworkUrl },
            creatorName = data?.creatorName.orEmpty(), tracks = displayed),
        isLoading = songs is Resource.Loading,
        hasLoadedContent = data != null,
        useCoverBackground = true,
        landscapeLayout = SongListLandscapeLayout.Split,
        usePlaylistTrackStyle = true,
        onPlayAll = { queue()?.let { player.playQueue(it) } },
        onShufflePlay = { queue(shuffled = true)?.let { player.playQueue(it) } },
        headerActionIcon = Icons.Default.FavoriteBorder,
        headerActionLabel = "收藏",
        onHeaderAction = { Toast.makeText(context, "不能收藏每日推荐歌单", Toast.LENGTH_SHORT).show() },
        onTrackClick = { item, index ->
            player.onTrackClicked(item.mediaId) {
                queue(indices.getOrNull(index) ?: metadata.indexOfFirst { it.mediaId == item.mediaId }.coerceAtLeast(0))
            }
        },
        playlistSearchQuery = query,
        isPlaylistSearchActive = searching,
        onPlaylistSearchQueryChange = { query = it },
        onPlaylistSearchActiveChange = { active -> searching = active; if (!active) query = "" },
        favoriteTrackIds = favorites,
        onTrackMore = { item, bounds -> tracks.firstOrNull { it.ref == item.trackRef }?.let { selection = it to bounds } },
        overlayContent = { SourceTrackActions(selection, { selection = null }, actions) },
        onBack = { nav.popBackStack() },
    )
}
