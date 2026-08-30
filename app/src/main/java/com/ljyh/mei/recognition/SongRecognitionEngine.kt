package com.ljyh.mei.recognition

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONObject
import kotlin.coroutines.resumeWithException
import kotlin.math.floor

enum class RecognitionDuration(val seconds: Int?) {
    Quick(3), Balanced(6), Extended(9), Continuous(null)
}

class SongRecognitionRecorder(private val context: Context) {
    suspend fun record(seconds: Int): FloatArray = withContext(Dispatchers.IO) {
        check(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            "Microphone permission is required"
        }
        require(seconds in 1..15)
        val sourceRate = preferredSampleRate()
        val minBuffer = AudioRecord.getMinBufferSize(
            sourceRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minBuffer > 0) { "No microphone input is available" }
        val recorder = createRecorder(sourceRate, minBuffer)
        val targetFrames = sourceRate * seconds
        val samples = ShortArray(targetFrames)
        var offset = 0
        try {
            recorder.startRecording()
            while (offset < targetFrames) {
                ensureActive()
                val read = recorder.read(samples, offset, minOf(minBuffer / 2, targetFrames - offset))
                check(read >= 0) { "Microphone read failed ($read)" }
                offset += read
            }
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }
        resample(samples, offset, sourceRate, 8_000)
    }

    private fun preferredSampleRate(): Int = listOf(48_000, 44_100, 16_000, 8_000).firstOrNull { rate ->
        AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) > 0
    } ?: 44_100

    private fun createRecorder(sampleRate: Int, minBuffer: Int): AudioRecord {
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

    private fun resample(input: ShortArray, count: Int, sourceRate: Int, targetRate: Int): FloatArray {
        val targetCount = floor(count.toDouble() * targetRate / sourceRate).toInt()
        return FloatArray(targetCount) { index ->
            val sourcePosition = index.toDouble() * sourceRate / targetRate
            val left = floor(sourcePosition).toInt().coerceIn(0, count - 1)
            val right = (left + 1).coerceAtMost(count - 1)
            val fraction = (sourcePosition - left).toFloat()
            ((input[left] * (1f - fraction) + input[right] * fraction) / Short.MAX_VALUE)
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
