package com.ljyh.mei.di

import android.content.Context
import com.ljyh.mei.AppContext
import com.ljyh.mei.MainActivity
import com.ljyh.mei.playback.MusicService
import com.ljyh.mei.runtime.RuntimeBackendModule
import com.ljyh.mei.runtime.RuntimeComponent
import com.ljyh.mei.data.session.AccountStore
import com.ljyh.mei.data.network.api.ApiService
import com.ljyh.mei.data.network.api.EApiService
import com.ljyh.mei.data.network.api.WeApiService
import dagger.BindsInstance
import dagger.Component
import javax.inject.Singleton

@Singleton
@Component(modules = [AppModule::class, RetrofitModule::class, RepositoryModule::class, ViewModelBindings::class,
    RuntimeBackendModule::class])
interface AppComponent : RuntimeComponent {
    fun inject(activity: MainActivity)
    fun inject(service: MusicService)
    fun viewModelFactory(): AppViewModelFactory
    fun database(): AppDatabase
    fun playbackReports(): com.ljyh.mei.playback.PlaybackReportSink
    fun runtime(): com.ljyh.mei.runtime.ComponentRuntime
    fun sessions(): com.ljyh.mei.data.session.SessionStore
    fun account(): AccountStore
    fun apiService(): ApiService
    fun songLyrics(): com.ljyh.mei.data.repository.SongLyricBackend
    fun songFavorites(): com.ljyh.mei.data.repository.SongFavoritesBackend
    fun downloadSources(): com.ljyh.mei.playback.DownloadSourceBackend
    fun eapiService(): EApiService
    fun weapiService(): WeApiService
    @ApplicationContext fun context(): Context

    @Component.Factory
    interface Factory {
        fun create(@BindsInstance @ApplicationContext context: Context): AppComponent
    }
}

/** One graph per application context; the selected runtime owns bootstrap and credentials. */
object AppGraph {
    @Volatile private var instance: AppComponent? = null
    val component: AppComponent
        get() = checkNotNull(instance) { "Application graph has not been initialized" }

    @Synchronized
    fun initialize(context: Context) {
        val applicationContext = context.applicationContext
        instance?.let {
            check(it.context() === applicationContext) { "Application graph already belongs to another context" }
            return
        }
        AppContext.instance = applicationContext
        instance = DaggerAppComponent.factory().create(applicationContext)
    }
}
