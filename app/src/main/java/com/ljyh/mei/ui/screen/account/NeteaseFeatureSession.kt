package com.ljyh.mei.ui.screen.account

import androidx.datastore.preferences.core.Preferences
import com.ljyh.mei.constants.*
import com.ljyh.mei.data.network.NeteaseAndroidClientProfile

/** Values are read exclusively from this app's current login, never from capture files. */
internal fun featureSessionCookies(preferences: Preferences): Map<String, String> = buildMap {
    fun putSaved(name: String, key: Preferences.Key<String>) {
        preferences[key]?.takeIf { it.isNotBlank() && ';' !in it && '\r' !in it && '\n' !in it }
            ?.let { put(name, it) }
    }
    putSaved("MUSIC_U", CookieKey)
    putSaved("MUSIC_A", NeteaseMusicAKey)
    putSaved("__csrf", NeteaseCsrfKey)
    putSaved("URS_APPID", NeteaseUrsAppIdKey)
    putSaved("deviceId", DeviceIdKey)
    putSaved("sDeviceId", SDeviceIdKey)
    put("os", "android")
    put("appver", NeteaseAndroidClientProfile.APP_VERSION)
}
