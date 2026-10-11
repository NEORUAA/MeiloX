package com.ljyh.mei.utils.lyric

import com.ljyh.mei.data.model.Lyric
import com.ljyh.mei.data.model.qq.u.emptyData
import com.ljyh.mei.ui.model.LyricSource
import com.ljyh.mei.ui.model.LyricSourceData
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DuetSourceFormatTest {
    private val detector = DuetDetector()
    private val lrc = """
        [00:01.00]A: First verse
        [00:03.00]B: Second verse
    """.trimIndent()
    private val qrc = """
        [1000,1000]First (1000,500)verse(1500,500)
        [3000,1000]Second (3000,500)verse(3500,500)
    """.trimIndent()
    private val yrc = """
        [1000,1000](1000,500,0)First (1500,500,0)verse
        [3000,1000](3000,500,0)Second (3500,500,0)verse
    """.trimIndent()

    @Test
    fun qqVerbatimLyricsUseQrcParserDuringDuetMerge() {
        assertTrue(detector.isDuetLikely(lrc))

        val result = detector.mergeWithDuet(netease(), qq(qrc))

        assertNotNull(result)
        assertEquals(LyricSource.QQMusic, result!!.source)
        assertTrue(result.isVerbatim)
        assertEquals(listOf("First verse", "Second verse"), result.lyricLine.lines.map {
            (it as KaraokeLine).syllables.joinToString("") { syllable -> syllable.content }
        })
        assertEquals(listOf(1000, 3000), result.lyricLine.lines.map { it.start })
    }

    @Test
    fun neteaseVerbatimLyricsRetainYrcParserAndSourcePriority() {
        val result = detector.mergeWithDuet(netease(yrc), qq(qrc))

        assertNotNull(result)
        assertEquals(LyricSource.NetEaseCloudMusic, result!!.source)
        assertTrue(result.isVerbatim)
        assertEquals(listOf("First verse", "Second verse"), result.lyricLine.lines.map {
            (it as KaraokeLine).syllables.joinToString("") { syllable -> syllable.content }
        })
        assertEquals(listOf(1000, 3000), result.lyricLine.lines.map { it.start })
    }

    @Test
    fun invalidQrcFallsBackToUsableLrc() {
        val result = detector.mergeWithDuet(netease(), qq("invalid QRC"))

        assertNotNull(result)
        assertEquals(LyricSource.QQMusic, result!!.source)
        assertFalse(result.isVerbatim)
        assertEquals(listOf("A: First verse", "B: Second verse"), result.lyricLine.lines.map {
            (it as SyncedLine).content
        })
    }

    @Test
    fun invalidYrcFallsBackToUsableLrc() {
        val result = detector.singleDuet(netease("invalid YRC"))

        assertNotNull(result)
        assertEquals(LyricSource.NetEaseCloudMusic, result!!.source)
        assertFalse(result.isVerbatim)
        assertEquals(2, result.lyricLine.lines.size)
    }

    @Test
    fun emptyParsedLyricsAreNotReturnedAsDuetOutput() {
        assertNull(detector.singleDuet(netease("invalid YRC", "invalid LRC")))
        assertNull(detector.mergeWithDuet(netease(), qq("invalid QRC", "invalid LRC")))
    }

    private fun netease(verbatim: String? = null, line: String = lrc) =
        LyricSourceData.NetEase(
            Lyric(
                code = 200,
                klyric = null,
                lrc = Lyric.Lrc(line, 1),
                qfy = false,
                romalrc = null,
                sfy = false,
                sgc = false,
                tlyric = null,
                ytlrc = null,
                yrc = verbatim?.let { Lyric.Yrc(it, 1) },
                pureMusic = false
            )
        )

    private fun qq(verbatim: String, line: String = lrc) = LyricSourceData.QQMusic(
        lyric = emptyData.copy(lyric = verbatim, qrcT = 1),
        isQRC = true,
        lrcContent = line
    )
}
