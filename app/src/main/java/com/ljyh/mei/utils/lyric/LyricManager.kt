package com.ljyh.mei.utils.lyric

import android.content.Context
import com.ljyh.mei.constants.QqTimeout
import com.ljyh.mei.constants.QqTimeoutKey
import com.ljyh.mei.data.model.Lyric
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.SongSourceIdentity
import com.ljyh.mei.data.model.qq.u.LyricResult
import com.ljyh.mei.data.model.qq.u.SearchResult
import com.ljyh.mei.data.model.room.CachedLyric
import com.ljyh.mei.data.model.room.QQSong
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.data.repository.PlayerRepository
import com.ljyh.mei.data.session.SessionChangedException
import com.ljyh.mei.data.session.SessionStamp
import com.ljyh.mei.data.session.SessionStore
import com.ljyh.mei.di.repository.CachedLyricRepository
import com.ljyh.mei.di.repository.QQSongRepository
import com.ljyh.mei.ui.model.LyricData
import com.ljyh.mei.ui.model.LyricSource
import com.ljyh.mei.ui.model.LyricSourceData
import com.ljyh.mei.utils.dataStore
import com.ljyh.mei.utils.encrypt.QRCUtils
import com.ljyh.mei.di.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.math.abs
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(kotlinx.coroutines.FlowPreview::class)

/** Coordinates existing lyric precedence and caches within one source/session-owned request batch. */
@Singleton
class LyricManager @Inject constructor(
    private val repository: PlayerRepository,
    private val qqSongRepository: QQSongRepository,
    private val cachedLyricRepository: CachedLyricRepository,
    private val duetDetector: DuetDetector,
    private val preloader: LyricPreloader,
    @ApplicationContext private val context: Context,
    private val sessions: SessionStore,
) {

    private val TAG = "LyricManager"
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // ==================== 状态暴露 ====================

    /** 当前歌词数据，UI 层通过 collectAsState 消费 */
    private val _lyricData =
        MutableStateFlow(createDefaultLyricData("歌词加载中", source = LyricSource.Loading))
    val lyricData: StateFlow<LyricData> = _lyricData.asStateFlow()

    /** QQ 音乐搜索结果，供手动选歌 sheet 使用 */
    private val _qqSearchResult = MutableStateFlow<Resource<SearchResult>>(Resource.Loading)
    val qqSearchResult: StateFlow<Resource<SearchResult>> = _qqSearchResult.asStateFlow()

    // ==================== 当前歌曲状态 ====================

    /** 当前正在加载歌词的歌曲 ID，用于防止重复加载 */
    private class Load(val source: SongSourceIdentity, val owner: SessionStamp)
    private var currentLoad: Load? = null
    private var retryAfterSession = false
    val songId: String? get() = currentLoad?.source?.entryId?.toString()
    val sourceKey: String? get() = currentLoad?.source?.key

    /** 当前歌词拉取协程 Job，切歌时 cancel */
    private var fetchJob: Job? = null

    /** Current QQ lyric request, including manually selected songs. */
    private var qqFetchJob: Job? = null

    /** 标记 QQ 源是否已终结（Success 或 Error），防止同一首歌多个 combine 触发重复处理 */
    private var qqFinalized = false

    // ==================== 歌词缓存 ====================

    /** 内存缓存，FIFO 淘汰，最多 5 首 */
    private val lyricCache = LinkedHashMap<String, LyricData>()
    private var cacheOwner: SessionStamp? = null

    /** 预加载协程 Job */
    private var preloadJob: Job? = null
    private var preloadOwner: SessionStamp? = null
    private var searchJob: Job? = null

    // ==================== 三源 StateFlow ====================

    // One immutable envelope prevents sampled results from being relabeled after a source switch.
    private data class Sources(
        val load: Load? = null,
        val net: Resource<Lyric> = Resource.Loading,
        val qq: Resource<LyricResult> = Resource.Loading,
        val am: Resource<String> = Resource.Loading,
        val lrcFallback: String? = null,
    )
    private val sources = MutableStateFlow(Sources())

    // ==================== 合并入口 ====================

    /**
     * 组合三个源的拉取状态，任一完成即触发合并。
     * sample(50) 防抖，避免短时间内多次触发。
     */
    init {
        sources.sample(50).onEach { snapshot ->
            if (snapshot.load != null && isCurrent(snapshot.load)) mergeAndApply(snapshot)
        }.launchIn(scope)
        scope.launch { sessions.changes.collect { reconcileSession() } }
        scope.launch { sessions.recoveryRequired.collect { reconcileSession() } }
    }

    private fun requireOwner(owner: SessionStamp) {
        sessions.requireCurrent(owner)
        if (sessions.recoveryRequired.value) throw SessionChangedException()
    }

    private fun isCurrent(load: Load): Boolean = currentLoad === load &&
        runCatching { requireOwner(load.owner) }.isSuccess

    private fun publish(load: Load, block: () -> Unit): Boolean {
        if (currentLoad !== load) return false
        return try {
            sessions.withCurrent(load.owner) {
                if (sessions.recoveryRequired.value) throw SessionChangedException()
                block()
                true
            }
        } catch (_: SessionChangedException) { false }
    }

    private suspend fun requireLoad(load: Load) {
        currentCoroutineContext().ensureActive()
        if (!isCurrent(load)) throw CancellationException("Lyric request is no longer current")
    }

    private fun captureLoad(metadata: MediaMetadata): Load {
        val source = metadata.source ?: SongSourceIdentity(metadata.id)
        require(source.entryId == metadata.id)
        val owner = sessions.snapshot()
        source.requireAccount(owner.identity)
        requireOwner(owner)
        return Load(source, owner)
    }

    private fun reconcileSession() {
        if (cacheOwner?.let { runCatching { requireOwner(it) }.isFailure } == true) {
            lyricCache.clear()
            cacheOwner = null
        }
        if (preloadOwner?.let { runCatching { requireOwner(it) }.isFailure } == true) {
            preloadJob?.cancel()
            preloadOwner = null
        }
        if (currentLoad?.let { !isCurrent(it) } == true) {
            cancelAll()
            retryAfterSession = true
            _lyricData.value = createDefaultLyricData("歌词加载中", source = LyricSource.Loading)
            _qqSearchResult.value = Resource.Loading
        }
        if (retryAfterSession && !sessions.recoveryRequired.value) {
            val metadata = lastMetadata ?: return
            if (runCatching { captureLoad(metadata) }.isSuccess) loadLyrics(metadata, forceReload = true)
        }
    }

    private fun beginLoad(load: Load, metadata: MediaMetadata) {
        fetchJob?.cancel()
        qqFetchJob?.cancel()
        searchJob?.cancel()
        currentLoad = load
        retryAfterSession = false
        lastMetadata = metadata
        qqFinalized = false
        sources.value = Sources(load)
        _qqSearchResult.value = Resource.Loading
        _lyricData.value = createDefaultLyricData("歌词加载中", source = LyricSource.Loading)
        if (cacheOwner != load.owner) lyricCache.clear()
        cacheOwner = load.owner
    }

    // ==================== 公开 API ====================

    /**
     * 为指定歌曲加载歌词
     *
     * 流程：
     * 1. 检查缓存（内存 → Room）
     * 2. 并行拉取网易云、AM、QQ 三源
     * 3. Source snapshots progressively update the existing merged lyric.
     *
     * @param metadata 歌曲元数据
     * @param forceReload Start a fresh request batch without reading the cache.
     */
    fun loadLyrics(metadata: MediaMetadata, forceReload: Boolean = false) {
        val load = try { captureLoad(metadata) } catch (_: Exception) {
            cancelAll()
            lastMetadata = metadata
            retryAfterSession = true
            _lyricData.value = createDefaultLyricData("歌词加载中", source = LyricSource.Loading)
            return
        }
        val songId = load.source.key
        if (!forceReload && sourceKey == songId && currentLoad?.owner == load.owner) return
        beginLoad(load, metadata)

        // 缓存查找：内存（同步）
        if (!forceReload) {
            lyricCache.remove(songId)?.let { cached ->
                publish(load) { _lyricData.value = cached }
            }
        }

        // 网络拉取
        fetchJob = scope.launch {
            // Room 缓存查找（异步，不阻塞主线程）
            if (!forceReload && isCurrent(load)) {
                val dbCached = withContext(Dispatchers.IO) {
                    cachedLyricRepository.get(songId).firstOrNull()
                }
                if (dbCached != null && isCurrent(load)) {
                    val data = dbCached.toLyricData()
                    publish(load) {
                        _lyricData.value = data
                        lyricCache[songId] = data
                    }
                }
            }

            delay(100)
            requireLoad(load)
            launch { fetchNetEaseLyric(load) }
            launch { fetchAMLLyric(load) }

            // QQ 音乐拉取（带超时控制）
            val localSong = qqSongRepository.getQQSong(songId).firstOrNull()
            requireLoad(load)
            val qqTimeout = try {
                QqTimeout.valueOf(
                    context.dataStore.data.first()[QqTimeoutKey] ?: QqTimeout.Sec8.name
                ).seconds
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                8
            }
            try {
                withTimeout(qqTimeout * 1000L) {
                    if (localSong != null) {
                        fetchQQLyric(localSong, load)
                    } else {
                        autoSearchAndPickBest(metadata, load)
                    }
                }
            } catch (_: TimeoutCancellationException) {
                publish(load) { sources.value = sources.value.copy(qq = Resource.Error("QQ timed out")) }
            }

            // 预加载已有 QQSong 时，补充填充搜索结果供 Sheet 使用
            if (localSong != null) {
                launch { searchAndMatchBest(metadata, load) }
            }
        }
    }

    /**
     * 自动搜索 QQ 音乐并选择最佳匹配
     *
     * 先按歌名搜索，无 duration 匹配时回退为"歌名+歌手"搜索
     */
    private suspend fun autoSearchAndPickBest(metadata: MediaMetadata, load: Load) {
        val best = searchAndMatchBest(metadata, load)
        if (best != null) {
            val qqSong = QQSong(
                id = load.source.key,
                qid = best.id.toString(),
                title = best.title,
                artist = best.singer.joinToString(",") { it.name },
                album = best.album.title,
                duration = best.interval
            )
            requireLoad(load)
            qqSongRepository.insertSong(qqSong)
            requireLoad(load)
            fetchQQLyric(qqSong, load)
        }
    }

    /**
     * 两级搜索匹配：先按歌名，无匹配则按"歌名+歌手"
     *
     * @return 匹配到的 QQ 歌曲，未匹配到返回 null
     */
    private suspend fun searchAndMatchBest(metadata: MediaMetadata, load: Load): SearchResult.Request.Data.Body.ItemSong? {
        val currentDurationSec = metadata.duration / 1000
        val artistName = metadata.artists.firstOrNull()?.name ?: ""
        val title = metadata.title
        val cleanedTitle = cleanTitle(title)

        // 1. 清洗后的歌名
        if (cleanedTitle != title) {
            val best = trySearchMatch(cleanedTitle, currentDurationSec, load)
            if (best != null) return best
        }

        // 2. 原始歌名
        val bestByTitle = trySearchMatch(title, currentDurationSec, load)
        if (bestByTitle != null) return bestByTitle

        // 3. 清洗后歌名+歌手
        if (artistName.isNotBlank() && cleanedTitle != title) {
            val combined = "$cleanedTitle $artistName"
            val best = trySearchMatch(combined, currentDurationSec, load)
            if (best != null) return best
        }

        // 4. 原始歌名+歌手
        if (artistName.isNotBlank()) {
            val combined = "$title $artistName"
            val best = trySearchMatch(combined, currentDurationSec, load)
            if (best != null) return best
        }

        return null
    }

    /**
     * 去除歌名中的括号内容（如 feat./with/remix 等附加信息）
     *
     * 处理中文括号（）和英文括号 ()。
     * 例如 "abc (feat. xxx)" → "abc"
     */
    private fun cleanTitle(title: String): String {
        return title
            .replace(Regex("""[\(（][^)）]*[\)）]"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    /**
     * 搜索 QQ 音乐并在前 5 条结果中匹配时长（±5 秒）
     *
     * @param keyword 搜索关键词
     * @param targetDurationSec 目标时长（秒）
     * @return 匹配到的歌曲，未匹配到返回 null
     */
    private suspend fun trySearchMatch(
        keyword: String,
        targetDurationSec: Long,
        load: Load,
    ): SearchResult.Request.Data.Body.ItemSong? {
        requireLoad(load)
        val result = repository.searchNew(keyword)
        requireLoad(load)
        publish(load) { _qqSearchResult.value = result }
        if (result !is Resource.Success) return null
        val songs = result.data.request.data.body.itemSong
        return songs.take(5).firstOrNull { song ->
            abs(targetDurationSec - song.interval) <= 5
        }
    }

    /**
     * 拉取网易云歌词
     *
     * Publishes NetEase fields only into the captured source batch.
     */
    private suspend fun fetchNetEaseLyric(load: Load) {
        try {
            requireLoad(load)
            val result = repository.getLyricV1(load.source.key, load.owner)
            requireLoad(load)
            publish(load) { sources.value = sources.value.copy(net = result) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            publish(load) { sources.value = sources.value.copy(net = Resource.Error("NetEase fetch failed")) }
        }
    }

    /**
     * 拉取 Apple Music TTML 逐字歌词
     *
     * Fetches public TTML by audio ID, retaining the batch ownership at publication.
     */
    private suspend fun fetchAMLLyric(load: Load) {
        try {
            requireLoad(load)
            val result = repository.getAMLLyric(load.source.songId.toString())
            requireLoad(load)
            publish(load) { sources.value = sources.value.copy(am = result) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            publish(load) { sources.value = sources.value.copy(am = Resource.Error("AML fetch failed")) }
        }
    }

    /**
     * 拉取 QQ 音乐歌词
     *
     * Fetches QRC and its LRC fallback within the same source batch.
     */
    private suspend fun fetchQQLyric(song: QQSong, load: Load) {
        requireLoad(load)
        require(song.id == load.source.key)
        publish(load) { sources.value = sources.value.copy(qq = Resource.Loading) }
        try {
            val result = repository.getLyricNew(
                song.title, song.album, song.artist, song.duration, song.qid.toLong()
            )
            requireLoad(load)
            publish(load) { sources.value = sources.value.copy(qq = result) }
            if (result is Resource.Success) {
                val qrcT = result.data.musicMusichallSongPlayLyricInfoGetPlayLyricInfo.data.qrcT
                if (qrcT != 0) fetchQQLyricLrc(song, load)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            publish(load) { sources.value = sources.value.copy(qq = Resource.Error("QQ fetch failed")) }
        }
    }

    /**
     * QQ 歌词 LRC 回退
     *
     * 当主歌词是 QRC 逐字格式时，额外拉取纯 LRC 作为兜底（qrc=0, qrcT=0）。
     * The decoded fallback stays in the captured source snapshot.
     */
    private suspend fun fetchQQLyricLrc(song: QQSong, load: Load) {
        try {
            requireLoad(load)
            val lrcResult = repository.getLyricLrc(
                song.title, song.album, song.artist, song.duration, song.qid.toLong()
            )
            requireLoad(load)
            if (lrcResult is Resource.Success) {
                val lrcContent = QRCUtils.decodeLyric(
                    lrcResult.data.musicMusichallSongPlayLyricInfoGetPlayLyricInfo.data.lyric
                )
                publish(load) { sources.value = sources.value.copy(lrcFallback = lrcContent) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).w("QQ LRC fallback failed: %s", e.javaClass.simpleName)
        }
    }

    // ==================== 合并逻辑 ====================

    /**
     * 合并三个源的数据并更新 UI
     *
     * 流程：
     * 1. 守卫：全 Loading 且已有歌词 → 跳过
     * 2. 组装 LyricSourceData 列表
     * 3. 调用 [mergeLyrics] 按优先级选出最佳
     * 4. 缓存结果（内存 + Room）
     * 5. 根据 QQ 源是否终结决定触发 AI（双源 smartMerge / 单源 singleEnhance）
     */
    private suspend fun mergeAndApply(snapshot: Sources) {
        val load = snapshot.load ?: return
        if (!isCurrent(load)) return
        val songIdAtStart = load.source.key
        val (net, qq, am) = Triple(snapshot.net, snapshot.qq, snapshot.am)

        // 守卫：三源全 Loading 且已有有效歌词 → 不覆盖
        val hasValidLyrics = _lyricData.value.let {
            it.source != LyricSource.Loading && it.source != LyricSource.Empty
                    && it.lyricLine.lines.isNotEmpty()
        }
        if (hasValidLyrics && net is Resource.Loading && qq is Resource.Loading && am is Resource.Loading) return

        data class MergeResult(
            val lyricData: LyricData,
            val cacheContent: String?,
            val cacheTranslation: String?,
            val cacheParserType: String,
            val sources: List<LyricSourceData>
        )

        val mergeResult = withContext(Dispatchers.IO) {
            val isPureMusic = (net as? Resource.Success)?.data?.pureMusic == true
            val sources = mutableListOf<LyricSourceData>()

            (am as? Resource.Success)?.let { sources.add(LyricSourceData.AM(it.data)) }
            (net as? Resource.Success)?.data?.let { sources.add(LyricSourceData.NetEase(it)) }
            (qq as? Resource.Success)?.data?.musicMusichallSongPlayLyricInfoGetPlayLyricInfo?.data?.let { data ->
                try {
                    val isQRC = data.qrcT != 0
                    val decoded = data.copy(
                        lyric = QRCUtils.decodeLyric(data.lyric),
                        trans = QRCUtils.decodeLyric(data.trans, true),
                        roma = QRCUtils.decodeLyric(data.roma)
                    )
                    sources.add(LyricSourceData.QQMusic(decoded, isQRC, snapshot.lrcFallback))
                } catch (e: Exception) {
                    Timber.e(e, "QRC decoding failed")
                }
            }

            val lyricData = mergeLyrics(sources, isPureMusic)

            val (cacheContent, cacheTranslation, cacheParserType) = buildCacheInfo(
                sources,
                lyricData
            )

            MergeResult(lyricData, cacheContent, cacheTranslation, cacheParserType, sources)
        }

        // 歌曲已切换，放弃旧结果
        if (!isCurrent(load)) return

        // 守卫：不拿空结果覆盖已有有效歌词（竞态保护）
        if (_lyricData.value.lyricLine.lines.isNotEmpty()
            && mergeResult.lyricData.lyricLine.lines.isEmpty()
        ) return

        // 守卫：同一源不重复更新 UI
        val currentSource = _lyricData.value.source
        val skipUiUpdate =
            currentSource != LyricSource.Loading && currentSource != LyricSource.Empty
                    && mergeResult.lyricData.source == currentSource
        val cacheContent = mergeResult.cacheContent

        if (!skipUiUpdate) {
            if (!publish(load) {
                _lyricData.value = mergeResult.lyricData
                lyricCache[songIdAtStart] = mergeResult.lyricData
                trimCache()
            }) return

            if (cacheContent != null) {
                if (!isCurrent(load)) return
                cachedLyricRepository.insert(
                    CachedLyric(
                        songId = songIdAtStart,
                        content = cacheContent,
                        translation = mergeResult.cacheTranslation,
                        isVerbatim = mergeResult.lyricData.isVerbatim,
                        isPureMusic = mergeResult.lyricData.isPureMusic,
                        sourceName = mergeResult.lyricData.source.name,
                        parserType = mergeResult.cacheParserType,
                        updatedAt = System.currentTimeMillis()
                    )
                )
                if (!isCurrent(load)) return
            }
        }

        // ===== 本地对唱合并（仅在 QQ 源终结后触发一次） =====
        val amSuccess = am as? Resource.Success
        if (amSuccess != null) return

        val netSuccess = net as? Resource.Success
        val qqSuccess = qq as? Resource.Success
        val qqTerminal = qq is Resource.Success || qq is Resource.Error

        if (qqTerminal && !qqFinalized) {
            qqFinalized = true

            // 仅在有对唱标记的网易云歌词时触发本地对唱解析
            val neteaseData = netSuccess?.data
            val lrcText = neteaseData?.lrc?.lyric?.takeIf { it.isNotBlank() }
            val hasDuet = lrcText != null && duetDetector.isDuetLikely(lrcText)
            Timber.tag(TAG)
                .d("duet check: hasLrc=${lrcText != null}, isDuet=$hasDuet, lrcLen=${lrcText?.length}")
            if (hasDuet) {
                val netease = LyricSourceData.NetEase(neteaseData)
                val qqParsed = qqSuccess?.let { parseQQSource(it, snapshot.lrcFallback) }
                val dueted = if (qqParsed != null) {
                    duetDetector.mergeWithDuet(netease, qqParsed)
                } else {
                    duetDetector.singleDuet(netease)
                }
                if (dueted != null) {
                    publish(load) {
                        _lyricData.value = dueted
                        lyricCache[songIdAtStart] = dueted
                    }
                }
            }

        }
    }

    /**
     * 从 QQ Resource.Success 中构建 LyricSourceData.QQMusic
     *
     * 解码 QRC 加密字段，提取 lyric、trans、roma 和 isQRC 标记。
     */
    private fun parseQQSource(qq: Resource.Success<LyricResult>, lrcFallbackContent: String?): LyricSourceData.QQMusic? {
        val data = qq.data.musicMusichallSongPlayLyricInfoGetPlayLyricInfo?.data ?: return null
        return try {
            val isQRC = data.qrcT != 0
            val decoded = data.copy(
                lyric = QRCUtils.decodeLyric(data.lyric),
                trans = QRCUtils.decodeLyric(data.trans, true),
                roma = QRCUtils.decodeLyric(data.roma)
            )
            LyricSourceData.QQMusic(decoded, isQRC, lrcFallbackContent)
        } catch (e: Exception) {
            Timber.e(e, "parseQQSource failed")
            null
        }
    }

    private var lastMetadata: MediaMetadata? = null

    /**
     * 手动搜索 QQ 音乐（供选歌 sheet 使用）
     *
     * 结果写入 [_qqSearchResult]，UI 层通过 [qqSearchResult] 观察。
     */
    fun searchQQSong(keyword: String) {
        val load = currentLoad?.takeIf(::isCurrent) ?: return
        searchJob?.cancel()
        publish(load) { _qqSearchResult.value = Resource.Loading }
        searchJob = scope.launch {
            val result = repository.searchNew(keyword)
            requireLoad(load)
            publish(load) { _qqSearchResult.value = result }
        }
    }

    /**
     * 用户手动选择 QQ 歌曲作为歌词来源
     *
     * 插入 QQSong 映射到 Room，然后拉取歌词。
     */
    fun selectQQSongForLyric(metadata: MediaMetadata, song: SearchResult.Request.Data.Body.ItemSong) {
        val load = try { captureLoad(metadata) } catch (_: Exception) { return }
        if (load.source.key != sourceKey || currentLoad?.owner != load.owner) return
        // Manual selection starts a new batch, including when the source key did not change.
        beginLoad(load, metadata)
        qqFetchJob = scope.launch {
            val qqSong = QQSong(
                id = load.source.key,
                qid = song.id.toString(),
                title = song.title,
                artist = song.singer.joinToString(",") { it.name },
                album = song.album.title,
                duration = song.interval
            )
            requireLoad(load)
            qqSongRepository.insertSong(qqSong)
            requireLoad(load)

            fetchQQLyric(qqSong, load)
        }
    }

    suspend fun getQQSongId(metadata: MediaMetadata): String? {
        return try {
            val load = captureLoad(metadata)
            val song = qqSongRepository.getQQSong(load.source.key).firstOrNull()
            currentCoroutineContext().ensureActive()
            requireOwner(load.owner)
            song?.qid
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) { null }
    }

    fun resetQQSongForLyric(metadata: MediaMetadata) {
        val load = try { captureLoad(metadata) } catch (_: Exception) { return }
        if (load.source.key != sourceKey || currentLoad?.owner != load.owner) return
        beginLoad(load, metadata)
        qqFetchJob = scope.launch {
            requireLoad(load)
            qqSongRepository.deleteSongById(load.source.key)
            requireLoad(load)
            qqFetchJob = null
            loadLyrics(metadata, forceReload = true)
        }
    }

    /**
     * 取消当前所有拉取和预加载任务
     */
    fun cancelAll() {
        fetchJob?.cancel()
        qqFetchJob?.cancel()
        preloadJob?.cancel()
        searchJob?.cancel()
        currentLoad = null
        retryAfterSession = false
        preloadOwner = null
        sources.value = Sources()
        _qqSearchResult.value = Resource.Loading
    }

    // ==================== 歌词预加载 ====================

    /**
     * 预加载指定歌曲的歌词到内存缓存
     *
     * 由 [PlayerStateContainer] 在切歌时调用，提前拉取下一首。
     * 委托给 [LyricPreloader] 执行，不干扰当前歌词展示。
     */
    fun preloadLyrics(metadata: MediaMetadata) {
        val load = try { captureLoad(metadata) } catch (_: Exception) { return }
        val songId = load.source.key
        if ((cacheOwner == load.owner && lyricCache.containsKey(songId)) || sourceKey == songId) return

        preloadJob?.cancel()
        preloadOwner = load.owner
        preloadJob = scope.launch {
            try {
                val result = preloader.preload(metadata, load.owner)
                currentCoroutineContext().ensureActive()
                if (result != null) sessions.withCurrent(load.owner) {
                    if (sessions.recoveryRequired.value) throw SessionChangedException()
                    if (cacheOwner != load.owner) lyricCache.clear()
                    cacheOwner = load.owner
                    lyricCache[songId] = result
                    trimCache()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag(TAG).w("Lyric preload failed: %s", e.javaClass.simpleName)
            }
        }
    }

    internal fun release() {
        cancelAll()
        scope.cancel()
        lyricCache.clear()
    }

    // ==================== 缓存管理 ====================

    /** 内存缓存 FIFO 逐出，保留最近 5 首 */
    private fun trimCache() {
        while (lyricCache.size > 5) {
            lyricCache.remove(lyricCache.entries.first().key)
        }
    }

    /**
     * 从合并源和结果中提取 Room 持久化所需信息
     *
     * @return Triple(原始歌词文本, 翻译文本, 解析器类型)
     *   对逐字歌词（TTML/YRC/QRC），第一项为 null 表示不缓存
     */
    private fun buildCacheInfo(
        sources: List<LyricSourceData>,
        lyricData: LyricData
    ): Triple<String?, String?, String> {
        return when (lyricData.source) {
            LyricSource.AM -> Triple(null, null, "TTML")
            LyricSource.NetEaseCloudMusic -> {
                val netease = sources.filterIsInstance<LyricSourceData.NetEase>().firstOrNull()
                if (lyricData.isVerbatim) {
                    Triple(null, null, "YRC")
                } else {
                    val lrc = netease?.lyric?.lrc?.lyric?.takeIf { it.isNotBlank() }
                    val translation = netease?.lyric?.tlyric?.lyric?.takeIf { it.isNotBlank() }
                    Triple(lrc, translation, "LRC")
                }
            }

            LyricSource.QQMusic -> {
                val qq = sources.filterIsInstance<LyricSourceData.QQMusic>().firstOrNull()
                val lrc = qq?.lrcContent?.takeIf { it.isNotBlank() }
                    ?: qq?.lyric?.lyric?.takeIf { it.isNotBlank() }
                val translation = qq?.lyric?.trans?.takeIf { it.isNotBlank() }
                if (lyricData.isVerbatim) {
                    Triple(lrc, translation, "QRC")
                } else {
                    Triple(lrc, translation, "LRC")
                }
            }

            else -> Triple(null, null, "LRC")
        }
    }

    /**

     * 从 Room 缓存恢复 [LyricData]
     *
     * 根据 parserType 选择对应的解析器：
     * - TTML → TTMLParser
     * - YRC  → YRCParser
     * - QRC  → QRCParser (decoded trans)
     * - LRC  → LRCParser (default)
     */
    fun CachedLyric.toLyricData(): LyricData = LyricData(
        isVerbatim = isVerbatim,
        isPureMusic = isPureMusic,
        source = try {
            LyricSource.valueOf(sourceName)
        } catch (_: Exception) {
            LyricSource.Empty
        },
        lyricLine = when (parserType) {
            "TTML" -> TTMLParser().parse(content)
            "YRC" -> YRCParser.parse(content, translation ?: "")
            "QRC" -> {
                val decoded = translation?.let { QRCUtils.decodeLyric(it) } ?: ""
                QRCParser.parse(content, decoded)
            }

            else -> LRCParser.parse(content, translation)
        }
    )


}
