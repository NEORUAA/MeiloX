package com.ljyh.mei.ui.screen.search


import com.ljyh.mei.ui.component.item.FixedCardCaption
import io.github.neoruaa.glasmio.core.swipeBackExcluded
import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import com.kyant.capsule.ContinuousRoundedRectangle
import com.ljyh.mei.R
import com.ljyh.mei.constants.PodcastsEnabledKey
import com.ljyh.mei.constants.HomeRowHorizontalPadding
import com.ljyh.mei.constants.homeCardSpacing
import com.ljyh.mei.constants.homePlaylistCardSize
import io.github.neoruaa.glasmio.ui.GlasmioButton
import io.github.neoruaa.glasmio.glass.GlassSurfaceStyle
import io.github.neoruaa.glasmio.ui.GlasmioGroupedList
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import com.ljyh.mei.ui.navigation.MeiloXPinnedListPage
import io.github.neoruaa.glasmio.scaffold.GlasmioHeaderAction
import io.github.neoruaa.glasmio.core.IosTypography
import io.github.neoruaa.glasmio.core.LocalGlassColors
import io.github.neoruaa.glasmio.icons.SfIcon
import io.github.neoruaa.glasmio.icons.SfSymbol
import com.ljyh.mei.ui.local.LocalNavController
import com.ljyh.mei.ui.local.LocalPlayerAwareWindowInsets
import com.ljyh.mei.ui.screen.Screen
import com.ljyh.mei.ui.component.utils.rememberDeviceInfo
import com.ljyh.mei.utils.rememberPreference
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

private data class SearchCategory(
    @param:StringRes val titleRes: Int,
    val label: String?,
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
fun SharedSearchLandingScreen(
    viewModel: SearchDiscoveryViewModel = hiltViewModel(),
    providerId: String,
    sourceAction: (@Composable () -> GlasmioHeaderAction)? = null,
) {
    androidx.compose.runtime.LaunchedEffect(providerId) { viewModel.open(providerId) }
    val state by viewModel.state.collectAsState()
    val navController = LocalNavController.current
    val bottomPaddingValues = LocalPlayerAwareWindowInsets.current.asPaddingValues()
    val podcastsEnabled by rememberPreference(PodcastsEnabledKey, true)
    val categories = state.discovery?.categories.orEmpty().filterNot { it.kind == "podcasts" && !podcastsEnabled }
    val categoryStyles = searchCategories
    val colors = LocalGlassColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val historyJson by rememberPreference(SearchHistoryKey, "")
    val history = remember(historyJson) { decodeSearchHistory(historyJson) }
    var showAllHistory by rememberSaveable { mutableStateOf(false) }

    val isLandscape = rememberDeviceInfo().isLandscape
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val categorySpacing = if (isLandscape) 16.dp else 12.dp
        val categoryColumns = if (isLandscape) {
            ((maxWidth - 32.dp + categorySpacing) / (164.dp + categorySpacing)).toInt().coerceIn(2, 5)
        } else 2
        MeiloXPinnedListPage(
            title = stringResource(R.string.app_tab_search),
            bottomPadding = 0.dp,
            bottomPaddingValues = bottomPaddingValues,
            horizontalContentPadding = 0.dp,
            largeTitleHorizontalPadding = 16.dp,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            actions = { listOfNotNull(sourceAction?.invoke()) },
        ) {
            if (history.isNotEmpty()) {
                item(key = "search-history") {
                    SearchHistorySection(
                        history = if (showAllHistory) history else history.take(5),
                        canExpand = history.size > 5 && !showAllHistory,
                        onExpand = { showAllHistory = true },
                        onSearch = { query ->
                            Screen.SearchResult.navigate(navController) {
                                addPath(Uri.encode(query))
                                addPath("1")
                            }
                        },
                        onRemove = { query -> scope.launch { context.removeSearchHistory(query) } },
                        onClear = { scope.launch { context.clearSearchHistory() } },
                    )
                }
            }
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
                        GlasmioButton(
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
                        modifier = Modifier.swipeBackExcluded(),
                        contentPadding = PaddingValues(horizontal = HomeRowHorizontalPadding),
                        horizontalArrangement = Arrangement.spacedBy(homeCardSpacing(isLandscape)),
                    ) {
                        items(discovery.recommendations, key = SearchRecommendation::id) { playlist ->
                            SearchRecommendationCard(playlist, cardSize = homePlaylistCardSize(isLandscape)) {
                                com.ljyh.mei.ui.screen.source.navigateSourceContent(navController, playlist.ref)
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
                categories.chunked(categoryColumns),
                key = { row -> row.joinToString("-") { it.id } },
            ) { row ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(categorySpacing),
                ) {
                    row.forEach { category ->
                        val matchingStyle = categoryStyles.firstOrNull { it.label == category.title || it.opensPodcasts && category.kind == "podcasts" }
                        val categoryStyle = matchingStyle ?: categoryStyles[categories.indexOf(category).coerceAtLeast(0) % categoryStyles.size]
                        val title = matchingStyle?.let { stringResource(it.titleRes) } ?: category.title
                        SearchCategoryCard(
                            category = categoryStyle,
                            title = title,
                            modifier = Modifier.weight(1f),
                            landscape = isLandscape,
                            onClick = {
                                if (category.kind == "podcasts") {
                                    Screen.Podcasts.navigate(navController) { addQuery("providerId", Uri.encode(providerId)) }
                                } else {
                                    Screen.PlaylistCategory.navigate(navController) {
                                        addPath(Uri.encode(category.id))
                                        addPath(Uri.encode(title))
                                        addQuery("providerId", Uri.encode(providerId))
                                    }
                                }
                            },
                        )
                    }
                    repeat(categoryColumns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
internal fun SearchHistorySection(
    history: List<String>,
    canExpand: Boolean,
    onExpand: () -> Unit,
    onSearch: (String) -> Unit,
    onRemove: (String) -> Unit,
    onClear: () -> Unit,
) {
    val colors = LocalGlassColors.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.search_history_title), Modifier.weight(1f),
                style = IosTypography.title2, fontWeight = FontWeight.Bold, color = colors.content)
            GlasmioButton(onClick = onClear, style = GlassSurfaceStyle.Navigation) {
                Text(stringResource(R.string.search_history_clear))
            }
        }
        GlasmioGroupedList(Modifier.padding(top = 8.dp)) {
            history.forEachIndexed { index, query ->
                GlasmioListRow(
                    title = query, systemName = "clock", showTopSeparator = index > 0,
                    onClick = { onSearch(query) },
                    trailing = {
                        Box(
                            modifier = Modifier
                                .offset(x = 8.dp)
                                .size(44.dp)
                                .clickable(role = Role.Button, onClick = { onRemove(query) }),
                            contentAlignment = Alignment.Center,
                        ) {
                            SfIcon(SfSymbol.Close, stringResource(R.string.search_history_remove, query), size = 14.dp)
                        }
                    },
                )
            }
            if (canExpand) GlasmioListRow(
                title = stringResource(R.string.search_history_show_all), onClick = onExpand,
            )
        }
    }
}

@Composable
private fun SearchRecommendationCard(
    playlist: SearchRecommendation,
    cardSize: Dp,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier.width(cardSize).clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = playlist.artworkUrl,
            contentDescription = playlist.name,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(ContinuousRoundedRectangle(8.dp)),
            contentScale = ContentScale.Crop,
        )
        val subtitle = playlist.copywriter?.takeIf(String::isNotBlank)
            ?: playlist.creatorNickname?.takeIf(String::isNotBlank)
        FixedCardCaption(
            title = playlist.name,
            subtitle = subtitle.orEmpty(),
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun SearchCategoryCard(
    category: SearchCategory,
    title: String,
    modifier: Modifier,
    landscape: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .aspectRatio(if (landscape) 16f / 9f else 1.55f)
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
            maxLines = if (landscape) 2 else 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
        )
    }
}
