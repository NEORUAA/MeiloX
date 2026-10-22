package com.ljyh.mei.ui.screen.main.library

import com.ljyh.mei.data.model.room.DownloadStatus
import com.ljyh.mei.data.model.room.DownloadTask
import com.ljyh.mei.data.model.room.Song
import com.ljyh.mei.data.model.room.SourceType
import com.ljyh.mei.ui.screen.main.library.component.DownloadLibraryFilter
import com.ljyh.mei.ui.screen.main.library.component.DownloadLibrarySort
import com.ljyh.mei.ui.screen.main.library.component.buildDownloadLibraryEntries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadLibraryDataTest {
    @Test
    fun joinsDownloadsAndKeepsTasksWithoutDuplicatingScannedFiles() {
        val downloaded = song("12", path = "content://audio/42", source = SourceType.DOWNLOAD)
        val scannedCopy = song("local_42", path = downloaded.path)
        val local = song("local_43")
        val tasks = listOf(
            DownloadTask("12", status = DownloadStatus.COMPLETED),
            DownloadTask("13", status = DownloadStatus.DOWNLOADING),
        )
        val entries = buildDownloadLibraryEntries(listOf(scannedCopy, local, downloaded), tasks)
        assertEquals(setOf("12", "13", "local_43"), entries.map { it.key }.toSet())
        assertEquals(1, entries.count { it.song?.path == downloaded.path })
        assertTrue(entries.first { it.key == "12" }.canPlay)
        assertFalse(entries.first { it.key == "13" }.canPlay)
        assertEquals("Song 12", entries.first { it.key == "12" }.title)
    }

    @Test
    fun filtersCompletedLocalAndUnfinishedTasksIndependently() {
        val songs = listOf(song("download", source = SourceType.DOWNLOAD), song("local"))
        val tasks = listOf(
            DownloadTask("download", status = DownloadStatus.COMPLETED),
            DownloadTask("missing", status = DownloadStatus.COMPLETED),
            DownloadTask("active", status = DownloadStatus.PENDING),
            DownloadTask("paused", status = DownloadStatus.PAUSED),
            DownloadTask("failed", status = DownloadStatus.FAILED),
        )
        assertEquals(listOf("download"), buildDownloadLibraryEntries(songs, tasks, filter = DownloadLibraryFilter.Downloaded).map { it.key })
        assertEquals(listOf("local"), buildDownloadLibraryEntries(songs, tasks, filter = DownloadLibraryFilter.Local).map { it.key })
        assertEquals(setOf("active", "paused", "failed"), buildDownloadLibraryEntries(songs, tasks, filter = DownloadLibraryFilter.Active).map { it.key }.toSet())
        assertFalse(buildDownloadLibraryEntries(songs, tasks).first { it.key == "missing" }.canPlay)
        val incomplete = buildDownloadLibraryEntries(songs, listOf(DownloadTask("download", status = DownloadStatus.PAUSED)))
        assertFalse(incomplete.first { it.key == "download" }.canPlay)
    }

    @Test
    fun searchesSongAndTaskMetadataAndSortsDeterministically() {
        val songs = listOf(
            song("a", title = "Zulu", artist = "Alpha", addedAt = 10),
            song("b", title = "alpha", artist = "Zulu", addedAt = 30).copy(album = "Album Match"),
            song("c", title = "Beta", artist = "Beta", addedAt = 20).copy(folderPath = "Music/Folder Match"),
        )
        val task = DownloadTask("task", songTitle = "Remote title", songArtist = "Task Artist", createdAt = 40)
        assertEquals(listOf("task", "b", "c", "a"), buildDownloadLibraryEntries(songs, listOf(task)).map { it.key })
        assertEquals(listOf("b", "c", "a"), buildDownloadLibraryEntries(songs, emptyList(), sort = DownloadLibrarySort.Title).map { it.key })
        assertEquals(listOf("a", "c", "b"), buildDownloadLibraryEntries(songs, emptyList(), sort = DownloadLibrarySort.Artist).map { it.key })
        assertEquals(listOf("b"), buildDownloadLibraryEntries(songs, emptyList(), query = " album MATCH ").map { it.key })
        assertEquals(listOf("c"), buildDownloadLibraryEntries(songs, emptyList(), query = "folder match").map { it.key })
        assertEquals(listOf("task"), buildDownloadLibraryEntries(songs, listOf(task), query = "TASK artist").map { it.key })
    }

    private fun song(
        id: String,
        title: String = "Song $id",
        artist: String = "Artist",
        path: String? = "content://audio/$id",
        source: SourceType = SourceType.LOCAL,
        addedAt: Long = 1,
    ) = Song(
        id = id,
        title = title,
        artist = listOf(artist),
        album = "Album",
        cover = "",
        duration = 180_000,
        path = path,
        sourceType = source,
        addedAt = addedAt,
    )
}
