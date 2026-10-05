package com.ljyh.mei.data.network

import org.junit.Assert.assertEquals
import org.junit.Test

class NeteaseAndroidClientProfileTest {
    @Test fun deviceSdkAndAegisUseTheSameVersionAndUserAgentAsLoginAndLogs() {
        assertEquals(NeteaseLoginSecurity.OFFICIAL_VERSION_NAME, NeteaseAndroidClientProfile.APP_VERSION)
        assertEquals(NeteaseLoginSecurity.OFFICIAL_VERSION_CODE, NeteaseAndroidClientProfile.VERSION_CODE)
        assertEquals(NeteaseLoginSecurity.OFFICIAL_CHANNEL, NeteaseAndroidClientProfile.CHANNEL)
        assertEquals(NeteaseLoginSecurity.OFFICIAL_ANDROID_USER_AGENT, NeteaseAndroidClientProfile.USER_AGENT_PREFIX)
    }

    @Test fun androidEapiUsesTheCapturedDeviceAndStableClientBuild() {
        val config = NeteaseAndroidClientProfile.eapiConfig("17", "Pixel 10 Pro", "AP4A", "1120x2436")
        assertEquals("android", config["os"])
        assertEquals("17", config["osver"])
        assertEquals("Pixel+10+Pro", config["mobilename"])
        assertEquals("1120x2436", config["resolution"])
        assertEquals("9.5.70", config["appver"])
        assertEquals("9005070", config["versioncode"])
        assertEquals("260818213343", config["buildver"])
        assertEquals(NeteaseAndroidClientProfile.userAgent("17", "Pixel 10 Pro", "AP4A"), config["ua"])
    }
}
