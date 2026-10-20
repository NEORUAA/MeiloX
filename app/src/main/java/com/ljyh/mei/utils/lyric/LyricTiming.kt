package com.ljyh.mei.utils.lyric

import com.ljyh.mei.ui.model.LyricData
import com.mocharealm.accompanist.lyrics.core.model.ISyncedLine
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeSyllable
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
import com.mocharealm.accompanist.lyrics.core.model.synced.UncheckedSyncedLine

/** Renderer time may stop changing only after every visible animation has saturated. */
internal class KaraokeRenderTimeWindow(
    val first: Int,
    val last: Int,
    private val safeRawFirst: Int,
    private val safeRawLast: Int,
) {
    fun sample(timeMs: Int): Int {
        // lyrics-ui's simple float uses Int subtraction. Preserve its original
        // arithmetic for unusual clock values which would overflow that subtraction.
        if (timeMs < safeRawFirst || timeMs > safeRawLast) return timeMs
        return timeMs.coerceIn(first, last)
    }
}

/**
 * Static bounds audited against lyrics-ui 1.0.19: karaoke fill, 700ms simple
 * float, word/glyph/phonetic animations, and nested vocals' inclusive +/-600ms
 * visibility. Focus, scrolling and breathing dots must keep the original clock.
 */
internal fun karaokeRenderTimeWindow(line: KaraokeLine): KaraokeRenderTimeWindow? {
    val nested = (line as? KaraokeLine.MainKaraokeLine)?.accompanimentLines.orEmpty()
    val renderLines = listOf(line) + nested
    var first = line.start.toLong()
    var last = line.end.toLong()
    var earliestSyllable = Long.MAX_VALUE
    var latestSyllable = Long.MIN_VALUE
    for (renderLine in renderLines) {
        if (renderLine.end <= renderLine.start || renderLine.syllables.isEmpty()) return null
        first = minOf(first, renderLine.start.toLong())
        last = maxOf(last, renderLine.end.toLong())
        if (renderLine !== line) {
            first = minOf(first, renderLine.start.toLong() - 600L)
            last = maxOf(last, renderLine.end.toLong() + 600L)
        }
        for (syllable in renderLine.syllables) {
            if (syllable.end <= syllable.start || syllable.content.isEmpty()) return null
            val start = syllable.start.toLong()
            earliestSyllable = minOf(earliestSyllable, start)
            latestSyllable = maxOf(latestSyllable, start)
            first = minOf(first, start)
            last = maxOf(last, syllable.end.toLong(), start + 700L)
        }
    }

    // Word animation interpolates timestamps with Float. Restrict this optional
    // cache to exact-integer Float timings and spans (2^24), where its multiply,
    // subtract, ratio and add chain has less than 8ms accumulated rounding error.
    // A 16ms margin also lies beyond the inclusive vocal-visibility boundaries.
    val exactFloatInteger = 1L shl 24
    first -= 16L
    last += 16L
    if (first < -exactFloatInteger || last > exactFloatInteger ||
        last - first > exactFloatInteger
    ) return null

    val safeRawFirst = maxOf(Int.MIN_VALUE.toLong(), Int.MIN_VALUE.toLong() + latestSyllable)
    val safeRawLast = minOf(Int.MAX_VALUE.toLong(), Int.MAX_VALUE.toLong() + earliestSyllable)
    if (first < safeRawFirst || last > safeRawLast) return null
    return KaraokeRenderTimeWindow(first.toInt(), last.toInt(), safeRawFirst.toInt(), safeRawLast.toInt())
}

/** Derive display timings from the original snapshot without changing its metadata. */
internal fun LyricData.withTimingOffset(offsetMs: Int): LyricData {
    if (offsetMs == 0) return this
    return copy(lyricLine = lyricLine.copy(lines = lyricLine.lines.map { it.shiftTiming(offsetMs) }))
}

private fun ISyncedLine.shiftTiming(offsetMs: Int): ISyncedLine {
    return when (this) {
        is SyncedLine -> copy(start = start.shiftTiming(offsetMs), end = end.shiftTiming(offsetMs))
        is UncheckedSyncedLine -> copy(start = start.shiftTiming(offsetMs), end = end.shiftTiming(offsetMs))
        is KaraokeLine.MainKaraokeLine -> copy(
            start = start.shiftTiming(offsetMs),
            end = end.shiftTiming(offsetMs),
            syllables = syllables.map { it.shiftTiming(offsetMs) },
            accompanimentLines = accompanimentLines?.map { it.shiftTiming(offsetMs) },
        )
        is KaraokeLine.AccompanimentKaraokeLine -> shiftTiming(offsetMs)
        else -> throw IllegalArgumentException("Unsupported lyric line type: ${javaClass.name}")
    }
}

private fun KaraokeLine.AccompanimentKaraokeLine.shiftTiming(
    offsetMs: Int,
): KaraokeLine.AccompanimentKaraokeLine {
    return copy(
        start = start.shiftTiming(offsetMs),
        end = end.shiftTiming(offsetMs),
        syllables = syllables.map { it.shiftTiming(offsetMs) },
    )
}

private fun KaraokeSyllable.shiftTiming(offsetMs: Int): KaraokeSyllable {
    return copy(start = start.shiftTiming(offsetMs), end = end.shiftTiming(offsetMs))
}

private fun Int.shiftTiming(offsetMs: Int): Int {
    val shifted = toLong() + offsetMs
    // Reject the entire derivation rather than clipping individual timestamps and durations.
    require(shifted in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
        "Lyric timing offset exceeds the supported range"
    }
    return shifted.toInt()
}
