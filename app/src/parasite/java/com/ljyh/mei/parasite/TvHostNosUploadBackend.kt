package com.ljyh.mei.parasite

import android.util.Pair
import com.ljyh.mei.data.repository.CloudUploadAuthorization
import com.ljyh.mei.data.repository.CloudUploadFile
import java.io.File
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/** TV 1.1.80 NOS SDK. No module OkHttp or Kotlin object enters the host SDK. */
internal class TvHostNosUploadBackend(private val loader: ClassLoader) : HostNosUploadBackend {
    private val uploaderType = loader.loadClass("com.netease.cloudmusic.core.upload.c")
    private val providerType = loader.loadClass("com.netease.cloudmusic.core.upload.b")
    private val objectType = loader.loadClass("com.netease.cloudmusic.core.upload.k")
    private val optionsType = loader.loadClass("com.netease.cloudmusic.core.upload.l")
    private val builderType = loader.loadClass("com.netease.cloudmusic.core.upload.l\$b")
    private val progressType = loader.loadClass("com.netease.cloudmusic.core.n.b")
    private val cancelType = loader.loadClass("com.netease.cloudmusic.core.n.c")
    private val service = loader.loadClass("com.netease.cloudmusic.common.ServiceFacade").getMethod("get", Class::class.java)
    private val upload = uploaderType.getMethod("upload", File::class.java, providerType, optionsType)
    private val setters = listOf("p", "y", "z", "r").associateWith { objectType.getMethod(it, String::class.java) }
    private val progress = builderType.getMethod("n", progressType)
    private val cancellation = builderType.getMethod("o", cancelType)
    private val cache = builderType.getMethod("k", Boolean::class.javaPrimitiveType)
    private val immutableServer = builderType.getMethod("l", Boolean::class.javaPrimitiveType)
    private val build = builderType.getMethod("a")

    override fun upload(file: CloudUploadFile, authorization: CloudUploadAuthorization, canceled: () -> Boolean, onProgress: (Long, Long) -> Unit): Int {
        if (canceled()) throw IOException("Official upload canceled")
        val uploader = invoke(service, null, uploaderType) ?: throw IOException("Official uploader is not initialized")
        val uploadObject = objectType.getConstructor().newInstance()
        listOf("p" to authorization.bucket, "y" to authorization.objectKey, "z" to authorization.token, "r" to file.mimeType)
            .forEach { (name, value) -> invoke(setters.getValue(name), uploadObject, value) }
        val provider = proxy(providerType) { method, args -> when (method.name) {
            "a" -> {
                check(!canceled()) { "Official upload canceled" }
                check(args.single() == file.md5) { "Cloud upload file changed" }
                uploadObject
            }
            // The TV cloud token-refresh helper is a stub. Do not retry with another account/token.
            "b" -> null
            else -> error("Unexpected official upload callback")
        } }
        val options = builderType.getConstructor().newInstance()
        invoke(cache, options, false)
        invoke(immutableServer, options, true)
        invoke(progress, options, proxy(progressType) { _, args -> onProgress(args[0] as Long, args[1] as Long); null })
        invoke(cancellation, options, proxy(cancelType) { _, _ -> canceled() })
        val result = invoke(upload, uploader, file.file, provider, invoke(build, options)) as? Pair<*, *>
            ?: throw IOException("Official uploader returned no result")
        return result.first as? Int ?: throw IOException("Invalid official upload result")
    }

    private fun proxy(type: Class<*>, callback: (Method, Array<out Any?>) -> Any?): Any =
        Proxy.newProxyInstance(loader, arrayOf(type)) { instance, method, args ->
            when (method.name) {
                "toString" -> "MeiloX upload callback"
                "hashCode" -> System.identityHashCode(instance)
                "equals" -> instance === args?.firstOrNull()
                else -> callback(method, args.orEmpty())
            }
        }

    private fun invoke(method: Method, receiver: Any?, vararg args: Any?): Any? = try {
        method.invoke(receiver, *args)
    } catch (error: Exception) {
        val cause = if (error is InvocationTargetException) error.targetException else error
        throw IOException("Official upload failed: ${cause.javaClass.simpleName}")
    }
}
