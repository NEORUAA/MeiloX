package com.ljyh.mei.playback

internal const val SIMILAR_FM_TITLE = "相似歌曲 FM"

@ConsistentCopyVisibility
internal data class SimilarFmRequest private constructor(val sourceIds: List<String>) {
    fun body(recentTrackIds: List<String> = emptyList()): Map<String, String> = linkedMapOf(
        "seedTrackIds" to com.google.gson.JsonArray().apply { sourceIds.forEach(::add) }.toString(),
        "recentTrackIds" to com.google.gson.JsonArray().apply {
            recentTrackIds.filter { it.isNotBlank() && it.length <= 2048 }.takeLast(200).forEach(::add)
        }.toString(),
    )

    companion object {
        fun fromSongIds(songIds: List<String?>): SimilarFmRequest? {
            if (songIds.isEmpty() || songIds.size > 200 || songIds.any { it.isNullOrBlank() || it.length > 2048 }) return null
            return SimilarFmRequest(songIds.filterNotNull())
        }
    }
}
