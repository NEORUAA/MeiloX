package com.ljyh.mei.ui.component.player.component.sheet

import com.ljyh.mei.ui.component.sheet.MeiloXBottomSheet
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.kyant.capsule.ContinuousRoundedRectangle
import com.ljyh.mei.R
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.source.api.TrackRefCodec
import com.ljyh.mei.ui.local.LocalPlayerConnection
import com.ljyh.mei.ui.local.LocalNavController
import com.ljyh.mei.ui.screen.Screen
import com.ljyh.mei.ui.screen.source.SourceManagementViewModel
import com.ljyh.mei.ui.component.sheet.rememberMeiloXSheetDismissal
import com.ljyh.mei.utils.TimeUtils
import com.ljyh.mei.utils.image.saveImageToGallery
import com.ljyh.mei.utils.setClipboard
import io.github.neoruaa.glasmio.core.LocalGlassColors
import io.github.neoruaa.glasmio.icons.SfIcon
import io.github.neoruaa.glasmio.scaffold.rememberGlasmioHeaderScrollEdge
import io.github.neoruaa.glasmio.ui.*
import kotlinx.coroutines.launch

@Composable
fun SongInfoSheet(
    metadata: MediaMetadata,
    qqSongId: String?,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val sourceViewModel: SourceManagementViewModel = hiltViewModel()
    val sourceSettings by sourceViewModel.settings.collectAsState()
    val providers by sourceViewModel.providers.collectAsState()
    val connection = LocalPlayerConnection.current
    val resolution = connection?.currentPlaybackResolution?.collectAsState()?.value
        ?.takeIf { it.originTrackRef == metadata.trackRef }
    val nav = LocalNavController.current
    val scope = rememberCoroutineScope()
    val dismissal = rememberMeiloXSheetDismissal(onDismissRequest)
    val listState = rememberLazyListState()
    val title = stringResource(R.string.more_action_song_info)
    MeiloXBottomSheet(
        onDismissRequest = dismissal::onDismissed,
        sheetState = dismissal.state,
        skipPartiallyExpanded = false,
        modifier = modifier,
        title = title,
        topBarBlurProgress = rememberGlasmioHeaderScrollEdge(listState),
        topBar = {
            GlasmioSheetTopToolbar(title, actions = {
                GlasmioSheetToolbarButton(onClick = { dismissal.requestDismiss() }) {
                    SfIcon("xmark", stringResource(R.string.cancel))
                }
            })
        },
    ) {
        val padding = LocalGlasmioSheetContentPadding.current
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f), state = listState,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            item("artwork") {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(140.dp)) {
                        AsyncImage(
                            model = metadata.coverUrl, contentDescription = "封面",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize().clip(ContinuousRoundedRectangle(12.dp)),
                        )
                        GlasmioSheetToolbarButton(
                            onClick = { scope.launch { saveImageToGallery(context, metadata.coverUrl) } },
                            modifier = Modifier.align(Alignment.BottomEnd),
                        ) { SfIcon("square.and.arrow.down", "保存封面", size = 20.dp) }
                    }
                }
            }
            item("basic") {
                InfoSection("基本信息", buildList {
                    add("音源" to (providers.find { it.id == metadata.trackRef.providerId }?.displayName ?: metadata.trackRef.providerId))
                    add("歌曲 ID" to metadata.trackRef.trackId)
                    add("歌曲名称" to metadata.title)
                    metadata.tns?.let { add("译名" to it) }
                })
            }
            if (resolution != null) item("playback-source") {
                InfoSection("播放音源", buildList {
                    resolution?.let { actual ->
                        add("实际音源" to (providers.find { it.id == actual.actualAudioProviderId }?.displayName ?: actual.actualAudioProviderId))
                        add("实际音质" to actual.resource.quality)
                        if (actual.resolvedTrackRef != actual.originTrackRef) add("状态" to "已自动切换音源")
                    }
                })
            }
            item("playback-controls") {
                val key = TrackRefCodec.encode(metadata.trackRef)
                GlasmioGroupedList {
                    GlasmioListRow(title = "允许此歌曲自动切换音源", showDisclosure = false, trailing = {
                        com.ljyh.mei.ui.component.MeiloXSwitch(key !in sourceSettings.disabledTrackFallbacks, { allowed ->
                            sourceViewModel.update { it.copy(disabledTrackFallbacks = if (allowed) it.disabledTrackFallbacks - key else it.disabledTrackFallbacks + key) }
                        })
                    })
                    GlasmioListRow(title = "音源与歌曲匹配", onClick = {
                        dismissal.requestDismiss { Screen.SourceManagement.navigate(nav) }
                    })
                }
            }
            if (qqSongId != null) item("qq") { InfoSection("QQ 音乐", listOf("QQ 歌曲 ID" to qqSongId)) }
            item("artists") {
                InfoSection("歌手", metadata.artists.flatMap { artist -> buildList {
                    add("歌手" to artist.name)
                    (artist.ref?.artistId ?: artist.id.takeIf { it != 0L }?.toString())?.let { add("歌手 ID" to it) }
                } })
            }
            item("album") {
                InfoSection("专辑", buildList {
                    add("专辑名称" to metadata.album.title)
                    (metadata.album.ref?.albumId ?: metadata.album.id.takeIf { it != 0L }?.toString())?.let { add("专辑 ID" to it) }
                })
            }
            item("other") {
                InfoSection("其他", listOf("时长" to TimeUtils.formatDuration(metadata.duration),
                    "Explicit" to if (metadata.explicit) "是" else "否"))
            }
        }
    }
}

@Composable
private fun InfoSection(title: String, values: List<Pair<String, String>>) {
    val context = LocalContext.current
    Column {
        GlasmioSectionTitle(title)
        GlasmioGroupedList {
            values.forEachIndexed { index, (label, value) ->
                GlasmioListRow(
                    title = label, subtitle = value, showTopSeparator = index > 0,
                    onClick = { setClipboard(context, value, label) }, showDisclosure = false,
                    trailing = { SfIcon("document.on.document", "复制", size = 18.dp, tint = LocalGlassColors.current.secondaryContent) },
                )
            }
        }
    }
}
