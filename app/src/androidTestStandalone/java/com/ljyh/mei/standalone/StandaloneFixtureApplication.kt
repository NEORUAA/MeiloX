package com.ljyh.mei.standalone

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.ljyh.mei.AppContext
import java.io.IOException
import okhttp3.OkHttpClient

/** Supplies platform context without accounts, the production graph, or background work. */
class StandaloneFixtureApplication : Application(), SingletonImageLoader.Factory {
    override fun onCreate() {
        super.onCreate()
        AppContext.instance = this
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .diskCache(null)
        .components {
            add(OkHttpNetworkFetcherFactory(
                OkHttpClient.Builder().addInterceptor {
                    throw IOException("Network images are disabled in standalone fixtures")
                }.build(),
            ))
        }
        .build()
}
