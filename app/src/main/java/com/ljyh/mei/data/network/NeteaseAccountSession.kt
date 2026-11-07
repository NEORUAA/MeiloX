package com.ljyh.mei.data.network

import androidx.datastore.preferences.core.Preferences
import com.ljyh.mei.constants.CookieKey
import com.ljyh.mei.constants.NeteaseSessionTypeKey
import com.ljyh.mei.constants.NeteaseUrsAppIdKey
import com.ljyh.mei.constants.NeteaseWebSessionNoticeDismissedKey

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
