package com.ljyh.mei.di

import androidx.lifecycle.ViewModel
import com.ljyh.mei.ui.ShareViewModel
import com.ljyh.mei.ui.component.player.PlayerViewModel
import com.ljyh.mei.ui.screen.about.AboutViewModel
import com.ljyh.mei.ui.screen.account.AccountHomeViewModel
import com.ljyh.mei.ui.screen.account.ListeningRankViewModel
import com.ljyh.mei.ui.screen.account.NeteaseLoginViewModel
import com.ljyh.mei.ui.screen.album.AlbumDetailViewModel
import com.ljyh.mei.ui.screen.artist.ArtistSongsViewModel
import com.ljyh.mei.ui.screen.artist.ArtistViewModel
import com.ljyh.mei.ui.screen.cloud.CloudMusicViewModel
import com.ljyh.mei.ui.screen.comment.CommentViewModel
import com.ljyh.mei.ui.screen.history.HistoryViewModel
import com.ljyh.mei.ui.screen.listentogether.ListenTogetherStoreHolder
import com.ljyh.mei.ui.screen.log.LogViewModel
import com.ljyh.mei.ui.screen.main.findmusic.FindMusicViewModel
import com.ljyh.mei.ui.screen.main.home.HomeViewModel
import com.ljyh.mei.ui.screen.main.library.LibraryViewModel
import com.ljyh.mei.ui.screen.playlist.PlaylistViewModel
import com.ljyh.mei.ui.screen.podcast.PodcastDetailViewModel
import com.ljyh.mei.ui.screen.podcast.PodcastViewModel
import com.ljyh.mei.ui.screen.recognition.SongRecognitionViewModel
import com.ljyh.mei.ui.screen.search.SearchDiscoveryViewModel
import com.ljyh.mei.ui.screen.search.SearchViewModel
import com.ljyh.mei.ui.screen.setting.StorageManagementViewModel
import com.ljyh.mei.ui.screen.social.ConversationViewModel
import com.ljyh.mei.ui.screen.social.ConversationsViewModel
import com.ljyh.mei.ui.screen.social.MessageContactsViewModel
import com.ljyh.mei.ui.screen.social.NeteaseShareViewModel
import com.ljyh.mei.ui.screen.song.SongWikiViewModel
import dagger.Binds
import dagger.Module
import dagger.multibindings.ClassKey
import dagger.multibindings.IntoMap

@Module
abstract class ViewModelBindings {
    @Binds @IntoMap @ClassKey(ShareViewModel::class)
    abstract fun bindShareViewModel(model: ShareViewModel): ViewModel

    @Binds @IntoMap @ClassKey(PlayerViewModel::class)
    abstract fun bindPlayerViewModel(model: PlayerViewModel): ViewModel

    @Binds @IntoMap @ClassKey(AboutViewModel::class)
    abstract fun bindAboutViewModel(model: AboutViewModel): ViewModel

    @Binds @IntoMap @ClassKey(AccountHomeViewModel::class)
    abstract fun bindAccountHomeViewModel(model: AccountHomeViewModel): ViewModel

    @Binds @IntoMap @ClassKey(ListeningRankViewModel::class)
    abstract fun bindListeningRankViewModel(model: ListeningRankViewModel): ViewModel

    @Binds @IntoMap @ClassKey(NeteaseLoginViewModel::class)
    abstract fun bindNeteaseLoginViewModel(model: NeteaseLoginViewModel): ViewModel

    @Binds @IntoMap @ClassKey(AlbumDetailViewModel::class)
    abstract fun bindAlbumDetailViewModel(model: AlbumDetailViewModel): ViewModel

    @Binds @IntoMap @ClassKey(ArtistSongsViewModel::class)
    abstract fun bindArtistSongsViewModel(model: ArtistSongsViewModel): ViewModel

    @Binds @IntoMap @ClassKey(ArtistViewModel::class)
    abstract fun bindArtistViewModel(model: ArtistViewModel): ViewModel

    @Binds @IntoMap @ClassKey(CloudMusicViewModel::class)
    abstract fun bindCloudMusicViewModel(model: CloudMusicViewModel): ViewModel

    @Binds @IntoMap @ClassKey(CommentViewModel::class)
    abstract fun bindCommentViewModel(model: CommentViewModel): ViewModel

    @Binds @IntoMap @ClassKey(HistoryViewModel::class)
    abstract fun bindHistoryViewModel(model: HistoryViewModel): ViewModel

    @Binds @IntoMap @ClassKey(ListenTogetherStoreHolder::class)
    abstract fun bindListenTogetherStoreHolder(model: ListenTogetherStoreHolder): ViewModel

    @Binds @IntoMap @ClassKey(LogViewModel::class)
    abstract fun bindLogViewModel(model: LogViewModel): ViewModel

    @Binds @IntoMap @ClassKey(FindMusicViewModel::class)
    abstract fun bindFindMusicViewModel(model: FindMusicViewModel): ViewModel

    @Binds @IntoMap @ClassKey(HomeViewModel::class)
    abstract fun bindHomeViewModel(model: HomeViewModel): ViewModel

    @Binds @IntoMap @ClassKey(LibraryViewModel::class)
    abstract fun bindLibraryViewModel(model: LibraryViewModel): ViewModel

    @Binds @IntoMap @ClassKey(PlaylistViewModel::class)
    abstract fun bindPlaylistViewModel(model: PlaylistViewModel): ViewModel

    @Binds @IntoMap @ClassKey(PodcastDetailViewModel::class)
    abstract fun bindPodcastDetailViewModel(model: PodcastDetailViewModel): ViewModel

    @Binds @IntoMap @ClassKey(PodcastViewModel::class)
    abstract fun bindPodcastViewModel(model: PodcastViewModel): ViewModel

    @Binds @IntoMap @ClassKey(SongRecognitionViewModel::class)
    abstract fun bindSongRecognitionViewModel(model: SongRecognitionViewModel): ViewModel

    @Binds @IntoMap @ClassKey(SearchDiscoveryViewModel::class)
    abstract fun bindSearchDiscoveryViewModel(model: SearchDiscoveryViewModel): ViewModel

    @Binds @IntoMap @ClassKey(SearchViewModel::class)
    abstract fun bindSearchViewModel(model: SearchViewModel): ViewModel

    @Binds @IntoMap @ClassKey(StorageManagementViewModel::class)
    abstract fun bindStorageManagementViewModel(model: StorageManagementViewModel): ViewModel

    @Binds @IntoMap @ClassKey(ConversationViewModel::class)
    abstract fun bindConversationViewModel(model: ConversationViewModel): ViewModel

    @Binds @IntoMap @ClassKey(ConversationsViewModel::class)
    abstract fun bindConversationsViewModel(model: ConversationsViewModel): ViewModel

    @Binds @IntoMap @ClassKey(MessageContactsViewModel::class)
    abstract fun bindMessageContactsViewModel(model: MessageContactsViewModel): ViewModel

    @Binds @IntoMap @ClassKey(NeteaseShareViewModel::class)
    abstract fun bindNeteaseShareViewModel(model: NeteaseShareViewModel): ViewModel

    @Binds @IntoMap @ClassKey(SongWikiViewModel::class)
    abstract fun bindSongWikiViewModel(model: SongWikiViewModel): ViewModel
}
