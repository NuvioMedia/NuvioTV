package com.nuvio.tv.core.di

import android.content.Context
import com.nuvio.tv.core.torrent.TorrServerApi
import com.nuvio.tv.core.torrent.TorrServerBinary
import com.nuvio.tv.core.torrent.TorrentService
import com.nuvio.tv.core.torrent.TorrentSettings
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object TorrentModule {

    @Provides
    @Singleton
    fun provideTorrentSettings(
        @ApplicationContext context: Context
    ): TorrentSettings = TorrentSettings(context)

    @Provides
    @Singleton
    fun provideTorrServerBinary(
        @ApplicationContext context: Context
    ): TorrServerBinary = TorrServerBinary(context)

    @Provides
    @Singleton
    fun provideTorrServerApi(
        binary: TorrServerBinary
    ): TorrServerApi = TorrServerApi(binary)

    @Provides
    @Singleton
    fun provideTorrentService(
        binary: TorrServerBinary,
        api: TorrServerApi
    ): TorrentService = TorrentService(binary, api)
}
