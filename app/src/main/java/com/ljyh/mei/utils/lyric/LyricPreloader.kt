package com.ljyh.mei.utils.lyric

import com.ljyh.mei.data.model.Lyric
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.SongSourceIdentity
import com.ljyh.mei.data.model.qq.u.LyricResult
import com.ljyh.mei.data.model.qq.u.SearchResult
import com.ljyh.mei.data.model.room.QQSong
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.data.repository.PlayerRepository
import com.ljyh.mei.data.session.SessionChangedException
import com.ljyh.mei.data.session.SessionStamp
import com.ljyh.mei.data.session.SessionStore
import com.ljyh.mei.di.repository.QQSongRepository
import com.ljyh.mei.ui.model.LyricData
import com.ljyh.mei.ui.model.LyricSourceData
import com.ljyh.mei.utils.encrypt.QRCUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
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
    private val repository: PlayerRepository,
    private val qqSongRepository: QQSongRepository,
    private val sessions: SessionStore,
) {
    private val TAG = "LyricPreloader"

    /**
     * 预加载指定歌曲的歌词
     *
     * @param metadata 歌曲元数据
     * @return 合并后的歌词数据，失败或无需预加载时返回 null
     */
    suspend fun preload(metadata: MediaMetadata, owner: SessionStamp = sessions.snapshot()): LyricData? {
        val source = metadata.source ?: SongSourceIdentity(metadata.id)
        require(source.entryId == metadata.id)
        source.requireAccount(owner.identity)
        requireOwner(owner)
        return fetchAndMerge(metadata, source, owner).also { requireOwner(owner) }
    }

    private suspend fun requireOwner(owner: SessionStamp) {
        currentCoroutineContext().ensureActive()
        sessions.requireCurrent(owner)
        if (sessions.recoveryRequired.value) throw SessionChangedException()
    }

    private suspend fun fetchAndMerge(metadata: MediaMetadata, source: SongSourceIdentity, owner: SessionStamp): LyricData? {
        val (netResult, amResult, qqResult) = coroutineScope {
            val netDeferred = async { fetchNetEase(source.key, owner) }
            val amDeferred = async { fetchAM(source.songId.toString(), owner) }
            val qqDeferred = async { fetchQQ(metadata, source.key, owner) }
            Triple(netDeferred.await(), amDeferred.await(), qqDeferred.await())
        }

        return withContext(Dispatchers.IO) {
            val isPureMusic = (netResult as? Resource.Success)?.data?.pureMusic == true
            val sources = mutableListOf<LyricSourceData>()

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
                    Timber.tag(TAG).w("QRC preload decoding failed: %s", e.javaClass.simpleName)
                }
            }

            mergeLyrics(sources, isPureMusic)
        }
    }

    private suspend fun fetchNetEase(id: String, owner: SessionStamp): Resource<Lyric> {
        return try {
            requireOwner(owner)
            repository.getLyricV1(id, owner).also { requireOwner(owner) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SessionChangedException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).w("NetEase preload fetch failed: %s", e.javaClass.simpleName)
            Resource.Error("NetEase fetch failed")
        }
    }

    private suspend fun fetchAM(id: String, owner: SessionStamp): Resource<String> {
        return try {
            requireOwner(owner)
            repository.getAMLLyric(id).also { requireOwner(owner) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SessionChangedException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).w("AML preload fetch failed: %s", e.javaClass.simpleName)
            Resource.Error("AML fetch failed")
        }
    }

    private suspend fun fetchQQ(metadata: MediaMetadata, songId: String, owner: SessionStamp): Resource<LyricResult> {
        requireOwner(owner)
        val localSong = qqSongRepository.getQQSong(songId).firstOrNull()
        requireOwner(owner)
        if (localSong != null) {
            return try {
                repository.getLyricNew(
                    localSong.title, localSong.album, localSong.artist,
                    localSong.duration, localSong.qid.toLong()
                ).also { requireOwner(owner) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SessionChangedException) {
                throw e
            } catch (e: Exception) {
                Timber.tag(TAG).w("QQ preload fetch failed: %s", e.javaClass.simpleName)
                Resource.Error("QQ fetch failed")
            }
        }

        // 静默搜索：查询但不写入任何共享 StateFlow
        val best = searchSilent(metadata, owner) ?: return Resource.Error("No QQ match found")

        val qqSong = QQSong(
            id = songId,
            qid = best.id.toString(),
            title = best.title,
            artist = best.singer.joinToString(",") { it.name },
            album = best.album.title,
            duration = best.interval
        )
        requireOwner(owner)
        qqSongRepository.insertSong(qqSong)
        requireOwner(owner)

        return try {
            repository.getLyricNew(
                qqSong.title, qqSong.album, qqSong.artist,
                qqSong.duration, qqSong.qid.toLong()
            ).also { requireOwner(owner) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SessionChangedException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).w("QQ preload fetch after search failed: %s", e.javaClass.simpleName)
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
        metadata: MediaMetadata,
        owner: SessionStamp,
    ): SearchResult.Request.Data.Body.ItemSong? {
        val currentDurationSec = metadata.duration / 1000
        val artistName = metadata.artists.firstOrNull()?.name ?: ""
        val title = metadata.title
        val cleanedTitle = cleanTitle(title)

        if (cleanedTitle != title) {
            trySearchSilent(cleanedTitle, currentDurationSec, owner)?.let { return it }
        }

        trySearchSilent(title, currentDurationSec, owner)?.let { return it }

        if (artistName.isNotBlank() && cleanedTitle != title) {
            val combined = "$cleanedTitle $artistName"
            trySearchSilent(combined, currentDurationSec, owner)?.let { return it }
        }

        if (artistName.isNotBlank()) {
            val combined = "$title $artistName"
            trySearchSilent(combined, currentDurationSec, owner)?.let { return it }
        }

        return null
    }

    private suspend fun trySearchSilent(
        keyword: String,
        targetDurationSec: Long,
        owner: SessionStamp,
    ): SearchResult.Request.Data.Body.ItemSong? {
        requireOwner(owner)
        val result = repository.searchNew(keyword)
        requireOwner(owner)
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
