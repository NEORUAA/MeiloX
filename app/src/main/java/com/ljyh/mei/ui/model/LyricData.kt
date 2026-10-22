package com.ljyh.mei.ui.model

import com.ljyh.mei.data.model.Lyric
import com.ljyh.mei.data.model.qq.u.LyricResult
import com.ljyh.mei.data.model.qq.u.SearchResult
import com.mocharealm.accompanist.lyrics.core.model.SyncedLyrics


data class LyricData(
    val isVerbatim: Boolean = false,
    val isPureMusic: Boolean = false,
    val source: LyricSource = LyricSource.Empty,
    val lyricLine: SyncedLyrics
)


/** A selectable lyric source or QQ search match for the current song. */
data class LyricCandidate(
    val key: String,
    val source: LyricSource,
    val title: String,
    val artist: String,
    val album: String,
    val coverUrl: String,
    val durationMs: Long,
    val qqSong: SearchResult.Request.Data.Body.ItemSong? = null,
    val variant: LyricVariant? = null,
    val preview: String = "",
    val isSelected: Boolean = false,
)

/** Stable per-song choices; a null variant retains the legacy source-only preference. */
enum class LyricVariant(val source: LyricSource) {
    AppleMusic(LyricSource.AM),
    NeteaseYrc(LyricSource.NetEaseCloudMusic),
    NeteaseLrc(LyricSource.NetEaseCloudMusic),
    QqQrc(LyricSource.QQMusic),
    QqLrc(LyricSource.QQMusic),
}

sealed class LyricSourceData(val source: LyricSource, val priority: Int) {
    data class NetEase(val lyric: Lyric) : LyricSourceData(LyricSource.NetEaseCloudMusic, 2)
    data class QQMusic(
        val lyric: LyricResult.MusicMusichallSongPlayLyricInfoGetPlayLyricInfo.Data,
        val isQRC: Boolean = true,
        val lrcContent: String? = null
    ) : LyricSourceData(LyricSource.QQMusic, 1)

    data class AM(val lyric: String) : LyricSourceData(LyricSource.AM, 3)
}

enum class LyricSource {
    Empty,
    NetEaseCloudMusic,
    QQMusic,
    AM,
    Loading
}
