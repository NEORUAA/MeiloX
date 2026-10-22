package com.ljyh.mei.utils.lyric

import com.ljyh.mei.ui.model.LyricData
import com.ljyh.mei.ui.model.LyricSource
import com.ljyh.mei.ui.model.LyricSourceData
import com.ljyh.mei.ui.model.LyricVariant
import timber.log.Timber

fun createDefaultLyricData(
    message: String,
    isPureMusic: Boolean = false,
    source: LyricSource = LyricSource.Empty
): LyricData {
    return LyricData(
        isVerbatim = false,
        isPureMusic = isPureMusic,
        source = source,
        lyricLine = LRCParser.parse("[00:00.00]${message}", null)
    )
}

/** Keep a usable snapshot while parallel sources are still resolving. */
internal fun selectLyricUpdate(
    current: LyricData,
    candidate: LyricData,
    sourcesFinished: Boolean,
    alignmentFinished: Boolean,
): LyricData {
    if (candidate == current) return current
    val currentPriority = current.displayPriority()
    val candidatePriority = candidate.displayPriority()
    if (candidatePriority == 0) {
        if (currentPriority > 0 || !sourcesFinished) return current
        return if (candidate.lyricLine.lines.isEmpty()) {
            createDefaultLyricData("没有歌词")
        } else candidate
    }
    if (candidatePriority < currentPriority) return current
    if (candidatePriority == currentPriority && !alignmentFinished) return current
    return candidate
}

/** A manual choice bypasses automatic source priority without changing source data. */
internal fun selectPreferredLyricUpdate(
    current: LyricData,
    candidate: LyricData,
    preferredSource: LyricSource?,
    sourcesFinished: Boolean,
    alignmentFinished: Boolean,
    preferredVariant: LyricVariant? = null,
): LyricData {
    val variantChanged = preferredVariant != null && lyricVariantOf(candidate) == preferredVariant &&
        lyricVariantOf(current) != preferredVariant
    val previous = if ((preferredSource != null && current.source != preferredSource) || variantChanged) {
        createDefaultLyricData("歌词加载中", source = LyricSource.Loading)
    } else current
    return selectLyricUpdate(previous, candidate, sourcesFinished, alignmentFinished)
}

internal fun selectLyricSources(
    sources: List<LyricSourceData>,
    preferredSource: LyricSource?,
): List<LyricSourceData> = if (preferredSource == null) sources else sources.filter {
    it.source == preferredSource
}

private fun LyricData.displayPriority(): Int {
    if (lyricLine.lines.isEmpty()) return 0
    return when (source) {
        LyricSource.AM -> 5
        LyricSource.NetEaseCloudMusic -> if (isVerbatim) 4 else 2
        LyricSource.QQMusic -> if (isVerbatim) 3 else 1
        LyricSource.Empty, LyricSource.Loading -> 0
    }
}

fun mergeLyrics(sources: List<LyricSourceData>, isPureMusic: Boolean = false): LyricData {
//    Timber.tag("LyricUtils").d("mergeLyrics: $sources")
    // 找到 NetEase 的逐字歌词（yrc + tlyric）
    val amSource = sources.filterIsInstance<LyricSourceData.AM>().firstOrNull()
    if (amSource != null) {
        val a = amSource.lyric
        Timber.tag("LyricUtils").d("TMLL")
        return LyricData(
            isVerbatim = true,
            isPureMusic = isPureMusic,
            source = LyricSource.AM,
            lyricLine = TTMLParser().parse(a)
        )
    }
    val neteaseSource = sources.filterIsInstance<LyricSourceData.NetEase>().firstOrNull()
    if (neteaseSource != null) {
        val n = neteaseSource.lyric
        val yrc = n.yrc?.lyric
        if (!yrc.isNullOrBlank()) {
            val translation = n.ytlrc?.lyric ?: n.tlyric?.lyric
            val yrcContent = yrc.trim()
            val hasYrcLines = yrcContent.lines().any { line ->
                val trimmed = line.trim()
                trimmed.startsWith("[") && trimmed.contains("]") && trimmed.contains("(")
            }
            if (hasYrcLines && translation != null) {
                Timber.tag("LyricUtils").d("yrc and tlyric")
                return LyricData(
                    isVerbatim = true,
                    isPureMusic = isPureMusic,
                    source = LyricSource.NetEaseCloudMusic,
                    lyricLine = attachPhonetics(YRCParser.parse(yrc, translation), n.romalrc?.lyric)
                )
            }
        }
    }

    // 如果 NetEase 没有逐字，再看 QQ 的逐字（qq.lyric + qq.trans）
    val qqSource = sources.filterIsInstance<LyricSourceData.QQMusic>().firstOrNull()
    if (qqSource != null) {
        val q = qqSource.lyric
        if (qqSource.isQRC && q.lyric.isNotBlank()) {
            Timber.tag("LyricUtils").d("qq.lyric and qq.trans")
            return LyricData(
                isVerbatim = true,
                isPureMusic = isPureMusic,
                source = LyricSource.QQMusic,
                lyricLine = attachPhonetics(QRCParser.parse(q.lyric, q.trans), q.roma)
            )
        }
        // 有些情况下 QQ 有逐字但没有 trans（视为非完整逐字），上面判断要求 trans 存在
    }

    // ===== 没有任何逐字歌词，按源优先回退 =====
    // 1) NetEase 非逐字（LRC）
    if (neteaseSource != null) {
        val n = neteaseSource.lyric
        val lrc = n.lrc?.lyric
        if (!lrc.isNullOrBlank()) {
            Timber.tag("LyricUtils").d("NetEase LRC")
            return LyricData(
                isVerbatim = false,
                isPureMusic = isPureMusic,
                source = LyricSource.NetEaseCloudMusic,
                lyricLine = attachPhonetics(LRCParser.parse(lrc, n.tlyric?.lyric), n.romalrc?.lyric)
            )
        }
    }

    // 2) QQ 非逐字（LRC）
    if (qqSource != null) {
        val q = qqSource.lyric
        val lrcText = qqSource.lrcContent ?: q.lyric
        if (lrcText.isNotBlank()) {
            Timber.tag("LyricUtils").d("QQ LRC")
            return LyricData(
                isVerbatim = false,
                isPureMusic = isPureMusic,
                source = LyricSource.QQMusic,
                lyricLine = attachPhonetics(LRCParser.parse(lrcText, q.trans), q.roma)
            )
        }
    }

    // 都没有，返回默认
    Timber.tag("LyricUtils").d("no lyric")
    return createDefaultLyricData(
        if (isPureMusic) "纯音乐，请欣赏" else "没有歌词",
        isPureMusic = isPureMusic,
        source = LyricSource.Empty
    )
}


fun String.parseAsTime(): Int {
    fun parseSecondsAndMillis(part: String): Int {
        val timeParts = part.split('.', limit = 2)
        val seconds = timeParts[0].toIntOrNull()?.times(1000) ?: 0

        // If there's no millisecond part, return seconds only.
        if (timeParts.size == 1) return seconds

        val millisStr = timeParts[1]
        // Pad the string to 3 digits on the right with '0'.
        // "4" -> "400", "45" -> "450", "456" -> "456"
        // Then take the first 3 characters to handle overly long inputs like "4567".
        val normalizedMillisStr = millisStr.padEnd(3, '0').substring(0, 3)
        val millis = normalizedMillisStr.toIntOrNull() ?: 0

        return seconds + millis
    }

    return try {
        val parts = this.split(":")
        when (parts.size) {
            3 -> {
                if (parts[2].contains('.')) {
                    // Format: HH:MM:SS.ms (TTML)
                    val hours = parts[0].toIntOrNull()?.times(3600 * 1000) ?: 0
                    val minutes = parts[1].toIntOrNull()?.times(60 * 1000) ?: 0
                    val secondsAndMillis = parseSecondsAndMillis(parts[2])
                    hours + minutes + secondsAndMillis
                } else {
                    // Format: MM:SS:CC (LRC with colon-separated centiseconds)
                    val minutes = parts[0].toIntOrNull()?.times(60 * 1000) ?: 0
                    val seconds = parts[1].toIntOrNull()?.times(1000) ?: 0
                    val centis = parts[2].toIntOrNull()?.times(10) ?: 0
                    minutes + seconds + centis
                }
            }

            2 -> { // Format: MM:SS.ms
                val minutes = parts[0].toIntOrNull()?.times(60 * 1000) ?: 0
                val secondsAndMillis = parseSecondsAndMillis(parts[1])
                minutes + secondsAndMillis
            }

            1 -> { // Format: SS.ms
                parseSecondsAndMillis(parts[0])
            }

            else -> 0
        }
    } catch (_: Exception) {
        0
    }
}
