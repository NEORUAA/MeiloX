package com.ljyh.mei.ui.screen.artist

import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.media3.common.util.UnstableApi
import coil3.compose.AsyncImage
import com.kyant.capsule.ContinuousRoundedRectangle
import com.ljyh.mei.R
import com.ljyh.mei.data.model.api.ArtistDetail
import com.ljyh.mei.data.model.toMediaItem
import com.ljyh.mei.data.model.toMediaMetadata
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.playback.queue.ListQueue
import com.ljyh.mei.ui.component.item.Track
import com.ljyh.mei.ui.component.player.OverlayState
import com.ljyh.mei.ui.component.shimmer.ListItemPlaceHolder
import com.ljyh.mei.ui.component.shimmer.ShimmerHost
import com.ljyh.mei.ui.component.shimmer.TextPlaceholder
import com.ljyh.mei.ui.glass.GlassButton
import com.ljyh.mei.ui.glass.GlassEmphasis
import com.ljyh.mei.ui.glass.IosPinnedPage
import com.ljyh.mei.ui.glass.IosTypography
import com.ljyh.mei.ui.glass.LocalGlassColors
import com.ljyh.mei.ui.glass.SfIcon
import com.ljyh.mei.ui.glass.SfSymbol
import com.ljyh.mei.ui.local.LocalNavController
import com.ljyh.mei.ui.local.LocalPlayerAwareWindowInsets
import com.ljyh.mei.ui.local.LocalPlayerConnection
import com.ljyh.mei.ui.model.Album
import com.ljyh.mei.ui.screen.Screen
import com.ljyh.mei.ui.screen.playlist.component.StandaloneTrackActionOverlay
import java.util.UUID


@OptIn(UnstableApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ArtistScreen(
    id: String,
    viewModel: ArtistViewModel = hiltViewModel()
) {
    val navController = LocalNavController.current
    val playerConnection = LocalPlayerConnection.current ?: return

    val artistDetail by viewModel.artistDetail.collectAsState()
    val artistAlbums by viewModel.artistAlbums.collectAsState()
    val artistSongs by viewModel.artistSongs.collectAsState()
    val followMutation by viewModel.followMutation.collectAsState()
    var isFollowed by remember(id) { mutableStateOf(false) }
    var currentOverlay by remember { mutableStateOf<OverlayState>(OverlayState.None) }
    val artistData = (artistDetail as? Resource.Success)?.data?.data
    val isArtistUnavailable = artistDetail is Resource.Success && artistData?.artist == null

    val scrollState = rememberLazyListState()
    // Hero 高度 320dp，当滚过约 60% 时显示 TopBar 标题
    val heroHeightPx = with(LocalDensity.current) { 320.dp.toPx() }
    val topBarCollapseProgress by remember {
        derivedStateOf {
            if (scrollState.firstVisibleItemIndex > 0) 1f
            else (scrollState.firstVisibleItemScrollOffset / (heroHeightPx * 0.6f))
                .coerceIn(0f, 1f)
        }
    }
    LaunchedEffect(id) {
        viewModel.getArtistDetail(id)
        viewModel.getArtistAlbums(id)
        viewModel.getArtistSongs(id)
    }
    LaunchedEffect(artistDetail) {
        (artistDetail as? Resource.Success)?.data?.data?.user?.followed?.let {
            isFollowed = it
        }
    }
    LaunchedEffect(followMutation) {
        (followMutation as? Resource.Success)?.data?.let { isFollowed = it }
    }

    Box(Modifier.fillMaxSize()) {
        IosPinnedPage(
            title = artistData?.artist?.name.orEmpty(),
            bottomPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateBottomPadding(),
            collapseProgress = topBarCollapseProgress,
            onNavigateBack = navController::popBackStack,
        ) { contentPadding ->
            if (isArtistUnavailable) {
                ArtistUnavailableState(
                    onNavigateBack = navController::popBackStack,
                    modifier = Modifier.fillMaxSize().padding(contentPadding),
                )
            } else {
                LazyColumn(
                    state = scrollState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        bottom = contentPadding.calculateBottomPadding(),
                    ),
                ) {
            // --- 1. Hero + Info Header ---
            item {
                when (val detail = artistDetail) {
                    is Resource.Success -> {
                        val data = detail.data.data
                        val artist = data?.artist
                        if (artist != null) {
                            ArtistHeader(
                                artist = artist,
                                isFollowed = isFollowed,
                                isFollowLoading = followMutation is Resource.Loading,
                                onFollowClick = {
                                    viewModel.setArtistFollowed(artist.id.toLong(), !isFollowed)
                                },
                                expertIdentities = data.secondaryExpertIdentiy.orEmpty()
                                    .filter { it.expertIdentiyCount > 0 }
                            )
                        } else {
                            ErrorItem(detail.data.message ?: "Unable to load artist")
                        }
                    }
                    is Resource.Loading -> ArtistHeaderShimmer()
                    is Resource.Error -> ErrorItem(detail.message)
                }
            }

            // --- 2. Hot Songs ---
            item { SectionTitle("热门单曲") }

            when (val songsResource = artistSongs) {
                is Resource.Success -> {
                    val songs = songsResource.data.hotSongs.orEmpty()
                    items(songs.take(10), key = { it.id }) { song ->
                        Track(
                            track = song.toMediaMetadata(),
                            onClick = {
                                val allIds = songs.map {
                                    it.id.toString() to it.toMediaMetadata().toMediaItem()
                                }
                                playerConnection.onTrackClicked(
                                    trackId = song.id.toString(),
                                    buildQueue = {
                                        ListQueue(
                                            UUID.randomUUID().toString(),
                                            "Hot Songs",
                                            allIds,
                                            songs.indexOf(song)
                                        )
                                    }
                                )
                            },
                            onMoreClick = { currentOverlay = OverlayState.TrackActionMenu(song.toMediaMetadata()) }
                        )
                    }
                }
                is Resource.Loading -> items(5) { ShimmerHost { ListItemPlaceHolder() } }
                is Resource.Error -> item { ErrorItem(songsResource.message) }
            }

            // --- 3. Albums ---
            item { SectionTitle("专辑") }

            when (val albumsResource = artistAlbums) {
                is Resource.Success -> {
                    val albums = albumsResource.data.hotAlbums.orEmpty()
                    item {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(albums, key = { it.id }) { hotAlbum ->
                                AlbumCard(
                                    album = Album(
                                        id = hotAlbum.id.toLong(),
                                        title = hotAlbum.name,
                                        cover = hotAlbum.picUrl,
                                        artist = hotAlbum.artists.map {
                                            Album.Artist(it.id.toLong(), it.name)
                                        },
                                        size = hotAlbum.size
                                    ),
                                    onClick = {
                                        navController.navigate("${Screen.Album.route}/$it")
                                    }
                                )
                            }
                        }
                    }
                }
                is Resource.Loading -> item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(4) { ShimmerHost { AlbumCardShimmer() } }
                    }
                }
                is Resource.Error -> item { ErrorItem(albumsResource.message) }
            }
                }
            }
        }
        StandaloneTrackActionOverlay(
            overlay = currentOverlay,
            onDismiss = { currentOverlay = OverlayState.None },
            onUpdateOverlay = { currentOverlay = it },
        )
    }
}

@Composable
private fun ArtistUnavailableState(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        SfIcon(
            symbol = SfSymbol.PersonFilled,
            contentDescription = null,
            size = 48.dp,
            tint = LocalGlassColors.current.tertiaryContent,
        )
        Text(
            text = stringResource(R.string.artist_unavailable_title),
            style = IosTypography.title2,
            color = LocalGlassColors.current.content,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            text = stringResource(R.string.artist_unavailable_description),
            style = IosTypography.subheadline,
            color = LocalGlassColors.current.secondaryContent,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
        GlassButton(
            onClick = onNavigateBack,
            modifier = Modifier.padding(top = 20.dp),
        ) {
            Text(stringResource(R.string.navigation_back))
        }
    }
}


// ─── Hero + Info Header ───────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ArtistHeader(
    artist: ArtistDetail.Data.Artist,
    expertIdentities: List<ArtistDetail.Data.SecondaryExpertIdentiy>,
    isFollowed: Boolean,
    isFollowLoading: Boolean,
    onFollowClick: () -> Unit,
) {
    var descExpanded by remember { mutableStateOf(false) }
    val bgColor = MaterialTheme.colorScheme.background

    Column(modifier = Modifier.fillMaxWidth()) {

        // ── Hero: cover 图 + 底部渐变收口 ──────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(320.dp)
        ) {
            // 封面图铺满
            AsyncImage(
                model = artist.cover,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )

            // 底部渐变：透明 → bgColor，让 Hero 与 body 无缝衔接
            // 中间留一段纯透明区让封面透出来，底部收口到完全不透明
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colorStops = arrayOf(
                                0.00f to bgColor.copy(alpha = 0.20f), // 顶部轻压
                                0.35f to Color.Transparent,           // 中段封面完全露出
                                0.62f to bgColor.copy(alpha = 0.55f),
                                0.80f to bgColor.copy(alpha = 0.88f),
                                1.00f to bgColor                      // 底部完全不透明
                            )
                        )
                    )
            )

            // Hero 内容：badges + 头像 + 名字 + 副标题 + 统计
            // 贴底部排列，向上延伸
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, bottom = 20.dp)
            ) {
                // 认证/身份 badges
                val allTags = artist.identifyTag ?: (emptyList<String>() + artist.identities)
                if (allTags.isNotEmpty()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(bottom = 10.dp)
                    ) {
                        allTags.take(3).forEach { tag ->
                            HeroBadge(tag)
                        }
                    }
                }

                // 头像 + 名字/副标题横排
                Row(
                    verticalAlignment = Alignment.Bottom,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    AsyncImage(
                        model = artist.avatar,
                        contentDescription = null,
                        modifier = Modifier
                            .size(60.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentScale = ContentScale.Crop
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = artist.name,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val subtitleParts = buildList {
                            if (artist.transNames.isNotEmpty()) addAll(artist.transNames)
                            if (artist.alias.isNotEmpty()) addAll(artist.alias)
                        }
                        if (subtitleParts.isNotEmpty()) {
                            Text(
                                text = subtitleParts.joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.48f),
                                modifier = Modifier.padding(top = 2.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 统计数字行
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    StatItem(value = artist.musicSize.formatCount(), label = "单曲")
                    StatItem(value = artist.albumSize.toString(), label = "专辑")
                    StatItem(value = artist.mvSize.toString(), label = "MV")
                }
            }
        }

        // ── Body: 关注 + 创作领域 + 简介 ─────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
        ) {
            // 关注按钮
            GlassButton(
                onClick = onFollowClick,
                enabled = !isFollowLoading,
                emphasis = if (isFollowed) GlassEmphasis.Prominent else GlassEmphasis.Regular,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp, bottom = 16.dp)
            ) {
                Text(
                    stringResource(
                        if (isFollowed) R.string.artist_following else R.string.artist_follow,
                    ),
                    style = MaterialTheme.typography.labelLarge,
                )
            }

            // 创作领域 chips
            if (expertIdentities.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(bottom = 14.dp)
                ) {
                    expertIdentities.forEach { expert ->
                        ExpertiseChip(
                            name = expert.expertIdentiyName,
                            count = expert.expertIdentiyCount
                        )
                    }
                }
            }

            // 简介
            if (artist.briefDesc.isNotBlank()) {
                Text(
                    text = artist.briefDesc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                    lineHeight = 20.sp,
                    maxLines = if (descExpanded) Int.MAX_VALUE else 3,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (descExpanded) "收起" else "展开全部",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(top = 4.dp, bottom = 8.dp)
                        .clickable { descExpanded = !descExpanded }
                )
            }
        }
    }
}


// ─── Hero Badge ───────────────────────────────────────────────────────────────

@Composable
private fun HeroBadge(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f),
        modifier = Modifier
            .background(
                color = MaterialTheme.colorScheme.background.copy(alpha = 0.45f),
                shape = ContinuousRoundedRectangle(20.dp)
            )
            .padding(horizontal = 10.dp, vertical = 4.dp)
    )
}


// ─── Stat Item ────────────────────────────────────────────────────────────────

@Composable
private fun StatItem(value: String, label: String) {
    Column {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.42f),
            modifier = Modifier.padding(top = 1.dp)
        )
    }
}


// ─── Expertise Chip ───────────────────────────────────────────────────────────

@Composable
private fun ExpertiseChip(name: String, count: Int) {
    Row(
        modifier = Modifier
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = ContinuousRoundedRectangle(6.dp)
            )
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = count.formatCount(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
        )
    }
}


// ─── Album Card ───────────────────────────────────────────────────────────────

@Composable
fun AlbumCard(album: Album, onClick: (Long) -> Unit) {
    Column(
        modifier = Modifier
            .width(116.dp)
            .clickable { onClick(album.id) }
    ) {
        AsyncImage(
            model = album.cover,
            contentDescription = album.title,
            modifier = Modifier
                .size(116.dp)
                .clip(ContinuousRoundedRectangle(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentScale = ContentScale.Crop
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = album.title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = "${album.size} 首",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.42f),
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

@Composable
fun AlbumCardShimmer() {
    Column(modifier = Modifier.width(116.dp)) {
        Spacer(
            modifier = Modifier
                .size(116.dp)
                .clip(ContinuousRoundedRectangle(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        )
        Spacer(modifier = Modifier.height(6.dp))
        TextPlaceholder(Modifier.width(96.dp).height(14.dp))
        Spacer(modifier = Modifier.height(4.dp))
        TextPlaceholder(Modifier.width(56.dp).height(12.dp))
    }
}


// ─── Header Shimmer ───────────────────────────────────────────────────────────

@Composable
fun ArtistHeaderShimmer() {
    ShimmerHost {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Hero 占位
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(320.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f)),
                contentAlignment = Alignment.BottomStart
            ) {
                Column(
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 20.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.Bottom,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Spacer(
                            modifier = Modifier
                                .size(60.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Column {
                            TextPlaceholder(Modifier.width(160.dp).height(22.dp))
                            Spacer(modifier = Modifier.height(6.dp))
                            TextPlaceholder(Modifier.width(100.dp).height(13.dp))
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        repeat(3) {
                            Column {
                                TextPlaceholder(Modifier.width(36.dp).height(18.dp))
                                Spacer(modifier = Modifier.height(4.dp))
                                TextPlaceholder(Modifier.width(24.dp).height(11.dp))
                            }
                        }
                    }
                }
            }
            // Body 占位
            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                Spacer(
                    modifier = Modifier
                        .padding(vertical = 16.dp)
                        .fillMaxWidth()
                        .height(40.dp)
                        .clip(ContinuousRoundedRectangle(50))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                )
                TextPlaceholder(Modifier.fillMaxWidth().height(13.dp))
                Spacer(modifier = Modifier.height(6.dp))
                TextPlaceholder(Modifier.fillMaxWidth(0.82f).height(13.dp))
                Spacer(modifier = Modifier.height(6.dp))
                TextPlaceholder(Modifier.fillMaxWidth(0.6f).height(13.dp))
            }
        }
    }
}


// ─── Shared ───────────────────────────────────────────────────────────────────

@Composable
fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 4.dp)
    )
}

@Composable
fun ErrorItem(message: String) {
    Text(
        text = message,
        modifier = Modifier.padding(20.dp),
        color = MaterialTheme.colorScheme.error
    )
}

private fun Int.formatCount(): String = when {
    this >= 100_000_000 -> "${this / 100_000_000}亿"
    this >= 10_000 -> "${this / 10_000}万"
    else -> this.toString()
}
