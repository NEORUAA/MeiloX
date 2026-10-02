package com.ljyh.mei.ui.screen.account

import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class WebCookieLoginPoller(
    private val pollIntervalMs: Long = 500,
    private val retryDelayMs: Long = 5_000,
    private val isOwnerCurrent: () -> Boolean? = { true },
) {
    private val verification = Mutex()
    private var webJob: Job? = null
    private var manualLogin = false
    private var completed = false

    init {
        require(pollIntervalMs > 0 && retryDelayMs >= pollIntervalMs)
    }

    fun pauseForManualLogin() {
        manualLogin = true
        webJob?.cancel()
    }

    fun resumeWebLogin() {
        manualLogin = false
    }

    suspend fun loginManually(musicU: String, verify: suspend (String) -> Boolean): Boolean {
        pauseForManualLogin()
        return verification.withLock {
            currentCoroutineContext().ensureActive()
            if (completed || isOwnerCurrent() != true) return@withLock false
            val succeeded = verify(musicU)
            currentCoroutineContext().ensureActive()
            if (succeeded) completed = true
            succeeded
        }
    }

    suspend fun poll(readMusicU: () -> String?, verify: suspend (String) -> Boolean): Boolean {
        currentCoroutineContext().ensureActive()
        val job = currentCoroutineContext()[Job]!!
        if (manualLogin || completed || isOwnerCurrent() == false) return false
        webJob?.cancel()
        webJob = job
        var failedCookie: String? = null
        var retryWaitMs = 0L
        try {
            while (!manualLogin && !completed) {
                currentCoroutineContext().ensureActive()
                val ownerCurrent = isOwnerCurrent()
                if (ownerCurrent == false) return false
                if (ownerCurrent == null) {
                    delay(pollIntervalMs)
                    continue
                }
                val musicU = readMusicU()?.takeIf(String::isNotBlank)
                if (musicU != null && (musicU != failedCookie || retryWaitMs <= 0)) {
                    // Manual login cancels this owner before waiting for any pending verification.
                    val succeeded = verification.withLock {
                        currentCoroutineContext().ensureActive()
                        if (manualLogin || completed || isOwnerCurrent() != true) return@withLock false
                        val verified = verify(musicU)
                        currentCoroutineContext().ensureActive()
                        verified
                    }
                    if (succeeded) {
                        completed = true
                        return true
                    }
                    failedCookie = musicU
                    retryWaitMs = retryDelayMs
                }
                delay(pollIntervalMs)
                retryWaitMs -= pollIntervalMs
            }
            return false
        } finally {
            if (webJob === job) webJob = null
        }
    }
}

internal fun musicUFromCookieHeader(header: String): String? = header.split(';')
    .map(String::trim)
    .firstOrNull { it.startsWith("MUSIC_U=") }
    ?.substringAfter('=')
    ?.takeIf(String::isNotBlank)
