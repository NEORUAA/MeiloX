package com.ljyh.mei.utils

import com.ljyh.mei.data.model.Lyric
import com.ljyh.mei.utils.lyric.TTMLParser
import com.ljyh.mei.utils.lyric.YRCParser
import com.mocharealm.accompanist.lyrics.core.model.ISyncedLine
import com.mocharealm.accompanist.lyrics.core.model.SyncedLyrics
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
import java.util.Locale

/** Audio tags need readable line-synced lyrics, rather than raw TTML or YRC payloads. */
internal object DownloadLyrics {
    private val lrcTimeline = Regex("""(?m)^\s*\[\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?].*\S.*$""")

    fun plainNetEase(lyric: Lyric?): String? {
        if (lyric?.pureMusic == true) return "[00:00.00]纯音乐，请欣赏"
        val main = lyric?.lrc?.lyric?.withoutContributorMetadata()
            ?.takeIf(lrcTimeline::containsMatchIn) ?: return null
        val translation = sequenceOf(lyric.tlyric?.lyric, lyric.ytlrc?.lyric)
            .mapNotNull { it?.withoutContributorMetadata() }
            .firstOrNull(lrcTimeline::containsMatchIn)
        return if (translation == null || translation == main) main else "$main\n$translation"
    }

    fun select(netEase: Lyric?, ttml: String?): String? {
        plainNetEase(netEase)?.let { return it }
        val parser = TTMLParser()
        ttml?.trim()?.takeIf(parser::canParse)?.let { content ->
            runCatching { encode(parser.parse(content)) }.getOrNull()?.let { return it }
        }
        val yrc = netEase?.yrc?.lyric?.withoutContributorMetadata()
            ?.takeIf(YRCParser::canParse) ?: return null
        val translation = sequenceOf(netEase.ytlrc?.lyric, netEase.tlyric?.lyric)
            .mapNotNull { it?.withoutContributorMetadata() }
            .firstOrNull(lrcTimeline::containsMatchIn)
        return runCatching { encode(YRCParser.parse(yrc, translation)) }.getOrNull()
    }

    private fun encode(lyrics: SyncedLyrics): String? = lyrics.lines
        .flatMap(::records)
        .sortedBy(Record::time)
        .joinToString("\n") { "[${timestamp(it.time)}]${it.text}" }
        .takeIf(String::isNotBlank)

    private fun records(line: ISyncedLine): List<Record> = when (line) {
        is KaraokeLine.MainKaraokeLine -> buildList {
            addKaraoke(line)
            line.accompanimentLines.orEmpty().forEach { addKaraoke(it) }
        }
        is KaraokeLine.AccompanimentKaraokeLine -> buildList { addKaraoke(line) }
        is SyncedLine -> buildList { addText(line.start, line.content, line.translation) }
        else -> emptyList()
    }

    private fun MutableList<Record>.addKaraoke(line: KaraokeLine) =
        addText(line.start, line.syllables.joinToString("") { it.content }, line.translation)

    private fun MutableList<Record>.addText(time: Int, text: String, translation: String?) {
        val main = text.singleLine()
        if (main.isNotBlank()) add(Record(time, main))
        translation?.singleLine()?.takeIf { it.isNotBlank() && it != main }
            ?.let { add(Record(time, it)) }
    }

    private fun timestamp(time: Int): String {
        val safe = time.coerceAtLeast(0)
        return String.format(Locale.ROOT, "%02d:%02d.%03d", safe / 60_000, safe % 60_000 / 1_000, safe % 1_000)
    }

    private fun String.withoutContributorMetadata(): String = lineSequence()
        .filterNot { it.trimStart().startsWith("{\"t\":") && it.contains("\"c\":[") }
        .joinToString("\n")
        .trim()

    private fun String.singleLine(): String = replace(Regex("\\s+"), " ").trim()

    private data class Record(val time: Int, val text: String)
}
