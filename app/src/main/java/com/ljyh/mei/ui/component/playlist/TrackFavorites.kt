package com.ljyh.mei.ui.component.playlist

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.source.api.TrackRef
import com.ljyh.mei.source.api.TrackRefCodec
import com.ljyh.mei.ui.component.player.PlayerViewModel
import io.github.neoruaa.glasmio.core.LocalGlassColors
import io.github.neoruaa.glasmio.icons.SfIcon

@Composable
internal fun rememberFavoriteTrackIds(
    tracks: List<MediaMetadata>,
    enabled: Boolean = true,
): Set<String> {
    if (!enabled) return emptySet()
    val refs = remember(tracks) {
        tracks.asSequence().filter { !it.isLocal && !it.isPodcast }.map { it.trackRef }.toSet()
    }
    return rememberSourceFavoriteTrackIds(refs)
}

@Composable
internal fun rememberFavoriteTrackIds(ids: Set<Long>, enabled: Boolean = true): Set<String> {
    if (!enabled) return emptySet()
    val refs = remember(ids) { ids.filter { it > 0L }.mapNotNull { TrackRefCodec.decode(it.toString()) }.toSet() }
    return rememberSourceFavoriteTrackIds(refs)
}

@Composable
private fun rememberSourceFavoriteTrackIds(refs: Set<TrackRef>): Set<String> {
    val viewModel: PlayerViewModel = hiltViewModel()
    val likes by viewModel.songLikes.collectAsStateWithLifecycle()
    val generation by viewModel.songLikeGeneration.collectAsStateWithLifecycle()
    LaunchedEffect(refs, generation) {
        if (refs.isNotEmpty()) viewModel.ensureSourceLikes(refs)
    }
    return remember(likes, refs) {
        val keys = refs.map { TrackRefCodec.mediaId(it) }.toSet()
        likes.filter { (key, liked) -> liked && key in keys }.keys
    }
}

@Composable
internal fun FavoriteTrackStar(
    modifier: Modifier = Modifier,
    tint: Color = LocalGlassColors.current.secondaryContent,
) {
    SfIcon(
        systemName = "star.fill",
        contentDescription = null,
        modifier = modifier.graphicsLayer {
            blendMode = BlendMode.Luminosity
            compositingStrategy = CompositingStrategy.Offscreen
        },
        tint = tint,
        size = 11.dp,
    )
}
