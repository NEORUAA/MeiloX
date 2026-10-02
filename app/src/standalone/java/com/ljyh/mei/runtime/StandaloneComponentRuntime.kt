package com.ljyh.mei.runtime

import android.app.Activity
import android.content.Context
import android.media.session.MediaSession
import androidx.core.content.FileProvider
import java.io.File
import javax.inject.Inject

/** Ordinary registered Android components need neither carrier routing nor host wrapping. */
class StandaloneComponentRuntime @Inject constructor() : ComponentRuntime {
    override fun wrapComponent(base: Context) = base
    override fun activityCreated(activity: Activity, restored: Boolean) = Unit
    override val deferMediaButtonsUntilRestored = false
    override fun consumePlaybackResumeRequest() = false
    override fun bindMediaButtons(context: Context, token: MediaSession.Token): AutoCloseable? = null
    override fun playbackServiceCreated(sessionCount: Int) = Unit
    override fun logShareUri(context: Context, file: File) =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    override fun clearLogShares(context: Context) = Unit
}
