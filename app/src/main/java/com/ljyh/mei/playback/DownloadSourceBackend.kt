package com.ljyh.mei.playback

import com.ljyh.mei.constants.MusicQuality
import com.ljyh.mei.data.model.DownloadSources
import com.ljyh.mei.data.session.SessionStamp

/** Each runtime owns the permission and quality policy for obtaining fresh media bytes. */
fun interface DownloadSourceBackend {
    val failureTitle: String get() = "获取下载链接失败"
    suspend fun resolve(ids: List<String>, quality: MusicQuality, owner: SessionStamp): DownloadSources
}
