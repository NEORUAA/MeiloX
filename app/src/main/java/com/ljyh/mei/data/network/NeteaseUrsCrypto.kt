package com.ljyh.mei.data.network

import android.content.Context
import android.util.Base64
import com.netease.android.dat.library.DatManager
import com.netease.urs.jni.NativeJni
import java.io.ByteArrayOutputStream
import java.security.Key
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.interfaces.RSAKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import org.json.JSONObject

/** The URS wire format recovered from the reference client: RSA + SM4, p1/p2 headers. */
internal class NeteaseUrsCrypto(context: Context, appSign: String) {
    private val serverKey: Key
    private val clientKey: Key
    val packageSignature: String

    init {
        val factory = KeyFactory.getInstance("RSA")
        DatManager(context, appSign).use { dat ->
            serverKey = factory.generatePublic(X509EncodedKeySpec(Base64.decode(
                dat.readKey(context, "key_public.dat", "music_server_public"), Base64.NO_WRAP,
            )))
            clientKey = factory.generatePrivate(PKCS8EncodedKeySpec(Base64.decode(
                dat.readKey(context, "key_private.dat", "music_client_private"), Base64.NO_WRAP,
            )))
        }
        val signature = NativeJni.getSignatureMd5Bytes(context, false)
        check(signature is ByteArray && signature.isNotEmpty()) { "URS signature unavailable" }
        // The SDK serializes all high nibbles before all low nibbles (not ordinary hex).
        packageSignature = com.netease.android.dat.library.NativeSignatureUtil.bytesToHex(signature)

    }

    fun encryptHeaders(parameters: JSONObject): Map<String, String> {
        val random = SecureRandom()
        val key = ByteArray(16).also(random::nextBytes)
        val iv = ByteArray(16).also(random::nextBytes)
        val keys = JSONObject().put("smkey", key.toHex()).put("smIv", iv.toHex())
        val encryptedKeys = Base64.encodeToString(rsa(Cipher.ENCRYPT_MODE, serverKey,
            keys.toString().toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        return mapOf(
            "p1" to sm4(Cipher.ENCRYPT_MODE, key, iv,
                parameters.toString().toByteArray(Charsets.UTF_8)).toHex(),
            "p2" to encryptedKeys.toByteArray(Charsets.UTF_8).toHex().uppercase(),
        )
    }

    fun decryptResponse(response: JSONObject): JSONObject {
        val data = response.opt("data")
        if (data == null || data == JSONObject.NULL) return JSONObject()
        if (!response.optBoolean("enc")) return data as? JSONObject ?: JSONObject(data.toString())
        val key = rsa(Cipher.DECRYPT_MODE, clientKey,
            Base64.decode(response.getString("key"), Base64.NO_WRAP)).toString(Charsets.UTF_8).fromHex()
        val iv = rsa(Cipher.DECRYPT_MODE, clientKey,
            Base64.decode(response.getString("iv"), Base64.NO_WRAP)).toString(Charsets.UTF_8).fromHex()
        return JSONObject(sm4(Cipher.DECRYPT_MODE, key, iv,
            data.toString().fromHex()).toString(Charsets.UTF_8))
    }

    private fun rsa(mode: Int, key: Key, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(mode, key)
        val block = (key as RSAKey).modulus.bitLength() / 8 - if (mode == Cipher.ENCRYPT_MODE) 11 else 0
        return ByteArrayOutputStream().use { out ->
            var position = 0
            while (position < data.size) {
                val count = minOf(block, data.size - position)
                out.write(cipher.doFinal(data, position, count))
                position += count
            }
            out.toByteArray()
        }
    }

    private fun sm4(mode: Int, key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        return NeteaseUrsSm4.transform(mode == Cipher.ENCRYPT_MODE, key, iv, data)
    }
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 255) }
internal fun String.fromHex(): ByteArray {
    require(length % 2 == 0) { "Invalid encrypted URS response" }
    return ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
