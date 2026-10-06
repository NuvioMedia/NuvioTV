package com.nuvio.tv.ui.screens.player.audiosync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** State of the on-device speech recognition model used by audio subtitle sync. */
data class SpeechModelState(
    val supported: Boolean = false,
    val sizeMb: Int = 0,
    val downloaded: Boolean = false,
    val downloading: Boolean = false,
    /** 0..1 while downloading. */
    val progress: Float = 0f,
    val error: String? = null,
)

/** What audio subtitle sync is doing right now, for the on-screen status line. */
data class SubtitleSyncDiagnostics(
    val phase: Phase,
    /** How the current offset was found, when synced. */
    val method: String? = null,
    val offsetMs: Long? = null,
    /**
     * Identifies the mapping in effect. It changes when the sync changes, not as playback moves
     * (with a frame-rate correction [offsetMs] grows every second).
     */
    val mapping: String = "",
    /** Seconds of audio already analysed ahead of the playhead; 0 in live-only mode. */
    val lookAheadSec: Int = 0,
    val liveOnly: Boolean = false,
    val speechHeardSec: Int = 0,
    val wordsHeard: Int = 0,
    val recognizer: String = "",
    val reference: String = "",
    /** Progress of testing the other subtitles in the same language, e.g. "testing 4 · 7 ruled out". */
    val alternatives: String = "",
    /** Set when subtitles are stretched to another frame rate, e.g. "subtitle stretched ×1.043". */
    val rate: String? = null,
    /** Progress of sampling audio across the film, e.g. "sampled 2 of 4 dialogue spots (40 MB)". */
    val sampling: String = "",
    /** Something worth telling the user, such as an automatic switch to a better-matching subtitle. */
    val notice: String? = null,
    /** Why syncing is slower or not possible, when known. */
    val problem: String? = null,
) {
    enum class Phase { Listening, Estimated, Synced, Unavailable }
}

/** Asks the player screen to select the addon subtitle at [url]; [id] makes repeated requests distinct. */
data class SubtitleSwitchRequest(val url: String, val id: Long)

/**
 * Bridge between the Android audio sync (which owns the model and analysis) and shared UI.
 * Platforms without audio sync leave [speechModel] unsupported and never publish diagnostics.
 */
object SubtitleSyncStatus {
    private val speechModelState = MutableStateFlow(SpeechModelState())
    val speechModel: StateFlow<SpeechModelState> = speechModelState.asStateFlow()

    private val diagnosticsState = MutableStateFlow<SubtitleSyncDiagnostics?>(null)
    val diagnostics: StateFlow<SubtitleSyncDiagnostics?> = diagnosticsState.asStateFlow()

    private val switchRequestState = MutableStateFlow<SubtitleSwitchRequest?>(null)

    /** Latest automatic subtitle switch; the player screen selects it like a user pick. */
    val switchRequests: StateFlow<SubtitleSwitchRequest?> = switchRequestState.asStateFlow()
    private var switchCounter = 0L

    /** Set by the platform that supports downloading the model. */
    var modelActions: SpeechModelActions? = null

    /** Set by the platform that keeps a sync log that can be shared. */
    var logActions: SyncLogActions? = null

    fun publishSpeechModel(state: SpeechModelState) {
        speechModelState.value = state
    }

    fun publishDiagnostics(diagnostics: SubtitleSyncDiagnostics?) {
        diagnosticsState.value = diagnostics
    }

    fun requestSubtitleSwitch(url: String) {
        switchRequestState.value = SubtitleSwitchRequest(url, ++switchCounter)
    }
}

interface SyncLogActions {
    fun share()
}

interface SpeechModelActions {
    fun download()
    fun delete()
}
