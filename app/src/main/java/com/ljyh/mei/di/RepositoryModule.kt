package com.ljyh.mei.di

import com.ljyh.mei.data.network.api.ApiService
import com.ljyh.mei.data.network.QQMusicUApiService
import com.ljyh.mei.data.network.api.EApiService
import com.ljyh.mei.data.network.api.WeApiService
import com.ljyh.mei.data.repository.HomeRepository
import com.ljyh.mei.data.repository.PlayerRepository
import com.ljyh.mei.data.repository.PlaylistRepository
import com.ljyh.mei.data.repository.SearchRepository
import com.ljyh.mei.data.repository.ShareRepository
import com.ljyh.mei.data.repository.UserRepository
import com.ljyh.mei.data.repository.ArtistRepository
import com.ljyh.mei.data.repository.CommentRepository
import com.ljyh.mei.data.repository.CatalogCollectionBackend
import com.ljyh.mei.data.repository.PlaylistTracksBackend
import com.ljyh.mei.data.repository.SongFavoritesBackend
import com.ljyh.mei.data.session.SessionStore
import dagger.Module
import dagger.Provides
import javax.inject.Singleton

@Module
object RepositoryModule {

    @Singleton
    @Provides
    fun provideHomeRepository(eApiService: EApiService, sessions: SessionStore): HomeRepository {
        return HomeRepository(eApiService, sessions)
    }


    @Singleton
    @Provides
    fun providePlaylistRepository(apiService: ApiService, weApiService: WeApiService, collections: com.ljyh.mei.data.repository.PlaylistCollectionBackend, sessions: SessionStore, catalogCollections: CatalogCollectionBackend, playlistTracks: PlaylistTracksBackend, downloadSources: com.ljyh.mei.playback.DownloadSourceBackend): PlaylistRepository {
        return PlaylistRepository(apiService, weApiService, collections, sessions, catalogCollections, playlistTracks, downloadSources)
    }

    @Singleton
    @Provides
    fun provideUserRepository(apiService: ApiService,eApiService: EApiService, weApiService: WeApiService): UserRepository {
        return UserRepository(apiService,eApiService, weApiService)
    }


    @Singleton
    @Provides
    fun provideShareRepository(apiService: ApiService, qqMusicUApiService: QQMusicUApiService): ShareRepository {
        return ShareRepository(apiService, qqMusicUApiService)
    }


    @Singleton
    @Provides
    fun providePlayerRepository(qqMusicUApiService: QQMusicUApiService,apiService: ApiService,weApiService: WeApiService, sessions: com.ljyh.mei.data.session.SessionStore, favorites: SongFavoritesBackend): PlayerRepository {
        return PlayerRepository(qqMusicUApiService,apiService,weApiService,sessions,favorites)
    }

    @Singleton
    @Provides
    fun provideSearchRepository(apiService: ApiService): SearchRepository {
        return SearchRepository(apiService)
    }

    @Singleton
    @Provides
    fun provideArtistRepository(apiService: ApiService, sessions: com.ljyh.mei.data.session.SessionStore, collections: CatalogCollectionBackend): ArtistRepository {
        return ArtistRepository(apiService, sessions, collections)
    }

    @Singleton
    @Provides
    fun provideCommentRepository(apiService: ApiService, weApiService: WeApiService): CommentRepository {
        return CommentRepository(apiService, weApiService)
    }
}
