package com.ljyh.mei.parasite

import android.app.IntentService
import android.app.Service
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.impl.background.systemjob.SystemJobScheduler
import androidx.work.impl.background.systemjob.SystemJobService
import androidx.work.impl.utils.PackageManagerHelper
import io.github.libxposed.api.XposedInterface.HookHandle
import io.github.libxposed.api.XposedModule
import java.util.WeakHashMap

/** Isolated module work on a verified, non-exported official service carrier. */
internal object HostWorkManager {
    private val delegates = WeakHashMap<Service, HostWorkJobService>()
    private var initialized = false
    private var installed = false
    private var report: (String) -> Unit = {}

    fun install(module: XposedModule, context: ModuleContext, logger: (String) -> Unit) {
        check(!installed)
        val carrier = ComponentName(HostIdentity.PACKAGE, HostWorkPolicy.CARRIER)
        val info = context.packageManager.getServiceInfo(carrier, PackageManager.ComponentInfoFlags.of(0))
        check(info.enabled && !info.exported && info.permission == "android.permission.BIND_JOB_SERVICE" &&
            info.processName == HostIdentity.PACKAGE) { "Official work carrier is unavailable" }
        report = logger
        val hooks = mutableListOf<HookHandle>()
        try {
            installHooks(module, context, carrier, logger, hooks)
        } catch (error: Throwable) {
            hooks.asReversed().forEach { runCatching { it.unhook() } }
            throw error
        }
        installed = true
        report("work_hooks_ready")
    }

    private fun installHooks(
        module: XposedModule, context: ModuleContext, carrier: ComponentName,
        logger: (String) -> Unit, hooks: MutableList<HookHandle>,
    ) {
        val loader = SystemJobScheduler::class.java.classLoader!!
        val converter = loader.loadClass("androidx.work.impl.background.systemjob.SystemJobInfoConverter")
        val component = converter.getDeclaredField("mWorkServiceComponent").apply { isAccessible = true }
        hooks += module.hook(converter.declaredConstructors.single()).intercept { chain ->
            val result = chain.proceed()
            if (isModuleContext(chain.getArg(0) as Context)) component.set(chain.thisObject, carrier)
            result
        }
        hooks += module.hook(converter.declaredMethods.single { it.name == "convert" }).intercept { chain ->
            val job = chain.proceed() as JobInfo
            if (job.service == carrier) {
                check(job.id in HostWorkPolicy.MIN_JOB_ID..HostWorkPolicy.MAX_JOB_ID)
                if (Build.VERSION.SDK_INT < 34) {
                    val existing = context.getSystemService(JobScheduler::class.java).getPendingJob(job.id)
                    check(existing == null || owns(existing, null)) { "Official job ID collision" }
                }
                job.extras.putString(HostWorkPolicy.MARKER_KEY, HostWorkPolicy.MARKER_VALUE)
            }
            job
        }
        val extensions = loader.loadClass("androidx.work.impl.background.systemjob.JobSchedulerExtKt")
        hooks += module.hook(extensions.getDeclaredMethod("getWmJobScheduler", Context::class.java)).intercept { chain ->
            val owner = chain.getArg(0) as Context
            if (isModuleContext(owner) && Build.VERSION.SDK_INT >= 34) {
                owner.getSystemService(JobScheduler::class.java).forNamespace(HostWorkPolicy.NAMESPACE)
            } else chain.proceed()
        }
        hooks += module.hook(SystemJobScheduler::class.java.getDeclaredMethod("getPendingJobs", Context::class.java, JobScheduler::class.java))
            .intercept { chain ->
                if (!isModuleContext(chain.getArg(0) as Context)) return@intercept chain.proceed()
                val scheduler = chain.getArg(1) as JobScheduler
                val namespace = if (Build.VERSION.SDK_INT >= 34) scheduler.namespace else null
                try { scheduler.allPendingJobs.filter { owns(it, namespace) } }
                catch (_: Exception) { null }
            }
        hooks += module.hook(PackageManagerHelper::class.java.getMethod("setComponentEnabled", Context::class.java, Class::class.java, Boolean::class.javaPrimitiveType))
            .intercept { chain ->
                val name = (chain.getArg(1) as Class<*>).name
                if (isModuleContext(chain.getArg(0) as Context) && name in setOf(
                        SystemJobService::class.java.name, "androidx.work.impl.background.systemalarm.RescheduleReceiver")) null
                else chain.proceed()
            }

        // Mirror Android's attachment into a delegate, never replace the original IntentService.
        val attach = Service::class.java.declaredMethods.single { it.name == "attach" && it.parameterCount == 6 }
            .apply { isAccessible = true }
        hooks += module.hook(attach).intercept { chain ->
            val result = chain.proceed()
            val service = chain.thisObject as Service
            if (service.javaClass.name == HostWorkPolicy.CARRIER) {
                val delegate = HostWorkJobService(logger)
                attach.invoke(delegate, *chain.args.toTypedArray())
                delegates[service] = delegate
                report("work_carrier_attached original_preserved=true")
            }
            result
        }
        hooks += module.hook(IntentService::class.java.getMethod("onCreate")).intercept { chain ->
            val result = chain.proceed()
            delegates[chain.thisObject as Service]?.onCreate()
            result
        }
        hooks += module.hook(IntentService::class.java.getMethod("onBind", Intent::class.java)).intercept { chain ->
            val original = chain.proceed()
            val intent = chain.getArg(0) as? Intent
            val delegate = delegates[chain.thisObject as Service]
            if (original == null && delegate != null && intent?.component == carrier && intent.action == null) {
                report("work_carrier_bound original_returned_null=true")
                delegate.onBind(intent)
            } else original
        }
        hooks += module.hook(IntentService::class.java.getMethod("onDestroy")).intercept { chain ->
            try { chain.proceed() }
            finally {
                delegates.remove(chain.thisObject as Service)?.let { delegate ->
                    runCatching { delegate.onDestroy() }
                    report("work_carrier_destroyed original_preserved=true")
                }
            }
        }
    }

    @Synchronized fun initialize(context: Context) {
        check(installed && isModuleContext(context))
        if (initialized) return
        WorkManager.initialize(context, Configuration.Builder()
            .setDefaultProcessName(HostIdentity.PACKAGE)
            .setJobSchedulerJobIdRange(HostWorkPolicy.MIN_JOB_ID, HostWorkPolicy.MAX_JOB_ID)
            .setMinimumLoggingLevel(Log.WARN)
            .build())
        initialized = true
        report("work_manager_initialized isolated_storage=true")
        com.ljyh.mei.utils.DownloadManager.recover(context)
    }

    private fun isModuleContext(context: Context) = context is ModuleContext && context.packageName == HostIdentity.PACKAGE

    internal fun owns(job: JobInfo, namespace: String?): Boolean = HostWorkPolicy.owns(
        job.service.packageName, job.service.className, job.id,
        job.extras.getString(HostWorkPolicy.MARKER_KEY), job.extras.getString(HostWorkPolicy.WORK_ID),
        job.extras.getInt(HostWorkPolicy.WORK_GENERATION, -1), namespace, Build.VERSION.SDK_INT,
    )
}

internal class HostWorkJobService(private val report: (String) -> Unit) : SystemJobService() {
    override fun attachBaseContext(base: Context) = super.attachBaseContext(HostRuntimeProbe.wrap(base))

    private fun owns(params: JobParameters): Boolean = HostWorkPolicy.owns(
        packageName, HostWorkPolicy.CARRIER, params.jobId,
        params.extras.getString(HostWorkPolicy.MARKER_KEY), params.extras.getString(HostWorkPolicy.WORK_ID),
        params.extras.getInt(HostWorkPolicy.WORK_GENERATION, -1),
        if (Build.VERSION.SDK_INT >= 34) params.jobNamespace else null, Build.VERSION.SDK_INT,
    )

    override fun onStartJob(params: JobParameters): Boolean {
        if (!owns(params)) return false
        report("work_job_started owner_verified=true")
        return super.onStartJob(params)
    }

    override fun onStopJob(params: JobParameters): Boolean {
        if (!owns(params)) return false
        report("work_job_stopped owner_verified=true")
        return super.onStopJob(params)
    }
}
