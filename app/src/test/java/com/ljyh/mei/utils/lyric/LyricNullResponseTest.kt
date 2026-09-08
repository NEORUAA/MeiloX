package com.ljyh.mei.utils.lyric

import com.google.gson.Gson
import com.ljyh.mei.data.model.Lyric
import com.ljyh.mei.ui.model.LyricSource
import com.ljyh.mei.ui.model.LyricSourceData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricNullResponseTest {
    private fun source(json: String) =
        LyricSourceData.NetEase(Gson().fromJson(json, Lyric::class.java))

    @Test
    fun nullOrMissingYrcTextFallsBackToLrc() {
        for (yrc in listOf("null", "{}", """{"lyric":null}""", """{"lyric":""}""")) {
            val result = mergeLyrics(listOf(source(
                """{"code":200,"yrc":$yrc,"lrc":{"lyric":"[00:01.00]hello"}}"""
            )))
            assertEquals(LyricSource.NetEaseCloudMusic, result.source)
            assertFalse(result.isVerbatim)
            assertEquals(1, result.lyricLine.lines.size)
            assertEquals(1000, result.lyricLine.lines.single().start)
        }
    }

    @Test
    fun absentLyricsFallBackToEmptyLyrics() {
        for (json in listOf(
            """{"code":200}""",
            """{"code":200,"yrc":null,"lrc":null}""",
            """{"code":200,"yrc":{},"lrc":{}}""",
            """{"code":200,"yrc":{"lyric":null},"lrc":{"lyric":null}}"""
        )) {
            val netease = source(json)
            val result = mergeLyrics(listOf(netease))
            assertEquals(LyricSource.Empty, result.source)
            assertFalse(result.isVerbatim)
            assertNull(DuetDetector().singleDuet(netease))
        }
    }

    @Test
    fun pureMusicWithoutLyricsKeepsPureMusicState() {
        val result = mergeLyrics(
            listOf(source("""{"code":200,"pureMusic":true,"yrc":{},"lrc":null}""")),
            isPureMusic = true
        )
        assertTrue(result.isPureMusic)
        assertEquals(LyricSource.NetEaseCloudMusic, result.source)
    }

    @Test
    fun nullVerbatimTranslationFallsBackToRegularTranslation() {
        val result = mergeLyrics(listOf(source(
            """{"code":200,"yrc":{"lyric":"[1000,1000](1000,1000,0)hello"},"ytlrc":{"lyric":null},"tlyric":{"lyric":"[00:01.00]translation"}}"""
        )))
        assertTrue(result.isVerbatim)
        assertEquals(LyricSource.NetEaseCloudMusic, result.source)
        assertTrue(result.lyricLine.lines.isNotEmpty())
    }
}
