package com.ljyh.mei.ui.screen.main.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.ljyh.mei.ui.navigation.MeiNavigator
import com.ljyh.mei.constants.CookieKey
import com.ljyh.mei.constants.UserAvatarUrlKey
import com.ljyh.mei.constants.UserIdKey
import com.ljyh.mei.constants.UserNicknameKey
import com.ljyh.mei.constants.UserPhotoKey
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.ui.local.LocalNavController
import com.ljyh.mei.ui.local.LocalPlayerAwareWindowInsets
import com.ljyh.mei.ui.model.toAlbum
import com.ljyh.mei.ui.screen.Screen
import com.ljyh.mei.ui.component.GlobalProfileAvatarButton
import com.ljyh.mei.ui.glass.IosPinnedListPage
import com.ljyh.mei.ui.screen.main.library.component.LibraryMobileLayout
import com.ljyh.mei.ui.screen.main.library.component.PhotoPickerSheet
import com.ljyh.mei.ui.navigation.LibraryPage
import com.ljyh.mei.utils.rememberPreference

@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel = hiltViewModel(),
    isNavigationTab: Boolean = false,
) {
    val navController = LocalNavController.current
    val account by viewModel.account.collectAsState()
    val photoAlbum by viewModel.photoAlbum.collectAsState()
    val localPlaylists by viewModel.localPlaylists.collectAsState()
    val albumList by viewModel.albumList.collectAsState()
    val userSubcount by viewModel.userSubcount.collectAsState()
    val networkPlaylists by viewModel.networkPlaylistsState.collectAsState()
    val likedSongs by viewModel.likedSongs.collectAsState()
    val likedSongsLoading by viewModel.likedSongsLoading.collectAsState()

    // Preferences
    val (userId, setUserId) = rememberPreference(UserIdKey, "")
    val (_, setUserNickname) = rememberPreference(UserNicknameKey, "")
    val (_, setUserAvatarUrl) = rememberPreference(UserAvatarUrlKey, "")
    val (userPhoto, setUserPhoto) = rememberPreference(UserPhotoKey, "")
    val cookie by rememberPreference(CookieKey, defaultValue = "")

    // State
    var showPhotoPicker by remember { mutableStateOf(false) }
    var selectedPage by remember { mutableStateOf(LibraryPage.Songs) }
    var subPlaylistCount by remember { mutableIntStateOf(0) }

    val likedPlaylistId = (networkPlaylists as? Resource.Success)?.data?.playlist?.firstOrNull()?.id
    val visiblePlaylists = remember(localPlaylists, likedPlaylistId) {
        localPlaylists.filterNot { it.id == likedPlaylistId?.toString() }
    }
    val (createdPlaylists, collectedPlaylists) = remember(visiblePlaylists, userId) {
        if (userId.isEmpty()) Pair(emptyList(), emptyList())
        else {
            val (created, collected) = visiblePlaylists.partition { it.author == userId }
            fun sorted(playlists: List<com.ljyh.mei.data.model.room.Playlist>): List<com.ljyh.mei.data.model.room.Playlist> {
                val maxLocalPlayCount = playlists.maxOfOrNull { it.localPlayCount } ?: 1
                val maxServerPlayCount = playlists.maxOfOrNull { it.playCount } ?: 1L
                val now = System.currentTimeMillis()
                return playlists.sortedByDescending {
                    it.sortScore(maxLocalPlayCount, maxServerPlayCount, now)
                }
            }
            Pair(sorted(created), sorted(collected))
        }
    }

    // --- 数据同步逻辑 ---
    LaunchedEffect(userId) {
        if (userId.isNotEmpty()) {
            viewModel.syncUserPlaylists(userId)
            viewModel.getPhotoAlbum(userId)
            viewModel.getAlbumList()
            viewModel.getUserSubcount()
        }
    }

    LaunchedEffect(photoAlbum) {
        if (userPhoto.isEmpty() && photoAlbum is Resource.Success) {
            (photoAlbum as Resource.Success).data.data.records.firstOrNull()?.imageUrl?.let {
                setUserPhoto(it)
            }
        }
    }
    LaunchedEffect(cookie, account) {
        if (cookie.isNotEmpty() && account !is Resource.Success) viewModel.getUserAccount()
    }
    LaunchedEffect(account) {
        (account as? Resource.Success)
            ?.data?.profile
            ?.let { profile ->
                setUserId(profile.userId.toString())
                setUserNickname(profile.nickname)
                setUserAvatarUrl(profile.avatarUrl)
            }
    }

    LaunchedEffect(userSubcount) {
        if (userSubcount is Resource.Success) {

        }
    }

    LaunchedEffect(likedPlaylistId) {
        likedPlaylistId?.let(viewModel::getLikedSongs)
    }

    LaunchedEffect(subPlaylistCount) {
        if (userId.isNotEmpty() && localPlaylists.size != subPlaylistCount && subPlaylistCount != 0)
            viewModel.syncUserPlaylists(userId, subPlaylistCount)
    }

    Box(modifier = Modifier.fillMaxSize()) {

        if (userId.isNotEmpty()) {
            LibraryMobileLayout(
                userPhoto = userPhoto,
                isNavigationTab = isNavigationTab,
                selectedPage = selectedPage,
                onPageSelect = { selectedPage = it },
                createdPlaylists = createdPlaylists,
                collectedPlaylists = collectedPlaylists,
                albums = if (albumList is Resource.Success) (albumList as Resource.Success).data.data.map { it.toAlbum() } else emptyList(),
                onPlaylistClick = { id->
                    Screen.PlayList.navigate(navController) { addPath(id) }
                },
                onAlbumClick = { id->
                    Screen.Album.navigate(navController) { addPath(id) }
                },
                userId = userId,
                likedSongs = likedSongs,
                // Keep the spinner up until the liked-playlist id is known and the
                // first detail request finishes; otherwise the empty state flashes.
                likedSongsLoading = networkPlaylists is Resource.Loading ||
                    (likedPlaylistId != null && likedSongsLoading),
            )

            if (showPhotoPicker) {
                PhotoPickerSheet(
                    photoAlbum = photoAlbum,
                    onSelect = { setUserPhoto(it); showPhotoPicker = false },
                    onDismiss = { showPhotoPicker = false }
                )
            }
        } else {
            // 未登录逻辑
            EmptyLoginState(navController, isNavigationTab)
        }
    }
}

@Composable
fun EmptyLoginState(
    navController: MeiNavigator,
    isNavigationTab: Boolean = false,
) {
    val insets = LocalPlayerAwareWindowInsets.current.asPaddingValues()
    IosPinnedListPage(
        title = stringResource(com.ljyh.mei.R.string.app_tab_library),
        bottomPadding = insets.calculateBottomPadding(),
        onNavigateBack = if (isNavigationTab) null else ({ navController.navigateUp() }),
        actions = {
            if (isNavigationTab) GlobalProfileAvatarButton()
        },
    ) {
        item {
            Box(
                modifier = Modifier.fillParentMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                com.ljyh.mei.ui.glass.GlassCard(
                    modifier = Modifier.padding(24.dp),
                    onClick = { Screen.NeteaseLogin.navigate(navController) },
                ) {
                    androidx.compose.foundation.layout.Column(
                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        com.ljyh.mei.ui.glass.SfIcon("person.crop.circle", null, size = 42.dp)
                        Text(
                            stringResource(com.ljyh.mei.R.string.library_sign_in),
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                }
            }
        }
    }
}
