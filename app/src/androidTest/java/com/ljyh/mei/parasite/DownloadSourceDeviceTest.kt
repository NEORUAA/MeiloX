package com.ljyh.mei.parasite

import com.ljyh.mei.data.session.SessionChangedException
import com.ljyh.mei.data.session.SessionIdentity
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ljyh.mei.constants.MusicQuality
import com.ljyh.mei.data.network.api.ApiService
import com.ljyh.mei.playback.resolveOfficialDownloadSources
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/** Exercises the Android Retrofit boundary with a substitute backend, never the account or media store. */
@RunWith(AndroidJUnit4::class)
class DownloadSourceDeviceTest {
    @Test fun downloadLyricFallbackUsesTheOfficialTransportAndCapturedSession() = runBlocking {
        val sessions = HostSessionBridge()
        val requests = HostRequestBridge(sessions)
        var calls = 0
        requests.bind(object : HostRequestBackend {
            override fun sessionIdentity() = SessionIdentity(17, true, false)
            override fun open(path: String, parameters: Map<String, String>): HostPendingRequest {
                assertEquals("song/lyric/v1", path)
                assertEquals("1", parameters["id"])
                assertEquals("false", parameters["cp"])
                calls++
                return object : HostPendingRequest {
                    override fun execute() = """{"code":200,"lrc":{"version":1,"lyric":"substitute lyric"}}"""
                    override fun cancel() = Unit
                    override fun close() = Unit
                }
            }
        })
        val api = Retrofit.Builder().baseUrl("https://music.163.com/")
            .callFactory(HostCallFactory(requests)).addConverterFactory(GsonConverterFactory.create())
            .build().create(ApiService::class.java)
        val owner = sessions.snapshot()
        val lyric = api.getLyricV1(com.ljyh.mei.data.model.api.GetLyricV1("1"), owner)
        assertEquals("substitute lyric", lyric.lrc?.lyric)
        assertEquals("substitute lyric", api.getLyricV1(com.ljyh.mei.data.model.api.GetLyricV1("1")).lrc?.lyric)
        sessions.invalidate()
        assertTrue(runCatching { api.getLyricV1(com.ljyh.mei.data.model.api.GetLyricV1("1"), owner) }.isFailure)
        assertEquals(2, calls)
    }

    @Test fun downloadObjectAndTuplePassThroughTheAndroidTransportWithoutCookiesOrPlaybackFallback() = runBlocking {
        val sessions = HostSessionBridge()
        val requests = HostRequestBridge(sessions)
        var calls = 0
        var denied = false
        requests.bind(object : HostRequestBackend {
            override fun sessionIdentity() = SessionIdentity(17, true, false)
            override fun open(path: String, parameters: Map<String, String>): HostPendingRequest {
                assertEquals("song/enhance/download/url/v1", path)
                assertEquals(mapOf("id" to "1_0", "level" to "lossless", "immerseType" to "ste"), parameters)
                calls++
                return object : HostPendingRequest {
                    override fun execute() = if (denied) """{"code":200,"data":{"id":1,"code":-105,"url":null}}"""
                    else """{"code":200,"data":{"id":1,"code":200,"url":"https://media.example.test/song.flac","type":"flac","level":"lossless","size":12345678901,"md5":"0123456789abcdef0123456789abcdef","expi":600}}"""
                    override fun cancel() = Unit
                    override fun close() = Unit
                }
            }
        })
        val api = Retrofit.Builder().baseUrl("https://music.163.com/")
            .callFactory(HostCallFactory(requests)).addConverterFactory(GsonConverterFactory.create())
            .build().create(ApiService::class.java)
        val owner = sessions.snapshot()
        val source = resolveOfficialDownloadSources(api, sessions, listOf("1"), MusicQuality.LOSSLESS, owner).sources.single()
        assertEquals(12345678901L, source.size)
        assertEquals("flac", source.fileType)
        assertEquals("lossless", source.level)
        denied = true
        val rejected = resolveOfficialDownloadSources(api, sessions, listOf("1"), MusicQuality.LOSSLESS, owner)
        assertEquals(mapOf("1" to -105), rejected.rejectedCodes)
        assertTrue(rejected.sources.isEmpty())
        sessions.invalidate()
        assertTrue(runCatching { resolveOfficialDownloadSources(api, sessions, listOf("1"), MusicQuality.LOSSLESS, owner) }
            .exceptionOrNull() is SessionChangedException)
        assertEquals(2, calls)
    }
}
