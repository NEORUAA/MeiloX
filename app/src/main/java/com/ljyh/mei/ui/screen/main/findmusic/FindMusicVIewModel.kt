package com.ljyh.mei.ui.screen.main.findmusic

import androidx.lifecycle.ViewModel
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.data.repository.PlaylistRepository
import androidx.lifecycle.viewModelScope
import com.ljyh.mei.data.model.weapi.HighQualityPlaylistResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

class FindMusicViewModel @Inject constructor(
    private val repository: PlaylistRepository
) : ViewModel() {

    // 分类列表
    val categories = "全部,华语,欧美,日语,韩语,粤语,小语种,流行,摇滚,民谣,电子,舞曲,说唱,轻音乐,爵士,乡村,R&B/Soul,古典,民族,英伦,金属,朋克,蓝调,雷鬼,世界音乐,拉丁,另类/独立,New Age,古风,后摇,Bossa Nova,清晨,夜晚,学习,工作,午休,下午茶,地铁,驾车,运动,旅行,散步,酒吧,怀旧,清新,浪漫,性感,伤感,治愈,放松,孤独,感动,兴奋,快乐,安静,思念,影视原声,ACG,儿童,校园,游戏,70后,80后,90后,网络歌曲,KTV,经典,翻唱,吉他,钢琴,器乐,榜单,00后".split(",")

    private val _selectedCategory = MutableStateFlow("全部")
    val selectedCategory = _selectedCategory.asStateFlow()

    private val _highQualityPlaylist = MutableStateFlow<Resource<HighQualityPlaylistResult>>(Resource.Loading)
    val highQualityPlaylist = _highQualityPlaylist.asStateFlow()
    private val _playlistCache = mutableMapOf<String, HighQualityPlaylistResult>()
    private var loadJob: Job? = null
    private var loadGeneration = 0

    init {
        // 初始化加载
        loadCategoryData("全部")
    }

    /**
     * 用户点击分类时调用
     */
    fun onCategorySelected(cat: String) {
        val category = normalizeCategory(cat)

        // 1. 更新选中的 Tag UI
        _selectedCategory.value = category

        // 2. 检查缓存
        if (_playlistCache.containsKey(category)) {
            loadGeneration++
            loadJob?.cancel()
            // 【命中缓存】：直接使用缓存数据，不发网络请求
            _highQualityPlaylist.value = Resource.Success(_playlistCache[category]!!)
        } else {
            // 【未命中缓存】：发起网络请求
            loadCategoryData(category)
        }
    }

    /**
     * 执行实际的数据加载逻辑
     * @param forceRefresh 是否强制刷新（哪怕有缓存也重新请求）
     */
    fun loadCategoryData(cat: String, limit: Int = 30, forceRefresh: Boolean = false) {
        val category = normalizeCategory(cat)
        if (!forceRefresh) {
            _playlistCache[category]?.let { cached ->
                loadGeneration++
                loadJob?.cancel()
                _highQualityPlaylist.value = Resource.Success(cached)
                return
            }
        }
        val generation = ++loadGeneration
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _highQualityPlaylist.value = Resource.Loading

            // 调用 Repository
            val result = repository.getHighQualityPlaylist(category, limit)
            if (generation != loadGeneration) return@launch

            // 如果请求成功，写入缓存
            if (result is Resource.Success) {
                _playlistCache[category] = result.data
            }

            // 更新 UI
            _highQualityPlaylist.value = result
        }
    }

    private fun normalizeCategory(category: String): String =
        if (category == "排行榜") "榜单" else category
}
