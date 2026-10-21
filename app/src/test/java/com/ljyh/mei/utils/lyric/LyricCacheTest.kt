package com.ljyh.mei.utils.lyric

import com.ljyh.mei.data.model.MediaMetadata
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

    @Test
    fun manualSourceCanReplaceHigherPriorityLyricsAndResetRestoresAutomaticChoice() {
        val apple = LyricSourceData.AM(
            """<tt xmlns="http://www.w3.org/ns/ttml"><body><div><p begin="00:01.000" end="00:02.000"><span begin="00:01.000" end="00:02.000">Apple verse</span></p></div></body></tt>"""
        )
        val qq = LyricSourceData.QQMusic(
            emptyData.copy(lyric = qrc, trans = translation, qrcT = 1), isQRC = true,
        )
        val sources = listOf(apple, qq)
        val automatic = mergeLyrics(selectLyricSources(sources, null))
        assertEquals(LyricSource.AM, automatic.source)

        val manual = mergeLyrics(selectLyricSources(sources, LyricSource.QQMusic))
        val selected = selectPreferredLyricUpdate(
            automatic, manual, LyricSource.QQMusic, sourcesFinished = true, alignmentFinished = true,
        )
        assertEquals(LyricSource.QQMusic, selected.source)
        assertEquals(qrc, buildCacheInfo(listOf(qq), selected).first)
        val restored = selectPreferredLyricUpdate(
            selected, mergeLyrics(selectLyricSources(sources, null)), null,
            sourcesFinished = true, alignmentFinished = true,
        )
        assertEquals(LyricSource.AM, restored.source)
        assertEquals(sources, selectLyricSources(sources, null))
    }

    @Test
    fun unavailableManualSourceDoesNotSilentlyChooseAnotherProvider() {
        val qq = LyricSourceData.QQMusic(emptyData.copy(lyric = qrc, qrcT = 1), isQRC = true)
        val selected = selectLyricSources(listOf(qq), LyricSource.NetEaseCloudMusic)
        assertTrue(selected.isEmpty())
        assertEquals(LyricSource.Empty, mergeLyrics(selected).source)
    }

    @Test
    fun nativeCandidatesOnlyIncludeUsableProvidersOnce() {
        val metadata = MediaMetadata(
            id = 123,
            title = "Song",
            coverUrl = "cover",
            artists = listOf(MediaMetadata.Artist(1, "Artist")),
            duration = 180_000,
            album = MediaMetadata.Album(2, "Album"),
        )
        val netease = LyricData(
            source = LyricSource.NetEaseCloudMusic,
            lyricLine = LRCParser.parse(lrc, translation),
        )
        val emptyApple = LyricData(
            source = LyricSource.AM,
            lyricLine = LRCParser.parse("", null),
        )
        val qq = netease.copy(source = LyricSource.QQMusic)
        val candidates = nativeLyricCandidates(
            metadata, listOf(netease, emptyApple, netease, qq, createDefaultLyricData("Loading")),
        )
        assertEquals(1, candidates.size)
        assertEquals(LyricSource.NetEaseCloudMusic, candidates.single().source)
        assertEquals("Artist", candidates.single().artist)
        assertEquals(180_000L, candidates.single().durationMs)
        assertNull(candidates.single().qqSong)
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
