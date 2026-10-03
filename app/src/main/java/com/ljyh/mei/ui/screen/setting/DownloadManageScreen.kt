package com.ljyh.mei.ui.screen.setting

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.kyant.capsule.ContinuousRoundedRectangle
import com.ljyh.mei.R
import com.ljyh.mei.data.model.room.DownloadStatus
import com.ljyh.mei.data.model.room.DownloadTask
import com.ljyh.mei.di.AppDatabase
import com.ljyh.mei.ui.component.GlobalProfileAvatarButton
import com.ljyh.mei.ui.glass.GlassButton
import com.ljyh.mei.ui.glass.GlassCard
import com.ljyh.mei.ui.glass.GlassEmphasis
import com.ljyh.mei.ui.glass.GlassIconButton
import com.ljyh.mei.ui.glass.IosListRow
import com.ljyh.mei.ui.glass.IosPinnedListPage
import com.ljyh.mei.ui.glass.IosScrollableTabRow
import com.ljyh.mei.ui.glass.SfIcon
import com.ljyh.mei.ui.glass.SfSymbol
import com.ljyh.mei.ui.local.LocalNavController
import com.ljyh.mei.ui.local.LocalPlayerAwareWindowInsets
import com.ljyh.mei.ui.screen.main.library.component.groupedLazyItems
import com.ljyh.mei.utils.DownloadManager
import kotlinx.coroutines.launch

private enum class DownloadFilter(val titleRes: Int) {
    All(R.string.download_filter_all),
    Active(R.string.download_filter_active),
    Paused(R.string.download_filter_paused),
    Completed(R.string.download_filter_completed),
    Failed(R.string.download_filter_failed),
}

private data class DownloadTaskAggregation(
    val tasksByFilter: Map<DownloadFilter, List<DownloadTask>>,
    val counts: Map<DownloadFilter, Int>,
)

private fun aggregateDownloadTasks(tasks: List<DownloadTask>): DownloadTaskAggregation {
    val grouped = DownloadFilter.entries.associateWith { mutableListOf<DownloadTask>() }
    tasks.forEach { task ->
        grouped.getValue(DownloadFilter.All).add(task)
        when (task.status) {
            DownloadStatus.PENDING, DownloadStatus.DOWNLOADING -> {
                grouped.getValue(DownloadFilter.Active).add(task)
            }
            DownloadStatus.PAUSED -> grouped.getValue(DownloadFilter.Paused).add(task)
            DownloadStatus.COMPLETED -> grouped.getValue(DownloadFilter.Completed).add(task)
            DownloadStatus.FAILED -> grouped.getValue(DownloadFilter.Failed).add(task)
        }
    }
    return DownloadTaskAggregation(
        tasksByFilter = grouped.mapValues { (_, value) -> value.toList() },
        counts = grouped.mapValues { (_, value) -> value.size },
    )
}

@Composable
fun DownloadManageScreen(
    @Suppress("UNUSED_PARAMETER") scrollBehavior: TopAppBarScrollBehavior,
    isNavigationTab: Boolean = false,
) {
    val navController = LocalNavController.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val downloadDao = remember(context) { AppDatabase.getDatabase(context).downloadDao() }
    val allTasksFlow = remember(downloadDao) { downloadDao.getAll() }
    val allTasks by allTasksFlow.collectAsState(initial = emptyList())
    var selectedFilter by remember { mutableStateOf(DownloadFilter.All) }
    val insets = LocalPlayerAwareWindowInsets.current.asPaddingValues()
    val taskAggregation = remember(allTasks) { aggregateDownloadTasks(allTasks) }
    val filteredTasks = taskAggregation.tasksByFilter.getValue(selectedFilter)

    IosPinnedListPage(
        title = stringResource(R.string.download_management),
        bottomPadding = insets.calculateBottomPadding(),
        verticalArrangement = Arrangement.spacedBy(0.dp),
        onNavigateBack = if (isNavigationTab) null else ({ navController.navigateUp() }),
        actions = {
            if (allTasks.isNotEmpty()) {
                GlassIconButton({ DownloadManager.deleteAll(context) }) {
                    SfIcon("trash", stringResource(R.string.clear_downloads))
                }
            }
            if (isNavigationTab) GlobalProfileAvatarButton()
        },
    ) {
        item(key = "download-filters") {
            IosScrollableTabRow(
                items = DownloadFilter.entries.map { it to "${stringResource(it.titleRes)} ${taskAggregation.counts.getValue(it)}" },
                selected = selectedFilter,
                onSelected = { selectedFilter = it },
                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
            )
        }
        if (filteredTasks.isEmpty()) {
            item(key = "download-empty") {
                GlassCard(Modifier.fillMaxWidth().padding(vertical = 28.dp)) {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 52.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SfIcon(SfSymbol.Download, null, size = 46.dp)
                    Text(
                        stringResource(R.string.no_download_tasks, stringResource(selectedFilter.titleRes)),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            }
        } else {
            groupedLazyItems(
                items = filteredTasks,
                key = { "download-${it.songId}" },
                contentType = "download-task",
            ) { task, index ->
                DownloadTaskItem(
                    task = task,
                    showTopSeparator = index > 0,
                    onPause = { DownloadManager.pauseSong(context, task.songId) },
                    onResume = {
                        scope.launch {
                            DownloadManager.resumeSong(
                                context,
                                task.songId,
                                context.getString(R.string.resumed_download),
                            )
                        }
                    },
                    onDelete = { DownloadManager.deleteTask(context, task.songId) },
                )
            }
        }
    }
}

@Composable
private fun DownloadTaskItem(
    task: DownloadTask,
    showTopSeparator: Boolean,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onDelete: () -> Unit,
) {
    val statusSymbol = when (task.status) {
        DownloadStatus.DOWNLOADING -> "arrow.down.circle.fill"
        DownloadStatus.COMPLETED -> "checkmark.circle.fill"
        DownloadStatus.FAILED -> "exclamationmark.circle.fill"
        DownloadStatus.PENDING -> "clock"
        DownloadStatus.PAUSED -> "pause.circle.fill"
    }
    val statusText = when (task.status) {
        DownloadStatus.PENDING -> stringResource(R.string.download_waiting)
        DownloadStatus.DOWNLOADING -> "${task.progress}%"
        DownloadStatus.PAUSED -> stringResource(R.string.download_paused)
        DownloadStatus.COMPLETED -> stringResource(R.string.download_completed)
        DownloadStatus.FAILED -> stringResource(R.string.download_failed)
    }
    IosListRow(
        title = task.songTitle.ifBlank { task.songId },
        subtitle = task.songArtist,
        detail = statusText,
        showTopSeparator = showTopSeparator,
        leading = {
            Box(Modifier.size(44.dp)) {
                AsyncImage(
                    model = task.songCover.ifBlank { null },
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(ContinuousRoundedRectangle(10.dp)),
                )
                SfIcon(
                    statusSymbol,
                    null,
                    size = 17.dp,
                    modifier = Modifier.align(Alignment.BottomEnd),
                )
            }
        },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
            if (task.status == DownloadStatus.PENDING || task.status == DownloadStatus.DOWNLOADING) {
                GlassIconButton(onPause, modifier = Modifier.padding(start = 6.dp)) {
                    SfIcon("pause", stringResource(R.string.pause), size = 17.dp)
                }
            } else if (task.status == DownloadStatus.PAUSED || task.status == DownloadStatus.FAILED) {
                GlassIconButton(onResume, modifier = Modifier.padding(start = 6.dp)) {
                    SfIcon("play.fill", stringResource(R.string.resume), size = 17.dp)
                }
            }
            GlassIconButton(onDelete, modifier = Modifier.padding(start = 6.dp)) {
                SfIcon("trash", stringResource(R.string.delete), size = 17.dp)
            }
            }
        },
    )
}
