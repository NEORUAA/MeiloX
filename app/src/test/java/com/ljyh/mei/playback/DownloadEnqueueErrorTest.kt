package com.ljyh.mei.playback

import com.ljyh.mei.R
import com.ljyh.mei.data.session.SessionChangedException
import com.ljyh.mei.utils.DownloadQueueUnavailableException
import com.ljyh.mei.utils.downloadEnqueueError
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadEnqueueErrorTest {
    @Test fun enqueueErrorsDoNotClaimThatAUrlRequestFailed() {
        assertEquals(R.string.download_queue_unavailable, downloadEnqueueError(DownloadQueueUnavailableException(IllegalStateException())))
        assertEquals(R.string.download_session_changed, downloadEnqueueError(SessionChangedException()))
        assertEquals(R.string.download_enqueue_failed, downloadEnqueueError(IllegalArgumentException()))
    }
}
