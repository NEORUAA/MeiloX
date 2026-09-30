package com.ljyh.mei.standalone

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import com.ljyh.mei.constants.UserIdKey
import com.ljyh.mei.di.AppGraph
import com.ljyh.mei.playback.DownloadWorker
import com.ljyh.mei.playback.DownloadWorkerEnvironment
import com.ljyh.mei.utils.DownloadManager
import com.ljyh.mei.utils.dataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import timber.log.Timber

internal object StandaloneDownloadRuntime {
    private val ready = CompletableDeferred<Unit>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start(context: Context, sessions: StandaloneSessionStore) {
        // Freeze once, before asynchronous login can overwrite the old public account preference.
        val affinity = runBlocking(Dispatchers.IO) {
            freezeLegacyDownloadAffinity(context.dataStore)
        }
        val manager = WorkManager.getInstance(context)
        scope.launch {
            try {
                StandaloneDownloadRecovery(AppGraph.component.database(), manager, affinity).convert()
                ready.complete(Unit)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                ready.completeExceptionally(error)
                Timber.w("Standalone download preparation failed: %s", error.javaClass.simpleName)
                return@launch
            }
            combine(sessions.changes, sessions.recoveryRequired) { _, recovery ->
                if (recovery) false else runCatching { sessions.snapshot().identity.authenticated }.getOrDefault(false)
            }.collect { authenticated ->
                if (authenticated) {
                    try { DownloadManager.recoverAndAwait(context) }
                    catch (error: CancellationException) { throw error }
                    catch (error: Exception) { Timber.w("Standalone queue recovery failed: %s", error.javaClass.simpleName) }
                }
            }
        }
    }

    suspend fun awaitPrepared() = ready.await()
}

internal suspend fun freezeLegacyDownloadAffinity(store: DataStore<Preferences>): Long {
    val key = longPreferencesKey("standalone_legacy_download_owner_v1")
    return store.edit { saved ->
        if (key !in saved) saved[key] = saved[UserIdKey]?.toLongOrNull()?.coerceAtLeast(0) ?: 0
    }[key]!!.coerceAtLeast(0)
}

internal class StandaloneDownloadWorkerFactory : WorkerFactory() {
    override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? {
        val legacy = "download" in workerParameters.tags && LEGACY_SONG_IDS in workerParameters.inputData.keyValueMap
        if (workerClassName != DownloadWorker::class.java.name && !legacy) return null
        return StandaloneDownloadWorker(appContext, workerParameters, legacy)
    }
}

internal class StandaloneDownloadWorker(
    context: Context, private val parameters: WorkerParameters, private val legacy: Boolean,
    private val prepared: suspend () -> Unit = StandaloneDownloadRuntime::awaitPrepared,
    private val environment: () -> DownloadWorkerEnvironment = { DownloadWorkerEnvironment.production(context) },
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        try { prepared() }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { return Result.retry() }
        if (legacy) return Result.failure(androidx.work.workDataOf("standalone_legacy_retired" to true))
        val current = environment()
        if (current.sessions.recoveryRequired.value) return Result.retry()
        return DownloadWorker(applicationContext, parameters, current).doWork()
    }
}
