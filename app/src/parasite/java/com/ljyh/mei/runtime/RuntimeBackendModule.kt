package com.ljyh.mei.runtime

import com.ljyh.mei.data.session.SessionStore
import com.ljyh.mei.data.repository.PlaylistCollectionBackend
import com.ljyh.mei.data.repository.CatalogCollectionBackend
import com.ljyh.mei.data.repository.PlaylistTracksBackend
import com.ljyh.mei.data.repository.SongFavoritesBackend
import com.ljyh.mei.parasite.HostPlaylistTracksBackend
import com.ljyh.mei.parasite.HostSongFavoritesBackend
import com.ljyh.mei.parasite.HostCatalogCollectionBackend
import com.ljyh.mei.parasite.HostPlaylistCollectionBackend
import com.ljyh.mei.playback.PlaybackReportSink
import com.ljyh.mei.parasite.HostCallFactory
import com.ljyh.mei.parasite.HostComponentRuntime
import com.ljyh.mei.parasite.HostPlaybackReportBridge
import com.ljyh.mei.parasite.HostSessionBridge
import dagger.Module
import dagger.Provides
import javax.inject.Named
import okhttp3.Call

/** Only the parasite source set binds shared consumers to official host adapters. */
@Module
object RuntimeBackendModule {
    @Provides internal fun downloadSources(backend: com.ljyh.mei.parasite.HostDownloadSourceBackend): com.ljyh.mei.playback.DownloadSourceBackend = backend
    @Provides fun cloudUploader(backend: com.ljyh.mei.parasite.HostCloudBinaryUploader): com.ljyh.mei.data.repository.CloudBinaryUploader = backend
    @Provides fun sessions(host: HostSessionBridge): SessionStore = host
    @Provides fun playbackReports(host: HostPlaybackReportBridge): PlaybackReportSink = host
    @Provides fun components(host: HostComponentRuntime): ComponentRuntime = host
    @Provides internal fun playlistCollections(backend: HostPlaylistCollectionBackend): PlaylistCollectionBackend = backend
    @Provides internal fun catalogCollections(backend: HostCatalogCollectionBackend): CatalogCollectionBackend = backend
    @Provides internal fun playlistTracks(backend: HostPlaylistTracksBackend): PlaylistTracksBackend = backend
    @Provides internal fun songFavorites(backend: HostSongFavoritesBackend): SongFavoritesBackend = backend

    @Provides @Named("NetEaseApiCalls")
    fun apiCalls(host: HostCallFactory): Call.Factory = host

    @Provides @Named("NetEaseWeApiCalls")
    fun weApiCalls(host: HostCallFactory): Call.Factory = host

    @Provides @Named("AudioMatchCalls")
    fun audioMatchCalls(host: HostCallFactory): Call.Factory = host
}
