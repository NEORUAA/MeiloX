package com.ljyh.mei.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import com.ljyh.mei.R
import io.github.neoruaa.glasmio.icons.SfSymbol

enum class ContentFeature {
    Podcasts,
    Downloads,
    CloudMusic,
    ListeningHistory,
}

enum class AppPagePlacement {
    Home,
    TabBar,
    Library,
}

enum class LibraryPage(
    @get:StringRes val titleRes: Int,
    val symbol: SfSymbol,
    val requiredFeature: ContentFeature? = null,
) {
    Songs(R.string.library_page_songs, SfSymbol.MusicNote),
    Playlists(R.string.library_page_playlists, SfSymbol.MusicNoteList),
    Albums(R.string.library_page_albums, SfSymbol.MusicNoteSquareStackFilled),
    Podcasts(R.string.library_page_podcasts, SfSymbol.Microphone, ContentFeature.Podcasts),
    Downloads(R.string.library_page_downloads, SfSymbol.Download, ContentFeature.Downloads),
    Cloud(R.string.library_page_cloud, SfSymbol.Cloud, ContentFeature.CloudMusic),
    History(R.string.library_page_history, SfSymbol.Clock, ContentFeature.ListeningHistory),
}

enum class AppTab(
    @get:StringRes val titleRes: Int,
    val symbol: SfSymbol,
    val libraryPage: LibraryPage? = null,
    val requiredFeature: ContentFeature? = null,
) {
    Home(R.string.app_tab_home, SfSymbol.House),
    Recommended(R.string.app_tab_recommended, SfSymbol.Sparkles),
    Music(R.string.app_tab_music, SfSymbol.MusicNote),
    Podcasts(R.string.app_tab_podcasts, SfSymbol.RadioWaves, requiredFeature = ContentFeature.Podcasts),
    Explore(R.string.app_tab_explore, SfSymbol.Safari),
    Library(R.string.app_tab_library, SfSymbol.MusicNoteList),
    LibrarySongs(R.string.app_tab_library_songs, SfSymbol.Heart, LibraryPage.Songs),
    LibraryPlaylists(R.string.app_tab_library_playlists, SfSymbol.MusicNoteList, LibraryPage.Playlists),
    LibraryAlbums(R.string.library_page_albums, SfSymbol.MusicNoteSquareStackFilled, LibraryPage.Albums),
    LibraryPodcasts(
        R.string.app_tab_library_podcasts,
        SfSymbol.Microphone,
        LibraryPage.Podcasts,
        ContentFeature.Podcasts,
    ),
    LibraryDownloads(
        R.string.app_tab_library_downloads,
        SfSymbol.Download,
        LibraryPage.Downloads,
        ContentFeature.Downloads,
    ),
    LibraryCloud(
        R.string.app_tab_library_cloud,
        SfSymbol.Cloud,
        LibraryPage.Cloud,
        ContentFeature.CloudMusic,
    ),
    LibraryHistory(
        R.string.app_tab_library_history,
        SfSymbol.Clock,
        LibraryPage.History,
        ContentFeature.ListeningHistory,
    ),
    Search(R.string.app_tab_search, SfSymbol.Search),
    ;

    val allowedPlacements: Set<AppPagePlacement>
        get() = when {
            this == Recommended -> setOf(AppPagePlacement.Home)
            libraryPage == null -> setOf(AppPagePlacement.Home, AppPagePlacement.TabBar)
            else -> AppPagePlacement.entries.toSet()
        }

    companion object {
        val movablePrimaryContentPages = listOf(Music, Podcasts, Explore, Library)
        val libraryContentPages = listOf(
            LibrarySongs,
            LibraryPlaylists,
            LibraryAlbums,
            LibraryPodcasts,
            LibraryDownloads,
            LibraryCloud,
            LibraryHistory,
        )
        val configurablePages = movablePrimaryContentPages + libraryContentPages

        fun fromLibraryPage(page: LibraryPage): AppTab = when (page) {
            LibraryPage.Songs -> LibrarySongs
            LibraryPage.Playlists -> LibraryPlaylists
            LibraryPage.Albums -> LibraryAlbums
            LibraryPage.Podcasts -> LibraryPodcasts
            LibraryPage.Downloads -> LibraryDownloads
            LibraryPage.Cloud -> LibraryCloud
            LibraryPage.History -> LibraryHistory
        }
    }
}

@Immutable
data class NavigationLayout(
    val homeTabs: List<AppTab> = listOf(
        AppTab.Recommended,
        AppTab.Music,
        AppTab.Podcasts,
    ),
    val separatedLibraryPages: List<LibraryPage> = listOf(LibraryPage.Cloud),
    val tabOrder: List<AppTab> = emptyList(),
    val enabledFeatures: Set<ContentFeature> = ContentFeature.entries.toSet(),
) {
    val visibleTabs: List<AppTab>
        get() = normalizeVisibleTabs(
            requested = tabOrder,
            homeTabs = normalizedHomeTabs,
            separatedLibraryPages = normalizedSeparatedPages,
            enabledFeatures = enabledFeatures,
        )

    val normalizedHomeTabs: List<AppTab>
        get() = normalizeHomeTabs(homeTabs, normalizedSeparatedPages, enabledFeatures)

    val normalizedSeparatedPages: List<LibraryPage>
        get() = LibraryPage.entries.filter {
            it in separatedLibraryPages && it.isEnabled(enabledFeatures)
        }

    fun placementOf(tab: AppTab): AppPagePlacement = when {
        tab == AppTab.Recommended || tab in normalizedHomeTabs -> AppPagePlacement.Home
        tab.libraryPage != null && tab.libraryPage !in normalizedSeparatedPages -> AppPagePlacement.Library
        else -> AppPagePlacement.TabBar
    }
}

private fun normalizeHomeTabs(
    requested: List<AppTab>,
    separatedLibraryPages: List<LibraryPage>,
    enabledFeatures: Set<ContentFeature>,
): List<AppTab> {
    val allowed = (
        AppTab.movablePrimaryContentPages + separatedLibraryPages.map(AppTab::fromLibraryPage)
    ).filter { it.isEnabled(enabledFeatures) }.toSet()
    return buildList {
        add(AppTab.Recommended)
        requested.forEach { tab ->
            if (tab in allowed && tab !in this) add(tab)
        }
    }
}

private fun normalizeVisibleTabs(
    requested: List<AppTab>,
    homeTabs: List<AppTab>,
    separatedLibraryPages: List<LibraryPage>,
    enabledFeatures: Set<ContentFeature>,
): List<AppTab> {
    val embeddedLibraryPages = LibraryPage.entries.filter {
        it !in separatedLibraryPages && it.isEnabled(enabledFeatures)
    }
    val defaults = buildList {
        add(AppTab.Home)
        AppTab.movablePrimaryContentPages.forEach { tab ->
            if (tab !in homeTabs && tab.isEnabled(enabledFeatures)) {
                if (tab != AppTab.Library || embeddedLibraryPages.isNotEmpty()) add(tab)
            }
        }
        separatedLibraryPages.forEach { page ->
            val tab = AppTab.fromLibraryPage(page)
            if (tab !in homeTabs && tab.isEnabled(enabledFeatures)) add(tab)
        }
        add(AppTab.Search)
    }.distinct()
    val allowed = defaults.toSet()
    val normalized = requested.filter { it in allowed }.distinct().toMutableList()
    defaults.forEachIndexed { index, tab ->
        if (tab !in normalized) {
            val nextExisting = defaults.drop(index + 1).firstOrNull { it in normalized }
            if (nextExisting == null) {
                normalized.add(tab)
            } else {
                normalized.add(normalized.indexOf(nextExisting), tab)
            }
        }
    }
    normalized.remove(AppTab.Home)
    normalized.add(0, AppTab.Home)
    normalized.remove(AppTab.Search)
    normalized.add(AppTab.Search)
    return normalized
}

private fun AppTab.isEnabled(enabledFeatures: Set<ContentFeature>): Boolean =
    requiredFeature == null || requiredFeature in enabledFeatures

private fun LibraryPage.isEnabled(enabledFeatures: Set<ContentFeature>): Boolean =
    requiredFeature == null || requiredFeature in enabledFeatures

sealed interface MusicDestination {
    data class Song(val id: Long) : MusicDestination
    data class Playlist(val id: Long, val isToplist: Boolean = false) : MusicDestination
    data class PlaylistCategory(val category: String) : MusicDestination
    data class Album(val id: Long) : MusicDestination
    data class Artist(val id: Long) : MusicDestination
    data object Podcasts : MusicDestination
    data class Podcast(val id: Long) : MusicDestination
    data class PodcastCategory(val id: Long, val name: String) : MusicDestination
    data class PodcastProgram(val id: Long) : MusicDestination
    data object DailySongs : MusicDestination
    data object NewAlbums : MusicDestination
    data object Toplists : MusicDestination
}

sealed interface ContentDestination {
    data object PrivateMessages : ContentDestination
    data object SongRecognition : ContentDestination
}
