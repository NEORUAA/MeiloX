package com.ljyh.mei.data.model.room

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo
import com.ljyh.mei.source.api.ProviderIds
import kotlin.math.exp

@Entity(tableName = "playlist")
data class Playlist(
    @PrimaryKey val id: String,
    val title: String,
    val cover: String,
    val author: String,
    val authorName: String,
    val authorAvatar: String,
    val count: Int,
    val type: PlaylistType = PlaylistType.NETEAST,
    val description: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val playCount: Long = 0L,
    val lastPlayTime: Long = 0L,
    val localPlayCount: Int = 0,
    @ColumnInfo(defaultValue = "'netease'")
    val providerId: String = if (type == PlaylistType.NETEAST) ProviderIds.NETEASE else ProviderIds.LOCAL,
    @ColumnInfo(defaultValue = "''") val providerPlaylistId: String = id,
) {
    fun sortScore(
        maxLocalPlayCount: Int = 1,
        maxServerPlayCount: Long = 1L,
        now: Long = System.currentTimeMillis()
    ): Double {
        val daysSinceLastPlay = if (lastPlayTime > 0L) {
            (now - lastPlayTime) / (1000.0 * 60 * 60 * 24)
        } else Double.MAX_VALUE

        val recent = exp(-daysSinceLastPlay / 30.0)
        val frequency = localPlayCount.toDouble() / maxLocalPlayCount.coerceAtLeast(1)
        val serverHeat = playCount.toDouble() / maxServerPlayCount.coerceAtLeast(1)

        return 50.0 * recent + 30.0 * frequency + 20.0 * serverHeat
    }
}
