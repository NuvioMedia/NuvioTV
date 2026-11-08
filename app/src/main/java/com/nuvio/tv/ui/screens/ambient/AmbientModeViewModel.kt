package com.nuvio.tv.ui.screens.ambient

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.local.AmbientModeDataStore
import com.nuvio.tv.domain.model.AmbientModeSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val PRELOAD_DELAY_MS = 20_000L
private const val REFRESH_INTERVAL_MS = 30L * 60_000L

@HiltViewModel
class AmbientModeViewModel @Inject constructor(
    dataStore: AmbientModeDataStore,
    private val loader: AmbientSlideLoader
) : ViewModel() {

    val settings: StateFlow<AmbientModeSettings> = dataStore.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AmbientModeSettings())

    private val _slides = MutableStateFlow<List<AmbientSlide>>(emptyList())
    val slides: StateFlow<List<AmbientSlide>> = _slides.asStateFlow()

    private var loadJob: Job? = null
    private var loadingKeys: List<String>? = null
    private var loadedKeys: List<String>? = null
    private var loadedAt = 0L

    init {
        viewModelScope.launch {
            settings
                .filter { it.enabled }
                .map { it.catalogKeys }
                .distinctUntilChanged()
                .collectLatest { keys ->
                    delay(PRELOAD_DELAY_MS)
                    refresh(keys)
                }
        }
    }

    fun onShown() {
        val keys = settings.value.catalogKeys
        val stale = SystemClock.elapsedRealtime() - loadedAt > REFRESH_INTERVAL_MS
        if (keys != loadedKeys || stale || _slides.value.isEmpty()) {
            refresh(keys)
        } else {
            _slides.value = _slides.value.shuffled()
        }
    }

    private fun refresh(keys: List<String>) {
        if (loadJob?.isActive == true && loadingKeys == keys) return
        loadJob?.cancel()
        loadingKeys = keys
        loadJob = viewModelScope.launch {
            val slides = try {
                loader.load(keys)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            if (slides.isNotEmpty() || keys != loadedKeys) {
                _slides.value = slides
            }
            loadedKeys = keys
            loadedAt = SystemClock.elapsedRealtime()
        }
    }
}
