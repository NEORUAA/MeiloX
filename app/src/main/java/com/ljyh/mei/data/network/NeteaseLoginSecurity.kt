package com.ljyh.mei.data.network

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.content.pm.VersionedPackage
import android.content.res.Resources
import android.os.SystemClock
import android.test.mock.MockPackageManager
import android.util.Base64
import android.util.Log
import android.util.Pair
import androidx.datastore.preferences.core.edit
import com.ljyh.mei.constants.NeteaseNmcidKey
import com.ljyh.mei.constants.NeteaseNmdiFinalKey
import com.ljyh.mei.constants.NeteaseNmdiKey
import com.ljyh.mei.constants.NeteaseNmtidKey
import com.ljyh.mei.utils.dataStore
import com.netease.cloudmusic.crypto.caesarson.CaesarsonCryptor
import dalvik.system.InMemoryDexClassLoader
import dagger.hilt.android.qualifiers.ApplicationContext
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.net.InetAddress
import java.net.URLEncoder
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

@Singleton
class NeteaseLoginSecurity @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:Named("NeteaseSecurityClient") private val client: OkHttpClient,
) {
    init {
        activeInstance = this
    }

    private val sdkContext: Context by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        OfficialNeteaseSecurityContext(context)
    }

    @Volatile
    private var cachedYdDeviceToken: String? = null

    private val watchManNetworkStarted = AtomicInteger()
    private val watchManNetworkCompleted = AtomicInteger()
    private val watchManNetworkFailed = AtomicInteger()
    private val serverTrackId = AtomicReference("")
    private val preparedPcQrCheckToken = AtomicReference("")
    private val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var initializedWatchManSdk: WatchManSdk? = null

    private val securityClassLoader: ClassLoader by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        loadSecurityClassLoader()
    }

    private val deviceSdk: DeviceSdk by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        loadDeviceSdk()
    }

    private val watchManSdk: WatchManSdk by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        loadWatchManSdk().also { initializedWatchManSdk = it }
    }

    suspend fun freshCheckToken(): String = withContext(Dispatchers.IO) {
        val checkpoint = watchManNetworkSnapshot()
        val token = watchManSdk.getToken()
        awaitWatchManNetworkIdle(checkpoint)
        token
    }

    suspend fun pcQrCheckToken(): String = withContext(Dispatchers.IO) {
        preparedPcQrCheckToken.get().takeIf(String::isNotBlank) ?: freshCheckToken()
    }

    suspend fun prepareForPcQrLogin(): String = withContext(Dispatchers.IO) {
        restoreServerTrackId()
        val ydDeviceToken = ydDeviceToken()
        val checkpoint = watchManNetworkSnapshot()
        val sdk = watchManSdk
        val checkToken = sdk.getToken()
        awaitWatchManNetworkIdle(checkpoint)
        preparedPcQrCheckToken.set(checkToken)
        val watchManDeviceId = awaitWatchManDeviceId()
        securityCookies()
        Log.i(
            TAG,
            "Security prewarm ready ydDeviceToken=${ydDeviceToken.length} " +
                "ydid=${watchManDeviceId.length} checkToken=${checkToken.length}",
        )
        ydDeviceToken
    }

    suspend fun ydDeviceToken(): String = withContext(Dispatchers.IO) {
        cachedYdDeviceToken?.takeIf(String::isNotBlank) ?: synchronized(this@NeteaseLoginSecurity) {
            cachedYdDeviceToken?.takeIf(String::isNotBlank) ?: deviceSdk.getToken().also {
                cachedYdDeviceToken = it
            }
        }
    }

    suspend fun securityCookies(): NeteaseSecurityCookies = withContext(Dispatchers.IO) {
        val preferences = context.dataStore.data.first()
        val watchManDeviceId = awaitWatchManDeviceId()
        val nmcid = preferences[NeteaseNmcidKey].orEmpty().ifBlank(::createNmcid)
        val existingNmdi = preferences[NeteaseNmdiKey].orEmpty()
        val nmdiWasFinal = preferences[NeteaseNmdiFinalKey] == true
        val nmdiIsFinal = nmdiWasFinal || watchManDeviceId.isNotBlank()
        val nmdi = when {
            watchManDeviceId.isNotBlank() && !nmdiWasFinal -> createNmdi(watchManDeviceId)
            existingNmdi.isNotBlank() -> existingNmdi
            else -> createNmdi(watchManDeviceId)
        }
        val nmtid = serverTrackId.get().ifBlank {
            preferences[NeteaseNmtidKey].orEmpty().also { storedTrackId ->
                if (storedTrackId.isNotBlank()) updateServerTrackId(storedTrackId, persist = false)
            }
        }
        context.dataStore.edit { values ->
            values[NeteaseNmcidKey] = nmcid
            values[NeteaseNmdiKey] = nmdi
            values[NeteaseNmdiFinalKey] = nmdiIsFinal
        }
        Log.i(
            TAG,
            "Security cookies ready nmcid=${nmcid.length} nmdi=${nmdi.length} " +
                "nmtid=${nmtid.length} ydid=${watchManDeviceId.length} final=$nmdiIsFinal",
        )
        NeteaseSecurityCookies(nmcid = nmcid, nmdi = nmdi, nmtid = nmtid)
    }

    private suspend fun restoreServerTrackId() {
        val storedTrackId = context.dataStore.data.first()[NeteaseNmtidKey].orEmpty()
        if (storedTrackId.isNotBlank()) updateServerTrackId(storedTrackId, persist = false)
    }

    private fun updateServerTrackId(trackId: String, persist: Boolean) {
        if (trackId.isBlank() || serverTrackId.getAndSet(trackId) == trackId) return
        initializedWatchManSdk?.setCustomTrackId(trackId)
        Log.i(TAG, "Security track ID updated length=${trackId.length}")
        if (persist) {
            persistenceScope.launch {
                if (serverTrackId.get() == trackId) {
                    context.dataStore.edit { it[NeteaseNmtidKey] = trackId }
                }
            }
        }
    }

    private suspend fun awaitWatchManDeviceId(): String {
        val deviceId = withTimeoutOrNull(WATCHMAN_NETWORK_TIMEOUT_MS) {
            var current = watchManSdk.getDeviceId()
            while (current.isBlank()) {
                delay(WATCHMAN_NETWORK_POLL_INTERVAL_MS)
                current = watchManSdk.getDeviceId()
            }
            current
        }
        check(!deviceId.isNullOrBlank()) { "NetEase WatchMan device ID synchronization timed out" }
        return deviceId
    }

    private suspend fun awaitWatchManNetworkIdle(checkpoint: WatchManNetworkSnapshot) {
        val settled = withTimeoutOrNull(WATCHMAN_NETWORK_TIMEOUT_MS) {
            var current = watchManNetworkSnapshot()
            var quietSince = SystemClock.elapsedRealtime()
            while (
                current.completed < current.started ||
                SystemClock.elapsedRealtime() - quietSince < WATCHMAN_NETWORK_QUIET_MS
            ) {
                delay(WATCHMAN_NETWORK_POLL_INTERVAL_MS)
                val next = watchManNetworkSnapshot()
                val now = SystemClock.elapsedRealtime()
                if (next != current) {
                    current = next
                    quietSince = now
                }
            }
            current
        }
        check(settled != null) { "NetEase WatchMan network synchronization timed out" }
        check(settled.failed == checkpoint.failed) {
            "NetEase WatchMan network synchronization failed"
        }
        Log.i(
            TAG,
            "WatchMan network ready started=${settled.started - checkpoint.started} " +
                "completed=${settled.completed - checkpoint.completed}",
        )
    }

    private fun watchManNetworkSnapshot() = WatchManNetworkSnapshot(
        started = watchManNetworkStarted.get(),
        completed = watchManNetworkCompleted.get(),
        failed = watchManNetworkFailed.get(),
    )

    private fun createNmcid(): String = buildString {
        repeat(NMCID_RANDOM_LENGTH) { append(('a'..'z').random()) }
        append('.')
        append(System.currentTimeMillis())
        append(".01.4")
    }

    private fun createNmdi(watchManDeviceId: String): String {
        val deviceInfo = org.json.JSONObject().apply {
            watchManDeviceId.takeIf(String::isNotBlank)?.let { put("ydid", it) }
        }.toString()
        CaesarsonCryptor.initWithConfig(CAESARSON_CONFIG)
        return URLEncoder.encode(
            CaesarsonCryptor.encrypt(deviceInfo),
            Charsets.UTF_8.name(),
        )
    }

    private fun loadSecurityClassLoader(): ClassLoader {
        return getOrCreateSecurityClassLoader(context)
    }

    private fun loadDeviceSdk(): DeviceSdk = try {
        val deviceClass = Class.forName(DEVICE_CLASS_NAME, true, securityClassLoader)
        val device = deviceClass.getMethod("get").invoke(null)
            ?: error("NetEase device SDK instance is unavailable")
        val initCode = invoke(
            deviceClass.getMethod("init", Context::class.java),
            device,
            sdkContext,
        ) as? Int
            ?: error("NetEase device SDK returned an invalid initialization result")
        check(initCode == SDK_SUCCESS) { "NetEase device SDK initialization failed ($initCode)" }
        Log.i(TAG, "Device SDK initialized code=$initCode")
        DeviceSdk(
            instance = device,
            getTokenMethod = deviceClass.getMethod("getToken", String::class.java),
        )
    } catch (error: LinkageError) {
        Log.e(TAG, "Device SDK could not load", error)
        throw NeteaseLoginSecurityException("NetEase device security runtime is unavailable", error)
    } catch (error: Exception) {
        Log.e(TAG, "Device SDK initialization failed", error)
        throw NeteaseLoginSecurityException("NetEase device security initialization failed", error)
    }

    private fun loadWatchManSdk(): WatchManSdk = try {
        val watchManClass = Class.forName(WATCHMAN_CLASS_NAME, true, securityClassLoader)
        val confClass = Class.forName(WATCHMAN_CONF_CLASS_NAME, true, securityClassLoader)
        val initCallbackClass = Class.forName(INIT_CALLBACK_CLASS_NAME, true, securityClassLoader)
        val tokenCallbackClass = Class.forName(TOKEN_CALLBACK_CLASS_NAME, true, securityClassLoader)
        val netClientClass = Class.forName(NET_CLIENT_CLASS_NAME, true, securityClassLoader)
        val conf = confClass.getConstructor().newInstance()

        invoke(confClass.getMethod("setCollectApk", java.lang.Boolean.TYPE), conf, false)
        invoke(confClass.getMethod("setCollectSensor", java.lang.Boolean.TYPE), conf, false)
        invoke(confClass.getMethod("setChannel", String::class.java), conf, OFFICIAL_CHANNEL)
        invoke(
            confClass.getMethod("setCustomTrackId", String::class.java),
            conf,
            serverTrackId.get(),
        )
        invoke(confClass.getMethod("setOnCoroutines", java.lang.Boolean.TYPE), conf, true)
        invoke(
            confClass.getMethod("setAbstractNetClient", netClientClass),
            conf,
            createWatchManNetClient(netClientClass),
        )

        val initCode = AtomicInteger(Int.MIN_VALUE)
        val initMessage = AtomicReference("")
        val initLatch = CountDownLatch(1)
        val initCallback = Proxy.newProxyInstance(
            securityClassLoader,
            arrayOf(initCallbackClass),
        ) { proxy, method, args ->
            when (method.name) {
                "onResult" -> {
                    initCode.set(args?.getOrNull(0) as? Int ?: Int.MIN_VALUE)
                    initMessage.set(args?.getOrNull(1) as? String ?: "")
                    initLatch.countDown()
                    null
                }

                else -> invokeProxyObjectMethod(proxy, method, args)
            }
        }
        invoke(
            watchManClass.getMethod(
                "init",
                Context::class.java,
                String::class.java,
                confClass,
                initCallbackClass,
            ),
            null,
            sdkContext,
            WATCHMAN_PRODUCT_ID,
            conf,
            initCallback,
        )
        check(initLatch.await(SDK_CALLBACK_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            "NetEase WatchMan initialization timed out"
        }
        check(initCode.get() == SDK_SUCCESS) {
            "NetEase WatchMan initialization failed (${initCode.get()}): ${initMessage.get()}"
        }
        Log.i(TAG, "WatchMan initialized code=${initCode.get()}")
        WatchManSdk(
            getTokenMethod = watchManClass.getMethod(
                "getToken",
                Integer.TYPE,
                tokenCallbackClass,
            ),
            tokenCallbackClass = tokenCallbackClass,
            classLoader = securityClassLoader,
            getDeviceIdMethod = watchManClass.getMethod("getDeviceId"),
            setCustomTrackIdMethod = watchManClass.getMethod(
                "setCustomTrackId",
                String::class.java,
            ),
        )
    } catch (error: LinkageError) {
        Log.e(TAG, "WatchMan SDK could not load", error)
        throw NeteaseLoginSecurityException("NetEase WatchMan security runtime is unavailable", error)
    } catch (error: Exception) {
        Log.e(TAG, "WatchMan SDK initialization failed", error)
        throw NeteaseLoginSecurityException("NetEase WatchMan security initialization failed", error)
    }

    private fun createWatchManNetClient(netClientClass: Class<*>): Any = Proxy.newProxyInstance(
        securityClassLoader,
        arrayOf(netClientClass),
    ) { proxy, method, args ->
        when (method.name) {
            "sendGet" -> executeWatchManRequest(
                url = args?.getOrNull(0) as? String,
                content = null,
                timeoutMs = args?.getOrNull(1) as? Int ?: DEFAULT_NETWORK_TIMEOUT_MS,
            )

            "sendPost" -> executeWatchManRequest(
                url = args?.getOrNull(0) as? String,
                content = args?.getOrNull(1) as? String ?: "",
                timeoutMs = args?.getOrNull(2) as? Int ?: DEFAULT_NETWORK_TIMEOUT_MS,
            )

            else -> invokeProxyObjectMethod(proxy, method, args)
        }
    }

    private fun executeWatchManRequest(
        url: String?,
        content: String?,
        timeoutMs: Int,
    ): Pair<Int, String> {
        var tracked = false
        var succeeded = false
        return try {
            check(!url.isNullOrBlank()) { "WatchMan request URL is empty" }
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", OFFICIAL_ANDROID_USER_AGENT)
                .apply {
                    if (content == null) {
                        get()
                    } else {
                        post(content.toRequestBody(FORM_MEDIA_TYPE))
                    }
                }
                .build()
            watchManNetworkStarted.incrementAndGet()
            tracked = true
            Log.i(
                TAG,
                "WatchMan network request host=${request.url.host} " +
                    "path=${request.url.encodedPath} timeoutMs=$timeoutMs",
            )
            val phaseTimeoutMs = timeoutMs.coerceAtLeast(1).toLong()
            val requestClient = client.newBuilder()
                .connectTimeout(phaseTimeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(phaseTimeoutMs, TimeUnit.MILLISECONDS)
                .writeTimeout(phaseTimeoutMs, TimeUnit.MILLISECONDS)
                .build()
            requestClient.newCall(request).execute().use { response ->
                val responseBody = response.body.string()
                Log.i(
                    TAG,
                    "WatchMan network response host=${request.url.host} " +
                        "path=${request.url.encodedPath} code=${response.code}",
                )
                succeeded = response.code == SDK_SUCCESS
                Pair(response.code, responseBody)
            }
        } catch (error: Exception) {
            Log.e(TAG, "WatchMan network request failed", error)
            Pair(SDK_NETWORK_ERROR, "")
        } finally {
            if (tracked) {
                if (!succeeded) watchManNetworkFailed.incrementAndGet()
                watchManNetworkCompleted.incrementAndGet()
            }
        }
    }

    private data class DeviceSdk(
        val instance: Any,
        val getTokenMethod: Method,
    ) {
        fun getToken(): String = try {
            val result = invoke(getTokenMethod, instance, DEVICE_PRODUCT_ID)
                ?: error("NetEase device SDK returned no token result")
            val resultClass = result.javaClass
            val code = resultClass.getMethod("getCode").invoke(result) as Int
            val token = resultClass.getMethod("getToken").invoke(result) as? String
            check(code == SDK_SUCCESS || code == SDK_OFFLINE_SUCCESS) {
                "NetEase device token request failed ($code)"
            }
            check(!token.isNullOrBlank()) { "NetEase device token is empty" }
            Log.i(TAG, "Generated ydDeviceToken code=$code length=${token.length}")
            token
        } catch (error: LinkageError) {
            Log.e(TAG, "Device token generation failed", error)
            throw NeteaseLoginSecurityException("NetEase device security runtime is unavailable", error)
        }
    }

    private data class WatchManSdk(
        val getTokenMethod: Method,
        val tokenCallbackClass: Class<*>,
        val classLoader: ClassLoader,
        val getDeviceIdMethod: Method,
        val setCustomTrackIdMethod: Method,
    ) {
        fun getDeviceId(): String = (invoke(getDeviceIdMethod, null) as? String).orEmpty()

        fun setCustomTrackId(trackId: String) {
            invoke(setCustomTrackIdMethod, null, trackId)
        }

        fun getToken(): String = try {
            val code = AtomicInteger(Int.MIN_VALUE)
            val message = AtomicReference("")
            val token = AtomicReference("")
            val latch = CountDownLatch(1)
            val callback = Proxy.newProxyInstance(
                classLoader,
                arrayOf(tokenCallbackClass),
            ) { proxy, method, args ->
                when (method.name) {
                    "onResult" -> {
                        code.set(args?.getOrNull(0) as? Int ?: Int.MIN_VALUE)
                        message.set(args?.getOrNull(1) as? String ?: "")
                        token.set(args?.getOrNull(2) as? String ?: "")
                        latch.countDown()
                        null
                    }

                    else -> invokeProxyObjectMethod(proxy, method, args)
                }
            }
            invoke(getTokenMethod, null, WATCHMAN_TOKEN_TIMEOUT, callback)
            check(latch.await(SDK_CALLBACK_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                "NetEase WatchMan token request timed out"
            }
            check(code.get() == SDK_SUCCESS) {
                "NetEase WatchMan token request failed (${code.get()}): ${message.get()}"
            }
            token.get().also {
                check(it.isNotBlank()) { "NetEase WatchMan token is empty" }
                Log.i(TAG, "Generated checkToken code=${code.get()} length=${it.length}")
            }
        } catch (error: LinkageError) {
            Log.e(TAG, "WatchMan token generation failed", error)
            throw NeteaseLoginSecurityException("NetEase WatchMan security runtime is unavailable", error)
        }
    }

    private data class WatchManNetworkSnapshot(
        val started: Int,
        val completed: Int,
        val failed: Int,
    )

    internal companion object {
        @Volatile
        private var activeInstance: NeteaseLoginSecurity? = null

        fun acceptServerTrackId(trackId: String) {
            activeInstance?.updateServerTrackId(trackId, persist = true)
        }

        @Volatile
        var sharedSecurityClassLoader: ClassLoader? = null

        val SECURITY_CLASS_LOADER_LOCK = Any()

        internal fun getOrCreateSecurityClassLoader(context: Context): ClassLoader {
            sharedSecurityClassLoader?.let { return it }
            return synchronized(SECURITY_CLASS_LOADER_LOCK) {
                sharedSecurityClassLoader ?: run {
                    val dexBuffers = SHARED_SECURITY_DEX_ASSETS.map { assetName ->
                        context.assets.open(assetName).use { input ->
                            ByteBuffer.wrap(input.readBytes())
                        }
                    }.toTypedArray()
                    InMemoryDexClassLoader(
                        dexBuffers,
                        context.applicationInfo.nativeLibraryDir,
                        context.classLoader,
                    ).also { sharedSecurityClassLoader = it }
                }
            }
        }

        const val TAG = "PcQrLogin"
        const val SECURITY_SDK_ASSET = "netease-device-sdk.dex"
        val SHARED_SECURITY_DEX_ASSETS = listOf(
            "netease-urs-loginapi.dex",
            "netease-urs-captcha.dex",
            "netease-urs-modular.dex",
            "netease-urs-core.dex",
            "netease-urs-httpdns.dex",
            SECURITY_SDK_ASSET,
        )
        const val DEVICE_CLASS_NAME = "com.netease.mobsec.xs.NEDevice"
        const val WATCHMAN_CLASS_NAME = "com.netease.mobsec.WatchMan"
        const val WATCHMAN_CONF_CLASS_NAME = "com.netease.mobsec.WatchManConf"
        const val INIT_CALLBACK_CLASS_NAME = "com.netease.mobsec.InitCallback"
        const val TOKEN_CALLBACK_CLASS_NAME = "com.netease.mobsec.GetTokenCallback"
        const val NET_CLIENT_CLASS_NAME = "com.netease.mobsec.AbstractNetClient"
        const val DEVICE_PRODUCT_ID = "946be734f7a741f5b1f36970b3075c7f"
        const val WATCHMAN_PRODUCT_ID = "YD00000558929251"
        const val OFFICIAL_CHANNEL = "xiaomi"
        const val OFFICIAL_PACKAGE_NAME = "com.netease.cloudmusic"
        const val OFFICIAL_VERSION_NAME = "9.5.70"
        const val OFFICIAL_VERSION_CODE = 9_005_070L
        const val OFFICIAL_SIGNING_CERTIFICATE =
            "MIIDjzCCAnegAwIBAgIEGyUU4TANBgkqhkiG9w0BAQsFADB3MQ8wDQYDVQQGEwYzMTAwMDAxETAPBgNVBAgTCFpoZUppYW5nMREwDwYDVQQHEwhIYW5nWmhvdTETMBEGA1UEChMKQ2xvdWRNdXNpYzEVMBMGA1UECxMMQ29ycC5OZXRlYXNlMRIwEAYDVQQDEwlMaWFuZ0ppYW4wIBcNMTMwMTIwMTQ0MzIwWhgPMjA2MzAxMDgxNDQzMjBaMHcxDzANBgNVBAYTBjMxMDAwMDERMA8GA1UECBMIWmhlSmlhbmcxETAPBgNVBAcTCEhhbmdaaG91MRMwEQYDVQQKEwpDbG91ZE11c2ljMRUwEwYDVQQLEwxDb3JwLk5ldGVhc2UxEjAQBgNVBAMTCUxpYW5nSmlhbjCCASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoCggEBANTrEZcFfWqtwBeApTdPYxe3MAk37iaJ5FMkQsH2RYibNZhwA7c5m2X0Aug/onE/9OuUjVGp/1jrgm/MTT3w0nq6IiBfkfhxlGmQ1clbQ4Ji16rsbKQ/bwueHIL5YwAVbgZHIyhzaZtRlIBxIfFYNgA6Bcr1uaHhg19RH5/M7ZzIAZSH0N2LVIOO4zTXWr7hIshOe1DjIZbZCcp83GtC9yBJFiS9ywjSSluRAlpJqhpTFMohMhZVvZ3StTDjrbslEfr06zPCUKdQmRVIq/3AoloRPud11w25d2r2gjDPTbVDSIcbFrOsfjarwupnS9ouc5hEPtWlFu/H5sxkfkm2UXUCAwEAAaMhMB8wHQYDVR0OBBYEFK/ssCUZYOZ+iPz0Gfm/KdkP6rtwMA0GCSqGSIb3DQEBCwUAA4IBAQBjSNtESSXKcVEDqaPR60h03MW4UDTcVugSCRBD/OZPEL31hGNb+QtvFtXUr6GJly+slYjymIr7M/6aVkOD8HiajsggzeuWpyTfNfeb2+CBJ8O6JWSzyGpWHlUnKMskuXmpPE2F1CZfgbDLBILh+wzu3Icgn7waAXOw83skUhpk580kJ5yxzC/5yg3P93IpbjQ4jAWZM573aQ48upiAheCnlR17U5019w5+XsRuFqComyfty+128ZkOyFIeutIxHnOqJeGJAqh+VXR8Cp+0wL4d/qR9Fyy+4cCQpMvTk/E76VmaQIjjiSyaI2PvDAy75bJ2qbb/GIKIEkSd8LsQyLvy"
        const val OFFICIAL_ANDROID_USER_AGENT =
            "NeteaseMusic/9.5.70.260818213343(9005070);Dalvik/2.1.0"
        const val SDK_SUCCESS = 200
        const val SDK_OFFLINE_SUCCESS = 201
        const val SDK_NETWORK_ERROR = 601
        const val WATCHMAN_TOKEN_TIMEOUT = 500
        const val WATCHMAN_NETWORK_TIMEOUT_MS = 20_000L
        const val WATCHMAN_NETWORK_POLL_INTERVAL_MS = 20L
        const val WATCHMAN_NETWORK_QUIET_MS = 1_000L
        const val NMCID_RANDOM_LENGTH = 6
        const val CAESARSON_CONFIG = "CeLwrg=="
        const val DEFAULT_NETWORK_TIMEOUT_MS = 10_000
        const val SDK_CALLBACK_TIMEOUT_SECONDS = 20L
        val FORM_MEDIA_TYPE = "application/x-www-form-urlencoded".toMediaType()

        fun invoke(method: Method, receiver: Any?, vararg args: Any): Any? = try {
            method.invoke(receiver, *args)
        } catch (error: InvocationTargetException) {
            throw error.targetException
        }

        fun invokeProxyObjectMethod(proxy: Any, method: Method, args: Array<out Any?>?): Any? =
            when (method.name) {
                "toString" -> "${proxy.javaClass.interfaces.firstOrNull()?.simpleName}Proxy"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> throw UnsupportedOperationException("Unsupported SDK callback: ${method.name}")
            }
    }
}

internal object NeteaseSecurityDns : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val systemLookup = runCatching { Dns.SYSTEM.lookup(hostname) }
        val systemAddresses = systemLookup.getOrNull()
        val needsFallback = hostname.equals(WATCHMAN_PRIMARY_HOST, ignoreCase = true) &&
            (systemAddresses.isNullOrEmpty() || systemAddresses.all { it.isDnsSinkhole() })

        if (!needsFallback) {
            return systemLookup.getOrThrow()
        }

        val fallbackAddresses = Dns.SYSTEM.lookup(WATCHMAN_FALLBACK_HOST)
            .filterNot { it.isDnsSinkhole() }
        if (fallbackAddresses.isEmpty()) {
            throw UnknownHostException(
                "WatchMan DNS fallback returned no usable addresses for $hostname",
            )
        }

        Log.i(
            NeteaseLoginSecurity.TAG,
            "WatchMan DNS fallback host=$hostname via=$WATCHMAN_FALLBACK_HOST " +
                "addresses=${fallbackAddresses.size}",
        )
        return fallbackAddresses
    }

    private fun InetAddress.isDnsSinkhole(): Boolean = isAnyLocalAddress || isLoopbackAddress

    private const val WATCHMAN_PRIMARY_HOST = "ac.dun.163yun.com"
    private const val WATCHMAN_FALLBACK_HOST = "ac.dun.163.com"
}

data class NeteaseSecurityCookies(
    val nmcid: String,
    val nmdi: String,
    val nmtid: String = "",
)

internal class OfficialNeteaseSecurityContext(
    base: Context,
) : ContextWrapper(base) {
    private val officialApplicationInfo by lazy(LazyThreadSafetyMode.NONE) {
        ApplicationInfo(base.applicationInfo).apply {
            packageName = NeteaseLoginSecurity.OFFICIAL_PACKAGE_NAME
        }
    }

    private val officialPackageManager by lazy(LazyThreadSafetyMode.NONE) {
        OfficialNeteasePackageManager(
            delegate = base.packageManager,
            sourcePackageName = base.packageName,
            officialApplicationInfo = officialApplicationInfo,
        )
    }

    override fun getApplicationContext(): Context = this

    override fun getApplicationInfo(): ApplicationInfo = officialApplicationInfo

    override fun getPackageManager(): PackageManager = officialPackageManager

    override fun getPackageName(): String = NeteaseLoginSecurity.OFFICIAL_PACKAGE_NAME
}

@Suppress("DEPRECATION")
private class OfficialNeteasePackageManager(
    private val delegate: PackageManager,
    private val sourcePackageName: String,
    private val officialApplicationInfo: ApplicationInfo,
) : MockPackageManager() {
    private val officialSignature = Signature(
        Base64.decode(NeteaseLoginSecurity.OFFICIAL_SIGNING_CERTIFICATE, Base64.DEFAULT),
    )

    override fun getPackageInfo(packageName: String, flags: Int): PackageInfo {
        if (packageName != NeteaseLoginSecurity.OFFICIAL_PACKAGE_NAME) {
            return delegate.getPackageInfo(packageName, flags)
        }
        val sourceInfo = delegate.getPackageInfo(sourcePackageName, flags)
        return PackageInfo().apply {
            this.packageName = NeteaseLoginSecurity.OFFICIAL_PACKAGE_NAME
            versionName = NeteaseLoginSecurity.OFFICIAL_VERSION_NAME
            versionCode = NeteaseLoginSecurity.OFFICIAL_VERSION_CODE.toInt()
            setLongVersionCode(NeteaseLoginSecurity.OFFICIAL_VERSION_CODE)
            applicationInfo = officialApplicationInfo
            firstInstallTime = sourceInfo.firstInstallTime
            lastUpdateTime = sourceInfo.lastUpdateTime
            signatures = arrayOf(officialSignature)
            signingInfo = SigningInfo(
                SigningInfo.VERSION_JAR,
                listOf(officialSignature),
                emptyList(),
                listOf(officialSignature),
            )
        }
    }

    override fun getPackageInfo(versionedPackage: VersionedPackage, flags: Int): PackageInfo =
        getPackageInfo(versionedPackage.packageName, flags)

    override fun getApplicationInfo(packageName: String, flags: Int): ApplicationInfo =
        if (packageName == NeteaseLoginSecurity.OFFICIAL_PACKAGE_NAME) {
            officialApplicationInfo
        } else {
            delegate.getApplicationInfo(packageName, flags)
        }

    override fun getApplicationLabel(info: ApplicationInfo): CharSequence =
        if (info.packageName == NeteaseLoginSecurity.OFFICIAL_PACKAGE_NAME) {
            "网易云音乐"
        } else {
            delegate.getApplicationLabel(info)
        }

    override fun getNameForUid(uid: Int): String? =
        if (uid == officialApplicationInfo.uid) {
            NeteaseLoginSecurity.OFFICIAL_PACKAGE_NAME
        } else {
            delegate.getNameForUid(uid)
        }

    override fun getPackagesForUid(uid: Int): Array<String>? =
        if (uid == officialApplicationInfo.uid) {
            arrayOf(NeteaseLoginSecurity.OFFICIAL_PACKAGE_NAME)
        } else {
            delegate.getPackagesForUid(uid)
        }

    override fun checkPermission(permissionName: String, packageName: String): Int =
        delegate.checkPermission(
            permissionName,
            if (packageName == NeteaseLoginSecurity.OFFICIAL_PACKAGE_NAME) {
                sourcePackageName
            } else {
                packageName
            },
        )

    override fun hasSystemFeature(name: String): Boolean = delegate.hasSystemFeature(name)

    override fun hasSystemFeature(name: String, version: Int): Boolean =
        delegate.hasSystemFeature(name, version)

    override fun getResourcesForApplication(app: ApplicationInfo): Resources =
        if (app.packageName == NeteaseLoginSecurity.OFFICIAL_PACKAGE_NAME) {
            delegate.getResourcesForApplication(sourcePackageName)
        } else {
            delegate.getResourcesForApplication(app)
        }

    override fun getResourcesForApplication(packageName: String): Resources =
        delegate.getResourcesForApplication(
            if (packageName == NeteaseLoginSecurity.OFFICIAL_PACKAGE_NAME) {
                sourcePackageName
            } else {
                packageName
            },
        )
}

private class NeteaseLoginSecurityException(
    message: String,
    cause: Throwable,
) : IllegalStateException(message, cause)
