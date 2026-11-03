package com.ljyh.mei.ui.screen.main.home

import kotlinx.coroutines.Job
import com.ljyh.mei.constants.HomeSongNextPagePeek
import com.ljyh.mei.ui.screen.playlist.component.StandaloneTrackActionOverlay
import com.ljyh.mei.ui.screen.playlist.PlaylistViewModel
import com.ljyh.mei.ui.component.player.OverlayState
import io.github.neoruaa.glasmio.core.swipeBackExcluded
import io.github.neoruaa.glasmio.scaffold.rememberGlasmioHeaderScrollEdge
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.calculateTargetValue
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.snapping.SnapLayoutInfoProvider
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.media3.common.util.UnstableApi
import com.ljyh.mei.ui.navigation.MeiNavigator
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.kyant.capsule.ContinuousRoundedRectangle
import com.ljyh.mei.ui.component.playlist.FavoriteTrackStar
import com.ljyh.mei.ui.component.playlist.rememberFavoriteTrackIds
import com.ljyh.mei.constants.songListCoverSize
import androidx.compose.ui.platform.testTag
import com.ljyh.mei.ui.component.item.ThreeRowSongList
import com.ljyh.mei.ui.component.item.HomeSongListRow
import com.ljyh.mei.R
import com.ljyh.mei.constants.HomeSectionSpacing
import com.ljyh.mei.constants.HomeCardSpacing
import com.ljyh.mei.constants.homeCardSpacing
import com.ljyh.mei.constants.HomeRowHorizontalPadding
import com.ljyh.mei.constants.homePlaylistCardSize
import com.ljyh.mei.constants.HomeSectionTitlePadding
import com.ljyh.mei.constants.HomeSongCoverSize
import com.ljyh.mei.constants.HomeSongCoverCornerRadius
import com.ljyh.mei.constants.HomeSongActionSize
import com.ljyh.mei.constants.HomeSongRowSpacing
import com.ljyh.mei.constants.RecommendCardHeight
import com.ljyh.mei.constants.PortraitRecommendCardHeight
import com.ljyh.mei.constants.RecommendCardWidth
import com.ljyh.mei.constants.PortraitRecommendCardWidth
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.eapi.HomePageResourceShow
import com.ljyh.mei.data.model.eapi.HomePageResourceShow.Data.Block.DslData.BlockResource.Resource as HomeCardResource
import com.ljyh.mei.data.model.toMediaItem
import com.ljyh.mei.data.model.toMediaMetadata
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.extensions.togglePlayPause
import com.ljyh.mei.playback.queue.ListQueue
import com.ljyh.mei.ui.component.globalProfileHeaderAction
import com.ljyh.mei.ui.component.home.CardExtInfo
import com.ljyh.mei.ui.component.home.PlaylistCard
import com.ljyh.mei.ui.component.home.RecommendCard
import com.ljyh.mei.ui.component.home.homeArtworkBorder
import com.ljyh.mei.ui.component.player.PlayerViewModel
import com.ljyh.mei.ui.component.utils.rememberDeviceInfo
import io.github.neoruaa.glasmio.scaffold.pullToRefreshRebound
import com.ljyh.mei.ui.navigation.MeiloXPinnedPage
import com.ljyh.mei.ui.navigation.registerMeiloXNavigationViewport
import io.github.neoruaa.glasmio.core.IosTypography
import io.github.neoruaa.glasmio.core.LocalGlassColors
import io.github.neoruaa.glasmio.icons.SfIcon
import io.github.neoruaa.glasmio.icons.SfSymbol
import com.ljyh.mei.ui.local.LocalNavController
import com.ljyh.mei.ui.local.LocalPlayerAwareWindowInsets
import com.ljyh.mei.ui.local.LocalPlayerConnection
import com.ljyh.mei.ui.screen.Screen
import com.ljyh.mei.utils.DateUtils.getGreeting
import com.ljyh.mei.utils.positionComparator
import java.util.UUID
import kotlin.math.abs
import kotlin.math.sign
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel = hiltViewModel(),
    playerViewModel: PlayerViewModel = hiltViewModel(),
) {
    val navController = LocalNavController.current
    val backStackEntry = navController.currentBackStackEntry
    val scrollToTop by backStackEntry?.savedStateHandle
        ?.getStateFlow("scrollToTop", false)
        ?.collectAsState(initial = false) ?: remember { mutableStateOf(false) }

    // 替换为 LazyListState
    val listState = rememberLazyListState()
    registerMeiloXNavigationViewport(listState)

    val homePageResourceShowPage1 by viewModel.homePageResourceShow.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.refreshErrors.collect {
            Toast.makeText(context, R.string.load_failed, Toast.LENGTH_SHORT).show()
        }
    }
    val device = rememberDeviceInfo()
    val glassColors = LocalGlassColors.current
    val playerConnection = LocalPlayerConnection.current
    val intelligenceList by playerViewModel.intelligenceList.collectAsState()
    val intelligenceFirstSong by playerViewModel.songDetail.collectAsState()

    // Consume the one-shot intelligence-mode result at screen scope. A block item can leave and
    // re-enter LazyColumn composition while the screen remains alive, so it must not own playback.
    LaunchedEffect(intelligenceList, intelligenceFirstSong, playerConnection) {
        val connection = playerConnection ?: return@LaunchedEffect
        val songs = when (val result = intelligenceList) {
            is Resource.Success -> result.data.data
            is Resource.Error,
            Resource.Loading -> return@LaunchedEffect
        }

        val firstSong = when (val result = intelligenceFirstSong) {
            is Resource.Success -> result.data.songs.firstOrNull()
            is Resource.Error -> null
            Resource.Loading -> return@LaunchedEffect
        }

        val items = songs.mapNotNull { song ->
            runCatching { song.toMediaMetadata().toMediaItem() }.getOrNull()
        }.toMutableList()
        firstSong?.let { song ->
            runCatching { song.toMediaMetadata().toMediaItem() }
                .getOrNull()
                ?.let { item -> items.add(0, item) }
        }

        if (items.isNotEmpty()) {
            Timber.tag("IntelligenceList").d("Playing ${songs.size} songs")
            connection.playQueue(
                ListQueue(
                    id = "intelligence-${System.currentTimeMillis()}",
                    title = "心动模式",
                    items = items.map { it.mediaId to it },
                    startIndex = 0,
                    position = 0
                )
            )
        }

        playerViewModel.consumeIntelligencePlayback()
    }

    // 滚动到顶部逻辑
    LaunchedEffect(scrollToTop) {
        if (scrollToTop) {
            listState.animateScrollToItem(0)
            backStackEntry?.savedStateHandle?.set("scrollToTop", false)
        }
    }

    val systemBarsPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues()
    val refreshState = rememberPullToRefreshState()
    val collapseDistancePx = with(LocalDensity.current) { 56.dp.toPx() }
    val collapseProgress by remember(listState) {
        derivedStateOf {
            if (listState.firstVisibleItemIndex > 0) 1f
            else (listState.firstVisibleItemScrollOffset / collapseDistancePx).coerceIn(0f, 1f)
        }
    }

    MeiloXPinnedPage(
        title = stringResource(R.string.app_tab_home),
        bottomPadding = systemBarsPadding.calculateBottomPadding(),
        collapseProgress = collapseProgress,
        topBarBlurProgress = rememberGlasmioHeaderScrollEdge(listState),
        backgroundColor = if (glassColors.isDark) glassColors.groupedBackground else Color.White,
        actions = { listOf(globalProfileHeaderAction()) },
    ) { pinnedPadding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { viewModel.homePageResourceShow(true) },
            state = refreshState,
        ) {
            Box(Modifier.fillMaxSize().pullToRefreshRebound(refreshState, isRefreshing)) {
                when (val result = homePageResourceShowPage1) {
                    is Resource.Success -> {
                        // 排序逻辑移出 LazyColumn，减少重组时的计算
                        val sortedBlocks = remember(result.data) {
                            result.data.filter { homeBlockData(it) != null }.sortedWith(positionComparator)
                        }


                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                // Preserve the title's origin without translated empty space below it.
                                top = pinnedPadding.calculateTopPadding() - 4.dp,
                                bottom = pinnedPadding.calculateBottomPadding() + HomeSectionSpacing
                            ),
                            verticalArrangement = Arrangement.spacedBy(HomeSectionSpacing)
                        ) {
                            if (!device.isLandscape) item(key = "ios-large-title") {
                                Text(
                                    stringResource(R.string.app_tab_home),
                                    style = IosTypography.largeTitle,
                                    color = LocalGlassColors.current.content,
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                )
                            }
                            items(
                                items = sortedBlocks,
                                key = { it.positionCode }
                            ) { block ->
                                HomeBlockItem(
                                    block = block,
                                    navController = navController,
                                    viewModel = viewModel,
                                    playerViewModel = playerViewModel,
                                    device = device
                                )
                            }
                        }
                    }

                    is Resource.Error -> {
                        // 这里可以加一个错误重试页面
                    }

                    Resource.Loading -> {
                        HomeShimmer(
                            contentPadding = PaddingValues(
                                top = pinnedPadding.calculateTopPadding(),
                                bottom = pinnedPadding.calculateBottomPadding() + HomeSectionSpacing,
                            ),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 独立的 Block 渲染组件，分离逻辑，保持代码清晰
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun HomeBlockItem(
    block: HomePageResourceShow.Data.Block,
    navController: MeiNavigator,
    viewModel: HomeViewModel,
    playerViewModel: PlayerViewModel,
    device: com.ljyh.mei.ui.component.utils.DeviceInfo
) {
    val gson = remember { Gson() }
    val playerConnection = LocalPlayerConnection.current ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loadingRecommendationKey by remember { mutableStateOf<String?>(null) }
    var playbackRequestGeneration by remember { mutableIntStateOf(0) }

    fun loadRecommendation(key: String, generation: Int, action: suspend () -> Unit) {
        loadingRecommendationKey = key
        scope.launch {
            try {
                action()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Timber.tag("HomeRecommendation").e(error, "Unable to load home recommendation")
                if (generation == playbackRequestGeneration) {
                    Toast.makeText(context, R.string.load_failed, Toast.LENGTH_SHORT).show()
                }
            } finally {
                if (generation == playbackRequestGeneration) {
                    loadingRecommendationKey = null
                }
            }
        }
    }

    val recommendCardWidth = if (device.isLandscape) RecommendCardWidth else PortraitRecommendCardWidth
    val recommendCardHeight = if (device.isLandscape) RecommendCardHeight else PortraitRecommendCardHeight
    val cardSpacing = homeCardSpacing(device.isLandscape)

    // 解析逻辑缓存，只要 block 不变，就不会重新解析 JSON
    val blockData = remember(block) {
        Timber.tag("Block").d(block.positionCode)
        homeBlockData(block)
    } ?: return

    Timber.tag("Block").d(block.positionCode)

    when (block.positionCode) {
        // --- 每日推荐 ---
        "PAGE_RECOMMEND_DAILY_RECOMMEND" -> {
            val resources = remember(blockData) {
                blockData.get("resources").asJsonArray.map {
                    gson.fromJson(
                        it.asJsonObject,
                        HomePageResourceShow.Data.Block.DslData.BlockResource.Resource::class.java
                    )
                }
            }

            BlockWithTitle(
                title = getGreeting(),
                resources = resources,
                rowHeight = recommendCardHeight,
                eagerCardWidth = recommendCardWidth,
                cardSpacing = cardSpacing,
            ) { resource ->
                val recommendationKey = "${resource.resourceType}:${resource.resourceId}:${resource.action}"
                RecommendCard(
                    cover = resource.coverImg,
                    title = resource.singleLineTitle?.trim()?.takeIf { it.isNotEmpty() }
                        ?: resource.title.trim().takeIf { it.isNotEmpty() },
                    extInfo = CardExtInfo(
                        icon = resource.iconDesc?.image,
                        text = resource.subTitle
                    ),
                    cardWidth = recommendCardWidth,
                    cardHeight = recommendCardHeight,
                    isLoading = loadingRecommendationKey == recommendationKey,
                ) {
                    if (loadingRecommendationKey == recommendationKey) return@RecommendCard
                    val requestGeneration = ++playbackRequestGeneration
                    loadingRecommendationKey = null
                    when (resource.resourceType) {
                        "dailySongs" -> Screen.EveryDay.navigate(navController)
                        "star" -> {
                            val playlistId = resource.resourceId
                            resource.extInfo.songId?.let { songId ->
                                playerViewModel.startIntelligenceMode(songId, playlistId, songId)
                            }

                            // 心动模式，发了ids
                        }

                        "fm" -> {
                            // 私人FM
                            playerConnection.fmStart(resource.resourceId)

                        }

                        "podcast", "djprogram" -> {
                            loadRecommendation(recommendationKey, requestGeneration) {
                                val program = viewModel.podcastProgram(resource.resourceId.toLong())
                                if (requestGeneration != playbackRequestGeneration) return@loadRecommendation
                                val item = program.toMediaMetadata().toMediaItem()
                                playerConnection.playQueue(
                                    ListQueue(
                                        id = "home_podcast_${program.id}",
                                        title = resource.subTitle,
                                        items = listOf(item.mediaId to item),
                                        startIndex = 0,
                                    )
                                )
                            }
                        }

                        "musicPodcast" -> resource.toMusicPodcastMediaMetadata()?.let { metadata ->
                            val item = metadata.toMediaItem()
                            playerConnection.playQueue(
                                ListQueue(
                                    id = "home_music_podcast_${resource.resourceId}",
                                    title = resource.title,
                                    items = listOf(item.mediaId to item),
                                    startIndex = 0,
                                )
                            )
                        }

                        "similarSong" -> {
                            val songIds = parseRecommendationSongIds(resource.resourceId)
                            if (songIds.isEmpty()) {
                                Toast.makeText(context, R.string.load_failed, Toast.LENGTH_SHORT).show()
                            } else {
                                loadRecommendation(recommendationKey, requestGeneration) {
                                    val requestContext = currentCoroutineContext()
                                    val started = playerConnection.similarFmStart(songIds) {
                                        requestContext.isActive && requestGeneration == playbackRequestGeneration
                                    }
                                    if (requestGeneration == playbackRequestGeneration) {
                                        check(started) { "Unable to start similar-song recommendations" }
                                    }
                                }
                            }
                        }

                        "nonstandard" -> {
                            val playlistId = parseRecommendationPlaylistId(resource.action, resource.playBtnData)
                            if (playlistId != null) {
                                Screen.PlayList.navigate(navController) { addPath(playlistId.toString()) }
                            } else {
                                Toast.makeText(context, R.string.load_failed, Toast.LENGTH_SHORT).show()
                            }
                        }

                        "similarArtist" -> {
                            // 从喜欢的艺人听起
                            // val ids = resourceId // 需要解析 json， 跳转艺人界面
                            val artistIds =
                                gson.fromJson(resource.resourceId, Array<String>::class.java)
                            Screen.Artist.navigate(navController) {
                                addPath(artistIds[0])
                            }
                        }

                        "playList" -> Screen.PlayList.navigate(navController) { addPath(resource.resourceId) }
                        else -> Toast.makeText(context, R.string.load_failed, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        // --- 各种歌单推荐 (雷达、云村、场景等) ---
        in HomePlaylistBlockCodes -> {
            // 将这些相似的逻辑合并处理，减少代码重复
            val title = homeBlockTitle(blockData, block.positionCode)
                ?: if (block.positionCode == "PAGE_RECOMMEND_RADAR") "雷达歌单" else null

            // 处理数据源字段差异
            val resourceArray = if (block.positionCode == "PAGE_RECOMMEND_RADAR")
                (blockData.get("resources") ?: blockData.get("blockResource")).asJsonArray
            else blockData.get("resources").asJsonArray

            val resources = remember(resourceArray) {
                resourceArray.map {
                    gson.fromJson(
                        it.asJsonObject,
                        HomePageResourceShow.Data.Block.DslData.BlockResource.Resource::class.java
                    )
                }.let { list ->
                    // 如果是我的歌单，去掉最后一个（通常是添加按钮或其他）
                    if (block.positionCode == "PAGE_RECOMMEND_MY_SHEET") list.dropLast(1) else list
                }
            }

            val playlistCardSize = homePlaylistCardSize(device.isLandscape)
            BlockWithTitle(
                title = title,
                resources = resources,
                cardSpacing = cardSpacing,
            ) { resource ->
                val isRadar = block.positionCode == "PAGE_RECOMMEND_RADAR"
                val isArtistPlaylist = block.positionCode == "PAGE_RECOMMEND_MIXED_ARTIST_PLAYLIST" ||
                    title?.contains("热门金曲") == true
                val separateCaptions = isRadar || isArtistPlaylist
                val coverText = resource.resourceExtInfo?.coverText
                val coverCaption = coverText?.filter(String::isNotBlank)
                    ?.joinToString(if (isRadar) "" else " ")?.takeIf(String::isNotBlank)
                val artistCoverCaption = if (isArtistPlaylist) homeArtistPlaylistCoverText(resource) else null
                val coverImg = if (separateCaptions) {
                    com.ljyh.mei.utils.playlistCoverWithoutOverlays(resource.coverImg)
                } else resource.coverImg
                PlaylistCard(
                    id = resource.resourceId,
                    title = if (isRadar) coverCaption.orEmpty() else resource.title,
                    coverImg = coverImg,
                    subTitle = if (separateCaptions) null else coverText,
                    extInfo = resource.resourceInteractInfo?.playCount,
                    imageSize = !coverImg.contains("thumbnail"),
                    cardSize = playlistCardSize,
                    titleMaxLines = if (separateCaptions) 1 else 2,
                    secondaryTitle = when {
                        isRadar -> resource.title
                        isArtistPlaylist -> artistCoverCaption ?: coverCaption
                        else -> null
                    },
                ) {
                    Screen.PlayList.navigate(navController) { addPath(resource.resourceId) }
                }
            }
        }

        // --- 私人推荐歌曲 / 相似歌曲 (三行滑动) ---
        in HomeSongBlockCodes -> {
            val title = homeBlockTitle(blockData, block.positionCode)
            val itemsArray = blockData.get("content").asJsonObject.get("items").asJsonArray

            val songsBlocks = remember(itemsArray) {
                itemsArray.map {
                    gson.fromJson(
                        it.asJsonObject,
                        HomePageResourceShow.Data.Block.DslData.HomeCommon.Content.Item::class.java
                    )
                }
            }

            title?.let { Title(it) }
            TripleLaneSlider(
                songsArray = songsBlocks,
                isLandscape = device.isLandscape,
            ) { songs, index ->
                val flatSongs = songs.flatMap { it.items }.map { it.resourceId to null }
                playerConnection.onTrackClicked(
                        trackId = flatSongs[index].first,
                        buildQueue = {
                            ListQueue(
                                id = UUID.randomUUID().toString(),
                                title = if (block.positionCode == "PAGE_RECOMMEND_PRIVATE_RCMD_SONG") "PRIVATE_RCMD_SONG" else "RED_SIMILAR_SONG",
                                items = flatSongs,
                                startIndex = index
                            )
                        }
                    )
            }
        }
    }
}

private fun HomePageResourceShow.Data.Block.DslData.BlockResource.Resource.toMusicPodcastMediaMetadata(): MediaMetadata? =
    runCatching {
        val programData = Gson().fromJson(programDTO, JsonObject::class.java)
            .getAsJsonObject("programData")
        val mainSong = programData.getAsJsonObject("mainSong")
        val radio = programData.getAsJsonObject("radio")
        val radioId = radio.get("id").asLong
        MediaMetadata(
            id = mainSong.get("id").asLong,
            title = programData.get("name")?.takeUnless { it.isJsonNull }?.asString ?: title,
            coverUrl = programData.get("coverUrl")?.takeUnless { it.isJsonNull }?.asString ?: coverImg,
            artists = listOf(MediaMetadata.Artist(0, subTitle)),
            duration = programData.get("duration")?.takeUnless { it.isJsonNull }?.asLong ?: 0L,
            album = MediaMetadata.Album(radioId, subTitle),
            isPodcast = true,
        )
    }.onFailure { error ->
        Timber.tag("HomeMusicPodcast").e(error, "Unable to parse program %s", resourceId)
    }.getOrNull()

@Composable
private fun BlockWithTitle(
    title: String?,
    resources: List<HomeCardResource>,
    rowHeight: Dp? = null,
    eagerCardWidth: Dp? = null,
    cardSpacing: Dp = HomeCardSpacing,
    content: @Composable (HomeCardResource) -> Unit,
) {
    val resourceKeys = remember(resources) { homeResourceKeys(resources) }
    Column {
        title?.let { Title(it) }
        if (eagerCardWidth != null) {
            val scrollState = rememberScrollState()
            val flingBehavior = rememberHomeRowFlingBehavior(
                scrollState, eagerCardWidth, cardSpacing, resources.size,
            )
            Row(
                modifier = Modifier.fillMaxWidth()
                    .swipeBackExcluded()
                    .then(if (rowHeight != null) Modifier.height(rowHeight) else Modifier)
                    .horizontalScroll(scrollState, flingBehavior = flingBehavior)
                    .padding(horizontal = HomeRowHorizontalPadding),
                horizontalArrangement = Arrangement.spacedBy(cardSpacing),
                verticalAlignment = Alignment.Top,
            ) {
                resources.forEachIndexed { index, resource ->
                    key(resourceKeys[index]) { content(resource) }
                }
            }
        } else {
            val listState = rememberLazyListState()
            LazyRow(
                state = listState,
                flingBehavior = rememberHomeRowFlingBehavior(listState),
                contentPadding = PaddingValues(horizontal = HomeRowHorizontalPadding),
                horizontalArrangement = Arrangement.spacedBy(cardSpacing),
                verticalAlignment = Alignment.Top,
                modifier = Modifier.fillMaxWidth()
                    .swipeBackExcluded()
                    .then(if (rowHeight != null) Modifier.height(rowHeight) else Modifier),
            ) {
                itemsIndexed(resources, key = { index, _ -> resourceKeys[index] }) { _, resource ->
                    content(resource)
                }
            }
        }
    }
}

@Composable
private fun rememberHomeRowFlingBehavior(
    scrollState: ScrollState,
    cardWidth: Dp,
    cardSpacing: Dp,
    cardCount: Int,
): FlingBehavior {
    val density = LocalDensity.current
    val itemSize = with(density) { cardWidth.roundToPx() }
    val stride = itemSize + with(density) { cardSpacing.roundToPx() }
    val minimumSnapVelocity = with(density) { 400.dp.toPx() }
    val provider = remember(scrollState, itemSize, stride, cardCount, minimumSnapVelocity) {
        object : SnapLayoutInfoProvider {
            override fun calculateApproachOffset(velocity: Float, decayOffset: Float): Float =
                (abs(decayOffset) - itemSize).coerceAtLeast(0f) * sign(decayOffset)

            override fun calculateSnapOffset(velocity: Float): Float {
                var before = Float.NEGATIVE_INFINITY
                var after = Float.POSITIVE_INFINITY
                repeat(cardCount) { index ->
                    val target = (index * stride).coerceAtMost(scrollState.maxValue)
                    val offset = (target - scrollState.value).toFloat()
                    if (offset <= 0f) before = maxOf(before, offset)
                    if (offset >= 0f) after = minOf(after, offset)
                }
                val offset = when {
                    abs(velocity) < minimumSnapVelocity -> if (abs(before) <= abs(after)) before else after
                    velocity > 0f -> after
                    else -> before
                }
                return if (offset.isFinite()) offset else 0f
            }
        }
    }
    val snapping = rememberSnapFlingBehavior(provider)
    val freeFling = ScrollableDefaults.flingBehavior()
    val decay = rememberSplineBasedDecay<Float>()
    return remember(scrollState, snapping, freeFling, decay) {
        object : FlingBehavior {
            override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
                val target = decay.calculateTargetValue(0f, initialVelocity)
                val reachesEdge = (initialVelocity < 0f && -target >= scrollState.value) ||
                    (initialVelocity > 0f && target >= scrollState.maxValue - scrollState.value)
                return with(if (reachesEdge) freeFling else snapping) {
                    performFling(initialVelocity)
                }
            }
        }
    }
}

@Composable
private fun rememberHomeRowFlingBehavior(listState: LazyListState): FlingBehavior {
    val snapPosition = remember(listState) {
        object : SnapPosition {
            override fun position(
                layoutSize: Int,
                itemSize: Int,
                beforeContentPadding: Int,
                afterContentPadding: Int,
                itemIndex: Int,
                itemCount: Int,
            ): Int = homeRowSnapPosition(
                layoutSize, itemSize, beforeContentPadding, afterContentPadding,
                itemIndex, itemCount, listState.layoutInfo.mainAxisItemSpacing,
            )
        }
    }
    val snapping = rememberSnapFlingBehavior(listState, snapPosition)
    val freeFling = ScrollableDefaults.flingBehavior()
    val decay = rememberSplineBasedDecay<Float>()
    return remember(listState, snapping, freeFling, decay) {
        object : FlingBehavior {
            override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
                val layout = listState.layoutInfo
                val itemSize = layout.visibleItemsInfo.firstOrNull()?.size
                    ?: return with(freeFling) { performFling(initialVelocity) }
                // Each home cover row has uniform widths. Use its measured geometry so
                // rotation, content padding and card spacing share the real scroll bounds.
                val stride = itemSize.toFloat() + layout.mainAxisItemSpacing
                val startDistance = listState.firstVisibleItemIndex * stride +
                    listState.firstVisibleItemScrollOffset
                val contentSize = layout.totalItemsCount * stride - layout.mainAxisItemSpacing +
                    layout.beforeContentPadding + layout.afterContentPadding
                val endDistance = (contentSize - layout.viewportSize.width - startDistance)
                    .coerceAtLeast(0f)
                val target = decay.calculateTargetValue(0f, initialVelocity)
                // Snapping exactly to the first card consumes all velocity. A fling
                // that would cross either edge must instead reach the overscroll effect.
                val reachesEdge = (initialVelocity < 0f && -target >= startDistance) ||
                    (initialVelocity > 0f && target >= endDistance)
                return with(if (reachesEdge) freeFling else snapping) {
                    performFling(initialVelocity)
                }
            }
        }
    }
}

@Composable
fun Title(text: String) {
    Text(
        text = text,
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onBackground,
        maxLines = 1,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = HomeSectionTitlePadding)
    )
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun TripleLaneSlider(
    songsArray: List<HomePageResourceShow.Data.Block.DslData.HomeCommon.Content.Item>,
    isLandscape: Boolean = false,
    onClick: (List<HomePageResourceShow.Data.Block.DslData.HomeCommon.Content.Item>, Int) -> Unit,
) {
    val songs = remember(songsArray) { songsArray.flatMap { it.items } }
    val coverSize = songListCoverSize(isLandscape)
    val trackIds = remember(songs) {
        songs.mapNotNull { it.resourceId.toLongOrNull()?.takeIf { id -> id > 0 } }.toSet()
    }
    val favoriteIds = rememberFavoriteTrackIds(trackIds)
    val playlistViewModel: PlaylistViewModel = hiltViewModel()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var moreJob by remember { mutableStateOf<Job?>(null) }
    var overlay by remember { mutableStateOf<OverlayState>(
        OverlayState.None) }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val spacing = homeCardSpacing(isLandscape)
        val requestedWidth = homePlaylistCardSize(isLandscape) * 2 + spacing
        val columnWidth = if (isLandscape) minOf(requestedWidth,
            (maxWidth - HomeRowHorizontalPadding * 2 - HomeSongNextPagePeek).coerceAtLeast(220.dp))
            else requestedWidth
        ThreeRowSongList(
            itemCount = songs.size,
            columnWidth = columnWidth,
            columnSpacing = spacing,
            contentPadding = PaddingValues(horizontal = HomeRowHorizontalPadding),
            coverSize = coverSize,
            modifier = Modifier.swipeBackExcluded(),
        ) { index ->
            val song = songs[index]
            Box {
                if (song.resourceId in favoriteIds) FavoriteTrackStar(
                    Modifier.align(Alignment.CenterStart).offset(x = (-14).dp)
                        .testTag("HomeSongFavorite:${song.resourceId}"),
                    tint = LocalGlassColors.current.secondaryContent,
                )
                HomeSongListRow(
                    coverUrl = song.coverUrl,
                    title = song.title,
                    subtitle = song.artistName,
                    coverSize = coverSize,
                    onClick = { onClick(songsArray, index) },
                    moreModifier = Modifier.testTag("HomeSongMore:${song.resourceId}"),
                    onMore = { anchor ->
                        moreJob?.cancel()
                        moreJob = scope.launch {
                            try {
                                val track = playlistViewModel.getSongDetails(listOf(song.resourceId))
                                    .songs.firstOrNull { it.id.toString() == song.resourceId }
                                    ?.toMediaMetadata() ?: error("Missing song details")
                                overlay = OverlayState.TrackActionMenu(track, anchor)
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Exception) {
                                Timber.w(error, "Unable to load home song actions")
                                Toast.makeText(context, R.string.load_failed, Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                )
            }
        }
    }
    StandaloneTrackActionOverlay(
        overlay = overlay,
        onDismiss = { overlay = OverlayState.None },
        onUpdateOverlay = { overlay = it },
        playlistViewModel = playlistViewModel,
    )
}
