package com.nuvio.tv.core.di

import com.nuvio.tv.core.profile.ProfileScopedCredentialStore
import com.nuvio.tv.core.usenet.UsenetSourceSettings
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

@Module
@InstallIn(SingletonComponent::class)
abstract class UsenetModule {
    @Binds
    @IntoSet
    abstract fun bindUsenetCredentials(store: UsenetSourceSettings): ProfileScopedCredentialStore
}
