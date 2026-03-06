package com.nuvio.tv.di

import com.nuvio.tv.core.recommendations.ChannelManager
import com.nuvio.tv.core.recommendations.ProgramBuilder
import com.nuvio.tv.core.recommendations.RecommendationDataStore
import com.nuvio.tv.core.recommendations.TvRecommendationManager
import com.nuvio.tv.domain.repository.WatchProgressRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext

/**
 * Hilt module that exposes TV recommendation singletons to the DI graph.
 *
 * Note: [RecommendationDataStore], [ChannelManager], [ProgramBuilder], and
 * [TvRecommendationManager] are all `@Singleton @Inject constructor(…)` classes,
 * so Hilt can construct them automatically. This module only exists for the
 * few cases where we need explicit `@Provides` bindings (currently none),
 * and as a documentation anchor for the recommendation dependency graph.
 *
 * If all classes use `@Inject constructor`, this module can remain empty and
 * still serve as the install-point for future explicit bindings.
 */
@Module
@InstallIn(SingletonComponent::class)
object RecommendationModule {
    // All recommendation classes use @Singleton + @Inject constructor,
    // so Hilt constructs them without explicit @Provides methods.
    // This module is kept as a placeholder for future bindings
    // (e.g., Trending channel data source).
}
