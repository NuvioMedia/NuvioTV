package com.nuvio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.local.ExperienceModeDataStore
import com.nuvio.tv.domain.model.ExperienceMode
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

@HiltViewModel
class ExperienceModeSettingsViewModel @Inject constructor(
    private val experienceModeDataStore: ExperienceModeDataStore
) : ViewModel() {
    val mode: Flow<ExperienceMode?> = experienceModeDataStore.mode

    fun setMode(mode: ExperienceMode) {
        viewModelScope.launch {
            experienceModeDataStore.setMode(mode)
        }
    }
}
