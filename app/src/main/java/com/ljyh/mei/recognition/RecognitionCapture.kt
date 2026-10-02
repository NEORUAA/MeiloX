package com.ljyh.mei.recognition

import android.content.Context
import android.content.Intent

/** A permission grant is consumed by one independently cancellable recording session. */
interface RecognitionCapture : AutoCloseable {
    fun permissionIntent(): Intent? = null
    fun acceptPermission(result: Intent?): Boolean = true
    fun discardPermission(result: Intent?) = close()
    fun open(): RecognitionRecording
    override fun close() = Unit
}

interface RecognitionRecording : AutoCloseable {
    suspend fun record(seconds: Int): FloatArray
    override fun close() = Unit
}

class PlatformRecognitionCapture(context: Context) : RecognitionCapture {
    private val recorder = SongRecognitionRecorder(context)
    override fun open() = object : RecognitionRecording {
        override suspend fun record(seconds: Int) = recorder.record(seconds)
    }
}
