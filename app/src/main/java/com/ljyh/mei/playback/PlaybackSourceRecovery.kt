package com.ljyh.mei.playback

import kotlinx.coroutines.Job

/** A retry budget and its pending job belong to the same full playback source. */
internal class PlaybackSourceRecovery(private val maximumAttempts: Int) {
    init { require(maximumAttempts > 0) }

    var sourceKey: String? = null
        private set
    var attempts: Int = 0
        private set
    var job: Job? = null

    fun selectSource(key: String?) {
        if (key == sourceKey) return
        job?.cancel()
        job = null
        sourceKey = key
        attempts = 0
    }

    fun tryBeginAttempt(): Boolean {
        if (sourceKey == null || attempts >= maximumAttempts) return false
        attempts++
        return true
    }

    fun clear() {
        job?.cancel()
        job = null
        sourceKey = null
        attempts = 0
    }
}
