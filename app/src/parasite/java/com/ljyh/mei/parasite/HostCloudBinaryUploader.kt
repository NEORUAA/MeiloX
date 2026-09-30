package com.ljyh.mei.parasite

import com.ljyh.mei.data.repository.CloudBinaryUploader
import com.ljyh.mei.data.repository.CloudUploadAuthorization
import com.ljyh.mei.data.repository.CloudUploadFile
import com.ljyh.mei.data.session.SessionStamp
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal fun interface HostNosUploadBackend {
    fun upload(file: CloudUploadFile, authorization: CloudUploadAuthorization, canceled: () -> Boolean, onProgress: (Long, Long) -> Unit): Int
}

@Singleton
class HostCloudBinaryUploader @Inject constructor(private val sessions: HostSessionBridge) : CloudBinaryUploader {
    @Volatile private var backend: HostNosUploadBackend? = null

    @Synchronized internal fun bind(backend: HostNosUploadBackend) {
        check(this.backend == null) { "Official file uploader is already bound" }
        this.backend = backend
    }

    override suspend fun upload(file: CloudUploadFile, authorization: CloudUploadAuthorization, owner: SessionStamp, onProgress: (Long, Long) -> Unit) = withContext(Dispatchers.IO) {
        val context = currentCoroutineContext()
        fun checkOwner() {
            context.ensureActive()
            sessions.requireCurrent(owner)
            check(!sessions.recoveryRequired.value) { "Session recovery is required" }
        }
        checkOwner()
        val upload = backend ?: throw IOException("Official file uploader is unavailable")
        val result = try {
            upload.upload(file, authorization, { runCatching { checkOwner() }.isFailure }) { sent, total ->
                checkOwner()
                sessions.withCurrent(owner) { onProgress(sent, total) }
            }
        } catch (error: Exception) {
            checkOwner()
            throw error
        }
        checkOwner()
        check(result == 1) { "Official file transfer failed ($result)" }
    }
}
