package com.ljyh.mei.data.network

import android.app.Activity
import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Reflection bridge for the NetEase URS SMS flow bundled with the official Android client.
 *
 * The SDK is kept isolated in an in-memory class loader because its packages overlap with
 * other NetEase runtimes used by the application. Verification codes and returned URS tokens
 * remain in memory and are never logged or persisted by this bridge.
 */
@Singleton
class NeteaseUrsSmsLogin @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    @Volatile
    private var runtime: UrsRuntime? = null
    private val initializationMutex = Mutex()

    suspend fun requestCode(
        activity: Activity,
        phone: String,
        countryCode: String,
    ) {
        Log.i(LOG_TAG, "URS SMS code request started")
        check(!activity.isFinishing && !activity.isDestroyed) {
            "The sign-in screen is no longer available"
        }
        val sdk = getRuntime()
        val captchaConfiguration = sdk.createCaptchaConfiguration(activity)
        try {
            withUrsTimeout("SMS code request", SMS_REQUEST_TIMEOUT_MS) {
                sdk.call(
                    methodName = "aquireSmsCode",
                    parameterTypes = arrayOf(
                        Int::class.javaPrimitiveType!!,
                        String::class.java,
                        sdk.captchaConfigurationClass,
                    ),
                    arguments = arrayOf(
                        SMS_LOGIN_INTENT,
                        ursPhoneAccount(phone, countryCode),
                        captchaConfiguration,
                    ),
                )
            }
        } catch (error: Throwable) {
            Log.e(LOG_TAG, "URS SMS code request failed type=${error.javaClass.simpleName}")
            throw error
        }
        Log.i(LOG_TAG, "URS SMS code request completed")
    }

    suspend fun verifyCode(
        phone: String,
        countryCode: String,
        code: String,
    ): String {
        Log.i(LOG_TAG, "URS SMS verification started")
        val sdk = getRuntime()
        val result = withUrsTimeout("SMS verification", SMS_VERIFY_TIMEOUT_MS) {
            sdk.call(
                methodName = "vertifySmsCode",
                parameterTypes = arrayOf(
                    String::class.java,
                    String::class.java,
                    sdk.loginOptionsClass,
                ),
                arguments = arrayOf(ursPhoneAccount(phone, countryCode), code, null),
            )
        } ?: error("NetEase URS returned an empty SMS verification result")
        check(sdk.ursAccountClass.isInstance(result)) {
            "NetEase URS returned an unexpected SMS verification result"
        }
        val token = sdk.ursAccountClass.getMethod("getToken").invoke(result) as? String
        check(!token.isNullOrBlank()) { "NetEase URS did not return a login token" }
        Log.i(LOG_TAG, "URS SMS verification completed")
        return token
    }

    private suspend fun getRuntime(): UrsRuntime {
        runtime?.let { return it }
        return initializationMutex.withLock {
            runtime?.let { return@withLock it }
            Log.i(LOG_TAG, "URS runtime creation started")
            val initialized = createRuntime()
            Log.i(LOG_TAG, "URS runtime created")
            try {
                withUrsTimeout("SDK initialization", SDK_INITIALIZATION_TIMEOUT_MS) {
                    initialized.initialize()
                }
            } catch (error: Throwable) {
                Log.e(
                    LOG_TAG,
                    "URS SDK initialization failed causes=${error.initializationCauseSummary()}",
                )
                throw error
            }
            runtime = initialized
            Log.i(LOG_TAG, "URS SDK initialized")
            initialized
        }
    }

    private suspend fun createRuntime(): UrsRuntime = withContext(Dispatchers.IO) {
        val officialCertificate = context.assets.open(OFFICIAL_CERTIFICATE_ASSET).use { input ->
            input.readBytes()
        }
        val classLoader = NeteaseLoginSecurity.getOrCreateSecurityClassLoader(context)
        initializeNativeLibraryContext(classLoader)
        UrsRuntime(
            NeteaseUrsApplicationContext(context.applicationContext, officialCertificate),
            classLoader,
        )
    }

    private fun initializeNativeLibraryContext(classLoader: ClassLoader) {
        val loaderClass = Class.forName(NATIVE_LIBRARY_LOADER_CLASS, true, classLoader)
        val callbackClass = Class.forName(NATIVE_LIBRARY_CALLBACK_CLASS, false, classLoader)
        loaderClass.getMethod(
            "a",
            Context::class.java,
            callbackClass,
        ).invoke(null, context.applicationContext, null)
        Log.i(LOG_TAG, "URS native library context initialized")
    }

    private fun ursPhoneAccount(phone: String, countryCode: String): String =
        if (countryCode == CHINA_COUNTRY_CODE) phone else "$countryCode-$phone"

    private class UrsRuntime(
        private val context: Context,
        private val classLoader: ClassLoader,
    ) {
        val captchaConfigurationClass: Class<*> = loadClass(URS_CAPTCHA_CONFIGURATION_CLASS)
        val loginOptionsClass: Class<*> = loadClass(LOGIN_OPTIONS_CLASS)
        val ursAccountClass: Class<*> = loadClass(URS_ACCOUNT_CLASS)

        private val ursSdkClass = loadClass(URS_SDK_CLASS)
        private val callbackClass = loadClass(URS_CALLBACK_CLASS)

        suspend fun initialize() {
            val config = buildConfig()
            Log.i(LOG_TAG, "URS SDK synchronous initialization started")
            withContext(Dispatchers.IO) {
                runCatching {
                    ursSdkClass.getMethod(
                        "createAPI",
                        Context::class.java,
                        Boolean::class.javaPrimitiveType,
                        loadClass(NE_CONFIG_CLASS),
                    ).invoke(null, context, true, config)
                }.getOrElse { error ->
                    throw error.unwrapReflectionError()
                }
            }
            Log.i(LOG_TAG, "URS SDK synchronous initialization completed")
            Log.i(LOG_TAG, "URS mobile application initialization started")
            call(
                methodName = "requestInitMobApp",
                parameterTypes = emptyArray(),
                arguments = emptyArray(),
            )
            Log.i(LOG_TAG, "URS mobile application initialization completed")
        }

        fun createCaptchaConfiguration(activity: Activity): Any =
            captchaConfigurationClass.methods
                .single { method ->
                    method.name == "createCaptchaConfigurationBuilder" &&
                        method.parameterTypes.size == 2 &&
                        method.parameterTypes[1] == Activity::class.java
                }
                .invoke(null, null, activity)
                ?: error("NetEase URS captcha configuration is unavailable")

        suspend fun call(
            methodName: String,
            parameterTypes: Array<Class<*>>,
            arguments: Array<Any?>,
        ): Any? = suspendCancellableCoroutine { continuation ->
            val callback = proxy(callbackClass) { method, callbackArguments ->
                when (method.name) {
                    "onSuccess" -> if (continuation.isActive) {
                        Log.i(LOG_TAG, "URS API callback success method=$methodName")
                        continuation.resume(callbackArguments?.getOrNull(1))
                    }

                    "onError" -> if (continuation.isActive) {
                        Log.w(
                            LOG_TAG,
                            "URS API callback error method=$methodName " +
                                "codes=${callbackArguments?.getOrNull(1)}/" +
                                "${callbackArguments?.getOrNull(2)}/" +
                                "${callbackArguments?.getOrNull(3)}",
                        )
                        val message = (callbackArguments?.getOrNull(4) as? String)
                            ?.takeIf(String::isNotBlank)
                            ?: "NetEase URS request failed"
                        continuation.resumeWithException(
                            NeteaseUrsException(
                                apiCode = callbackArguments?.getOrNull(1) as? Int,
                                subCode = callbackArguments?.getOrNull(2) as? Int,
                                detailCode = callbackArguments?.getOrNull(3) as? Int,
                                message = message,
                            ),
                        )
                    }
                }
            }
            runCatching {
                Log.i(LOG_TAG, "URS API call started method=$methodName")
                val builder = ursSdkClass
                    .getMethod("customize", String::class.java, callbackClass)
                    .invoke(null, PRODUCT, callback)
                val api = builder.javaClass.getMethod("build").invoke(builder)
                api.javaClass.getMethod(methodName, *parameterTypes).invoke(api, *arguments)
            }.onFailure { error ->
                if (continuation.isActive) {
                    continuation.resumeWithException(error.unwrapReflectionError())
                }
            }
        }

        private fun buildConfig(): Any {
            val builderClass = loadClass(NE_CONFIG_BUILDER_CLASS)
            val builder = builderClass.getConstructor().newInstance()
            builder.callBuilder("product", PRODUCT)
            builder.callBuilder("accessId", ACCESS_ID)
            builder.callBuilder("setAppSign", APP_SIGN)
            builder.callBuilder("setDebug", false)
            val privacyClass = loadClass(PRIVACY_LEVEL_CLASS)
            val strictPrivacy = privacyClass.enumConstants
                ?.firstOrNull { (it as? Enum<*>)?.name == "STRICT" }
                ?: error("NetEase URS strict privacy mode is unavailable")
            builder.callBuilder("setPrivacyLevel", strictPrivacy)
            builder.callBuilder("setUrsServerPublicDatPath", PUBLIC_KEY_ASSET)
            builder.callBuilder("setUrsClientPrivateDatPath", PRIVATE_KEY_ASSET)
            return builderClass.getMethod("build").invoke(builder)
                ?: error("NetEase URS configuration is unavailable")
        }

        private fun Any.callBuilder(methodName: String, value: Any) {
            javaClass.methods
                .single { method -> method.name == methodName && method.parameterTypes.size == 1 }
                .invoke(this, value)
        }

        private fun proxy(
            interfaceClass: Class<*>,
            callback: (Method, Array<out Any?>?) -> Unit,
        ): Any = Proxy.newProxyInstance(
            classLoader,
            arrayOf(interfaceClass),
        ) { proxy, method, arguments ->
            when (method.name) {
                "toString" -> "NeteaseUrsProxy(${interfaceClass.simpleName})"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === arguments?.getOrNull(0)
                else -> {
                    callback(method, arguments)
                    method.returnType.defaultValue()
                }
            }
        }

        private fun loadClass(name: String): Class<*> = Class.forName(name, true, classLoader)
    }

    private companion object {
        const val LOG_TAG = "NeteaseUrsLogin"
        const val PRODUCT = "music"
        const val ACCESS_ID = "29c8a5f38b72a98ac74ac2c667d05dfa"
        const val APP_SIGN =
            "BHYOW1T4C3rr25657klSTYqpTPHBRtzMX4gD73mO/I0N3tnxEcjsshtIaXXv6+e79/" +
                "azwgyVwr/K4Ov0y3LuFdRjY04MXTWFun9r2xFc5Y1vnegkuvpOERfwFgbbSuYGT77tR/" +
                "+12VOGvOdufRSRWvk3xIHn3AV1CxjBu2admY3g6aEjbFqxcphy7T9aoGthod/JGmU4hJ7" +
                "HVFYH2rjOvCE30NKWxWpC4FTD1012JZ+kNNrIGOBBlUp6vvLWMhxoNaIePA3QV5alSX" +
                "Q4rlc8b9keNPBu8P+tpXI1LtrUx7fBx+VHrsAgPbfP8vXxFnzrXQVOKm1GgqB5btynA" +
                "4iT8g=="
        const val PUBLIC_KEY_ASSET = "key_public.dat"
        const val PRIVATE_KEY_ASSET = "key_private.dat"
        const val OFFICIAL_CERTIFICATE_ASSET = "netease_official_signing_certificate.der"
        const val CHINA_COUNTRY_CODE = "86"
        const val SMS_LOGIN_INTENT = 1
        const val SDK_INITIALIZATION_TIMEOUT_MS = 45_000L
        const val SMS_REQUEST_TIMEOUT_MS = 120_000L
        const val SMS_VERIFY_TIMEOUT_MS = 60_000L

        const val URS_SDK_CLASS = "com.netease.loginapi.URSdk"
        const val URS_CALLBACK_CLASS = "com.netease.loginapi.expose.URSAPICallback"
        const val NE_CONFIG_CLASS = "com.netease.loginapi.NEConfig"
        const val NE_CONFIG_BUILDER_CLASS = "com.netease.loginapi.NEConfig\u0024NEConfigBuilder"
        const val PRIVACY_LEVEL_CLASS = "com.netease.loginapi.privacy.PrivacyLevel"
        const val URS_CAPTCHA_CONFIGURATION_CLASS =
            "com.netease.loginapi.expose.vo.URSCaptchaConfiguration"
        const val LOGIN_OPTIONS_CLASS = "com.netease.loginapi.expose.vo.LoginOptions"
        const val URS_ACCOUNT_CLASS = "com.netease.loginapi.expose.vo.URSAccount"
        const val NATIVE_LIBRARY_LOADER_CLASS = "h3.c"
        const val NATIVE_LIBRARY_CALLBACK_CLASS = "h3.c\u0024a"
    }
}

private suspend fun <T> withUrsTimeout(
    operation: String,
    timeoutMillis: Long,
    block: suspend () -> T,
): T = try {
    withTimeout(timeoutMillis) { block() }
} catch (error: TimeoutCancellationException) {
    throw NeteaseUrsException(
        message = "NetEase URS $operation timed out",
        cause = error,
    )
}

class NeteaseUrsException(
    val apiCode: Int? = null,
    val subCode: Int? = null,
    val detailCode: Int? = null,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

private fun Throwable.unwrapReflectionError(): Throwable =
    (this as? InvocationTargetException)?.targetException ?: this

private fun Throwable.initializationCauseSummary(): String =
    generateSequence(this) { error -> error.cause }
        .take(8)
        .joinToString(" <- ") { error ->
            val message = error.message
                ?.replace('\n', ' ')
                ?.replace('\r', ' ')
                ?.take(240)
                ?.takeIf(String::isNotBlank)
            if (message == null) error.javaClass.name
            else "${error.javaClass.name}($message)"
        }

private fun Class<*>.defaultValue(): Any? = when (this) {
    Boolean::class.javaPrimitiveType -> false
    Byte::class.javaPrimitiveType -> 0.toByte()
    Short::class.javaPrimitiveType -> 0.toShort()
    Int::class.javaPrimitiveType -> 0
    Long::class.javaPrimitiveType -> 0L
    Float::class.javaPrimitiveType -> 0f
    Double::class.javaPrimitiveType -> 0.0
    Char::class.javaPrimitiveType -> '\u0000'
    else -> null
}
