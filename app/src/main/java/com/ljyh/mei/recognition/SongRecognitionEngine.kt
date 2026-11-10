package com.ljyh.mei.recognition

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.Keep
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONObject
import kotlin.coroutines.resumeWithException
import kotlin.math.floor

class SongRecognitionRecorder(private val context: Context) {
    suspend fun record(seconds: Int, projection: MediaProjection?): FloatArray = withContext(Dispatchers.IO) {
        check(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            "Audio recording permission is required"
        }
        require(seconds in 1..15)
        val channels = if (projection == null) 1 else 2
        val channelMask = if (channels == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO
        val sourceRate = listOf(48_000, 44_100, 16_000, 8_000).firstOrNull { rate ->
            minimumBufferSize(rate, channelMask) > 0
        } ?: error("No compatible audio input format")
        val minBuffer = minimumBufferSize(sourceRate, channelMask)
        val recorder = if (projection == null) createMicrophoneRecorder(sourceRate, minBuffer)
        else createPlaybackRecorder(projection, sourceRate, channelMask, minBuffer)
        val samples = ShortArray(sourceRate * seconds * channels)
        try {
            val chunkSize = (minBuffer / Short.SIZE_BYTES / channels).coerceAtLeast(1) * channels
            val complete = withTimeoutOrNull(seconds * 1_000L + 5_000L) {
                recorder.startRecording()
                check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Audio recording did not start" }
                var offset = 0
                while (offset < samples.size) {
                    ensureActive()
                    val read = recorder.read(
                        samples, offset, minOf(chunkSize, samples.size - offset), AudioRecord.READ_NON_BLOCKING,
                    )
                    check(read >= 0) { "Audio read failed ($read)" }
                    if (read == 0) delay(10) else offset += read
                }
                true
            }
            check(complete == true) { "Audio recording timed out" }
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }
        resample(samples, channels, sourceRate, 8_000)
    }

    private fun minimumBufferSize(rate: Int, channelMask: Int): Int =
        AudioRecord.getMinBufferSize(rate, channelMask, AudioFormat.ENCODING_PCM_16BIT)

    @SuppressLint("MissingPermission")
    private fun createMicrophoneRecorder(sampleRate: Int, minBuffer: Int): AudioRecord {
        val bufferSize = maxOf(minBuffer * 2, sampleRate)
        for (source in listOf(MediaRecorder.AudioSource.UNPROCESSED, MediaRecorder.AudioSource.MIC)) {
            val recorder = runCatching {
                AudioRecord(
                    source,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize,
                )
            }.getOrNull() ?: continue
            if (recorder.state == AudioRecord.STATE_INITIALIZED) return recorder
            recorder.release()
        }
        error("Could not initialize microphone input")
    }

    @SuppressLint("MissingPermission")
    private fun createPlaybackRecorder(
        projection: MediaProjection,
        sampleRate: Int,
        channelMask: Int,
        minBuffer: Int,
    ): AudioRecord {
        val capture = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(channelMask)
            .build()
        return AudioRecord.Builder()
            .setAudioFormat(format)
            .setAudioPlaybackCaptureConfig(capture)
            .setBufferSizeInBytes(minBuffer * 2)
            .build()
    }

    private fun resample(input: ShortArray, channels: Int, sourceRate: Int, targetRate: Int): FloatArray {
        val frames = input.size / channels
        val targetCount = floor(frames.toDouble() * targetRate / sourceRate).toInt()
        fun sample(frame: Int): Float {
            val offset = frame * channels
            return if (channels == 1) input[offset].toFloat()
            else (input[offset] + input[offset + 1]) * 0.5f
        }
        return FloatArray(targetCount) { index ->
            val sourcePosition = index.toDouble() * sourceRate / targetRate
            val left = floor(sourcePosition).toInt().coerceIn(0, frames - 1)
            val right = (left + 1).coerceAtMost(frames - 1)
            val fraction = (sourcePosition - left).toFloat()
            (sample(left) * (1f - fraction) + sample(right) * fraction) / Short.MAX_VALUE
        }
    }
}

class NeteaseFingerprintGenerator(context: Context) {
    private val applicationContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val bridge = FingerprintBridge()
    private val pendingGenerations = mutableMapOf<String, CancellableContinuation<String>>()
    private var webView: WebView? = null
    private var prepared: CompletableDeferred<Unit>? = null
    private var nextRequestId = 0L

    suspend fun generate(samples: FloatArray): String = withContext(Dispatchers.Main) {
        require(samples.isNotEmpty())
        prepare().await()
        val bytes = ByteBuffer.allocate(samples.size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .apply { samples.forEach(::putFloat) }
            .array()
        val pcm = Base64.encodeToString(bytes, Base64.NO_WRAP)
        suspendCancellableCoroutine { continuation ->
            val requestId = (++nextRequestId).toString()
            pendingGenerations[requestId] = continuation
            continuation.invokeOnCancellation {
                mainHandler.post { pendingGenerations.remove(requestId) }
            }
            val script = """
                (function() {
                    try {
                        Promise.resolve(generateFingerprint(${quote(pcm)})).then(
                            function(result) {
                                $BRIDGE_NAME.onSuccess(${quote(requestId)}, String(result));
                            },
                            function(error) {
                                $BRIDGE_NAME.onFailure(${quote(requestId)}, String(error));
                            }
                        );
                    } catch (error) {
                        $BRIDGE_NAME.onFailure(${quote(requestId)}, String(error));
                    }
                })();
            """.trimIndent()
            runCatching {
                webView?.evaluateJavascript(script, null)
                    ?: error("Fingerprint runtime is unavailable")
            }.onFailure { completeGeneration(requestId, Result.failure(it)) }
        }
    }

    fun release() {
        val destroy = Runnable {
            webView?.apply {
                stopLoading()
                loadUrl("about:blank")
                removeJavascriptInterface(BRIDGE_NAME)
                removeAllViews()
                destroy()
            }
            webView = null
            pendingGenerations.values.forEach {
                it.resumeWithException(IllegalStateException("Fingerprint runtime was released"))
            }
            pendingGenerations.clear()
            prepared?.cancel()
            prepared = null
        }
        if (Looper.myLooper() == Looper.getMainLooper()) destroy.run()
        else Handler(Looper.getMainLooper()).post(destroy)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun prepare(): CompletableDeferred<Unit> {
        prepared?.let { return it }
        val deferred = CompletableDeferred<Unit>()
        prepared = deferred
        webView = WebView(applicationContext).apply {
            settings.javaScriptEnabled = true
            settings.allowFileAccess = true
            addJavascriptInterface(bridge, BRIDGE_NAME)
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    if (!deferred.isCompleted) deferred.complete(Unit)
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: android.webkit.WebResourceRequest?,
                    error: android.webkit.WebResourceError?,
                ) {
                    if (request?.isForMainFrame == true && !deferred.isCompleted) {
                        deferred.completeExceptionally(IllegalStateException(error?.description?.toString()))
                    }
                }
            }
            loadUrl("file:///android_asset/audio_fingerprint/index.html")
        }
        return deferred
    }

    private fun completeGeneration(requestId: String, result: Result<String>) {
        val continuation = pendingGenerations.remove(requestId) ?: return
        if (continuation.isActive) continuation.resumeWith(result)
    }

    @Keep
    private inner class FingerprintBridge {
        @JavascriptInterface
        fun onSuccess(requestId: String, fingerprint: String) {
            mainHandler.post {
                val result = runCatching {
                    val decoded = Base64.decode(fingerprint, Base64.DEFAULT)
                    check(decoded.isNotEmpty()) { "The fingerprint runtime returned no data" }
                    fingerprint
                }
                completeGeneration(requestId, result)
            }
        }

        @JavascriptInterface
        fun onFailure(requestId: String, message: String) {
            mainHandler.post {
                completeGeneration(
                    requestId,
                    Result.failure(IllegalStateException("Fingerprint generation failed: $message")),
                )
            }
        }
    }

    private fun quote(value: String): String = JSONObject.quote(value)

    private companion object {
        const val BRIDGE_NAME = "NeteaseFingerprintBridge"
    }
}
