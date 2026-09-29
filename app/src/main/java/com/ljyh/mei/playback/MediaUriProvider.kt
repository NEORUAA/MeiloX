package com.ljyh.mei.playback

import android.net.Uri
import androidx.core.net.toUri
import com.ljyh.mei.di.repository.SongRepository
import com.ljyh.mei.data.session.SessionStore
import com.ljyh.mei.data.session.SessionStamp
import kotlinx.coroutines.flow.firstOrNull
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

class SourceNotFoundException(message: String) : IOException(message)

internal data class ResolvedMediaSource(
    val uri: Uri,
    val actualQuality: String,
    val cacheKey: String?,
)

@Singleton
class MediaUriProvider @Inject constructor(
    private val urls: PlaybackUrlResolver,
    private val songRepository: SongRepository,
    private val sessions: SessionStore,
) {
    suspend fun resolveMediaUri(mediaId: String, quality: String): Uri =
        resolveMediaSource(mediaId, quality, sessions.snapshot()).uri

    internal suspend fun resolveMediaSource(mediaId: String, quality: String, owner: SessionStamp): ResolvedMediaSource {
        sessions.requireCurrent(owner)
        val requestedQuality = normalizePlaybackQuality(quality)
        val localPath = songRepository.getSong(mediaId).firstOrNull()?.path
            ?: songRepository.getSong("local_$mediaId").firstOrNull()?.path
        sessions.requireCurrent(owner)
        if (localPath != null) {
            if (localPath.startsWith("content://")) {
                return ResolvedMediaSource(Uri.parse(localPath), requestedQuality, cacheKey = null)
            }
            val file = File(localPath)
            if (file.exists()) return ResolvedMediaSource(Uri.fromFile(file), requestedQuality, cacheKey = null)
        }
        val resolved = urls.resolve(mediaId, quality, owner)
        sessions.requirePlaybackSession(owner)
        return ResolvedMediaSource(resolved.url.toUri(), resolved.actualQuality, resolved.cacheKey)
    }

    fun invalidate(mediaId: String) = urls.invalidate(mediaId)
}
