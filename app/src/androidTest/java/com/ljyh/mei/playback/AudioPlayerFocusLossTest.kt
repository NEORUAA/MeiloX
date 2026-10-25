package com.ljyh.mei.playback

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.SilenceMediaSource
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ljyh.mei.MainActivity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AudioPlayerFocusLossTest {
    @Test
    fun audioFocusPolicySurvivesLiveChangesAndDeckPromotion() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val attributes = musicAudioAttributes()
        lateinit var player: StableDeckPlayer
        instrumentation.runOnMainSync {
            val first = ExoPlayer.Builder(instrumentation.targetContext).build()
            val second = ExoPlayer.Builder(instrumentation.targetContext).build()
            player = StableDeckPlayer(first, second, attributes)
        }
        try {
            assertFocusPolicy(player, activeHandlesFocus = true)
            instrumentation.runOnMainSync { player.setHandleAudioFocus(false) }
            assertFocusPolicy(player, activeHandlesFocus = false)
            instrumentation.runOnMainSync { player.promoteStandby() }
            assertFocusPolicy(player, activeHandlesFocus = false)
            instrumentation.runOnMainSync { player.setHandleAudioFocus(true) }
            assertFocusPolicy(player, activeHandlesFocus = true)
            instrumentation.runOnMainSync { player.promoteStandby() }
            assertFocusPolicy(player, activeHandlesFocus = true)
            instrumentation.runOnMainSync {
                assertFalse(player.playWhenReady)
                assertEquals(Player.STATE_IDLE, player.playbackState)
            }
        } finally {
            instrumentation.runOnMainSync { player.release() }
        }
    }

    @Test
    fun concurrentPlaybackContinuesThroughCompetingFocusAndPromotion() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(InstrumentationRegistry.getArguments().getString("issue67ConcurrentPlayback") == "true")
        assumeTrue(context.packageName == "com.neoruaa.meilox.issue62")
        // Target 35+ requires a foreground activity or media foreground service to request focus.
        ActivityScenario.launch(MainActivity::class.java).use {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setOnAudioFocusChangeListener({}, Handler(Looper.getMainLooper()))
                .build()
            val focusLost = CountDownLatch(1)
            lateinit var player: StableDeckPlayer
            instrumentation.runOnMainSync {
                val decks = List(2) {
                    ExoPlayer.Builder(context)
                        .setAudioAttributes(musicAudioAttributes(), false)
                        .build().apply {
                            volume = 0f
                            setMediaSource(SilenceMediaSource(30_000_000L))
                            prepare()
                        }
                }
                player = StableDeckPlayer(decks[0], decks[1], musicAudioAttributes(), handlesAudioFocus = false)
                player.addListener(object : Player.Listener {
                    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                        if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS) {
                            focusLost.countDown()
                        }
                    }
                })
                player.play()
            }
            fun requestCompetingFocus() {
                instrumentation.runOnMainSync {
                    audioManager.abandonAudioFocusRequest(request)
                    assertEquals(
                        "The foreground focus contender must acquire audio focus",
                        AudioManager.AUDIOFOCUS_REQUEST_GRANTED,
                        audioManager.requestAudioFocus(request),
                    )
                }
            }
            try {
                awaitPlaying(player.activeDeck)
                requestCompetingFocus()
                assertPlaybackContinues(player.activeDeck)
                instrumentation.runOnMainSync { player.standbyDeck.play() }
                awaitPlaying(player.standbyDeck)
                instrumentation.runOnMainSync { player.promoteStandby().pause() }
                requestCompetingFocus()
                assertPlaybackContinues(player.activeDeck)

                instrumentation.runOnMainSync { player.setHandleAudioFocus(true) }
                assertFocusPolicy(player, activeHandlesFocus = true)
                requestCompetingFocus()
                assertTrue("Normal playback must yield to another focus owner", focusLost.await(5, TimeUnit.SECONDS))
                instrumentation.runOnMainSync { assertFalse(player.playWhenReady) }

                instrumentation.runOnMainSync {
                    player.setHandleAudioFocus(false)
                    player.play()
                }
                awaitPlaying(player.activeDeck)
                requestCompetingFocus()
                assertPlaybackContinues(player.activeDeck)
            } finally {
                instrumentation.runOnMainSync {
                    player.release()
                    audioManager.abandonAudioFocusRequest(request)
                }
            }
        }
    }

    @Test
    fun releaseCancelsAnActiveFadeAndIgnoresLateVolumeCommands() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var player: ExoPlayer
        lateinit var fades: AudioPlayer
        var releasedVolume = 0f
        val settled = CountDownLatch(1)
        instrumentation.runOnMainSync {
            player = ExoPlayer.Builder(instrumentation.targetContext).build()
            player.volume = 0.35f
            fades = AudioPlayer(player).apply { volumeSmoothDuration = 100L }
            fades.startSmooth()
            fades.release()
            releasedVolume = player.volume
            fades.pauseSmooth()
            fades.startSmooth()
            fades.setVolume(0f, 0f)
            Handler(Looper.getMainLooper()).postDelayed({ settled.countDown() }, 200L)
        }
        try {
            assertTrue(settled.await(2, TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                assertEquals(releasedVolume, player.volume, 0.001f)
                assertFalse(fades.isPlaying())
            }
        } finally {
            instrumentation.runOnMainSync {
                fades.release()
                player.release()
            }
        }
    }

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
            val focusManager = focusManager(deck)
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
                fades.release()
                player.release()
            }
        }
    }

    private fun musicAudioAttributes() = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .build()

    private fun focusManager(deck: ExoPlayer): Any {
        val internalPlayer = deck.javaClass.getDeclaredField("internalPlayer").run {
            isAccessible = true
            get(deck)
        }
        return internalPlayer.javaClass.getDeclaredField("audioFocusManager").run {
            isAccessible = true
            get(internalPlayer)!!
        }
    }

    private fun assertFocusPolicy(player: StableDeckPlayer, activeHandlesFocus: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var active: ExoPlayer
        lateinit var standby: ExoPlayer
        instrumentation.runOnMainSync {
            active = player.activeDeck
            standby = player.standbyDeck
        }
        for ((deck, expected) in listOf(active to activeHandlesFocus, standby to false)) {
            val settled = CountDownLatch(1)
            val gain = AtomicReference<Int>()
            Handler(deck.playbackLooper).post {
                val manager = focusManager(deck)
                gain.set(manager.javaClass.getDeclaredField("focusGainToRequest").run {
                    isAccessible = true
                    getInt(manager)
                })
                settled.countDown()
            }
            assertTrue("The focus policy must reach the playback looper", settled.await(5, TimeUnit.SECONDS))
            assertEquals(if (expected) AudioManager.AUDIOFOCUS_GAIN else 0, gain.get())
        }
    }

    private fun awaitPlaying(deck: ExoPlayer) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val playing = CountDownLatch(1)
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) playing.countDown()
            }
        }
        instrumentation.runOnMainSync {
            deck.addListener(listener)
            if (deck.isPlaying) playing.countDown()
        }
        try {
            assertTrue("The silent playback fixture must start", playing.await(5, TimeUnit.SECONDS))
        } finally {
            instrumentation.runOnMainSync { deck.removeListener(listener) }
        }
    }

    private fun assertPlaybackContinues(deck: ExoPlayer) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var position = 0L
        val settled = CountDownLatch(1)
        instrumentation.runOnMainSync {
            position = deck.currentPosition
            Handler(Looper.getMainLooper()).postDelayed({ settled.countDown() }, 300L)
        }
        assertTrue(settled.await(2, TimeUnit.SECONDS))
        instrumentation.runOnMainSync {
            assertTrue("Concurrent playback must remain active", deck.isPlaying)
            assertTrue("Concurrent playback must keep advancing", deck.currentPosition > position)
        }
    }
}
