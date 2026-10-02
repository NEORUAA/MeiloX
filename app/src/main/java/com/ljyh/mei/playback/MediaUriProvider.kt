package com.ljyh.mei.playback

import android.net.Uri
import androidx.core.net.toUri
import com.ljyh.mei.di.repository.SongRepository
import com.ljyh.mei.di.repository.DownloadRepository
import com.ljyh.mei.data.model.SongSourceIdentity
import com.ljyh.mei.data.model.room.DownloadStatus
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
    val onlineSource: PlaybackUrl? = null,
)

@Singleton
class MediaUriProvider @Inject constructor(
    private val urls: PlaybackUrlResolver,
    private val songRepository: SongRepository,
    private val sessions: SessionStore,
    private val downloads: DownloadRepository? = null,
) {
    suspend fun resolveMediaUri(mediaId: String, quality: String): Uri =
        resolveMediaSource(mediaId, quality, sessions.snapshot()).uri

    internal suspend fun resolveMediaSource(mediaId: String, quality: String, owner: SessionStamp): ResolvedMediaSource {
        sessions.requireCurrent(owner)
        val cloud = mediaId.takeIf { it.startsWith("meilox-cloud-v1:") }?.let(SongSourceIdentity::fromKey)
        cloud?.requireAccount(owner.identity)
        val requestedQuality = normalizePlaybackQuality(quality)
        val localId = cloud?.entryId?.toString() ?: mediaId
        val mayUseLocal = cloud == null || downloads?.getBySongId(localId)?.let { task ->
            task.sourceKey == cloud.key && task.ownerId == cloud.accountId && task.status == DownloadStatus.COMPLETED
        } == true
        val localPath = if (mayUseLocal) songRepository.getSong(localId).firstOrNull()?.path
            ?: songRepository.getSong("local_$localId").firstOrNull()?.path else null
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
        return ResolvedMediaSource(resolved.url.toUri(), resolved.actualQuality, resolved.cacheKey, resolved)
    }

    fun invalidate(mediaId: String) = urls.invalidate(mediaId)
}
