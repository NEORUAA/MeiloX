package com.ljyh.mei.di

import okhttp3.Call
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class RuntimeTransportBindingsTest {
    @Test fun eachProtocolKeepsItsSelectedFactoryWithoutCreatingAnIndependentClient() {
        val api = Call.Factory { error("No request expected") }
        val weapi = Call.Factory { error("No request expected") }
        val audio = Call.Factory { error("No request expected") }
        val apiRetrofit = RetrofitModule.provideRetrofit(api)
        val weapiRetrofit = RetrofitModule.provideWeApiRetrofit(weapi)
        val audioRetrofit = RetrofitModule.provideAudioMatchRetrofit(audio)
        assertSame(api, apiRetrofit.callFactory())
        assertSame(weapi, weapiRetrofit.callFactory())
        assertSame(audio, audioRetrofit.callFactory())
        assertEquals("https://interface.music.163.com/", apiRetrofit.baseUrl().toString())
        assertEquals("https://music.163.com/", weapiRetrofit.baseUrl().toString())
        assertEquals("https://interface.music.163.com/", audioRetrofit.baseUrl().toString())
    }
}
