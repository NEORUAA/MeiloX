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
import java.util.WeakHashMap

/** Owns host ViewModels and observers without sharing either classloader's AndroidX types. */
internal class TvHostLoginBackend(
    private val loader: ClassLoader,
    private val hostContext: Context,
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
    private val owned = WeakHashMap<Any, Boolean>()

    fun installHooks(module: XposedModule) {
        val status = modelType.getDeclaredMethod("p0", loader.loadClass("com.netease.cloudmusic.audio.c.d"))
        val failureCleanup = modelType.getDeclaredMethod("G0")
        val hooks = mutableListOf<HookHandle>()
        try {
            hooks += module.hook(status).intercept { chain ->
                val model = chain.thisObject
                if (owned[model] == false) {
                    false
                } else {
                    val result = chain.proceed()
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
                    if (value.first == 200) HostLoginStatus.SUCCESS else HostLoginStatus.ERROR,
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
        invoke(logout, null, hostContext, true)
    }

    private fun requireMainThread() = check(Looper.myLooper() == Looper.getMainLooper())

    private fun invoke(method: Method, receiver: Any?, vararg arguments: Any?): Any? = try {
        method.invoke(receiver, *arguments)
    } catch (_: Exception) {
        // Host exception messages may include authorization URLs or credentials.
        throw IOException("Official login operation failed")
    }
}
