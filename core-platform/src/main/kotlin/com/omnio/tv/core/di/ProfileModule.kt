package com.omnio.tv.core.di

import com.omnio.tv.core.profile.ProfileManagerImpl
import com.omnio.tv.domain.profile.ProfileManager
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class ProfileModule {

    @Binds
    @Singleton
    abstract fun bindProfileManager(impl: ProfileManagerImpl): ProfileManager
}
