package com.ljyh.mei.playback

import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder
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
    private var internalQueueUpdateDepth = 0
    internal var onQueueEdited: ((replaced: Boolean) -> Unit)? = null
    internal var onSeekRequested: (() -> Unit)? = null

    internal fun <T> withInternalQueueUpdate(block: () -> T): T {
        internalQueueUpdateDepth++
        return try {
            block()
        } finally {
            internalQueueUpdateDepth--
        }
    }

    private fun notifyQueueEdit(replaced: Boolean = false) {
        if (internalQueueUpdateDepth == 0) onQueueEdited?.invoke(replaced)
    }

    internal fun setPlaybackOrder(order: List<Int>, userEdit: Boolean = false) {
        checkOnApplicationLooper()
        if (!order.isPlaybackPermutation(activeDeck.mediaItemCount)) return
        if (userEdit) notifyQueueEdit()
        activeDeck.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(order.toIntArray(), System.nanoTime()))
        invalidateState()
    }

    /** Forces source recreation without changing the entry identity or shuffle position. */
    internal fun refreshMediaItemSource(index: Int) {
        checkOnApplicationLooper()
        val deck = activeDeck
        val item = deck.getMediaItemAt(index)
        val order = deck.playbackOrderIndices(true)
        deck.removeMediaItem(index)
        deck.addMediaItem(index, item)
        setPlaybackOrder(order)
    }

    override fun handleSetMediaItems(
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<*> {
        notifyQueueEdit(replaced = true)
        return super.handleSetMediaItems(mediaItems.map { it.withQueueEntryId() }, startIndex, startPositionMs)
    }

    override fun handleAddMediaItems(index: Int, mediaItems: List<MediaItem>): ListenableFuture<*> {
        notifyQueueEdit()
        return super.handleAddMediaItems(index, mediaItems.map { it.withQueueEntryId() })
    }

    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> {
        notifyQueueEdit(replaced = fromIndex == 0 && toIndex == activeDeck.mediaItemCount)
        return super.handleRemoveMediaItems(fromIndex, toIndex)
    }

    override fun handleMoveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int): ListenableFuture<*> {
        notifyQueueEdit()
        val orderedIds = activeDeck.playbackOrderIndices(true).map { activeDeck.getMediaItemAt(it).queueEntryId }
        val result = super.handleMoveMediaItems(fromIndex, toIndex, newIndex)
        val indices = (0 until activeDeck.mediaItemCount).associateBy { activeDeck.getMediaItemAt(it).queueEntryId }
        setPlaybackOrder(orderedIds.mapNotNull { indices[it] })
        return result
    }

    override fun handleReplaceMediaItems(
        fromIndex: Int,
        toIndex: Int,
        mediaItems: List<MediaItem>,
    ): ListenableFuture<*> {
        val sameEntries = toIndex - fromIndex == mediaItems.size && mediaItems.indices.all {
            activeDeck.getMediaItemAt(fromIndex + it).mediaId == mediaItems[it].mediaId
        }
        if (!sameEntries) notifyQueueEdit()
        val order = activeDeck.playbackOrderIndices(true)
        val replacements = mediaItems.mapIndexed { offset, item ->
            val id = if (sameEntries) activeDeck.getMediaItemAt(fromIndex + offset).queueEntryId else null
            if (id != null) item.withQueueEntryId(id) else item.withQueueEntryId()
        }
        val result = super.handleReplaceMediaItems(fromIndex, toIndex, replacements)
        if (sameEntries) setPlaybackOrder(order)
        return result
    }

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
        if (internalQueueUpdateDepth == 0) onSeekRequested?.invoke()
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
