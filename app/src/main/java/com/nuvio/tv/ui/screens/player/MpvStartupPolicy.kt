package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.data.local.InternalPlayerEngine

internal object MpvStartupPolicy {

    data class AttachInput(
        val hasView: Boolean,
        val sameViewAlreadyBound: Boolean = false,
        val usingMpvEngine: Boolean = true,
        val streamUrlPresent: Boolean = true,
        val mediaLoadPrepared: Boolean,
        val initializationInProgress: Boolean,
        val mediaAlreadyLoaded: Boolean = false,
    )

    sealed class AttachDecision {
        data object None : AttachDecision()
        data object BindWithoutLoad : AttachDecision()
        data object LoadMedia : AttachDecision()
    }

    enum class StartupStep {
        PublishMpvEngineToUi,
        MarkInitializationInProgress,
        AwaitAfrAndMaybeSwitchDisplayMode,
        SettleDelayAfterDisplaySwitch,
        InitializeMpvPlayer,
        FinishInitialization,
        ComposeAttachIfNotYetAttached,
    }

    data class SimulationResult(
        val mediaLoaded: Boolean,
        val surfaceCreatedBeforeDisplaySwitch: Boolean,
        val surfaceCreatedAfterDisplaySwitch: Boolean,
        val displayModeSwitched: Boolean,
        val surfaceStale: Boolean,
        val stuckAtStart: Boolean,
    )

    fun currentAttachDecision(input: AttachInput): AttachDecision {
        if (!input.hasView) return AttachDecision.None
        if (!input.usingMpvEngine || !input.streamUrlPresent) {
            return AttachDecision.BindWithoutLoad
        }
        if (input.sameViewAlreadyBound && input.mediaAlreadyLoaded) {
            return AttachDecision.None
        }
        if (!input.mediaLoadPrepared || input.initializationInProgress) {
            return if (input.sameViewAlreadyBound) AttachDecision.None else AttachDecision.BindWithoutLoad
        }
        return AttachDecision.LoadMedia
    }

    fun publishedUiEngine(
        effectiveEngine: InternalPlayerEngine,
        mpvSurfaceAllowed: Boolean,
        currentUiEngine: InternalPlayerEngine,
    ): InternalPlayerEngine {
        if (effectiveEngine == InternalPlayerEngine.MVP_PLAYER && !mpvSurfaceAllowed) {
            return if (currentUiEngine == InternalPlayerEngine.MVP_PLAYER) {
                InternalPlayerEngine.EXOPLAYER
            } else {
                currentUiEngine
            }
        }
        return effectiveEngine
    }

    fun currentAutoMpvColdStartSteps(): List<StartupStep> = listOf(
        StartupStep.MarkInitializationInProgress,
        StartupStep.AwaitAfrAndMaybeSwitchDisplayMode,
        StartupStep.PublishMpvEngineToUi,
        StartupStep.InitializeMpvPlayer,
        StartupStep.FinishInitialization,
        StartupStep.ComposeAttachIfNotYetAttached,
    )

    fun legacyAutoMpvColdStartSteps(): List<StartupStep> = listOf(
        StartupStep.PublishMpvEngineToUi,
        StartupStep.MarkInitializationInProgress,
        StartupStep.AwaitAfrAndMaybeSwitchDisplayMode,
        StartupStep.SettleDelayAfterDisplaySwitch,
        StartupStep.InitializeMpvPlayer,
        StartupStep.FinishInitialization,
        StartupStep.ComposeAttachIfNotYetAttached,
    )

    fun safeAutoMpvColdStartSteps(): List<StartupStep> = currentAutoMpvColdStartSteps()

    fun exoPlayerThenManualMpvSteps(): List<StartupStep> = listOf(
        StartupStep.AwaitAfrAndMaybeSwitchDisplayMode,
        StartupStep.PublishMpvEngineToUi,
        StartupStep.MarkInitializationInProgress,
        StartupStep.InitializeMpvPlayer,
        StartupStep.FinishInitialization,
        StartupStep.ComposeAttachIfNotYetAttached,
    )

    fun currentWaitingSurfaceAttachSteps(): List<StartupStep> = listOf(
        StartupStep.PublishMpvEngineToUi,
        StartupStep.MarkInitializationInProgress,
        StartupStep.InitializeMpvPlayer,
        StartupStep.FinishInitialization,
        StartupStep.ComposeAttachIfNotYetAttached,
    )

    @Suppress("UNUSED_PARAMETER")
    fun isSurfaceStaleAfterAfr(
        createdBeforeDisplayModeSwitch: Boolean,
        displayModeSwitched: Boolean,
        settleDelayElapsed: Boolean = true,
    ): Boolean = createdBeforeDisplayModeSwitch && displayModeSwitched

    fun simulate(
        steps: List<StartupStep>,
        displayModeSwitches: Boolean,
        attachDecision: (AttachInput) -> AttachDecision = ::currentAttachDecision,
        attachAfterInitializeSeesNullView: Boolean = false,
    ): SimulationResult {
        var uiShowsMpv = false
        var viewAttached = false
        var boundViewGeneration = 0
        var liveViewGeneration = 0
        var initializationInProgress = false
        var mediaLoadPrepared = false
        var mediaAlreadyLoaded = false
        var mediaLoaded = false
        var displayModeSwitched = false
        var surfaceCreatedBeforeDisplaySwitch = false
        var surfaceCreatedAfterDisplaySwitch = false

        fun tryAttach() {
            if (!uiShowsMpv) return
            if (!viewAttached) {
                liveViewGeneration += 1
                viewAttached = true
                if (displayModeSwitched) {
                    surfaceCreatedAfterDisplaySwitch = true
                } else {
                    surfaceCreatedBeforeDisplaySwitch = true
                }
            }
            val sameViewAlreadyBound =
                boundViewGeneration != 0 && boundViewGeneration == liveViewGeneration
            val decision = attachDecision(
                AttachInput(
                    hasView = true,
                    sameViewAlreadyBound = sameViewAlreadyBound,
                    usingMpvEngine = true,
                    streamUrlPresent = true,
                    mediaLoadPrepared = mediaLoadPrepared,
                    initializationInProgress = initializationInProgress,
                    mediaAlreadyLoaded = mediaAlreadyLoaded,
                )
            )
            if (decision != AttachDecision.None) {
                boundViewGeneration = liveViewGeneration
            }
            if (decision == AttachDecision.LoadMedia) {
                mediaLoaded = true
                mediaAlreadyLoaded = true
            }
        }

        fun composeIfUiShowsMpv() {
            if (uiShowsMpv) tryAttach()
        }

        for (step in steps) {
            when (step) {
                StartupStep.PublishMpvEngineToUi -> {
                    uiShowsMpv = true
                }
                StartupStep.MarkInitializationInProgress -> {
                    initializationInProgress = true
                }
                StartupStep.AwaitAfrAndMaybeSwitchDisplayMode -> {
                    composeIfUiShowsMpv()
                    if (displayModeSwitches) {
                        displayModeSwitched = true
                    }
                }
                StartupStep.SettleDelayAfterDisplaySwitch -> {
                    composeIfUiShowsMpv()
                }
                StartupStep.InitializeMpvPlayer -> {
                    mediaLoadPrepared = true
                    if (viewAttached) {
                        mediaLoaded = true
                        mediaAlreadyLoaded = true
                    } else if (attachAfterInitializeSeesNullView) {
                        composeIfUiShowsMpv()
                    }
                }
                StartupStep.FinishInitialization -> {
                    initializationInProgress = false
                }
                StartupStep.ComposeAttachIfNotYetAttached -> {
                    composeIfUiShowsMpv()
                }
            }
        }

        val surfaceStale = isSurfaceStaleAfterAfr(
            createdBeforeDisplayModeSwitch = surfaceCreatedBeforeDisplaySwitch,
            displayModeSwitched = displayModeSwitched,
        )
        return SimulationResult(
            mediaLoaded = mediaLoaded,
            surfaceCreatedBeforeDisplaySwitch = surfaceCreatedBeforeDisplaySwitch,
            surfaceCreatedAfterDisplaySwitch = surfaceCreatedAfterDisplaySwitch,
            displayModeSwitched = displayModeSwitched,
            surfaceStale = surfaceStale,
            stuckAtStart = !mediaLoaded || surfaceStale,
        )
    }
}
