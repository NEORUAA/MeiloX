package com.ljyh.mei.playback

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import com.ljyh.mei.data.model.PLACEHOLDER_URI
import com.ljyh.mei.data.model.createPlaceholder
import com.ljyh.mei.data.model.metadata
import com.ljyh.mei.data.model.api.GetRandomPlaylist
import com.ljyh.mei.data.model.api.RandomPlaylistData
import com.ljyh.mei.data.model.toMediaItem
import com.ljyh.mei.data.model.toMediaMetadata
import com.ljyh.mei.data.network.api.ApiService
import com.ljyh.mei.data.network.api.WeApiService
import com.ljyh.mei.playback.queue.Queue
import com.ljyh.mei.playback.queue.PlaylistQueueSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

@OptIn(UnstableApi::class)
class PlaybackQueueManager(
    private val player: Player,
    private val apiService: ApiService,
    private val weApiService: WeApiService,
    private val scope: CoroutineScope,
    private val serverShuffleAllowed: () -> Boolean = { true },
    private val radioItemFactory: (com.ljyh.mei.data.model.weapi.Data) -> MediaItem = {
        it.toMediaMetadata().toMediaItem()
    },
) : Player.Listener {

    private val TAG = "QueueManager"
    private val _queueState = MutableStateFlow<QueueState>(QueueState.Idle)
    private val loadingIds = mutableMapOf<String, Long>()
    private var released = false
    private var activeQueueBuildJob: Job? = null
    private var pendingSimilarGeneration: Long? = null
    private var queueBuildGeneration = 0L
    private var activeFmGeneration: Long? = null
    private var similarFmRequest: SimilarFmRequest? = null
    val similarFmSourceIds: List<String>?
        get() = similarFmRequest?.sourceIds
    var playlistSource: PlaylistQueueSource? = null
        private set
    private var serverShuffleJob: Job? = null
    private var serverShuffleGeneration = 0L
    private var serverShuffleRequested = false
    private var cloudShuffleEnabled: Boolean? = null
    private var queueSelectionJob: Job? = null

    private val _isShuffleModeEnabled = MutableStateFlow(false)
    var isFmMode = false
    private var fetchingFmGeneration: Long? = null
    private val _queueAutoplayEnabled = MutableStateFlow(false)
    val queueAutoplayEnabled = _queueAutoplayEnabled.asStateFlow()
    private val _queueAutoplayStartEntryId = MutableStateFlow<String?>(null)
    val queueAutoplayStartEntryId = _queueAutoplayStartEntryId.asStateFlow()
    private var queueAutoplayQueueEntryIds = emptyList<String>()


    init {
        player.addListener(this)
        (player as? StableDeckPlayer)?.onQueueEdited = { replaced ->
            cancelServerShuffle()
            queueSelectionJob?.cancel()
            if (replaced) {
                playlistSource = null
                _queueAutoplayStartEntryId.value = null
                queueAutoplayQueueEntryIds = emptyList()
            }
        }
        (player as? StableDeckPlayer)?.onSeekRequested = {
            queueSelectionJob?.cancel()
            cancelPendingSimilarStart()
        }
    }

    fun seekToQueueEntry(entryId: String) {
        cancelPendingSimilarStart()
        queueSelectionJob?.cancel()
        queueSelectionJob = scope.launch(Dispatchers.Main) {
            val index = (0 until player.mediaItemCount).firstOrNull {
                player.getMediaItemAt(it).queueEntryId == entryId
            } ?: return@launch
            val item = player.getMediaItemAt(index)
            val hydrated = hydrateQueueItem(item.mediaId to item) ?: return@launch
            currentCoroutineContext().ensureActive()
            val latestIndex = (0 until player.mediaItemCount).firstOrNull {
                player.getMediaItemAt(it).queueEntryId == entryId
            } ?: return@launch
            val stablePlayer = player as? StableDeckPlayer ?: return@launch
            stablePlayer.withInternalQueueUpdate {
                if (needsMetadataHydration(player.getMediaItemAt(latestIndex))) {
                    player.replaceMediaItem(latestIndex, hydrated)
                }
                player.seekToDefaultPosition(latestIndex)
                if (player.playbackState == Player.STATE_IDLE) player.prepare()
                player.playWhenReady = true
            }
        }
    }

    fun cancelServerShuffle() {
        serverShuffleGeneration++
        serverShuffleJob?.cancel()
        serverShuffleJob = null
        serverShuffleRequested = true
    }

    fun restorePlaylistSource(source: PlaylistQueueSource?) {
        cancelServerShuffle()
        playlistSource = source?.takeIf { it.playlistId > 0 }
    }

    fun setCloudShuffleEnabled(enabled: Boolean) {
        val previous = cloudShuffleEnabled
        if (previous == enabled) return
        cloudShuffleEnabled = enabled
        if (previous == null) {
            // Initial preference delivery must not reshuffle a restored snapshot.
            if (enabled) requestServerShuffle()
            return
        }
        cancelServerShuffle()
        if (enabled) {
            serverShuffleRequested = false
            requestServerShuffle()
        } else if (player.shuffleModeEnabled && !isFmMode && serverShuffleAllowed()) {
            val order = player.playbackOrderIndices()
            val currentPosition = order.indexOf(player.currentMediaItemIndex)
            if (currentPosition >= 0) {
                (player as? StableDeckPlayer)?.setPlaybackOrder(
                    order.take(currentPosition + 1) + order.drop(currentPosition + 1).shuffled(),
                )
            }
        }
    }

    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
        if (!shuffleModeEnabled) {
            cancelServerShuffle()
            serverShuffleRequested = false
        } else {
            requestServerShuffle()
        }
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        refreshQueueAutoplayBoundary()
        checkAndLoadMetadata()
    }

    private fun requestServerShuffle() {
        val source = playlistSource ?: return
        val stablePlayer = player as? StableDeckPlayer ?: return
        val seedId = player.currentMediaItem?.mediaId?.toLongOrNull() ?: return
        if (cloudShuffleEnabled != true || serverShuffleRequested || isFmMode || !player.shuffleModeEnabled ||
            !serverShuffleAllowed() || source.playlistId <= 0 || seedId <= 0
        ) return
        serverShuffleRequested = true
        val generation = ++serverShuffleGeneration
        serverShuffleJob = scope.launch(Dispatchers.Main) {
            try {
                val data = fetchCloudShuffleOrNull {
                    apiService.getRandomPlaylist(GetRandomPlaylist(source.playlistId, seedId, source.alg))
                }
                currentCoroutineContext().ensureActive()
                if (generation != serverShuffleGeneration || playlistSource != source ||
                    cloudShuffleEnabled != true || !player.shuffleModeEnabled || isFmMode || !serverShuffleAllowed()
                ) return@launch
                if (data == null) {
                    Log.w(TAG, "Cloud shuffle failed; continuing the existing local shuffle")
                    return@launch
                }
                stablePlayer.withInternalQueueUpdate { applyServerShuffle(stablePlayer, data) }
                checkAndLoadMetadata()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Server shuffle unavailable; keeping the local playback order", error)
            } finally {
                if (generation == serverShuffleGeneration) serverShuffleJob = null
            }
        }
    }

    private fun applyServerShuffle(stablePlayer: StableDeckPlayer, data: RandomPlaylistData) {
        val songIds = data.songIds.orEmpty().map { it.id.toString() }.distinct()
            .filter { (it.toLongOrNull() ?: 0) > 0 }
        if (songIds.isEmpty() || player.mediaItemCount == 0) return
        val previousOrder = player.playbackOrderIndices()
        val currentPosition = previousOrder.indexOf(player.currentMediaItemIndex)
        if (currentPosition < 0) return
        val protectedIds = previousOrder.take(currentPosition + 1)
            .mapNotNull { player.getMediaItemAt(it).queueEntryId }.toSet()
        val protectedOrder = previousOrder.take(currentPosition + 1)
            .mapNotNull { player.getMediaItemAt(it).queueEntryId }
        val unavailableIds = data.privileges.orEmpty().filter { it.isUnavailable }
            .map { it.id.toString() }.toSet()
        val tracks = data.songData.orEmpty().associateBy { it.id.toString() }
        val resolvedTracks = tracks.mapValues { (_, track) -> runCatching { track.toMediaItem() }.getOrNull() }

        // Keep the current/history prefix, but do not introduce known unavailable tracks.
        for (index in player.mediaItemCount - 1 downTo 0) {
            val item = player.getMediaItemAt(index)
            if (item.mediaId in unavailableIds && item.queueEntryId !in protectedIds &&
                item.metadata?.isLocal != true
            ) player.removeMediaItem(index)
        }
        val existingIds = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }.toSet()
        for (index in 0 until player.mediaItemCount) {
            val item = player.getMediaItemAt(index)
            if (needsMetadataHydration(item)) {
                resolvedTracks[item.mediaId]?.let { player.replaceMediaItem(index, it) }
            }
        }
        val additions = songIds.filter { it !in existingIds && it !in unavailableIds }.map { id ->
            resolvedTracks[id] ?: createPlaceholder(id)
        }
        if (additions.isNotEmpty()) player.addMediaItems(additions)

        val entries = (0 until player.mediaItemCount).map { player.getMediaItemAt(it) }
        val indexByKey = entries.indices.associateBy { entries[it].queueEntryId }
        val prefix = protectedOrder.mapNotNull { indexByKey[it] }
        val pending = entries.indices.filter { entries[it].queueEntryId !in protectedIds }
        val bySong = pending.groupBy { entries[it].mediaId }
        val ordered = songIds.flatMap { bySong[it].orEmpty() }.toMutableList()
        val included = ordered.toSet()
        pending.filterNot { it in included }.shuffled().forEach { index ->
            ordered.add(kotlin.random.Random.nextInt(ordered.size + 1), index)
        }
        stablePlayer.setPlaybackOrder(prefix + ordered)
    }

    fun startFmMode(seedItem: MediaItem) {
        cancelActiveQueueBuild()
        val generation = queueBuildGeneration
        activeQueueBuildJob = scope.launch(Dispatchers.Main) {
            try {
                startFmPlayback(seedItem, generation)
            } catch (e: CancellationException) {
                throw e
            } finally {
                if (generation == queueBuildGeneration) {
                    activeQueueBuildJob = null
                }
            }
        }
    }

    fun startFmModeById(seedId: String?) {
        cancelActiveQueueBuild()
        val generation = queueBuildGeneration
        activeQueueBuildJob = scope.launch(Dispatchers.Main) {
            try {
                if (generation != queueBuildGeneration) return@launch
                if (seedId == null) {
                    startFmPlayback(null, generation)
                } else {
                    // 先加载这首歌的详情，再进入 FM；新播放请求会取消此 job。
                    val details = loadSongDetails(listOf(seedId))
                    currentCoroutineContext().ensureActive()
                    if (generation != queueBuildGeneration) return@launch
                    startFmPlayback(details.firstOrNull(), generation)
                }
            } catch (e: CancellationException) {
                throw e
            } finally {
                if (generation == queueBuildGeneration) {
                    activeQueueBuildJob = null
                }
            }
        }
    }

    fun startSimilarFmMode(
        songIds: List<String>,
        shouldPlay: () -> Boolean = { true },
    ): Deferred<Boolean> {
        val request = SimilarFmRequest.fromSongIds(songIds) ?: return CompletableDeferred(false)
        cancelActiveQueueBuild(clearPlaylistSource = false)
        val generation = queueBuildGeneration
        pendingSimilarGeneration = generation
        val result = scope.async(Dispatchers.Main, start = CoroutineStart.LAZY) {
            try {
                _queueState.value = QueueState.Loading(SIMILAR_FM_TITLE)
                val response = weApiService.getRadio(request.body())
                currentCoroutineContext().ensureActive()
                if (generation != queueBuildGeneration || !shouldPlay()) return@async false
                val items = if (response.code == 200) response.data.orEmpty()
                    .filter { it.id > 0 }.distinctBy { it.id }
                    .mapNotNull { runCatching { radioItemFactory(it) }.getOrNull() }
                else emptyList()
                if (items.isEmpty()) {
                    _queueState.value = QueueState.Error("相似歌曲 FM 加载失败")
                    return@async false
                }

                // Keep the current queue until a valid recommendation response is ready.
                pendingSimilarGeneration = null
                similarFmRequest = request
                isFmMode = true
                _queueAutoplayEnabled.value = false
                _queueAutoplayStartEntryId.value = null
                activeFmGeneration = generation
                playlistSource = null
                player.stop()
                player.clearMediaItems()
                player.shuffleModeEnabled = false
                _isShuffleModeEnabled.value = false
                player.repeatMode = Player.REPEAT_MODE_ALL
                player.setMediaItems(items, 0, 0L)
                player.prepare()
                player.play()
                _queueState.value = QueueState.Playing(SIMILAR_FM_TITLE, items.size)
                true
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (generation == queueBuildGeneration) {
                    _queueState.value = QueueState.Error("相似歌曲 FM 加载失败")
                }
                Timber.tag(TAG).w(error, "Unable to start similar FM")
                false
            } finally {
                if (pendingSimilarGeneration == generation) pendingSimilarGeneration = null
                if (generation == queueBuildGeneration) activeQueueBuildJob = null
            }
        }
        activeQueueBuildJob = result
        result.start()
        return result
    }

    fun setQueueAutoplayEnabled(enabled: Boolean): Boolean {
        if (enabled == _queueAutoplayEnabled.value) return true
        if (enabled) {
            // Queue autoplay must not replace an intentional personal/similar FM session.
            if (isFmMode) return false
            val current = player.currentMediaItem ?: return false
            if (current.metadata?.isLocal == true || current.metadata?.isPodcast == true) return false
            val request = SimilarFmRequest.fromSongIds(listOf(current.mediaId)) ?: return false
            cancelActiveQueueBuild(clearPlaylistSource = false)
            restoreFmMode(true, request.sourceIds, queueAutoplay = true)
            scope.launch { fetchAndAppendFmRecommendations(current.mediaId) }
        } else {
            // Invalidate even a transport that finishes after a quick off/on toggle.
            cancelActiveQueueBuild(clearPlaylistSource = false)
            restoreFmMode(false)
        }
        return true
    }

    fun restoreFmMode(
        enabled: Boolean,
        similarSourceIds: List<String>? = null,
        queueAutoplay: Boolean = false,
    ) {
        similarFmRequest = similarSourceIds?.let(SimilarFmRequest::fromSongIds)
        // An invalid saved similar context must not silently request personal FM.
        isFmMode = enabled && (similarSourceIds == null || similarFmRequest != null) &&
            (!queueAutoplay || similarFmRequest != null)
        if (!isFmMode) similarFmRequest = null
        activeFmGeneration = queueBuildGeneration.takeIf { isFmMode }
        _queueAutoplayEnabled.value = isFmMode && queueAutoplay
        _queueAutoplayStartEntryId.value = null
        queueAutoplayQueueEntryIds = emptyList()
    }

    fun restoreQueueAutoplayStartIndex(index: Int?) {
        _queueAutoplayStartEntryId.value = index?.takeIf {
            _queueAutoplayEnabled.value && it in 0 until player.mediaItemCount
        }?.let { player.getMediaItemAt(it).queueEntryId }
        queueAutoplayQueueEntryIds = (0 until player.mediaItemCount)
            .mapNotNull { player.getMediaItemAt(it).queueEntryId }
    }

    private fun refreshQueueAutoplayBoundary() {
        if (!_queueAutoplayEnabled.value) return
        val currentIds = (0 until player.mediaItemCount).mapNotNull { player.getMediaItemAt(it).queueEntryId }
        _queueAutoplayStartEntryId.value = repairQueueAutoplayStartEntryId(
            _queueAutoplayStartEntryId.value, queueAutoplayQueueEntryIds, currentIds,
        )
        queueAutoplayQueueEntryIds = currentIds
    }

    private suspend fun startFmPlayback(seedItem: MediaItem?, generation: Long) {
        if (generation != queueBuildGeneration) return

        isFmMode = true
        similarFmRequest = null
        _queueAutoplayEnabled.value = false
        _queueAutoplayStartEntryId.value = null
        activeFmGeneration = generation
        _queueState.value = QueueState.Loading("私人 FM")

        if (seedItem != null) {
            // 1. 准备播放器环境
            player.stop()
            player.clearMediaItems()
            player.shuffleModeEnabled = false
            _isShuffleModeEnabled.value = false
            player.repeatMode = Player.REPEAT_MODE_ALL

            // 2. 立即添加种子歌曲并播放 (保证 UI 立即显示正确的封面和标题)
            player.addMediaItem(seedItem)
            player.prepare()
            player.play()

            // 3. 异步获取 FM 推荐列表并追加
            fetchAndAppendFmRecommendations(seedItem.mediaId)
        } else {
            // 没有种子时保留现有 FM 队列，只补充推荐歌曲。
            fetchAndAppendFmRecommendations()
        }
    }

    suspend fun fetchAndAppendFmRecommendations(currentId: String? = null) {
        if (!isFmMode) return
        if (activeQueueBuildJob != null && activeFmGeneration != queueBuildGeneration) return
        val fmGeneration = queueBuildGeneration
        if (fetchingFmGeneration == fmGeneration) return
        activeFmGeneration = fmGeneration
        fetchingFmGeneration = fmGeneration
        val request = similarFmRequest
        try {
            val response = weApiService.getRadio(request?.body().orEmpty())
            if (response.code == 200 && !response.data.isNullOrEmpty()) {
                val items = response.data.filter { it.id > 0 }
                    .mapNotNull { runCatching { radioItemFactory(it) }.getOrNull() }

                withContext(Dispatchers.Main) {
                    if (!isFmMode || fmGeneration != activeFmGeneration ||
                        fmGeneration != queueBuildGeneration || request != similarFmRequest
                    ) {
                        return@withContext
                    }
                    val queuedIds = if (request == null) emptySet() else {
                        (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }.toSet()
                    }
                    val additions = items.filter { it.mediaId != currentId && it.mediaId !in queuedIds }
                        .let { if (request == null) it else it.distinctBy(MediaItem::mediaId) }
                    if (additions.isNotEmpty()) {
                        val firstAdditionIndex = player.mediaItemCount
                        player.addMediaItems(additions)
                        if (_queueAutoplayEnabled.value && _queueAutoplayStartEntryId.value == null) {
                            restoreQueueAutoplayStartIndex(firstAdditionIndex)
                        }
                        refreshQueueAutoplayBoundary()
                    }

                    _queueState.value = QueueState.Playing(
                        if (request == null) "私人 FM" else SIMILAR_FM_TITLE,
                        player.mediaItemCount,
                    )

                    // 触发占位符检查（如果你 FM 接口返回的是简略信息需要补全详情的话）
                    checkAndLoadMetadata()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "FM Fetch Error", e)
        } finally {
            if (fetchingFmGeneration == fmGeneration) fetchingFmGeneration = null
        }
    }


    fun fmTrashCurrent() {
        if (!isFmMode) return

        val currentIndex = player.currentMediaItemIndex
        val currentId = player.currentMediaItem?.mediaId ?: return

        scope.launch {
            try {
                // 1. 调用云音乐的“不喜欢”接口 (建议在 apiService 中实现)
                // apiService.dislikeSong(currentId)

                withContext(Dispatchers.Main) {
                    // 2. 移除当前项，ExoPlayer 会自动切换到下一首
                    player.removeMediaItem(currentIndex)

                    // 3. 如果移除后快没了，赶紧补充
                    if (player.mediaItemCount - player.currentMediaItemIndex <= 2) {
                        fetchAndAppendFmRecommendations()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Trash failed", e)
            }
        }
    }

    @OptIn(UnstableApi::class)
    fun playQueue(
        queue: Queue,
        startInShuffleMode: Boolean = false,
        playWhenReady: Boolean = true,
        repeatMode: Int = Player.REPEAT_MODE_ALL,
    ) {
        cancelServerShuffle()
        queueSelectionJob?.cancel()
        activeQueueBuildJob?.cancel()
        pendingSimilarGeneration = null
        val generation = ++queueBuildGeneration
        activeQueueBuildJob = scope.launch(Dispatchers.Main) {
            try {
                if (generation != queueBuildGeneration) return@launch
                _queueState.value = QueueState.Loading(queue.title ?: "加载中")

                val status = queue.getInitialStatus()
                val allIds = status.ids

                if (allIds.isEmpty()) {
                    _queueState.value = QueueState.Empty
                    return@launch
                }

                val startIndex = status.mediaItemIndex
                if (startIndex !in allIds.indices) {
                    _queueState.value = QueueState.Error("播放位置无效")
                    return@launch
                }

                // The selected item must be hydrated before prepare(). A placeholder can fail
                // immediately in the player, and MusicService handles that failure by seeking
                // to the next item (which is random when shuffle is enabled).
                val selectedItem = hydrateQueueItem(allIds[startIndex])
                if (selectedItem == null) {
                    Log.e(TAG, "Unable to hydrate selected item ${allIds[startIndex].first}")
                    if (generation == queueBuildGeneration) {
                        _queueState.value = QueueState.Error("当前歌曲加载失败")
                    }
                    return@launch
                }

                currentCoroutineContext().ensureActive()
                if (generation != queueBuildGeneration) return@launch

                val mediaItems = allIds.mapIndexed { index, item ->
                    if (index == startIndex) selectedItem
                    else item.second ?: createPlaceholder(item.first)
                }

                // 2. 停止并重置
                isFmMode = false
                activeFmGeneration = null
                similarFmRequest = null
                _queueAutoplayEnabled.value = false
                _queueAutoplayStartEntryId.value = null
                player.stop()
                player.clearMediaItems()

                // 强制先关闭随机模式 必须先关掉，才能保证 setMediaItems 里的 index 是线性的、准确的
                player.shuffleModeEnabled = false
                _isShuffleModeEnabled.value = false

                // 设置列表并直接跳转 此时 shuffle 是 false，所以 status.mediaItemIndex 绝对对应 list 里的第 N 个
                player.setMediaItems(mediaItems, startIndex, status.position.toLong())
                playlistSource = queue.playlistSource
                serverShuffleRequested = false

                player.repeatMode = repeatMode
                player.prepare()

                // 如果需要，再开启随机 此时当前播放的歌曲已经定下来了，ExoPlayer 只会打乱"后面"的歌
                if (startInShuffleMode) {
                    (player as? StableDeckPlayer)?.setPlaybackOrder(
                        listOf(startIndex) + mediaItems.indices.filter { it != startIndex }.shuffled(),
                    )
                    player.shuffleModeEnabled = true
                    _isShuffleModeEnabled.value = true
                }

                player.playWhenReady = playWhenReady
                if (startInShuffleMode) requestServerShuffle()

                _queueState.value = QueueState.Playing(queue.title ?: "播放列表", allIds.size)

                // 6. 触发懒加载
                // 因为当前歌曲已经是 RealItem 了，这个方法会自动跳过当前歌曲，去加载下一首
                checkAndLoadMetadata()

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation != queueBuildGeneration) return@launch
                Log.e(TAG, "playQueue Error", e)
                _queueState.value = QueueState.Error(e.message ?: "播放错误")
            } finally {
                if (generation == queueBuildGeneration) {
                    activeQueueBuildJob = null
                }
            }
        }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_IDLE && player.playerError != null) {
            _queueState.value = QueueState.Error(player.playerError?.message ?: "播放错误")
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (mediaItem == null) return

        // 基础元数据加载逻辑
        checkAndLoadMetadata()

        // --- 新增 FM 边界检查 ---
        if (isFmMode) {
            val currentIndex = player.currentMediaItemIndex
            val remaining = if (_queueAutoplayEnabled.value) {
                val order = player.playbackOrderIndices()
                order.indexOf(currentIndex).takeIf { it >= 0 }?.let { order.size - it }
                    ?: (player.mediaItemCount - currentIndex)
            } else {
                player.mediaItemCount - currentIndex
            }

            // 当播放到倒数第 2 首时，自动拉取新歌
            if (remaining <= 2) {
                scope.launch {
                    fetchAndAppendFmRecommendations()
                }
            }

            // 可选：清理历史，防止内存溢出（保留当前和上一首，删除更早的）
            if (!_queueAutoplayEnabled.value && currentIndex > 5) {
                player.removeMediaItems(0, currentIndex - 1)
            }
        }
    }

    fun setShuffleModeEnabled(enabled: Boolean) {
        if (isFmMode && !_queueAutoplayEnabled.value && enabled) {
            Log.w(TAG, "Shuffle is not allowed in FM mode")
            return
        }
        scope.launch(Dispatchers.Main) {
            if (enabled && !player.shuffleModeEnabled && player.mediaItemCount > 0) {
                val current = player.currentMediaItemIndex
                (player as? StableDeckPlayer)?.setPlaybackOrder(
                    listOf(current) + (0 until player.mediaItemCount).filter { it != current }.shuffled(),
                )
            }
            _isShuffleModeEnabled.value = enabled
            player.shuffleModeEnabled = enabled
        }
    }


    private fun checkAndLoadMetadata(windowSize: Int = 3) {
        scope.launch(Dispatchers.Main) {
            // 基础状态检查 确保线程存活且列表不为空
            if (released || player.mediaItemCount == 0) return@launch

            val timeline = player.currentTimeline
            if (timeline.isEmpty) return@launch
            if (!player.applicationLooper.thread.isAlive) return@launch

            val currentIndex = player.currentMediaItemIndex
            if (currentIndex !in 0 until player.mediaItemCount) return@launch
            val indicesToCheck = mutableSetOf<Int>()
            indicesToCheck.add(currentIndex)

            // 计算窗口索引 必须在获取到 INDEX_UNSET 时立即 break，不能把 -1 传给下一次调用

            // 向后找 (Next)
            var next = currentIndex
            for (i in 0 until windowSize) {
                // 如果当前已经是最后一个，next 会变成 INDEX_UNSET (-1)
                if (next == C.INDEX_UNSET) break

                next =
                    timeline.getNextWindowIndex(next, player.repeatMode, player.shuffleModeEnabled)
                if (next != C.INDEX_UNSET) {
                    indicesToCheck.add(next)
                }
            }

            // 向前找 (Previous)
            var prev = currentIndex
            for (i in 0 until windowSize) {
                // 如果当前已经是第一个，prev 会变成 INDEX_UNSET (-1)
                if (prev == C.INDEX_UNSET) break

                prev = timeline.getPreviousWindowIndex(
                    prev,
                    player.repeatMode,
                    player.shuffleModeEnabled
                )
                if (prev != C.INDEX_UNSET) {
                    indicesToCheck.add(prev)
                }
            }

            loadMetadataForIndices(indicesToCheck)
        }
    }

    /** Visible rows identify queue entries, not raw indices or decorative section headers. */
    fun loadMetadataForQueueEntries(entryIds: List<String>): Job {
        val requestedEntries = entryIds.toSet()
        return scope.launch(Dispatchers.Main) {
            if (released || requestedEntries.isEmpty()) return@launch
            loadMetadataForIndices((0 until player.mediaItemCount).filter { index ->
                player.getMediaItemAt(index).queueEntryId in requestedEntries
            })
        }
    }

    private suspend fun loadMetadataForIndices(indices: Collection<Int>) {
        if (released) return
        val generation = queueBuildGeneration
        val ids = indices.mapNotNull { index ->
            if (index !in 0 until player.mediaItemCount) return@mapNotNull null
            val item = player.getMediaItemAt(index)
            item.mediaId.takeIf {
                needsMetadataHydration(item) && loadingIds[it] != generation
            }
        }.distinct()
        if (ids.isEmpty()) return
        ids.forEach { loadingIds[it] = generation }
        try {
            val loadedById = loadSongDetails(ids).associateBy(MediaItem::mediaId)
            currentCoroutineContext().ensureActive()
            if (released || generation != queueBuildGeneration || loadedById.isEmpty()) return

            // Reordering can happen while the request runs. Match current IDs and resolve
            // duplicate occurrences together, preserving each entry's independent identity.
            for (index in 0 until player.mediaItemCount) {
                val currentItem = player.getMediaItemAt(index)
                val loaded = loadedById[currentItem.mediaId] ?: continue
                if (needsMetadataHydration(currentItem)) {
                    val entryId = currentItem.queueEntryId
                    player.replaceMediaItem(index, if (entryId == null) loaded else loaded.withQueueEntryId(entryId))
                }
            }
        } finally {
            // Partial responses must remain retryable. A stale request must not clear a
            // newer queue's in-flight request for the same song.
            ids.forEach { if (loadingIds[it] == generation) loadingIds.remove(it) }
        }
    }

    /**
     * 添加到队列末尾
     */
    fun addToQueue(items: List<MediaItem>) {
        scope.launch(Dispatchers.Main) {
            val order = player.playbackOrderIndices(true)
            val previousSize = player.mediaItemCount
            player.addMediaItems(items)
            (player as? StableDeckPlayer)?.setPlaybackOrder(order + (previousSize until player.mediaItemCount))

            if (player.playbackState == Player.STATE_IDLE) {
                player.prepare()
            }
        }
    }

    /**
     * 在下一首播放 (插队)
     */
    fun playNext(items: List<MediaItem>) {
        scope.launch(Dispatchers.Main) {
            val order = player.playbackOrderIndices(true)
            val currentPosition = order.indexOf(player.currentMediaItemIndex)
            val insertIndex =
                if (player.mediaItemCount == 0) 0 else player.currentMediaItemIndex + 1
            player.addMediaItems(insertIndex, items)
            val remapped = order.map { if (it >= insertIndex) it + items.size else it }.toMutableList()
            remapped.addAll(currentPosition + 1, (insertIndex until insertIndex + items.size).toList())
            (player as? StableDeckPlayer)?.setPlaybackOrder(remapped)

            if (player.playbackState == Player.STATE_IDLE) {
                player.prepare()
            }
        }
    }


    private suspend fun loadSongDetails(ids: List<String>): List<MediaItem> {
        return withContext(Dispatchers.IO) {
            try {
                val response = apiService.getSongDetail(
                    com.ljyh.mei.data.model.api.GetSongDetails(c = ids.joinToString(","))
                )
                response.songs.map { it.toMediaItem() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load song details", e)
                emptyList()
            }
        }
    }

    /**
     * Resolve a queue entry used as the current item before the player is prepared.
     * Non-placeholder items are already safe to use; unresolved entries reuse the same
     * metadata request as the lazy-loading path below.
     */
    private suspend fun hydrateQueueItem(item: Pair<String, MediaItem?>): MediaItem? {
        val existing = item.second
        if (existing != null && isPlayableMediaItem(existing)) return existing

        return loadSongDetails(listOf(item.first))
            .firstOrNull { it.mediaId == item.first }
    }

    fun release() {
        released = true
        cancelActiveQueueBuild()
        (player as? StableDeckPlayer)?.onQueueEdited = null
        (player as? StableDeckPlayer)?.onSeekRequested = null
        player.removeListener(this)
        loadingIds.clear()
        // 不要 cancel scope，因为它是由外部 Service 传进来的
    }

    private fun cancelActiveQueueBuild(clearPlaylistSource: Boolean = true) {
        cancelServerShuffle()
        queueSelectionJob?.cancel()
        if (clearPlaylistSource) playlistSource = null
        activeQueueBuildJob?.cancel()
        activeQueueBuildJob = null
        pendingSimilarGeneration = null
        queueBuildGeneration++
    }

    private fun cancelPendingSimilarStart() {
        val generation = pendingSimilarGeneration ?: return
        pendingSimilarGeneration = null
        if (generation != queueBuildGeneration) return
        // An explicit queue selection supersedes only the uncommitted similar request.
        // Preserve the current queue's playlist source and active FM context.
        activeQueueBuildJob?.cancel()
        activeQueueBuildJob = null
        queueBuildGeneration++
    }

    sealed class QueueState {
        object Idle : QueueState()
        data class Loading(val queueName: String) : QueueState()
        data class Playing(val queueName: String, val itemCount: Int) : QueueState()
        data class ItemsAdded(val count: Int) : QueueState()
        data class Error(val message: String) : QueueState()
        object Completed : QueueState()
        object Empty : QueueState()
    }
}

internal fun repairQueueAutoplayStartEntryId(
    startEntryId: String?,
    previousEntryIds: List<String>,
    currentEntryIds: List<String>,
): String? {
    if (startEntryId == null || startEntryId in currentEntryIds) return startEntryId
    val previousStart = previousEntryIds.indexOf(startEntryId)
    if (previousStart < 0) return null
    val remainingIds = currentEntryIds.toSet()
    return previousEntryIds.drop(previousStart + 1).firstOrNull { it in remainingIds }
}

internal fun isPlaceholderMediaItem(item: MediaItem): Boolean =
    item.localConfiguration?.uri?.toString() == PLACEHOLDER_URI

internal fun isPlayableMediaItem(item: MediaItem): Boolean =
    item.localConfiguration != null && !isPlaceholderMediaItem(item)

private fun needsMetadataHydration(item: MediaItem): Boolean =
    !isPlayableMediaItem(item)
