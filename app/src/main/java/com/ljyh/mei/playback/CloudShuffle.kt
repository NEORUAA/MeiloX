package com.ljyh.mei.playback

import com.ljyh.mei.data.model.api.RandomPlaylistData
import com.ljyh.mei.data.model.api.RandomPlaylistResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/** Null means keep the already-playing local shuffle without rebuilding or seeking. */
internal suspend fun fetchCloudShuffleOrNull(
    timeoutMs: Long = 10_000L,
    request: suspend () -> RandomPlaylistResponse,
): RandomPlaylistData? = try {
    withTimeoutOrNull(timeoutMs) {
        val response = request()
        val data = response.data ?: return@withTimeoutOrNull null
        if (response.code != 200 || data.songIds.isNullOrEmpty()) return@withTimeoutOrNull null
        // Validate before the caller mutates the queue, including malformed Gson elements.
        if (data.songIds.any { it.id <= 0 } ||
            data.songData.orEmpty().any { it.id <= 0 } ||
            data.privileges.orEmpty().any { it.id <= 0 }
        ) return@withTimeoutOrNull null
        data
    }
} catch (error: CancellationException) {
    // Disabling cloud shuffle or changing queues must not trigger another playback action.
    throw error
} catch (_: Exception) {
    null
}
