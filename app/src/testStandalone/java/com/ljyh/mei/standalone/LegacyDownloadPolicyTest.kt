package com.ljyh.mei.standalone

import com.ljyh.mei.data.model.room.DownloadStatus
import com.ljyh.mei.data.model.room.DownloadTask
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class LegacyDownloadPolicyTest {
    private val id = UUID.fromString("00000000-0000-0000-0000-000000000017")
    private val task = DownloadTask("1", url = "https://expired.example.test/old", fileType = "flac", fileName = "old.flac",
        progress = 37, songTitle = "Original", songArtist = "A/B", songAlbum = "Album", songCover = "Cover",
        quality = "lossless", createdAt = 123, updatedAt = 456)
    private fun work(state: LegacyWorkState = LegacyWorkState.ACTIVE) = LegacyDownloadWork(
        mapOf(LEGACY_SONG_IDS to "[\"1\"]", LEGACY_PLAYLIST_NAME to "Original playlist", LEGACY_DOWNLOAD_PATH to "Music/Original"), state,
    )
    private fun migrate(value: DownloadTask = task, owner: Long = 17, works: List<LegacyDownloadWork> = listOf(work())) =
        migrateLegacyDownload(value, owner, works, 789) { id }

    @Test fun activeConversionRetainsIntentAndAffinityButCannotReuseAStoredSource() {
        val actual = requireNotNull(migrate(task.copy(status = DownloadStatus.DOWNLOADING)))
        assertEquals(task.copy(requestId = id.toString(), ownerId = 17, url = "", fileName = "", fileType = "",
            progress = 0, status = DownloadStatus.PENDING, playlistName = "Original playlist", downloadPath = "Music/Original", updatedAt = 789), actual)
    }

    @Test fun pausedAndFailedRowsKeepTheirStateProgressAndCreationTime() {
        for (state in listOf(DownloadStatus.PAUSED, DownloadStatus.FAILED)) {
            val actual = requireNotNull(migrate(task.copy(status = state)))
            assertEquals(state, actual.status)
            assertEquals(task.progress, actual.progress)
            assertEquals(task.createdAt, actual.createdAt)
            assertEquals(17, actual.ownerId)
        }
    }

    @Test fun terminalLegacyWorkCannotCauseAnAutomaticRedownload() {
        for (state in listOf(LegacyWorkState.CANCELLED, LegacyWorkState.SUCCEEDED, LegacyWorkState.FAILED)) {
            val actual = requireNotNull(migrate(works = listOf(work(state))))
            assertEquals(if (state == LegacyWorkState.CANCELLED) DownloadStatus.PAUSED else DownloadStatus.FAILED, actual.status)
            assertEquals(37, actual.progress)
        }
    }

    @Test fun completedAndAlreadyConvertedRowsAreNeverAlteredOrReassigned() {
        for (value in listOf(task.copy(status = DownloadStatus.COMPLETED), task.copy(requestId = id.toString()), task.copy(ownerId = 88))) {
            assertNull(migrate(value))
        }
        val converted = requireNotNull(migrate())
        assertNull(migrate(converted, owner = 88))
    }

    @Test fun unknownAffinityDoesNotBecomeTheAccountThatHappensToLogInNext() {
        for (owner in listOf(0L, -1L)) {
            val actual = requireNotNull(migrate(owner = owner))
            assertEquals(0, actual.ownerId)
            assertEquals(DownloadStatus.FAILED, actual.status)
            assertEquals("Music/Original", actual.downloadPath)
            assertNull(migrate(actual, owner = 88))
        }
    }

    @Test fun missingOrAmbiguousMetadataStaysUnownedAndRequiresAnExplicitNewIntent() {
        for (works in listOf(emptyList(), listOf(work(), work()))) {
            val actual = requireNotNull(migrate(works = works))
            assertEquals(task.copy(url = "", status = DownloadStatus.FAILED, updatedAt = 789), actual)
        }
        assertEquals(DownloadStatus.PAUSED, migrate(task.copy(status = DownloadStatus.PAUSED), works = emptyList())?.status)
    }

    @Test fun onlyTheOriginalSingleStringIdentityContractIsAccepted() {
        for (value in listOf(null, 1, "{}", "[]", "[1]", "[null]", "[\"2\"]", "[\"1\",\"2\"]", "invalid")) {
            assertNull(decodeLegacyDownload(work().input + (LEGACY_SONG_IDS to value), "1"))
        }
        for (song in listOf("01", "0", "-1", "1_17", "9223372036854775808")) {
            assertNull(decodeLegacyDownload(mapOf(LEGACY_SONG_IDS to "[\"$song\"]"), song))
        }
    }

    @Test fun optionalInputDefaultsMatchTheOldWorkerAndTraversalIsNotAccepted() {
        assertEquals("未分类" to "Music/Mei", decodeLegacyDownload(mapOf(LEGACY_SONG_IDS to "[\"1\"]"), "1"))
        assertEquals("" to "", decodeLegacyDownload(work().input + (LEGACY_PLAYLIST_NAME to "") + (LEGACY_DOWNLOAD_PATH to ""), "1"))
        for (value in listOf(17, "Music/../Secret", "Music/./Secret")) {
            assertNull(decodeLegacyDownload(work().input + (LEGACY_DOWNLOAD_PATH to value), "1"))
        }
        assertNull(decodeLegacyDownload(work().input + (LEGACY_PLAYLIST_NAME to listOf("Bad")), "1"))
    }

    @Test fun invalidQualityCannotScheduleAnUnexecutableConvertedTask() {
        val actual = requireNotNull(migrate(task.copy(quality = "unknown")))
        assertEquals(0, actual.ownerId)
        assertEquals("", actual.requestId)
        assertEquals(DownloadStatus.FAILED, actual.status)
    }
}
