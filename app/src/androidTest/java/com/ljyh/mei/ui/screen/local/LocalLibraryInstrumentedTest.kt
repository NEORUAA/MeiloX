package com.ljyh.mei.ui.screen.local

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.provider.MediaStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.room.PlaybackHistory
import com.ljyh.mei.data.model.room.SourceType
import com.ljyh.mei.di.AppDatabase
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real MediaStore audio and isolated Room/artwork storage; no account endpoint is used. */
@RunWith(AndroidJUnit4::class)
class LocalLibraryInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun scanningIsIdempotentAndPreservesMatchedMetadata() = runBlocking {
        val token = UUID.randomUUID().toString()
        val testFiles = File(context.cacheDir, "local-library-test-$token").apply { mkdirs() }
        val scannerContext = object : ContextWrapper(context) {
            override fun getFilesDir(): File = testFiles
        }
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        val resolver = context.contentResolver
        var fixture: android.net.Uri? = null
        automation.adoptShellPermissionIdentity(permission)
        try {
            val title = "Local library fixture $token"
            val audio = createPcmWave()
            fixture = checkNotNull(resolver.insert(
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, "$title.wav")
                    put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/Mei-test/")
                    put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                    put(MediaStore.Audio.Media.IS_MUSIC, 1)
                },
            ))
            val fixtureUri = checkNotNull(fixture)
            checkNotNull(resolver.openOutputStream(fixtureUri, "w")).use { it.write(audio) }
            assertEquals(1, resolver.update(fixtureUri, ContentValues().apply {
                put(MediaStore.Audio.Media.IS_PENDING, 0)
            }, null, null))
            // Publishing must produce a real indexed audio duration before the scanner can find it.
            withTimeout(10_000) {
                while (!isIndexedAudio(fixtureUri)) delay(50)
            }
            val scanner = LocalLibraryScanner(scannerContext, database)
            val progress = mutableListOf<LocalScanState>()
            val firstState = scanner.scan(progress::add)
            val fixtureId = "local_${ContentUris.parseId(fixtureUri)}"
            val first = checkNotNull(database.songDao().getById(fixtureId))
            // This untagged WAV uses its file name; derived MediaStore tags can be read-only.
            assertEquals(title, first.title)
            assertEquals(SourceType.LOCAL, first.sourceType)
            assertEquals("wav", first.fileFormat)
            assertEquals(audio.size.toLong(), first.fileSize)
            assertTrue(first.duration in 1_490L..1_510L)
            assertEquals(
                ContentUris.withAppendedId(
                    MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL),
                    ContentUris.parseId(fixtureUri),
                ).toString(),
                first.path,
            )
            assertNotNull(resolver.openInputStream(fixtureUri)?.use { it.read() })
            assertTrue(progress.any { it.isScanning && it.total > 0 })
            assertEquals(firstState.total, firstState.scanned)
            assertFalse(firstState.isScanning)
            assertTrue(progress.zipWithNext().all { (before, after) -> before.scanned <= after.scanned })

            val playedAt = listOf(1_000L, 2_000L)
            playedAt.forEach { database.historyDao().insertHistory(PlaybackHistory(songId = fixtureId, playedAt = it)) }
            scanner.scan {}
            val second = checkNotNull(database.songDao().getById(fixtureId))
            assertEquals(first.id, second.id)
            assertEquals(first.path, second.path)
            assertEquals(1, database.songDao().getAllSong().first().count { it.path == first.path })
            assertEquals(playedAt, database.historyDao().getHistory().first()
                .filter { it.song.id == fixtureId }.map { it.playedAt }.sorted())

            val candidate = MediaMetadata(
                id = 7L,
                title = "Matched library title",
                coverUrl = "https://example.invalid/local-library-cover.jpg",
                artists = listOf(MediaMetadata.Artist(8L, "Matched library artist")),
                duration = first.duration + 1_000L,
                album = MediaMetadata.Album(9L, "Matched library album"),
            )
            val matched = second.withMatchedMetadata(candidate)
            database.songDao().updateLocalDisplayMetadata(
                second.id, matched.title, matched.artist, matched.album, matched.cover,
            )
            scanner.scan {}
            val rescanned = checkNotNull(database.songDao().getById(fixtureId))
            assertEquals(candidate.title, rescanned.title)
            assertEquals(candidate.artists.map { it.name }, rescanned.artist)
            assertEquals(candidate.album.title, rescanned.album)
            assertEquals(candidate.coverUrl, rescanned.cover)
            assertEquals(first.id, rescanned.id)
            assertEquals(first.path, rescanned.path)
            assertEquals(first.duration, rescanned.duration)
            assertEquals(first.sourceType, rescanned.sourceType)
            assertEquals(1, database.songDao().getAllSong().first().count { it.path == first.path })
            assertEquals(playedAt, database.historyDao().getHistory().first()
                .filter { it.song.id == fixtureId }.map { it.playedAt }.sorted())
            assertArrayEquals(audio, checkNotNull(resolver.openInputStream(fixtureUri)).use { it.readBytes() })

            // A cursor opened before deletion must not restore a cleared local path.
            database.songDao().updatePath(fixtureId, null)
            var deletedDuringScan = false
            scanner.scan { state ->
                if (!deletedDuringScan && state.isScanning && state.total > 0 && state.scanned == 0) {
                    resolver.delete(fixtureUri, null, null)
                    deletedDuringScan = true
                }
            }
            assertTrue(deletedDuringScan)
            assertEquals(null, database.songDao().getById(fixtureId)?.path)
        } finally {
            try {
                fixture?.let { resolver.delete(it, null, null) }
            } finally {
                database.close()
                testFiles.deleteRecursively()
                automation.dropShellPermissionIdentity()
            }
        }
    }

    private fun isIndexedAudio(uri: android.net.Uri): Boolean =
        context.contentResolver.query(
            uri,
            arrayOf(MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.SIZE),
            null, null, null,
        )?.use { rows ->
            rows.moveToFirst() && rows.getLong(0) > 0 && rows.getLong(1) > 0
        } == true

    private fun createPcmWave(): ByteArray {
        val sampleRate = 8_000
        val sampleCount = sampleRate * 3 / 2
        val bytesPerSample = 2
        val dataSize = sampleCount * bytesPerSample
        return ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + dataSize)
            put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1)
            putShort(1)
            putInt(sampleRate)
            putInt(sampleRate * bytesPerSample)
            putShort(bytesPerSample.toShort())
            putShort(16)
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataSize)
            repeat(sampleCount) { index ->
                putShort((sin(2 * PI * 440 * index / sampleRate) * 2_000).toInt().toShort())
            }
        }.array()
    }
}
