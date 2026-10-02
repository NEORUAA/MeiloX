package com.ljyh.mei.ui.screen.playlist

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import com.ljyh.mei.R
import com.ljyh.mei.data.model.toMediaItem
import com.ljyh.mei.data.model.toMediaMetadata
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.playback.queue.ListQueue
import com.ljyh.mei.ui.glass.GlassButton
import com.ljyh.mei.ui.local.LocalNavController
import com.ljyh.mei.ui.local.LocalPlayerConnection
import com.ljyh.mei.ui.model.UiPlaylist

@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EveryDay(viewModel: PlaylistViewModel = viewModel()) {
    val context = LocalContext.current
    val everyDaySongs = viewModel.everyDay.collectAsState().value
    val session = viewModel.dailySession.collectAsState().value
    val navController = LocalNavController.current
    val playerConnection = LocalPlayerConnection.current ?: return

    DisposableEffect(viewModel) {
        viewModel.getEveryDayRecommendSongs()
        onDispose { viewModel.stopDailyRecommendations() }
    }

    val uiData = remember(everyDaySongs, session) {
        val songs = (everyDaySongs as? Resource.Success)?.data?.data?.dailySongs.orEmpty().distinctBy { it.id }
        UiPlaylist(
            id = -1L,
            title = "每日推荐",
            count = songs.size,
            subscriberCount = -1,
            cover = songs.firstOrNull()?.al?.picUrl.orEmpty(),
            coverList = songs.take(6).map { it.al.picUrl },
            creatorName = "网易云音乐",
            isCreator = false,
            description = "根据你的音乐口味生成，每天6:00更新",
            tracks = songs.map { it.toMediaMetadata() },
            playCount = -1,
            isSubscribed = false,
        )
    }

    var isDailySearchActive by remember(session) { mutableStateOf(false) }
    var dailySearchQuery by remember(session) { mutableStateOf("") }
    val displayedUiData = remember(uiData, dailySearchQuery) {
        if (dailySearchQuery.isBlank()) uiData else uiData.copy(
            tracks = uiData.tracks.filter { it.matchesPlaylistSearch(dailySearchQuery) }
        )
    }

    fun queue(trackId: Long? = null): ListQueue? {
        val tracks = viewModel.dailyTracks(session, everyDaySongs)
        if (tracks.isEmpty()) return null
        val index = if (trackId == null) 0 else tracks.indexOfFirst { it.id == trackId }
        if (index < 0) return null
        return ListQueue(
            id = "dailySongs", title = uiData.title,
            items = tracks.map { it.id.toString() to it.toMediaItem() }, startIndex = index,
        )
    }

    if (everyDaySongs is Resource.Error) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Error: ${(everyDaySongs as Resource.Error).message}")
                GlassButton(onClick = viewModel::getEveryDayRecommendSongs) { Text(stringResource(R.string.retry)) }
            }
        }
    } else {
        CommonSongListScreen(
            uiData = displayedUiData,
            pagingItems = null,
            isLoading = everyDaySongs is Resource.Loading,
            onPlayAll = { queue()?.let { playerConnection.playQueue(it, expectedSession = session) } },
            onShufflePlay = { queue()?.let { playerConnection.playQueue(it, shuffle = true, expectedSession = session) } },
            headerActionIcon = Icons.Default.FavoriteBorder,
            headerActionLabel = "收藏",
            onTrackClick = { metadata, _ ->
                val selected = queue(metadata.id)
                if (selected != null) playerConnection.onTrackClicked(
                    trackId = metadata.id.toString(),
                    expectedSession = session,
                    buildQueue = { queue(metadata.id) },
                )
            },
            playlistSearchQuery = dailySearchQuery,
            isPlaylistSearchActive = isDailySearchActive,
            onPlaylistSearchQueryChange = { dailySearchQuery = it },
            onPlaylistSearchActiveChange = { active ->
                isDailySearchActive = active
                if (!active) dailySearchQuery = ""
            },
            onBack = { navController.popBackStack() },
            onHeaderAction = {
                Toast.makeText(context, "不能收藏每日推荐歌单", Toast.LENGTH_SHORT).show()
            },
            viewModel = viewModel,
        )
    }
}
