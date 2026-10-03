package com.ljyh.mei.playback

import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.ui.model.LyricData
import com.ljyh.mei.ui.model.LyricSource
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeAlignment
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
import io.github.proify.lyricon.lyric.model.LyricWord
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import org.json.JSONObject
import java.util.Locale

internal fun MediaMetadata.toSystemLyricSong(data: LyricData?): Song {
    val lines = data?.takeUnless {
        it.isPureMusic || it.source == LyricSource.Empty || it.source == LyricSource.Loading
    }?.lyricLine?.lines.orEmpty().sortedBy { it.start }
    val richLines = lines.mapIndexedNotNull { index, line ->
        val karaoke = line as? KaraokeLine
        val plain = line as? SyncedLine
        val text = karaoke?.syllables?.joinToString("") { it.content } ?: plain?.content
        if (text.isNullOrBlank()) return@mapIndexedNotNull null
        val begin = line.start.toLong().coerceAtLeast(0L)
        val end = line.end.toLong().takeIf { it > begin }
            ?: lines.getOrNull(index + 1)?.start?.toLong()?.takeIf { it > begin }
            ?: duration.takeIf { it > begin }
            ?: (begin + 3_000L)
        val words = karaoke?.syllables?.mapNotNull { syllable ->
            if (syllable.content.isEmpty()) return@mapNotNull null
            val wordBegin = syllable.start.toLong().coerceIn(begin, end - 1L)
            val wordEnd = syllable.end.toLong().coerceIn(wordBegin + 1L, end)
            LyricWord(
                begin = wordBegin,
                end = wordEnd,
                duration = wordEnd - wordBegin,
                text = syllable.content,
            )
        }?.sortedBy { it.begin }?.takeIf { it.isNotEmpty() }
        RichLyricLine(
            begin = begin,
            end = end,
            duration = end - begin,
            text = text,
            words = words,
            translation = karaoke?.translation ?: plain?.translation,
            roma = karaoke?.phonetic ?: karaoke?.syllables?.mapNotNull { it.phonetic }
                ?.takeIf { it.isNotEmpty() }?.joinToString(""),
            isAlignedRight = karaoke?.alignment == KaraokeAlignment.End,
            secondary = (karaoke as? KaraokeLine.MainKaraokeLine)?.accompanimentLines
                ?.joinToString(" ") { background -> background.syllables.joinToString("") { it.content } },
        )
    }
    return Song(
        id = id.toString(),
        name = title,
        artist = artists.joinToString(" / ") { it.name },
        duration = duration.coerceAtLeast(0L),
        lyrics = richLines,
    )
}

/** Player-owned ColorOS metadata contract; no Bridge broadcasts or extra media session. */
internal fun Song.toColorOsLyricInfo(packageName: String, generation: Long): String? {
    val lines = lyrics.orEmpty()
    val lrc = buildString {
        lines.forEach { line ->
            append('[').append(timestamp(line.begin)).append(']')
            appendLine(singleLine(line.text))
        }
    }
    val raw = if (lines.any { !it.words.isNullOrEmpty() }) buildString {
        lines.forEach { line ->
            append('[').append(timestamp(line.begin)).append(']')
            val words = line.words
            if (words.isNullOrEmpty()) {
                appendLine(singleLine(line.text))
            } else {
                words.forEach { word ->
                    append('<').append(timestamp(word.begin)).append('>')
                    append(singleLine(word.text))
                }
                append('<').append(timestamp(words.last().end)).appendLine('>')
            }
        }
    } else null
    val translation = buildString {
        lines.forEach { line ->
            if (!line.translation.isNullOrBlank()) {
                append('[').append(timestamp(line.begin)).append(']')
                appendLine(singleLine(line.translation))
            }
        }
    }
    val payload = JSONObject()
        .put("songName", name)
        .put("artist", artist)
        .put("songId", id)
        .put("lyricType", 0)
        .put("lyric", lrc)
        .put("noLyric", lines.isEmpty())
        .put("provider", packageName)
        .put("sessionGeneration", generation)
        .apply {
            if (raw != null) put("rawLyric", raw)
            if (translation.isNotEmpty()) put("translationLyric", translation)
        }.toString()
    // Leave ample Binder headroom for the host metadata and artwork.
    return payload.takeIf { it.length <= 128 * 1024 }
}

private fun timestamp(timeMs: Long): String = String.format(
    Locale.ROOT, "%02d:%02d.%03d", timeMs / 60_000L, timeMs / 1_000L % 60L, timeMs % 1_000L,
)

private fun singleLine(text: String?): String = text.orEmpty().replace('\r', ' ').replace('\n', ' ')
