package com.ljyh.mei.playback

import android.content.Context
import com.ljyh.mei.data.network.api.ApiService
import com.ljyh.mei.di.AppDatabase
import com.ljyh.mei.di.AppGraph
import com.ljyh.mei.parasite.HostSessionBridge
import com.ljyh.mei.parasite.HostSessionStamp
import com.ljyh.mei.utils.ImageUtils
import com.ljyh.mei.utils.LyricFetcher
import okhttp3.Call

/** Per-worker dependencies allow a private qualification graph without rebinding the live account. */
internal class DownloadWorkerEnvironment(
    val database: AppDatabase,
    val sessions: HostSessionBridge,
    val api: ApiService,
    val client: Call.Factory,
    val lyric: suspend (String, HostSessionStamp) -> String?,
    val cover: suspend (String) -> ByteArray?,
    val publication: DownloadPublication,
    val notification: ((String, Int, Boolean) -> Unit)? = null,
    val notifications: DownloadNotifications = DownloadNotifications.production,
) {
    companion object {
        fun official(context: Context): DownloadWorkerEnvironment {
            val graph = AppGraph.component
            val database = graph.database()
            return DownloadWorkerEnvironment(
                database, graph.hostRequests().sessions, graph.apiService(), DownloadWorker.getDownloadClient(),
                LyricFetcher::fetchBestLyric, ImageUtils::downloadImageBytes,
                DownloadPublication(database, AndroidDownloadMediaStore(context), context.packageName),
            )
        }
    }
}
