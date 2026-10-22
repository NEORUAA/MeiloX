package com.ljyh.mei.utils.lyric

import com.google.gson.Gson
import com.ljyh.mei.data.model.Lyric
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.qq.u.emptyData
import com.ljyh.mei.data.model.room.CachedLyric
import com.ljyh.mei.ui.model.LyricData
import com.ljyh.mei.ui.model.LyricSource
import com.ljyh.mei.ui.model.LyricSourceData
import com.ljyh.mei.ui.model.LyricVariant
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
import org.junit.Assert.*
import org.junit.Test

class LyricCacheTest {
    private val qrc = "[15800,1200]First(15800,600) line(16400,600)"
    private val lrc = "[00:15.80]First line"
    private val translation = "[00:15.80]Translated line"

    @Test
    fun pronunciationUsesTheClosestLineBeforeAnEarlierToleranceMatch() {
        val main = LRCParser.parse("[00:01.00]No pronunciation\n[00:01.60]Has pronunciation", null)
        for (timing in listOf("00:01.60", "00:01.59")) {
            val attached = attachPhonetics(main, "[$timing]second pronunciation")
            assertTrue(attached.lines.first() is SyncedLine)
            assertEquals("second pronunciation", (attached.lines[1] as KaraokeLine).phonetic)
        }
    }

    @Test
    fun malformedOptionalPronunciationKeepsUsableMainLyrics() {
        val main = QRCParser.parse(qrc, translation)
        assertSame(main, attachPhonetics(main, "[2147483648,1000]invalid(2147483648,1000)"))
        val source = LyricSourceData.QQMusic(emptyData.copy(
            lyric = qrc, trans = translation, qrcT = 1,
            roma = "[2147483648,1000]invalid(2147483648,1000)",
        ), isQRC = true)
        assertEquals(main, mergeLyrics(listOf(source)).lyricLine)
    }

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
    fun loadedCandidatesOnlyIncludeUsableVersionsOnce() {
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
        val candidates = loadedLyricCandidates(
            metadata, listOf(netease, emptyApple, netease, qq, createDefaultLyricData("Loading")),
        )
        assertEquals(listOf(LyricVariant.NeteaseLrc, LyricVariant.QqLrc), candidates.map { it.variant })
        assertEquals("Artist", candidates.first().artist)
        assertEquals(180_000L, candidates.first().durationMs)
        assertEquals("First line", candidates.first().preview)
        assertTrue(candidates.all { it.qqSong == null })
    }

    @Test
    fun wordAndLineVersionsKeepIndependentTimelinesAndCacheFormats() {
        val netease = LyricSourceData.NetEase(Gson().fromJson(
            """{"code":200,"yrc":{"lyric":"[1000,1000](1000,1000,0)Word version"},"lrc":{"lyric":"[00:02.00]Line version"},"romalrc":{"lyric":"[00:02.00]line pronunciation"}}""",
            Lyric::class.java,
        ))
        val qq = LyricSourceData.QQMusic(
            emptyData.copy(lyric = qrc, trans = translation, qrcT = 1), isQRC = true, lrcContent = lrc,
        )
        val sources = listOf(netease, qq)
        val variants = buildLyricVariants(sources)
        assertEquals(
            listOf(LyricVariant.NeteaseYrc, LyricVariant.NeteaseLrc, LyricVariant.QqQrc, LyricVariant.QqLrc),
            variants.map(::lyricVariantOf),
        )
        assertEquals(listOf(1_000, 2_000, 15_800, 15_800), variants.map { it.lyricLine.lines.single().start })
        assertEquals("line pronunciation", (variants[1].lyricLine.lines.single() as KaraokeLine).phonetic)
        assertEquals("QRC", buildCacheInfo(sources, variants[2]).third)
        assertEquals("LRC", buildCacheInfo(sources, variants[3]).third)
        assertEquals(lrc, buildCacheInfo(sources, variants[3]).first)
    }

    @Test
    fun explicitLineChoiceCanReplaceWordsWithoutChangingAutomaticPriority() {
        val source = LyricSourceData.QQMusic(emptyData.copy(lyric = qrc, qrcT = 1), lrcContent = lrc)
        val variants = buildLyricVariants(listOf(source))
        val words = variants.first { it.isVerbatim }
        val lines = variants.first { !it.isVerbatim }
        assertSame(words, selectLyricUpdate(words, lines, true, true))
        assertSame(lines, selectPreferredLyricUpdate(
            words, lines, LyricSource.QQMusic, true, true, preferredVariant = LyricVariant.QqLrc,
        ))
        assertTrue(lines.matchesLyricPreference(LyricSource.QQMusic, LyricVariant.QqLrc))
        assertFalse(words.matchesLyricPreference(LyricSource.QQMusic, LyricVariant.QqLrc))
        assertTrue(words.matchesLyricPreference(LyricSource.QQMusic, null))
        assertSame(lines, selectPreferredLyricUpdate(
            lines, createDefaultLyricData("No lyrics"), LyricSource.QQMusic, false, true,
            preferredVariant = LyricVariant.QqLrc,
        ))
    }

    @Test
    fun manualLineSelectionRefreshesCacheWhenThePublishedSnapshotIsUnchanged() {
        val source = LyricSourceData.QQMusic(
            emptyData.copy(lyric = qrc, trans = translation, qrcT = 1), lrcContent = lrc,
        )
        val sources = listOf(source)
        val variants = buildLyricVariants(sources)
        val words = variants.first { it.isVerbatim }
        val lines = variants.first { !it.isVerbatim }
        val previous = buildLyricCacheUpdate("manual-choice", words, buildCacheInfo(sources, words), null)!!
            .copy(updatedAt = 100)

        // Selection already published the available line snapshot before the merge runs.
        val merged = buildLyricVariants(sources).first { !it.isVerbatim }
        val selected = selectPreferredLyricUpdate(
            lines, merged, LyricSource.QQMusic, true, true, preferredVariant = LyricVariant.QqLrc,
        )
        assertSame(lines, selected)
        val update = buildLyricCacheUpdate(
            "manual-choice", selected, buildCacheInfo(sources, merged), previous,
        )!!
        val restored = update.toLyricData()!!
        assertEquals(lines, restored)
        assertTrue(restored.matchesLyricPreference(LyricSource.QQMusic, LyricVariant.QqLrc))
        assertEquals("LRC", update.parserType)

        // Repeated source completions must not rewrite the same persistent content.
        assertNull(buildLyricCacheUpdate(
            "manual-choice", selected, buildCacheInfo(sources, merged), update.copy(updatedAt = 200),
        ))
    }

    @Test
    fun lyricCacheDeduplicationTracksStoredContentInsteadOfDisplayMetadata() {
        val source = LyricSourceData.QQMusic(
            emptyData.copy(lyric = qrc, trans = translation, qrcT = 1), isQRC = true,
        )
        val sources = listOf(source)
        val words = mergeLyrics(sources)
        val info = buildCacheInfo(sources, words)
        val stored = buildLyricCacheUpdate("cached-song", words, info, null)!!.copy(updatedAt = 100)
        val withPhonetics = words.copy(lyricLine = attachPhonetics(words.lyricLine, "[00:15.80]pronunciation"))
        assertNotEquals(words, withPhonetics)
        assertNull(buildLyricCacheUpdate("cached-song", withPhonetics, info, stored))
        assertNotNull(buildLyricCacheUpdate("other-song", words, info, stored))
        assertNotNull(buildLyricCacheUpdate("cached-song", words, Triple(qrc, "[00:15.80]New translation", "QRC"), stored))
    }

    @Test
    fun versionPreferenceKeepsLegacySourcesAndRejectsMismatchedOrUnknownValues() {
        assertNull(lyricVariantPreference(LyricSource.NetEaseCloudMusic, null))
        assertNull(lyricVariantPreference(LyricSource.NetEaseCloudMusic, "future-version"))
        assertNull(lyricVariantPreference(LyricSource.NetEaseCloudMusic, LyricVariant.QqLrc.name))
        assertEquals(LyricVariant.QqLrc, lyricVariantPreference(LyricSource.QQMusic, LyricVariant.QqLrc.name))
        assertNull(lyricVariantPreference(null, LyricVariant.QqLrc.name))
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
