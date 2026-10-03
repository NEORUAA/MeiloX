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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

@OptIn(UnstableApi::class)
class PlaybackQueueManager(
    private val player: Player,
    private val apiService: ApiService,
    private val weApiService: WeApiService,
    private val scope: CoroutineScope,
    private val serverShuffleAllowed: () -> Boolean = { true },
) : Player.Listener {

    private val TAG = "QueueManager"
    private val _queueState = MutableStateFlow<QueueState>(QueueState.Idle)
    private val loadingIds = ConcurrentHashMap.newKeySet<String>()
    private var activeQueueBuildJob: Job? = null
    private var queueBuildGeneration = 0L
    private var activeFmGeneration: Long? = null
    var playlistSource: PlaylistQueueSource? = null
        private set
    private var serverShuffleJob: Job? = null
    private var serverShuffleGeneration = 0L
    private var serverShuffleRequested = false
    private var cloudShuffleEnabled: Boolean? = null
    private var queueSelectionJob: Job? = null

    private val _isShuffleModeEnabled = MutableStateFlow(false)
    var isFmMode = false
    private var isFetchingFm = false


    init {
        player.addListener(this)
        (player as? StableDeckPlayer)?.onQueueEdited = { replaced ->
            cancelServerShuffle()
            queueSelectionJob?.cancel()
            if (replaced) playlistSource = null
        }
        (player as? StableDeckPlayer)?.onSeekRequested = { queueSelectionJob?.cancel() }
    }

    fun seekToQueueEntry(entryId: String) {
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

    private suspend fun startFmPlayback(seedItem: MediaItem?, generation: Long) {
        if (generation != queueBuildGeneration) return

        isFmMode = true
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
        if (isFetchingFm) return
        if (!isFmMode) return
        val fmGeneration = activeFmGeneration ?: queueBuildGeneration.also {
            activeFmGeneration = it
        }
        isFetchingFm = true
        try {
            // 调用 FM 接口
            val response = weApiService.getRadio(mapOf())
            if (response.code == 200 && response.data.isNotEmpty()) {

                // 过滤掉当前正在播放的 ID，防止重复
                val items = response.data
                    .filter { it.id.toString() != currentId }
                    .map { it.toMediaMetadata().toMediaItem() }

                withContext(Dispatchers.Main) {
                    val regularBuildPending = activeQueueBuildJob != null &&
                        fmGeneration != queueBuildGeneration
                    if (!isFmMode || fmGeneration != activeFmGeneration || regularBuildPending) {
                        return@withContext
                    }
                    // 追加到列表末尾
                    player.addMediaItems(items)

                    _queueState.value = QueueState.Playing("私人 FM", player.mediaItemCount)

                    // 触发占位符检查（如果你 FM 接口返回的是简略信息需要补全详情的话）
                    checkAndLoadMetadata()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "FM Fetch Error", e)
        } finally {
            isFetchingFm = false
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
    ) {
        cancelServerShuffle()
        queueSelectionJob?.cancel()
        activeQueueBuildJob?.cancel()
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
                player.stop()
                player.clearMediaItems()

                // 强制先关闭随机模式 必须先关掉，才能保证 setMediaItems 里的 index 是线性的、准确的
                player.shuffleModeEnabled = false
                _isShuffleModeEnabled.value = false

                // 设置列表并直接跳转 此时 shuffle 是 false，所以 status.mediaItemIndex 绝对对应 list 里的第 N 个
                player.setMediaItems(mediaItems, startIndex, status.position.toLong())
                playlistSource = queue.playlistSource
                serverShuffleRequested = false

                player.repeatMode = Player.REPEAT_MODE_ALL
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
            val totalCount = player.mediaItemCount

            // 当播放到倒数第 2 首时，自动拉取新歌
            if (totalCount - currentIndex <= 2) {
                scope.launch {
                    fetchAndAppendFmRecommendations()
                }
            }

            // 可选：清理历史，防止内存溢出（保留当前和上一首，删除更早的）
            if (currentIndex > 5) {
                player.removeMediaItems(0, currentIndex - 1)
            }
        }
    }

    fun setShuffleModeEnabled(enabled: Boolean) {
        if (isFmMode && enabled) {
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
            if (!player.applicationLooper.thread.isAlive || player.mediaItemCount == 0) return@launch

            val timeline = player.currentTimeline
            if (timeline.isEmpty) return@launch

            val currentIndex = player.currentMediaItemIndex
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

            // 3. 筛选需要加载的 ID
            val itemsNeedLoading = indicesToCheck.mapNotNull { index ->
                if (index < 0 || index >= player.mediaItemCount) return@mapNotNull null

                val item = player.getMediaItemAt(index)
                val id = item.mediaId

                // Resolve entries without a playable local configuration, including placeholders.
                if (needsMetadataHydration(item) && !loadingIds.contains(id)) {
                    id
                } else null
            }.distinct()

            if (itemsNeedLoading.isEmpty()) return@launch

            loadingIds.addAll(itemsNeedLoading)

            // IO 线程加载数据
            val loadedItems = try {
                withContext(Dispatchers.IO) {
                    loadSongDetails(itemsNeedLoading)
                }
            } catch (e: CancellationException) {
                loadingIds.removeAll(itemsNeedLoading.toSet())
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Fetch Metadata Failed", e)
                loadingIds.removeAll(itemsNeedLoading.toSet())
                emptyList()
            }

            // Also clear IDs omitted by a partial response; otherwise a failed entry would
            // remain marked forever and never be retried.
            loadingIds.removeAll(itemsNeedLoading.toSet())

            // 回到主线程更新
            if (loadedItems.isNotEmpty()) {
                loadingIds.removeAll(loadedItems.map { it.mediaId }.toSet())

                // 再次检查状态，防止异步期间 Player 被释放
                if (player.mediaItemCount == 0) return@launch
                // 为了性能，我们只遍历我们关心的那几个位置（indicesToCheck），但是必须做 Double Check
                indicesToCheck.forEach { index ->
                    // 索引防越界
                    if (index >= 0 && index < player.mediaItemCount) {
                        val currentItem = player.getMediaItemAt(index)

                        // 查找这个位置的 ID 是否有对应的新数据
                        val newItem = loadedItems.find { it.mediaId == currentItem.mediaId }

                        // 只有当 ID 匹配，且当前确实是占位符时才替换
                        // 这样即使 index 错位了（比如变成了别的歌），因为 ID 不匹配，也不会错误替换
                        if (newItem != null) {
                            // 只有当它是占位符，或者我们要强制刷新时才替换
                            if (isPlaceholderMediaItem(currentItem)) {
                                player.replaceMediaItem(index, newItem)
                            }
                        }
                    }
                }
            }
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
        cancelActiveQueueBuild()
        (player as? StableDeckPlayer)?.onQueueEdited = null
        (player as? StableDeckPlayer)?.onSeekRequested = null
        player.removeListener(this)
        loadingIds.clear()
        // 不要 cancel scope，因为它是由外部 Service 传进来的
    }

    private fun cancelActiveQueueBuild() {
        cancelServerShuffle()
        queueSelectionJob?.cancel()
        playlistSource = null
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

internal fun isPlaceholderMediaItem(item: MediaItem): Boolean =
    item.localConfiguration?.uri?.toString() == PLACEHOLDER_URI

internal fun isPlayableMediaItem(item: MediaItem): Boolean =
    item.localConfiguration != null && !isPlaceholderMediaItem(item)

private fun needsMetadataHydration(item: MediaItem): Boolean =
    !isPlayableMediaItem(item)
