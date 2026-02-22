package com.nuvio.tv.core.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
object ProfileModule {
    // ProfileDataStore, ProfileDataStoreFactory, and ProfileManager are all @Singleton
    // with @Inject constructors, so Hilt can provide them automatically.
    // This module exists as a marker and for any future explicit @Provides if needed.
}
