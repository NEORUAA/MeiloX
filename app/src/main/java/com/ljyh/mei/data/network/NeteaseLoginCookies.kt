package com.ljyh.mei.data.network

import okhttp3.Cookie
import okhttp3.Response

internal data class NeteaseLoginCookies(
    val musicU: String,
    val csrf: String,
    val musicA: String?,
    val refreshToken: String?,
)

/** MUSIC_U authenticates the session. x-refresh-token is an optional extension. */
internal fun readNeteaseLoginCookies(response: Response): NeteaseLoginCookies {
    val cookies = Cookie.parseAll(response.request.url, response.headers)
    fun cookie(name: String): String? = cookies
        .lastOrNull { it.name.equals(name, ignoreCase = true) }
        ?.value?.takeIf(String::isNotBlank)

    val musicU = cookie("MUSIC_U")
    val csrf = cookie("__csrf") ?: response.request.header("Cookie")
        ?.split(';')
        ?.map(String::trim)
        ?.firstOrNull { it.substringBefore('=').equals("__csrf", ignoreCase = true) }
        ?.substringAfter('=', "")
        ?.takeIf(String::isNotBlank)
    check(musicU != null && csrf != null) {
        "NetEase mobile login returned an incomplete account session"
    }
    return NeteaseLoginCookies(
        musicU = musicU,
        csrf = csrf,
        musicA = cookie("MUSIC_A"),
        refreshToken = response.header("x-refresh-token")?.takeIf(String::isNotBlank),
    )
}
