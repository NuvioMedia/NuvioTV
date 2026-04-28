package com.omnio.tv.core.di

import com.omnio.tv.core.plugin.PluginManagerImpl
import com.omnio.tv.domain.plugin.PluginManager
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class PluginModule {

    @Binds
    @Singleton
    abstract fun bindPluginManager(impl: PluginManagerImpl): PluginManager
}
