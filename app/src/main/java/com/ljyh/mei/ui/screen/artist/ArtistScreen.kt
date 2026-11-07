package com.ljyh.mei.ui.screen.artist

import com.ljyh.mei.ui.component.item.FixedCardCaption
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onSizeChanged
import com.ljyh.mei.ui.component.item.ThreeRowSongList
import com.ljyh.mei.constants.HomeSongRowSpacing
import com.ljyh.mei.ui.component.item.HomeSongListRow
import com.ljyh.mei.constants.homePlaylistCardSize
import com.ljyh.mei.constants.homeCardSpacing
import com.ljyh.mei.constants.HomeSongNextPagePeek
import com.ljyh.mei.data.model.mediaId
import com.ljyh.mei.data.model.MediaMetadata
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.draw.drawWithContent
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
import coil3.compose.AsyncImage
import io.github.neoruaa.glasmio.glass.render.backdrops.rememberCanvasBackdrop
import com.kyant.capsule.ContinuousRoundedRectangle
import com.kyant.shapes.Capsule
import com.ljyh.mei.R
import com.ljyh.mei.constants.DetailPageHorizontalPadding
import com.ljyh.mei.ui.component.playlist.CoverBackground
import com.ljyh.mei.ui.component.playlist.FavoriteTrackStar
import com.ljyh.mei.ui.component.playlist.rememberCoverBackground
import com.ljyh.mei.ui.component.shimmer.ShimmerHost
import com.ljyh.mei.ui.component.shimmer.TextPlaceholder
import com.ljyh.mei.ui.local.LocalColorfulPageBackgrounds
import com.ljyh.mei.ui.local.LocalPageAppearance
import com.ljyh.mei.ui.local.LocalPlayerAwareWindowInsets
import com.ljyh.mei.ui.navigation.MeiloXPinnedPage
import com.ljyh.mei.ui.theme.LocalAccentSeedColor
import com.ljyh.mei.ui.theme.MusicTheme
import io.github.neoruaa.glasmio.core.IosTypography
import io.github.neoruaa.glasmio.core.LocalGlassColors
import io.github.neoruaa.glasmio.core.defaultGlassColors
import io.github.neoruaa.glasmio.glass.LocalGlassBackdrop
import io.github.neoruaa.glasmio.icons.SfIcon
import io.github.neoruaa.glasmio.icons.SfSymbol
import io.github.neoruaa.glasmio.ui.GlasmioButton
import kotlinx.coroutines.CancellationException

private val ArtistSongCoverSize = com.ljyh.mei.constants.LandscapeSongCoverSize
private val ArtistPopularSongsHeight = ArtistSongCoverSize * 3 + HomeSongRowSpacing * 2

/** The original artist detail layout, shared by every source. */
@Composable
internal fun ArtistDetailPageLayout(
    title: String,
    background: CoverBackground,
    scrollState: androidx.compose.foundation.lazy.LazyListState,
    landscape: Boolean,
    popularSongsHeightPx: Int,
    onBack: () -> Unit,
    header: @Composable (PaddingValues) -> Unit,
    latestRelease: @Composable () -> Unit,
    songSection: @Composable () -> Unit,
    popularSongs: @Composable () -> Unit,
    albumSection: @Composable () -> Unit,
    isArtistUnavailable: Boolean = false,
    footer: androidx.compose.foundation.lazy.LazyListScope.() -> Unit = {},
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // The hero and the glass collapse follow the actual available page width.
        val heroHeightPx = with(LocalDensity.current) { if (landscape) 400.dp.toPx() else maxWidth.toPx() }
        val topBarCollapseProgress by remember(scrollState, heroHeightPx) { derivedStateOf {
            if (scrollState.firstVisibleItemIndex > 0) 1f else
                (scrollState.firstVisibleItemScrollOffset / (heroHeightPx * 0.6f).coerceAtLeast(1f)).coerceIn(0f, 1f)
        } }
        val blurDistancePx = with(LocalDensity.current) { 56.dp.toPx() }
        val topBarBlurProgress by remember(scrollState, blurDistancePx) { derivedStateOf {
            if (scrollState.firstVisibleItemIndex > 0) 1f else
                (scrollState.firstVisibleItemScrollOffset / blurDistancePx).coerceIn(0f, 1f)
        } }
        val latestArtworkHeight = with(LocalDensity.current) { popularSongsHeightPx.toDp() }.takeIf { it > 0.dp }
            ?: ArtistPopularSongsHeight
        val latestTextWidth = with(LocalDensity.current) { 120.sp.toDp() }
        val latestMinimumWidth = latestArtworkHeight + DetailPageHorizontalPadding * 2 + 16.dp + latestTextWidth
        val latestPaneWidth = maxOf(maxWidth * 0.31f, latestMinimumWidth.coerceAtMost(maxWidth * 0.55f))
            MeiloXPinnedPage(
                title = title,
                bottomPadding =
                    LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateBottomPadding(),
                collapseProgress = topBarCollapseProgress,
                topBarBlurProgress = topBarBlurProgress,
                onNavigateBack = onBack,
                backgroundColor = background.color,
                backgroundBrush = background.brush,
            ) { contentPadding ->
                if (isArtistUnavailable) {
                    ArtistUnavailableState(
                        onNavigateBack = onBack,
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
                            header(contentPadding)
                        }

                        if (landscape) {
                            item(key = "artist-landscape-sections") {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                                    Column(Modifier.width(latestPaneWidth)) { latestRelease() }
                                    Column(Modifier.weight(1f)) { popularSongs() }
                                }
                            }
                            item(key = "artist-albums") {
                                Column(Modifier.padding(top = 16.dp)) { albumSection() }
                            }
                        } else {
                            item(key = "artist-latest-release") { latestRelease() }
                            item(key = "artist-songs") { songSection() }
                            item(key = "artist-albums") { albumSection() }
                        }
                        footer()
                    }
                }
            }
    }
}

@Composable
internal fun ArtistDetailTheme(
    route: String,
    coverUrl: String,
    artwork: com.ljyh.mei.ui.screen.playlist.DetailArtworkViewModel = hiltViewModel(),
    content: @Composable (CoverBackground) -> Unit,
) {
    val colorfulBackgrounds = LocalColorfulPageBackgrounds.current
    var coverIsDark by remember(artwork, coverUrl) {
        mutableStateOf(artwork.getCachedCoverIsDark(coverUrl))
    }
    LaunchedEffect(artwork, coverUrl, colorfulBackgrounds) {
        if (!colorfulBackgrounds || coverUrl.isBlank()) return@LaunchedEffect
        try {
            artwork.getCoverIsDarkOrExtract(coverUrl)?.let { coverIsDark = it }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Keep the app appearance while artwork brightness is unavailable.
        }
    }

    val isDark = (if (colorfulBackgrounds) coverIsDark else null) ?: LocalGlassColors.current.isDark
    val setPageAppearance = LocalPageAppearance.current
    val appearanceOwner = remember(artwork, route) { Any() }
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
                getCachedColor = artwork::getCachedColor,
                getOrExtractColor = artwork::getOrExtractColor,
                ownerKey = artwork,
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
internal fun ArtistDetailHeader(
    name: String,
    subtitle: String = "",
    cover: String,
    accent: Color,
    canPlayHotSongs: Boolean,
    isFollowed: Boolean,
    isFollowLoading: Boolean,
    onInfoClick: () -> Unit,
    onPlayHotSongs: () -> Unit,
    onFollowClick: () -> Unit,
    landscape: Boolean = false,
    canFollow: Boolean = true,
) {
    val colors = LocalGlassColors.current
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
                    text = name,
                    style = if (landscape) IosTypography.title2.copy(fontSize = 32.sp, lineHeight = 36.sp) else IosTypography.title2,
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
                                .padding(top = 4.dp),
                    )
                }

                Row(
                    modifier = Modifier.padding(top = 18.dp, bottom = if (landscape) 8.dp else 16.dp),
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
                    if (canFollow) ArtistActionButton(
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
            .size(if (landscape) { if (prominent) 72.dp else 48.dp } else { if (prominent) 68.dp else 44.dp })
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
internal fun ArtistAlbumCard(title: String, cover: String, trackCount: Int, artworkSize: Dp = 116.dp, onClick: () -> Unit) {
    Column(modifier = Modifier.width(artworkSize).clickable { onClick() }) {
        AsyncImage(
            model = cover,
            contentDescription = title,
            modifier =
                Modifier.size(artworkSize)
                    .clip(ContinuousRoundedRectangle(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            contentScale = ContentScale.Crop,
        )
        Spacer(modifier = Modifier.height(6.dp))
        FixedCardCaption(
            title = title,
            subtitle = "$trackCount 首",
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
                                        36.sp.toDp()
                                    }
                                else 22.dp
                            ),
                        verticalPadding = if (landscape) 0.dp else 4.dp,
                    )
                    TextPlaceholder(
                        Modifier.padding(top = 4.dp)
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
                        Modifier.padding(top = 18.dp, bottom = if (landscape) 8.dp else 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        (if (landscape) listOf(48.dp, 72.dp, 48.dp) else listOf(44.dp, 68.dp, 44.dp)).forEach { size ->
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
        val avatarSize = minOf(maxWidth * 0.20f, 200.dp)
        if (landscape) {
            Column(
                Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 8.dp),
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
                Spacer(Modifier.height(24.dp))
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
internal fun ArtistFeaturedAlbumInformation(title: String, trackCount: Int, releaseDate: String? = null, landscape: Boolean) {
    val metadataStyle = if (landscape) IosTypography.subheadline
        else IosTypography.subheadline.copy(fontSize = 13.sp, lineHeight = 18.sp)
    releaseDate?.takeIf(String::isNotBlank)?.let {
        Text(it, style = metadataStyle, color = LocalGlassColors.current.secondaryContent)
    }
    Text(title, style = if (landscape) IosTypography.headline else IosTypography.headline.copy(
        fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
        maxLines = if (landscape) 3 else 2, overflow = TextOverflow.Ellipsis,
        color = LocalGlassColors.current.content)
    Text(stringResource(R.string.playlist_song_count, trackCount), style = metadataStyle,
        color = LocalGlassColors.current.secondaryContent)
}

@Composable
internal fun ArtistFeaturedAlbumLayout(
    landscape: Boolean,
    artworkHeight: Dp,
    artwork: @Composable (Modifier) -> Unit,
    information: @Composable () -> Unit,
    action: @Composable () -> Unit,
    onClick: (() -> Unit)? = null,
) {
    val colors = LocalGlassColors.current
    val shape = ContinuousRoundedRectangle(24.dp)
    val coverSize = if (landscape) artworkHeight else 88.dp
    Row(
        Modifier.fillMaxWidth().padding(horizontal = if (landscape) DetailPageHorizontalPadding else 20.dp)
            .padding(top = if (landscape) 0.dp else 12.dp, bottom = if (landscape) 0.dp else 4.dp)
            .then(if (landscape) Modifier else Modifier.clip(shape)
                .background(colors.content.copy(alpha = 0.10f))
                .border(0.5.dp, Color.White.copy(alpha = 0.15f), shape))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .then(if (landscape) Modifier else Modifier.padding(12.dp)),
        horizontalArrangement = Arrangement.spacedBy(if (landscape) 16.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        artwork(Modifier.size(coverSize).clip(ContinuousRoundedRectangle(8.dp)))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(if (landscape) 4.dp else 0.dp)) {
            information()
            if (landscape) Box(Modifier.padding(top = 4.dp)) { action() }
        }
        if (!landscape) action()
    }
}

@Composable
private fun ArtistFeaturedAlbumPlaceholder(artworkHeight: Dp = ArtistPopularSongsHeight, landscape: Boolean = true) {
    ArtistFeaturedAlbumLayout(landscape, artworkHeight,
        artwork = { Spacer(it.background(MaterialTheme.colorScheme.surfaceVariant)) },
        information = { repeat(3) { TextPlaceholder() } },
        action = {
            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                Spacer(Modifier.size(if (landscape) 36.dp else 32.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant))
            }
        },
    )
}

@Composable
internal fun ArtistPopularSongs(
    tracks: List<MediaMetadata>,
    favoriteIds: Set<String>,
    currentTrackId: String?,
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
            Box {
                if (track.mediaId in favoriteIds) {
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
                    onClick = { onClick(index) },
                    onMore = { anchor -> onMore(track, anchor) },
                    moreModifier = Modifier.testTag("ArtistSongMore:${track.id}"),
                    coverSize = ArtistSongCoverSize,
                )

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
    topPadding: Dp = 20.dp,
) {
    Row(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(
                start = if (landscape) DetailPageHorizontalPadding else 20.dp,
                end = if (landscape) DetailPageHorizontalPadding else 20.dp,
                top = topPadding,
                bottom = bottomPadding,
            ),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            modifier = if (onClick != null) Modifier.weight(1f, fill = false) else Modifier,
            style = if (landscape) MaterialTheme.typography.titleMedium.copy(fontSize = 20.sp, lineHeight = 24.sp) else MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        if (onClick != null)
            SfIcon(
                "chevron.forward",
                null,
                size = if (landscape) 18.dp else 16.dp,
                weight = FontWeight.SemiBold,
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
