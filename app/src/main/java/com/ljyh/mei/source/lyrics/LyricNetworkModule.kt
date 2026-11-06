package com.ljyh.mei.source.lyrics

import com.ljyh.mei.BuildConfig
import com.ljyh.mei.data.network.QQMusicUApiService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/** Lyric networking belongs to the host even when every content source is removed. */
@Module
@InstallIn(SingletonComponent::class)
object LyricNetworkModule {
    @Singleton
    @Provides
    @Named("qqMusicRetrofitU")
    fun provideQQMusicRetrofitU(@Named("qqMusicOkHttp") okHttpClient: OkHttpClient): Retrofit = Retrofit.Builder()
        .baseUrl("https://u.y.qq.com/")
        .addConverterFactory(GsonConverterFactory.create())
        .client(okHttpClient)
        .build()

    @Singleton
    @Provides
    @Named("qqMusicOkHttp")
    fun provideQQMusicOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.164 Safari/537.36")
                .header("Referer", "https://y.qq.com/")
                .build())
        }
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC else HttpLoggingInterceptor.Level.NONE
        })
        .build()

    @Singleton
    @Provides
    fun provideQQMusicApiServiceU(@Named("qqMusicRetrofitU") retrofit: Retrofit): QQMusicUApiService =
        retrofit.create(QQMusicUApiService::class.java)
}
