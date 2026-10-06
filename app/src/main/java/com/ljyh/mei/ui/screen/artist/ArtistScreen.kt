package com.ljyh.mei.ui.screen.artist

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import com.ljyh.mei.ui.component.playlist.rememberCoverBackground
import com.ljyh.mei.ui.component.player.OverlayState
import com.ljyh.mei.ui.component.shimmer.ListItemPlaceHolder
import com.ljyh.mei.ui.component.shimmer.ShimmerHost
import com.ljyh.mei.ui.component.shimmer.TextPlaceholder
import com.ljyh.mei.ui.glass.GlassButton
import com.ljyh.mei.ui.glass.GlassEmphasis
import com.ljyh.mei.ui.glass.GlassIconButton
import com.ljyh.mei.ui.glass.GlassSurfaceStyle
import com.ljyh.mei.ui.glass.IosListRow
import com.ljyh.mei.ui.glass.IosPinnedPage
import com.ljyh.mei.ui.glass.IosTypography
import com.ljyh.mei.ui.glass.LocalGlassColors
import com.ljyh.mei.ui.glass.SheetGroupedListBackgroundAlpha
import com.ljyh.mei.ui.glass.SfIcon
import com.ljyh.mei.ui.glass.SfSymbol
import com.ljyh.mei.ui.local.LocalNavController
import com.ljyh.mei.ui.local.LocalPlayerAwareWindowInsets
import com.ljyh.mei.ui.local.LocalPlayerConnection
import com.ljyh.mei.ui.model.Album
import com.ljyh.mei.ui.model.toAlbum
import com.ljyh.mei.ui.screen.Screen
import com.ljyh.mei.ui.screen.playlist.component.StandaloneTrackActionOverlay
import java.util.UUID


@OptIn(UnstableApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ArtistScreen(
    id: String,
    viewModel: ArtistViewModel = hiltViewModel(key = "artist:$id"),
) {
    val navController = LocalNavController.current
    val playerConnection = LocalPlayerConnection.current

    val artistDetail by viewModel.artistDetail.collectAsState()
    val artistAlbums by viewModel.artistAlbums.collectAsState()
    val artistSongs by viewModel.artistSongs.collectAsState()
    val followMutation by viewModel.followMutation.collectAsState()
    var isFollowed by remember(id) { mutableStateOf(false) }
    var currentOverlay by remember(id) { mutableStateOf<OverlayState>(OverlayState.None) }
    val onAllSongsClick = { navController.navigate("${Screen.ArtistSongs.route}/$id") }
    val onAllAlbumsClick = { navController.navigate("${Screen.ArtistAlbums.route}/$id") }
    val onInfoClick = { navController.navigate("${Screen.ArtistInfo.route}/$id") }
    val artistData = (artistDetail as? Resource.Success)?.data?.data
    val isArtistUnavailable = artistDetail is Resource.Success && artistData?.artist == null
    val heroCover = artistData?.artist?.let { artist ->
        artist.cover.takeIf(String::isNotBlank) ?: artist.avatar
    }.orEmpty()
    val background = rememberCoverBackground(
        coverUrl = heroCover,
        getCachedColor = viewModel::getCachedColor,
        getOrExtractColor = viewModel::getOrExtractColor,
        ownerKey = viewModel,
    )
    val hotSongs = (artistSongs as? Resource.Success)?.data?.hotSongs.orEmpty()

    val scrollState = rememberLazyListState()
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

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // The square hero follows the actual page width, including split-screen resizing.
        val heroHeightPx = with(LocalDensity.current) { maxWidth.toPx() }
        val topBarCollapseProgress by remember(scrollState, heroHeightPx) {
            derivedStateOf {
                if (scrollState.firstVisibleItemIndex > 0) 1f
                else (scrollState.firstVisibleItemScrollOffset / (heroHeightPx * 0.6f).coerceAtLeast(1f))
                    .coerceIn(0f, 1f)
            }
        }
        IosPinnedPage(
            title = artistData?.artist?.name.orEmpty(),
            bottomPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateBottomPadding(),
            collapseProgress = topBarCollapseProgress,
            onNavigateBack = navController::popBackStack,
            backgroundColor = background.color,
            backgroundBrush = background.brush,
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
                                        cover = heroCover,
                                        canPlayHotSongs = playerConnection != null && hotSongs.isNotEmpty(),
                                        onInfoClick = onInfoClick,
                                        onPlayHotSongs = {
                                            if (hotSongs.isNotEmpty()) {
                                                playerConnection?.playQueue(
                                                    ListQueue(
                                                        UUID.randomUUID().toString(),
                                                        artist.name,
                                                        hotSongs.map { song ->
                                                            song.id.toString() to song.toMediaMetadata().toMediaItem()
                                                        },
                                                        0,
                                                    ),
                                                    shuffle = false,
                                                )
                                            }
                                        },
                                        isFollowed = isFollowed,
                                        isFollowLoading = followMutation is Resource.Loading,
                                        onFollowClick = {
                                            viewModel.setArtistFollowed(artist.id.toLong(), !isFollowed)
                                        },
                                    )
                                } else {
                                    ErrorItem(detail.data.message ?: "Unable to load artist")
                                }
                            }
                            is Resource.Loading -> ArtistHeaderShimmer()
                            is Resource.Error -> {
                                Box(Modifier.padding(top = contentPadding.calculateTopPadding())) {
                                    ErrorItem(detail.message)
                                }
                            }
                        }
                    }

                    // --- 2. Hot Songs ---
                    item { SectionTitle(stringResource(R.string.artist_hot_songs)) }

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
                                        playerConnection?.onTrackClicked(
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
                                    onMoreClick = { currentOverlay = OverlayState.TrackActionMenu(song.toMediaMetadata(), it) }
                                )
                            }
                        }
                        is Resource.Loading -> items(5) { ShimmerHost { ListItemPlaceHolder() } }
                        is Resource.Error -> item { ErrorItem(songsResource.message) }
                    }

                    val songCount = artistData?.artist?.musicSize
                        ?: (artistSongs as? Resource.Success)?.data?.artist?.musicSize
                    if (songCount != null) {
                        item(key = "all-artist-songs") {
                            IosListRow(
                                title = stringResource(R.string.artist_all_songs_count, songCount),
                                modifier = Modifier.padding(horizontal = 6.dp),
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

                    // --- 3. Albums ---
                    item { SectionTitle(stringResource(R.string.artist_albums)) }

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
                                            album = hotAlbum.toAlbum(),
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

                    val albumCount = artistData?.artist?.albumSize
                        ?: (artistAlbums as? Resource.Success)?.data?.artist?.albumSize
                    if (albumCount != null) {
                        item(key = "all-artist-albums") {
                            IosListRow(
                                title = stringResource(R.string.artist_all_albums_count, albumCount),
                                modifier = Modifier.padding(horizontal = 6.dp).padding(top = 10.dp),
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


// ─── Artist Hero ─────────────────────────────────────────────────────────────

@Composable
private fun ArtistHeader(
    artist: ArtistDetail.Data.Artist,
    cover: String,
    canPlayHotSongs: Boolean,
    isFollowed: Boolean,
    isFollowLoading: Boolean,
    onInfoClick: () -> Unit,
    onPlayHotSongs: () -> Unit,
    onFollowClick: () -> Unit,
) {
    val colors = LocalGlassColors.current
    val regularButtonBackground = colors.elevatedBackground.copy(
        alpha = SheetGroupedListBackgroundAlpha,
    )
    val subtitle = remember(artist) {
        (artist.transNames + artist.alias.filterIsInstance<String>())
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.equals(artist.name, ignoreCase = true) }
            .distinct()
            .joinToString(" · ")
    }
    val playHotSongsLabel = stringResource(R.string.artist_play_hot_songs)

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AsyncImage(
            model = cover,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    // Reveal the same page gradient beneath the image to avoid a solid seam.
                    drawRect(
                        brush = Brush.verticalGradient(
                            0f to Color.Black,
                            0.45f to Color.Black,
                            0.70f to Color.Black.copy(alpha = 0.45f),
                            1f to Color.Transparent,
                        ),
                        blendMode = BlendMode.DstIn,
                    )
                },
        )

        Text(
            text = artist.name,
            style = IosTypography.title2,
            color = colors.content,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 20.dp).padding(top = 8.dp),
        )
        if (subtitle.isNotBlank()) {
            Text(
                text = subtitle,
                style = IosTypography.subheadline,
                color = colors.secondaryContent,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 20.dp).padding(top = 7.dp),
            )
        }

        Row(
            modifier = Modifier.padding(top = 18.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassIconButton(
                onClick = onInfoClick,
                style = GlassSurfaceStyle.Navigation,
                navigationSurfaceColor = regularButtonBackground,
                navigationSurfaceAlphaMultiplier = 1f,
                sampleBackdrop = false,
            ) {
                SfIcon(
                    "info",
                    stringResource(R.string.artist_info_title),
                    size = 24.dp,
                    weight = FontWeight.SemiBold,
                )
            }
            GlassButton(
                onClick = onPlayHotSongs,
                modifier = Modifier.semantics { contentDescription = playHotSongsLabel },
                style = GlassSurfaceStyle.Navigation,
                enabled = canPlayHotSongs,
                emphasis = GlassEmphasis.Prominent,
            ) {
                SfIcon("play.fill", null, size = 18.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.pip_play),
                    style = IosTypography.headline,
                )
            }
            GlassIconButton(
                onClick = onFollowClick,
                enabled = !isFollowLoading,
                style = GlassSurfaceStyle.Navigation,
                navigationSurfaceColor = regularButtonBackground.takeUnless { isFollowed },
                navigationSurfaceAlphaMultiplier = if (isFollowed) 1.25f else 1f,
                emphasis = if (isFollowed) GlassEmphasis.Prominent else GlassEmphasis.Regular,
                sampleBackdrop = isFollowed,
            ) {
                SfIcon(
                    if (isFollowed) "checkmark" else "plus",
                    stringResource(if (isFollowed) R.string.artist_following else R.string.artist_follow),
                    size = 24.dp,
                    weight = FontWeight.SemiBold,
                )
            }
        }
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
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f)),
            )
            TextPlaceholder(Modifier.padding(top = 8.dp).width(180.dp).height(22.dp))
            TextPlaceholder(Modifier.padding(top = 7.dp).width(120.dp).height(16.dp))
            Row(
                modifier = Modifier.padding(top = 18.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                listOf(44.dp, 122.dp, 44.dp).forEach { width ->
                    Spacer(
                        modifier = Modifier
                            .width(width)
                            .height(44.dp)
                            .clip(ContinuousRoundedRectangle(50))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    )
                }
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
