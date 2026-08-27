package com.ljyh.mei.data.network

import android.content.Context
import android.provider.Settings
import android.util.Base64
import dalvik.system.InMemoryDexClassLoader
import java.lang.reflect.Method
import java.net.URLEncoder
import java.nio.ByteBuffer

internal object NeteaseOfficialDeviceId {
    @Volatile
    private var loadedSdk: DeviceIdSdk? = null

    fun create(context: Context, fallbackLocalId: String): NeteaseOfficialDeviceIdentity {
        val sdk = loadSdk(context)
        val officialContext = OfficialNeteaseSecurityContext(context)
        val wifiAddress = (sdk.getWifi.invoke(null, officialContext) as? String)
            ?.takeIf(String::isNotBlank)
            ?: DEFAULT_WIFI_ADDRESS
        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID,
        ).orEmpty().ifBlank { NULL_DEVICE_FIELD }
        val sdkLocalId = (sdk.getLocalId.invoke(null, officialContext) as? String)
            ?.trim()
            .orEmpty()
            .let(::normalizeLocalId)
            .takeIf { it.length == LOCAL_ID_LENGTH }
        val localId = sdkLocalId ?: normalizeLocalId(fallbackLocalId).also {
            check(it.length == LOCAL_ID_LENGTH) {
                "NetEase local device ID fallback must be $LOCAL_ID_LENGTH characters"
            }
        }
        val rawDeviceId = listOf(
            NULL_DEVICE_FIELD,
            wifiAddress,
            androidId,
            localId,
        ).joinToString("\t")
        return NeteaseOfficialDeviceIdentity(
            encoded = URLEncoder.encode(
                Base64.encodeToString(rawDeviceId.toByteArray(), Base64.NO_WRAP),
                Charsets.UTF_8.name(),
            ),
            wifiLength = wifiAddress.length,
            androidIdLength = androidId.length,
            localIdLength = localId.length,
            usedLocalIdFallback = sdkLocalId == null,
        )
    }

    private fun loadSdk(context: Context): DeviceIdSdk {
        loadedSdk?.let { return it }
        return synchronized(this) {
            loadedSdk ?: run {
                NeteaseNativeLogPolicy.installSecurityFilter()
                val dexBytes = context.assets.open(DEVICE_ID_SDK_ASSET).use { it.readBytes() }
                val classLoader = InMemoryDexClassLoader(
                    arrayOf(ByteBuffer.wrap(dexBytes)),
                    context.applicationInfo.nativeLibraryDir,
                    context.classLoader,
                )
                val deviceIdClass = Class.forName(DEVICE_ID_CLASS_NAME, true, classLoader)
                DeviceIdSdk(
                    classLoader = classLoader,
                    getWifi = deviceIdClass.getMethod("getWifi", Context::class.java),
                    getLocalId = deviceIdClass.getMethod("getLocalID", Context::class.java),
                ).also { loadedSdk = it }
            }
        }
    }

    private fun normalizeLocalId(value: String): String = when {
        value.length >= 24 -> value.substring(8, 24)
        value.length > LOCAL_ID_LENGTH -> value.take(LOCAL_ID_LENGTH)
        else -> value
    }

    private const val NULL_DEVICE_FIELD = "null"
    private const val DEFAULT_WIFI_ADDRESS = "02:00:00:00:00:00"
    private const val LOCAL_ID_LENGTH = 16
    private const val DEVICE_ID_SDK_ASSET = "netease-device-id-sdk.dex"
    private const val DEVICE_ID_CLASS_NAME = "com.netease.is.deviceid.NEDeviceID"
}

internal data class NeteaseOfficialDeviceIdentity(
    val encoded: String,
    val wifiLength: Int,
    val androidIdLength: Int,
    val localIdLength: Int,
    val usedLocalIdFallback: Boolean,
)

private data class DeviceIdSdk(
    @Suppress("unused") val classLoader: ClassLoader,
    val getWifi: Method,
    val getLocalId: Method,
)
