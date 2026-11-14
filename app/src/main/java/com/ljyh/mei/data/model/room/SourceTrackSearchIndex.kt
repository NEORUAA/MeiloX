package com.ljyh.mei.data.model.room

import androidx.room.Entity
import androidx.room.Index

/** Persisted metadata used to search a source-owned playlist without reloading every page. */
@Entity(
    tableName = "source_track_search_index",
    primaryKeys = [
        "providerId", "providerInstanceId", "providerVersion", "accountRevision",
        "playlistId", "playlistFingerprint", "position",
    ],
    indices = [
        Index(
            value = ["providerId", "providerInstanceId", "providerVersion", "accountRevision", "playlistId", "playlistFingerprint"],
            name = "idx_source_track_search_lookup",
        ),
        Index(value = ["updatedAt"], name = "idx_source_track_search_updated"),
    ],
)
data class SourceTrackSearchIndex(
    val providerId: String,
    val providerInstanceId: String,
    val providerVersion: String,
    val accountRevision: String,
    val playlistId: String,
    val playlistFingerprint: String,
    val contentRevision: Long,
    val position: Int,
    val trackId: String,
    val title: String,
    /** Unit-separator delimited artist names. */
    val artists: String,
    val albumTitle: String,
    /** Complete provider-neutral Track payload for cold-start browsing and playback metadata. */
    val trackJson: String = "",
    val expectedCount: Int,
    val updatedAt: Long = System.currentTimeMillis(),
) {
    companion object {
        const val ARTIST_SEPARATOR = "\u001F"
    }
}
