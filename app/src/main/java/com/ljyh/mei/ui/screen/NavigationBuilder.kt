package com.ljyh.mei.ui.screen

import com.ljyh.mei.source.api.*
import com.ljyh.mei.ui.screen.source.SourceArtistCatalogScreen
import com.ljyh.mei.ui.screen.source.SourceContentScreen
import com.ljyh.mei.ui.screen.source.SourceAccountScreen
import com.ljyh.mei.ui.screen.source.SourcePlaylistEditorScreen
import com.ljyh.mei.ui.screen.source.SourcePlaylistPickerScreen

import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
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
import com.ljyh.mei.ui.screen.history.HistoryScreen
import com.ljyh.mei.ui.screen.local.LocalMusicScreen
import com.ljyh.mei.ui.screen.local.LocalSongListScreen
import com.ljyh.mei.ui.screen.main.home.HomeHubScreen
import com.ljyh.mei.ui.navigation.LibraryPage
import com.ljyh.mei.ui.screen.main.library.LibraryScreen
import com.ljyh.mei.ui.screen.playlist.EveryDay
import com.ljyh.mei.ui.screen.search.SearchResultScreen
import com.ljyh.mei.ui.screen.setting.AppearanceSettings
import com.ljyh.mei.ui.screen.artist.ArtistInfoScreen
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
import com.ljyh.mei.ui.screen.account.NeteaseWebLoginScreen
import com.ljyh.mei.ui.screen.account.PcQrLoginScreen
import com.ljyh.mei.ui.screen.account.ListeningStatsScreen
import com.ljyh.mei.ui.screen.account.AccountHomeScreen
import com.ljyh.mei.ui.screen.account.OfficialNeteasePage
import com.ljyh.mei.ui.screen.account.OfficialNeteasePageScreen
import com.ljyh.mei.ui.screen.account.AccountSecurityScreen
import com.ljyh.mei.ui.screen.account.MusicianAccountScreen
import com.ljyh.mei.ui.screen.account.AccountBindingsScreen
import com.ljyh.mei.ui.screen.account.AccountDevicesScreen
import com.ljyh.mei.ui.screen.account.AccountDeviceTab
import com.ljyh.mei.ui.screen.account.AccountConnectionsScreen
import com.ljyh.mei.data.model.melox.AccountConnectionType
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

    composable(
        route = "${Screen.LibraryCategory.route}/{page}",
        arguments = listOf(navArgument("page") { type = NavType.StringType }),
    ) { entry ->
        LibraryPage.entries.firstOrNull { it.name == entry.arguments?.getString("page") }?.let {
            LibraryScreen(category = it)
        }
    }

    sourceScoped(Screen.FindMusic) { providerId ->
        FindMusicScreen(providerId = providerId)
    }

    composable(
        route = "${Screen.PlaylistCategory.route}/{category}/{title}?providerId={providerId}",
        arguments = listOf(
            navArgument("category") { type = NavType.StringType },
            navArgument("title") { type = NavType.StringType },
            navArgument("providerId") { type = NavType.StringType; nullable = true; defaultValue = null },
        ),
    ) {
        val category = it.arguments?.getString("category").orEmpty()
        val title = it.arguments?.getString("title").orEmpty()
        FindMusicScreen(initialCategory = category, titleOverride = title, providerId = it.arguments?.getString("providerId"))
    }

    sourceScoped(Screen.Podcasts) {
        PodcastScreen()
    }

    sourceScoped(Screen.CloudMusic) {
        CloudMusicScreen()
    }

    composable(Screen.Search.route) {
        SearchLandingScreen()
    }

    composable("${Screen.PrivateMessages.route}?providerId={providerId}", arguments = listOf(navArgument("providerId") { type = NavType.StringType; nullable = true; defaultValue = null })) {
        ConversationsScreen(providerId = it.arguments?.getString("providerId"))
    }

    composable("${Screen.OfficialMessages.route}?providerId={providerId}", arguments = listOf(navArgument("providerId") { type = NavType.StringType; nullable = true; defaultValue = null })) {
        ConversationsScreen(officialMessages = true, providerId = it.arguments?.getString("providerId"))
    }

    composable("${Screen.MessageContacts.route}?providerId={providerId}", arguments = listOf(navArgument("providerId") { type = NavType.StringType; nullable = true; defaultValue = null })) {
        MessageContactsScreen(providerId = it.arguments?.getString("providerId"))
    }

    composable(
        route = "${Screen.PrivateConversation.route}/{userId}?providerId={providerId}",
        arguments = listOf(navArgument("userId") { type = NavType.StringType }, navArgument("providerId") { type = NavType.StringType; nullable = true; defaultValue = null }),
    ) {
        ConversationScreen(it.arguments!!.getString("userId").orEmpty(), providerId = it.arguments?.getString("providerId"))
    }

    composable("${Screen.ListenTogether.route}?providerId={providerId}", arguments = listOf(navArgument("providerId") { type = NavType.StringType; nullable = true; defaultValue = null })) {
        ListenTogetherScreen(providerId = it.arguments?.getString("providerId"))
    }

    composable("${Screen.SongRecognition.route}?providerId={providerId}", arguments = listOf(navArgument("providerId") { type = NavType.StringType; nullable = true; defaultValue = null })) {
        SongRecognitionScreen(providerId = it.arguments?.getString("providerId"))
    }

    sourceScoped(Screen.NeteaseLogin) {
        NeteaseLoginScreen()
    }
    sourceScoped(Screen.NeteaseWebLogin) {
        NeteaseWebLoginScreen()
    }

    sourceScoped(Screen.PcQrLogin) {
        PcQrLoginScreen()
    }

    sourceScoped(Screen.AccountHome) {
        AccountHomeScreen()
    }
    sourceScoped(Screen.NeteaseMusician) {
        MusicianAccountScreen()
    }
    sourceScoped(Screen.AccountSecurity) {
        AccountSecurityScreen()
    }
    sourceScoped(Screen.AccountBindings) { AccountBindingsScreen() }
    sourceScoped(Screen.AccountDevices) { AccountDevicesScreen() }
    sourceScoped(Screen.AccountAuthorizations) { AccountDevicesScreen(initialTab = AccountDeviceTab.Authorizations) }
    composable(
        route = "${Screen.AccountWeb.route}/{page}?providerId={providerId}",
        arguments = listOf(navArgument("providerId") { type = NavType.StringType; nullable = true; defaultValue = null }, navArgument("page") { type = NavType.StringType }),
    ) { entry ->
        OfficialNeteasePage.entries.firstOrNull { it.name == entry.arguments?.getString("page") }
            ?.let { OfficialNeteasePageScreen(it) }
    }

    sourceScoped(Screen.ListeningStats) {
        ListeningStatsScreen()
    }

    composable(
        route = "${Screen.AccountConnections.route}/{userId}/{type}?providerId={providerId}",
        arguments = listOf(navArgument("providerId") { type = NavType.StringType; nullable = true; defaultValue = null },
            navArgument("userId") { type = NavType.StringType },
            navArgument("type") { type = NavType.StringType },
        ),
    ) { entry ->
        AccountConnectionType.entries.firstOrNull { it.name == entry.arguments?.getString("type") }?.let {
            AccountConnectionsScreen(entry.arguments!!.getString("userId").orEmpty(), it)
        }
    }

    composable(
        route = "${Screen.AccountListeningRank.route}/{userId}?providerId={providerId}",
        arguments = listOf(navArgument("providerId") { type = NavType.StringType; nullable = true; defaultValue = null }, navArgument("userId") { type = NavType.StringType }),
    ) {
        ListeningRankScreen(it.arguments!!.getString("userId").orEmpty())
    }

    composable(
        route = "${Screen.PodcastDetail.route}/{id}?providerId={providerId}",
        arguments = listOf(navArgument("providerId") { type = NavType.StringType; nullable = true; defaultValue = null }, navArgument("id") { type = NavType.StringType }),
    ) {
        PodcastDetailScreen(it.arguments!!.getString("id").orEmpty())
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
    composable(Screen.SourceManagement.route) {
        com.ljyh.mei.ui.screen.source.SourceManagementScreen()
    }
    composable(Screen.SourceMarket.route) {
        com.ljyh.mei.ui.screen.source.SourceManagementScreen(market = true)
    }
    composable(
        route = "${Screen.SourcePluginSettings.route}/{pluginId}",
        arguments = listOf(navArgument("pluginId") { type = NavType.StringType }),
    ) { entry ->
        com.ljyh.mei.ui.screen.source.SourceManagementScreen(
            pluginId = entry.arguments?.getString("pluginId").orEmpty(),
        )
    }

    composable("${Screen.SourceContent.route}/{providerId}/{kind}/{contentId}", arguments = listOf(
        navArgument("providerId") { type = NavType.StringType }, navArgument("kind") { type = NavType.StringType },
        navArgument("contentId") { type = NavType.StringType },
    )) { entry ->
        val ref = sourceContentRef(entry.arguments?.getString("providerId").orEmpty(), entry.arguments?.getString("kind").orEmpty(), entry.arguments?.getString("contentId").orEmpty())
        if (ref != null) SourceContentScreen(ref) else MissingSourceContentPage()
    }
    for ((screen, albums) in listOf(Screen.SourceArtistSongs to false, Screen.SourceArtistAlbums to true)) {
        composable("${screen.route}/{providerId}/{artistId}", arguments = listOf(
            navArgument("providerId") { type = NavType.StringType }, navArgument("artistId") { type = NavType.StringType },
        )) { entry ->
            val ref = sourceContentRef(entry.arguments?.getString("providerId").orEmpty(), "artist",
                entry.arguments?.getString("artistId").orEmpty()) as? ArtistRef
            if (ref != null) SourceArtistCatalogScreen(ref, albums) else MissingSourceContentPage()
        }
    }
    composable("${Screen.SourceAccount.route}/{providerId}", arguments = listOf(navArgument("providerId") { type = NavType.StringType })) {
        SourceAccountScreen(it.arguments?.getString("providerId").orEmpty())
    }
    composable("${Screen.SourcePlaylistCreate.route}/{providerId}", arguments = listOf(navArgument("providerId") { type = NavType.StringType })) {
        SourcePlaylistEditorScreen(it.arguments?.getString("providerId").orEmpty())
    }
    composable("${Screen.SourcePlaylistEdit.route}/{providerId}/{playlistId}", arguments = listOf(
        navArgument("providerId") { type = NavType.StringType }, navArgument("playlistId") { type = NavType.StringType },
    )) { SourcePlaylistEditorScreen(it.arguments?.getString("providerId").orEmpty(), it.arguments?.getString("playlistId")) }
    composable("${Screen.SourcePlaylistPicker.route}/{track}", arguments = listOf(navArgument("track") { type = NavType.StringType })) {
        val ref = TrackRefCodec.decode(it.arguments?.getString("track").orEmpty())
        if (ref != null) SourcePlaylistPickerScreen(ref) else MissingSourceContentPage()
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

    sourceScoped(Screen.EveryDay){
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
        SourceContentScreen(PlaylistRef(ProviderIds.NETEASE, it.arguments!!.getLong("id").toString()))
    }


    composable(
        route = "${Screen.Album.route}/{id}",
        arguments = listOf(
            navArgument("id") {
                type = NavType.LongType
            }
        )
    ) {
        SourceContentScreen(AlbumRef(ProviderIds.NETEASE, it.arguments!!.getLong("id").toString()))
    }

    composable(
        route = "${Screen.Artist.route}/{id}",
        arguments = listOf(
            navArgument("id") {
                type = NavType.StringType
            }
        )
    ) {
        SourceContentScreen(ArtistRef(ProviderIds.NETEASE, it.arguments!!.getString("id")!!))
    }

    composable(
        route = "${Screen.ArtistInfo.route}/{id}?providerId={providerId}",
        arguments = listOf(navArgument("id") { type = NavType.StringType },
            navArgument("providerId") { type = NavType.StringType; defaultValue = ProviderIds.NETEASE }),
    ) {
        ArtistInfoScreen(id = it.arguments!!.getString("id")!!,
            providerId = it.arguments?.getString("providerId") ?: ProviderIds.NETEASE)
    }

    composable(
        route = "${Screen.ArtistSongs.route}/{id}",
        arguments = listOf(navArgument("id") { type = NavType.StringType }),
    ) {
        SourceArtistCatalogScreen(ArtistRef(ProviderIds.NETEASE, it.arguments!!.getString("id")!!), albums = false)
    }

    composable(
        route = "${Screen.ArtistAlbums.route}/{id}",
        arguments = listOf(navArgument("id") { type = NavType.StringType }),
    ) {
        SourceArtistCatalogScreen(ArtistRef(ProviderIds.NETEASE, it.arguments!!.getString("id")!!), albums = true)
    }

    sourceScoped(Screen.History) {
        HistoryScreen()
    }

    composable(
        route = "${Screen.Comment.route}/{songId}?providerId={providerId}",
        arguments = listOf(navArgument("providerId") { type = NavType.StringType; nullable = true; defaultValue = null },
            navArgument("songId") { type = NavType.StringType }
        )
    ) {
        CommentScreen(
            songId = it.arguments!!.getString("songId")!!
        )
    }

    composable(
        route = "${Screen.SongWiki.route}/{songId}?providerId={providerId}",
        arguments = listOf(navArgument("providerId") { type = NavType.StringType; nullable = true; defaultValue = null }, navArgument("songId") { type = NavType.StringType }),
    ) {
        SongWikiScreen(songId = it.arguments!!.getString("songId").orEmpty())
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun navigationEntry(
    route: String,
    scrollBehavior: TopAppBarScrollBehavior,
    isNavigationTab: Boolean = false,
) {
    val path = route.substringBefore('?')
    when {
        path == Screen.Home.route -> HomeHubScreen()
        path == Screen.Library.route -> LibraryScreen(
            isNavigationTab = isNavigationTab,
        )
        path.startsWith("${Screen.LibraryCategory.route}/") -> {
            LibraryPage.entries.firstOrNull {
                it.name == path.substringAfter("${Screen.LibraryCategory.route}/")
            }?.let { LibraryScreen(category = it) }
        }
        path == Screen.FindMusic.route -> FindMusicScreen(isNavigationTab = isNavigationTab, providerId = sourceFeatureRouteProvider(route))
        path.startsWith("${Screen.PlaylistCategory.route}/") -> {
            val arguments = path.substringAfter("${Screen.PlaylistCategory.route}/")
                .split('/', limit = 2)
            if (arguments.size == 2) {
                val category = URLDecoder.decode(arguments[0], "UTF-8")
                val title = URLDecoder.decode(arguments[1], "UTF-8")
                FindMusicScreen(initialCategory = category, titleOverride = title, providerId = sourceFeatureRouteProvider(route))
            }
        }
        path == Screen.Podcasts.route -> PodcastScreen(isNavigationTab = isNavigationTab)
        path == Screen.CloudMusic.route -> CloudMusicScreen(isNavigationTab = isNavigationTab)
        path == Screen.Search.route -> SearchLandingScreen()
        path.substringBefore('?') == Screen.PrivateMessages.route -> ConversationsScreen(providerId = sourceFeatureRouteProvider(route))
        path.substringBefore('?') == Screen.OfficialMessages.route -> ConversationsScreen(officialMessages = true, providerId = sourceFeatureRouteProvider(route))
        path.substringBefore('?') == Screen.MessageContacts.route -> MessageContactsScreen(providerId = sourceFeatureRouteProvider(route))
        path.substringBefore('?') == Screen.ListenTogether.route -> ListenTogetherScreen(providerId = sourceFeatureRouteProvider(route))
        path.substringBefore('?') == Screen.SongRecognition.route -> SongRecognitionScreen(providerId = sourceFeatureRouteProvider(route))
        path == Screen.NeteaseLogin.route -> NeteaseLoginScreen()
        path == Screen.NeteaseWebLogin.route -> NeteaseWebLoginScreen()
        path == Screen.PcQrLogin.route -> PcQrLoginScreen()
        path == Screen.AccountHome.route -> AccountHomeScreen()
        path == Screen.NeteaseMusician.route -> MusicianAccountScreen()
        path == Screen.AccountSecurity.route -> AccountSecurityScreen()
        path == Screen.AccountBindings.route -> AccountBindingsScreen()
        path == Screen.AccountDevices.route -> AccountDevicesScreen()
        path == Screen.AccountAuthorizations.route -> AccountDevicesScreen(initialTab = AccountDeviceTab.Authorizations)
        path.startsWith("${Screen.AccountWeb.route}/") -> {
            OfficialNeteasePage.entries.firstOrNull {
                it.name == path.substringAfter("${Screen.AccountWeb.route}/")
            }?.let { OfficialNeteasePageScreen(it) }
        }
        path == Screen.ListeningStats.route -> ListeningStatsScreen()
        path == Screen.Test.route -> Test()
        path == Screen.Setting.route -> SettingScreen(scrollBehavior)
        path == Screen.AppearanceSettings.route -> AppearanceSettings(scrollBehavior)
        path == Screen.GeneralSettings.route -> GeneralSettings()
        path == Screen.LyricsSettings.route -> LyricsSettings()
        path == Screen.ContentSettings.route -> ContentsSetting(scrollBehavior)
        path == Screen.PlaySettings.route -> PlaySetting(scrollBehavior)
        path == Screen.EqualizerSettings.route -> EqualizerSettings()
        path == Screen.DownloadSettings.route -> DownloadSetting(scrollBehavior)
        path == Screen.StorageManagement.route -> StorageManagementScreen()
        path == Screen.SourceManagement.route -> com.ljyh.mei.ui.screen.source.SourceManagementScreen()
        path == Screen.SourceMarket.route -> com.ljyh.mei.ui.screen.source.SourceManagementScreen(market = true)
        path.startsWith("${Screen.SourcePluginSettings.route}/") -> {
            com.ljyh.mei.ui.screen.source.SourceManagementScreen(
                pluginId = android.net.Uri.decode(path.substringAfter("${Screen.SourcePluginSettings.route}/")),
            )
        }
        path.startsWith("${Screen.SourceContent.route}/") -> {
            val parts = path.substringAfter("${Screen.SourceContent.route}/").split('/')
            val ref = if (parts.size == 3) sourceContentRef(android.net.Uri.decode(parts[0]), parts[1], android.net.Uri.decode(parts[2])) else null
            if (ref != null) SourceContentScreen(ref) else MissingSourceContentPage()
        }
        path.startsWith("${Screen.SourceArtistSongs.route}/") || path.startsWith("${Screen.SourceArtistAlbums.route}/") -> {
            val albums = path.startsWith("${Screen.SourceArtistAlbums.route}/")
            val prefix = if (albums) Screen.SourceArtistAlbums.route else Screen.SourceArtistSongs.route
            val parts = path.substringAfter("$prefix/").split('/')
            val ref = if (parts.size == 2) sourceContentRef(android.net.Uri.decode(parts[0]), "artist",
                android.net.Uri.decode(parts[1])) as? ArtistRef else null
            if (ref != null) SourceArtistCatalogScreen(ref, albums) else MissingSourceContentPage()
        }
        path.startsWith("${Screen.SourceAccount.route}/") -> SourceAccountScreen(android.net.Uri.decode(path.substringAfter("${Screen.SourceAccount.route}/")))
        path.startsWith("${Screen.SourcePlaylistCreate.route}/") -> SourcePlaylistEditorScreen(android.net.Uri.decode(path.substringAfter("${Screen.SourcePlaylistCreate.route}/")))
        path.startsWith("${Screen.SourcePlaylistEdit.route}/") -> {
            val parts = path.substringAfter("${Screen.SourcePlaylistEdit.route}/").split('/')
            if (parts.size == 2) SourcePlaylistEditorScreen(android.net.Uri.decode(parts[0]), android.net.Uri.decode(parts[1])) else MissingSourceContentPage()
        }
        path.startsWith("${Screen.SourcePlaylistPicker.route}/") -> {
            val ref = TrackRefCodec.decode(android.net.Uri.decode(path.substringAfter("${Screen.SourcePlaylistPicker.route}/")))
            if (ref != null) SourcePlaylistPickerScreen(ref) else MissingSourceContentPage()
        }
        path == Screen.DownloadManage.route -> DownloadManageScreen(
            scrollBehavior = scrollBehavior,
            isNavigationTab = isNavigationTab,
        )
        path == Screen.LocalMusic.route -> LocalMusicScreen(scrollBehavior)
        path == Screen.EveryDay.route -> EveryDay()
        path == Screen.About.route -> AboutScreen()
        path == Screen.Log.route -> LogScreen()
        path == Screen.History.route -> HistoryScreen(isNavigationTab = isNavigationTab)
        path.startsWith("${Screen.PrivateConversation.route}/") -> {
            android.net.Uri.decode(path.substringBefore('?').substringAfter("${Screen.PrivateConversation.route}/"))
                .takeIf(String::isNotBlank)?.let { userId -> ConversationScreen(userId, providerId = sourceFeatureRouteProvider(route)) }
        }
        path.startsWith("${Screen.AccountListeningRank.route}/") -> {
            path.substringAfter("${Screen.AccountListeningRank.route}/").let(android.net.Uri::decode)
                .let { userId -> ListeningRankScreen(userId) }
        }
        path.startsWith("${Screen.AccountConnections.route}/") -> {
            val arguments = path.substringAfter("${Screen.AccountConnections.route}/").split('/', limit = 2)
            val userId = arguments.firstOrNull()?.let(android.net.Uri::decode)?.takeIf(String::isNotBlank)
            val type = AccountConnectionType.entries.firstOrNull { it.name == arguments.getOrNull(1) }
            if (userId != null && type != null) AccountConnectionsScreen(userId, type)
        }
        path.startsWith("${Screen.PodcastDetail.route}/") -> {
            path.substringAfter("${Screen.PodcastDetail.route}/").let(android.net.Uri::decode)
                .let { id -> PodcastDetailScreen(id) }
        }
        path.startsWith("${Screen.LocalSongList.route}/") -> {
            val args = path.substringAfter("${Screen.LocalSongList.route}/").split('/', limit = 2)
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
        path.startsWith("${Screen.SearchResult.route}/") -> {
            val args = path.substringAfter("${Screen.SearchResult.route}/").split('/', limit = 2)
            if (args.size == 2) {
                SearchResultScreen(
                    query = android.net.Uri.decode(args[0]),
                    type = args[1].toIntOrNull() ?: 1,
                )
            }
        }
        path.startsWith("${Screen.PlayList.route}/") -> {
            path.substringAfter("${Screen.PlayList.route}/").toLongOrNull()
                ?.let { SourceContentScreen(PlaylistRef(ProviderIds.NETEASE, it.toString())) }
        }
        path.startsWith("${Screen.Album.route}/") -> {
            path.substringAfter("${Screen.Album.route}/").toLongOrNull()
                ?.let { SourceContentScreen(AlbumRef(ProviderIds.NETEASE, it.toString())) }
        }
        path.startsWith("${Screen.ArtistInfo.route}/") -> {
            ArtistInfoScreen(id = URLDecoder.decode(path.substringAfter("${Screen.ArtistInfo.route}/"), "UTF-8"),
                providerId = sourceFeatureRouteProvider(route) ?: ProviderIds.NETEASE)
        }
        path.startsWith("${Screen.ArtistSongs.route}/") -> {
            SourceArtistCatalogScreen(ArtistRef(ProviderIds.NETEASE, path.substringAfter("${Screen.ArtistSongs.route}/")), albums = false)
        }
        path.startsWith("${Screen.ArtistAlbums.route}/") -> {
            SourceArtistCatalogScreen(ArtistRef(ProviderIds.NETEASE, path.substringAfter("${Screen.ArtistAlbums.route}/")), albums = true)
        }
        path.startsWith("${Screen.Artist.route}/") -> {
            path.substringAfter("${Screen.Artist.route}/")
                .let { SourceContentScreen(ArtistRef(ProviderIds.NETEASE, it)) }
        }
        path.startsWith("${Screen.Comment.route}/") -> {
            path.substringAfter("${Screen.Comment.route}/")
                .let { CommentScreen(songId = it) }
        }
        path.startsWith("${Screen.SongWiki.route}/") -> {
            path.substringAfter("${Screen.SongWiki.route}/").let(android.net.Uri::decode)
                .let { SongWikiScreen(songId = it) }
        }
        else -> error("Unknown Mei navigation path: $path")
    }
}

internal fun sourceContentRef(providerId: String, kind: String, id: String): ContentRef? = runCatching {
    when (kind) {
        "track" -> TrackRef(providerId, id)
        "album" -> AlbumRef(providerId, id)
        "artist" -> ArtistRef(providerId, id)
        "playlist" -> PlaylistRef(providerId, id)
        else -> null
    }
}.getOrNull()

private fun sourceFeatureRouteProvider(route: String): String? = runCatching {
    android.net.Uri.parse("meilox://feature/$route").getQueryParameter("providerId")?.takeIf(String::isNotBlank)
}.getOrNull()

@Composable
private fun MissingSourceContentPage() {
    val nav = com.ljyh.mei.ui.local.LocalNavController.current
    com.ljyh.mei.ui.navigation.MeiloXPinnedListPage("内容不可用", onNavigateBack = nav::navigateUp) {
        item { io.github.neoruaa.glasmio.ui.GlasmioGroupedList {
            io.github.neoruaa.glasmio.ui.GlasmioListRow("来源或内容不存在", showDisclosure = false)
        } }
    }
}

private fun NavGraphBuilder.sourceScoped(screen: Screen, content: @Composable (String?) -> Unit) {
    composable("${screen.route}?providerId={providerId}", arguments = listOf(
        navArgument("providerId") { type = NavType.StringType; nullable = true; defaultValue = null },
    )) { content(it.arguments?.getString("providerId")) }
}
