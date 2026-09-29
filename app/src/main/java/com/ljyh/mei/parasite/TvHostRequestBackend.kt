package com.ljyh.mei.parasite

import android.os.Looper
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedInterface.HookHandle
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/** Pinned TV 1.1.80 adapter. No host model or Kotlin type crosses this boundary. */
internal class TvHostRequestBackend(
    private val loader: ClassLoader,
    private val report: (String) -> Unit = {},
) : HostRequestBackend {
    private val core = loader.loadClass("com.netease.cloudmusic.core.b")
    private val sessionType = loader.loadClass("com.netease.cloudmusic.r0.a")
    private val session: Any
    private val userId = sessionType.getMethod("e")
    private val authenticated = core.getMethod("d")
    private val anonymous = core.getMethod("c")
    private val requestType = loader.loadClass("com.netease.cloudmusic.network.v.e.a")
    private val requestBase = loader.loadClass("com.netease.cloudmusic.network.v.e.f")
    private val factory = loader.loadClass("com.netease.cloudmusic.network.f").getMethod("c", String::class.java, Map::class.java)
    private val execute = requestType.getMethod("k")
    private val cancel = requestBase.getMethod("c")
    private val playlistToken = loader.loadClass("com.netease.cloudmusic.h1.y.a").getMethod("a")
    private val cancellations = ConcurrentHashMap<Any, AtomicBoolean>()

    init {
        requireNotNull(loader.loadClass("com.netease.cloudmusic.NeteaseMusicApplication").getMethod("getInstance").invoke(null))
        session = requireNotNull(sessionType.getMethod("c").invoke(null))
    }

    fun installHooks(module: XposedModule, sessions: HostSessionBridge, authorization: HostAuthorizationGuard) {
        val prepare = requestBase.getMethod("f")
        val profileType = loader.loadClass("com.netease.cloudmusic.meta.Profile")
        val profileUpdate = sessionType.getMethod("p", profileType)
        val profileId = profileType.getMethod("getUserId")
        val cookieType = loader.loadClass("okhttp3.Cookie")
        val cookieName = cookieType.getMethod("name")
        val cookieStore = loader.loadClass("com.netease.cloudmusic.network.cookie.store.AbsCookieStore")
        val saveCookies = cookieStore.getMethod("saveCookies", List::class.java)
        val removeCookie = cookieStore.getMethod("removeCookie", cookieType)
        val removeAll = cookieStore.getMethod("removeAllCookie")
        fun isSessionCookie(cookie: Any?): Boolean = cookie != null &&
            invoke(cookieName, cookie) in setOf("MUSIC_U", "MUSIC_A")
        val hooks = mutableListOf<HookHandle>()
        try {
            hooks += module.hook(prepare).intercept { chain ->
                val result = chain.proceed()
                val request = chain.thisObject
                if (request != null && cancellations[request]?.get() == true) invoke(cancel, request)
                result
            }
            hooks += module.hook(profileUpdate).intercept { chain ->
                val profile = chain.getArg(0)
                val changed = profile == null || invoke(profileId, profile) != invoke(userId, session)
                report("session_profile_update account_changed=$changed")
                // The host also persists ordinary profile/privilege refreshes here.
                authorization.mutate {
                    if (changed) sessions.beginTransition().use { chain.proceed() } else chain.proceed()
                }
            }
            hooks += module.hook(saveCookies).intercept { chain ->
                val changed = (chain.getArg(0) as? List<*>)?.any(::isSessionCookie) == true
                // Observe names only; cookie values never enter the module session state.
                if (changed) authorization.mutate(cookie = true) { sessions.beginTransition().use { chain.proceed() } }
                else chain.proceed()
            }
            hooks += module.hook(removeCookie).intercept { chain ->
                if (isSessionCookie(chain.getArg(0))) authorization.mutate(cookie = true) { sessions.beginTransition().use { chain.proceed() } }
                else chain.proceed()
            }
            hooks += module.hook(removeAll).intercept { chain ->
                authorization.mutate(cookie = true) { sessions.beginTransition().use { chain.proceed() } }
            }
        } catch (error: Throwable) {
            hooks.asReversed().forEach { runCatching { it.unhook() } }
            throw error
        }
    }

    override fun sessionIdentity(): HostSessionIdentity {
        val id = invoke(userId, session) as Long
        val anon = invoke(anonymous, null) == true
        val auth = invoke(authenticated, null) == true && !anon && id > 0
        return HostSessionIdentity(if (auth) id else 0, auth, anon)
    }

    override fun open(path: String, parameters: Map<String, String>): HostPendingRequest {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Host requests must run off the main thread" }
        val businessParameters = tvPlaylistRequestParameters(path, parameters) {
            invoke(playlistToken, null) as? String ?: throw IOException("Official playlist token unavailable")
        }
        val request = invoke(factory, null, path, businessParameters) ?: throw IOException("Official request creation failed")
        invoke(requestBase.getMethod("d", Int::class.javaPrimitiveType), request, 10_000)
        invoke(requestBase.getMethod("i0", Int::class.javaPrimitiveType), request, 15_000)
        val canceled = AtomicBoolean()
        cancellations[request] = canceled
        return object : HostPendingRequest {
            override fun execute(): String = (invoke(execute, request) as? JSONObject)?.toString()
                ?: throw IOException("Official request returned no JSON")

            override fun cancel() {
                canceled.set(true)
                invoke(cancel, request)
            }

            override fun close() { cancellations.remove(request) }
        }
    }

    private fun invoke(method: Method, receiver: Any?, vararg arguments: Any?): Any? = try {
        method.invoke(receiver, *arguments)
    } catch (error: Exception) {
        val cause = if (error is InvocationTargetException) error.targetException else error
        // Do not retain a host exception message/cause that may contain request credentials.
        throw IOException("Official transport failed: ${cause.javaClass.simpleName}")
    }
}

internal fun tvPlaylistRequestParameters(
    path: String,
    parameters: Map<String, String>,
    token: () -> String,
): Map<String, String> {
    val needsToken = path in setOf("multi/terminal/playlist/subscribe", "v1/playlist/manipulate/tracks", "playlist/create")
    if (!needsToken && path != "multi/terminal/playlist/unsubscribe") return parameters
    require(parameters.keys.none { it.equals("checkToken", ignoreCase = true) }) { "Playlist security parameters belong to the host" }
    // The official generator may return empty when its security service is disabled.
    return if (needsToken) parameters + ("checkToken" to token()) else parameters
}
