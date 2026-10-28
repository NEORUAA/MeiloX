package com.ljyh.mei.ui.component.player.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.res.stringResource
import com.ljyh.mei.R
import com.ljyh.mei.data.model.MediaMetadata
import io.github.neoruaa.glasmio.ui.GlasmioCascadingMenu
import io.github.neoruaa.glasmio.ui.GlasmioCascadingMenuItem

/** Player destinations use the same cascading interaction as track download qualities. */
@Composable
fun PlayerNavigationMenu(
    album: MediaMetadata.Album,
    artists: List<MediaMetadata.Artist>,
    playlistTitle: String?,
    anchorBounds: Rect,
    onAlbumClick: (Long) -> Unit,
    onArtistClick: (Long) -> Unit,
    onPlaylistClick: () -> Unit,
    onDismiss: () -> Unit,
    viewportBounds: Rect? = null,
) {
    val navigableArtists = artists.filter { it.id > 0 }.distinctBy { it.id }
    val artistItems = if (navigableArtists.size > 1) {
        navigableArtists.map { artist ->
            GlasmioCascadingMenuItem(
                title = artist.name,
                onClick = { onArtistClick(artist.id) },
            )
        }
    } else emptyList()

    GlasmioCascadingMenu(
        anchorBounds = anchorBounds,
        viewportBounds = viewportBounds,
        title = stringResource(R.string.player_navigation_title),
        expandedDescription = stringResource(R.string.menu_expanded),
        collapsedDescription = stringResource(R.string.menu_collapsed),
        centerHorizontally = true,
        expandFromAnchor = Alignment.Start,
        forceBelowAnchor = true,
        useAccentIcons = false,
        onDismiss = onDismiss,
        items = listOf(
            GlasmioCascadingMenuItem(
                title = stringResource(R.string.player_navigation_artist),
                subtitle = artists.joinToString { it.name }.ifBlank { "—" },
                systemName = "microphone.dynamic.on.stand",
                enabled = navigableArtists.isNotEmpty(),
                children = artistItems,
                onClick = { navigableArtists.singleOrNull()?.let { onArtistClick(it.id) } },
            ),
            GlasmioCascadingMenuItem(
                title = stringResource(R.string.player_navigation_album),
                subtitle = album.title.ifBlank { "—" },
                systemName = "rectangle.stack",
                enabled = album.id > 0,
                onClick = { onAlbumClick(album.id) },
            ),
            GlasmioCascadingMenuItem(
                title = stringResource(R.string.player_navigation_playlist),
                subtitle = playlistTitle?.takeIf(String::isNotBlank)
                    ?: stringResource(R.string.player_action_queue),
                systemName = "music.note.list",
                onClick = onPlaylistClick,
            ),
        ),
    )
}
