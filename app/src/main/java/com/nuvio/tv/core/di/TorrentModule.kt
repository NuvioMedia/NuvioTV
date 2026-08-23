package com.nuvio.tv.core.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.nuvio.tv.core.torrent.TorrServerApi
import com.nuvio.tv.core.torrent.TorrServerBinary
import com.nuvio.tv.core.torrent.TorrentService
import com.nuvio.tv.core.torrent.TorrentSettings
import com.nuvio.tv.core.torrent.torrentSettingsDataStore
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
    fun provideTorrentSettingsDataStore(
        @ApplicationContext context: Context
    ): DataStore<Preferences> = torrentSettingsDataStore(context)

    @Provides
    @Singleton
    fun provideTorrentSettings(
        dataStore: DataStore<Preferences>
    ): TorrentSettings = TorrentSettings(dataStore)

    @Provides
    @Singleton
    fun provideTorrServerBinary(
        @ApplicationContext context: Context,
        torrentSettings: TorrentSettings
    ): TorrServerBinary = TorrServerBinary(context, torrentSettings)

    @Provides
    @Singleton
    fun provideTorrServerApi(
        binary: TorrServerBinary
    ): TorrServerApi = TorrServerApi(binary)

    @Provides
    @Singleton
    fun provideTorrentService(
        @dagger.hilt.android.qualifiers.ApplicationContext appContext: android.content.Context,
        binary: TorrServerBinary,
        api: TorrServerApi
    ): TorrentService = TorrentService(appContext, binary, api)
}