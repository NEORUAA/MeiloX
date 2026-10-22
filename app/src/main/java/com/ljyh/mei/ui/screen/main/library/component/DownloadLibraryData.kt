package com.ljyh.mei.ui.screen.main.library.component

import com.ljyh.mei.data.model.room.DownloadStatus
import com.ljyh.mei.data.model.room.DownloadTask
import com.ljyh.mei.data.model.room.Song
import com.ljyh.mei.data.model.room.SourceType

data class DownloadLibraryEntry(
    val key: String,
    val song: Song?,
    val task: DownloadTask?,
) {
    val title: String get() = song?.title?.takeIf(String::isNotBlank)
        ?: task?.songTitle?.takeIf(String::isNotBlank) ?: task?.songId.orEmpty()
    val artist: String get() = song?.artist?.filter(String::isNotBlank)
        ?.takeIf { it.isNotEmpty() }?.joinToString(" / ") ?: task?.songArtist.orEmpty()
    val album: String get() = song?.album?.takeIf(String::isNotBlank) ?: task?.songAlbum.orEmpty()
    val cover: String get() = song?.cover?.takeIf(String::isNotBlank) ?: task?.songCover.orEmpty()
    val canPlay: Boolean get() = !song?.path.isNullOrBlank() &&
        (task == null || task.status == DownloadStatus.COMPLETED)
    val isLocal: Boolean get() = song?.sourceType == SourceType.LOCAL
}

enum class DownloadLibraryFilter { All, Downloaded, Local, Active }
enum class DownloadLibrarySort { Recent, Title, Artist }

/** Joins stored files and tasks without showing a scanned copy of the same file twice. */
fun buildDownloadLibraryEntries(
    songs: List<Song>,
    tasks: List<DownloadTask>,
    query: String = "",
    filter: DownloadLibraryFilter = DownloadLibraryFilter.All,
    sort: DownloadLibrarySort = DownloadLibrarySort.Recent,
): List<DownloadLibraryEntry> {
    val uniqueTasks = tasks.distinctBy(DownloadTask::songId)
    val taskIds = uniqueTasks.mapTo(hashSetOf(), DownloadTask::songId)
    val paths = hashSetOf<String>()
    val uniqueSongs = songs.sortedWith(
        compareByDescending<Song> { it.id in taskIds }
            .thenBy { it.sourceType == SourceType.LOCAL },
    ).distinctBy(Song::id).filter { song ->
        song.path?.trim()?.removePrefix("file://")?.takeIf(String::isNotEmpty)
            ?.let(paths::add) ?: true
    }
    val songsById = uniqueSongs.associateBy(Song::id)
    val entries = uniqueTasks.map { task ->
        DownloadLibraryEntry(task.songId, songsById[task.songId], task)
    } + uniqueSongs.filter { it.id !in taskIds }.map { song ->
        DownloadLibraryEntry(song.id, song, null)
    }
    val search = query.trim()
    val visible = entries.filter { entry ->
        val matchesFilter = when (filter) {
            DownloadLibraryFilter.All -> true
            DownloadLibraryFilter.Downloaded -> entry.canPlay && !entry.isLocal
            DownloadLibraryFilter.Local -> entry.isLocal
            DownloadLibraryFilter.Active -> entry.task?.status?.let { it != DownloadStatus.COMPLETED } == true
        }
        matchesFilter && (search.isBlank() || listOf(
            entry.title, entry.artist, entry.album, entry.song?.folderPath.orEmpty(),
        ).any { it.contains(search, ignoreCase = true) })
    }
    val comparator = when (sort) {
        DownloadLibrarySort.Recent -> compareByDescending<DownloadLibraryEntry> {
            it.task?.createdAt ?: it.song?.addedAt ?: 0L
        }
        DownloadLibrarySort.Title -> compareBy(String.CASE_INSENSITIVE_ORDER) { entry: DownloadLibraryEntry -> entry.title }
        DownloadLibrarySort.Artist -> compareBy(String.CASE_INSENSITIVE_ORDER) { entry: DownloadLibraryEntry -> entry.artist }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }
    }
    return visible.sortedWith(comparator.thenBy(DownloadLibraryEntry::key))
}
