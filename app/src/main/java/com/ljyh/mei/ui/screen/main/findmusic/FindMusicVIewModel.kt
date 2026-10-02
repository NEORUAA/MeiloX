package com.ljyh.mei.ui.screen.main.findmusic

import androidx.lifecycle.ViewModel
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.data.repository.PlaylistRepository
import com.ljyh.mei.data.repository.HighQualityPlaylistSource
import com.ljyh.mei.data.session.SessionStore
import com.ljyh.mei.data.session.SessionStamp
import com.ljyh.mei.data.session.SessionChangedException
import androidx.lifecycle.viewModelScope
import com.ljyh.mei.data.model.weapi.HighQualityPlaylistResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

class FindMusicViewModel internal constructor(
    private val repository: HighQualityPlaylistSource,
    private val sessions: SessionStore,
) : ViewModel() {
    @Inject constructor(repository: PlaylistRepository, sessions: SessionStore) : this(repository as HighQualityPlaylistSource, sessions)

    // 分类列表
    val categories = "全部,华语,欧美,日语,韩语,粤语,小语种,流行,摇滚,民谣,电子,舞曲,说唱,轻音乐,爵士,乡村,R&B/Soul,古典,民族,英伦,金属,朋克,蓝调,雷鬼,世界音乐,拉丁,另类/独立,New Age,古风,后摇,Bossa Nova,清晨,夜晚,学习,工作,午休,下午茶,地铁,驾车,运动,旅行,散步,酒吧,怀旧,清新,浪漫,性感,伤感,治愈,放松,孤独,感动,兴奋,快乐,安静,思念,影视原声,ACG,儿童,校园,游戏,70后,80后,90后,网络歌曲,KTV,经典,翻唱,吉他,钢琴,器乐,榜单,00后".split(",")

    private val _selectedCategory = MutableStateFlow("全部")
    val selectedCategory = _selectedCategory.asStateFlow()

    private val _highQualityPlaylist = MutableStateFlow<Resource<HighQualityPlaylistResult>>(Resource.Loading)
    val highQualityPlaylist = _highQualityPlaylist.asStateFlow()
    private val _playlistOwner = MutableStateFlow<Pair<SessionStamp, Int>?>(null)
    val playlistOwner = _playlistOwner.asStateFlow()
    private val _playlistCache = mutableMapOf<String, HighQualityPlaylistResult>()
    private val stateLock = Any()
    private var dataSession: SessionStamp? = null
    private var requestedCategory = "全部"
    private var requestedLimit = 30
    private var loadJob: Job? = null
    private var loadGeneration = 0
    private val invalidation = sessions.onInvalidated { revision ->
        synchronized(stateLock) {
            if ((dataSession?.generation ?: -1) < revision) resetSession(null)
        }
    }

    init {
        viewModelScope.launch {
            combine(sessions.changes, sessions.recoveryRequired) { _, _ -> Unit }.collect {
                val stamp = if (sessions.recoveryRequired.value) null else runCatching { sessions.snapshot() }.getOrNull()
                if (stamp == null) {
                    loadJob?.cancel()
                    synchronized(stateLock) { resetSession(null) }
                } else if (synchronized(stateLock) { dataSession != stamp }) {
                    loadCategoryData(requestedCategory, requestedLimit, true)
                }
            }
        }
    }

    private fun resetSession(owner: SessionStamp?) {
        loadGeneration++
        dataSession = owner
        _playlistOwner.value = null
        _playlistCache.clear()
        _highQualityPlaylist.value = if (sessions.recoveryRequired.value) {
            Resource.Error("Official session recovery is required")
        } else Resource.Loading
    }

    /**
     * 用户点击分类时调用
     */
    fun onCategorySelected(cat: String) {
        val category = normalizeCategory(cat)

        synchronized(stateLock) { _selectedCategory.value = category }
        loadCategoryData(category)
    }

    fun withCurrent(
        owner: Pair<SessionStamp, Int>?, expected: Resource<HighQualityPlaylistResult>, category: String,
        playlistId: Long, action: () -> Unit,
    ) {
        if (owner == null || expected !is Resource.Success) return
        val (stamp, generation) = owner
        runCatching {
            sessions.withCurrent(stamp) {
                synchronized(stateLock) {
                    if (!sessions.recoveryRequired.value && dataSession == stamp && loadGeneration == generation &&
                        _highQualityPlaylist.value === expected && _selectedCategory.value == category &&
                        requestedCategory == category && expected.data.playlists.any { it.id == playlistId }) action()
                }
            }
        }
    }

    /**
     * 执行实际的数据加载逻辑
     * @param forceRefresh 是否强制刷新（哪怕有缓存也重新请求）
     */
    fun loadCategoryData(cat: String, limit: Int = 30, forceRefresh: Boolean = false) {
        val category = normalizeCategory(cat)
        synchronized(stateLock) {
            requestedCategory = category
            requestedLimit = limit
        }
        loadJob?.cancel()
        val owner = if (sessions.recoveryRequired.value) null else runCatching { sessions.snapshot() }.getOrNull()
        if (owner == null) {
            synchronized(stateLock) { resetSession(null) }
            return
        }
        val (generation, cached) = runCatching { sessions.withCurrent(owner) {
            if (sessions.recoveryRequired.value) throw SessionChangedException()
            synchronized(stateLock) {
                if (dataSession != owner) resetSession(owner)
                val previous = if (forceRefresh) null else _playlistCache[category]
                _highQualityPlaylist.value = previous?.let { Resource.Success(it) } ?: Resource.Loading
                _playlistOwner.value = owner to ++loadGeneration
                loadGeneration to (previous != null)
            }
        } }.getOrNull() ?: return
        if (cached) return
        loadJob = viewModelScope.launch {
            fun publish(result: Resource<HighQualityPlaylistResult>) = sessions.withCurrent(owner) {
                if (sessions.recoveryRequired.value) throw SessionChangedException()
                synchronized(stateLock) {
                    if (dataSession == owner && generation == loadGeneration) {
                        if (result is Resource.Success) _playlistCache[category] = result.data
                        _highQualityPlaylist.value = result
                    }
                }
            }
            try {
                sessions.requireCurrent(owner)
                if (sessions.recoveryRequired.value) throw SessionChangedException()
                val result = repository.getHighQualityPlaylist(category, limit, owner)
                currentCoroutineContext().ensureActive()
                publish(result)
            } catch (error: CancellationException) {
                throw error
            } catch (_: SessionChangedException) {
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                runCatching { publish(Resource.Error(error.message ?: "High-quality playlist request failed")) }
            }
        }
    }

    private fun normalizeCategory(category: String): String =
        if (category == "排行榜") "榜单" else category

    override fun onCleared() {
        invalidation.close()
        synchronized(stateLock) { loadGeneration++; _playlistOwner.value = null; _playlistCache.clear() }
        super.onCleared()
    }
}
