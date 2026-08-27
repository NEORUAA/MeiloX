package com.ljyh.mei.ui.screen

import androidx.annotation.StringRes
import com.ljyh.mei.R
import com.ljyh.mei.ui.glass.SfSymbol
import com.ljyh.mei.ui.navigation.MeiNavigator
import com.ljyh.mei.ui.navigation.ContentFeature

sealed class Screen(val route:String) {
    data object Home:Screen("home")
    data object Library:Screen("library")
    data object FindMusic: Screen("find_music")
    data object PlaylistCategory: Screen("playlist_category")
    data object SearchResult:Screen("search_result")
    data object PlayList:Screen("playlist")
    data object Setting:Screen("setting")
    data object ContentSettings:Screen(("setting/content"))
    data object NeteaseLogin:Screen("account/login")
    data object PcQrLogin:Screen("account/pc_qr_login")
    data object AccountHome:Screen("account/home")
    data object AccountListeningRank:Screen("account/listening_rank")
    data object AppearanceSettings:Screen("setting/appearance")
    data object GeneralSettings:Screen("setting/general")
    data object LyricsSettings:Screen("setting/lyrics")
    data object PlaySettings:Screen("setting/play")
    data object EqualizerSettings:Screen("setting/equalizer")
    data object DownloadSettings:Screen("setting/download")
    data object StorageManagement:Screen("setting/storage")
    data object DownloadManage:Screen("download_manage")
    data object LocalMusic:Screen("local_music")
    data object LocalSongList:Screen("local_songs")
    data object EveryDay:Screen("everyday")
    data object Album: Screen("album")
    data object Artist: Screen("artist")
    data object History: Screen("history")
    data object Podcasts: Screen("podcasts")
    data object PodcastDetail: Screen("podcast")
    data object CloudMusic: Screen("cloud_music")
    data object Search: Screen("search")
    data object PrivateMessages: Screen("private_messages")
    data object PrivateConversation: Screen("private_message")
    data object MessageContacts: Screen("message_contacts")
    data object ListenTogether: Screen("listen_together")
    data object SongRecognition: Screen("song_recognition")
    data object Comment: Screen("comment")
    data object SongWiki: Screen("song_wiki")
    data object Test:Screen("test")
    data object About: Screen("about")
    data object Log: Screen("log")

    inline fun navigate(
        navController: MeiNavigator,
        builder: NavigationBuilder.() -> Unit = {}
    ) {
        navController.navigate(NavigationBuilder(route).apply(builder).build())
    }

    companion object {
        val MainScreens: List<Screen>
            get() = listOf(Home, FindMusic, Podcasts, Library, DownloadManage, CloudMusic, History, Search, Setting)
    }
}

enum class Index(
    val route: String,
    @get:StringRes val labelRes: Int,
    val symbol: SfSymbol,
    val requiredFeature: ContentFeature? = null,
) {
    Home(Screen.Home.route, R.string.app_tab_home, SfSymbol.House),
    FindMusic(Screen.FindMusic.route, R.string.app_tab_explore, SfSymbol.Safari),
    Podcasts(Screen.Podcasts.route, R.string.app_tab_podcasts, SfSymbol.RadioWaves, ContentFeature.Podcasts),
    Library(Screen.Library.route, R.string.app_tab_library, SfSymbol.MusicNoteList),
    Downloads(Screen.DownloadManage.route, R.string.app_tab_library_downloads, SfSymbol.Download, ContentFeature.Downloads),
    Cloud(Screen.CloudMusic.route, R.string.app_tab_library_cloud, SfSymbol.Cloud, ContentFeature.CloudMusic),
    History(Screen.History.route, R.string.app_tab_library_history, SfSymbol.Clock, ContentFeature.ListeningHistory),
    Settings(Screen.Setting.route, R.string.settings, SfSymbol.Settings),
    Search(Screen.Search.route, R.string.app_tab_search, SfSymbol.Search),

    ;

    companion object {
        val DefaultOrder = listOf(Home, FindMusic, Library, Settings)
    }
}



class NavigationBuilder(
    route: String
) {
    private var finalRoute: String = route
    private val query: MutableMap<String, String> = hashMapOf()

    fun addPath(path: String) {
        finalRoute += "/$path"
    }

    fun addQuery(key: String, value: String) {
        query += key to value
    }

    fun build(): String = if (query.isEmpty()) {
        finalRoute
    } else {
        "$finalRoute${
            query.entries.joinToString(
                separator = "&",
                prefix = "?"
            ) { "${it.key}=${it.value}" }
        }"
    }
}
