package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.data.local.InternalPlayerEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MpvStartupPolicyTest {

    @Test
    fun attachSkipsLoadWhenMediaNotPrepared() {
        val decision = MpvStartupPolicy.currentAttachDecision(
            MpvStartupPolicy.AttachInput(
                hasView = true,
                mediaLoadPrepared = false,
                initializationInProgress = false,
            )
        )
        assertEquals(MpvStartupPolicy.AttachDecision.BindWithoutLoad, decision)
    }

    @Test
    fun attachSkipsLoadWhileInitializationInProgress() {
        val decision = MpvStartupPolicy.currentAttachDecision(
            MpvStartupPolicy.AttachInput(
                hasView = true,
                mediaLoadPrepared = true,
                initializationInProgress = true,
            )
        )
        assertEquals(MpvStartupPolicy.AttachDecision.BindWithoutLoad, decision)
    }

    @Test
    fun sameViewReattachNeverRetriesLoadWhenAlreadyLoaded() {
        val decision = MpvStartupPolicy.currentAttachDecision(
            MpvStartupPolicy.AttachInput(
                hasView = true,
                sameViewAlreadyBound = true,
                mediaLoadPrepared = true,
                initializationInProgress = false,
                mediaAlreadyLoaded = true,
            )
        )
        assertEquals(MpvStartupPolicy.AttachDecision.None, decision)
    }

    @Test
    fun sameViewReattachLoadsWhenPreparedAndNeverLoaded() {
        val decision = MpvStartupPolicy.currentAttachDecision(
            MpvStartupPolicy.AttachInput(
                hasView = true,
                sameViewAlreadyBound = true,
                mediaLoadPrepared = true,
                initializationInProgress = false,
                mediaAlreadyLoaded = false,
            )
        )
        assertEquals(MpvStartupPolicy.AttachDecision.LoadMedia, decision)
    }

    @Test
    fun attachLoadsWhenPreparedAndInitNotInProgress() {
        val decision = MpvStartupPolicy.currentAttachDecision(
            MpvStartupPolicy.AttachInput(
                hasView = true,
                mediaLoadPrepared = true,
                initializationInProgress = false,
            )
        )
        assertEquals(MpvStartupPolicy.AttachDecision.LoadMedia, decision)
    }

    @Test
    fun settleDelayDoesNotClearStaleSurface() {
        assertTrue(
            MpvStartupPolicy.isSurfaceStaleAfterAfr(
                createdBeforeDisplayModeSwitch = true,
                displayModeSwitched = true,
                settleDelayElapsed = true,
            )
        )
        assertFalse(
            MpvStartupPolicy.isSurfaceStaleAfterAfr(
                createdBeforeDisplayModeSwitch = true,
                displayModeSwitched = false,
                settleDelayElapsed = true,
            )
        )
        assertFalse(
            MpvStartupPolicy.isSurfaceStaleAfterAfr(
                createdBeforeDisplayModeSwitch = false,
                displayModeSwitched = true,
                settleDelayElapsed = true,
            )
        )
    }

    @Test
    fun autoMpvLegacyOrderGetsStuckWhenAfrSwitchesDisplayMode() {
        val result = MpvStartupPolicy.simulate(
            steps = MpvStartupPolicy.legacyAutoMpvColdStartSteps(),
            displayModeSwitches = true,
        )

        assertTrue(result.displayModeSwitched)
        assertTrue(result.surfaceCreatedBeforeDisplaySwitch)
        assertFalse(result.surfaceCreatedAfterDisplaySwitch)
        assertTrue(result.mediaLoaded)
        assertTrue(result.surfaceStale)
        assertTrue(result.stuckAtStart)
    }

    @Test
    fun autoMpvCurrentOrderIsNotStuckWhenAfrSwitchesDisplayMode() {
        val result = MpvStartupPolicy.simulate(
            steps = MpvStartupPolicy.currentAutoMpvColdStartSteps(),
            displayModeSwitches = true,
        )

        assertTrue(result.displayModeSwitched)
        assertFalse(result.surfaceCreatedBeforeDisplaySwitch)
        assertTrue(result.surfaceCreatedAfterDisplaySwitch)
        assertTrue(result.mediaLoaded)
        assertFalse(result.surfaceStale)
        assertFalse(result.stuckAtStart)
    }

    @Test
    fun autoMpvCurrentOrderIsNotStuckWhenDisplayModeDoesNotSwitch() {
        val result = MpvStartupPolicy.simulate(
            steps = MpvStartupPolicy.currentAutoMpvColdStartSteps(),
            displayModeSwitches = false,
        )

        assertFalse(result.displayModeSwitched)
        assertTrue(result.mediaLoaded)
        assertFalse(result.surfaceStale)
        assertFalse(result.stuckAtStart)
    }

    @Test
    fun attachDuringWaitingSurfaceLoadsAfterInitFinishes() {
        val result = MpvStartupPolicy.simulate(
            steps = MpvStartupPolicy.currentWaitingSurfaceAttachSteps(),
            displayModeSwitches = false,
            attachAfterInitializeSeesNullView = true,
        )

        assertTrue(result.mediaLoaded)
        assertFalse(result.stuckAtStart)
    }

    @Test
    fun attachAfterWaitingSurfaceInitFinishesStillLoads() {
        val result = MpvStartupPolicy.simulate(
            steps = MpvStartupPolicy.currentWaitingSurfaceAttachSteps(),
            displayModeSwitches = false,
            attachAfterInitializeSeesNullView = false,
        )

        assertTrue(result.mediaLoaded)
        assertFalse(result.stuckAtStart)
    }

    @Test
    fun exoPlayerThenManualMpvAfterAfrIsNotStuck() {
        val result = MpvStartupPolicy.simulate(
            steps = MpvStartupPolicy.exoPlayerThenManualMpvSteps(),
            displayModeSwitches = true,
        )

        assertTrue(result.displayModeSwitched)
        assertFalse(result.surfaceCreatedBeforeDisplaySwitch)
        assertTrue(result.surfaceCreatedAfterDisplaySwitch)
        assertTrue(result.mediaLoaded)
        assertFalse(result.surfaceStale)
        assertFalse(result.stuckAtStart)
    }

    @Test
    fun deferringMpvUiUntilAfterAfrAvoidsStaleSurface() {
        val result = MpvStartupPolicy.simulate(
            steps = MpvStartupPolicy.safeAutoMpvColdStartSteps(),
            displayModeSwitches = true,
        )

        assertTrue(result.displayModeSwitched)
        assertFalse(result.surfaceCreatedBeforeDisplaySwitch)
        assertTrue(result.surfaceCreatedAfterDisplaySwitch)
        assertTrue(result.mediaLoaded)
        assertFalse(result.surfaceStale)
        assertFalse(result.stuckAtStart)
    }

    @Test
    fun currentColdStartPublishesUiAfterAfr() {
        val current = MpvStartupPolicy.currentAutoMpvColdStartSteps()

        assertTrue(
            current.indexOf(MpvStartupPolicy.StartupStep.AwaitAfrAndMaybeSwitchDisplayMode) <
                current.indexOf(MpvStartupPolicy.StartupStep.PublishMpvEngineToUi)
        )
        assertEquals(
            MpvStartupPolicy.safeAutoMpvColdStartSteps(),
            current,
        )
    }

    @Test
    fun publishedUiEngineHidesMpvUntilSurfaceIsAllowed() {
        assertEquals(
            InternalPlayerEngine.EXOPLAYER,
            MpvStartupPolicy.publishedUiEngine(
                effectiveEngine = InternalPlayerEngine.MVP_PLAYER,
                mpvSurfaceAllowed = false,
                currentUiEngine = InternalPlayerEngine.MVP_PLAYER,
            )
        )
        assertEquals(
            InternalPlayerEngine.EXOPLAYER,
            MpvStartupPolicy.publishedUiEngine(
                effectiveEngine = InternalPlayerEngine.MVP_PLAYER,
                mpvSurfaceAllowed = false,
                currentUiEngine = InternalPlayerEngine.EXOPLAYER,
            )
        )
        assertEquals(
            InternalPlayerEngine.MVP_PLAYER,
            MpvStartupPolicy.publishedUiEngine(
                effectiveEngine = InternalPlayerEngine.MVP_PLAYER,
                mpvSurfaceAllowed = true,
                currentUiEngine = InternalPlayerEngine.EXOPLAYER,
            )
        )
    }
}
