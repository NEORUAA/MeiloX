package com.ljyh.mei.ui.screen.account

import androidx.datastore.preferences.core.preferencesOf
import com.ljyh.mei.constants.CookieKey
import com.ljyh.mei.constants.NeteaseCsrfKey
import org.junit.Assert.*
import org.junit.Test

class NeteaseFeatureSessionTest {
    @Test fun webSessionOnlyUsesTheCurrentAppsStoredCredentials() {
        val cookies = featureSessionCookies(preferencesOf(CookieKey to "example-session", NeteaseCsrfKey to "example-csrf"))
        assertEquals("example-session", cookies["MUSIC_U"])
        assertEquals("example-csrf", cookies["__csrf"])
        assertFalse(cookies.containsKey("MUSIC_A"))
        assertFalse(featureSessionCookies(preferencesOf()).containsKey("MUSIC_U"))
    }

    @Test fun malformedCookieValuesAndForeignBridgeTargetsAreRejected() {
        assertFalse(featureSessionCookies(preferencesOf(CookieKey to "bad; injected=value")).containsKey("MUSIC_U"))
        assertTrue(isNeteaseFeatureUrl("https://st.music.163.com/g/device"))
        assertFalse(isNeteaseFeatureUrl("https://music.163.com.example.test/"))
        assertFalse(isNeteaseFeatureUrl("http://music.163.com/"))
    }
}
