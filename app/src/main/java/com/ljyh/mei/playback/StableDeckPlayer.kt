package com.ljyh.mei.playback

import android.graphics.Bitmap
import android.net.Uri
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.io.ByteArrayOutputStream

/** Publishes external metadata through Media3 without changing the application's player or queue. */
@UnstableApi
internal class ExternalMediaPlayer(private val source: Player) : ForwardingSimpleBasePlayer(source) {
    private var lyricMediaId: String? = null
    private var lyric: String? = null
    private var artworkMediaId: String? = null
    private var artworkUri: Uri? = null
    private var artworkData: ByteArray? = null

    fun setExternalLyrics(mediaId: String?, lyric: String?) {
        val text = lyric?.takeIf { it.isNotBlank() }
        if (lyricMediaId == mediaId && this.lyric == text) return
        lyricMediaId = mediaId
        this.lyric = text
        invalidateState()
    }

    fun setArtwork(mediaId: String?, bitmap: Bitmap) {
        val metadata = source.mediaMetadata
        if (mediaId == null || mediaId != source.currentMediaItem?.mediaId || metadata.artworkData != null) return
        // Ignore the bitmap decoded from our own bytes on subsequent platform metadata updates.
        if (artworkMediaId == mediaId && artworkUri == metadata.artworkUri && artworkData != null) return
        val data = ByteArrayOutputStream().use { output ->
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)) return
            output.toByteArray()
        }
        artworkMediaId = mediaId
        artworkUri = metadata.artworkUri
        artworkData = data
        invalidateState()
    }

    override fun getState(): State {
        val state = super.getState()
        val mediaId = source.currentMediaItem?.mediaId
        val title = lyric.takeIf { mediaId != null && mediaId == lyricMediaId }
        val artwork = artworkData.takeIf {
            mediaId != null && mediaId == artworkMediaId && state.currentMetadata.artworkUri == artworkUri
        }
        if (title == null && artwork == null) return state
        val metadata = state.currentMetadata.buildUpon().apply {
            if (title != null) setTitle(title)
            if (artwork != null && state.currentMetadata.artworkData == null) {
                setArtworkData(artwork, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
            }
        }.build()
        return state.buildUpon().setPlaylist(state.timeline, state.currentTracks, metadata).build()
    }
}

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
    internal var isExternalMediaRequest: (() -> Boolean)? = null

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

    /** Recreates sources without changing entry identities or playback order. */
    internal fun refreshMediaItemSource(index: Int, refreshQueuedSources: Boolean = false) {
        checkOnApplicationLooper()
        val deck = activeDeck
        val order = deck.playbackOrderIndices(true)
        if (refreshQueuedSources) {
            // Already prepared next-item sources may still retain the previous quality.
            val items = List(deck.mediaItemCount, deck::getMediaItemAt)
            deck.setMediaItems(items, index, deck.currentPosition)
        } else {
            val item = deck.getMediaItemAt(index)
            deck.removeMediaItem(index)
            deck.addMediaItem(index, item)
        }
        setPlaybackOrder(order)
    }

    override fun handleSetMediaItems(
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<*> {
        // Selecting an existing queue by media ID must not rebuild it or reset its shuffle order.
        if (isExternalMediaRequest?.invoke() == true && startIndex in mediaItems.indices &&
            mediaItems.size == activeDeck.mediaItemCount &&
            mediaItems.indices.all { mediaItems[it] == activeDeck.getMediaItemAt(it) }
        ) {
            return handleSeek(startIndex, startPositionMs, Player.COMMAND_SEEK_TO_MEDIA_ITEM)
        }
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
        val result = if (seekCommand == Player.COMMAND_SEEK_TO_PREVIOUS) {
            if (activeDeck.hasPreviousMediaItem()) {
                activeDeck.seekToPreviousMediaItem()
            } else {
                activeDeck.seekTo(0L)
            }
            Futures.immediateVoidFuture()
        } else {
            super.handleSeek(mediaItemIndex, positionMs, seekCommand)
        }
        // External track selections start playback; in-track seeks retain the current state.
        if (isExternalMediaRequest?.invoke() == true && activeDeck.mediaItemCount > 0 &&
            (seekCommand == Player.COMMAND_SEEK_TO_PREVIOUS ||
                seekCommand == Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM ||
                seekCommand == Player.COMMAND_SEEK_TO_NEXT ||
                seekCommand == Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM ||
                seekCommand == Player.COMMAND_SEEK_TO_MEDIA_ITEM)
        ) {
            if (activeDeck.playbackState == Player.STATE_IDLE) activeDeck.prepare()
            activeDeck.play()
        }
        return result
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
