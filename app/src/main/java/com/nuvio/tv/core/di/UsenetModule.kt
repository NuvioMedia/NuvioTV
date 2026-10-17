package com.nuvio.tv.core.di

import android.content.Context
import com.nuvio.tv.core.profile.ProfileScopedCredentialStore
import com.nuvio.tv.core.usenet.NewznabStateStorage
import com.nuvio.tv.core.usenet.SharedPreferencesNewznabStateStorage
import com.nuvio.tv.core.usenet.UnplayableReleases
import com.nuvio.tv.core.usenet.UsenetSourceSettings
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

@Module
@InstallIn(SingletonComponent::class)
abstract class UsenetModule {
    @Binds
    @IntoSet
    abstract fun bindUsenetCredentials(store: UsenetSourceSettings): ProfileScopedCredentialStore

    @Binds
    abstract fun bindNewznabStateStorage(storage: SharedPreferencesNewznabStateStorage): NewznabStateStorage

    @Binds
    @IntoSet
    abstract fun bindNewznabStateReset(storage: SharedPreferencesNewznabStateStorage): ProfileScopedCredentialStore

    companion object {
        // One instance: the engine sidecar and the player reach it without injection.
        @Provides
        fun unplayableReleases(@ApplicationContext context: Context): UnplayableReleases = UnplayableReleases.get(context)

        @Provides
        @IntoSet
        fun unplayableReleasesReset(@ApplicationContext context: Context): ProfileScopedCredentialStore =
            UnplayableReleases.storage(context)
    }
}
