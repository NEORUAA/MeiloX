package com.ljyh.mei.ui.component.player.component.sheet

import com.ljyh.mei.ui.component.sheet.MeiloXBottomSheet
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
                    add("歌曲 ID" to metadata.id.toString())
                    add("歌曲名称" to metadata.title)
                    metadata.tns?.let { add("译名" to it) }
                })
            }
            if (qqSongId != null) item("qq") { InfoSection("QQ 音乐", listOf("QQ 歌曲 ID" to qqSongId)) }
            item("artists") {
                InfoSection("歌手", metadata.artists.flatMap { listOf("歌手" to it.name, "歌手 ID" to it.id.toString()) })
            }
            item("album") {
                InfoSection("专辑", listOf("专辑名称" to metadata.album.title, "专辑 ID" to metadata.album.id.toString()))
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
