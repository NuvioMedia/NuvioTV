package com.nuvio.tv.core.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The last Dolby Vision Profile 5 -> HDR10 note the player showed, kept for the app's lifetime so
 * Settings can show it after the on-screen note has faded.
 */
internal object DolbyVisionP5Status {
    private val lastNoteState = MutableStateFlow<String?>(null)
    val lastNote: StateFlow<String?> = lastNoteState.asStateFlow()

    fun onNote(text: String) {
        lastNoteState.value = text
    }
}
