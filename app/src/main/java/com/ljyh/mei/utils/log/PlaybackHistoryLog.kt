package com.ljyh.mei.utils.log

import android.util.Log
import com.ljyh.mei.BuildConfig
import java.util.Locale
import timber.log.Timber

/** Mirrors caller-sanitized playback diagnostics to Logcat in release builds. */
internal fun logPlaybackHistory(
    priority: Int,
    format: String,
    vararg args: Any?,
) {
    val message = runCatching {
        if (args.isEmpty()) format else String.format(Locale.ROOT, format, *args)
    }.getOrDefault(format)

    Timber.tag(PLAYBACK_HISTORY_TAG).log(priority, message)
    if (!BuildConfig.DEBUG) {
        Log.println(priority, PLAYBACK_HISTORY_TAG, message)
    }
}

private const val PLAYBACK_HISTORY_TAG = "PlaybackHistory"
