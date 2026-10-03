package com.ljyh.mei.data.network

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NeteaseLoginSecurityInstrumentedTest {
    @Test
    fun generatesYidunDeviceTokenFromPackagedRuntime() = runBlocking {
        val security = NeteaseLoginTestRuntime.security

        assertTrue(security.ydDeviceToken().isNotBlank())
    }

    @Test
    fun generatesIndependentWatchManCheckTokenFromPackagedRuntime() = runBlocking {
        val security = NeteaseLoginTestRuntime.security

        val checkToken = security.freshCheckToken()
        val ydDeviceToken = security.ydDeviceToken()

        assertTrue(checkToken.isNotBlank())
        assertTrue(ydDeviceToken.isNotBlank())
        assertNotEquals(checkToken, ydDeviceToken)
    }
}
