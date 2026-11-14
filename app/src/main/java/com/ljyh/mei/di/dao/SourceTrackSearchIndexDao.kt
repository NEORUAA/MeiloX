package com.ljyh.mei.di.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.ljyh.mei.data.model.room.SourceTrackSearchIndex

@Dao
interface SourceTrackSearchIndexDao {
    @Query("""
        SELECT * FROM source_track_search_index
        WHERE providerId = :providerId
          AND providerInstanceId = :providerInstanceId
          AND providerVersion = :providerVersion
          AND accountRevision = :accountRevision
          AND playlistId = :playlistId
          AND playlistFingerprint = :playlistFingerprint
        ORDER BY position
    """)
    suspend fun getIndex(
        providerId: String,
        providerInstanceId: String,
        providerVersion: String,
        accountRevision: String,
        playlistId: String,
        playlistFingerprint: String,
    ): List<SourceTrackSearchIndex>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entries: List<SourceTrackSearchIndex>)

    /** Replace one verified playlist generation atomically, preserving repeated track positions. */
    @Transaction
    suspend fun replaceIndex(entries: List<SourceTrackSearchIndex>) {
        val first = entries.firstOrNull() ?: return
        require(entries.all {
            it.providerId == first.providerId && it.providerInstanceId == first.providerInstanceId &&
                it.providerVersion == first.providerVersion && it.accountRevision == first.accountRevision &&
                it.playlistId == first.playlistId && it.playlistFingerprint == first.playlistFingerprint
        })
        deleteIndex(first.providerId, first.providerInstanceId, first.providerVersion,
            first.accountRevision, first.playlistId)
        upsert(entries)
    }

    @Query("""
        DELETE FROM source_track_search_index
        WHERE providerId = :providerId
          AND providerInstanceId = :providerInstanceId
          AND providerVersion = :providerVersion
          AND accountRevision = :accountRevision
          AND playlistId = :playlistId
    """)
    suspend fun deleteIndex(
        providerId: String,
        providerInstanceId: String,
        providerVersion: String,
        accountRevision: String,
        playlistId: String,
    )

}
