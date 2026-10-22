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
import com.ljyh.mei.ui.component.player.PlayerViewModel
import com.ljyh.mei.ui.glass.LocalGlassColors
import com.ljyh.mei.ui.glass.SfIcon

@Composable
internal fun rememberFavoriteTrackIds(
    tracks: List<MediaMetadata>,
    enabled: Boolean = true,
): Set<String> {
    if (!enabled) return emptySet()
    val viewModel: PlayerViewModel = hiltViewModel()
    val likes by viewModel.songLikes.collectAsStateWithLifecycle()
    val account by viewModel.songLikeAccount.collectAsStateWithLifecycle()
    val generation by viewModel.songLikeGeneration.collectAsStateWithLifecycle()
    val ids = remember(tracks) {
        tracks.asSequence()
            .filter { !it.isLocal && !it.isPodcast && it.id > 0 }
            .map { it.id }
            .toSet()
    }
    LaunchedEffect(ids, account, generation) {
        val currentAccount = account ?: return@LaunchedEffect
        if (ids.isNotEmpty()) viewModel.ensureSongLikes(ids, currentAccount.sessionFingerprint)
    }
    return remember(likes, account) {
        if (account == null) emptySet()
        else likes.filterValues { it }.keys.toSet()
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
        size = 12.dp,
    )
}
