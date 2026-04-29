package com.omnio.phone.ui.screens.profiles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omnio.tv.data.remote.supabase.AvatarRepository
import com.omnio.tv.domain.model.UserProfile
import com.omnio.tv.domain.profile.ProfileManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProfilesViewModel @Inject constructor(
    private val profileManager: ProfileManager,
    private val avatarRepository: AvatarRepository
) : ViewModel() {

    val profiles: StateFlow<List<UserProfile>> = profileManager.profiles
    val activeProfileId: StateFlow<Int> = profileManager.activeProfileId

    private val _avatarUrlsById = MutableStateFlow<Map<String, String>>(emptyMap())
    val avatarUrlsById: StateFlow<Map<String, String>> = _avatarUrlsById.asStateFlow()

    init {
        viewModelScope.launch {
            runCatching { avatarRepository.getAvatarCatalog() }
                .onSuccess { catalog ->
                    _avatarUrlsById.value = catalog.associate { it.id to it.imageUrl }
                }
        }
    }

    fun selectProfile(id: Int) {
        viewModelScope.launch {
            profileManager.setActiveProfile(id)
        }
    }
}
