package com.ljyh.mei.di

import android.content.Context
import com.ljyh.mei.AppContext
import com.ljyh.mei.MainActivity
import com.ljyh.mei.playback.MusicService
import com.ljyh.mei.parasite.HostRequestBridge
import com.ljyh.mei.parasite.HostAccountStore
import com.ljyh.mei.data.network.api.ApiService
import com.ljyh.mei.data.network.api.EApiService
import com.ljyh.mei.data.network.api.WeApiService
import dagger.BindsInstance
import dagger.Component
import javax.inject.Singleton

@Singleton
@Component(modules = [AppModule::class, RetrofitModule::class, RepositoryModule::class, ViewModelBindings::class])
interface AppComponent {
    fun inject(activity: MainActivity)
    fun inject(service: MusicService)
    fun viewModelFactory(): AppViewModelFactory
    fun database(): AppDatabase
    fun hostRequests(): HostRequestBridge
    fun hostAccount(): HostAccountStore
    fun apiService(): ApiService
    fun eapiService(): EApiService
    fun weapiService(): WeApiService
    @ApplicationContext fun context(): Context

    @Component.Factory
    interface Factory {
        fun create(@BindsInstance @ApplicationContext context: Context): AppComponent
    }
}

/** One module-classloader graph per host process, independent of the host Application. */
object AppGraph {
    @Volatile private var instance: AppComponent? = null
    val component: AppComponent
        get() = checkNotNull(instance) { "Module graph has not been initialized" }

    @Synchronized
    fun initialize(context: Context) {
        val applicationContext = context.applicationContext
        instance?.let {
            check(it.context() === applicationContext) { "Module graph already belongs to another context" }
            return
        }
        AppContext.instance = applicationContext
        instance = DaggerAppComponent.factory().create(applicationContext)
    }
}
