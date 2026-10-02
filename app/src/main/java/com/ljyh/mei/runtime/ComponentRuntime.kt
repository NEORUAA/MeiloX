package com.ljyh.mei.runtime

import android.app.Activity
import android.content.Context
import android.media.session.MediaSession
import android.net.Uri
import com.ljyh.mei.recognition.PlatformRecognitionCapture
import com.ljyh.mei.recognition.RecognitionCapture
import java.io.File

/** Platform attachment and media-control integration supplied by the selected runtime. */
interface ComponentRuntime {
    val usesLyricsPipHelper: Boolean get() = false
    fun enterLyricsPip(activity: Activity, source: LyricsPipSource) = source.close()
    fun recognitionCapture(context: Context): RecognitionCapture = PlatformRecognitionCapture(context)
    fun wrapComponent(base: Context): Context
    fun activityCreated(activity: Activity, restored: Boolean)
    val deferMediaButtonsUntilRestored: Boolean
    fun consumePlaybackResumeRequest(): Boolean
    fun bindMediaButtons(context: Context, token: MediaSession.Token): AutoCloseable?
    fun playbackServiceCreated(sessionCount: Int)
    fun logShareUri(context: Context, file: File): Uri
    fun clearLogShares(context: Context)
}
