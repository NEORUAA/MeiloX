package com.ljyh.mei.di

import com.ljyh.mei.source.netease.NeteaseCatalogTransport
import com.ljyh.mei.data.repository.SearchRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {
    @Singleton
    @Provides
    fun provideSearchRepository(apiService: NeteaseCatalogTransport): SearchRepository {
        return SearchRepository(apiService)
    }

}
