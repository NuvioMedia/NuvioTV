package com.nuvio.tv.ui.screens.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.UserProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProfileSelectionViewModel @Inject constructor(
    private val profileManager: ProfileManager
) : ViewModel() {

    val profiles: StateFlow<List<UserProfile>> = profileManager.profiles

    fun selectProfile(id: Int, onComplete: () -> Unit) {
        viewModelScope.launch {
            profileManager.setActiveProfile(id)
            onComplete()
        }
    }
}
