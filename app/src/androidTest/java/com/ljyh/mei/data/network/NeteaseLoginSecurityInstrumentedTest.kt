package com.ljyh.mei.data.network

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NeteaseLoginSecurityInstrumentedTest {
    @Test
    fun generatesYidunDeviceTokenFromPackagedRuntime() = runBlocking {
        val security = NeteaseLoginSecurity(
            context = InstrumentationRegistry.getInstrumentation().targetContext,
            client = OkHttpClient(),
        )

        assertTrue(security.ydDeviceToken().isNotBlank())
    }

    @Test
    fun generatesIndependentWatchManCheckTokenFromPackagedRuntime() = runBlocking {
        val security = NeteaseLoginSecurity(
            context = InstrumentationRegistry.getInstrumentation().targetContext,
            client = OkHttpClient(),
        )

        val checkToken = security.freshCheckToken()
        val ydDeviceToken = security.ydDeviceToken()

        assertTrue(checkToken.isNotBlank())
        assertTrue(ydDeviceToken.isNotBlank())
        assertNotEquals(checkToken, ydDeviceToken)
    }
}
