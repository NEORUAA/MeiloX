package com.ljyh.mei.ui.screen.search

import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import com.kyant.capsule.ContinuousRoundedRectangle
import com.ljyh.mei.R
import com.ljyh.mei.constants.PodcastsEnabledKey
import com.ljyh.mei.data.model.melox.SearchDiscovery
import com.ljyh.mei.data.model.melox.SearchDiscoveryPlaylist
import com.ljyh.mei.data.repository.MeloXRepository
import com.ljyh.mei.data.session.SessionStore
import com.ljyh.mei.data.session.SessionChangedException
import com.ljyh.mei.data.session.SessionStamp
import com.ljyh.mei.ui.glass.GlassButton
import com.ljyh.mei.ui.glass.IosPinnedListPage
import com.ljyh.mei.ui.glass.IosTypography
import com.ljyh.mei.ui.glass.LocalGlassColors
import com.ljyh.mei.ui.glass.SfIcon
import com.ljyh.mei.ui.glass.SfSymbol
import com.ljyh.mei.ui.local.LocalNavController
import com.ljyh.mei.ui.local.LocalPlayerAwareWindowInsets
import com.ljyh.mei.ui.screen.Screen
import com.ljyh.mei.utils.rememberPreference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import javax.inject.Inject

data class SearchDiscoveryState(
    val session: SessionStamp? = null,
    val loading: Boolean = true,
    val discovery: SearchDiscovery? = null,
    val error: Boolean = false,
)

class SearchDiscoveryViewModel internal constructor(
    private val sessions: SessionStore,
    private val load: suspend (SessionStamp) -> SearchDiscovery,
) : ViewModel() {
    @Inject constructor(repository: MeloXRepository, sessions: SessionStore) : this(sessions, repository::searchDiscovery)

    private val _state = MutableStateFlow(SearchDiscoveryState())
    val state = _state.asStateFlow()
    private val stateLock = Any()
    private var version = 0L
    private var job: Job? = null
    private val invalidation = sessions.onInvalidated { revision ->
        synchronized(stateLock) {
            if ((state.value.session?.generation ?: -1) < revision) {
                version++
                _state.value = pendingState()
            }
        }
    }

    init {
        viewModelScope.launch {
            combine(sessions.changes, sessions.recoveryRequired) { _, _ -> Unit }.collect {
                val stamp = if (sessions.recoveryRequired.value) null
                    else runCatching { sessions.snapshot() }.getOrNull()
                if (stamp == null) {
                    job?.cancel()
                    synchronized(stateLock) {
                        version++
                        _state.value = pendingState()
                    }
                } else if (state.value.session != stamp) refresh()
            }
        }
    }

    private fun pendingState() = SearchDiscoveryState(
        loading = !sessions.recoveryRequired.value, error = sessions.recoveryRequired.value,
    )

    fun withCurrent(expected: SearchDiscoveryState, action: () -> Unit) {
        val stamp = expected.session ?: return
        runCatching {
            sessions.withCurrent(stamp) {
                synchronized(stateLock) {
                    if (!sessions.recoveryRequired.value && state.value === expected) action()
                }
            }
        }
    }

    fun refresh() {
        val stamp = if (sessions.recoveryRequired.value) null
            else runCatching { sessions.snapshot() }.getOrNull()
        job?.cancel()
        if (stamp == null) {
            synchronized(stateLock) {
                version++
                _state.value = pendingState()
            }
            return
        }
        val requestVersion = runCatching {
            sessions.withCurrent(stamp) {
                if (sessions.recoveryRequired.value) throw SessionChangedException()
                synchronized(stateLock) {
                    _state.value = if (state.value.session == stamp) state.value.copy(loading = true, error = false)
                        else SearchDiscoveryState(session = stamp)
                    ++version
                }
            }
        }.getOrNull() ?: return
        job = viewModelScope.launch {
            try {
                sessions.requireCurrent(stamp)
                if (sessions.recoveryRequired.value) throw SessionChangedException()
                val discovery = load(stamp)
                currentCoroutineContext().ensureActive()
                publish(stamp, requestVersion) { SearchDiscoveryState(session = stamp, loading = false, discovery = discovery) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: SessionChangedException) {
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                runCatching { publish(stamp, requestVersion) { it.copy(loading = false, error = true) } }
            }
        }
    }

    private fun publish(stamp: SessionStamp, requestVersion: Long, update: (SearchDiscoveryState) -> SearchDiscoveryState) {
        sessions.withCurrent(stamp) {
            if (sessions.recoveryRequired.value) throw SessionChangedException()
            synchronized(stateLock) { if (version == requestVersion) _state.value = update(state.value) }
        }
    }

    override fun onCleared() {
        invalidation.close()
        super.onCleared()
    }
}

private data class SearchCategory(
    @param:StringRes val titleRes: Int,
    val apiName: String?,
    val icon: SfSymbol,
    val startColor: Color,
    val endColor: Color,
    val opensPodcasts: Boolean = false,
)

private val searchCategories = listOf(
    SearchCategory(R.string.search_category_rankings, "排行榜", SfSymbol.MusicNoteList, Color(0xFF5B5CE2), Color(0xFF9A6BFF)),
    SearchCategory(R.string.search_category_podcasts, null, SfSymbol.RadioWaves, Color(0xFFDB4B83), Color(0xFFFF8B6A), opensPodcasts = true),
    SearchCategory(R.string.search_category_chinese, "华语", SfSymbol.MusicNote, Color(0xFF158CBA), Color(0xFF45D6C6)),
    SearchCategory(R.string.search_category_western, "欧美", SfSymbol.Safari, Color(0xFF1776D2), Color(0xFF55B4FF)),
    SearchCategory(R.string.search_category_japanese, "日语", SfSymbol.Sparkles, Color(0xFFE4568E), Color(0xFFFFA4C5)),
    SearchCategory(R.string.search_category_korean, "韩语", SfSymbol.Sparkles, Color(0xFF7E55D9), Color(0xFFC185FF)),
    SearchCategory(R.string.search_category_cantonese, "粤语", SfSymbol.Waveform, Color(0xFF008B8B), Color(0xFF43D6B2)),
    SearchCategory(R.string.search_category_pop, "流行", SfSymbol.StarFilled, Color(0xFFFF7B39), Color(0xFFFFC34D)),
    SearchCategory(R.string.search_category_rock, "摇滚", SfSymbol.MusicNote, Color(0xFFB92B27), Color(0xFFEB5757)),
    SearchCategory(R.string.search_category_folk, "民谣", SfSymbol.MusicNote, Color(0xFF458C4B), Color(0xFF9CCB5B)),
    SearchCategory(R.string.search_category_electronic, "电子", SfSymbol.Waveform, Color(0xFF3155C6), Color(0xFF4AC4FF)),
    SearchCategory(R.string.search_category_rap, "说唱", SfSymbol.Microphone, Color(0xFF46318A), Color(0xFF8F68DC)),
    SearchCategory(R.string.search_category_rnb_soul, "R&B/Soul", SfSymbol.MusicNote, Color(0xFF984EAB), Color(0xFFD98EDC)),
    SearchCategory(R.string.search_category_classical, "古典", SfSymbol.MusicNoteList, Color(0xFF8B633B), Color(0xFFD5A86A)),
    SearchCategory(R.string.search_category_acg, "ACG", SfSymbol.Sparkles, Color(0xFFE34B74), Color(0xFFFFA14A)),
    SearchCategory(R.string.search_category_soundtrack, "影视原声", SfSymbol.MusicNote, Color(0xFF40506C), Color(0xFF7D9BC7)),
    SearchCategory(R.string.search_category_study, "学习", SfSymbol.MusicNoteList, Color(0xFF2B7A78), Color(0xFF6BCB9E)),
    SearchCategory(R.string.search_category_work, "工作", SfSymbol.Clock, Color(0xFF52616B), Color(0xFF8EA7B5)),
    SearchCategory(R.string.search_category_relax, "放松", SfSymbol.Waveform, Color(0xFF348F50), Color(0xFF56B4D3)),
    SearchCategory(R.string.search_category_night, "夜晚", SfSymbol.Clock, Color(0xFF24243E), Color(0xFF5D5D9B)),
)

@Composable
fun SearchLandingScreen(viewModel: SearchDiscoveryViewModel = viewModel()) {
    val state = viewModel.state.collectAsState().value
    val navController = LocalNavController.current
    val bottom = LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateBottomPadding()
    val podcastsEnabled by rememberPreference(PodcastsEnabledKey, true)
    val categories = searchCategories.filterNot { it.opensPodcasts && !podcastsEnabled }
    val colors = LocalGlassColors.current

    IosPinnedListPage(
        title = stringResource(R.string.app_tab_search),
        bottomPadding = bottom,
        horizontalContentPadding = 0.dp,
        largeTitleHorizontalPadding = 16.dp,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
            item(key = "popular-title") {
                Text(
                    text = stringResource(R.string.search_popular_recommendations),
                    style = IosTypography.title2,
                    fontWeight = FontWeight.Bold,
                    color = colors.content,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            if (state.loading && state.discovery == null) {
                item(key = "loading") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }
            if (state.error) {
                item(key = "error") {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = stringResource(R.string.load_failed),
                            style = IosTypography.body,
                            color = colors.secondaryContent,
                        )
                        GlassButton(
                            onClick = viewModel::refresh,
                            modifier = Modifier.padding(top = 8.dp),
                        ) {
                            Text(stringResource(R.string.retry))
                        }
                    }
                }
            }
            state.discovery?.let { discovery ->
                item(key = "recommendations") {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        items(discovery.recommendations, key = SearchDiscoveryPlaylist::id) { playlist ->
                            SearchRecommendationCard(playlist) {
                                viewModel.withCurrent(state) {
                                    Screen.PlayList.navigate(navController) { addPath(playlist.id.toString()) }
                                }
                            }
                        }
                    }
                }
            }
            item(key = "categories-title") {
                Text(
                    text = stringResource(R.string.search_browse_categories),
                    style = IosTypography.title2,
                    fontWeight = FontWeight.Bold,
                    color = colors.content,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            items(
                categories.chunked(2),
                key = { row -> row.joinToString("-") { it.titleRes.toString() } },
            ) { row ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    row.forEach { category ->
                        val title = stringResource(category.titleRes)
                        SearchCategoryCard(
                            category = category,
                            title = title,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                if (category.opensPodcasts) {
                                    Screen.Podcasts.navigate(navController)
                                } else {
                                    Screen.PlaylistCategory.navigate(navController) {
                                        addPath(Uri.encode(checkNotNull(category.apiName)))
                                        addPath(Uri.encode(title))
                                    }
                                }
                            },
                        )
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
    }
}

@Composable
private fun SearchRecommendationCard(
    playlist: SearchDiscoveryPlaylist,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier.width(172.dp).clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = playlist.artworkUrl,
            contentDescription = playlist.name,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(ContinuousRoundedRectangle(14.dp)),
            contentScale = ContentScale.Crop,
        )
        Text(
            text = playlist.name,
            style = IosTypography.subheadline,
            fontWeight = FontWeight.Medium,
            color = LocalGlassColors.current.content,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        val subtitle = playlist.copywriter?.takeIf(String::isNotBlank)
            ?: playlist.creatorNickname?.takeIf(String::isNotBlank)
        subtitle?.let {
            Text(
                text = it,
                style = IosTypography.caption,
                color = LocalGlassColors.current.secondaryContent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SearchCategoryCard(
    category: SearchCategory,
    title: String,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .aspectRatio(1.55f)
            .clip(ContinuousRoundedRectangle(14.dp))
            .background(Brush.linearGradient(listOf(category.startColor, category.endColor)))
            .clickable(onClick = onClick),
    ) {
        SfIcon(
            symbol = category.icon,
            contentDescription = null,
            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).rotate(-8f),
            tint = Color.White.copy(alpha = 0.22f),
            size = 60.dp,
            weight = FontWeight.Bold,
        )
        Text(
            text = title,
            style = IosTypography.headline,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
        )
    }
}
