package com.omnio.tv.core.di

import com.omnio.tv.core.tmdb.TmdbServiceImpl
import com.omnio.tv.domain.tmdb.TmdbService
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class TmdbModule {

    @Binds
    @Singleton
    abstract fun bindTmdbService(impl: TmdbServiceImpl): TmdbService
}
