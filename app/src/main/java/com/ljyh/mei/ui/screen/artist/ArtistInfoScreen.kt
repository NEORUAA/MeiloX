package com.ljyh.mei.ui.screen.artist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.ljyh.mei.R
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.ui.glass.GlassButton
import com.ljyh.mei.ui.glass.IosGroupedList
import com.ljyh.mei.ui.glass.IosListRow
import com.ljyh.mei.ui.glass.IosPinnedListPage
import com.ljyh.mei.ui.glass.IosTypography
import com.ljyh.mei.ui.glass.LocalGlassColors
import com.ljyh.mei.ui.local.LocalNavController
import com.ljyh.mei.ui.local.LocalPlayerAwareWindowInsets
import com.ljyh.mei.ui.screen.Screen

@Composable
fun ArtistInfoScreen(
    id: String,
    viewModel: ArtistViewModel = hiltViewModel(key = "artist-info:$id"),
) {
    val navController = LocalNavController.current
    val artistDetail by viewModel.artistDetail.collectAsState()
    val artist = (artistDetail as? Resource.Success)?.data?.data?.artist

    LaunchedEffect(id) { viewModel.getArtistDetail(id) }

    IosPinnedListPage(
        title = stringResource(R.string.artist_info_title),
        subtitle = artist?.name,
        bottomPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateBottomPadding(),
        onNavigateBack = navController::popBackStack,
    ) {
        when (val detail = artistDetail) {
            is Resource.Loading -> item(key = "loading") {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = LocalGlassColors.current.accent)
                }
            }
            is Resource.Error -> item(key = "error") {
                IosGroupedList {
                    Text(
                        text = detail.message,
                        style = IosTypography.body,
                        color = LocalGlassColors.current.secondaryContent,
                        modifier = Modifier.padding(16.dp),
                    )
                    IosListRow(
                        title = stringResource(R.string.retry),
                        onClick = { viewModel.getArtistDetail(id) },
                    )
                }
            }
            is Resource.Success -> {
                val data = detail.data.data
                val artistData = data?.artist
                if (artistData == null) {
                    item(key = "unavailable") {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.artist_unavailable_title),
                                style = IosTypography.title2,
                                color = LocalGlassColors.current.content,
                            )
                            Text(
                                text = stringResource(R.string.artist_unavailable_description),
                                style = IosTypography.body,
                                color = LocalGlassColors.current.secondaryContent,
                            )
                            GlassButton(onClick = { navController.popBackStack() }) {
                                Text(stringResource(R.string.navigation_back))
                            }
                        }
                    }
                } else {
                    item(key = "works") {
                        ArtistInfoSection(stringResource(R.string.artist_info_works)) {
                            IosListRow(
                                title = stringResource(R.string.artist_info_songs),
                                detail = artistData.musicSize.toString(),
                                onClick = { navController.navigate("${Screen.ArtistSongs.route}/$id") },
                                showTopSeparator = false,
                            )
                            IosListRow(
                                title = stringResource(R.string.artist_info_albums),
                                detail = artistData.albumSize.toString(),
                                onClick = { navController.navigate("${Screen.ArtistAlbums.route}/$id") },
                            )
                            IosListRow(
                                title = stringResource(R.string.artist_info_music_videos),
                                detail = artistData.mvSize.toString(),
                            )
                        }
                    }

                    val identities = (artistData.identifyTag.orEmpty() + artistData.identities.orEmpty())
                        .map(String::trim)
                        .filter(String::isNotBlank)
                        .distinct()
                    if (identities.isNotEmpty()) {
                        item(key = "identities") {
                            ArtistInfoSection(stringResource(R.string.artist_info_identities)) {
                                identities.forEachIndexed { index, identity ->
                                    IosListRow(
                                        title = identity,
                                        showTopSeparator = index != 0,
                                    )
                                }
                            }
                        }
                    }

                    val expertise = data.secondaryExpertIdentiy.orEmpty()
                        .filter { it.expertIdentiyName.isNotBlank() }
                    if (expertise.isNotEmpty()) {
                        item(key = "expertise") {
                            ArtistInfoSection(stringResource(R.string.artist_info_expertise)) {
                                expertise.forEachIndexed { index, expert ->
                                    IosListRow(
                                        title = expert.expertIdentiyName.trim(),
                                        detail = expert.expertIdentiyCount.toString(),
                                        showTopSeparator = index != 0,
                                    )
                                }
                            }
                        }
                    }

                    val translatedNames = artistData.transNames.orEmpty()
                        .map(String::trim)
                        .filter(String::isNotBlank)
                        .distinct()
                    if (translatedNames.isNotEmpty()) {
                        item(key = "translated-names") {
                            ArtistInfoSection(stringResource(R.string.artist_info_translated_names)) {
                                ArtistInfoTextList(translatedNames)
                            }
                        }
                    }

                    val aliases = artistData.alias.orEmpty()
                        .filterIsInstance<String>()
                        .map(String::trim)
                        .filter(String::isNotBlank)
                        .distinct()
                    if (aliases.isNotEmpty()) {
                        item(key = "aliases") {
                            ArtistInfoSection(stringResource(R.string.artist_info_aliases)) {
                                ArtistInfoTextList(aliases)
                            }
                        }
                    }

                    if (artistData.briefDesc.isNotBlank()) {
                        item(key = "biography") {
                            ArtistInfoSection(stringResource(R.string.artist_info_biography)) {
                                Column(
                                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    artistData.briefDesc.trim().split(Regex("\\n\\s*\\n|\\r?\\n"))
                                        .filter(String::isNotBlank)
                                        .forEach { paragraph ->
                                            Text(
                                                text = paragraph.trim(),
                                                style = IosTypography.body,
                                                lineHeight = 26.sp,
                                                color = LocalGlassColors.current.content,
                                            )
                                        }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ArtistInfoSection(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            style = IosTypography.headline,
            fontWeight = FontWeight.SemiBold,
            color = LocalGlassColors.current.content,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        IosGroupedList { content() }
    }
}

@Composable
private fun ArtistInfoTextList(values: List<String>) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        values.forEach { value ->
            Text(
                text = value,
                style = IosTypography.body,
                lineHeight = 24.sp,
                color = LocalGlassColors.current.content,
            )
        }
    }
}
