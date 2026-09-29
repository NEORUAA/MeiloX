package com.ljyh.mei.parasite

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Pair
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedInterface.HookHandle
import java.io.Closeable
import java.io.IOException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject

/** Owns host ViewModels and observers without sharing either classloader's AndroidX types. */
internal class TvHostLoginBackend(
    private val loader: ClassLoader,
    private val hostContext: Context,
    private val authorization: HostAuthorizationGuard,
    private val report: (String) -> Unit,
) : HostLoginBackend<Bitmap> {
    private val modelType = loader.loadClass("com.netease.cloudmusic.audio.c.b")
    private val storeType = loader.loadClass("androidx.lifecycle.ViewModelStore")
    private val observerType = loader.loadClass("androidx.lifecycle.Observer")
    private val liveDataType = loader.loadClass("androidx.lifecycle.LiveData")
    private val observe = liveDataType.getMethod("observeForever", observerType)
    private val remove = liveDataType.getMethod("removeObserver", observerType)
    private val hasObservers = liveDataType.getMethod("hasObservers")
    private val put = storeType.getDeclaredMethod("put", String::class.java, loader.loadClass("androidx.lifecycle.ViewModel"))
        .apply { isAccessible = true }
    private val clear = storeType.getMethod("clear")
    private val stop = modelType.getMethod("o0")
    private val start = modelType.getMethod("k0", Boolean::class.javaPrimitiveType, Long::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
    private val logout = loader.loadClass("com.netease.cloudmusic.utils.d1")
        .getMethod("g", Context::class.java, Boolean::class.javaPrimitiveType)
    private val attempts = mutableMapOf<Any, (HostLoginState<Bitmap>) -> Unit>()
    private val owned = Collections.synchronizedMap(WeakHashMap<Any, Boolean>())
    private val authorizations = Collections.synchronizedMap(WeakHashMap<Any, HostAuthorizationGuard.Attempt>())
    private val accountRead = loader.loadClass("com.netease.cloudmusic.audio.c.a").getMethod("c")
    private val profileId = loader.loadClass("com.netease.cloudmusic.meta.Profile").getMethod("getUserId")
    private val profileAnonymous = loader.loadClass("com.netease.cloudmusic.meta.Profile").getMethod("isAnonym")
    private val core = loader.loadClass("com.netease.cloudmusic.core.b")
    private val sessionType = loader.loadClass("com.netease.cloudmusic.r0.a")
    private val session = sessionType.getMethod("c").invoke(null)
    private val userId = sessionType.getMethod("e")
    private val scopedWorkers = ConcurrentHashMap.newKeySet<String>()

    private fun reportWorker(kind: String) {
        if (scopedWorkers.add(kind)) report("login_worker_scoped kind=$kind")
    }

    fun installHooks(module: XposedModule) {
        val status = modelType.getDeclaredMethod("p0", loader.loadClass("com.netease.cloudmusic.audio.c.d"))
        val failureCleanup = modelType.getDeclaredMethod("G0")
        val executeCallable = modelType.getDeclaredMethod("n0", Callable::class.java)
        val profileTask = loader.loadClass("com.netease.cloudmusic.audio.c.b\$h")
        val taskModel = profileTask.getDeclaredField("b").apply { isAccessible = true }
        val hostUnit = loader.loadClass("kotlin.Unit").getField("INSTANCE").get(null)
        val hooks = mutableListOf<HookHandle>()
        try {
            hooks += module.hook(status).intercept { chain ->
                val model = chain.thisObject
                if (owned[model] == false) {
                    false
                } else {
                    val attempt = authorizations[model]
                    val result = if (attempt == null) chain.proceed() else authorization.run(attempt) { chain.proceed() }
                    val event = when ((chain.getArg(0) as? Enum<*>)?.name) {
                        "AUTH_ING", "AUTH_SUCCESS" -> HostLoginStatus.CONFIRMING
                        "TIME_OUT" -> HostLoginStatus.EXPIRED
                        "ERROR" -> HostLoginStatus.ERROR
                        else -> null
                    }
                    if (event != null) attempts[model]?.invoke(HostLoginState(event))
                    result
                }
            }
            hooks += module.hook(failureCleanup).intercept { chain ->
                if (owned.containsKey(chain.thisObject)) {
                    // G0 queues global logout outside ViewModelScope, potentially after a new
                    // account has signed in. Failed module attempts only retire their own scope.
                    report("login_failure_global_cleanup_suppressed")
                    null
                } else chain.proceed()
            }
            hooks += module.hook(executeCallable).intercept { chain ->
                val attempt = authorizations[chain.thisObject]
                if (attempt != null) reportWorker("callable")
                if (attempt == null) chain.proceed()
                else if (!authorization.isActive(attempt)) null
                else try { authorization.run(attempt) { chain.proceed() } } catch (_: IOException) { null }
            }
            hooks += module.hook(profileTask.getDeclaredMethod("invokeSuspend", Any::class.java)).intercept { chain ->
                val attempt = authorizations[taskModel.get(chain.thisObject)]
                if (attempt != null) reportWorker("profile")
                if (attempt == null) chain.proceed()
                else if (!authorization.isActive(attempt)) hostUnit
                else try { authorization.run(attempt) { chain.proceed() } } catch (_: IOException) { hostUnit }
            }
            // Failed account reads enqueue retries outside ViewModelScope.
            listOf("B0", "I0").forEach { name ->
                hooks += module.hook(modelType.getDeclaredMethod(name)).intercept { chain ->
                    if (owned[chain.thisObject] == false) null else chain.proceed()
                }
            }
            val parsers = listOf(
                loader.loadClass("com.netease.cloudmusic.h1.b.d").getMethod("e", JSONObject::class.java),
                loader.loadClass("com.netease.cloudmusic.audio.c.a\$d").getDeclaredMethod("b", JSONObject::class.java),
                loader.loadClass("com.netease.cloudmusic.audio.c.a\$e").getDeclaredMethod("b", JSONObject::class.java),
            )
            parsers.forEach { parser ->
                hooks += module.hook(parser).intercept { chain -> authorization.mutate { chain.proceed() } }
            }
            hooks += module.hook(accountRead).intercept { chain ->
                authorization.verifyProfile({ chain.proceed() }, ::isOfficialProfile)
            }
            authorization.setRecovery {
                val accepted = isOfficialProfile(invoke(accountRead, null))
                report("login_session_recovery accepted=$accepted")
                accepted
            }
            report("login_authorization_hooks_bound")
        } catch (error: Throwable) {
            hooks.asReversed().forEach { runCatching { it.unhook() } }
            throw error
        }
    }

    override fun open(emit: (HostLoginState<Bitmap>) -> Unit): Closeable {
        requireMainThread()
        check(attempts.isEmpty()) { "Official login is already active" }
        val model = modelType.getConstructor().newInstance()
        val store = storeType.getConstructor().newInstance()
        invoke(put, store, "meilox-login", model)
        val observers = mutableListOf<kotlin.Pair<Any, Any>>()
        val authorizationAttempt = authorization.begin()
        authorizations[model] = authorizationAttempt
        var closed = false
        var qr: Bitmap? = null
        var lastStatus: HostLoginStatus? = null
        val watchdog = Handler(Looper.getMainLooper())
        val handle = Closeable {
            requireMainThread()
            if (!closed) {
                closed = true
                owned[model] = false
                attempts.remove(model)
                watchdog.removeCallbacksAndMessages(null)
                var clean = true
                observers.forEach { (live, observer) ->
                    if (runCatching { invoke(remove, live, observer) }.isFailure) clean = false
                }
                if (runCatching { invoke(clear, store) }.isFailure) clean = false
                if (runCatching { invoke(stop, model) }.isFailure) clean = false
                val remaining = observers.count { (live, _) -> runCatching { invoke(hasObservers, live) }.getOrNull() != false }
                observers.clear()
                qr = null
                authorization.retire(authorizationAttempt)
                report("login_closed clean=$clean observers_remaining=$remaining active=${attempts.size}")
            }
        }
        fun send(state: HostLoginState<Bitmap>) {
            if (!closed) {
                val retainedQr = if (state.status in setOf(HostLoginStatus.WAITING, HostLoginStatus.CONFIRMING)) qr else null
                if (lastStatus != state.status) report("login_state status=${state.status}")
                lastStatus = state.status
                emit(state.copy(qr = retainedQr))
            }
        }
        fun observe(getter: String, receive: (Any?) -> Unit) {
            val live = requireNotNull(invoke(modelType.getMethod(getter), model))
            val proxy = Proxy.newProxyInstance(loader, arrayOf(observerType)) { self, method, args ->
                when (method.name) {
                    "onChanged" -> { if (!closed) receive(args?.get(0)); null }
                    "hashCode" -> System.identityHashCode(self)
                    "equals" -> self === args?.get(0)
                    "toString" -> "MeiloXLoginObserver"
                    else -> null
                }
            }
            observers += live to proxy
            invoke(observe, live, proxy)
        }
        try {
            owned[model] = true
            attempts[model] = ::send
            observe("x0") { value ->
                if (value is Bitmap) {
                    watchdog.removeCallbacksAndMessages(null)
                    qr = value
                    send(HostLoginState(HostLoginStatus.WAITING))
                }
            }
            observe("s0") { if (it == true) send(HostLoginState(HostLoginStatus.ERROR)) }
            observe("w0") { value ->
                if (value is Pair<*, *>) send(HostLoginState(
                    if (value.first == 200 && authorization.complete(authorizationAttempt)) HostLoginStatus.SUCCESS else HostLoginStatus.ERROR,
                ))
            }
            report("login_opened active=${attempts.size}")
            watchdog.postDelayed({ send(HostLoginState(HostLoginStatus.ERROR)) }, 30_000)
            invoke(start, model, false, 0L, false)
            return handle
        } catch (_: Exception) {
            handle.close()
            throw IOException("Official login could not start")
        }
    }

    override fun logout() {
        requireMainThread()
        check(attempts.isEmpty()) { "Close login attempts before logout" }
        val attempt = authorization.begin()
        try { authorization.run(attempt) { invoke(logout, null, hostContext, true) } }
        finally { authorization.retire(attempt) }
    }

    private fun requireMainThread() = check(Looper.myLooper() == Looper.getMainLooper())

    private fun isOfficialProfile(result: Any?): Boolean {
        if (result !is Pair<*, *> || result.first != 200 || result.second == null) return false
        val id = invoke(profileId, result.second) as? Long ?: return false
        val anonymous = invoke(core.getMethod("c"), null) == true
        val authenticated = invoke(core.getMethod("d"), null) == true
        return id > 0 && invoke(userId, session) == id &&
            invoke(profileAnonymous, result.second) == anonymous && (anonymous || authenticated)
    }

    private fun invoke(method: Method, receiver: Any?, vararg arguments: Any?): Any? = try {
        method.invoke(receiver, *arguments)
    } catch (_: Exception) {
        // Host exception messages may include authorization URLs or credentials.
        throw IOException("Official login operation failed")
    }
}
