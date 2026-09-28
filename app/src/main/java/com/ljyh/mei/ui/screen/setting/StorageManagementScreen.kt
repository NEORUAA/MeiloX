package com.ljyh.mei.ui.screen.setting

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import coil3.imageLoader
import com.ljyh.mei.R
import com.ljyh.mei.data.model.room.DownloadStatus
import com.ljyh.mei.data.model.room.SourceType
import com.ljyh.mei.di.AppDatabase
import com.ljyh.mei.playback.CacheManager
import com.ljyh.mei.ui.glass.GlassCard
import com.ljyh.mei.ui.glass.GlassIconButton
import com.ljyh.mei.ui.glass.IosAlertButtonRole
import com.ljyh.mei.ui.glass.IosAlertButtonSpec
import com.ljyh.mei.ui.glass.IosAlertDialog
import com.ljyh.mei.ui.glass.IosPinnedListPage
import com.ljyh.mei.ui.glass.LocalGlassColors
import com.ljyh.mei.ui.glass.SfIcon
import com.ljyh.mei.ui.glass.SfSymbol
import com.ljyh.mei.ui.local.LocalNavController
import com.ljyh.mei.ui.local.LocalPlayerAwareWindowInsets
import com.ljyh.mei.di.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ManagedStorageUsage(
    val downloads: Long = 0,
    val networkCache: Long = 0,
    val temporary: Long = 0,
    val database: Long = 0,
    val deviceTotal: Long = 0,
    val deviceAvailable: Long = 0,
) {
    val managed: Long get() = downloads + networkCache + temporary + database
    val reclaimable: Long get() = networkCache + temporary
}

data class StorageManagementState(
    val usage: ManagedStorageUsage = ManagedStorageUsage(),
    val hasLoaded: Boolean = false,
    val isBusy: Boolean = false,
    val message: String? = null,
    val error: String? = null,
)

internal enum class StorageAction {
    AllCaches, NetworkCache, TemporaryFiles, RepairDownloads, ResetAutoCache, OptimizeDatabase, AllDownloads,
}

class StorageManagementViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AppDatabase,
) : ViewModel() {
    private val _state = MutableStateFlow(StorageManagementState())
    val state = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        if (_state.value.isBusy) return
        viewModelScope.launch {
            _state.value = _state.value.copy(isBusy = true, error = null)
            _state.value = runCatching { measureUsage() }
                .fold(
                    onSuccess = { _state.value.copy(usage = it, hasLoaded = true, isBusy = false) },
                    onFailure = { _state.value.copy(hasLoaded = true, isBusy = false, error = it.message) },
                )
        }
    }

    internal fun perform(action: StorageAction) {
        if (_state.value.isBusy) return
        viewModelScope.launch {
            _state.value = _state.value.copy(isBusy = true, message = null, error = null)
            runCatching {
                withContext(Dispatchers.IO) {
                    when (action) {
                        StorageAction.AllCaches -> {
                            clearNetworkCache()
                            clearTemporaryFiles()
                        }
                        StorageAction.NetworkCache -> clearNetworkCache()
                        StorageAction.TemporaryFiles -> clearTemporaryFiles()
                        StorageAction.RepairDownloads -> repairDownloads()
                        StorageAction.ResetAutoCache -> database.downloadDao().clearPlaybackCounts()
                        StorageAction.OptimizeDatabase -> database.openHelper.writableDatabase.execSQL("VACUUM")
                        StorageAction.AllDownloads -> deleteAllDownloads()
                    }
                }
            }.onSuccess {
                val usage = measureUsage()
                _state.value = _state.value.copy(
                    usage = usage,
                    hasLoaded = true,
                    isBusy = false,
                    message = context.getString(action.completedMessage),
                )
            }.onFailure {
                _state.value = _state.value.copy(isBusy = false, error = it.message)
            }
        }
    }

    private suspend fun measureUsage(): ManagedStorageUsage = withContext(Dispatchers.IO) {
        val downloads = database.songDao().getSongsBySource(SourceType.DOWNLOAD).first()
            .sumOf { song -> song.path?.let(::storedContentSize) ?: 0L }
        val network = listOf("media", "image_cache", "coil3_disk_cache")
            .sumOf { File(context.cacheDir, it).treeSize() }
        val temporary = listOf("download", "data")
            .sumOf { File(context.cacheDir, it).treeSize() }
        val db = context.getDatabasePath("app_database")
        val databaseBytes = listOf(db, File(db.path + "-wal"), File(db.path + "-shm")).sumOf(File::treeSize)
        val stats = StatFs(context.filesDir.path)
        ManagedStorageUsage(
            downloads = downloads,
            networkCache = network,
            temporary = temporary,
            database = databaseBytes,
            deviceTotal = stats.totalBytes,
            deviceAvailable = stats.availableBytes,
        )
    }

    private fun clearNetworkCache() {
        CacheManager.clear()
        context.imageLoader.memoryCache?.clear()
        context.imageLoader.diskCache?.clear()
        File(context.cacheDir, "image_cache").deleteContents()
    }

    private suspend fun clearTemporaryFiles() {
        val hasActiveDownloads = database.downloadDao().getAll().first().any {
            it.status == DownloadStatus.PENDING || it.status == DownloadStatus.DOWNLOADING
        }
        if (!hasActiveDownloads) File(context.cacheDir, "download").deleteContents()
        File(context.cacheDir, "data").deleteContents()
    }

    private suspend fun repairDownloads() {
        val tasks = database.downloadDao().getAll().first()
        check(tasks.none { it.status == DownloadStatus.PENDING || it.status == DownloadStatus.DOWNLOADING }) {
            context.getString(R.string.storage_active_downloads_error)
        }
        tasks.filter { it.status == DownloadStatus.COMPLETED }.forEach { task ->
            val song = database.songDao().getSong(task.songId).first()
            if (song?.path == null || !storedContentExists(song.path)) {
                database.downloadDao().delete(task.songId)
                database.songDao().updatePath(task.songId, null)
            }
        }
    }

    private suspend fun deleteAllDownloads() {
        WorkManager.getInstance(context).cancelAllWorkByTag("download")
        database.downloadDao().getAll().first().forEach { task ->
            database.songDao().getSong(task.songId).first()?.path?.let { path ->
                runCatching {
                    if (path.startsWith("content://")) context.contentResolver.delete(Uri.parse(path), null, null)
                    else File(path).delete()
                }
            }
            database.songDao().updatePath(task.songId, null)
        }
        database.downloadDao().deleteAll()
        database.downloadDao().clearPlaybackCounts()
    }

    private fun storedContentSize(path: String): Long = if (path.startsWith("content://")) {
        context.contentResolver.query(Uri.parse(path), arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        } ?: 0L
    } else File(path).length()

    private fun storedContentExists(path: String): Boolean = if (path.startsWith("content://")) {
        runCatching { context.contentResolver.openFileDescriptor(Uri.parse(path), "r")?.use { true } ?: false }.getOrDefault(false)
    } else File(path).isFile

    private val StorageAction.completedMessage: Int get() = when (this) {
        StorageAction.AllCaches -> R.string.storage_all_caches_cleared
        StorageAction.NetworkCache -> R.string.storage_network_cache_cleared
        StorageAction.TemporaryFiles -> R.string.storage_temporary_cleared
        StorageAction.RepairDownloads -> R.string.storage_downloads_repaired
        StorageAction.ResetAutoCache -> R.string.storage_auto_cache_reset
        StorageAction.OptimizeDatabase -> R.string.storage_database_optimized
        StorageAction.AllDownloads -> R.string.storage_downloads_deleted
    }
}

@Composable
fun StorageManagementScreen(viewModel: StorageManagementViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    val navController = LocalNavController.current
    val context = LocalContext.current
    val insets = LocalPlayerAwareWindowInsets.current.asPaddingValues()
    var confirmation by remember { mutableStateOf<StorageAction?>(null) }

    IosPinnedListPage(
        title = stringResource(R.string.storage_management),
        onNavigateBack = navController::navigateUp,
        bottomPadding = insets.calculateBottomPadding(),
        actions = {
            GlassIconButton(viewModel::refresh, enabled = !state.isBusy) {
                SfIcon(SfSymbol.ArrowClockwise, stringResource(R.string.refresh))
            }
        },
    ) {
        item {
            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    SfIcon("internaldrive.fill", null, size = 30.dp)
                    Text(stringResource(R.string.storage_managed_content), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        if (state.hasLoaded) Formatter.formatFileSize(context, state.usage.managed) else "…",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        stringResource(
                            R.string.storage_device_summary,
                            Formatter.formatFileSize(context, state.usage.deviceTotal - state.usage.deviceAvailable),
                            Formatter.formatFileSize(context, state.usage.deviceAvailable),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(R.string.storage_reclaimable, Formatter.formatFileSize(context, state.usage.reclaimable)),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        state.message?.let { item { Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 4.dp)) } }
        state.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 4.dp)) } }
        item {
            SettingsGroup(stringResource(R.string.storage_items)) {
                StorageUsageRow("arrow.down.circle.fill", R.string.storage_downloads, state.usage.downloads, context)
                StorageUsageRow("photo.stack", R.string.storage_network_cache, state.usage.networkCache, context)
                StorageUsageRow("waveform.path", R.string.storage_temporary_files, state.usage.temporary, context)
                StorageUsageRow("cylinder.split.1x2", R.string.storage_database, state.usage.database, context)
            }
        }
        item {
            SettingsGroup(stringResource(R.string.storage_cache_cleanup)) {
                StorageActionRow("eraser", R.string.storage_clear_all_caches, state.isBusy) { confirmation = StorageAction.AllCaches }
                StorageActionRow("photo.on.rectangle.angled", R.string.storage_clear_network_cache, state.isBusy) { confirmation = StorageAction.NetworkCache }
                StorageActionRow("waveform", R.string.storage_clear_temporary, state.isBusy) { confirmation = StorageAction.TemporaryFiles }
            }
        }
        item {
            SettingsGroup(stringResource(R.string.storage_maintenance)) {
                StorageActionRow("wrench.and.screwdriver", R.string.storage_repair_downloads, state.isBusy) { confirmation = StorageAction.RepairDownloads }
                StorageActionRow("arrow.counterclockwise", R.string.storage_reset_auto_cache, state.isBusy) { confirmation = StorageAction.ResetAutoCache }
                StorageActionRow("cylinder.split.1x2", R.string.storage_optimize_database, state.isBusy) { confirmation = StorageAction.OptimizeDatabase }
            }
        }
        item {
            SettingsGroup(stringResource(R.string.storage_destructive)) {
                StorageActionRow("trash", R.string.storage_delete_all_downloads, state.isBusy, true) { confirmation = StorageAction.AllDownloads }
            }
        }
    }

    confirmation?.let { action ->
        IosAlertDialog(
            onDismissRequest = { confirmation = null },
            title = stringResource(action.confirmationTitle),
            message = stringResource(action.confirmationMessage),
            buttons = listOf(
                IosAlertButtonSpec(
                    label = stringResource(R.string.cancel),
                    role = IosAlertButtonRole.Cancel,
                    onClick = { confirmation = null },
                ),
                IosAlertButtonSpec(
                    label = stringResource(R.string.confirm),
                    onClick = {
                        confirmation = null
                        viewModel.perform(action)
                    },
                ),
            ),
        )
    }
}

@Composable
private fun StorageUsageRow(systemName: String, titleRes: Int, bytes: Long, context: Context) {
    GlassCard(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            SfIcon(systemName, null)
            Text(stringResource(titleRes), modifier = Modifier.weight(1f).padding(horizontal = 13.dp))
            Text(Formatter.formatFileSize(context, bytes), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StorageActionRow(systemName: String, titleRes: Int, busy: Boolean, destructive: Boolean = false, onClick: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth(), onClick = if (busy) null else onClick) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            SfIcon(systemName, null)
            Text(
                stringResource(titleRes),
                modifier = Modifier.weight(1f).padding(horizontal = 13.dp),
                color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            SfIcon("chevron.forward", null, size = 15.dp, tint = LocalGlassColors.current.separator)
        }
    }
}

private val StorageAction.confirmationTitle: Int get() = when (this) {
    StorageAction.AllCaches -> R.string.storage_confirm_all_caches
    StorageAction.NetworkCache -> R.string.storage_confirm_network_cache
    StorageAction.TemporaryFiles -> R.string.storage_confirm_temporary
    StorageAction.RepairDownloads -> R.string.storage_confirm_repair
    StorageAction.ResetAutoCache -> R.string.storage_confirm_reset_auto_cache
    StorageAction.OptimizeDatabase -> R.string.storage_confirm_optimize
    StorageAction.AllDownloads -> R.string.storage_confirm_delete_downloads
}

private val StorageAction.confirmationMessage: Int get() = when (this) {
    StorageAction.AllCaches -> R.string.storage_confirm_all_caches_message
    StorageAction.NetworkCache -> R.string.storage_confirm_network_cache_message
    StorageAction.TemporaryFiles -> R.string.storage_confirm_temporary_message
    StorageAction.RepairDownloads -> R.string.storage_confirm_repair_message
    StorageAction.ResetAutoCache -> R.string.storage_confirm_reset_auto_cache_message
    StorageAction.OptimizeDatabase -> R.string.storage_confirm_optimize_message
    StorageAction.AllDownloads -> R.string.storage_confirm_delete_downloads_message
}

private fun File.treeSize(): Long = when {
    isFile -> length()
    isDirectory -> listFiles()?.sumOf(File::treeSize) ?: 0L
    else -> 0L
}

private fun File.deleteContents() {
    listFiles()?.forEach { child ->
        if (child.isDirectory) child.deleteRecursively() else child.delete()
    }
}
