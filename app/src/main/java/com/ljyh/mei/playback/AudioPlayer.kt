package com.ljyh.mei.playback


import android.animation.Animator
import android.animation.ValueAnimator
import android.media.MediaPlayer
import android.view.animation.LinearInterpolator
import androidx.media3.common.Player

/**
 * 音频播放器（部分源码，淡入淡出部分）
 * 由 MediaPlayer 拓展，使用很简单
 *
 * AudioPlayer (Part of the source code)
 * The android.media.MediaPlayer extensions for audio play.
 * It is very easy to use.
 *
 * @version 20210730
 * @author Moriafly
 * @since 2021/07/26
 */
class AudioPlayer(
    private val player: Player,
    private val shouldSuppressVolumeWrites: () -> Boolean = { false },
): MediaPlayer() {

    private var volume = player.volume

    var volumeSmoothDuration: Long = 500L
        set(value) {
            pauseSmoothValueAnimator.duration = value
            startSmoothValueAnimator.duration = value
            field = value
        }

    private fun setExoPlayerVolume(volume: Float) {
        if (shouldSuppressVolumeWrites()) return
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
                    if (!shouldSuppressVolumeWrites()) {
                        player.pause()
                    }
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
                if (!shouldSuppressVolumeWrites()) {
                    player.playWhenReady = true
                }
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

    override fun isPlaying(): Boolean {
        if (isPauseSmoothing) {
            return false
        }
        if (isStartSmoothing) {
            return true
        }
        return player.isPlaying
    }

    fun pauseSmooth() {
        val currentVolume = player.volume
        startSmoothValueAnimator.cancel()
        pauseSmoothValueAnimator.cancel()
        pauseAnimationCancelled = false
        val (start, target) = audioVolumeAnimationRange(currentVolume, 0F)
        if (!audioVolumeAnimationNeeded(start, target)) {
            setExoPlayerVolume(target)
            if (!shouldSuppressVolumeWrites()) {
                player.pause()
            }
            isPauseSmoothing = false
            return
        }
        pauseSmoothValueAnimator.setFloatValues(start, target)
        isPauseSmoothing = true
        pauseSmoothValueAnimator.start()
    }

    fun startSmooth() {
        val currentVolume = player.volume
        pauseSmoothValueAnimator.cancel()
        startSmoothValueAnimator.cancel()
        startAnimationCancelled = false
        val (start, target) = audioVolumeAnimationRange(currentVolume, 1F)
        if (!audioVolumeAnimationNeeded(start, target)) {
            setExoPlayerVolume(target)
            if (!shouldSuppressVolumeWrites()) {
                player.playWhenReady = true
            }
            isStartSmoothing = false
            return
        }
        startSmoothValueAnimator.setFloatValues(start, target)
        isStartSmoothing = true
        startSmoothValueAnimator.start()
    }

    override fun setVolume(leftVolume: Float, rightVolume: Float) {
        super.setVolume(
            leftVolume * leftChannel,
            rightVolume * rightChannel
        )

        volume = leftVolume
        setExoPlayerVolume(leftVolume)
    }

    override fun reset() {
        pauseSmoothValueAnimator.cancel()
        startSmoothValueAnimator.cancel()
        isPauseSmoothing = false
        isStartSmoothing = false
        super.reset()
    }

}

internal fun audioVolumeAnimationRange(currentVolume: Float, targetVolume: Float): Pair<Float, Float> =
    currentVolume.coerceIn(0F, 1F) to targetVolume.coerceIn(0F, 1F)

internal fun audioVolumeAnimationNeeded(startVolume: Float, targetVolume: Float): Boolean =
    kotlin.math.abs(startVolume - targetVolume) > 0.001F
