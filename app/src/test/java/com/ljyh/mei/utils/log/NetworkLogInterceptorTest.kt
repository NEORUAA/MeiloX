package com.ljyh.mei.utils.log

import java.io.IOException
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import timber.log.Timber

class NetworkLogInterceptorTest {
    private val events = mutableListOf<LogEvent>()

    @After
    fun tearDown() {
        Timber.uprootAll()
    }

    @Test
    fun playbackHistoryFailureDoesNotLogRawThrowable() {
        val secret = "synthetic-music-u-secret"
        plantRecordingTree()

        runFailingRequest(
            url = "https://interface.music.163.com/eapi/feedback/weblog?MUSIC_U=$secret",
        )

        assertTrue(events.isEmpty())
    }

    @Test
    fun ordinaryFailureKeepsExistingThrowableLogging() {
        val secret = "synthetic-ordinary-secret"
        plantRecordingTree()

        runFailingRequest(
            url = "https://interface.music.163.com/eapi/song/detail?token=$secret",
        )

        assertTrue(events.any { it.throwableMessage?.contains(secret) == true })
    }

    @Test
    fun playbackProfileAndRoutePredicateAreNarrow() {
        assertTrue(
            isPlaybackHistoryRequest(
                Request.Builder()
                    .url("https://example.test/anything")
                    .header("X-Netease-Eapi-Profile", "playback-history")
                    .build(),
            ),
        )
        assertTrue(
            isPlaybackHistoryRequest(
                Request.Builder().url("https://example.test/eapi/feedback/weblog").build(),
            ),
        )
        assertFalse(
            isPlaybackHistoryRequest(
                Request.Builder().url("https://example.test/eapi/song/detail").build(),
            ),
        )
    }

    private fun plantRecordingTree() {
        Timber.plant(object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                events += LogEvent(message, t?.message)
            }
        })
    }

    private fun runFailingRequest(url: String) {
        val client = OkHttpClient.Builder()
            .addInterceptor(NetworkLogInterceptor())
            .addInterceptor { throw IOException("network failure: $url") }
            .build()
        runCatching {
            client.newCall(Request.Builder().url(url).build()).execute()
        }
    }

    private data class LogEvent(
        val message: String,
        val throwableMessage: String?,
    )
}
