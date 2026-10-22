package com.ljyh.mei.playback

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import android.net.Uri
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.platform.app.InstrumentationRegistry
import com.ljyh.mei.data.model.room.DownloadStatus
import com.ljyh.mei.data.model.room.DownloadTask
import com.ljyh.mei.data.model.room.Song
import com.ljyh.mei.data.model.room.SourceType
import com.ljyh.mei.di.AppDatabase
import com.ljyh.mei.utils.DownloadManager
import com.ljyh.mei.utils.DownloadLyrics
import com.ljyh.mei.utils.ImageUtils
import com.ljyh.mei.utils.SongMate
import com.ljyh.mei.utils.detectAudioFileFormat
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Download fixtures use local files, Room and loopback audio without changing the service queue. */
@RunWith(AndroidJUnit4::class)
class DownloadLifecycleInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun downloadedTagsKeepPngAndJpegCoversAcrossAudioFormats(): Unit = runBlocking {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val lyric = checkNotNull(DownloadLyrics.select(null,
            """<tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata"><body><div><p begin="00:01.000" end="00:02.000"><span begin="00:01.000" end="00:02.000">Hello world</span><span ttm:role="x-translation">你好世界</span></p></div></body></tt>"""))
        assertEquals("[00:01.000]Hello world\n[00:01.000]你好世界", lyric)
        for (format in listOf("mp3", "flac", "m4a")) {
            for (imageType in listOf("png", "jpg")) {
                val file = File.createTempFile("download-tags-", ".download", context.cacheDir)
                try {
                    assets.open("download-tags/blank.$format").use { input ->
                        file.outputStream().use { output -> input.copyTo(output) }
                    }
                    val cover = assets.open("download-tags/cover.$imageType").use { it.readBytes() }
                    SongMate.writeTagsWithCoverBytes("标题 Title", "Artist", "Album", cover, file.path, lyric)
                    assertEquals(format, detectAudioFileFormat(file)?.extension)
                    val saved = AudioFileIO.readAs(file, format).tag
                    assertEquals("标题 Title", saved.getFirst(FieldKey.TITLE))
                    assertEquals("Artist", saved.getFirst(FieldKey.ARTIST))
                    assertEquals("Album", saved.getFirst(FieldKey.ALBUM))
                    assertEquals(lyric, saved.getFirst(FieldKey.LYRICS))
                    assertArrayEquals(cover, saved.firstArtwork?.binaryData)
                    assertEquals(if (imageType == "png") "image/png" else "image/jpeg", saved.firstArtwork?.mimeType)
                    if (format != "m4a") assertEquals(3, saved.firstArtwork?.pictureType)
                    if (format == "flac") {
                        assertEquals(16, saved.firstArtwork?.width)
                        assertEquals(16, saved.firstArtwork?.height)
                    }
                    // Invalid replacement art must leave the previously saved cover intact.
                    SongMate.writeTagsWithCoverBytes("标题 Title", "Artist", "Album", byteArrayOf(1), file.path, lyric)
                    assertArrayEquals(cover, AudioFileIO.readAs(file, format).tag.firstArtwork?.binaryData)
                } finally { file.delete() }
            }
        }
    }

    @Test fun coverDownloadsPreserveOriginalBytesAndCancellation(): Unit = runBlocking {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val file = File.createTempFile("download-cover-", ".png", context.cacheDir)
        try {
            val cover = assets.open("download-tags/cover.png").use { it.readBytes() }
            file.writeBytes(cover)
            FixtureAudio(file).use { server ->
                assertArrayEquals(cover, ImageUtils.downloadImageBytes(server.url, OkHttpClient()))
                withTimeout(5_000) { server.sent.await() }
            }
        } finally { file.delete() }
        StalledAudio().use { server ->
            val job = async(Dispatchers.IO) { ImageUtils.downloadImageBytes(server.url, OkHttpClient()) }
            assertTrue(server.entered.await(5, TimeUnit.SECONDS))
            withTimeout(3_000) { job.cancelAndJoin() }
            assertTrue(server.closed.await(3, TimeUnit.SECONDS))
        }
    }

    @Test fun retiredRequestsCannotChangePausedReplacedOrDeletedRows() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val dao = db.downloadDao()
            val original = DownloadTask("fixture", requestId = "old")
            dao.insert(original)
            assertEquals(1, dao.updateOwnedProgress("fixture", "old", DownloadStatus.DOWNLOADING, 5, 1))
            dao.updateStatus("fixture", DownloadStatus.PAUSED)
            assertEquals(0, dao.updateOwnedProgress("fixture", "old", DownloadStatus.COMPLETED, 100, 2))
            assertEquals(DownloadStatus.PAUSED, dao.getBySongId("fixture")?.status)
            dao.insert(original.copy(requestId = "new"))
            assertEquals(0, dao.updateOwnedProgress("fixture", "old", DownloadStatus.FAILED, 0, 3))
            assertEquals(1, dao.updateOwnedProgress("fixture", "new", DownloadStatus.DOWNLOADING, 9, 4))
            dao.delete("fixture")
            assertEquals(0, dao.updateOwnedProgress("fixture", "new", DownloadStatus.COMPLETED, 100, 5))
            dao.insert(DownloadTask("legacy"))
            assertEquals(1, dao.claimLegacyRequest("legacy", "worker"))
            assertEquals(0, dao.claimLegacyRequest("legacy", "retired"))
            assertEquals("worker", dao.getBySongId("legacy")?.requestId)
        } finally { db.close() }
    }

    @Test fun cancellationClosesABlockedAudioReadAndRemovesPartialFile() = runBlocking {
        StalledAudio().use { server ->
            val file = File.createTempFile("download-cancel-", ".wav", context.cacheDir)
            try {
                val job = async(Dispatchers.IO) { downloadAudioFile(OkHttpClient(), server.url, file) {} }
                assertTrue(server.entered.await(5, TimeUnit.SECONDS))
                withTimeout(3_000) { job.cancelAndJoin() }
                assertTrue(server.closed.await(3, TimeUnit.SECONDS))
                assertFalse(file.exists())
            } finally { file.delete() }
        }
    }

    @Test fun pausingARealWorkerKeepsItsRowPausedAndDoesNotPublish() = runBlocking {
        val songId = "download-fixture-${UUID.randomUUID()}"
        val db = AppDatabase.getDatabase(context)
        StalledAudio().use { server ->
            try {
                DownloadManager.enqueue(context, listOf(SongDownloadInfo(songId, server.url,
                    "Download cancellation fixture", listOf("Fixture"), "Fixture", "", 0, "wav")),
                    "Lifecycle fixture", downloadPath = "Music/MeiloX-test-fixtures")
                assertTrue(server.entered.await(15, TimeUnit.SECONDS))
                DownloadManager.pauseSong(context, songId)
                withTimeout(5_000) {
                    while (db.downloadDao().getBySongId(songId)?.status != DownloadStatus.PAUSED) delay(30)
                }
                assertTrue(server.closed.await(5, TimeUnit.SECONDS))
                delay(200)
                assertEquals(DownloadStatus.PAUSED, db.downloadDao().getBySongId(songId)?.status)
                assertNull(db.songDao().getSong(songId).first()?.path)
            } finally {
                db.downloadDao().getBySongId(songId)?.requestId?.let {
                    androidx.work.WorkManager.getInstance(context).cancelWorkById(UUID.fromString(it)).result.get()
                }
                db.downloadDao().delete(songId)
            }
        }
    }

    @Test fun failedFileDeletionKeepsItsTaskAndPathUntilASuccessfulRetry() = runBlocking {
        val songId = "delete-fixture-${UUID.randomUUID()}"
        val db = AppDatabase.getDatabase(context)
        val directory = File(context.cacheDir, songId).apply { mkdirs() }
        val child = File(directory, "retained.wav").apply { writeBytes(ByteArray(32)) }
        val task = DownloadTask(songId, status = DownloadStatus.COMPLETED, requestId = UUID.randomUUID().toString())
        try {
            db.downloadDao().insert(task)
            db.songDao().insertSong(Song(
                songId, "Deletion fixture", listOf("Fixture"), "Fixture", "", 1_000,
                path = directory.absolutePath, sourceType = SourceType.DOWNLOAD,
            ))
            val failure = CompletableDeferred<Boolean>()
            val callbackOnMain = AtomicBoolean()
            DownloadManager.deleteTask(context, songId) {
                callbackOnMain.set(Looper.myLooper() == Looper.getMainLooper())
                failure.complete(it)
            }
            assertFalse(withTimeout(5_000) { failure.await() })
            assertTrue(callbackOnMain.get())
            assertTrue(child.exists())
            assertEquals(task, db.downloadDao().getBySongId(songId))
            assertEquals(directory.absolutePath, db.songDao().getSong(songId).first()?.path)

            assertTrue(child.delete())
            val success = CompletableDeferred<Boolean>()
            DownloadManager.deleteTask(context, songId) { success.complete(it) }
            assertTrue(withTimeout(5_000) { success.await() })
            assertFalse(directory.exists())
            assertNull(db.downloadDao().getBySongId(songId))
            assertNull(db.songDao().getSong(songId).first()?.path)
        } finally {
            db.downloadDao().delete(songId)
            db.songDao().deleteById(songId)
            child.delete()
            directory.delete()
        }
    }

    @Test fun deletingARealWorkerCancelsItsReadAndCannotPublishAfterRemoval() = runBlocking {
        val songId = "delete-worker-fixture-${UUID.randomUUID()}"
        val db = AppDatabase.getDatabase(context)
        var requestId: String? = null
        StalledAudio().use { server ->
            try {
                DownloadManager.enqueue(context, listOf(SongDownloadInfo(songId, server.url,
                    "Download deletion fixture", listOf("Fixture"), "Fixture", "", 0, "wav")),
                    "Lifecycle fixture", downloadPath = "Music/MeiloX-test-fixtures")
                requestId = db.downloadDao().getBySongId(songId)?.requestId
                assertTrue(server.entered.await(15, TimeUnit.SECONDS))
                val removed = CompletableDeferred<Boolean>()
                DownloadManager.deleteTask(context, songId) { removed.complete(it) }
                assertTrue(withTimeout(5_000) { removed.await() })
                assertTrue(server.closed.await(5, TimeUnit.SECONDS))
                delay(200)
                assertNull(db.downloadDao().getBySongId(songId))
                assertNull(db.songDao().getSong(songId).first()?.path)
            } finally {
                requestId?.let {
                    androidx.work.WorkManager.getInstance(context).cancelWorkById(UUID.fromString(it)).result.get()
                }
                db.downloadDao().delete(songId)
                db.songDao().deleteById(songId)
            }
        }
    }

    @Test fun sameNameDownloadsOwnDifferentFilesAndNeverAdoptExistingAudio() = runBlocking {
        val directory = File(context.cacheDir, "download-identity-fixture").apply { mkdirs() }
        val first = createDownloadTempFile(directory, "first", "wav")
        val second = createDownloadTempFile(directory, "second", "wav")
        val path = "Music/MeiloX-test-fixtures/${UUID.randomUUID()}"
        val name = "Same title - Same artist.wav"
        val resolver = context.contentResolver
        val uris = mutableListOf<android.net.Uri>()
        try {
            assertNotEquals(first.absolutePath, second.absolutePath)
            val seed = checkNotNull(resolver.insert(android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Audio.Media.DISPLAY_NAME, name)
                    put(android.provider.MediaStore.Audio.Media.RELATIVE_PATH, "$path/")
                    put(android.provider.MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
                })).also(uris::add)
            val original = ByteArray(256) { 11 }
            resolver.openOutputStream(seed)!!.use { it.write(original) }
            first.writeBytes(ByteArray(512) { 22 })
            second.writeBytes(ByteArray(1024) { 33 })
            val a = checkNotNull(publishDownloadedAudio(context, first, name, "wav", path)).also(uris::add)
            val b = checkNotNull(publishDownloadedAudio(context, second, name, "wav", path)).also(uris::add)
            assertEquals(3, uris.distinct().size)
            fun bytes(uri: android.net.Uri) = resolver.openInputStream(uri)!!.use { it.readBytes() }
            assertArrayEquals(original, bytes(seed))
            assertArrayEquals(first.readBytes(), bytes(a))
            assertArrayEquals(second.readBytes(), bytes(b))
            resolver.delete(a, null, null); uris.remove(a)
            assertArrayEquals(original, bytes(seed))
            assertArrayEquals(second.readBytes(), bytes(b))
        } finally {
            uris.forEach { resolver.delete(it, null, null) }
            first.delete(); second.delete(); directory.delete()
        }
    }

    @Test fun mismatchedMp3DownloadPublishesAndPlaysRealFlac(): Unit = runBlocking {
        val fixturePath = InstrumentationRegistry.getArguments().getString("downloadAudioFixturePath")
        assumeTrue("Provide downloadAudioFixturePath to opt into the real-audio fixture", !fixturePath.isNullOrBlank())
        val supplied = File(checkNotNull(fixturePath))
        val fixture = (if (supplied.isAbsolute) supplied else File(context.cacheDir, fixturePath)).canonicalFile
        require(fixture.path.startsWith(context.cacheDir.canonicalPath + File.separator)) {
            "The audio fixture must be inside the target application's cache directory"
        }
        assertTrue("The host-supplied FLAC fixture is missing", fixture.isFile && fixture.length() > 0)
        val songId = "audio-format-fixture-${UUID.randomUUID()}"
        val db = AppDatabase.getDatabase(context)
        val resolver = context.contentResolver
        val workManager = androidx.work.WorkManager.getInstance(context)
        var requestId: UUID? = null
        var published: Uri? = null
        var player: ExoPlayer? = null
        FixtureAudio(fixture).use { server ->
            try {
                DownloadManager.enqueue(context, listOf(SongDownloadInfo(
                    songId, server.url, "FLAC format fixture $songId", listOf("Fixture"),
                    "Download format fixture", "", 0, fileType = "mp3", quality = "jyeffect",
                )), "Audio format fixture", downloadPath = "Music/MeiloX-test-fixtures/$songId")
                requestId = UUID.fromString(checkNotNull(db.downloadDao().getBySongId(songId)).requestId)
                val completed = withTimeout(120_000) {
                    while (true) {
                        val task = checkNotNull(db.downloadDao().getBySongId(songId))
                        check(task.status != DownloadStatus.FAILED) { "The audio fixture download failed" }
                        if (task.status == DownloadStatus.COMPLETED) return@withTimeout task
                        delay(100)
                    }
                    @Suppress("UNREACHABLE_CODE") error("Download did not complete")
                }
                withTimeout(5_000) { server.sent.await() }
                val song = checkNotNull(db.songDao().getSong(songId).first())
                val uri = Uri.parse(checkNotNull(song.path))
                published = uri
                assertEquals("flac", completed.fileType)
                assertTrue(completed.fileName.endsWith(".flac"))
                assertEquals("flac", song.fileFormat)
                assertTrue("Unexpected stored duration: ${song.duration}", song.duration in 284_000L..288_000L)
                resolver.query(uri, arrayOf(MediaStore.Audio.Media.DISPLAY_NAME,
                    MediaStore.Audio.Media.MIME_TYPE), null, null, null)!!.use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertTrue(cursor.getString(0).endsWith(".flac"))
                    assertEquals("audio/flac", cursor.getString(1))
                }

                val ready = CompletableDeferred<Unit>()
                withContext(Dispatchers.Main) {
                    player = ExoPlayer.Builder(context).build().apply {
                        volume = 0f
                        addListener(object : Player.Listener {
                            override fun onPlaybackStateChanged(playbackState: Int) {
                                if (playbackState == Player.STATE_READY) ready.complete(Unit)
                            }
                            override fun onPlayerError(error: PlaybackException) {
                                ready.completeExceptionally(error)
                            }
                        })
                        setMediaItem(MediaItem.fromUri(uri))
                        prepare()
                    }
                }
                withTimeout(15_000) { ready.await() }
                val deck = checkNotNull(player)
                val duration = withContext(Dispatchers.Main) { deck.duration }
                assertTrue("Unexpected decoded duration: $duration", duration in 284_000L..288_000L)
                val startPosition = withContext(Dispatchers.Main) { deck.currentPosition.also { deck.play() } }
                val advancedPosition = withTimeout(15_000) {
                    var position: Long
                    do {
                        delay(100)
                        position = withContext(Dispatchers.Main) {
                            check(deck.playerError == null) { "Fixture playback failed: ${deck.playerError}" }
                            deck.currentPosition
                        }
                    } while (position - startPosition <= 1_500)
                    position
                }
                withContext(Dispatchers.Main) { deck.seekTo(duration - 4_000) }
                withTimeout(15_000) {
                    while (!withContext(Dispatchers.Main) {
                        check(deck.playerError == null) { "Fixture end playback failed: ${deck.playerError}" }
                        deck.playbackState == Player.STATE_ENDED
                    }) delay(100)
                }
                Log.i("DownloadAudioFixture", "FLAC download completed: stored=${song.duration}, " +
                    "decoded=$duration, advanced=$advancedPosition, end reached")
            } finally {
                withContext(NonCancellable) {
                    withContext(Dispatchers.Main) { player?.release() }
                    requestId?.let { workManager.cancelWorkById(it).result.get(5, TimeUnit.SECONDS) }
                    val paths = listOfNotNull(published?.toString(), db.songDao().getSong(songId).first()?.path)
                    paths.distinct().forEach { resolver.delete(Uri.parse(it), null, null) }
                    db.downloadDao().delete(songId)
                    db.songDao().deleteById(songId)
                }
            }
        }
    }

    private class FixtureAudio(private val file: File) : AutoCloseable {
        private val listener = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        @Volatile private var socket: Socket? = null
        val sent = CompletableDeferred<Unit>()
        val url = "http://127.0.0.1:${listener.localPort}/audio.mp3"
        private val thread = Thread {
            try {
                listener.accept().use { client ->
                    socket = client
                    client.soTimeout = 15_000
                    val input = client.getInputStream()
                    var tail = ""
                    var headerBytes = 0
                    while (!tail.endsWith("\r\n\r\n")) {
                        check(++headerBytes <= 8192) { "HTTP request headers are too large" }
                        val byte = input.read()
                        check(byte >= 0) { "HTTP client closed before request headers" }
                        tail = (tail + byte.toChar()).takeLast(4)
                    }
                    client.getOutputStream().use { output ->
                        output.write(("HTTP/1.1 200 OK\r\nContent-Length: ${file.length()}\r\n" +
                            "Content-Type: audio/mpeg\r\nConnection: close\r\n\r\n").toByteArray())
                        file.inputStream().use { it.copyTo(output, 64 * 1024) }
                        output.flush()
                    }
                    sent.complete(Unit)
                }
            } catch (error: Exception) { sent.completeExceptionally(error) }
        }.apply { isDaemon = true; start() }

        override fun close() { socket?.close(); listener.close(); thread.join(1_000) }
    }

    private class StalledAudio : AutoCloseable {
        private val listener = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        private var socket: Socket? = null
        val entered = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val url = "http://127.0.0.1:${listener.localPort}/audio.wav"
        private val thread = Thread {
            try {
                listener.accept().use { client ->
                    socket = client
                    client.soTimeout = 15_000
                    val input = client.getInputStream()
                    var tail = ""
                    while (!tail.endsWith("\r\n\r\n")) {
                        val b = input.read(); if (b < 0) return@Thread
                        tail = (tail + b.toChar()).takeLast(4)
                    }
                    client.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Length: 65536\r\nContent-Type: audio/wav\r\n\r\n".toByteArray())
                        write(ByteArray(1024)); flush()
                    }
                    entered.countDown()
                    if (input.read() == -1) closed.countDown()
                }
            } catch (_: Exception) { closed.countDown() }
        }.apply { isDaemon = true; start() }
        override fun close() { socket?.close(); listener.close(); thread.join(1_000) }
    }
}
