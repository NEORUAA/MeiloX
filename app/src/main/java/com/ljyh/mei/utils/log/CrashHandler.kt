package com.ljyh.mei.utils.log

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.collections.iterator
import kotlin.system.exitProcess

object CrashHandler : Thread.UncaughtExceptionHandler {

    private const val TAG = "CrashHandler"
    private var mDefaultHandler: Thread.UncaughtExceptionHandler? = null
    private lateinit var mContext: Context

    // 日志文件文件夹名称
    private const val LOG_DIR_NAME = "crash_logs"

    fun init(context: Context) {
        mContext = context.applicationContext
        if (Thread.getDefaultUncaughtExceptionHandler() === this) return
        // 获取系统默认的UncaughtException处理器
        mDefaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        // 设置该CrashHandler为程序的默认处理器
        Thread.setDefaultUncaughtExceptionHandler(this)
    }

    /**
     * 当 UncaughtException 发生时会转入该函数来处理
     */
    override fun uncaughtException(thread: Thread, ex: Throwable) {
        try {
            handleException(ex)
        } catch (loggingFailure: Throwable) {
            // Log.println survives the release rules that strip Log.e calls.
            Log.println(Log.ERROR, TAG, "Failed to save crash report: $loggingFailure")
        } finally {
            // Preserve the original exception in Android's fatal crash report.
            val defaultHandler = mDefaultHandler
            if (defaultHandler != null && defaultHandler !== this) {
                defaultHandler.uncaughtException(thread, ex)
            } else {
                System.err.println("Uncaught exception in thread ${thread.name}")
                ex.printStackTrace()
            }
            Process.killProcess(Process.myPid())
            exitProcess(1)
        }
    }

    /**
     * 自定义错误处理，收集错误信息，发送错误报告等操作均在此完成.
     * @return true: 如果处理了该异常信息; otherwise false.
     */
    private fun handleException(ex: Throwable?): Boolean {
        if (ex == null) return false

        // 1. (可选) 使用 Toast 来显示异常信息，但在 Compose 或非 UI 线程中直接弹 Toast 可能崩溃
        // 这里建议只做日志保存，重启后提示用户

        // 2. 收集设备参数信息
        val infos = collectDeviceInfo(mContext)

        // 3. 保存日志文件
        saveCrashInfo2File(ex, infos)

        return true
    }

    /**
     * 收集设备参数信息
     */
    private fun collectDeviceInfo(context: Context): Map<String, String> {
        val infos = HashMap<String, String>()
        try {
            val pm = context.packageManager
            val pi = pm.getPackageInfo(context.packageName, PackageManager.GET_ACTIVITIES)
            if (pi != null) {
                val versionName = pi.versionName ?: "null"
                val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    pi.longVersionCode.toString()
                } else {
                    @Suppress("DEPRECATION")
                    pi.versionCode.toString()
                }
                infos["versionName"] = versionName
                infos["versionCode"] = versionCode
            }
        } catch (e: PackageManager.NameNotFoundException) {
            Log.e(TAG, "an error occured when collect package info", e)
        }

        infos["MODEL"] = Build.MODEL
        infos["DEVICE"] = Build.DEVICE
        infos["MANUFACTURER"] = Build.MANUFACTURER
        infos["ANDROID_VERSION"] = Build.VERSION.RELEASE
        infos["SDK_INT"] = Build.VERSION.SDK_INT.toString()

        return infos
    }

    /**
     * 保存错误信息到文件中
     */
    private fun saveCrashInfo2File(ex: Throwable, infos: Map<String, String>): String? {
        val sb = StringBuffer()
        // 拼接设备信息
        for ((key, value) in infos) {
            sb.append("$key=$value\n")
        }

        // 拼接堆栈信息
        val writer = StringWriter()
        val printWriter = PrintWriter(writer)
        ex.printStackTrace(printWriter)
        printWriter.close()
        val result = writer.toString()
        sb.append(result)

        try {
            val timestamp = System.currentTimeMillis()
            val formatter = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.getDefault())
            val time = formatter.format(Date())
            val fileName = "crash-$time-$timestamp.log"

            // Store reports in the app's private files directory.
            val logDir = File(mContext.filesDir, LOG_DIR_NAME)
            if (!logDir.exists()) {
                logDir.mkdirs()
            }

            val file = File(logDir, fileName)
            FileOutputStream(file).use { it.write(sb.toString().toByteArray()) }

            Log.e(TAG, "Crash log saved to: ${file.absolutePath}")
            return fileName
        } catch (e: Exception) {
            Log.e(TAG, "an error occured while writing file...", e)
        }
        return null
    }
}
