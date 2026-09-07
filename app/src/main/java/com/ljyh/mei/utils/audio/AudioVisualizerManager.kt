package com.ljyh.mei.utils.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.audiofx.Visualizer
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.pow

class AudioVisualizerManager(private val context: Context) {

    private var visualizer: Visualizer? = null
    @Volatile
    private var captureEnabled = false
    
    // EMA Smoothing factor (from SPlayer spec)
    private val smoothingFactor = 0.28f
    private val threshold = 180f / 255f
    
    private val _bassValue = MutableStateFlow(0f)
    val bassValue: StateFlow<Float> = _bassValue.asStateFlow()

    fun attachToPlayer(audioSessionId: Int) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.w("AudioVisualizer", "RECORD_AUDIO permission not granted. Visualizer will not attach.")
            return
        }

        release()

        try {
            visualizer = Visualizer(audioSessionId).apply {
                captureSize = 512
                setDataCaptureListener(
                    object : Visualizer.OnDataCaptureListener {
                        override fun onWaveFormDataCapture(
                            visualizer: Visualizer,
                            waveform: ByteArray,
                            samplingRate: Int
                        ) {
                            // Not used for this effect
                        }

                        override fun onFftDataCapture(
                            visualizer: Visualizer,
                            fft: ByteArray,
                            samplingRate: Int
                        ) {
                            processFftData(fft)
                        }
                    },
                    Visualizer.getMaxCaptureRate() / 2,
                    false,
                    true
                )
                enabled = captureEnabled
            }
        } catch (e: Exception) {
            Log.e("AudioVisualizer", "Error initializing Visualizer: \${e.message}")
        }
    }

    fun setCaptureEnabled(enabled: Boolean) {
        if (captureEnabled == enabled) return
        captureEnabled = enabled
        if (!enabled) {
            _bassValue.value = 0f
        }

        val currentVisualizer = visualizer ?: return
        try {
            currentVisualizer.enabled = enabled
        } catch (_: IllegalStateException) {
            // Visualizer may be released concurrently while the host lifecycle changes.
        } catch (_: SecurityException) {
            // Audio capture permission or platform policy can change while paused.
        }
    }

    private fun processFftData(fft: ByteArray) {
        if (!captureEnabled) return
        if (fft.size < 4) return // Not enough data for 3 bins
        
        // SPlayer pulls from the first 3 low-frequency bins.
        // In Android FFT byte array:
        // fft[0] is DC component, fft[1] is Nyquist (often ignored for LF)
        // Bins start at index 2 (real, imag pairs)
        // Bin 1: r=fft[2], i=fft[3]
        // Bin 2: r=fft[4], i=fft[5]
        // Bin 3: r=fft[6], i=fft[7]
        
        var sumMagnitude = 0f
        val binsToAverage = 3
        
        for (i in 0 until binsToAverage) {
            val r = fft[2 + i * 2]
            val im = fft[3 + i * 2]
            // Magnitude = sqrt(r^2 + im^2), normalized to 0-1 range (approx by dividing by 128)
            val magnitude = kotlin.math.hypot(r.toFloat(), im.toFloat()) / 128f
            sumMagnitude += magnitude
        }
        
        val avgMagnitude = sumMagnitude / binsToAverage
        
        // Non-Linear Scaling (Threshold -> Pow -> Max)
        val rawEnergy = (avgMagnitude - threshold).coerceAtLeast(0f)
        val energy = rawEnergy.pow(2)
        
        // EMA Smoothing
        val currentSmoothed = _bassValue.value
        val newSmoothed = currentSmoothed + smoothingFactor * (energy - currentSmoothed)
        
        _bassValue.value = newSmoothed.coerceIn(0f, 1f)
    }

    fun release() {
        visualizer?.let { currentVisualizer ->
            try {
                currentVisualizer.enabled = false
            } catch (_: IllegalStateException) {
                // The platform can invalidate a Visualizer before release is called.
            } catch (_: SecurityException) {
                // Keep lifecycle cleanup safe when capture permission is revoked.
            }
            try {
                currentVisualizer.release()
            } catch (_: IllegalStateException) {
                // The platform can invalidate a Visualizer before release is called.
            } catch (_: SecurityException) {
                // Keep lifecycle cleanup safe when capture permission is revoked.
            }
        }
        visualizer = null
        _bassValue.value = 0f
    }
}
