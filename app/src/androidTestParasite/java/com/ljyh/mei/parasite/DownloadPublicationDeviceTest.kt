package com.ljyh.mei.parasite

import com.ljyh.mei.data.session.SessionChangedException
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ljyh.mei.data.model.room.DownloadArtifact
import com.ljyh.mei.data.model.room.DownloadStatus
import com.ljyh.mei.data.model.room.DownloadTask
import com.ljyh.mei.di.AppDatabase
import com.ljyh.mei.playback.DownloadMediaReceipt
import com.ljyh.mei.playback.DownloadMediaStore
import com.ljyh.mei.playback.DownloadPublication
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Private databases and synthetic bytes only. Never requests a download grant. */
@RunWith(AndroidJUnit4::class)
class DownloadPublicationDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private class SimulatedDeath : Error()

    private class FakeMedia : DownloadMediaStore {
        val rows = linkedMapOf<String, DownloadMediaReceipt>()
        val bytes = mutableMapOf<String, ByteArray>()
        var crash = ""
        var publishCount = 0
        override fun create(artifact: DownloadArtifact): DownloadMediaReceipt {
            val row = DownloadMediaReceipt("content://test/${UUID.randomUUID()}", "v1", 1, artifact.ownerPackage, artifact.stagingPath, true)
            rows[row.uri] = row
            if (crash == "insert") throw SimulatedDeath()
            return row
        }
        override fun staged(artifact: DownloadArtifact) = rows.values.filter { it.path == artifact.stagingPath && it.pending }
        override fun inspect(uri: String) = rows[uri]
        override fun copy(uri: String, source: File, requireActive: () -> Unit) {
            requireActive()
            bytes[uri] = source.readBytes()
            if (crash == "copy") throw SimulatedDeath()
        }
        override fun matchesContent(artifact: DownloadArtifact) = bytes[artifact.uri]?.let {
            it.size.toLong() == artifact.size && java.security.MessageDigest.getInstance("SHA-256")
                .digest(it).joinToString("") { value -> "%02x".format(value) } == artifact.sha256
        } ?: false
        override fun publish(artifact: DownloadArtifact) {
            if (crash == "before-publish") throw SimulatedDeath()
            rows[artifact.uri] = rows.getValue(artifact.uri).copy(path = artifact.relativePath, pending = false)
            publishCount++
            if (crash == "after-publish") throw SimulatedDeath()
        }
        override fun delete(uri: String) { rows.remove(uri); bytes.remove(uri) }
    }

    private fun test(block: suspend (AppDatabase, FakeMedia, File, DownloadTask) -> Unit) = runBlocking(Dispatchers.IO) {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val file = File.createTempFile("download-publication-", ".wav", context.cacheDir).apply { writeBytes(ByteArray(1024) { it.toByte() }) }
        try {
            val task = DownloadTask("1", requestId = UUID.randomUUID().toString(), ownerId = 7, quality = "standard", songTitle = "Test")
            db.downloadDao().insert(task)
            block(db, FakeMedia(), file, task)
        } finally { file.delete(); db.close() }
    }

    private fun store(db: AppDatabase, media: FakeMedia, file: File, task: DownloadTask, withOwner: (() -> Unit) -> Unit = { it() }) {
        DownloadPublication(db, media, context.packageName).store(task, file, "Test.wav", "wav", "Music/MeiloX Test", 1000, {}, withOwner)
    }

    @Test fun completeFileIsReferencedAndPublished() = test { db, media, file, task ->
        store(db, media, file, task)
        assertEquals(DownloadStatus.COMPLETED, db.downloadDao().getBySongId("1")?.status)
        assertEquals(DownloadArtifact.PUBLISHED, db.downloadArtifactDao().all().single().phase)
        assertEquals(media.rows.keys.single(), db.songDao().getSong("1").first()?.path)
        DownloadPublication(db, media, context.packageName).recover()
        assertEquals(1, media.rows.size)
    }

    @Test fun insertionDeathIsRecoveredWithoutAUriAndWithoutTouchingAnotherDirectory() = test { db, media, file, task ->
        val foreign = DownloadMediaReceipt("content://test/foreign", "v1", 1, context.packageName, "Music/user/", true)
        media.rows[foreign.uri] = foreign
        media.crash = "insert"
        assertThrows(SimulatedDeath::class.java) { store(db, media, file, task) }
        assertEquals("", db.downloadArtifactDao().all().single().uri)
        DownloadPublication(db, media, context.packageName).recover()
        assertEquals(listOf(foreign), media.rows.values.toList())
        assertTrue(db.downloadArtifactDao().all().isEmpty())
    }

    @Test fun copyDeathRemovesOnlyItsPendingFile() = test { db, media, file, task ->
        media.crash = "copy"
        assertThrows(SimulatedDeath::class.java) { store(db, media, file, task) }
        DownloadPublication(db, media, context.packageName).recover()
        assertTrue(media.rows.isEmpty())
        assertEquals(DownloadStatus.PENDING, db.downloadDao().getBySongId("1")?.status)
        assertNull(db.songDao().getSong("1").first())
    }

    @Test fun invalidatedOwnerCannotCommit() = test { db, media, file, task ->
        assertThrows(SessionChangedException::class.java) {
            store(db, media, file, task) { throw SessionChangedException() }
        }
        assertTrue(media.rows.isEmpty())
        assertTrue(db.downloadArtifactDao().all().isEmpty())
        assertEquals(DownloadStatus.PENDING, db.downloadDao().getBySongId("1")?.status)
    }

    @Test fun deathBeforePublicationCompletesWithoutAnotherDownload() = test { db, media, file, task ->
        media.crash = "before-publish"
        assertThrows(SimulatedDeath::class.java) { store(db, media, file, task) }
        assertEquals(DownloadArtifact.COMMITTED, db.downloadArtifactDao().all().single().phase)
        assertEquals(DownloadStatus.COMPLETED, db.downloadDao().getBySongId("1")?.status)
        media.crash = ""
        DownloadPublication(db, media, context.packageName).recover()
        assertFalse(media.rows.values.single().pending)
        assertEquals(1, media.publishCount)
    }

    @Test fun deathAfterPublicationDoesNotPublishOrDownloadTwice() = test { db, media, file, task ->
        media.crash = "after-publish"
        assertThrows(SimulatedDeath::class.java) { store(db, media, file, task) }
        media.crash = ""
        DownloadPublication(db, media, context.packageName).recover()
        assertEquals(1, media.publishCount)
        assertEquals(DownloadArtifact.PUBLISHED, db.downloadArtifactDao().all().single().phase)
    }

    @Test fun detachedFileIsDeletedButEditedOrReusedMediaIsNot() = test { db, media, file, task ->
        store(db, media, file, task)
        val artifact = db.downloadArtifactDao().all().single()
        db.songDao().updatePath("1", null)
        val row = media.rows.getValue(artifact.uri)
        media.rows[artifact.uri] = row.copy(version = "v2")
        DownloadPublication(db, media, context.packageName).recover()
        assertEquals(1, media.rows.size)
        media.rows[artifact.uri] = row
        val original = media.bytes.getValue(artifact.uri)
        media.bytes[artifact.uri] = byteArrayOf(9)
        DownloadPublication(db, media, context.packageName).recover()
        assertEquals(1, media.rows.size)
        media.bytes[artifact.uri] = original
        DownloadPublication(db, media, context.packageName).recover()
        assertTrue(media.rows.isEmpty())
        assertTrue(db.downloadArtifactDao().all().isEmpty())
    }

    @Test fun realMediaStorePublishesAndCleansOnlySyntheticMedia() = runBlocking(Dispatchers.IO) {
        val result = com.ljyh.mei.playback.DownloadPublicationProbe.run(context)
        assertTrue(result)
    }
}
