package com.ljyh.mei.data.model.room

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo

@Entity(tableName = "download_task")
data class DownloadTask(
    @PrimaryKey val songId: String,
    val url: String = "",
    val fileName: String = "",
    val fileType: String = "",
    val status: DownloadStatus = DownloadStatus.PENDING,
    val progress: Int = 0,
    val songTitle: String = "",
    val songArtist: String = "",
    val songAlbum: String = "",
    val songCover: String = "",
    val quality: String = "",
    @ColumnInfo(defaultValue = "''") val requestId: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val originTrackRef: String? = null,
    val resolvedTrackRef: String? = null,
    @ColumnInfo(defaultValue = "'netease'") val actualAudioProviderId: String = "netease",
    @ColumnInfo(defaultValue = "'netease'") val actualProviderInstanceId: String = "netease",
    val actualQuality: String? = null,
)
