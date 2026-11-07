package com.ljyh.mei.data.network

import org.junit.Assert.assertEquals
import org.junit.Test

class NeteaseAndroidClientProfileTest {
    @Test fun nativeDeviceAndSecuritySdksUseThePinnedVendorIdentity() {
        assertEquals(NeteaseLoginSecurity.OFFICIAL_VERSION_NAME, NeteaseAndroidClientProfile.APP_VERSION)
        assertEquals(NeteaseLoginSecurity.OFFICIAL_VERSION_CODE, NeteaseAndroidClientProfile.VERSION_CODE)
        assertEquals(NeteaseLoginSecurity.OFFICIAL_CHANNEL, NeteaseAndroidClientProfile.CHANNEL)
        assertEquals(NeteaseLoginSecurity.OFFICIAL_ANDROID_USER_AGENT, NeteaseAndroidClientProfile.USER_AGENT_PREFIX)
    }
}
