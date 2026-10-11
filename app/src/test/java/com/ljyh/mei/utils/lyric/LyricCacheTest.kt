package com.ljyh.mei.utils.lyric

import com.ljyh.mei.data.model.qq.u.emptyData
import com.ljyh.mei.data.model.room.CachedLyric
import com.ljyh.mei.ui.model.LyricData
import com.ljyh.mei.ui.model.LyricSource
import com.ljyh.mei.ui.model.LyricSourceData
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
import org.junit.Assert.*
import org.junit.Test

class LyricCacheTest {
    private val qrc = "[15800,1200]First(15800,600) line(16400,600)"
    private val lrc = "[00:15.80]First line"
    private val translation = "[00:15.80]Translated line"

    @Test
    fun qqVerbatimCachePreservesQrcAndDecodedTranslation() {
        val source = LyricSourceData.QQMusic(
            emptyData.copy(lyric = qrc, trans = translation, qrcT = 1),
            isQRC = true,
            lrcContent = lrc
        )
        val original = mergeLyrics(listOf(source))
        val (content, trans, parser) = buildCacheInfo(listOf(source), original)

        assertEquals(qrc, content)
        assertEquals("QRC", parser)
        val restored = cache(content!!, parser, trans).toLyricData()!!
        assertEquals(original, restored)
        val line = restored.lyricLine.lines.single() as KaraokeLine.MainKaraokeLine
        assertEquals(2, line.syllables.size)
        assertEquals(15800, line.start)
        assertEquals(17000, line.end)
        assertEquals("Translated line", line.translation)
    }

    @Test
    fun legacyLrcMislabeledAsQrcRecoversWithoutClearingCache() {
        val restored = cache(lrc, "QRC", translation).toLyricData()!!

        assertFalse(restored.isVerbatim)
        assertEquals(LyricSource.QQMusic, restored.source)
        val line = restored.lyricLine.lines.single() as SyncedLine
        assertEquals("First line", line.content)
        assertEquals(15800, line.start)
        assertEquals("Translated line", line.translation)
    }

    @Test
    fun qqLineCacheKeepsMatchingLrcParser() {
        val source = LyricSourceData.QQMusic(
            emptyData.copy(lyric = qrc, trans = translation, qrcT = 1),
            lrcContent = lrc
        )
        val original = LyricData(
            source = LyricSource.QQMusic,
            lyricLine = LRCParser.parse(lrc, translation)
        )
        val (content, trans, parser) = buildCacheInfo(listOf(source), original)

        assertEquals(lrc, content)
        assertEquals("LRC", parser)
        assertEquals(original, cache(content!!, parser, trans, false).toLyricData())
    }

    @Test
    fun unparseableCacheDoesNotReplaceVisibleLyrics() {
        assertNull(cache("invalid lyrics", "QRC").toLyricData())
        assertNull(cache("", "LRC", isVerbatim = false).toLyricData())
    }

    private fun cache(
        content: String,
        parser: String,
        translation: String? = null,
        isVerbatim: Boolean = true
    ) = CachedLyric(
        songId = "issue-27",
        content = content,
        translation = translation,
        isVerbatim = isVerbatim,
        isPureMusic = false,
        sourceName = LyricSource.QQMusic.name,
        parserType = parser,
        updatedAt = 0
    )
}
