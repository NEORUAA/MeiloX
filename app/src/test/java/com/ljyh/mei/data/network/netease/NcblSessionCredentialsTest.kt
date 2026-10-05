package com.ljyh.mei.data.network.netease

import androidx.datastore.preferences.core.mutablePreferencesOf
import com.ljyh.mei.constants.CookieKey
import com.ljyh.mei.constants.DeviceIdKey
import com.ljyh.mei.constants.NeteaseCsrfKey
import com.ljyh.mei.constants.NeteaseMusicAKey
import com.ljyh.mei.constants.NeteaseSessionTypeKey
import com.ljyh.mei.constants.NeteaseUrsAppIdKey
import com.ljyh.mei.data.network.NeteaseSessionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class NcblSessionCredentialsTest {
    @Test fun mobileLogsReadCredentialsFromTheLoginSession() {
        val credentials = mobile().ncblCredentials()!!
        assertEquals("mobile-cookie", credentials.musicU)
        assertEquals("login-app-id", credentials.ursAppId)
        assertEquals("login-music-a", credentials.musicA)
        assertEquals("login-csrf", credentials.csrf)
        assertFalse(credentials.toString().contains("mobile-cookie"))
    }

    @Test fun webSessionNeverBorrowsStaleMobileMetadata() {
        val preferences = mobile().apply { this[NeteaseSessionTypeKey] = NeteaseSessionType.Web.name }
        assertNull(preferences.ncblCredentials())
    }

    @Test fun legacyMobileSessionCanKeepItsEstablishedDeviceIdentity() {
        val preferences = mobile().apply { remove(NeteaseSessionTypeKey) }
        assertNotNull(preferences.ncblCredentials())
    }

    @Test fun missingDeviceIdOrUrsAppIdNeverInventsANewIdentity() {
        val missingDevice = mobile().apply { remove(DeviceIdKey) }
        assertNull(missingDevice.ncblCredentials())
        assertFalse(missingDevice.contains(DeviceIdKey))
        assertNull(mobile().apply { remove(NeteaseUrsAppIdKey) }.ncblCredentials())
    }

    @Test fun signedOutSessionCannotCreateClientLogs() {
        assertNull(mobile().apply { remove(CookieKey) }.ncblCredentials())
    }

    private fun mobile() = mutablePreferencesOf(
        CookieKey to "mobile-cookie", DeviceIdKey to "login-device",
        NeteaseSessionTypeKey to NeteaseSessionType.Mobile.name,
        NeteaseUrsAppIdKey to "login-app-id", NeteaseMusicAKey to "login-music-a",
        NeteaseCsrfKey to "login-csrf",
    )
}
