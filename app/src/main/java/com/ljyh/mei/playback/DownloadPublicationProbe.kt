package com.ljyh.mei.playback

import android.content.Context
import androidx.room.Room
import com.ljyh.mei.BuildConfig
import com.ljyh.mei.data.model.room.DownloadArtifact
import com.ljyh.mei.data.model.room.DownloadTask
import com.ljyh.mei.di.AppDatabase
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlinx.coroutines.runBlocking

/** Synthetic local publication only, available to instrumentation or explicit debug probes. */
internal object DownloadPublicationProbe {
    fun run(context: Context): Boolean {
        check(BuildConfig.DEBUG)
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val file = File.createTempFile("meilox-publication-probe-", ".wav", context.cacheDir)
        val media = AndroidDownloadMediaStore(context)
        val publication = DownloadPublication(db, media, context.packageName)
        try {
            val audio = ByteBuffer.allocate(44 + 1600).order(ByteOrder.LITTLE_ENDIAN)
                .put("RIFF".toByteArray()).putInt(1636).put("WAVEfmt ".toByteArray()).putInt(16)
                .putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16)
                .put("data".toByteArray()).putInt(1600).array()
            file.writeBytes(audio)
            val task = DownloadTask("1", requestId = UUID.randomUUID().toString(), ownerId = 1, quality = "standard")
            runBlocking { db.downloadDao().insert(task) }
            publication.store(task, file, "MeiloX synthetic test.wav", "wav", "Music/MeiloX Test/${task.requestId}", 100, {}, { it() })
            val artifact = db.downloadArtifactDao().all().single()
            check(artifact.phase == DownloadArtifact.PUBLISHED)
            check(media.matchesContent(artifact))
            check(media.inspect(artifact.uri)?.pending == false)
            runBlocking { db.songDao().updatePath("1", null) }
            publication.recover()
            check(media.inspect(artifact.uri) == null && db.downloadArtifactDao().all().isEmpty())
            return true
        } finally {
            runBlocking { db.songDao().updatePath("1", null) }
            publication.recover()
            file.delete()
            db.close()
        }
    }
}
