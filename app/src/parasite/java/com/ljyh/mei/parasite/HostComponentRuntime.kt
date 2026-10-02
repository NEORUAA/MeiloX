package com.ljyh.mei.parasite

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.media.session.MediaSession
import android.net.Uri
import androidx.core.content.FileProvider
import com.ljyh.mei.BuildConfig
import com.ljyh.mei.runtime.ComponentRuntime
import com.ljyh.mei.recognition.RecognitionCapture
import com.ljyh.mei.parasite.helper.HostRecognitionCapture
import com.ljyh.mei.parasite.helper.HostLyricsPip
import com.ljyh.mei.runtime.LyricsPipSource
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HostComponentRuntime internal constructor(
    private val enabled: Boolean,
    private val wrap: (Context) -> Context,
    private val report: (String) -> Unit,
) : ComponentRuntime {
    @Inject constructor() : this(
        BuildConfig.PARASITE_APP_ENABLED, HostRuntimeProbe::wrap, { HostRuntimeProbe.report(it) },
    )

    override fun wrapComponent(base: Context): Context =
        if (enabled && base.packageName == HostIdentity.PACKAGE) wrap(base) else base

    override fun recognitionCapture(context: Context): RecognitionCapture {
        require(enabled && context.packageName == HostIdentity.PACKAGE) { "Recognition requires the verified host runtime" }
        return HostRecognitionCapture(context)
    }

    override val usesLyricsPipHelper: Boolean get() = enabled
    override fun enterLyricsPip(activity: Activity, source: LyricsPipSource) {
        require(enabled && activity.packageName == HostIdentity.PACKAGE) { "PiP requires the verified host runtime" }
        HostLyricsPip.enter(activity, source)
    }

    override fun activityCreated(activity: Activity, restored: Boolean) {
        if (enabled && activity.packageName == HostIdentity.PACKAGE) {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            report("app_activity_created restored=$restored")
        }
    }

    override val deferMediaButtonsUntilRestored: Boolean get() = enabled

    override fun consumePlaybackResumeRequest(): Boolean = enabled && HostMediaButtons.consumeResumeRequest()

    override fun bindMediaButtons(context: Context, token: MediaSession.Token): AutoCloseable? =
        if (enabled) HostMediaButtons.bind(context, token) else null

    override fun playbackServiceCreated(sessionCount: Int) {
        if (enabled) report("app_music_service_created sessions=$sessionCount")
    }

    override fun logShareUri(context: Context, file: File): Uri {
        val module = context as? ModuleContext
        require(enabled && module != null && context.packageName == HostIdentity.PACKAGE) {
            "Log sharing requires the verified host runtime"
        }
        val host = module.baseContext
        val staged = HostLogShareFiles.stage(file, module.filesDir, host.cacheDir)
        try {
            return FileProvider.getUriForFile(host, "${host.packageName}.fileprovider", staged)
        } catch (error: Exception) {
            staged.delete()
            staged.parentFile?.delete()
            throw error
        }
    }

    override fun clearLogShares(context: Context) {
        val module = context as? ModuleContext
        require(enabled && module != null && context.packageName == HostIdentity.PACKAGE)
        HostLogShareFiles.clear(module.baseContext.cacheDir)
    }
}

/** The pinned host exposes cache/apk, not the module's private files directory. */
internal object HostLogShareFiles {
    const val RETENTION_MS = 24 * 60 * 60 * 1000L

    fun stage(file: File, moduleFiles: File, hostCache: File, now: Long = System.currentTimeMillis()): File {
        val source = file.canonicalFile
        val roots = listOf("app_logs", "crash_logs").map { File(moduleFiles.canonicalFile, it) }
        require(source.isFile && source.canRead() && roots.any { it == source.parentFile }) {
            "Only module log files can be shared"
        }
        val root = root(hostCache)
        check(root.isDirectory || root.mkdirs() || root.isDirectory) { "Cannot create log share cache" }
        removeCopies(root, now - RETENTION_MS)
        val directory = File(root, UUID.randomUUID().toString())
        check(directory.mkdir()) { "Cannot reserve log share cache" }
        val staged = File(directory, source.name)
        try {
            source.copyTo(staged)
            check(directory.setLastModified(now)) { "Cannot timestamp log share cache" }
            return staged
        } catch (error: Exception) {
            staged.delete()
            directory.delete()
            throw error
        }
    }

    fun clear(hostCache: File) {
        val root = root(hostCache)
        removeCopies(root, Long.MAX_VALUE)
        root.delete()
    }

    private fun root(hostCache: File): File =
        File(hostCache.canonicalFile, "apk/${ModuleStorage.NAMESPACE}_log_share").also {
            require(it.canonicalFile == it.absoluteFile) { "Log share cache cannot contain links" }
        }

    private fun removeCopies(root: File, before: Long) {
        root.listFiles().orEmpty().filter { directory ->
            runCatching { UUID.fromString(directory.name).toString() == directory.name }.getOrDefault(false) &&
                directory.canonicalFile == directory.absoluteFile && directory.isDirectory &&
                directory.lastModified() <= before
        }.forEach { directory ->
            directory.listFiles().orEmpty().filter { it.isFile && it.canonicalFile == it.absoluteFile }.forEach { it.delete() }
            directory.delete()
        }
    }
}
