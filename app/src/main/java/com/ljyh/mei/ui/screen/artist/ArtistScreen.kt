package com.ljyh.mei.ui.screen.artist

import io.github.neoruaa.glasmio.core.swipeBackExcluded
import androidx.compose.material3.IconButton
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import com.ljyh.mei.ui.component.item.ThreeRowSongList
import com.ljyh.mei.constants.HomeSongRowSpacing
import com.ljyh.mei.ui.component.item.HomeSongListRow
import com.ljyh.mei.constants.HomeSongActionSize
import com.ljyh.mei.constants.homePlaylistCardSize
import com.ljyh.mei.constants.homeCardSpacing
import com.ljyh.mei.constants.HomeSongNextPagePeek
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.api.ArtistAlbum
import java.text.DateFormat
import java.util.Date
import androidx.annotation.OptIn
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.nativePaint
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.media3.common.util.UnstableApi
import coil3.compose.AsyncImage
import io.github.neoruaa.glasmio.glass.render.backdrops.rememberCanvasBackdrop
import com.kyant.capsule.ContinuousRoundedRectangle
import com.kyant.shapes.Capsule
import com.ljyh.mei.R
import com.ljyh.mei.constants.DetailPageHorizontalPadding
import com.ljyh.mei.data.model.api.ArtistDetail
import com.ljyh.mei.data.model.toMediaItem
import com.ljyh.mei.data.model.toMediaMetadata
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.playback.queue.ListQueue
import com.ljyh.mei.ui.component.item.Track
import com.ljyh.mei.ui.component.player.OverlayState
import com.ljyh.mei.ui.component.playlist.CoverBackground
import com.ljyh.mei.ui.component.playlist.FavoriteTrackStar
import com.ljyh.mei.ui.component.playlist.rememberCoverBackground
import com.ljyh.mei.ui.component.playlist.rememberFavoriteTrackIds
import com.ljyh.mei.ui.component.shimmer.ListItemPlaceHolder
import com.ljyh.mei.ui.component.shimmer.ShimmerHost
import com.ljyh.mei.ui.component.shimmer.TextPlaceholder
import com.ljyh.mei.ui.component.utils.rememberDeviceInfo
import com.ljyh.mei.ui.local.LocalColorfulPageBackgrounds
import com.ljyh.mei.ui.local.LocalNavController
import com.ljyh.mei.ui.local.LocalPageAppearance
import com.ljyh.mei.ui.local.LocalPlayerAwareWindowInsets
import com.ljyh.mei.ui.local.LocalPlayerConnection
import com.ljyh.mei.ui.model.Album
import com.ljyh.mei.ui.model.toAlbum
import com.ljyh.mei.ui.navigation.MeiloXPinnedPage
import com.ljyh.mei.ui.screen.Screen
import com.ljyh.mei.ui.screen.playlist.component.StandaloneTrackActionOverlay
import com.ljyh.mei.ui.theme.LocalAccentSeedColor
import com.ljyh.mei.ui.theme.MusicTheme
import com.ljyh.mei.utils.color.ColorExtractionUtils
import io.github.neoruaa.glasmio.core.IosPageHorizontalPadding
import io.github.neoruaa.glasmio.core.IosTypography
import io.github.neoruaa.glasmio.core.LocalGlassColors
import io.github.neoruaa.glasmio.core.defaultGlassColors
import io.github.neoruaa.glasmio.glass.LocalGlassBackdrop
import io.github.neoruaa.glasmio.icons.SfIcon
import io.github.neoruaa.glasmio.icons.SfSymbol
import io.github.neoruaa.glasmio.ui.GlasmioButton
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import java.util.UUID
import kotlinx.coroutines.CancellationException

private val ArtistSongCoverSize = 54.dp
private val ArtistPopularSongsHeight = ArtistSongCoverSize * 3 + HomeSongRowSpacing * 2

@OptIn(UnstableApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ArtistScreen(id: String, viewModel: ArtistViewModel = hiltViewModel(key = "artist:$id")) {
    val navController = LocalNavController.current
    val playerConnection = LocalPlayerConnection.current

    val artistDetail by viewModel.artistDetail.collectAsState()
    val artistAlbums by viewModel.artistAlbums.collectAsState()
    // Lazy content and its derived tracks must capture the same response snapshot.
    val artistSongs = viewModel.artistSongs.collectAsState().value
    val followMutation by viewModel.followMutation.collectAsState()
    val confirmedFollowed by viewModel.confirmedFollowed.collectAsState()
    val isFollowed = resolveArtistFollowed(id, artistSongs, artistAlbums, confirmedFollowed)
    var currentOverlay by remember(id) { mutableStateOf<OverlayState>(OverlayState.None) }
    val onAllSongsClick = { navController.navigate("${Screen.ArtistSongs.route}/$id") }
    val onAllAlbumsClick = { navController.navigate("${Screen.ArtistAlbums.route}/$id") }
    val onInfoClick = { navController.navigate("${Screen.ArtistInfo.route}/$id") }
    val artistData = (artistDetail as? Resource.Success)?.data?.data
    val isArtistUnavailable = artistDetail is Resource.Success && artistData?.artist == null
    val heroCover =
        artistData
            ?.artist
            ?.let { artist -> artist.cover.takeIf(String::isNotBlank) ?: artist.avatar }
            .orEmpty()
    val hotSongs = (artistSongs as? Resource.Success)?.data?.hotSongs.orEmpty()
    val hotTracks = remember(hotSongs) { hotSongs.map { it.toMediaMetadata() } }
    val favoriteTrackIds = rememberFavoriteTrackIds(hotTracks)
    val currentTrackId = playerConnection?.mediaMetadata?.collectAsState()?.value?.id
    val currentlyPlaying = playerConnection?.isPlaying?.collectAsState()?.value == true

    val scrollState = rememberLazyListState()
    val landscape = rememberDeviceInfo().isLandscape
    var popularSongsHeightPx by remember { mutableStateOf(0) }
    LaunchedEffect(id) { viewModel.loadArtistIfNeeded(id) }

    ArtistCoverTheme(
        route = "${Screen.Artist.route}/$id",
        coverUrl = heroCover,
        viewModel = viewModel,
    ) { background ->
        val separatorColor = LocalGlassColors.current.content.copy(alpha = 0.16f)
        val headerAccent =
            remember(background.seedColor, background.color, background.endColor) {
                ColorExtractionUtils.detailAccentColor(
                    // Neutral artwork or unavailable extraction must not inherit another cover's
                    // accent.
                    seedColor = background.seedColor ?: Color.Gray,
                    backgroundColor = background.color,
                    backgroundEndColor = background.endColor,
                )
            }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // The square hero follows the actual page width, including split-screen resizing.
            val heroHeightPx =
                with(LocalDensity.current) { if (landscape) 400.dp.toPx() else maxWidth.toPx() }
            val topBarCollapseProgress by
                remember(scrollState, heroHeightPx) {
                    derivedStateOf {
                        if (scrollState.firstVisibleItemIndex > 0) 1f
                        else
                            (scrollState.firstVisibleItemScrollOffset /
                                    (heroHeightPx * 0.6f).coerceAtLeast(1f))
                                .coerceIn(0f, 1f)
                    }
                }
            val blurDistancePx = with(LocalDensity.current) { 56.dp.toPx() }
            val topBarBlurProgress by
                remember(scrollState, blurDistancePx) {
                    derivedStateOf {
                        if (scrollState.firstVisibleItemIndex > 0) 1f
                        else
                            (scrollState.firstVisibleItemScrollOffset / blurDistancePx).coerceIn(
                                0f,
                                1f,
                            )
                    }
                }
            val latestArtworkHeight =
                with(LocalDensity.current) { popularSongsHeightPx.toDp() }.takeIf { it > 0.dp }
                    ?: ArtistPopularSongsHeight
            val latestPaneWidth =
                maxWidth * 0.31f
            val songSection: @Composable () -> Unit = {
                Column {
                    // --- 2. Hot Songs ---
                    SectionTitle(stringResource(R.string.artist_hot_songs))

                    when (val songsResource = artistSongs) {
                        is Resource.Success -> {
                            val songs = songsResource.data.hotSongs.orEmpty()
                            val visibleSongs = songs.take(10)
                            visibleSongs.forEachIndexed { index, song ->
                                val track = hotTracks[index]
                                Column(
                                    modifier =
                                        Modifier.padding(
                                            horizontal =
                                                DetailPageHorizontalPadding -
                                                    IosPageHorizontalPadding
                                        )
                                ) {
                                    Box {
                                        Track(
                                            track = track,
                                            detailInkBlend = true,
                                            onClick = {
                                                val allIds =
                                                    songs.map {
                                                        it.id.toString() to
                                                            it.toMediaMetadata().toMediaItem()
                                                    }
                                                playerConnection?.onTrackClicked(
                                                    trackId = song.id.toString(),
                                                    buildQueue = {
                                                        ListQueue(
                                                            UUID.randomUUID().toString(),
                                                            "Hot Songs",
                                                            allIds,
                                                            songs.indexOf(song),
                                                        )
                                                    },
                                                )
                                            },
                                            onMoreClick = {
                                                currentOverlay =
                                                    OverlayState.TrackActionMenu(track, it)
                                            },
                                        )
                                        if (
                                            track.id > 0 &&
                                                !track.isLocal &&
                                                !track.isPodcast &&
                                                track.id.toString() in favoriteTrackIds
                                        ) {
                                            FavoriteTrackStar(
                                                modifier =
                                                    Modifier.align(Alignment.CenterStart)
                                                        .offset(
                                                            x =
                                                                IosPageHorizontalPadding -
                                                                    DetailPageHorizontalPadding /
                                                                        2f -
                                                                    6.dp
                                                        ),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                    if (index < visibleSongs.lastIndex) {
                                        HorizontalDivider(
                                            modifier =
                                                Modifier.padding(
                                                        start = IosPageHorizontalPadding + 64.dp,
                                                        end = IosPageHorizontalPadding,
                                                    )
                                                    .graphicsLayer {
                                                        blendMode = BlendMode.Luminosity
                                                        compositingStrategy =
                                                            CompositingStrategy.Offscreen
                                                    },
                                            thickness = 0.5.dp,
                                            color = separatorColor,
                                        )
                                    }
                                }
                            }
                        }
                        is Resource.Loading ->
                            repeat(5) {
                                ShimmerHost {
                                    ListItemPlaceHolder(
                                        modifier =
                                            Modifier.padding(
                                                    horizontal = DetailPageHorizontalPadding - 12.dp
                                                )
                                                .then(
                                                    if (landscape) Modifier.height(64.dp)
                                                    else Modifier
                                                )
                                    )
                                }
                            }
                        is Resource.Error -> ErrorItem(songsResource.message)
                    }

                    val songCount =
                        artistData?.artist?.musicSize
                            ?: (artistSongs as? Resource.Success)?.data?.artist?.musicSize
                    if (songCount != null) {

                        GlasmioListRow(
                            title = stringResource(R.string.artist_all_songs_count, songCount),
                            modifier =
                                Modifier.padding(
                                        horizontal =
                                            DetailPageHorizontalPadding - IosPageHorizontalPadding
                                    )
                                    .drawBehind {
                                        drawLine(
                                            color = separatorColor,
                                            start = Offset(IosPageHorizontalPadding.toPx(), 0f),
                                            end =
                                                Offset(
                                                    size.width - IosPageHorizontalPadding.toPx(),
                                                    0f,
                                                ),
                                            strokeWidth = 0.5.dp.toPx(),
                                            blendMode = BlendMode.Luminosity,
                                        )
                                    },
                            showTopSeparator = false,
                            onClick = onAllSongsClick,
                            trailing = {
                                SfIcon(
                                    "chevron.forward",
                                    null,
                                    modifier = Modifier.padding(start = 8.dp),
                                    size = 12.dp,
                                    tint = LocalGlassColors.current.secondaryContent,
                                )
                            },
                        )
                    }
                }
            }
            val albumSection: @Composable () -> Unit = {
                Column {
                    // --- 3. Albums ---
                    SectionTitle(
                        stringResource(R.string.artist_albums),
                        onClick = onAllAlbumsClick.takeIf { landscape },
                        landscape = landscape,
                    )

                    when (val albumsResource = artistAlbums) {
                        is Resource.Success -> {
                            val albums = albumsResource.data.hotAlbums.orEmpty()

                            LazyRow(
                                modifier = Modifier.swipeBackExcluded(),
                                contentPadding =
                                    PaddingValues(horizontal = DetailPageHorizontalPadding),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                items(albums, key = { it.id }) { hotAlbum ->
                                    AlbumCard(
                                        album = hotAlbum.toAlbum(),
                                        artworkSize =
                                            if (landscape) homePlaylistCardSize(true) else 116.dp,
                                        onClick = {
                                            navController.navigate("${Screen.Album.route}/$it")
                                        },
                                    )
                                }
                            }
                        }
                        is Resource.Loading -> {
                            LazyRow(
                                modifier = Modifier.swipeBackExcluded(),
                                contentPadding =
                                    PaddingValues(horizontal = DetailPageHorizontalPadding),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                items(4) {
                                    ShimmerHost {
                                        AlbumCardShimmer(
                                            artworkSize =
                                                if (landscape) homePlaylistCardSize(true)
                                                else 116.dp
                                        )
                                    }
                                }
                            }
                        }
                        is Resource.Error -> ErrorItem(albumsResource.message)
                    }

                    val albumCount =
                        artistData?.artist?.albumSize
                            ?: (artistAlbums as? Resource.Success)?.data?.artist?.albumSize
                    if (albumCount != null && !landscape) {

                        GlasmioListRow(
                            title = stringResource(R.string.artist_all_albums_count, albumCount),
                            modifier =
                                Modifier.padding(
                                        horizontal =
                                            DetailPageHorizontalPadding - IosPageHorizontalPadding
                                    )
                                    .padding(top = 10.dp)
                                    .drawBehind {
                                        drawLine(
                                            color = separatorColor,
                                            start = Offset(IosPageHorizontalPadding.toPx(), 0f),
                                            end =
                                                Offset(
                                                    size.width - IosPageHorizontalPadding.toPx(),
                                                    0f,
                                                ),
                                            strokeWidth = 0.5.dp.toPx(),
                                            blendMode = BlendMode.Luminosity,
                                        )
                                    },
                            showTopSeparator = false,
                            onClick = onAllAlbumsClick,
                            trailing = {
                                SfIcon(
                                    "chevron.forward",
                                    null,
                                    modifier = Modifier.padding(start = 8.dp),
                                    size = 12.dp,
                                    tint = LocalGlassColors.current.secondaryContent,
                                )
                            },
                        )
                    }
                }
            }
            val latestRelease: @Composable () -> Unit = {
                Column {
                    SectionTitle(
                        stringResource(R.string.artist_latest_release),
                        bottomPadding = 12.dp,
                        landscape = true,
                    )
                    when (val resource = artistAlbums) {
                        is Resource.Success -> {
                            resource.data.hotAlbums
                                .orEmpty()
                                .maxByOrNull { it.publishTime }
                                ?.let { latest ->
                                    ArtistFeaturedAlbum(
                                        latest,
                                        artworkHeight = latestArtworkHeight,
                                        onClick = {
                                            navController.navigate("${Screen.Album.route}/$it")
                                        },
                                    )
                                }
                        }
                        is Resource.Loading ->
                            ShimmerHost { ArtistFeaturedAlbumPlaceholder(latestArtworkHeight) }
                        is Resource.Error -> ErrorItem(resource.message)
                    }
                }
            }
            val popularSongs: @Composable () -> Unit = {
                Column {
                    SectionTitle(
                        stringResource(R.string.artist_hot_songs),
                        onClick = onAllSongsClick,
                        landscape = true,
                    )
                    when (val resource = artistSongs) {
                        is Resource.Success ->
                            ArtistPopularSongs(
                                tracks = hotTracks.take(10),
                                favoriteIds = favoriteTrackIds,
                                currentTrackId = currentTrackId,
                                playing = currentlyPlaying,
                                onHeightChanged = { popularSongsHeightPx = it },
                                onClick = { index ->
                                    playerConnection?.onTrackClicked(
                                        trackId = hotTracks[index].id.toString(),
                                        buildQueue = {
                                            ListQueue(
                                                UUID.randomUUID().toString(),
                                                "Hot Songs",
                                                hotTracks.map {
                                                    it.id.toString() to it.toMediaItem()
                                                },
                                                index,
                                            )
                                        },
                                    )
                                },
                                onMore = { track, anchor ->
                                    currentOverlay = OverlayState.TrackActionMenu(track, anchor)
                                },
                            )
                        is Resource.Loading ->
                            ShimmerHost {
                                Column(Modifier.padding(horizontal = DetailPageHorizontalPadding)) {
                                    repeat(3) { index ->
                                        ListItemPlaceHolder(Modifier.height(ArtistSongCoverSize))
                                        if (index < 2) Spacer(Modifier.height(8.dp))
                                    }
                                }
                            }
                        is Resource.Error -> ErrorItem(resource.message)
                    }
                }
            }
            MeiloXPinnedPage(
                title = artistData?.artist?.name.orEmpty(),
                bottomPadding =
                    LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateBottomPadding(),
                collapseProgress = topBarCollapseProgress,
                topBarBlurProgress = topBarBlurProgress,
                onNavigateBack = navController::popBackStack,
                backgroundColor = background.color,
                backgroundBrush = background.brush,
            ) { contentPadding ->
                if (isArtistUnavailable) {
                    ArtistUnavailableState(
                        onNavigateBack = navController::popBackStack,
                        modifier =
                            Modifier.fillMaxSize()
                                .padding(
                                    top = contentPadding.calculateTopPadding(),
                                    bottom = contentPadding.calculateBottomPadding(),
                                ),
                    )
                } else {
                    LazyColumn(
                        state = scrollState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding =
                            PaddingValues(
                                top = if (landscape) contentPadding.calculateTopPadding() else 0.dp,
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
                                            cover =
                                                if (landscape) artist.avatar.ifBlank { heroCover }
                                                else heroCover,
                                            landscape = landscape,
                                            accent = headerAccent,
                                            canPlayHotSongs =
                                                playerConnection != null && hotSongs.isNotEmpty(),
                                            onInfoClick = onInfoClick,
                                            onPlayHotSongs = {
                                                if (hotSongs.isNotEmpty()) {
                                                    playerConnection?.playQueue(
                                                        ListQueue(
                                                            UUID.randomUUID().toString(),
                                                            artist.name,
                                                            hotSongs.map { song ->
                                                                song.id.toString() to
                                                                    song
                                                                        .toMediaMetadata()
                                                                        .toMediaItem()
                                                            },
                                                            0,
                                                        ),
                                                        shuffle = false,
                                                    )
                                                }
                                            },
                                            isFollowed = isFollowed == true,
                                            isFollowLoading =
                                                isFollowed == null ||
                                                    followMutation is Resource.Loading,
                                            onFollowClick = {
                                                isFollowed?.let { followed ->
                                                    viewModel.setArtistFollowed(
                                                        artist.id.toLong(),
                                                        !followed,
                                                    )
                                                }
                                            },
                                        )
                                    } else {
                                        ErrorItem(detail.data.message ?: "Unable to load artist")
                                    }
                                }
                                is Resource.Loading -> ArtistHeaderShimmer(landscape = landscape)
                                is Resource.Error -> {
                                    Box(
                                        Modifier.padding(top = contentPadding.calculateTopPadding())
                                    ) {
                                        ErrorItem(detail.message)
                                    }
                                }
                            }
                        }

                        if (landscape) {
                            item(key = "artist-landscape-sections") {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                                    Column(Modifier.width(latestPaneWidth)) { latestRelease() }
                                    Column(Modifier.weight(1f)) { popularSongs() }
                                }
                            }
                            item(key = "artist-albums") {
                                Column(Modifier.padding(top = 24.dp)) { albumSection() }
                            }
                        } else {
                            item(key = "artist-songs") { songSection() }
                            item(key = "artist-albums") { albumSection() }
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
}

@Composable
private fun ArtistCoverTheme(
    route: String,
    coverUrl: String,
    viewModel: ArtistViewModel,
    content: @Composable (CoverBackground) -> Unit,
) {
    val colorfulBackgrounds = LocalColorfulPageBackgrounds.current
    var coverIsDark by remember(viewModel, coverUrl) {
        mutableStateOf(viewModel.getCachedCoverIsDark(coverUrl))
    }
    LaunchedEffect(viewModel, coverUrl, colorfulBackgrounds) {
        if (!colorfulBackgrounds || coverUrl.isBlank()) return@LaunchedEffect
        try {
            viewModel.getCoverIsDarkOrExtract(coverUrl)?.let { coverIsDark = it }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Keep the app appearance while artwork brightness is unavailable.
        }
    }

    val isDark = (if (colorfulBackgrounds) coverIsDark else null) ?: LocalGlassColors.current.isDark
    val setPageAppearance = LocalPageAppearance.current
    val appearanceOwner = remember(viewModel, route) { Any() }
    DisposableEffect(route, appearanceOwner, isDark, setPageAppearance) {
        setPageAppearance(route, appearanceOwner, isDark)
        onDispose { setPageAppearance(route, appearanceOwner, null) }
    }

    val accentSeed = LocalAccentSeedColor.current ?: defaultGlassColors(isDark).accent
    MusicTheme(seedColor = accentSeed, isDark = isDark) {
        val colors = defaultGlassColors(isDark, MaterialTheme.colorScheme.primary)
        CompositionLocalProvider(LocalGlassColors provides colors) {
            val background = rememberCoverBackground(
                coverUrl = coverUrl,
                getCachedColor = viewModel::getCachedColor,
                getOrExtractColor = viewModel::getOrExtractColor,
                ownerKey = viewModel,
            )
            // Body controls use the cover-local surface. Menus inherit the rendered page backdrop.
            val backdropBrush = rememberUpdatedState(background.brush)
            val backdropPaint = remember { Paint() }
            val backdrop = rememberCanvasBackdrop {
                backdropBrush.value.applyTo(size, backdropPaint, 1f)
                // Fill the blur outsets too, including negative coordinates at the top and left.
                drawContext.canvas.nativeCanvas.drawPaint(backdropPaint.nativePaint)
            }
            CompositionLocalProvider(
                LocalGlassBackdrop provides backdrop,
            ) {
                content(background)
            }
        }
    }
}

@Composable
private fun ArtistUnavailableState(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = DetailPageHorizontalPadding),
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
        GlasmioButton(
            onClick = onNavigateBack,
            modifier = Modifier.padding(top = 20.dp),
        ) {
            Text(stringResource(R.string.navigation_back))
        }
    }
}

// ─── Artist Hero ─────────────────────────────────────────────────────────────

@Composable
private fun ArtistHeader(
    artist: ArtistDetail.Data.Artist,
    cover: String,
    accent: Color,
    canPlayHotSongs: Boolean,
    isFollowed: Boolean,
    isFollowLoading: Boolean,
    onInfoClick: () -> Unit,
    onPlayHotSongs: () -> Unit,
    onFollowClick: () -> Unit,
    landscape: Boolean = false,
) {
    val colors = LocalGlassColors.current
    val subtitle =
        remember(artist) {
            (artist.transNames + artist.alias.filterIsInstance<String>())
                .map(String::trim)
                .filter { it.isNotEmpty() && !it.equals(artist.name, ignoreCase = true) }
                .distinct()
                .joinToString(" · ")
        }
    val playHotSongsLabel = stringResource(R.string.artist_play_hot_songs)

    ArtistHeroLayout(
        landscape = landscape,
        image = { imageModifier ->
            AsyncImage(
                model = cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier =
                    if (landscape) imageModifier
                    else
                        imageModifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                            .drawWithContent {
                                drawContent()
                                // Reveal the same page gradient beneath the image to avoid a solid
                                // seam.
                                drawRect(
                                    brush =
                                        Brush.verticalGradient(
                                            0f to Color.Black,
                                            0.45f to Color.Black,
                                            0.70f to Color.Black.copy(alpha = 0.45f),
                                            1f to Color.Transparent,
                                        ),
                                    blendMode = BlendMode.DstIn,
                                )
                            },
            )
        },
        information = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = artist.name,
                    style = if (landscape) IosTypography.title2.copy(fontSize = 36.sp, lineHeight = 43.sp) else IosTypography.title2,
                    color = colors.content,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier =
                        Modifier.padding(horizontal = DetailPageHorizontalPadding)
                            .padding(top = 8.dp),
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        style = IosTypography.subheadline,
                        color = colors.secondaryContent,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier =
                            Modifier.padding(horizontal = DetailPageHorizontalPadding)
                                .padding(top = 7.dp),
                    )
                }

                Row(
                    modifier = Modifier.padding(top = 18.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ArtistActionButton(onClick = onInfoClick, accent = accent, landscape = landscape) {
                        SfIcon(
                            "info",
                            stringResource(R.string.artist_info_title),
                            size = if (landscape) 28.dp else 24.dp,
                            weight = FontWeight.SemiBold,
                            tint = accent,
                        )
                    }
                    ArtistActionButton(
                        onClick = onPlayHotSongs,
                        modifier = Modifier.semantics { contentDescription = playHotSongsLabel },
                        accent = accent,
                        enabled = canPlayHotSongs,
                        prominent = true,
                        landscape = landscape,
                    ) {
                        SfIcon(
                            "play.fill",
                            null,
                            modifier =
                                Modifier.graphicsLayer {
                                    translationX = 2.dp.toPx()
                                    blendMode = BlendMode.DstOut
                                    compositingStrategy = CompositingStrategy.Offscreen
                                },
                            size = if (landscape) 36.dp else 28.dp,
                            tint = Color.Black,
                        )
                    }
                    ArtistActionButton(
                        onClick = onFollowClick,
                        accent = accent,
                        enabled = !isFollowLoading,
                        landscape = landscape,
                    ) {
                        SfIcon(
                            if (isFollowed) "star.fill" else "star",
                            stringResource(
                                if (isFollowed) R.string.artist_following
                                else R.string.artist_follow
                            ),
                            size = if (landscape) 28.dp else 24.dp,
                            weight = FontWeight.SemiBold,
                            tint = accent,
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun ArtistActionButton(
    onClick: () -> Unit,
    accent: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    prominent: Boolean = false,
    landscape: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors = LocalGlassColors.current
    Box(
        modifier = modifier
            .size(if (landscape) { if (prominent) 80.dp else 54.dp } else { if (prominent) 64.dp else 44.dp })
            .graphicsLayer {
                blendMode = BlendMode.Luminosity
                compositingStrategy = CompositingStrategy.Offscreen
                alpha = if (enabled) 1f else 0.4f
            }
            .clip(Capsule())
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (prominent) {
            Box(Modifier.fillMaxSize().background(accent))
        } else {
            Box(Modifier.fillMaxSize().background(colors.content.copy(alpha = 0.10f)))
        }
        content()
    }
}

// ─── Album Card ───────────────────────────────────────────────────────────────

@Composable
fun AlbumCard(album: Album, artworkSize: Dp = 116.dp, onClick: (Long) -> Unit) {
    Column(modifier = Modifier.width(artworkSize).clickable { onClick(album.id) }) {
        AsyncImage(
            model = album.cover,
            contentDescription = album.title,
            modifier =
                Modifier.size(artworkSize)
                    .clip(ContinuousRoundedRectangle(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            contentScale = ContentScale.Crop,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = album.title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = "${album.size} 首",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.42f),
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
fun AlbumCardShimmer(artworkSize: Dp = 116.dp) {
    Column(modifier = Modifier.width(artworkSize)) {
        Spacer(
            modifier =
                Modifier.size(artworkSize)
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
fun ArtistHeaderShimmer(landscape: Boolean = false) {
    ShimmerHost {
        ArtistHeroLayout(
            landscape = landscape,
            image = { imageModifier ->
                Box(
                    imageModifier
                        .then(if (landscape) Modifier else Modifier.fillMaxWidth().aspectRatio(1f))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f))
                )
            },
            information = {
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    TextPlaceholder(
                        Modifier.padding(top = 8.dp)
                            .width(180.dp)
                            .height(
                                if (landscape)
                                    with(LocalDensity.current) {
                                        43.sp.toDp()
                                    }
                                else 22.dp
                            ),
                        verticalPadding = if (landscape) 0.dp else 4.dp,
                    )
                    TextPlaceholder(
                        Modifier.padding(top = 7.dp)
                            .width(120.dp)
                            .height(
                                if (landscape)
                                    with(LocalDensity.current) {
                                        IosTypography.subheadline.lineHeight.toDp()
                                    }
                                else 16.dp
                            ),
                        verticalPadding = if (landscape) 0.dp else 4.dp,
                    )
                    Row(
                        Modifier.padding(top = 18.dp, bottom = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        (if (landscape) listOf(54.dp, 80.dp, 54.dp) else listOf(44.dp, 64.dp, 44.dp)).forEach { size ->
                            Spacer(
                                Modifier.size(size)
                                    .clip(Capsule())
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                            )
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun ArtistHeroLayout(
    landscape: Boolean,
    image: @Composable (Modifier) -> Unit,
    information: @Composable () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val avatarSize = minOf(maxWidth * 0.17f, 216.dp)
        if (landscape) {
            Column(
                Modifier.fillMaxWidth().padding(top = 40.dp, bottom = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                image(
                    Modifier.size(avatarSize)
                        .dropShadow(
                            shape = CircleShape,
                            shadow = Shadow(
                                radius = 18.dp,
                                color = Color.Black.copy(alpha = 0.18f),
                                offset = DpOffset(0.dp, 12.dp),
                            ),
                        )
                        .clip(CircleShape)
                )
                Spacer(Modifier.height(32.dp))
                information()
            }
        } else {
            image(Modifier)
            Box(Modifier.fillMaxWidth().padding(top = (maxWidth - 64.dp).coerceAtLeast(0.dp))) {
                information()
            }
        }
    }
}

@Composable
private fun ArtistFeaturedAlbum(
    album: ArtistAlbum.HotAlbum,
    artworkHeight: Dp = ArtistPopularSongsHeight,
    onClick: (Long) -> Unit,
) {
    val releaseDate =
        remember(album.publishTime) {
            DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(album.publishTime))
        }
    Row(
        Modifier.fillMaxWidth()
            .padding(horizontal = DetailPageHorizontalPadding)
            .height(artworkHeight)
            .clickable { onClick(album.id.toLong()) },
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = album.picUrl,
            contentDescription = album.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(artworkHeight).clip(ContinuousRoundedRectangle(8.dp)),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                releaseDate,
                style = IosTypography.caption,
                color = LocalGlassColors.current.secondaryContent,
            )
            Text(
                album.name,
                style = IosTypography.headline,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(R.string.playlist_song_count, album.size),
                style = IosTypography.caption,
                color = LocalGlassColors.current.secondaryContent,
            )
        }
    }
}

@Composable
private fun ArtistFeaturedAlbumPlaceholder(artworkHeight: Dp = ArtistPopularSongsHeight) {
    Row(
        Modifier.fillMaxWidth()
            .padding(horizontal = DetailPageHorizontalPadding)
            .height(artworkHeight),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(
            Modifier.size(artworkHeight)
                .clip(ContinuousRoundedRectangle(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        )
        Column(Modifier.weight(1f)) {
            TextPlaceholder()
            TextPlaceholder()
        }
    }
}

@Composable
internal fun ArtistPopularSongs(
    tracks: List<MediaMetadata>,
    favoriteIds: Set<String>,
    currentTrackId: Long?,
    playing: Boolean,
    onClick: (Int) -> Unit,
    onMore: (MediaMetadata, Rect) -> Unit,
    onHeightChanged: (Int) -> Unit = {},
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val spacing = homeCardSpacing(true)
        val columnWidth =
            minOf(
                homePlaylistCardSize(true) * 2 + spacing,
                (maxWidth - DetailPageHorizontalPadding * 2 - HomeSongNextPagePeek).coerceAtLeast(
                    220.dp
                ),
            )
        ThreeRowSongList(
            tracks.size,
            columnWidth,
            spacing,
            PaddingValues(horizontal = DetailPageHorizontalPadding),
            coverSize = ArtistSongCoverSize,
            modifier = Modifier.onSizeChanged { onHeightChanged(it.height) }
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(Color.Transparent, Color.Black),
                            endX = DetailPageHorizontalPadding.toPx(),
                        ),
                        blendMode = BlendMode.DstIn,
                    )
                },
        ) { index ->
            val track = tracks[index]
            var anchor by remember(track.id) { mutableStateOf(Rect.Zero) }
            Box {
                if (track.id.toString() in favoriteIds) {
                    FavoriteTrackStar(
                        Modifier.align(Alignment.CenterStart)
                            .offset(x = (-14).dp)
                            .testTag("ArtistSongFavorite:${track.id}"),
                        tint = LocalGlassColors.current.secondaryContent,
                    )
                }
                HomeSongListRow(
                    track.coverUrl,
                    track.title,
                    track.artists.joinToString(" / ") { it.name },
                    isPlaying = playing && track.id == currentTrackId,
                    onClick = { onClick(index) },
                    coverSize = ArtistSongCoverSize,
                ) {
                    IconButton(
                        onClick = { onMore(track, anchor) },
                        modifier =
                            Modifier.size(HomeSongActionSize)
                                .offset(x = (-6).dp)
                                .testTag("ArtistSongMore:${track.id}")
                                .onGloballyPositioned { anchor = it.boundsInWindow() },
                    ) {
                        SfIcon(
                            "ellipsis",
                            stringResource(R.string.more_actions_title),
                            size = 18.dp,
                            tint = LocalGlassColors.current.secondaryContent,
                        )
                    }
                }
            }
        }
    }
}

// ─── Shared ───────────────────────────────────────────────────────────────────

@Composable
fun SectionTitle(
    title: String,
    onClick: (() -> Unit)? = null,
    bottomPadding: Dp = if (onClick != null) 12.dp else 4.dp,
    landscape: Boolean = false,
) {
    Row(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(
                start = DetailPageHorizontalPadding,
                end = DetailPageHorizontalPadding,
                top = 20.dp,
                bottom = bottomPadding,
            ),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            modifier = if (onClick != null) Modifier.weight(1f, fill = false) else Modifier,
            style = if (landscape) MaterialTheme.typography.titleMedium.copy(fontSize = 22.sp, lineHeight = 28.sp) else MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        if (onClick != null)
            SfIcon(
                "chevron.forward",
                null,
                size = if (landscape) 16.dp else 12.dp,
                tint = LocalGlassColors.current.secondaryContent,
            )
    }
}

@Composable
fun ErrorItem(message: String) {
    Text(
        text = message,
        modifier = Modifier.padding(horizontal = DetailPageHorizontalPadding, vertical = 20.dp),
        color = MaterialTheme.colorScheme.error
    )
}
