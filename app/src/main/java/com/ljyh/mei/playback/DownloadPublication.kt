package com.ljyh.mei.playback

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.ljyh.mei.data.model.room.DownloadArtifact
import com.ljyh.mei.data.model.room.DownloadTask
import com.ljyh.mei.data.model.room.Song
import com.ljyh.mei.data.model.room.SourceType
import com.ljyh.mei.di.AppDatabase
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import timber.log.Timber

internal data class DownloadMediaReceipt(
    val uri: String,
    val version: String,
    val generation: Long,
    val owner: String,
    val path: String,
    val pending: Boolean,
)

internal interface DownloadMediaStore {
    fun create(artifact: DownloadArtifact): DownloadMediaReceipt
    fun staged(artifact: DownloadArtifact): List<DownloadMediaReceipt>
    fun inspect(uri: String): DownloadMediaReceipt?
    fun copy(uri: String, source: File, requireActive: () -> Unit)
    fun matchesContent(artifact: DownloadArtifact): Boolean
    fun publish(artifact: DownloadArtifact)
    fun delete(uri: String)
}

/** All calls run on IO under DownloadManager.mutations, including startup recovery. */
internal class DownloadPublication(
    private val db: AppDatabase,
    private val media: DownloadMediaStore,
    private val ownerPackage: String,
) {
    private val dao get() = db.downloadArtifactDao()

    fun store(
        task: DownloadTask, file: File, name: String, type: String, path: String, duration: Long,
        requireActive: () -> Unit, withOwner: (() -> Unit) -> Unit,
    ) {
        requireActive()
        task.requireExecutable(UUID.fromString(task.requestId), task.ownerId)
        var artifact = DownloadArtifact(
            requestId = task.requestId, songId = task.songId, ownerId = task.ownerId,
            ownerPackage = ownerPackage, displayName = name, relativePath = path.trimEnd('/') + "/",
            mimeType = when (type) {
                "flac" -> "audio/flac"
                "aac" -> "audio/aac"
                "ogg" -> "audio/ogg"
                "wav" -> "audio/wav"
                "m4a" -> "audio/mp4"
                "opus" -> "audio/opus"
                else -> "audio/mpeg"
            },
            size = file.length(), sha256 = file.inputStream().use { digest(it).second },
        )
        require(artifact.size > 0)
        // The private UUID directory identifies insertion even if killed before saving its URI.
        dao.insert(artifact)
        try {
            val receipt = media.create(artifact)
            check(receipt.owner == ownerPackage && receipt.pending && receipt.path == artifact.stagingPath)
            artifact = artifact.copy(uri = receipt.uri, providerVersion = receipt.version, generation = receipt.generation)
            check(dao.update(artifact) == 1)
            media.copy(artifact.uri, file, requireActive)
            check(media.matchesContent(artifact)) { "Downloaded media verification failed" }
            requireActive()
            val committed = artifact.copy(phase = DownloadArtifact.COMMITTED)
            withOwner {
                db.runInTransaction {
                    val current = checkNotNull(dao.task(task.songId, task.requestId, task.ownerId))
                    current.requireExecutable(UUID.fromString(task.requestId), task.ownerId)
                    dao.insertSong(Song(
                        id = task.songId, title = task.songTitle,
                        artist = task.songArtist.split(Regex("[/、,;]")).map(String::trim).filter(String::isNotBlank)
                            .ifEmpty { listOf(task.songArtist.trim()) },
                        album = task.songAlbum, cover = task.songCover, duration = duration,
                        path = artifact.uri, sourceType = SourceType.DOWNLOAD, folderPath = path,
                    ))
                    check(dao.complete(task.songId, task.requestId, task.ownerId, System.currentTimeMillis()) == 1)
                    check(dao.update(committed) == 1)
                }
            }
            artifact = committed
            publish(artifact)
        } catch (error: Exception) {
            // A committed file belongs to Room even if MediaStore publication must be retried.
            if (artifact.phase == DownloadArtifact.COPYING) runCatching { discard(artifact) }
            throw error
        }
    }

    fun recover() {
        dao.all().forEach { artifact ->
            try {
                when {
                    artifact.phase == DownloadArtifact.COPYING -> discard(artifact)
                    artifact.uri.isNotEmpty() && !dao.isReferenced(artifact.uri) -> discard(artifact)
                    artifact.phase == DownloadArtifact.COMMITTED -> publish(artifact)
                }
            } catch (error: Exception) {
                Timber.w("Download publication recovery deferred: %s", error.javaClass.simpleName)
            }
        }
    }

    private fun publish(artifact: DownloadArtifact) {
        val row = checkNotNull(media.inspect(artifact.uri)) { "Committed download is missing" }
        requireIdentity(artifact, row)
        check(media.matchesContent(artifact)) { "Committed download was modified" }
        if (row.pending) {
            check(row.path == artifact.stagingPath)
            media.publish(artifact)
        } else check(row.path == artifact.relativePath)
        check(dao.update(artifact.copy(phase = DownloadArtifact.PUBLISHED)) == 1)
    }

    private fun discard(artifact: DownloadArtifact) {
        if (artifact.uri.isEmpty()) {
            media.staged(artifact).forEach { row ->
                check(row.owner == ownerPackage && row.pending && row.path == artifact.stagingPath)
                media.delete(row.uri)
            }
        } else media.inspect(artifact.uri)?.let { row ->
            requireIdentity(artifact, row)
            if (artifact.phase == DownloadArtifact.COPYING) {
                check(row.pending && row.path == artifact.stagingPath)
            } else {
                check(row.path in setOf(artifact.stagingPath, artifact.relativePath))
                check(media.matchesContent(artifact)) { "Detached download was modified" }
            }
            media.delete(artifact.uri)
        }
        dao.delete(artifact.requestId)
    }

    private fun requireIdentity(artifact: DownloadArtifact, row: DownloadMediaReceipt) {
        check(artifact.ownerPackage == ownerPackage && row.owner == ownerPackage &&
            row.version == artifact.providerVersion && row.generation == artifact.generation && row.uri == artifact.uri) {
            "Download media identity changed"
        }
    }
}

internal class AndroidDownloadMediaStore(context: Context) : DownloadMediaStore {
    private val context = context.applicationContext
    private val resolver = context.contentResolver
    private val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    override fun create(artifact: DownloadArtifact): DownloadMediaReceipt {
        val uri = checkNotNull(resolver.insert(collection, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, artifact.displayName)
            put(MediaStore.MediaColumns.RELATIVE_PATH, artifact.stagingPath)
            put(MediaStore.MediaColumns.MIME_TYPE, artifact.mimeType)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        })) { "Cannot create download media" }
        return checkNotNull(inspect(uri.toString()))
    }

    override fun staged(artifact: DownloadArtifact): List<DownloadMediaReceipt> = query(
        collection, "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ? AND ${MediaStore.MediaColumns.IS_PENDING} = 1",
        arrayOf(artifact.stagingPath, artifact.ownerPackage),
    )

    override fun inspect(uri: String): DownloadMediaReceipt? = query(Uri.parse(uri), null, null).singleOrNull()

    private fun query(uri: Uri, selection: String?, args: Array<String>?): List<DownloadMediaReceipt> {
        val version = MediaStore.getVersion(context, MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val columns = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.GENERATION_ADDED,
            MediaStore.MediaColumns.OWNER_PACKAGE_NAME, MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.IS_PENDING)
        return checkNotNull(resolver.query(uri, columns, selection, args, null)).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(DownloadMediaReceipt(
                    uri = ContentUris.withAppendedId(collection, cursor.getLong(0)).toString(), version = version,
                    generation = cursor.getLong(1), owner = cursor.getString(2).orEmpty(),
                    path = cursor.getString(3).orEmpty(), pending = cursor.getInt(4) == 1,
                ))
            }
        }
    }

    override fun copy(uri: String, source: File, requireActive: () -> Unit) {
        checkNotNull(resolver.openFileDescriptor(Uri.parse(uri), "w")).use { descriptor ->
            FileOutputStream(descriptor.fileDescriptor).use { output ->
                source.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        requireActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                }
                output.fd.sync()
            }
        }
    }

    override fun matchesContent(artifact: DownloadArtifact): Boolean =
        checkNotNull(resolver.openInputStream(Uri.parse(artifact.uri))).use {
            val (size, sha256) = digest(it)
            size == artifact.size && sha256 == artifact.sha256
        }

    override fun publish(artifact: DownloadArtifact) {
        check(resolver.update(Uri.parse(artifact.uri), ContentValues().apply {
            put(MediaStore.MediaColumns.RELATIVE_PATH, artifact.relativePath)
            put(MediaStore.MediaColumns.DISPLAY_NAME, artifact.displayName)
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }, null, null) == 1) { "Cannot publish downloaded media" }
    }

    override fun delete(uri: String) {
        check(resolver.delete(Uri.parse(uri), null, null) == 1) { "Cannot remove downloaded media" }
    }
}

private fun digest(input: InputStream): Pair<Long, String> {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    var size = 0L
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        digest.update(buffer, 0, count)
        size += count
    }
    return size to digest.digest().joinToString("") { "%02x".format(it) }
}
