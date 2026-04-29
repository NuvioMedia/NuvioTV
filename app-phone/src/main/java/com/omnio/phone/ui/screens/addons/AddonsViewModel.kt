package com.omnio.phone.ui.screens.addons

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omnio.tv.domain.model.Addon
import com.omnio.tv.domain.repository.AddonRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class AddonsViewModel @Inject constructor(
    addonRepository: AddonRepository
) : ViewModel() {

    val addons: StateFlow<List<Addon>> = addonRepository
        .getInstalledAddons()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
