package com.ljyh.mei.standalone

import com.google.gson.JsonParser
import com.ljyh.mei.constants.MusicQuality
import com.ljyh.mei.data.model.room.DownloadStatus
import com.ljyh.mei.data.model.room.DownloadTask
import java.util.UUID

internal const val LEGACY_SONG_IDS = "song_ids_json"
internal const val LEGACY_PLAYLIST_NAME = "playlist_name"
internal const val LEGACY_DOWNLOAD_PATH = "download_path"

internal enum class LegacyWorkState { ACTIVE, CANCELLED, SUCCEEDED, FAILED }
internal data class LegacyDownloadWork(val input: Map<String, Any?>, val state: LegacyWorkState)

/** Public persisted identity is affinity only, never authentication for a resumed operation. */
internal fun migrateLegacyDownload(
    task: DownloadTask,
    affinity: Long,
    works: List<LegacyDownloadWork>,
    now: Long,
    newId: () -> UUID = UUID::randomUUID,
): DownloadTask? {
    if (task.requestId.isNotEmpty() || task.ownerId != 0L || task.status == DownloadStatus.COMPLETED) return null
    val work = works.singleOrNull()
    val metadata = work?.let { decodeLegacyDownload(it.input, task.songId) }
    if (metadata == null || MusicQuality.entries.none { it.text == task.quality }) {
        return task.copy(url = "", status = if (task.status == DownloadStatus.PAUSED) task.status else DownloadStatus.FAILED, updatedAt = now)
    }
    val status = when {
        task.status == DownloadStatus.PAUSED || task.status == DownloadStatus.FAILED -> task.status
        affinity <= 0 -> DownloadStatus.FAILED
        work.state == LegacyWorkState.CANCELLED -> DownloadStatus.PAUSED
        work.state != LegacyWorkState.ACTIVE -> DownloadStatus.FAILED
        else -> DownloadStatus.PENDING
    }
    return task.copy(
        requestId = newId().toString(), ownerId = affinity.coerceAtLeast(0),
        playlistName = metadata.first, downloadPath = metadata.second,
        url = "", fileName = "", fileType = "", status = status,
        progress = if (status == DownloadStatus.PENDING) 0 else task.progress, updatedAt = now,
    )
}

internal fun decodeLegacyDownload(input: Map<String, Any?>, songId: String): Pair<String, String>? = runCatching {
    require(songId.toLongOrNull()?.takeIf { it > 0 }?.toString() == songId)
    val array = JsonParser.parseString(input[LEGACY_SONG_IDS] as String).asJsonArray
    require(array.size() == 1 && array[0].isJsonPrimitive && array[0].asJsonPrimitive.isString && array[0].asString == songId)
    val name = input[LEGACY_PLAYLIST_NAME]?.let { it as String } ?: "未分类"
    val path = input[LEGACY_DOWNLOAD_PATH]?.let { it as String } ?: "Music/Mei"
    require(path.trim().trim('/').split('/').none { it == "." || it == ".." })
    name to path
}.getOrNull()
