package com.ljyh.mei.data.network

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import com.ljyh.mei.constants.CookieKey
import com.ljyh.mei.constants.NeteaseCsrfKey
import com.ljyh.mei.constants.NeteaseMusicAKey
import com.ljyh.mei.constants.NeteaseRefreshTokenKey
import com.ljyh.mei.constants.NeteaseSessionTypeKey
import com.ljyh.mei.constants.NeteaseUrsAppIdKey
import com.ljyh.mei.constants.NeteaseWebSessionNoticeDismissedKey
import com.ljyh.mei.constants.UserAvatarUrlKey
import com.ljyh.mei.constants.UserIdKey
import com.ljyh.mei.constants.UserNicknameKey

enum class NeteaseSessionType { None, Web, Mobile }

/** Older mobile builds stored URS_APPID before an explicit session type existed. */
fun Preferences.neteaseSessionType(): NeteaseSessionType = when {
    this[CookieKey].isNullOrBlank() -> NeteaseSessionType.None
    this[NeteaseSessionTypeKey] == NeteaseSessionType.Web.name -> NeteaseSessionType.Web
    this[NeteaseSessionTypeKey] == NeteaseSessionType.Mobile.name -> NeteaseSessionType.Mobile
    this[NeteaseSessionTypeKey] == null && !this[NeteaseUrsAppIdKey].isNullOrBlank() -> NeteaseSessionType.Mobile
    else -> NeteaseSessionType.Web
}

fun Preferences.shouldExplainLegacyWebSession(): Boolean =
    neteaseSessionType() == NeteaseSessionType.Web &&
        this[NeteaseSessionTypeKey] == null &&
        this[NeteaseWebSessionNoticeDismissedKey] != true

internal fun Preferences.requireNeteaseMobileSession() {
    check(neteaseSessionType() == NeteaseSessionType.Mobile) {
        "NetEase PC QR authorization requires a mobile account session"
    }
}

internal fun MutablePreferences.setNeteaseWebSession(musicU: String) {
    this[CookieKey] = musicU
    this[NeteaseSessionTypeKey] = NeteaseSessionType.Web.name
    remove(NeteaseCsrfKey)
    remove(NeteaseMusicAKey)
    remove(NeteaseRefreshTokenKey)
    remove(NeteaseUrsAppIdKey)
}

internal fun MutablePreferences.clearNeteaseAccountSession() {
    remove(CookieKey)
    remove(NeteaseSessionTypeKey)
    remove(NeteaseCsrfKey)
    remove(NeteaseMusicAKey)
    remove(NeteaseRefreshTokenKey)
    remove(NeteaseUrsAppIdKey)
    remove(UserIdKey)
    remove(UserNicknameKey)
    remove(UserAvatarUrlKey)
}
