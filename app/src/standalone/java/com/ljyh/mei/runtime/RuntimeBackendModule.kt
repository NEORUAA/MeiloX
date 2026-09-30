package com.ljyh.mei.runtime

import com.ljyh.mei.data.session.SessionStore
import com.ljyh.mei.data.repository.PlaylistCollectionBackend
import com.ljyh.mei.data.repository.CatalogCollectionBackend
import com.ljyh.mei.data.repository.PlaylistTracksBackend
import com.ljyh.mei.data.repository.SongFavoritesBackend
import com.ljyh.mei.standalone.StandalonePlaylistTracksBackend
import com.ljyh.mei.standalone.StandaloneSongFavoritesBackend
import com.ljyh.mei.standalone.StandaloneCatalogCollectionBackend
import com.ljyh.mei.standalone.StandalonePlaylistCollectionBackend
import com.ljyh.mei.data.network.netease.DataStoreNcblSessionContextProvider
import com.ljyh.mei.data.network.netease.NcblSessionContextProvider
import com.ljyh.mei.playback.PlaybackReportSink
import com.ljyh.mei.standalone.DataStoreAccountPersistence
import com.ljyh.mei.standalone.StandaloneAccountPersistence
import com.ljyh.mei.standalone.StandalonePlaybackReports
import com.ljyh.mei.standalone.StandaloneSessionStore
import com.ljyh.mei.standalone.StandaloneTransport
import dagger.Module
import dagger.Provides
import javax.inject.Named
import okhttp3.Call

@Module
object RuntimeBackendModule {
    @Provides internal fun downloadSources(backend: com.ljyh.mei.standalone.StandaloneDownloadSourceBackend): com.ljyh.mei.playback.DownloadSourceBackend = backend
    @Provides internal fun cloudUploader(backend: com.ljyh.mei.standalone.StandaloneCloudBinaryUploader): com.ljyh.mei.data.repository.CloudBinaryUploader = backend
    @Provides internal fun persistence(store: DataStoreAccountPersistence): StandaloneAccountPersistence = store
    @Provides fun sessions(store: StandaloneSessionStore): SessionStore = store
    @Provides internal fun playbackReports(reports: StandalonePlaybackReports): PlaybackReportSink = reports
    @Provides fun components(runtime: StandaloneComponentRuntime): ComponentRuntime = runtime
    @Provides internal fun playlistCollections(backend: StandalonePlaylistCollectionBackend): PlaylistCollectionBackend = backend
    @Provides internal fun catalogCollections(backend: StandaloneCatalogCollectionBackend): CatalogCollectionBackend = backend
    @Provides internal fun playlistTracks(backend: StandalonePlaylistTracksBackend): PlaylistTracksBackend = backend
    @Provides internal fun songFavorites(backend: StandaloneSongFavoritesBackend): SongFavoritesBackend = backend

    @Provides @Named("NetEaseApiCalls")
    internal fun apiCalls(transport: StandaloneTransport): Call.Factory = transport.business

    @Provides @Named("NetEaseWeApiCalls")
    internal fun weApiCalls(transport: StandaloneTransport): Call.Factory = transport.business

    @Provides @Named("AudioMatchCalls")
    internal fun audioMatchCalls(transport: StandaloneTransport): Call.Factory = transport.audioMatch

    @Provides @Named("NeteaseClientLog")
    internal fun clientLogCalls(transport: StandaloneTransport): Call.Factory = transport.clientLogs

    @Provides
    internal fun clientLogContext(provider: DataStoreNcblSessionContextProvider): NcblSessionContextProvider = provider
}
