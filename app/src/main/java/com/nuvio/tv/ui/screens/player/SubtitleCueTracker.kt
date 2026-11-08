package com.nuvio.tv.ui.screens.player

import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.exoplayer.text.TextOutput
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class SubtitleCueState(
    val version: Long = 0L,
    val visible: Boolean = false,
    val text: String = "",
    val hasBitmap: Boolean = false
)

internal class SubtitleCueTracker {
    private val mutableState = MutableStateFlow(SubtitleCueState())
    val state: StateFlow<SubtitleCueState> = mutableState.asStateFlow()
    private val mutablePrescannedTexts = MutableStateFlow<List<String>>(emptyList())
    val prescannedTexts: StateFlow<List<String>> = mutablePrescannedTexts.asStateFlow()

    @Volatile
    private var lastKey: String? = null

    fun update(cues: List<Cue>) {
        val hasBitmap = cues.any { it.bitmap != null }
        publish(
            key = cues.joinToString("\u0000") { cue ->
                cue.bitmap?.let { "\u0001${System.identityHashCode(it)}" } ?: cue.text?.toString().orEmpty()
            },
            visible = cues.any { !it.text.isNullOrBlank() || it.bitmap != null },
            text = cues.mapNotNull { it.text?.toString()?.takeIf(String::isNotBlank) }.joinToString("\n"),
            hasBitmap = hasBitmap
        )
    }

    fun updateText(text: String?) {
        val key = text.orEmpty()
        publish(key = key, visible = key.isNotBlank(), text = key, hasBitmap = false)
    }

    fun setPrescannedTexts(texts: List<String>) {
        mutablePrescannedTexts.value = texts
    }

    @Synchronized
    private fun publish(key: String, visible: Boolean, text: String, hasBitmap: Boolean) {
        if (key == lastKey) return
        lastKey = key
        mutableState.value = SubtitleCueState(mutableState.value.version + 1, visible, text, hasBitmap)
    }
}

internal class CueTrackingTextOutput(
    private val delegate: TextOutput,
    private val onCuesShown: (List<Cue>) -> Unit
) : TextOutput {
    override fun onCues(cueGroup: CueGroup) {
        delegate.onCues(cueGroup)
        onCuesShown(cueGroup.cues)
    }

    @Deprecated("Uses the deprecated Media3 callback for text outputs.")
    override fun onCues(cues: List<Cue>) {
        @Suppress("DEPRECATION")
        delegate.onCues(cues)
        onCuesShown(cues)
    }
}
