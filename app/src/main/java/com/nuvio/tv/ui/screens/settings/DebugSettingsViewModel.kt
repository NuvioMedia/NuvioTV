package com.nuvio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.data.local.DebugSettingsDataStore
import com.nuvio.tv.data.local.LibraryPreferences
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SavedLibraryItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.random.Random

@HiltViewModel
class DebugSettingsViewModel @Inject constructor(
    private val dataStore: DebugSettingsDataStore,
    private val authManager: AuthManager,
    private val libraryPreferences: LibraryPreferences
) : ViewModel() {

    private val _uiState = MutableStateFlow(DebugSettingsUiState())
    val uiState: StateFlow<DebugSettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            dataStore.accountTabEnabled.collectLatest { enabled ->
                _uiState.update { it.copy(accountTabEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            dataStore.syncCodeFeaturesEnabled.collectLatest { enabled ->
                _uiState.update { it.copy(syncCodeFeaturesEnabled = enabled) }
            }
        }
    }

    fun onEvent(event: DebugSettingsEvent) {
        when (event) {
            is DebugSettingsEvent.ToggleAccountTab -> {
                viewModelScope.launch { dataStore.setAccountTabEnabled(event.enabled) }
            }
            is DebugSettingsEvent.ToggleSyncCodeFeatures -> {
                viewModelScope.launch { dataStore.setSyncCodeFeaturesEnabled(event.enabled) }
            }
            is DebugSettingsEvent.GenerateLibraryItems -> {
                viewModelScope.launch {
                    _uiState.update { it.copy(generateLibraryLoading = true, generateLibraryResult = null) }
                    try {
                        generateRandomLibraryItems(event.count)
                        _uiState.update {
                            it.copy(
                                generateLibraryLoading = false,
                                generateLibraryResult = "Added ${event.count} items to library"
                            )
                        }
                    } catch (e: Exception) {
                        _uiState.update {
                            it.copy(
                                generateLibraryLoading = false,
                                generateLibraryResult = "Failed: ${e.message}"
                            )
                        }
                    }
                }
            }
            is DebugSettingsEvent.SignIn -> {
                viewModelScope.launch {
                    _uiState.update { it.copy(signInLoading = true, signInResult = null) }
                    val result = authManager.signInWithEmail(event.email, event.password)
                    _uiState.update {
                        it.copy(
                            signInLoading = false,
                            signInResult = if (result.isSuccess) "Signed in successfully" else "Failed: ${result.exceptionOrNull()?.message}"
                        )
                    }
                }
            }
        }
    }

    private suspend fun generateRandomLibraryItems(count: Int) {
        val movieTitles = listOf(
            "The Shawshank Redemption", "The Godfather", "The Dark Knight", "Pulp Fiction",
            "Forrest Gump", "Inception", "The Matrix", "Goodfellas", "Fight Club",
            "Interstellar", "The Silence of the Lambs", "Se7en", "The Green Mile",
            "Gladiator", "Saving Private Ryan", "Schindler's List", "The Departed",
            "Whiplash", "Django Unchained", "The Prestige", "Memento", "Alien",
            "Blade Runner", "Jurassic Park", "The Terminator", "Back to the Future",
            "Die Hard", "Mad Max: Fury Road", "Jaws", "Rocky"
        )
        val showTitles = listOf(
            "Breaking Bad", "Game of Thrones", "The Sopranos", "The Wire", "Stranger Things",
            "Chernobyl", "Band of Brothers", "True Detective", "Fargo", "The Office",
            "Friends", "Seinfeld", "Lost", "Dexter", "The Walking Dead", "Westworld",
            "Narcos", "Peaky Blinders", "Ozark", "Better Call Saul", "The Mandalorian",
            "Succession", "Dark", "The Expanse", "Black Mirror", "Mr. Robot",
            "Mindhunter", "The Crown", "Fleabag", "Sherlock"
        )
        val genres = listOf("Action", "Drama", "Comedy", "Thriller", "Sci-Fi", "Horror", "Romance", "Adventure", "Crime", "Fantasy")
        val years = (1990..2025).toList()

        for (i in 1..count) {
            val isMovie = Random.nextBoolean()
            val titles = if (isMovie) movieTitles else showTitles
            val title = "${titles.random()} ${Random.nextInt(1000, 9999)}"
            val type = if (isMovie) "movie" else "series"
            val item = SavedLibraryItem(
                id = "tt${Random.nextInt(1000000, 9999999)}",
                type = type,
                name = title,
                poster = null,
                posterShape = PosterShape.POSTER,
                background = null,
                description = "Debug-generated $type item #$i",
                releaseInfo = years.random().toString(),
                imdbRating = (Math.round((Random.nextFloat() * 4f + 6f) * 10f) / 10f),
                genres = genres.shuffled().take(Random.nextInt(1, 4)),
                addonBaseUrl = null
            )
            libraryPreferences.addItem(item)
        }
    }
}

data class DebugSettingsUiState(
    val accountTabEnabled: Boolean = false,
    val syncCodeFeaturesEnabled: Boolean = false,
    val generateLibraryLoading: Boolean = false,
    val generateLibraryResult: String? = null,
    val signInLoading: Boolean = false,
    val signInResult: String? = null
)

sealed class DebugSettingsEvent {
    data class ToggleAccountTab(val enabled: Boolean) : DebugSettingsEvent()
    data class ToggleSyncCodeFeatures(val enabled: Boolean) : DebugSettingsEvent()
    data class GenerateLibraryItems(val count: Int) : DebugSettingsEvent()
    data class SignIn(val email: String, val password: String) : DebugSettingsEvent()
}
