package com.ljyh.mei.utils.lyric

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.ljyh.mei.constants.QqTimeout
import com.ljyh.mei.constants.QqTimeoutKey
import com.ljyh.mei.data.model.Lyric
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.qq.u.LyricResult
import com.ljyh.mei.data.model.qq.u.SearchResult
import com.ljyh.mei.data.model.room.CachedLyric
import com.ljyh.mei.data.model.room.QQSong
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.data.repository.PlayerRepository
import com.ljyh.mei.di.repository.CachedLyricRepository
import com.ljyh.mei.di.repository.QQSongRepository
import com.ljyh.mei.ui.model.LyricCandidate
import com.ljyh.mei.ui.model.LyricData
import com.ljyh.mei.ui.model.LyricSource
import com.ljyh.mei.ui.model.LyricSourceData
import com.ljyh.mei.ui.model.LyricVariant
import com.ljyh.mei.utils.dataStore
import com.ljyh.mei.utils.encrypt.QRCUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(kotlinx.coroutines.FlowPreview::class)

/**
 * 歌词管理器
 *
 * 协调歌词的获取、合并、缓存、预加载和AI增强全过程。
 *
 * 数据流概要：
 * 1. [loadLyrics] 被调用时重置所有源状态为 Loading
 * 2. 并行拉取三源（网易云、AM、QQ），各自更新对应的 StateFlow
 * 3. [combine] 监听三个 StateFlow，任一变化触发 [mergeAndApply]
 * 4. [mergeLyrics] 按优先级选出最佳歌词
 * 5. 内存缓存 (lyricCache) 和 Room 持久化 (cached_lyric) 加速后续加载
 * 6. 歌词预加载 ([preloadLyrics]) 提前缓存下一首
 * 7. 本地对唱检测 ([DuetDetector]) 合并后对对唱歌词进行对齐
 */
@Singleton
class LyricManager @Inject constructor(
    private val repository: PlayerRepository,
    private val qqSongRepository: QQSongRepository,
    private val cachedLyricRepository: CachedLyricRepository,
    private val duetDetector: DuetDetector,
    private val preloader: LyricPreloader,
    @ApplicationContext private val context: Context
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

    private val _lyricCandidates = MutableStateFlow<List<LyricCandidate>>(emptyList())
    val lyricCandidates: StateFlow<List<LyricCandidate>> = _lyricCandidates.asStateFlow()

    private val _lyricOffsetMs = MutableStateFlow(0)
    val lyricOffsetMs: StateFlow<Int> = _lyricOffsetMs.asStateFlow()

    // Raw snapshots and caches never contain the display offset.
    private var rawLyricData = _lyricData.value
    private val availableLyrics = mutableMapOf<LyricVariant, LyricData>()
    private var preferredSource: LyricSource? = null
    private var preferredVariant: LyricVariant? = null
    private var settingsReady = false
    private val settingsMutex = Mutex()
    private val cacheMutex = Mutex()
    private val settingsCache = mutableMapOf<String, SongLyricSettings>()
    private var sourceSelectionGeneration = 0L
    private var searchGeneration = 0L
    private var qqRequestGeneration = 0L
    private var searchJob: Job? = null

    private data class SongLyricSettings(
        val offsetMs: Int = 0,
        val source: LyricSource? = null,
        val variant: LyricVariant? = null,
    )

    // ==================== 当前歌曲状态 ====================

    /** 当前正在加载歌词的歌曲 ID，用于防止重复加载 */
    private var currentSongId: String? = null

    val songId: String? get() = currentSongId

    /** 当前歌词拉取协程 Job，切歌时 cancel */
    private var fetchJob: Job? = null

    /** Current QQ lyric request, including manually selected songs. */
    private var qqFetchJob: Job? = null

    /** Reject suspended merges from an earlier load, including an A -> B -> A switch. */
    private var loadGeneration = 0L

    // ==================== 歌词缓存 ====================

    /** 内存缓存，FIFO 淘汰，最多 5 首 */
    private val lyricCache = LinkedHashMap<String, LyricData>()
    private var lastPersistedLyric: CachedLyric? = null

    /** 预加载协程 Job */
    private var preloadJob: Job? = null

    // ==================== 三源 StateFlow ====================

    /** 网易云歌词拉取状态 */
    private val netLyricResult = MutableStateFlow<Resource<Lyric>>(Resource.Loading)

    /** QQ 音乐歌词拉取状态 */
    private val qqLyricResult = MutableStateFlow<Resource<LyricResult>>(Resource.Loading)

    /** AM (Apple Music TTML) 歌词拉取状态 */
    private val amLyricResult = MutableStateFlow<Resource<String>>(Resource.Loading)

    // ==================== 合并入口 ====================

    /**
     * 组合三个源的拉取状态，任一完成即触发合并。
     * sample(50) 防抖，避免短时间内多次触发。
     */
    init {
        combine(netLyricResult, qqLyricResult, amLyricResult) { net, qq, am ->
            loadGeneration to Triple(net, qq, am)
        }.sample(50)
            .onEach { (generation, sources) ->
                mergeAndApply(sources.first, sources.second, sources.third, generation)
            }.launchIn(scope)
    }

    // ==================== 公开 API ====================

    /**
     * 为指定歌曲加载歌词
     *
     * 流程：
     * 1. 检查缓存（内存 → Room）
     * 2. 并行拉取网易云、AM、QQ 三源
     * 3. 通过 combine → mergeAndApply 渐进式更新歌词
     *
     * @param metadata 歌曲元数据
     * @param forceReload 是否强制重新拉取，true 时跳过缓存和 currentSongId 拦截
     */
    fun loadLyrics(metadata: MediaMetadata, forceReload: Boolean = false) {
        val songId = metadata.id.toString()
        if (!forceReload && currentSongId == songId) return

        currentSongId = songId
        lastMetadata = metadata
        fetchJob?.cancel()
        qqFetchJob?.cancel()
        searchJob?.cancel()
        val generation = ++loadGeneration
        lastPersistedLyric = null
        sourceSelectionGeneration++
        searchGeneration++
        qqRequestGeneration++
        netLyricResult.value = Resource.Loading
        qqLyricResult.value = Resource.Loading
        amLyricResult.value = Resource.Loading
        _qqSearchResult.value = Resource.Loading
        lrcFallbackContent = null
        qqLineLyricsPending = false
        currentQQSong = null
        availableLyrics.clear()
        _lyricCandidates.value = emptyList()

        val knownSettings = settingsCache[songId]
        settingsReady = knownSettings != null
        preferredSource = knownSettings?.source
        preferredVariant = knownSettings?.variant
        _lyricOffsetMs.value = knownSettings?.offsetMs ?: 0
        val memory = if (!forceReload) lyricCache[songId] else null
        publishRawLyricData(memory?.takeIf {
            settingsReady && it.matchesLyricPreference(preferredSource, preferredVariant)
        } ?: createDefaultLyricData("歌词加载中", source = LyricSource.Loading))

        fetchJob = scope.launch {
            val settings = settingsMutex.withLock { readSettings(songId) }
            if (!isCurrentLoad(songId, generation)) return@launch
            settingsReady = true
            preferredSource = settings.source
            preferredVariant = settings.variant
            _lyricOffsetMs.value = settings.offsetMs
            publishRawLyricData(memory?.takeIf {
                it.matchesLyricPreference(preferredSource, preferredVariant)
            } ?: createDefaultLyricData("歌词加载中", source = LyricSource.Loading))
            rememberNativeCandidate(metadata, memory)

            if (!forceReload) {
                val dbCached = cacheMutex.withLock {
                    val cached = withContext(Dispatchers.IO) {
                        cachedLyricRepository.get(songId).firstOrNull()
                    }
                    if (isCurrentLoad(songId, generation)) lastPersistedLyric = cached
                    cached
                }
                if (!isCurrentLoad(songId, generation)) return@launch
                dbCached?.toLyricData()?.let { data ->
                    rememberNativeCandidate(metadata, data)
                    if (data.matchesLyricPreference(preferredSource, preferredVariant)) {
                        val selected = selectPreferredLyricUpdate(
                            rawLyricData, data, preferredSource,
                            sourcesFinished = false, alignmentFinished = false,
                            preferredVariant = preferredVariant,
                        )
                        if (selected !== rawLyricData) {
                            publishRawLyricData(selected)
                            lyricCache[songId] = selected
                            trimCache()
                        }
                    }
                }
            }

            delay(100)
            if (!isCurrentLoad(songId, generation)) return@launch
            launch { fetchNetEaseLyric(songId, generation) }
            launch { fetchAMLLyric(songId, generation) }

            val selectionGeneration = sourceSelectionGeneration
            val localSong = qqSongRepository.getQQSong(songId).firstOrNull()
            if (!isCurrentLoad(songId, generation)) return@launch
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
                        if (selectionGeneration == sourceSelectionGeneration) fetchQQLyric(localSong)
                    } else {
                        autoSearchAndPickBest(metadata, generation, selectionGeneration)
                    }
                }
            } catch (_: TimeoutCancellationException) {
                if (isCurrentLoad(songId, generation) && selectionGeneration == sourceSelectionGeneration) {
                    qqLyricResult.value = Resource.Error("QQ timed out")
                }
            }
            if (localSong != null && isCurrentLoad(songId, generation)) {
                searchAndMatchBest(metadata, generation)
            }
        }
    }

    private fun isCurrentLoad(songId: String, generation: Long): Boolean =
        currentSongId == songId && loadGeneration == generation

    /**
     * 自动搜索 QQ 音乐并选择最佳匹配
     *
     * 先按歌名搜索，无 duration 匹配时回退为"歌名+歌手"搜索
     */
    private suspend fun autoSearchAndPickBest(
        metadata: MediaMetadata,
        generation: Long,
        selectionGeneration: Long,
    ) {
        val songId = metadata.id.toString()
        val best = searchAndMatchBest(metadata, generation)
        if (!isCurrentLoad(songId, generation) || selectionGeneration != sourceSelectionGeneration) return
        if (best != null) {
            val qqSong = best.toQQSong(songId)
            settingsMutex.withLock {
                if (!isCurrentLoad(songId, generation) || selectionGeneration != sourceSelectionGeneration) return
                qqSongRepository.insertSong(qqSong)
            }
            if (isCurrentLoad(songId, generation) && selectionGeneration == sourceSelectionGeneration) {
                fetchQQLyric(qqSong)
            }
        } else {
            qqLyricResult.value = Resource.Error("No matching QQ song")
        }
    }

    private suspend fun searchAndMatchBest(
        metadata: MediaMetadata,
        generation: Long,
    ): SearchResult.Request.Data.Body.ItemSong? {
        val songId = metadata.id.toString()
        val targetDurationSec = metadata.duration / 1000
        val artistName = metadata.artists.firstOrNull()?.name.orEmpty()
        val title = metadata.title
        val cleanedTitle = cleanTitle(title)
        val searchToken = searchGeneration
        val keywords = buildList {
            add(cleanedTitle)
            add(title)
            if (artistName.isNotBlank()) {
                add("$cleanedTitle $artistName")
                add("$title $artistName")
            }
        }.distinct()
        for (keyword in keywords) {
            val result = repository.searchNew(keyword)
            currentCoroutineContext().ensureActive()
            if (!isCurrentLoad(songId, generation)) return null
            if (searchToken == searchGeneration) publishSearchResult(result)
            val best = (result as? Resource.Success)?.data?.request?.data?.body?.itemSong
                ?.take(5)?.firstOrNull { abs(targetDurationSec - it.interval) <= 5 }
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
     * 拉取网易云歌词
     *
     * 写入 netLyricResult（Lyric 结构体，含 lrc/yrc/tlyric/ytlrc 等字段）。
     */
    private suspend fun fetchNetEaseLyric(id: String, generation: Long) {
        try {
            val result = repository.getLyricV1(id)
            currentCoroutineContext().ensureActive()
            if (isCurrentLoad(id, generation)) netLyricResult.value = result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "NetEase fetch error")
            if (isCurrentLoad(id, generation)) netLyricResult.value = Resource.Error("NetEase fetch failed")
        }
    }

    /**
     * 拉取 Apple Music TTML 逐字歌词
     *
     * 写入 amLyricResult（原始 TTML 字符串）。
     */
    private suspend fun fetchAMLLyric(id: String, generation: Long) {
        try {
            val result = repository.getAMLLyric(id)
            currentCoroutineContext().ensureActive()
            if (isCurrentLoad(id, generation)) amLyricResult.value = result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "AML fetch error")
            if (isCurrentLoad(id, generation)) amLyricResult.value = Resource.Error("AML fetch failed")
        }
    }

    /** 当前 QQ 歌曲引用（用于 LRC 回退） */
    private var currentQQSong: QQSong? = null

    /**
     * 拉取 QQ 音乐歌词
     *
     * 写入 qqLyricResult。若返回 QRC 格式（qrcT ≠ 0），额外拉取 LRC 兜底。
     */
    fun fetchQQLyric(song: QQSong) {
        if (song.id != currentSongId) return
        qqFetchJob?.cancel()
        val generation = loadGeneration
        val requestGeneration = ++qqRequestGeneration
        currentQQSong = song
        availableLyrics.keys.removeAll { it.source == LyricSource.QQMusic }
        lastMetadata?.let(::updateLyricCandidates)
        lrcFallbackContent = null
        qqLineLyricsPending = false
        qqLyricResult.value = Resource.Loading
        qqFetchJob = scope.launch {
            fun isCurrentRequest() = isCurrentLoad(song.id, generation) &&
                requestGeneration == qqRequestGeneration
            try {
                val result = repository.getLyricNew(
                    song.title, song.album, song.artist, song.duration, song.qid.toLong()
                )
                currentCoroutineContext().ensureActive()
                if (!isCurrentRequest()) return@launch
                qqLineLyricsPending = result is Resource.Success &&
                    result.data.musicMusichallSongPlayLyricInfoGetPlayLyricInfo.data.qrcT != 0
                qqLyricResult.value = result
                if (qqLineLyricsPending) {
                    fetchQQLyricLrc(song, generation, requestGeneration)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "QQ fetch error")
                if (isCurrentRequest()) qqLyricResult.value = Resource.Error("QQ fetch failed")
            }
        }
    }

    /**
     * QQ 歌词 LRC 回退
     *
     * 当主歌词是 QRC 逐字格式时，额外拉取纯 LRC 作为兜底（qrc=0, qrcT=0）。
     * 解码后存入 lrcFallbackContent 并触发重合并。
     */
    private suspend fun fetchQQLyricLrc(song: QQSong, generation: Long, requestGeneration: Long) {
        try {
            val lrcResult = repository.getLyricLrc(
                song.title, song.album, song.artist, song.duration, song.qid.toLong()
            )
            currentCoroutineContext().ensureActive()
            if (lrcResult is Resource.Success && isCurrentLoad(song.id, generation) &&
                requestGeneration == qqRequestGeneration
            ) {
                val lrcContent = QRCUtils.decodeLyric(
                    lrcResult.data.musicMusichallSongPlayLyricInfoGetPlayLyricInfo.data.lyric
                )
                lrcFallbackContent = lrcContent
                remergeLyrics()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "QQ LRC fallback fetch error")
        } finally {
            if (isCurrentLoad(song.id, generation) && requestGeneration == qqRequestGeneration) {
                qqLineLyricsPending = false
                remergeLyrics()
            }
        }
    }

    /** QQ LRC 回退内容，供 mergeLyrics 中 QQ 源使用 */
    private var lrcFallbackContent: String? = null
    private var qqLineLyricsPending = false

    /**
     * 触发重合并
     *
     * 当 LRC 回退数据到达时，用当前三个源的现有状态重新合并歌词。
     */
    private fun remergeLyrics() {
        val generation = loadGeneration
        scope.launch {
            mergeAndApply(
                netLyricResult.value,
                qqLyricResult.value,
                amLyricResult.value,
                generation,
            )
        }
    }

    // ==================== 合并逻辑 ====================

    /** Resolve source selection and duet alignment before publishing a single snapshot. */
    private suspend fun mergeAndApply(
        net: Resource<Lyric>,
        qq: Resource<LyricResult>,
        am: Resource<String>,
        generation: Long,
    ) {
        val songIdAtStart = currentSongId ?: return
        val metadata = lastMetadata ?: return
        val selectionGeneration = sourceSelectionGeneration
        val selectedSource = preferredSource
        val selectedVariant = preferredVariant
        val qqGeneration = qqRequestGeneration
        val fallbackContent = lrcFallbackContent
        val lineLyricsPending = qqLineLyricsPending
        fun isCurrentSnapshot() = settingsReady && generation == loadGeneration &&
                currentSongId == songIdAtStart && selectionGeneration == sourceSelectionGeneration &&
                selectedSource == preferredSource && selectedVariant == preferredVariant && net === netLyricResult.value &&
                qq === qqLyricResult.value && am === amLyricResult.value && qqGeneration == qqRequestGeneration &&
                fallbackContent == lrcFallbackContent && lineLyricsPending == qqLineLyricsPending
        if (!isCurrentSnapshot()) return
        val netTerminal = net !is Resource.Loading
        val qqTerminal = qq !is Resource.Loading
        val sourcesFinished = when (selectedSource) {
            LyricSource.NetEaseCloudMusic -> netTerminal
            LyricSource.QQMusic -> qqTerminal && (selectedVariant != LyricVariant.QqLrc || !lineLyricsPending)
            LyricSource.AM -> am !is Resource.Loading
            else -> netTerminal && qqTerminal && am !is Resource.Loading
        }
        val alignmentFinished = selectedSource != null || am is Resource.Success || (netTerminal && qqTerminal)

        data class MergeResult(
            val lyricData: LyricData,
            val cacheContent: String?,
            val cacheTranslation: String?,
            val cacheParserType: String,
            val variants: List<LyricData>,
        )

        val mergeResult = withContext(Dispatchers.IO) {
            val isPureMusic = (net as? Resource.Success)?.data?.pureMusic == true
            val sources = mutableListOf<LyricSourceData>()
            (am as? Resource.Success)?.let { sources.add(LyricSourceData.AM(it.data)) }
            (net as? Resource.Success)?.data?.let { sources.add(LyricSourceData.NetEase(it)) }
            (qq as? Resource.Success)?.data?.musicMusichallSongPlayLyricInfoGetPlayLyricInfo?.data?.let { data ->
                try {
                    val decoded = data.copy(
                        lyric = QRCUtils.decodeLyric(data.lyric),
                        trans = QRCUtils.decodeLyric(data.trans, true),
                        roma = QRCUtils.decodeLyric(data.roma),
                    )
                    sources.add(LyricSourceData.QQMusic(decoded, data.qrcT != 0, fallbackContent))
                } catch (e: Exception) {
                    Timber.e(e, "QRC decoding failed")
                }
            }

            val roleTimeline = sources.filterIsInstance<LyricSourceData.NetEase>().firstOrNull()?.lyric?.lrc?.lyric
            val variants = buildLyricVariants(sources, isPureMusic).map { data ->
                if (netTerminal && qqTerminal && !roleTimeline.isNullOrBlank()) {
                    duetDetector.alignExisting(data, roleTimeline)
                } else data
            }
            val selectedSources = selectLyricSources(sources, selectedSource)
            val requested = selectedVariant?.let { variant -> variants.firstOrNull { lyricVariantOf(it) == variant } }
            var lyricData = if (selectedVariant == null) {
                mergeLyrics(selectedSources, isPureMusic)
            } else {
                // An explicit format must not briefly jump to another format while its request is pending.
                requested ?: createDefaultLyricData("没有歌词", isPureMusic = isPureMusic)
            }
            // Role detection needs NetEase's timeline even when QQ supplies the words.
            // Finish it before any UI update or suspending cache write.
            if (selectedVariant == null && netTerminal && qqTerminal && (am !is Resource.Success || selectedSource != null)) {
                val netease = selectedSources.filterIsInstance<LyricSourceData.NetEase>().firstOrNull()
                val lrcText = netease?.lyric?.lrc?.lyric
                if (netease != null && !lrcText.isNullOrBlank() && duetDetector.isDuetLikely(lrcText)) {
                    val qqSource = selectedSources.filterIsInstance<LyricSourceData.QQMusic>().firstOrNull()
                    val dueted = if (qqSource != null) {
                        duetDetector.mergeWithDuet(netease, qqSource)
                    } else {
                        duetDetector.singleDuet(netease)
                    }
                    if (dueted != null && dueted.lyricLine.lines.isNotEmpty()) {
                        val phonetics = when (dueted.source) {
                            LyricSource.NetEaseCloudMusic -> netease.lyric.romalrc?.lyric
                            LyricSource.QQMusic -> qqSource?.lyric?.roma
                            else -> null
                        }
                        lyricData = dueted.copy(lyricLine = attachPhonetics(dueted.lyricLine, phonetics))
                    }
                }
            }
            val (content, translation, parserType) = buildCacheInfo(selectedSources, lyricData)
            MergeResult(lyricData, content, translation, parserType, variants)
        }

        if (!isCurrentSnapshot()) return
        mergeResult.variants.forEach { data -> lyricVariantOf(data)?.let { availableLyrics[it] = data } }
        updateLyricCandidates(metadata)
        val current = rawLyricData
        val selected = selectPreferredLyricUpdate(
            current, mergeResult.lyricData, selectedSource, sourcesFinished, alignmentFinished,
            preferredVariant = selectedVariant,
        )
        if (selected !== current) publishRawLyricData(selected)
        if (selected.source == LyricSource.Loading || selected.source == LyricSource.Empty) return
        if (lyricCache[songIdAtStart] != selected) {
            lyricCache[songIdAtStart] = selected
            trimCache()
        }
        // A retained snapshot must never be stored with a rejected source's raw text.
        if (selected != mergeResult.lyricData) return
        cacheMutex.withLock {
            if (!isCurrentSnapshot()) return
            val update = buildLyricCacheUpdate(
                songIdAtStart, selected,
                Triple(mergeResult.cacheContent, mergeResult.cacheTranslation, mergeResult.cacheParserType),
                lastPersistedLyric,
            ) ?: return
            val stored = update.copy(updatedAt = System.currentTimeMillis())
            cachedLyricRepository.insert(stored)
            lastPersistedLyric = stored
        }
    }

    private var lastMetadata: MediaMetadata? = null

    private suspend fun readSettings(songId: String): SongLyricSettings {
        return settingsCache[songId] ?: context.dataStore.data.first().let { preferences ->
            val source = preferences[stringPreferencesKey("lyric_source_$songId")]?.let { name ->
                LyricSource.entries.firstOrNull {
                    it.name == name && it != LyricSource.Empty && it != LyricSource.Loading
                }
            }
            SongLyricSettings(
                offsetMs = preferences[intPreferencesKey("lyric_offset_ms_$songId")] ?: 0,
                source = source,
                variant = lyricVariantPreference(source, preferences[stringPreferencesKey("lyric_variant_$songId")]),
            ).also { settingsCache[songId] = it }
        }
    }

    private fun publishRawLyricData(data: LyricData) {
        rawLyricData = data
        val offsetMs = _lyricOffsetMs.value
        if (data.source == LyricSource.Empty || data.source == LyricSource.Loading) {
            _lyricData.value = data
            return
        }
        try {
            _lyricData.value = data.withTimingOffset(offsetMs)
        } catch (e: IllegalArgumentException) {
            Timber.w(e, "Discarding an unsupported lyric timing offset")
            _lyricOffsetMs.value = 0
            _lyricData.value = data
            val songId = currentSongId ?: return
            scope.launch {
                settingsMutex.withLock {
                    val settings = readSettings(songId)
                    if (settings.offsetMs == offsetMs) {
                        context.dataStore.edit { it.remove(intPreferencesKey("lyric_offset_ms_$songId")) }
                        settingsCache[songId] = settings.copy(offsetMs = 0)
                    }
                }
            }
        }
    }

    private fun rememberNativeCandidate(metadata: MediaMetadata, data: LyricData?) {
        if (data != null && data.source in setOf(LyricSource.NetEaseCloudMusic, LyricSource.AM) &&
            data.lyricLine.lines.isNotEmpty()
        ) {
            lyricVariantOf(data)?.let { availableLyrics[it] = data }
            updateLyricCandidates(metadata)
        }
    }

    private fun updateLyricCandidates(metadata: MediaMetadata) {
        val native = loadedLyricCandidates(metadata, availableLyrics.values.toList(), currentQQSong)
            .map { it.copy(isSelected = it.variant == preferredVariant) }
        val qq = (_qqSearchResult.value as? Resource.Success)?.data?.request?.data?.body?.itemSong
            .orEmpty().map { it.toLyricCandidate() }
        _lyricCandidates.value = (native + qq).distinctBy { it.source to it.key }
    }

    private fun publishSearchResult(result: Resource<SearchResult>) {
        _qqSearchResult.value = result
        lastMetadata?.let { updateLyricCandidates(it) }
    }

    fun searchQQSong(keyword: String) {
        val songId = currentSongId ?: return
        val generation = loadGeneration
        val searchToken = ++searchGeneration
        searchJob?.cancel()
        publishSearchResult(Resource.Loading)
        searchJob = scope.launch {
            val result = repository.searchNew(keyword)
            currentCoroutineContext().ensureActive()
            if (isCurrentLoad(songId, generation) && searchToken == searchGeneration) {
                publishSearchResult(result)
            }
        }
    }

    fun selectQQSongForLyric(metadata: MediaMetadata, song: SearchResult.Request.Data.Body.ItemSong) {
        selectLyricCandidate(metadata, song.toLyricCandidate())
    }

    fun selectLyricCandidate(metadata: MediaMetadata, candidate: LyricCandidate) {
        val songId = metadata.id.toString()
        if (songId != currentSongId || !_lyricCandidates.value.any {
                it.source == candidate.source && it.key == candidate.key
            }
        ) return
        val generation = loadGeneration
        val selectionGeneration = ++sourceSelectionGeneration
        scope.launch {
            settingsMutex.withLock {
                val settings = readSettings(songId)
                candidate.qqSong?.let { qqSongRepository.insertSong(it.toQQSong(songId)) }
                context.dataStore.edit {
                    it[stringPreferencesKey("lyric_source_$songId")] = candidate.source.name
                    val variantKey = stringPreferencesKey("lyric_variant_$songId")
                    if (candidate.variant == null) it.remove(variantKey) else it[variantKey] = candidate.variant.name
                }
                settingsCache[songId] = settings.copy(source = candidate.source, variant = candidate.variant)
            }
            if (!isCurrentLoad(songId, generation) || selectionGeneration != sourceSelectionGeneration) return@launch
            preferredSource = candidate.source
            preferredVariant = candidate.variant
            val snapshot = candidate.variant?.let(availableLyrics::get)
                ?: availableLyrics.values.firstOrNull { it.source == candidate.source && candidate.qqSong == null }
                ?: createDefaultLyricData("歌词加载中", source = LyricSource.Loading)
            publishRawLyricData(snapshot)
            updateLyricCandidates(metadata)
            if (candidate.source == LyricSource.QQMusic) {
                candidate.qqSong?.let { fetchQQLyric(it.toQQSong(songId)) }
            }
            remergeLyrics()
        }
    }

    fun adjustLyricOffset(metadata: MediaMetadata, deltaMs: Int) {
        updateLyricOffset(metadata) { old ->
            (old.toLong() + deltaMs).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
        }
    }

    fun resetLyricOffset(metadata: MediaMetadata) {
        updateLyricOffset(metadata) { 0 }
    }

    private fun updateLyricOffset(metadata: MediaMetadata, transform: (Int) -> Int) {
        val songId = metadata.id.toString()
        if (songId != currentSongId) return
        val generation = loadGeneration
        scope.launch {
            settingsMutex.withLock {
                val settings = readSettings(songId)
                val offsetMs = transform(settings.offsetMs)
                if (isCurrentLoad(songId, generation) && rawLyricData.source != LyricSource.Loading &&
                    rawLyricData.source != LyricSource.Empty
                ) {
                    try {
                        rawLyricData.withTimingOffset(offsetMs)
                    } catch (e: IllegalArgumentException) {
                        Timber.w(e, "Ignoring an unsupported lyric timing adjustment")
                        return@withLock
                    }
                }
                context.dataStore.edit {
                    val key = intPreferencesKey("lyric_offset_ms_$songId")
                    if (offsetMs == 0) it.remove(key) else it[key] = offsetMs
                }
                settingsCache[songId] = settings.copy(offsetMs = offsetMs)
                if (isCurrentLoad(songId, generation)) {
                    _lyricOffsetMs.value = offsetMs
                    publishRawLyricData(rawLyricData)
                }
            }
        }
    }

    fun resetLyricSource(metadata: MediaMetadata) {
        val songId = metadata.id.toString()
        if (songId != currentSongId) return
        val generation = loadGeneration
        val selectionGeneration = ++sourceSelectionGeneration
        preloadJob?.cancel()
        scope.launch {
            settingsMutex.withLock {
                val settings = readSettings(songId)
                context.dataStore.edit {
                    it.remove(stringPreferencesKey("lyric_source_$songId"))
                    it.remove(stringPreferencesKey("lyric_variant_$songId"))
                }
                settingsCache[songId] = settings.copy(source = null, variant = null)
                qqSongRepository.deleteSongById(songId)
                cacheMutex.withLock {
                    lyricCache.remove(songId)
                    cachedLyricRepository.delete(songId)
                }
            }
            if (isCurrentLoad(songId, generation) && selectionGeneration == sourceSelectionGeneration) {
                loadLyrics(metadata, forceReload = true)
            }
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
        currentSongId = null
        settingsReady = false
        loadGeneration++
        sourceSelectionGeneration++
        searchGeneration++
        qqRequestGeneration++
    }

    // ==================== 歌词预加载 ====================

    /**
     * 预加载指定歌曲的歌词到内存缓存
     *
     * 由 [PlayerStateContainer] 在切歌时调用，提前拉取下一首。
     * 委托给 [LyricPreloader] 执行，不干扰当前歌词展示。
     */
    fun preloadLyrics(metadata: MediaMetadata) {
        val songId = metadata.id.toString()
        if (lyricCache.containsKey(songId) || currentSongId == songId) return

        preloadJob?.cancel()
        preloadJob = scope.launch {
            val result = preloader.preload(metadata)
            currentCoroutineContext().ensureActive()
            if (result != null && result.source != LyricSource.Empty
                && result.source != LyricSource.Loading && result.lyricLine.lines.isNotEmpty()
            ) {
                // A late preload must not replace an already aligned foreground cache.
                lyricCache[songId] = lyricCache[songId]?.let { existing ->
                    selectLyricUpdate(existing, result, sourcesFinished = false, alignmentFinished = false)
                } ?: result
                trimCache()
            }
        }
    }

    // ==================== 缓存管理 ====================

    /** 内存缓存 FIFO 逐出，保留最近 5 首 */
    private fun trimCache() {
        while (lyricCache.size > 5) {
            lyricCache.remove(lyricCache.entries.first().key)
        }
    }

}

/** Compare stored content independently of whether the selected lyric changed the UI. */
internal fun buildLyricCacheUpdate(
    songId: String,
    selected: LyricData,
    cacheInfo: Triple<String?, String?, String>,
    previous: CachedLyric?,
): CachedLyric? {
    val content = cacheInfo.first ?: return null
    val entry = CachedLyric(
        songId = songId,
        content = content,
        translation = cacheInfo.second,
        isVerbatim = selected.isVerbatim,
        isPureMusic = selected.isPureMusic,
        sourceName = selected.source.name,
        parserType = cacheInfo.third,
        updatedAt = previous?.updatedAt ?: 0,
    )
    return entry.takeUnless { it == previous }
}

internal fun loadedLyricCandidates(
    metadata: MediaMetadata,
    lyrics: List<LyricData>,
    qqSong: QQSong? = null,
): List<LyricCandidate> = lyrics.filter {
    lyricVariantOf(it) != null && it.lyricLine.lines.isNotEmpty()
}.distinctBy(::lyricVariantOf).sortedBy { lyricVariantOf(it)?.ordinal }.map { data ->
    val variant = requireNotNull(lyricVariantOf(data))
    val match = qqSong?.takeIf { data.source == LyricSource.QQMusic }
    LyricCandidate(
        key = "${variant.name}:${metadata.id}${match?.qid?.let { ":$it" }.orEmpty()}",
        source = data.source,
        title = match?.title ?: metadata.title,
        artist = match?.artist ?: metadata.artists.joinToString(", ") { it.name },
        album = match?.album ?: metadata.album.title,
        coverUrl = metadata.coverUrl,
        durationMs = match?.duration?.times(1_000L) ?: metadata.duration,
        variant = variant,
        preview = lyricPreview(data),
    )
}

private fun SearchResult.Request.Data.Body.ItemSong.toLyricCandidate() = LyricCandidate(
    key = "${LyricSource.QQMusic.name}:$id",
    source = LyricSource.QQMusic,
    title = name,
    artist = singer.joinToString(", ") { it.name },
    album = album.name,
    coverUrl = "https://y.qq.com/music/photo_new/T002R300x300M000${album.pmid}.jpg",
    durationMs = interval * 1000L,
    qqSong = this,
)

private fun SearchResult.Request.Data.Body.ItemSong.toQQSong(songId: String) = QQSong(
    id = songId,
    qid = id.toString(),
    title = title,
    artist = singer.joinToString(",") { it.name },
    album = album.title,
    duration = interval,
)

/** Returns raw text and its matching parser for persistent lyric caching. */
internal fun buildCacheInfo(
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
            val translation = qq?.lyric?.trans?.takeIf { it.isNotBlank() }
            if (lyricData.isVerbatim) {
                // QRC must retain its word timestamps, never the LRC fallback.
                Triple(qq?.lyric?.lyric?.takeIf { it.isNotBlank() }, translation, "QRC")
            } else {
                val lrc = qq?.lrcContent?.takeIf { it.isNotBlank() }
                    ?: qq?.lyric?.lyric?.takeIf { it.isNotBlank() }
                Triple(lrc, translation, "LRC")
            }
        }

        else -> Triple(null, null, "LRC")
    }
}

/** Restores cached lyrics, including legacy LRC entries incorrectly tagged as QRC. */
internal fun CachedLyric.toLyricData(): LyricData? {
    var restoredVerbatim = isVerbatim
    val lyrics = when (parserType) {
        "TTML" -> TTMLParser().parse(content)
        "YRC" -> YRCParser.parse(content, translation)
        "QRC" -> {
            // Cache text and translation have already been decoded before insertion.
            val qrc = QRCParser.parse(content, translation)
            if (qrc.lines.isNotEmpty()) {
                qrc
            } else {
                restoredVerbatim = false
                LRCParser.parse(content, translation)
            }
        }
        else -> LRCParser.parse(content, translation)
    }
    if (lyrics.lines.isEmpty()) return null
    return LyricData(
        isVerbatim = restoredVerbatim,
        isPureMusic = isPureMusic,
        source = try {
            LyricSource.valueOf(sourceName)
        } catch (_: IllegalArgumentException) {
            LyricSource.Empty
        },
        lyricLine = lyrics
    )
}
