package com.ljyh.mei.playback

import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * Stable player identity for MediaSession and application consumers while two ExoPlayer decks
 * alternate between active and standby roles.
 */
@UnstableApi
class StableDeckPlayer(
    firstDeck: ExoPlayer,
    secondDeck: ExoPlayer,
    private val audioAttributes: AudioAttributes,
) : ForwardingSimpleBasePlayer(firstDeck) {
    private val decks = arrayOf(firstDeck, secondDeck)
    private var activeDeckIndex = 0
    private var released = false

    init {
        require(firstDeck.applicationLooper === secondDeck.applicationLooper) {
            "Deck players must use the same application looper"
        }
    }

    val activeDeck: ExoPlayer
        get() = decks[activeDeckIndex]

    val standbyDeck: ExoPlayer
        get() = decks[1 - activeDeckIndex]

    internal val deckPlayers: List<ExoPlayer>
        get() = decks.asList()

    /** Promotes the already-playing standby deck without pausing or seeking it. */
    fun promoteStandby(): ExoPlayer {
        checkOnApplicationLooper()
        val outgoingDeck = activeDeck
        val incomingDeck = standbyDeck

        outgoingDeck.setHandleAudioBecomingNoisy(false)
        outgoingDeck.setAudioAttributes(audioAttributes, false)
        incomingDeck.setAudioAttributes(audioAttributes, true)
        incomingDeck.setHandleAudioBecomingNoisy(true)

        activeDeckIndex = 1 - activeDeckIndex
        setPlayer(incomingDeck)
        return outgoingDeck
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int,
    ): ListenableFuture<*> {
        if (seekCommand == Player.COMMAND_SEEK_TO_PREVIOUS) {
            if (activeDeck.hasPreviousMediaItem()) {
                activeDeck.seekToPreviousMediaItem()
            } else {
                activeDeck.seekTo(0L)
            }
            return Futures.immediateVoidFuture()
        }
        return super.handleSeek(mediaItemIndex, positionMs, seekCommand)
    }

    override fun handleRelease(): ListenableFuture<*> {
        if (!released) {
            released = true
            decks.forEach(ExoPlayer::release)
        }
        return Futures.immediateVoidFuture()
    }

    private fun checkOnApplicationLooper() {
        check(Looper.myLooper() === applicationLooper) {
            "Deck promotion must run on the player application looper"
        }
    }
}
