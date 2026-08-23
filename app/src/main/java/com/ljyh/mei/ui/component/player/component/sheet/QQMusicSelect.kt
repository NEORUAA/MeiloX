package com.ljyh.mei.ui.component.player.component.sheet

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.kyant.capsule.ContinuousRoundedRectangle
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.qq.u.SearchResult
import com.ljyh.mei.data.model.room.QQSong
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.ui.component.player.PlayerViewModel
import com.ljyh.mei.ui.glass.IosModalSheet
import com.ljyh.mei.ui.glass.IosSheetTopToolbar
import com.ljyh.mei.ui.glass.IosSheetTopToolbarButton
import com.ljyh.mei.ui.glass.SfIcon
import com.ljyh.mei.utils.TimeUtils.formatDuration
import com.ljyh.mei.utils.TimeUtils.formatSeconds
import kotlin.math.abs
import timber.log.Timber


@Composable
fun QQMusicSelectSheet(
    viewmodel: PlayerViewModel,
    mediaMetadata: MediaMetadata,
    onDismiss: () -> Unit,
) {
    val searchNew by viewmodel.searchResult.collectAsState()
    IosModalSheet(
        onDismissRequest = onDismiss,
        skipPartiallyExpanded = false,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            IosSheetTopToolbar(
                title = "正在为这首歌寻找歌词",
                actions = {
                    IosSheetTopToolbarButton(onClick = onDismiss) {
                        SfIcon("xmark", "关闭", size = 20.dp)
                    }
                },
            )
            CurrentReferenceHeader(mediaMetadata.title, mediaMetadata.artists[0].name, mediaMetadata.duration)
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
            val result = searchNew
            when (result) {
                is Resource.Loading -> {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(300.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }

                is Resource.Error -> {
                    Text(
                        result.message,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(20.dp)
                    )
                }

                is Resource.Success -> {
                    val songs = result.data.request.data.body.itemSong
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            // Size to content, capped by the sheet's available height.
                            .weight(1f, fill = false)
                            .padding(bottom = 24.dp)
                    ) {
                        itemsIndexed(songs) { _, song ->
                            OptimizedSongItem(
                                song = song,
                                targetDuration = mediaMetadata.duration,
                                targetTitle = mediaMetadata.title,
                                onClick = {
                                    Timber.tag("QQMusicSelect").d(song.toString())
                                    viewmodel.selectQQSong(mediaMetadata, song)
                                    onDismiss()
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CurrentReferenceHeader(title: String, artist: String, duration: Long) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = formatDuration(duration),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
        }
        Text(
            text = artist,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun OptimizedSongItem(
    song: SearchResult.Request.Data.Body.ItemSong,
    targetDuration: Long,
    targetTitle: String,
    onClick: () -> Unit
) {
    // 匹配算法优化
    val targetSec = targetDuration / 1000
    val durationDiff = abs(targetSec - song.interval)
    val isDurationMatch = durationDiff <= 3 // 3秒以内极度匹配
    val isTitleMatch = song.name.contains(targetTitle, ignoreCase = true) || targetTitle.contains(
        song.name,
        ignoreCase = true
    )

    // 如果时长和标题都接近，认为是“推荐”
    val isHighlyRecommended = isDurationMatch && isTitleMatch

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 封面图
        AsyncImage(
            model = "https://y.qq.com/music/photo_new/T002R300x300M000${song.album.pmid}.jpg",
            contentDescription = null,
            modifier = Modifier
                .size(52.dp)
                .clip(ContinuousRoundedRectangle(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentScale = ContentScale.Crop
        )

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = song.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isHighlyRecommended) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )

                if (isHighlyRecommended) {
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.primary,
                        shape = ContinuousRoundedRectangle(4.dp)
                    ) {
                        Text(
                            "最佳匹配",
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            }

            Text(
                text = "${song.singer.joinToString { it.name }} · ${song.album.name}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // 右侧时长对比
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = formatSeconds(song.interval.toLong()),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isDurationMatch) FontWeight.Bold else FontWeight.Normal,
                color = if (isDurationMatch) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (durationDiff != 0L && durationDiff < 60) {
                Text(
                    text = "${if (song.interval > targetSec) "+" else "-"}${durationDiff}s",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isDurationMatch) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error.copy(
                        alpha = 0.7f
                    )
                )
            }
        }
    }
}
