package com.nuvio.tv.di

import com.nuvio.tv.core.plugin.PluginManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object PluginModule {
    @Provides
    @Singleton
    fun providePluginManager(): PluginManager {
        return PluginManager()
    }
}
