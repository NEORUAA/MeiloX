package com.ljyh.mei.utils

import com.ljyh.mei.R
import com.ljyh.mei.parasite.HostSessionChangedException

internal class DownloadQueueUnavailableException(cause: Throwable) : IllegalStateException("Download scheduler is unavailable", cause)

internal fun downloadEnqueueError(error: Exception): Int {
    timber.log.Timber.w("Download enqueue failed: %s", error.javaClass.simpleName)
    return when (error) {
        is DownloadQueueUnavailableException -> R.string.download_queue_unavailable
        is HostSessionChangedException -> R.string.download_session_changed
        else -> R.string.download_enqueue_failed
    }
}
