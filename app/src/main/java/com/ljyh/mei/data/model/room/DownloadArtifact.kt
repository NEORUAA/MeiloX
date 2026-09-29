package com.ljyh.mei.data.model.room

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A durable receipt for one newly created file, independent of queue replacement/deletion. */
@Entity(tableName = "download_artifact")
data class DownloadArtifact(
    @PrimaryKey val requestId: String,
    val songId: String,
    val ownerId: Long,
    val ownerPackage: String,
    val displayName: String,
    val relativePath: String,
    val mimeType: String,
    val size: Long,
    val sha256: String,
    val uri: String = "",
    val providerVersion: String = "",
    val generation: Long = 0,
    val phase: String = COPYING,
) {
    val stagingPath: String get() = "Music/.meilox-download/$requestId/"

    companion object {
        const val COPYING = "COPYING"
        const val COMMITTED = "COMMITTED"
        const val PUBLISHED = "PUBLISHED"
    }
}
