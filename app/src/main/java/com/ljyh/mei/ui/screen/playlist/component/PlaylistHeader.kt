package com.ljyh.mei.ui.screen.playlist.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.kyant.capsule.ContinuousRoundedRectangle
import com.ljyh.mei.R
import com.ljyh.mei.constants.PlaylistCoverStyle
import com.ljyh.mei.constants.PlaylistCoverStyleKey
import com.ljyh.mei.ui.component.playlist.FinalPerfectCollage
import com.ljyh.mei.ui.glass.GlassButton
import com.ljyh.mei.ui.glass.GlassEmphasis
import com.ljyh.mei.ui.glass.GlassIconButton
import com.ljyh.mei.ui.glass.IosTypography
import com.ljyh.mei.ui.glass.LocalGlassColors
import com.ljyh.mei.ui.glass.SfIcon
import com.ljyh.mei.utils.rememberEnumPreference

/**
 * iOS-style playlist hero. The cover and actions intentionally stay self-contained so the
 * playlist page can be hosted by [IosPinnedListPage] without a second app bar or card system.
 */
@Composable
fun PlaylistHeader(
    title: String,
    count: Int,
    playCount: Long,
    subscribeCount: Long,
    cover: String,
    coverList: List<String>,
    creator: String,
    isSubscribed: Boolean,
    onPlayAll: () -> Unit,
    onSubscribed: (Boolean) -> Unit,
    onDownload: (() -> Unit)? = null,
    actionIcon: ImageVector,
    actionLabel: String,
    metadata: String? = null,
) {
    val colors = LocalGlassColors.current
    val playlistCoverStyle by rememberEnumPreference(
        PlaylistCoverStyleKey,
        defaultValue = PlaylistCoverStyle.Cover,
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PlaylistCover(
            style = playlistCoverStyle,
            cover = cover,
            coverList = coverList,
        )

        Text(
            text = title,
            style = IosTypography.title2,
            color = colors.content,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 22.dp).padding(horizontal = 20.dp),
        )
        if (creator.isNotBlank()) {
            Text(
                text = creator,
                style = IosTypography.title2.copy(
                    fontWeight = FontWeight.Normal,
                    fontSize = IosTypography.subheadline.fontSize,
                    lineHeight = IosTypography.subheadline.lineHeight,
                ),
                color = colors.content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 7.dp),
            )
        }

        Text(
            text = metadata ?: buildPlaylistMetadata(count, playCount, subscribeCount),
            style = IosTypography.subheadline.copy(fontWeight = FontWeight.Medium),
            color = colors.secondaryContent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 7.dp),
        )

        Row(
            modifier = Modifier.padding(top = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassIconButton(onClick = onPlayAll, enabled = count > 0) {
                SfIcon("shuffle", null, size = 24.dp, weight = FontWeight.SemiBold)
            }
            GlassButton(
                onClick = onPlayAll,
                enabled = count > 0,
                emphasis = GlassEmphasis.Prominent,
            ) {
                SfIcon("play.fill", null, size = 18.dp)
                Spacer(Modifier.width(8.dp))
                androidx.compose.material3.Text(
                    stringResource(R.string.pip_play),
                    style = IosTypography.headline,
                )
            }
            GlassIconButton(
                onClick = { onSubscribed(isSubscribed) },
                emphasis = if (isSubscribed) GlassEmphasis.Prominent else GlassEmphasis.Regular,
            ) {
                SfIcon(
                    if (isSubscribed) "checkmark" else "plus",
                    actionLabel,
                    size = 24.dp,
                    weight = FontWeight.SemiBold,
                )
            }
            onDownload?.let { download ->
                GlassIconButton(onClick = download) {
                    SfIcon(
                        "arrow.down.circle",
                        stringResource(R.string.track_action_download),
                        size = 22.dp,
                    )
                }
            }
        }

    }
}

@Composable
private fun PlaylistCover(
    style: PlaylistCoverStyle,
    cover: String,
    coverList: List<String>,
) {
    val shape = ContinuousRoundedRectangle(14.dp)
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .fillMaxWidth(0.68f)
            .widthIn(max = 300.dp)
            .aspectRatio(1f)
            .shadow(18.dp, shape)
            .clip(shape),
    ) {
        when (style) {
            PlaylistCoverStyle.Cover -> AsyncImage(
                model = cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )

            PlaylistCoverStyle.FirstSongImage -> AsyncImage(
                model = coverList.firstOrNull() ?: cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )

            PlaylistCoverStyle.Combination -> {
                if (coverList.size < 5) {
                    AsyncImage(
                        model = cover,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.matchParentSize(),
                    )
                } else {
                    FinalPerfectCollage(
                        imageUrls = coverList,
                        modifier = Modifier.matchParentSize(),
                    )
                }
            }
        }
    }
}

@Composable
private fun buildPlaylistMetadata(
    count: Int,
    playCount: Long,
    subscribeCount: Long,
): String {
    val songs = stringResource(R.string.playlist_song_count, count)
    val played = if (playCount >= 0) {
        stringResource(R.string.song_wiki_play_count, formatCompactCount(playCount))
    } else {
        null
    }
    return listOfNotNull(songs, played).joinToString(" · ")
}

private fun formatCompactCount(value: Long): String = value.toString()
