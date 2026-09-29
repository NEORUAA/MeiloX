package com.ljyh.mei.playback

import com.ljyh.mei.constants.MusicQuality
import com.ljyh.mei.data.model.room.DownloadStatus
import com.ljyh.mei.data.model.room.DownloadTask
import com.ljyh.mei.data.session.SessionStore
import com.ljyh.mei.data.session.SessionChangedException
import com.ljyh.mei.data.session.SessionStamp
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job

/** Durable identity is public account ID plus a unique request, never a session credential. */
internal fun DownloadTask.requireExecutable(request: UUID, account: Long) {
    if (requestId != request.toString() || ownerId <= 0 || ownerId != account ||
        songId.toLongOrNull()?.takeIf { it > 0 }?.toString() != songId ||
        status !in setOf(DownloadStatus.PENDING, DownloadStatus.DOWNLOADING) ||
        MusicQuality.entries.none { it.text == quality }) {
        throw CancellationException("Download request is no longer executable")
    }
}

internal fun SessionStore.requireDownloadOwner(owner: SessionStamp) {
    requirePlaybackSession(owner)
    if (owner.identity.userId <= 0 || !owner.identity.authenticated || owner.identity.anonymous) {
        throw SessionChangedException()
    }
}

internal suspend fun <T> SessionStore.withDownloadOwner(owner: SessionStamp, block: suspend () -> T): T = coroutineScope {
    val job = currentCoroutineContext().job
    val subscription = onInvalidated { job.cancel(CancellationException("Official download session changed")) }
    try {
        requireDownloadOwner(owner)
        block()
    } finally { subscription.close() }
}
