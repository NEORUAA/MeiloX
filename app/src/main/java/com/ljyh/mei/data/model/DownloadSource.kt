package com.ljyh.mei.data.model

data class DownloadSource(
    val id: Long,
    val url: String,
    val fileType: String,
    val level: String,
    val requestedLevel: String,
    val size: Long,
    val md5: String,
    val expiresAtMs: Long?,
)

data class DownloadSources(
    val sources: List<DownloadSource>,
    val rejectedCodes: Map<String, Int> = emptyMap(),
)
