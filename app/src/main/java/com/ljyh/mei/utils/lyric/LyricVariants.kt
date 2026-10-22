package com.ljyh.mei.utils.lyric

import com.ljyh.mei.ui.model.LyricData
import com.ljyh.mei.ui.model.LyricSource
import com.ljyh.mei.ui.model.LyricSourceData
import com.ljyh.mei.ui.model.LyricVariant
import com.mocharealm.accompanist.lyrics.core.model.SyncedLyrics
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine

/** Keep word and line versions separate without changing automatic source priority. */
internal fun buildLyricVariants(
    sources: List<LyricSourceData>,
    isPureMusic: Boolean = false,
): List<LyricData> = buildList {
    fun addVersion(source: LyricSource, verbatim: Boolean, parse: () -> SyncedLyrics) {
        val lyrics = runCatching(parse).getOrNull()?.takeIf { it.lines.isNotEmpty() } ?: return
        add(LyricData(verbatim, isPureMusic, source, lyrics))
    }

    sources.filterIsInstance<LyricSourceData.AM>().firstOrNull()?.let { source ->
        addVersion(LyricSource.AM, true) { TTMLParser().parse(source.lyric) }
    }
    sources.filterIsInstance<LyricSourceData.NetEase>().firstOrNull()?.lyric?.let { lyric ->
        lyric.yrc?.lyric?.takeIf(String::isNotBlank)?.let { yrc ->
            addVersion(LyricSource.NetEaseCloudMusic, true) {
                attachPhonetics(
                    YRCParser.parse(yrc, lyric.ytlrc?.lyric ?: lyric.tlyric?.lyric),
                    lyric.romalrc?.lyric,
                )
            }
        }
        lyric.lrc?.lyric?.takeIf(String::isNotBlank)?.let { lrc ->
            addVersion(LyricSource.NetEaseCloudMusic, false) {
                attachPhonetics(LRCParser.parse(lrc, lyric.tlyric?.lyric), lyric.romalrc?.lyric)
            }
        }
    }
    sources.filterIsInstance<LyricSourceData.QQMusic>().firstOrNull()?.let { source ->
        if (source.isQRC && source.lyric.lyric.isNotBlank()) {
            addVersion(LyricSource.QQMusic, true) {
                attachPhonetics(QRCParser.parse(source.lyric.lyric, source.lyric.trans), source.lyric.roma)
            }
        }
        val lrc = source.lrcContent?.takeIf(String::isNotBlank)
            ?: source.lyric.lyric.takeIf { !source.isQRC && it.isNotBlank() }
        if (lrc != null) {
            addVersion(LyricSource.QQMusic, false) {
                attachPhonetics(LRCParser.parse(lrc, source.lyric.trans), source.lyric.roma)
            }
        }
    }
}

internal fun lyricVariantOf(data: LyricData): LyricVariant? = when (data.source) {
    LyricSource.AM -> LyricVariant.AppleMusic
    LyricSource.NetEaseCloudMusic -> if (data.isVerbatim) LyricVariant.NeteaseYrc else LyricVariant.NeteaseLrc
    LyricSource.QQMusic -> if (data.isVerbatim) LyricVariant.QqQrc else LyricVariant.QqLrc
    LyricSource.Empty, LyricSource.Loading -> null
}

internal fun lyricVariantPreference(source: LyricSource?, stored: String?): LyricVariant? =
    LyricVariant.entries.firstOrNull { it.name == stored && it.source == source }

internal fun LyricData.matchesLyricPreference(source: LyricSource?, variant: LyricVariant?): Boolean =
    (source == null || this.source == source) && (variant == null || lyricVariantOf(this) == variant)

internal fun lyricPreview(data: LyricData): String = data.lyricLine.lines.asSequence().map { line ->
    when (line) {
        is KaraokeLine -> line.syllables.joinToString("") { it.content }
        is SyncedLine -> line.content
        else -> ""
    }.trim()
}.firstOrNull(String::isNotBlank).orEmpty().take(160)
