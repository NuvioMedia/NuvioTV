package com.omnio.phone.ui.screens.profiles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omnio.tv.domain.model.UserProfile
import com.omnio.tv.domain.profile.ProfileManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProfilesViewModel @Inject constructor(
    private val profileManager: ProfileManager
) : ViewModel() {

    val profiles: StateFlow<List<UserProfile>> = profileManager.profiles
    val activeProfileId: StateFlow<Int> = profileManager.activeProfileId

    fun selectProfile(id: Int) {
        viewModelScope.launch {
            profileManager.setActiveProfile(id)
        }
    }
}
