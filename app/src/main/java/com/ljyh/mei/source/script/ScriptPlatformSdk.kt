package com.ljyh.mei.source.script

import android.content.Context
import com.ljyh.mei.source.api.SourceErrorCode
import com.ljyh.mei.source.api.SourceException
import okhttp3.Interceptor
import java.security.MessageDigest

/** Closed native codecs never receive the host's account or infer service request behavior. */
internal object ScriptPlatformSdk {
    fun interceptor(name: String, configuration: Map<String, String>, arguments: Map<String, String>): Interceptor = when (name) {
        "netease.urs" -> {
            if (arguments.isNotEmpty()) throw SourceException(SourceErrorCode.INVALID_RESPONSE)
            com.ljyh.mei.data.network.NeteaseUrsCodecSdk.interceptor(configuration)
        }
        "netease.transport" -> com.ljyh.mei.data.network.NeteaseWireCodec.interceptor(arguments)
        else -> throw SourceException(SourceErrorCode.UNSUPPORTED)
    }

    @Synchronized internal fun installationDeviceId(context: Context, namespace: String): String {
        val key = MessageDigest.getInstance("SHA-256").digest(namespace.toByteArray()).joinToString("") { "%02x".format(it) }
        val file = java.io.File(context.noBackupFilesDir, "source-sdk/$key/transport-device")
        if (file.isFile) return file.readText().also { require(Regex("[a-f0-9]{32}").matches(it)) }
        val value = java.util.UUID.randomUUID().toString().replace("-", "")
        file.parentFile?.mkdirs()
        val temporary = java.io.File(file.parentFile, "transport-device.tmp")
        temporary.writeText(value)
        check(temporary.renameTo(file))
        return value
    }
}
