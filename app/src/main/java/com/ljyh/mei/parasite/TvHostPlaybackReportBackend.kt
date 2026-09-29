package com.ljyh.mei.parasite

import com.ljyh.mei.data.session.SessionStamp
import io.github.libxposed.api.XposedInterface.HookHandle
import io.github.libxposed.api.XposedModule
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/** TV 1.1.80's two existing reporting channels; no host Kotlin/JSON types escape. */
internal class TvHostPlaybackReportBackend(
    private val loader: ClassLoader,
    private val bridge: HostPlaybackReportBridge,
    private val report: (String) -> Unit,
) : HostPlaybackReportBackend {
    private val jsonType = loader.loadClass("com.alibaba.fastjson.JSONObject")
    private val jsonConstructor = jsonType.getConstructor(Map::class.java)
    private val statisticType = loader.loadClass("com.netease.cloudmusic.core.statistic.IStatistic")
    private val getService = loader.loadClass("com.netease.cloudmusic.common.ServiceFacade")
        .getMethod("get", Class::class.java)
    private val legacy = statisticType.getMethod("logJSONWithMspm", String::class.java, String::class.java, jsonType)
    private val biType = loader.loadClass("com.netease.cloudmusic.bilog.k.d")
    private val biFactory = checkNotNull(biType.getField("a").get(null))
    private val biStart = biFactory.javaClass.getMethod("d")
    private val biEnd = biFactory.javaClass.getMethod("c")
    private val biFields = biType.getMethod("d", Map::class.java)
    private val biPublish = biType.getMethod("a")
    private val biEnabled = loader.loadClass("com.netease.cloudmusic.h").getMethod("e")
    private val dataReportType = loader.loadClass("com.netease.cloudmusic.s0.l.a")
    private val dataReportInstance = dataReportType.getMethod("A")
    private val dataReportReady = dataReportType.getMethod("C")
    private val ownership = PlaybackReportOwnership(bridge)

    fun installHooks(module: XposedModule) {
        val encrypted = loader.loadClass("com.netease.cloudmusic.core.statistic.j1")
            .getDeclaredMethod("r", String::class.java, jsonType)
        val legacyWorker = loader.loadClass("com.netease.cloudmusic.core.statistic.p0")
            .getDeclaredMethod("I", String::class.java, jsonType, Long::class.javaPrimitiveType)
        val hooks = mutableListOf<HookHandle>()
        try {
            for (method in listOf(encrypted, legacyWorker)) {
                hooks += module.hook(method).intercept { chain ->
                    val action = chain.getArg(0) as? String
                    if (action !in PlaybackReportOwnership.ACTIONS) return@intercept chain.proceed()
                    @Suppress("UNCHECKED_CAST")
                    val fields = chain.getArg(1) as? MutableMap<String, Any?> ?: return@intercept null
                    val owner = ownership.consume(checkNotNull(action), fields)
                    if (owner == null) {
                        report("official_playback_report_dropped action=$action")
                        return@intercept null
                    }
                    val result = chain.proceed()
                    report("official_playback_report_processed action=$action seconds=${fields["time"] ?: 0} " +
                        "start=${fields["startlogtime"]} generation=${owner.generation}")
                    result
                }
            }
        } catch (error: Throwable) {
            hooks.asReversed().forEach { runCatching { it.unhook() } }
            throw error
        }
        report("official_playback_report_hooks_ready")
    }

    override fun emit(action: String, fields: Map<String, Any>, owner: SessionStamp) {
        bridge.requireOwner(owner)
        val statistic = invoke(getService, null, statisticType)
            ?: throw IOException("Official statistic service is unavailable")
        val enabled = invoke(biEnabled, null) == true
        if (enabled) {
            check(invoke(dataReportReady, invoke(dataReportInstance, null)) == true) {
                "Official BI reporter is not initialized"
            }
            val biAction = if (action == "startplay") "_plv" else "_pld"
            val event = invoke(if (action == "startplay") biStart else biEnd, biFactory)
                ?: throw IOException("Official BI event unavailable")
            invoke(biFields, event, ownership.mark(biAction, fields, owner))
            invoke(biPublish, event)
        }
        bridge.requireOwner(owner)
        val json = jsonConstructor.newInstance(ownership.mark(action, fields, owner))
        invoke(legacy, statistic, action, "5f3ab1eab1b200b0c2e37ee6", json)
        report("official_playback_report_enqueued action=$action bi=$enabled")
    }

    private fun invoke(method: Method, receiver: Any?, vararg arguments: Any?): Any? = try {
        method.invoke(receiver, *arguments)
    } catch (error: Exception) {
        val cause = if (error is InvocationTargetException) error.targetException else error
        throw IOException("Official reporting failed: ${cause.javaClass.simpleName}")
    }
}
