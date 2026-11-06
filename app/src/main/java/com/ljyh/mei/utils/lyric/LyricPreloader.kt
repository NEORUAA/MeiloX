package com.ljyh.mei.utils.lyric

import com.ljyh.mei.data.model.Lyric
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.qq.u.LyricResult
import com.ljyh.mei.data.model.qq.u.SearchResult
import com.ljyh.mei.data.model.room.QQSong
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.source.lyrics.LyricRepository
import com.ljyh.mei.di.repository.QQSongRepository
import com.ljyh.mei.ui.model.LyricData
import com.ljyh.mei.ui.model.LyricSourceData
import com.ljyh.mei.utils.encrypt.QRCUtils
import com.ljyh.mei.utils.DownloadLyrics
import com.ljyh.mei.source.api.ProviderIds
import com.ljyh.mei.source.core.SourceRouter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * 歌词预加载器
 *
 * 独立于 [LyricManager] 运行，提前拉取下一首歌曲的歌词到内存缓存。
 * 所有网络请求使用私有方法，绝不更新共享 StateFlow，因此不会干扰当前歌词展示。
 */
@Singleton
class LyricPreloader @Inject constructor(
    private val repository: LyricRepository,
    private val qqSongRepository: QQSongRepository,
    private val sourceRouter: SourceRouter,
) {
    private val TAG = "LyricPreloader"

    /**
     * 预加载指定歌曲的歌词
     *
     * @param metadata 歌曲元数据
     * @return 合并后的歌词数据，失败或无需预加载时返回 null
     */
    suspend fun preload(metadata: MediaMetadata): LyricData? {
        val songId = metadata.lyricStorageKey()
        return try {
            fetchAndMerge(metadata)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            Timber.tag(TAG).w(error, "Unable to preload lyrics for %s", songId)
            null
        }
    }

    /** Download tags use the same host lyric sources without changing the displayed song. */
    suspend fun downloadLyrics(metadata: MediaMetadata): String? = withTimeoutOrNull(8_000L) {
        preload(metadata)?.let { data ->
            if (data.isPureMusic) "[00:00.00]纯音乐，请欣赏" else DownloadLyrics.encode(data.lyricLine)
        }
    }

    private suspend fun fetchAndMerge(metadata: MediaMetadata): LyricData? {
        val (legacy, provider) = coroutineScope {
            val identity = async { repository.neteaseId(metadata) }
            val netDeferred = async { identity.await()?.let { fetchNetEase(it) } ?: Resource.Error("No matching lyric identity") }
            val amDeferred = async { identity.await()?.let { fetchAM(it) } ?: Resource.Error("No matching lyric identity") }
            val qqDeferred = async { fetchQQ(metadata) }
            val providerDeferred = async {
                if (metadata.trackRef.providerId in setOf(ProviderIds.NETEASE, ProviderIds.LOCAL)) null
                else try { sourceRouter.lyrics(metadata.trackRef) }
                catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
            }
            Triple(netDeferred.await(), amDeferred.await(), qqDeferred.await()) to providerDeferred.await()
        }
        val (netResult, amResult, qqResult) = legacy

        return withContext(Dispatchers.IO) {
            val isPureMusic = (netResult as? Resource.Success)?.data?.pureMusic == true
            val sources = mutableListOf<LyricSourceData>()
            provider?.let { sources.add(LyricSourceData.Provider(it)) }

            (amResult as? Resource.Success)?.let { sources.add(LyricSourceData.AM(it.data)) }
            (netResult as? Resource.Success)?.data?.let { sources.add(LyricSourceData.NetEase(it)) }
            (qqResult as? Resource.Success)?.data?.musicMusichallSongPlayLyricInfoGetPlayLyricInfo?.data?.let { data ->
                try {
                    val isQRC = data.qrcT != 0
                    val decoded = data.copy(
                        lyric = QRCUtils.decodeLyric(data.lyric),
                        trans = QRCUtils.decodeLyric(data.trans, true),
                        roma = QRCUtils.decodeLyric(data.roma)
                    )
                    sources.add(LyricSourceData.QQMusic(decoded, isQRC, null))
                } catch (e: Exception) {
                    Timber.e(e, "QRC decoding failed in preload")
                }
            }

            mergeLyrics(sources, isPureMusic)
        }
    }

    private suspend fun fetchNetEase(id: String): Resource<Lyric> {
        return try {
            repository.getLyricV1(id)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Timber.e(e, "NetEase preload fetch error")
            Resource.Error("NetEase fetch failed")
        }
    }

    private suspend fun fetchAM(id: String): Resource<String> {
        return try {
            repository.getAMLLyric(id)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Timber.e(e, "AML preload fetch error")
            Resource.Error("AML fetch failed")
        }
    }

    private suspend fun fetchQQ(metadata: MediaMetadata): Resource<LyricResult> {
        val songId = metadata.lyricStorageKey()

        val localSong = qqSongRepository.getQQSong(songId).firstOrNull()
        if (localSong != null) {
            return try {
                repository.getLyricNew(
                    localSong.title, localSong.album, localSong.artist,
                    localSong.duration, localSong.qid.toLong()
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Timber.e(e, "QQ preload fetch error")
                Resource.Error("QQ fetch failed")
            }
        }

        // 静默搜索：查询但不写入任何共享 StateFlow
        val best = searchSilent(metadata) ?: return Resource.Error("No QQ match found")

        val qqSong = QQSong(
            id = songId,
            qid = best.id.toString(),
            title = best.title,
            artist = best.singer.joinToString(",") { it.name },
            album = best.album.title,
            duration = best.interval
        )
        // A preload must never overwrite a manual binding selected by the foreground load.
        return try {
            repository.getLyricNew(
                qqSong.title, qqSong.album, qqSong.artist,
                qqSong.duration, qqSong.qid.toLong()
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Timber.e(e, "QQ preload fetch error after search")
            Resource.Error("QQ fetch failed")
        }
    }

    // ==================== 静默搜索（不写入共享 StateFlow） ====================

    /**
     * 搜索 QQ 音乐并匹配最佳结果，不产生任何副作用。
     *
     * 与 [LyricManager.searchAndMatchBest] 逻辑相同，但不写入 _qqSearchResult。
     */
    private suspend fun searchSilent(
        metadata: MediaMetadata
    ): SearchResult.Request.Data.Body.ItemSong? {
        val currentDurationSec = metadata.duration / 1000
        val artistName = metadata.artists.firstOrNull()?.name ?: ""
        val title = metadata.title
        val cleanedTitle = cleanTitle(title)

        if (cleanedTitle != title) {
            Timber.tag(TAG).d("silent QQ search: $cleanedTitle")
            trySearchSilent(cleanedTitle, currentDurationSec)?.let { return it }
        }

        Timber.tag(TAG).d("silent QQ search retry: $title")
        trySearchSilent(title, currentDurationSec)?.let { return it }

        if (artistName.isNotBlank() && cleanedTitle != title) {
            val combined = "$cleanedTitle $artistName"
            trySearchSilent(combined, currentDurationSec)?.let { return it }
        }

        if (artistName.isNotBlank()) {
            val combined = "$title $artistName"
            trySearchSilent(combined, currentDurationSec)?.let { return it }
        }

        return null
    }

    private suspend fun trySearchSilent(
        keyword: String,
        targetDurationSec: Long
    ): SearchResult.Request.Data.Body.ItemSong? {
        val result = repository.searchNew(keyword)
        if (result !is Resource.Success) return null
        val songs = result.data.request.data.body.itemSong
        return songs.take(5).firstOrNull { song ->
            abs(targetDurationSec - song.interval) <= 5
        }
    }

    private fun cleanTitle(title: String): String {
        return title
            .replace(Regex("""[\(（][^)）]*[\)）]"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }
}
