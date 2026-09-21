package com.nuvio.tv.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.compositionLocalOf
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.core.build.AppFeaturePolicy
import com.nuvio.tv.core.streams.PlaybackAvailability
import com.nuvio.tv.data.local.PluginDataStore
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.MetaRepository
import com.nuvio.tv.ui.screens.home.ContinueWatchingItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

internal val LocalPlaybackAvailability = compositionLocalOf { PlaybackAvailability() }

@HiltViewModel
internal class PlaybackAvailabilityViewModel @Inject constructor(
    addonRepository: AddonRepository,
    pluginDataStore: PluginDataStore,
    metaRepository: MetaRepository
) : ViewModel() {
    private val enabledScrapers = if (AppFeaturePolicy.pluginsEnabled) {
        combine(pluginDataStore.scrapers, pluginDataStore.pluginsEnabled) { scrapers, enabled ->
            if (enabled) scrapers.filter { it.enabled } else emptyList()
        }
    } else {
        flowOf(emptyList())
    }

    val availability = combine(addonRepository.getInstalledAddons(), enabledScrapers) { addons, scrapers ->
        PlaybackAvailability(addons, scrapers, isLoaded = true, cachedMeta = metaRepository::getCachedMeta)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlaybackAvailability())
}

@Composable
internal fun PlaybackAvailabilityProvider(content: @Composable () -> Unit) {
    val viewModel: PlaybackAvailabilityViewModel = hiltViewModel()
    val availability by viewModel.availability.collectAsStateWithLifecycle()
    CompositionLocalProvider(LocalPlaybackAvailability provides availability, content = content)
}

internal fun PlaybackAvailability.canStream(item: ContinueWatchingItem): Boolean = when (item) {
    is ContinueWatchingItem.InProgress -> canStream(
        item.progress.contentType, item.progress.videoId, item.progress.contentId
    )
    is ContinueWatchingItem.NextUp -> canStream(
        item.info.contentType, item.info.videoId, item.info.contentId
    )
}
