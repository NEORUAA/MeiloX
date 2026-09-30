package com.ljyh.mei.standalone

import androidx.room.withTransaction
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.impl.WorkManagerImpl
import com.ljyh.mei.di.AppDatabase
import com.ljyh.mei.utils.DownloadManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Input Data is absent from public WorkInfo; isolate the pinned WorkManager 2.11.2 DAO read here. */
@Suppress("RestrictedApi")
internal class StandaloneDownloadRecovery(
    private val db: AppDatabase,
    private val manager: WorkManager,
    private val affinity: Long,
    private val namePrefix: String = "download_song_",
    private val downloadTag: String = "download",
    private val afterCommit: suspend () -> Unit = {},
) {
    suspend fun convert() = withContext(Dispatchers.IO) {
        DownloadManager.mutations.withLock {
            val workDb = (manager as WorkManagerImpl).workDatabase
            val all = manager.getWorkInfosByTag(downloadTag).await()
            val legacy = all.mapNotNull { info ->
                workDb.workSpecDao().getWorkSpec(info.id.toString())?.input
                    ?.takeIf { LEGACY_SONG_IDS in it.keyValueMap }?.let { info to it.keyValueMap }
            }
            val converted = db.downloadDao().getAll().first().filter {
                it.requestId.isEmpty() && it.ownerId == 0L && it.status != com.ljyh.mei.data.model.room.DownloadStatus.COMPLETED
            }.mapNotNull { task ->
                val named = manager.getWorkInfosForUniqueWork(namePrefix + task.songId).await().map { it.id }.toSet()
                val inputs = legacy.filter { (info, _) ->
                    info.id in named && namePrefix + task.songId in info.tags
                }.map { (info, input) ->
                    val state = when (info.state) {
                        WorkInfo.State.CANCELLED -> LegacyWorkState.CANCELLED
                        WorkInfo.State.SUCCEEDED -> LegacyWorkState.SUCCEEDED
                        WorkInfo.State.FAILED -> LegacyWorkState.FAILED
                        else -> LegacyWorkState.ACTIVE
                    }
                    LegacyDownloadWork(input, state)
                }
                migrateLegacyDownload(task, affinity, inputs, System.currentTimeMillis())
            }
            // Commit affinity/destination before canceling old work: a crash must not reinterpret
            // our cancellation as a user pause. Old workers cannot pass the runtime's startup gate.
            db.withTransaction { converted.forEach { db.downloadDao().insert(it) } }
            afterCommit()
            legacy.forEach { (info, _) -> manager.cancelWorkById(info.id).result.await() }
        }
    }
}
