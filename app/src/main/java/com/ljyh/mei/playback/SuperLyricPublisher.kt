package com.ljyh.mei.playback

import androidx.media3.common.Player
import com.hchen.superlyricapi.SuperLyricData
import com.hchen.superlyricapi.SuperLyricHelper
import com.hchen.superlyricapi.SuperLyricLine
import com.hchen.superlyricapi.SuperLyricWord
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

/** Adapts the shared timeline to SuperLyricApi's current-line publishing contract. */
internal class SuperLyricPublisher(
    private val player: Player,
    private val scope: CoroutineScope,
) {
    private var song: Song? = null
    private var album: String? = null
    private var displayTranslation = true
    private var displayRoma = true
    private var updateJob: Job? = null
    private var lastLineIndex = Int.MIN_VALUE
    private var reportedFailure = false

    fun setSong(song: Song?, album: String?, translation: Boolean, roma: Boolean) {
        this.song = song
        this.album = album
        displayTranslation = translation
        displayRoma = roma
        lastLineIndex = Int.MIN_VALUE
    }

    fun syncPlayback() {
        updateJob?.cancel()
        lastLineIndex = Int.MIN_VALUE
        if (!player.isPlaying || song == null) {
            publishCurrentLine()
            return
        }
        updateJob = scope.launch {
            while (isActive && player.isPlaying) {
                publishCurrentLine()
                val position = player.currentPosition.coerceAtLeast(0L)
                val nextBoundary = song?.lyrics.orEmpty().asSequence()
                    .flatMap { sequenceOf(it.begin, it.end) }
                    .filter { it > position }
                    .minOrNull()
                // Wake at line boundaries, with a bounded interval for receiver reconnection.
                val waitMs = nextBoundary?.let {
                    ((it - position) / player.playbackParameters.speed.toDouble()).toLong()
                } ?: 1_000L
                delay(waitMs.coerceIn(20L, 1_000L))
            }
        }
    }

    private fun publishCurrentLine() {
        callApi {
            if (!SuperLyricHelper.isAvailable()) {
                lastLineIndex = Int.MIN_VALUE
                return@callApi
            }
            if (!SuperLyricHelper.isPublisherRegistered()) {
                SuperLyricHelper.registerPublisher()
                // Keep the SDK's media-session clock for seeks and word highlighting.
                SuperLyricHelper.setSystemPlayStateListenerEnabled(true)
                lastLineIndex = Int.MIN_VALUE
            }
            val current = song
            val position = player.currentPosition.coerceAtLeast(0L)
            val lines = current?.lyrics.orEmpty()
            val index = if (player.isPlaying && current?.id == player.currentMediaItem?.mediaId) {
                lines.indexOfLast { position >= it.begin && position < it.end }
            } else -1
            if (index == lastLineIndex) return@callApi
            val data = SuperLyricData()
                .setTitle(current?.name)
                .setArtist(current?.artist)
                .setAlbum(album)
            if (index < 0) {
                SuperLyricHelper.sendStop(data)
            } else {
                val line = lines[index]
                data.setLyric(line.toSuperLyricLine())
                if (displayTranslation) {
                    line.translation?.takeIf { it.isNotBlank() }?.let {
                        data.setTranslation(SuperLyricLine(it, line.begin, line.end))
                    }
                }
                val secondary = line.roma?.takeIf { displayRoma && it.isNotBlank() }
                    ?: line.secondary?.takeIf { it.isNotBlank() }
                secondary?.let { data.setSecondary(SuperLyricLine(it, line.begin, line.end)) }
                SuperLyricHelper.sendLyric(data)
            }
            lastLineIndex = index
        }
    }

    fun stop() {
        updateJob?.cancel()
        updateJob = null
        callApi {
            if (SuperLyricHelper.isAvailable() && SuperLyricHelper.isPublisherRegistered()) {
                SuperLyricHelper.sendStop(SuperLyricData())
                SuperLyricHelper.unregisterPublisher()
            }
        }
        song = null
        album = null
        lastLineIndex = Int.MIN_VALUE
    }

    private inline fun callApi(block: () -> Unit) {
        try {
            block()
        } catch (error: Exception) {
            reportFailure(error)
        } catch (error: LinkageError) {
            // Missing platform API access must not interrupt the playback service.
            reportFailure(error)
        }
    }

    private fun reportFailure(error: Throwable) {
        lastLineIndex = Int.MIN_VALUE
        if (!reportedFailure) {
            reportedFailure = true
            Timber.w(error, "SuperLyricApi publication failed")
        }
    }
}

private fun RichLyricLine.toSuperLyricLine(): SuperLyricLine = SuperLyricLine(
    text.orEmpty(),
    words?.map { SuperLyricWord(it.text.orEmpty(), it.begin, it.end) }?.toTypedArray(),
    begin,
    end,
)
