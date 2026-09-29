package com.ljyh.mei.parasite

import com.ljyh.mei.data.network.QQMusicUApiService
import com.ljyh.mei.data.network.api.ApiService
import com.ljyh.mei.data.network.api.AudioMatchService
import com.ljyh.mei.data.network.api.EApiService
import com.ljyh.mei.data.network.api.MeloXDirectService
import com.ljyh.mei.data.network.api.WeApiService
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method
import kotlin.coroutines.Continuation

/** Restore omitted continuation annotation slots observed when module DEX runs in the TV host. */
internal object HostRetrofitCompatibility {
    fun install(module: XposedModule, report: (String) -> Unit) {
        val services = setOf(ApiService::class.java, WeApiService::class.java, EApiService::class.java,
            MeloXDirectService::class.java, AudioMatchService::class.java, QQMusicUApiService::class.java)
        module.hook(Method::class.java.getMethod("getParameterAnnotations")).intercept { chain ->
            val result = chain.proceed()
            val method = chain.thisObject as Method
            if (method.declaringClass !in services) return@intercept result
            @Suppress("UNCHECKED_CAST")
            val original = result as Array<Array<Annotation>>
            completeSuspendAnnotations(method, original).also {
                if (it !== original) report("retrofit_annotations_padded parameters=${it.size} annotations=${original.size}")
            }
        }
    }
}

internal fun completeSuspendAnnotations(method: Method, original: Array<Array<Annotation>>): Array<Array<Annotation>> {
    val parameters = method.parameterTypes
    if (parameters.lastOrNull() != Continuation::class.java || original.size != parameters.size - 1) return original
    return Array(parameters.size) { index -> original.getOrElse(index) { emptyArray() } }
}
