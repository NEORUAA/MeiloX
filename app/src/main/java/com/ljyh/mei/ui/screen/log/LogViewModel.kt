package com.ljyh.mei.ui.screen.log

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import com.ljyh.mei.di.ApplicationContext
import com.ljyh.mei.runtime.ComponentRuntime
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject



class LogViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val components: ComponentRuntime,
) : ViewModel() {

    // 日志文件列表
    private val _logFiles = MutableStateFlow<List<File>>(emptyList())
    val logFiles = _logFiles.asStateFlow()

    // 当前查看的文件内容
    private val _currentFileContent = MutableStateFlow<String?>(null)
    val currentFileContent = _currentFileContent.asStateFlow()

    // 当前选中的文件对象（用于分享）
    private var currentSelectedFile: File? = null
    private var shareJob: Job? = null

    init {
        loadLogFiles()
    }

    /**
     * 加载日志文件列表
     */
    fun loadLogFiles() {
        viewModelScope.launch(Dispatchers.IO) {
            val logDir = File(context.filesDir, "app_logs")
            val crashDir = File(context.filesDir, "crash_logs")

            val files = mutableListOf<File>()
            // 安全检查文件夹是否存在
            if (logDir.exists()) files.addAll(logDir.listFiles() ?: emptyArray())
            if (crashDir.exists()) files.addAll(crashDir.listFiles() ?: emptyArray())

            // 按最后修改时间降序排列（最新的在最上面）
            files.sortByDescending { it.lastModified() }

            _logFiles.value = files
        }
    }

    /**
     * 读取文件内容
     */
    fun readContent(file: File) {
        currentSelectedFile = file // 记录当前选中的文件
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 读取文件文本，如果文件太大建议限制读取长度，防止OOM
                val text = file.readText()
                _currentFileContent.value = text
            } catch (e: Exception) {
                _currentFileContent.value = "读取文件失败: ${e.message}"
            }
        }
    }

    /**
     * 关闭详情页
     */
    fun closeContent() {
        _currentFileContent.value = null
        currentSelectedFile = null
    }

    /**
     * 分享当前打开的日志文件
     */
    fun shareCurrentFile() {
        val file = currentSelectedFile ?: return
        shareJob?.cancel()
        shareJob = viewModelScope.launch {
            try {
                val uri = withContext(Dispatchers.IO) { components.logShareUri(context, file) }

                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    // 赋予临时读权限
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                val chooser = Intent.createChooser(intent, "分享日志给开发者")
                // 在非 Activity 环境启动 Activity 需要这个 Flag
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(chooser)

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
                // 可以在这里通过 Toast 提示用户分享失败，或者通过 EventFlow 发送 UI 事件
            }
        }
    }

    /**
     * 清空所有日志
     */
    fun clearAllLogs() {
        val pendingShare = shareJob
        pendingShare?.cancel()
        viewModelScope.launch(Dispatchers.IO) {
            pendingShare?.join()
            _logFiles.value.forEach {
                try { it.delete() } catch (e: Exception) { e.printStackTrace() }
            }
            try { components.clearLogShares(context) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { e.printStackTrace() }
            loadLogFiles() // 刷新列表
        }
    }
}
