package com.ljyh.mei.utils.lyric

import com.mocharealm.accompanist.lyrics.core.model.SyncedLyrics
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeAlignment
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeSyllable
import com.mocharealm.accompanist.lyrics.core.model.karaoke.copy
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
import kotlin.math.abs

/** Match timestamped romanization without replacing translations or existing TTML phonetics. */
internal fun attachPhonetics(lyrics: SyncedLyrics, phoneticLrc: String?): SyncedLyrics {
    if (phoneticLrc.isNullOrBlank()) return lyrics
    val phoneticLines = try {
        if (QRCParser.canParse(phoneticLrc)) {
            QRCParser.parse(phoneticLrc, null).lines.filterIsInstance<KaraokeLine>()
                .map { it.start to it.syllables.joinToString("") { syllable -> syllable.content }.trim() }
        } else {
            LRCParser.parse(phoneticLrc, null).lines.filterIsInstance<SyncedLine>()
                .map { it.start to it.content.trim() }
        }.filter { it.second.isNotBlank() }
    } catch (_: IllegalArgumentException) {
        // Optional romanization must not discard valid main lyrics when its timing is malformed.
        return lyrics
    }
    if (phoneticLines.isEmpty()) return lyrics

    data class Match(val lineIndex: Int, val phoneticIndex: Int, val distance: Long)
    val candidates = buildList {
        lyrics.lines.forEachIndexed { lineIndex, line ->
            if (line is KaraokeLine || line is SyncedLine) {
                phoneticLines.forEachIndexed { phoneticIndex, phonetic ->
                    val distance = abs(phonetic.first.toLong() - line.start)
                    if (distance <= 1_000L) add(Match(lineIndex, phoneticIndex, distance))
                }
            }
        }
    }.sortedBy { it.distance }
    val matches = mutableMapOf<Int, Int>()
    val used = mutableSetOf<Int>()
    candidates.forEach { match ->
        if (match.lineIndex !in matches && match.phoneticIndex !in used) {
            matches[match.lineIndex] = match.phoneticIndex
            used += match.phoneticIndex
        }
    }
    val lines = lyrics.lines.mapIndexed { index, line ->
        val match = matches[index] ?: return@mapIndexed line
        if (line is KaraokeLine && (!line.phonetic.isNullOrBlank() ||
                    line.syllables.any { !it.phonetic.isNullOrBlank() })) return@mapIndexed line
        val phonetic = phoneticLines[match].second
        when (line) {
            is KaraokeLine -> line.copy(phonetic = phonetic)
            is SyncedLine -> KaraokeLine.MainKaraokeLine(
                syllables = listOf(KaraokeSyllable(line.content, line.start, line.end)),
                translation = line.translation,
                alignment = KaraokeAlignment.Start,
                start = line.start,
                end = line.end,
                phonetic = phonetic,
            )
            else -> line
        }
    }
    return lyrics.copy(lines = lines)
}
