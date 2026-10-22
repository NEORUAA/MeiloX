package com.ljyh.mei.playback


import android.animation.Animator
import android.animation.ValueAnimator
import android.view.animation.LinearInterpolator
import androidx.media3.common.Player

/**
 * Controls Media3 volume fades without allocating an unused platform MediaPlayer.
 *
 * These fades observe playWhenReady changes; playback commands belong to the caller.
 * Reissuing pause() after audio focus loss changes the reason to USER_REQUEST, which
 * Media3 corrects back to AUDIO_FOCUS_LOSS and can trigger an endless callback loop.
 *
 * @version 20210730
 * @author Moriafly
 * @since 2021/07/26
 */
class AudioPlayer(
    private val player: Player,
    private val shouldSuppressVolumeWrites: () -> Boolean = { false },
) {

    private var volume = player.volume
    private var released = false

    var volumeSmoothDuration: Long = 500L
        set(value) {
            pauseSmoothValueAnimator.duration = value
            startSmoothValueAnimator.duration = value
            field = value
        }

    private fun setExoPlayerVolume(volume: Float) {
        if (released || shouldSuppressVolumeWrites()) return
        player.volume = volume
    }

    private var pauseAnimationCancelled = false

    private val pauseSmoothValueAnimator = ValueAnimator.ofFloat(1F, 0F).apply {
        duration = volumeSmoothDuration
        interpolator = LinearInterpolator()
        addUpdateListener {
            volume = it.animatedValue as Float
            try {
//                setVolume(volume, volume)
                setExoPlayerVolume(volume)
            } catch (e: Exception) {
                it.cancel()
            }
        }
        addListener(object : Animator.AnimatorListener {
            override fun onAnimationStart(animation: Animator) {
                pauseAnimationCancelled = false
            }

            override fun onAnimationEnd(animation: Animator) {
                if (!pauseAnimationCancelled) {
                    setExoPlayerVolume(0F)
                }
                isPauseSmoothing = false
            }

            override fun onAnimationCancel(animation: Animator) {
                pauseAnimationCancelled = true
                isPauseSmoothing = false
            }

            override fun onAnimationRepeat(animation: Animator) { }
        })
    }

    private var startAnimationCancelled = false

    private val startSmoothValueAnimator = ValueAnimator.ofFloat(0F, 1F).apply {
        duration = volumeSmoothDuration
        interpolator = LinearInterpolator()
        addUpdateListener {
            volume = it.animatedValue as Float
            try {
//                setVolume(volume, volume)
                setExoPlayerVolume(volume)
            } catch (e: Exception) {
                it.cancel()
            }
        }
        addListener(object : Animator.AnimatorListener {
            override fun onAnimationStart(animation: Animator) {
                startAnimationCancelled = false
            }

            override fun onAnimationEnd(animation: Animator) {
                if (!startAnimationCancelled) {
                    setExoPlayerVolume(1F)
                }
                isStartSmoothing = false
            }

            override fun onAnimationCancel(animation: Animator) {
                startAnimationCancelled = true
                isStartSmoothing = false
            }

            override fun onAnimationRepeat(animation: Animator) { }
        })
    }

    var leftChannel: Float = 1F

    var rightChannel: Float = 1F

    private var isPauseSmoothing: Boolean = false

    private var isStartSmoothing: Boolean = false

    fun isPlaying(): Boolean {
        if (released) return false
        if (isPauseSmoothing) {
            return false
        }
        if (isStartSmoothing) {
            return true
        }
        return player.isPlaying
    }

    fun pauseSmooth() {
        if (released) return
        val currentVolume = player.volume
        startSmoothValueAnimator.cancel()
        pauseSmoothValueAnimator.cancel()
        pauseAnimationCancelled = false
        val (start, target) = audioVolumeAnimationRange(currentVolume, 0F)
        if (!audioVolumeAnimationNeeded(start, target)) {
            setExoPlayerVolume(target)
            isPauseSmoothing = false
            return
        }
        pauseSmoothValueAnimator.setFloatValues(start, target)
        isPauseSmoothing = true
        pauseSmoothValueAnimator.start()
    }

    fun startSmooth() {
        if (released) return
        val currentVolume = player.volume
        pauseSmoothValueAnimator.cancel()
        startSmoothValueAnimator.cancel()
        startAnimationCancelled = false
        val (start, target) = audioVolumeAnimationRange(currentVolume, 1F)
        if (!audioVolumeAnimationNeeded(start, target)) {
            setExoPlayerVolume(target)
            isStartSmoothing = false
            return
        }
        startSmoothValueAnimator.setFloatValues(start, target)
        isStartSmoothing = true
        startSmoothValueAnimator.start()
    }

    fun setVolume(leftVolume: Float, @Suppress("UNUSED_PARAMETER") rightVolume: Float) {
        volume = leftVolume
        setExoPlayerVolume(leftVolume)
    }

    fun reset() {
        pauseSmoothValueAnimator.cancel()
        startSmoothValueAnimator.cancel()
        isPauseSmoothing = false
        isStartSmoothing = false
    }

    fun release() {
        released = true
        reset()
    }

}

internal fun audioVolumeAnimationRange(currentVolume: Float, targetVolume: Float): Pair<Float, Float> =
    currentVolume.coerceIn(0F, 1F) to targetVolume.coerceIn(0F, 1F)

internal fun audioVolumeAnimationNeeded(startVolume: Float, targetVolume: Float): Boolean =
    kotlin.math.abs(startVolume - targetVolume) > 0.001F
