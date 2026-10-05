package com.ljyh.mei.data.network

import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NeteaseLoginCookiesTest {
    private fun response(vararg headers: Pair<String, String>, cookie: String? = null): Response =
        Response.Builder()
            .request(Request.Builder().url("https://interface.music.163.com/eapi/login/cellphone")
                .apply { cookie?.let { header("Cookie", it) } }.build())
            .protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .apply { headers.forEach { (name, value) -> addHeader(name, value) } }.build()

    @Test
    fun acceptsAuthenticatedSessionWithoutRefreshToken() {
        val session = readNeteaseLoginCookies(response(
            "Set-Cookie" to "MUSIC_U=account-session; Path=/",
            "Set-Cookie" to "__csrf=csrf-session; Path=/",
        ))
        assertEquals("account-session", session.musicU)
        assertEquals("csrf-session", session.csrf)
        assertNull(session.refreshToken)
    }

    @Test
    fun keepsOptionalSessionFieldsAndRequestCsrf() {
        val session = readNeteaseLoginCookies(response(
            "Set-Cookie" to "MUSIC_U=new-session; Path=/",
            "Set-Cookie" to "MUSIC_A=anonymous-session; Path=/",
            "X-Refresh-Token" to "refresh-session",
            cookie = "__csrf=request-csrf; MUSIC_U=old-session",
        ))
        assertEquals("new-session", session.musicU)
        assertEquals("request-csrf", session.csrf)
        assertEquals("anonymous-session", session.musicA)
        assertEquals("refresh-session", session.refreshToken)
    }

    @Test(expected = IllegalStateException::class)
    fun doesNotMistakeOldRequestCookieForSuccessfulLogin() {
        readNeteaseLoginCookies(response(cookie = "MUSIC_U=old-session; __csrf=old-csrf"))
    }

    @Test(expected = IllegalStateException::class)
    fun rejectsEmptyMusicU() {
        readNeteaseLoginCookies(response("Set-Cookie" to "MUSIC_U=; Path=/", cookie = "__csrf=csrf"))
    }
}
