package com.ljyh.mei.ui.screen

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import androidx.compose.ui.platform.LocalContext
import com.ljyh.mei.di.AppDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.net.URLDecoder
import com.ljyh.mei.ui.screen.about.AboutScreen
import com.ljyh.mei.ui.screen.album.AlbumDetailScreen
import com.ljyh.mei.ui.screen.history.HistoryScreen
import com.ljyh.mei.ui.screen.local.LocalMusicScreen
import com.ljyh.mei.ui.screen.local.LocalSongListScreen
import com.ljyh.mei.ui.screen.main.home.HomeHubScreen
import com.ljyh.mei.ui.screen.main.library.LibraryScreen
import com.ljyh.mei.ui.screen.playlist.EveryDay
import com.ljyh.mei.ui.screen.playlist.PlaylistScreen
import com.ljyh.mei.ui.screen.search.SearchResultScreen
import com.ljyh.mei.ui.screen.setting.AppearanceSettings
import com.ljyh.mei.ui.screen.artist.ArtistScreen
import com.ljyh.mei.ui.screen.main.findmusic.FindMusicScreen
import com.ljyh.mei.ui.screen.setting.ContentsSetting
import com.ljyh.mei.ui.screen.setting.DownloadManageScreen
import com.ljyh.mei.ui.screen.setting.DownloadSetting
import com.ljyh.mei.ui.screen.setting.StorageManagementScreen
import com.ljyh.mei.ui.screen.setting.GeneralSettings
import com.ljyh.mei.ui.screen.setting.PlaySetting
import com.ljyh.mei.ui.screen.setting.EqualizerSettings
import com.ljyh.mei.ui.screen.setting.LyricsSettings
import com.ljyh.mei.ui.screen.setting.SettingScreen
import com.ljyh.mei.ui.screen.log.LogScreen
import com.ljyh.mei.ui.screen.comment.CommentScreen
import com.ljyh.mei.ui.screen.cloud.CloudMusicScreen
import com.ljyh.mei.ui.screen.podcast.PodcastDetailScreen
import com.ljyh.mei.ui.screen.podcast.PodcastScreen
import com.ljyh.mei.ui.screen.search.SearchLandingScreen
import com.ljyh.mei.ui.screen.social.ConversationScreen
import com.ljyh.mei.ui.screen.social.ConversationsScreen
import com.ljyh.mei.ui.screen.social.MessageContactsScreen
import com.ljyh.mei.ui.screen.listentogether.ListenTogetherScreen
import com.ljyh.mei.ui.screen.recognition.SongRecognitionScreen
import com.ljyh.mei.ui.screen.account.NeteaseLoginScreen
import com.ljyh.mei.ui.screen.account.PcQrLoginScreen
import com.ljyh.mei.ui.screen.account.AccountHomeScreen
import com.ljyh.mei.ui.screen.account.ListeningRankScreen
import com.ljyh.mei.ui.screen.song.SongWikiScreen


@OptIn(ExperimentalMaterial3Api::class)
fun NavGraphBuilder.navigationBuilder(
    navController: NavHostController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    composable(Screen.Home.route) {
        HomeHubScreen()
    }

    composable(Screen.Library.route) {
        LibraryScreen()
    }

    composable(Screen.FindMusic.route) {
        FindMusicScreen()
    }

    composable(
        route = "${Screen.PlaylistCategory.route}/{category}/{title}",
        arguments = listOf(
            navArgument("category") { type = NavType.StringType },
            navArgument("title") { type = NavType.StringType },
        ),
    ) {
        val category = it.arguments?.getString("category").orEmpty()
        val title = it.arguments?.getString("title").orEmpty()
        FindMusicScreen(initialCategory = category, titleOverride = title)
    }

    composable(Screen.Podcasts.route) {
        PodcastScreen()
    }

    composable(Screen.CloudMusic.route) {
        CloudMusicScreen()
    }

    composable(Screen.Search.route) {
        SearchLandingScreen()
    }

    composable(Screen.PrivateMessages.route) {
        ConversationsScreen()
    }

    composable(Screen.MessageContacts.route) {
        MessageContactsScreen()
    }

    composable(
        route = "${Screen.PrivateConversation.route}/{userId}",
        arguments = listOf(navArgument("userId") { type = NavType.LongType }),
    ) {
        ConversationScreen(it.arguments!!.getLong("userId"))
    }

    composable(Screen.ListenTogether.route) {
        ListenTogetherScreen()
    }

    composable(Screen.SongRecognition.route) {
        SongRecognitionScreen()
    }

    composable(Screen.NeteaseLogin.route) {
        NeteaseLoginScreen()
    }

    composable(Screen.PcQrLogin.route) {
        PcQrLoginScreen()
    }

    composable(Screen.AccountHome.route) {
        AccountHomeScreen()
    }

    composable(
        route = "${Screen.AccountListeningRank.route}/{userId}",
        arguments = listOf(navArgument("userId") { type = NavType.LongType }),
    ) {
        ListeningRankScreen(it.arguments!!.getLong("userId"))
    }

    composable(
        route = "${Screen.PodcastDetail.route}/{id}",
        arguments = listOf(navArgument("id") { type = NavType.LongType }),
    ) {
        PodcastDetailScreen(it.arguments!!.getLong("id"))
    }

    composable(Screen.Test.route) {
        Test()
    }

    composable(Screen.Setting.route) {
        SettingScreen(scrollBehavior)
    }

    composable(Screen.AppearanceSettings.route) {
        AppearanceSettings(scrollBehavior)
    }

    composable(Screen.GeneralSettings.route) {
        GeneralSettings()
    }

    composable(Screen.LyricsSettings.route) {
        LyricsSettings()
    }

    composable(Screen.ContentSettings.route) {
        ContentsSetting(scrollBehavior)
    }
    composable(Screen.PlaySettings.route){
        PlaySetting(scrollBehavior)
    }
    composable(Screen.EqualizerSettings.route) {
        EqualizerSettings()
    }

    composable(Screen.DownloadSettings.route) {
        DownloadSetting(scrollBehavior)
    }

    composable(Screen.StorageManagement.route) {
        StorageManagementScreen()
    }

    composable(Screen.DownloadManage.route) {
        DownloadManageScreen(scrollBehavior)
    }

    composable(Screen.LocalMusic.route) {
        LocalMusicScreen(scrollBehavior)
    }

    composable(
        route = "${Screen.LocalSongList.route}/{type}/{name}",
        arguments = listOf(
            navArgument("type") { type = NavType.StringType },
            navArgument("name") { type = NavType.StringType }
        )
    ) {
        val type = it.arguments?.getString("type") ?: "all"
        val name = it.arguments?.getString("name") ?: ""
        val context = LocalContext.current

        val filterValue: String
        val title: String

        when (type) {
            "folder" -> {
                filterValue = URLDecoder.decode(name, "UTF-8")
                title = filterValue.substringAfterLast('/').ifEmpty { filterValue.substringAfterLast(":") }
            }
            "artist" -> {
                filterValue = name
                title = name
            }
            "album" -> {
                filterValue = name
                title = name
            }
            else -> {
                filterValue = name
                title = "全部歌曲"
            }
        }

        LocalSongListScreen(
            filterType = when (type) {
                "folder" -> "folder"
                else -> type
            },
            filterValue = filterValue,
            title = title,
            scrollBehavior = scrollBehavior
        )
    }

    composable(Screen.EveryDay.route){
        EveryDay()
    }
    composable(Screen.About.route) {
        AboutScreen()
    }
    composable(Screen.Log.route) {
        LogScreen()
    }
    composable(
        route = "${Screen.SearchResult.route}/{query}/{type}",
        arguments = listOf(
            navArgument("query") {
                type = NavType.StringType
            }
            ,
            navArgument("type") {
                type = NavType.IntType
            }
        ),
    ) {
        SearchResultScreen(
            query = android.net.Uri.decode(it.arguments!!.getString("query")!!),
            type= it.arguments!!.getInt("type"),
        )
    }
    composable(
        route = "${Screen.PlayList.route}/{id}",
        arguments = listOf(
            navArgument("id") {
                type = NavType.LongType
            }
        )
    ) {
        PlaylistScreen(id = it.arguments!!.getLong("id"))
    }


    composable(
        route = "${Screen.Album.route}/{id}",
        arguments = listOf(
            navArgument("id") {
                type = NavType.LongType
            }
        )
    ) {
        AlbumDetailScreen(id = it.arguments!!.getLong("id"))
    }
    
    composable(
        route = "${Screen.Artist.route}/{id}",
        arguments = listOf(
            navArgument("id") {
                type = NavType.StringType
            }
        )
    ) {
        ArtistScreen(id = it.arguments!!.getString("id")!!)
    }

    composable(Screen.History.route) {
        HistoryScreen()
    }

    composable(
        route = "${Screen.Comment.route}/{songId}",
        arguments = listOf(
            navArgument("songId") { type = NavType.StringType }
        )
    ) {
        CommentScreen(
            songId = it.arguments!!.getString("songId")!!
        )
    }

    composable(
        route = "${Screen.SongWiki.route}/{songId}",
        arguments = listOf(navArgument("songId") { type = NavType.LongType }),
    ) {
        SongWikiScreen(songId = it.arguments!!.getLong("songId"))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun navigationEntry(
    route: String,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    when {
        route == Screen.Home.route -> HomeHubScreen()
        route == Screen.Library.route -> LibraryScreen()
        route == Screen.FindMusic.route -> FindMusicScreen()
        route.startsWith("${Screen.PlaylistCategory.route}/") -> {
            val arguments = route.substringAfter("${Screen.PlaylistCategory.route}/")
                .split('/', limit = 2)
            if (arguments.size == 2) {
                val category = URLDecoder.decode(arguments[0], "UTF-8")
                val title = URLDecoder.decode(arguments[1], "UTF-8")
                FindMusicScreen(initialCategory = category, titleOverride = title)
            }
        }
        route == Screen.Podcasts.route -> PodcastScreen()
        route == Screen.CloudMusic.route -> CloudMusicScreen()
        route == Screen.Search.route -> SearchLandingScreen()
        route == Screen.PrivateMessages.route -> ConversationsScreen()
        route == Screen.MessageContacts.route -> MessageContactsScreen()
        route == Screen.ListenTogether.route -> ListenTogetherScreen()
        route == Screen.SongRecognition.route -> SongRecognitionScreen()
        route == Screen.NeteaseLogin.route -> NeteaseLoginScreen()
        route == Screen.PcQrLogin.route -> PcQrLoginScreen()
        route == Screen.AccountHome.route -> AccountHomeScreen()
        route == Screen.Test.route -> Test()
        route == Screen.Setting.route -> SettingScreen(scrollBehavior)
        route == Screen.AppearanceSettings.route -> AppearanceSettings(scrollBehavior)
        route == Screen.GeneralSettings.route -> GeneralSettings()
        route == Screen.LyricsSettings.route -> LyricsSettings()
        route == Screen.ContentSettings.route -> ContentsSetting(scrollBehavior)
        route == Screen.PlaySettings.route -> PlaySetting(scrollBehavior)
        route == Screen.EqualizerSettings.route -> EqualizerSettings()
        route == Screen.DownloadSettings.route -> DownloadSetting(scrollBehavior)
        route == Screen.StorageManagement.route -> StorageManagementScreen()
        route == Screen.DownloadManage.route -> DownloadManageScreen(scrollBehavior)
        route == Screen.LocalMusic.route -> LocalMusicScreen(scrollBehavior)
        route == Screen.EveryDay.route -> EveryDay()
        route == Screen.About.route -> AboutScreen()
        route == Screen.Log.route -> LogScreen()
        route == Screen.History.route -> HistoryScreen()
        route.startsWith("${Screen.PrivateConversation.route}/") -> {
            route.substringAfter("${Screen.PrivateConversation.route}/").toLongOrNull()
                ?.let { userId -> ConversationScreen(userId) }
        }
        route.startsWith("${Screen.AccountListeningRank.route}/") -> {
            route.substringAfter("${Screen.AccountListeningRank.route}/").toLongOrNull()
                ?.let { userId -> ListeningRankScreen(userId) }
        }
        route.startsWith("${Screen.PodcastDetail.route}/") -> {
            route.substringAfter("${Screen.PodcastDetail.route}/").toLongOrNull()
                ?.let { id -> PodcastDetailScreen(id) }
        }
        route.startsWith("${Screen.LocalSongList.route}/") -> {
            val args = route.substringAfter("${Screen.LocalSongList.route}/").split('/', limit = 2)
            if (args.size == 2) {
                val type = args[0]
                val name = args[1]
                val filterValue: String
                val title: String
                when (type) {
                    "folder" -> {
                        filterValue = URLDecoder.decode(name, "UTF-8")
                        title = filterValue.substringAfterLast('/').ifEmpty {
                            filterValue.substringAfterLast(":")
                        }
                    }
                    "artist", "album" -> {
                        filterValue = name
                        title = name
                    }
                    else -> {
                        filterValue = name
                        title = stringResource(com.ljyh.mei.R.string.local_music_all_songs)
                    }
                }
                LocalSongListScreen(
                    filterType = if (type == "folder") "folder" else type,
                    filterValue = filterValue,
                    title = title,
                    scrollBehavior = scrollBehavior,
                )
            }
        }
        route.startsWith("${Screen.SearchResult.route}/") -> {
            val args = route.substringAfter("${Screen.SearchResult.route}/").split('/', limit = 2)
            if (args.size == 2) {
                SearchResultScreen(
                    query = android.net.Uri.decode(args[0]),
                    type = args[1].toIntOrNull() ?: 1,
                )
            }
        }
        route.startsWith("${Screen.PlayList.route}/") -> {
            route.substringAfter("${Screen.PlayList.route}/").toLongOrNull()
                ?.let { PlaylistScreen(id = it) }
        }
        route.startsWith("${Screen.Album.route}/") -> {
            route.substringAfter("${Screen.Album.route}/").toLongOrNull()
                ?.let { AlbumDetailScreen(id = it) }
        }
        route.startsWith("${Screen.Artist.route}/") -> {
            route.substringAfter("${Screen.Artist.route}/")
                .let { ArtistScreen(id = it) }
        }
        route.startsWith("${Screen.Comment.route}/") -> {
            route.substringAfter("${Screen.Comment.route}/")
                .let { CommentScreen(songId = it) }
        }
        route.startsWith("${Screen.SongWiki.route}/") -> {
            route.substringAfter("${Screen.SongWiki.route}/").toLongOrNull()
                ?.let { SongWikiScreen(songId = it) }
        }
        else -> error("Unknown Mei navigation route: $route")
    }
}
