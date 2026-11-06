package com.ljyh.mei.source.lyrics

import android.util.Base64
import com.ljyh.mei.data.model.qq.u.GetLyricData
import com.ljyh.mei.data.model.qq.u.GetSearchData
import com.ljyh.mei.data.model.qq.u.LyricResult
import com.ljyh.mei.data.model.qq.u.SearchResult
import com.ljyh.mei.data.network.QQMusicUApiService
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.data.network.safeApiCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Existing QQ lyric matching is an extension, not an advertised QQ audio provider. */
class QQLyricsExtension(private val api: QQMusicUApiService) {
    suspend fun search(keyword: String): Resource<SearchResult> = withContext(Dispatchers.IO) {
        safeApiCall {
            api.search(GetSearchData(
                comm = GetSearchData.Comm(),
                req = GetSearchData.Req(param = GetSearchData.Req.Param(query = keyword)),
            ))
        }
    }

    suspend fun getLyrics(
        title: String,
        album: String,
        artist: String,
        duration: Long,
        id: Long,
        wordTimed: Boolean,
    ): Resource<LyricResult> = withContext(Dispatchers.IO) {
        safeApiCall {
            val lyric = if (wordTimed) {
                GetLyricData.GetPlayLyricInfo.GetLyric(
                    singerName = encode(artist), songName = encode(title), albumName = encode(album),
                    interval = duration, songID = id,
                )
            } else {
                GetLyricData.GetPlayLyricInfo.GetLyric(
                    singerName = encode(artist), songName = encode(title), albumName = encode(album),
                    interval = duration, songID = id, qrc = 0, qrcT = 0,
                )
            }
            api.getLyric(GetLyricData(
                comm = GetLyricData.Comm(),
                getPlayLyricInfo = GetLyricData.GetPlayLyricInfo(param = lyric),
            ))
        }
    }

    private fun encode(value: String): String = Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
}
