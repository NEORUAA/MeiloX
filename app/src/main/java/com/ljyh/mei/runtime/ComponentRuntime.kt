package com.ljyh.mei.runtime

import android.app.Activity
import android.content.Context
import android.media.session.MediaSession

/** Platform attachment and media-control integration supplied by the selected runtime. */
interface ComponentRuntime {
    fun wrapComponent(base: Context): Context
    fun activityCreated(activity: Activity, restored: Boolean)
    val deferMediaButtonsUntilRestored: Boolean
    fun consumePlaybackResumeRequest(): Boolean
    fun bindMediaButtons(context: Context, token: MediaSession.Token): AutoCloseable?
    fun playbackServiceCreated(sessionCount: Int)
}
