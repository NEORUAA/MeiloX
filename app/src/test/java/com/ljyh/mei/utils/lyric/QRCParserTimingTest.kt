package com.ljyh.mei.utils.lyric

import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import org.junit.Assert.assertEquals
import org.junit.Test

class QRCParserTimingTest {
    @Test
    fun reversedWordTimesUseTheFullLineRangeWithoutReorderingText() {
        val line = parseLine("[1000,1000]First(1800,200)Last(1000,100)")

        assertEquals(1000, line.start)
        assertEquals(2000, line.end)
        assertEquals(listOf("First", "Last"), line.syllables.map { it.content })
        assertEquals(listOf(1800, 1000), line.syllables.map { it.start })
    }

    @Test
    fun overlappingWordsDoNotTruncateTheLineAtTheLastWord() {
        val line = parseLine("[1000,3000]First(1000,3000)Last(2200,100)")

        assertEquals(1000, line.start)
        assertEquals(4000, line.end)
    }

    @Test
    fun reversedBackgroundWordTimesUseTheFullAccompanimentRange() {
        val line = parseLine(
            "[1000,1000]Main(1000,1000)\n[bg:First(1800,200)Last(1000,100)]"
        )
        val background = line.accompanimentLines!!.single()

        assertEquals(1000, background.start)
        assertEquals(2000, background.end)
        assertEquals(listOf("First", "Last"), background.syllables.map { it.content })
    }

    @Test
    fun reversedColonTimingKeepsBothTokensWithinTheMergedSyllable() {
        val line = parseLine("[1000,1000]Singer(1800,200):(1000,100)")
        val syllable = line.syllables.single()

        assertEquals("Singer:", syllable.content)
        assertEquals(1000, syllable.start)
        assertEquals(2000, syllable.end)
    }

    @Test
    fun colonMergeDoesNotTruncateAnOverlappingWord() {
        val line = parseLine("[1000,3000]Singer(1000,3000):(1800,100)")

        assertEquals(1000, line.start)
        assertEquals(4000, line.end)
    }

    @Test
    fun orderedRelativeWordTimesAndTranslationRemainUnchanged() {
        val line = QRCParser.parse(
            "[1000,1000]First(0,400)Last(400,600)",
            "[00:01.00]Translation",
        ).lines.single() as KaraokeLine.MainKaraokeLine

        assertEquals(1000, line.start)
        assertEquals(2000, line.end)
        assertEquals("Translation", line.translation)
        assertEquals(listOf(1000, 1400), line.syllables.map { it.start })
    }

    private fun parseLine(content: String) =
        QRCParser.parse(content, null).lines.single() as KaraokeLine.MainKaraokeLine
}
