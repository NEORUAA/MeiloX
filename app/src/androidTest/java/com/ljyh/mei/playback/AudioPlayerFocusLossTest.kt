package com.ljyh.mei.playback

import android.media.AudioManager
import android.os.Handler
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.SilenceMediaSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AudioPlayerFocusLossTest {
    @Test
    fun audioFocusLossDoesNotCreateAPauseFeedbackLoop() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ready = CountDownLatch(1)
        val focusLost = CountDownLatch(1)
        val callbackStorm = CountDownLatch(1)
        val reasons = mutableListOf<Int>()
        lateinit var deck: ExoPlayer
        lateinit var player: ForwardingSimpleBasePlayer
        lateinit var fades: AudioPlayer

        instrumentation.runOnMainSync {
            deck = ExoPlayer.Builder(instrumentation.targetContext)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(),
                    true,
                )
                .build()
            player = ForwardingSimpleBasePlayer(deck)
            fades = AudioPlayer(player).apply { volumeSmoothDuration = 0L }
            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) ready.countDown()
                }

                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    if (playWhenReady) return
                    reasons += reason
                    if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS) {
                        focusLost.countDown()
                    }
                    // Bound the old implementation's loop so a failing test cannot flood the host.
                    if (reasons.size >= 32) {
                        player.removeListener(this)
                        callbackStorm.countDown()
                        return
                    }
                    fades.pauseSmooth()
                }
            })
            deck.setMediaSource(SilenceMediaSource(5_000_000L))
            deck.prepare()
        }

        try {
            assertTrue("Player did not become ready", ready.await(5, TimeUnit.SECONDS))
            // Inject the platform callback on its owning looper. No audio is played, no real
            // focus is requested, and no MediaSession is registered with SystemUI.
            val internalPlayer = deck.javaClass.getDeclaredField("internalPlayer").run {
                isAccessible = true
                get(deck)
            }
            val focusManager = internalPlayer.javaClass.getDeclaredField("audioFocusManager").run {
                isAccessible = true
                get(internalPlayer)
            }
            val focusListener = focusManager.javaClass.getDeclaredMethod("getFocusListener").run {
                isAccessible = true
                invoke(focusManager) as AudioManager.OnAudioFocusChangeListener
            }
            Handler(deck.playbackLooper).post {
                focusListener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
            }
            assertTrue("Focus loss was not delivered", focusLost.await(5, TimeUnit.SECONDS))
            assertFalse("Pause feedback loop detected", callbackStorm.await(1, TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                assertEquals(listOf(Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS), reasons)
                assertFalse(player.playWhenReady)
                assertEquals(0f, player.volume, 0.001f)
            }
        } finally {
            instrumentation.runOnMainSync {
                player.release()
                fades.reset()
                fades.release()
            }
        }
    }
}
