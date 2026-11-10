package com.ljyh.mei.data.network

import com.google.gson.JsonParser
import com.ljyh.mei.source.api.SourceErrorCode
import com.ljyh.mei.source.api.SourceException
import com.ljyh.mei.source.api.SourceResponseSizeException
import com.ljyh.mei.utils.encrypt.decryptEApiBytes
import com.ljyh.mei.utils.encrypt.encryptEApi
import com.ljyh.mei.utils.encrypt.rsaEncrypt
import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.zip.GZIPInputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import okhttp3.FormBody
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer

/** Wire encoding only. Source scripts supply URLs, signing paths, headers and complete payloads. */
internal object NeteaseWireCodec {
    private const val MAX_REQUEST_BYTES = 256 * 1024
    private const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024
    private val argumentNames = setOf("mode", "signingPath", "formFields", "weapiKey")

    fun interceptor(arguments: Map<String, String>): Interceptor {
        val mode = mode(arguments)
        return Interceptor { chain ->
            val request = encodeRequest(chain.request(), arguments)
            val response = chain.proceed(request)
            val body = response.body
            if (body == null) response else {
                val contentType = body.contentType()
                val bytes = body.use {
                    if (it.contentLength() > MAX_RESPONSE_BYTES || it.source().request(MAX_RESPONSE_BYTES + 1L)) throw SourceResponseSizeException()
                    it.bytes()
                }
                val decoded = if (response.isSuccessful) decodeResponseBytes(bytes, mode) else bytes
                response.newBuilder().body(decoded.toResponseBody(contentType)).build()
            }
        }
    }

    internal fun encodeRequest(request: Request, arguments: Map<String, String>): Request {
        val mode = mode(arguments)
        // Plain API queries already carry their complete payload in the URL.
        if (request.method == "GET" && mode == "api" && arguments.size == 1) return request
        if (request.method != "POST") invalid()
        val body = request.body ?: invalid()
        if (body.contentLength() !in 0..MAX_REQUEST_BYTES.toLong()) invalid()
        val buffer = Buffer()
        body.writeTo(buffer)
        if (buffer.size > MAX_REQUEST_BYTES) invalid()
        val serialized = buffer.readUtf8()
        val value = runCatching { JsonParser.parseString(serialized) }.getOrElse { invalid() }
        if (!value.isJsonObject) invalid()
        val form = FormBody.Builder()
        when (mode) {
            "eapi" -> {
                val path = arguments["signingPath"]?.takeIf {
                    it.startsWith('/') && it.length <= 8192 && it.none { char -> char in "\u0000\r\n?#" }
                } ?: invalid()
                form.add("params", encryptEApi(path, serialized).params)
            }
            "weapi" -> {
                val key = arguments["weapiKey"]?.takeIf { Regex("[A-Za-z0-9]{16}").matches(it) } ?: invalid()
                val first = encryptCbc(serialized.toByteArray(Charsets.UTF_8), "0CoJUm6Qyw8W8jud")
                val second = encryptCbc(Base64.getEncoder().encode(first), key)
                form.add("params", Base64.getEncoder().encodeToString(second))
                form.add("encSecKey", rsaEncrypt(key))
            }
            "api" -> value.asJsonObject.entrySet().forEach { (name, field) ->
                form.add(name, if (field.isJsonPrimitive) field.asString else field.toString())
            }
        }
        arguments["formFields"]?.let { serializedFields ->
            val fields = runCatching { JsonParser.parseString(serializedFields) }.getOrElse { invalid() }
            if (!fields.isJsonObject || fields.asJsonObject.size() > 32 || serializedFields.length > 32_768) invalid()
            fields.asJsonObject.entrySet().forEach { (name, field) ->
                if (name in setOf("params", "encSecKey") || name.length !in 1..128 ||
                    !field.isJsonPrimitive || !field.asJsonPrimitive.isString || field.asString.length > 16_384) invalid()
                form.add(name, field.asString)
            }
        }
        val encoded = form.build()
        if (encoded.contentLength() > MAX_REQUEST_BYTES) invalid()
        return request.newBuilder().post(encoded).header("Content-Type", encoded.contentType().toString()).build()
    }

    internal fun decodeResponseBytes(bytes: ByteArray, mode: String, limit: Int = MAX_RESPONSE_BYTES): ByteArray {
        if (limit !in 1..MAX_RESPONSE_BYTES) invalid()
        if (bytes.size > limit) throw SourceResponseSizeException()
        if (mode != "eapi" || bytes.isEmpty() || bytes.firstOrNull { (it.toInt() and 0xff) > 32 } in listOf('{'.code.toByte(), '['.code.toByte())) return bytes
        val decrypted = try { decryptEApiBytes(bytes) } catch (_: Exception) { return bytes }
        if (decrypted.size > limit) throw SourceResponseSizeException()
        if (decrypted.size < 2 || decrypted[0] != 0x1f.toByte() || decrypted[1] != 0x8b.toByte()) return decrypted
        return try {
            GZIPInputStream(ByteArrayInputStream(decrypted)).use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > limit) throw SourceResponseSizeException()
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        } catch (error: SourceException) { throw error
        } catch (_: Exception) { bytes }
    }

    private fun mode(arguments: Map<String, String>): String {
        if (arguments.keys.any { it !in argumentNames } || arguments.values.sumOf { it.length } > 65_536) invalid()
        return arguments["mode"]?.takeIf { it in setOf("api", "weapi", "eapi") } ?: invalid()
    }

    private fun encryptCbc(value: ByteArray, key: String): ByteArray = Cipher.getInstance("AES/CBC/PKCS5Padding").run {
        init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.toByteArray(Charsets.US_ASCII), "AES"), IvParameterSpec("0102030405060708".toByteArray(Charsets.US_ASCII)))
        doFinal(value)
    }

    private fun invalid(): Nothing = throw SourceException(SourceErrorCode.INVALID_RESPONSE, "Invalid wire codec input")
}
