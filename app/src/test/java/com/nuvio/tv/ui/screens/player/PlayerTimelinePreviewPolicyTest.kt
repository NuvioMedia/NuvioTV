package com.nuvio.tv.ui.screens.player

import org.junit.Assert.*
import org.junit.Test

class PlayerTimelinePreviewPolicyTest {
    private val seeking = PlayerUiState(isBuffering = false, showLoadingOverlay = false,
        pendingPreviewSeekPosition = 5000)
    private val vod = PlaybackTimelineState(duration = 10000)
    private fun allowed(ui: PlayerUiState = seeking, timeline: PlaybackTimelineState = vod,
        foreground: Boolean = true, focused: Boolean = true, video: Boolean = true) =
        canShowTimelinePreview(ui, timeline, foreground, focused, video)

    @Test fun focusedPendingSeekAndHiddenControlSeekAreBothEligible() {
        assertTrue(allowed())
        assertTrue(allowed(seeking.copy(showControls = false, showSeekOverlay = true), focused = false))
    }
    @Test fun ordinaryPlaybackAndFocusOnOtherActionsDoNotStartPreviews() {
        assertFalse(allowed(seeking.copy(pendingPreviewSeekPosition = null)))
        assertFalse(allowed(focused = false))
        assertFalse(allowed(seeking.copy(showControls = false, showSeekOverlay = false)))
    }
    @Test fun backgroundPipOrUnknownVideoCannotAllocateThePreviewDecoder() {
        assertFalse(allowed(foreground = false))
        assertFalse(allowed(video = false))
    }
    @Test fun liveUnknownDurationAndInvalidPositionsAreRejected() {
        assertFalse(allowed(timeline = vod.copy(isLive = true)))
        assertFalse(allowed(timeline = vod.copy(duration = 0)))
        assertFalse(allowed(timeline = vod.copy(duration = -1)))
        assertFalse(allowed(seeking.copy(pendingPreviewSeekPosition = -1)))
        assertFalse(allowed(seeking.copy(pendingPreviewSeekPosition = 10001)))
    }
    @Test fun eachExistingModalOrSidePanelClosesThePreviewLane() {
        listOf(seeking.copy(showPauseOverlay = true), seeking.copy(showAudioOverlay = true),
            seeking.copy(showSubtitleOverlay = true), seeking.copy(showSubtitleStylePanel = true),
            seeking.copy(showSubtitleTimingDialog = true), seeking.copy(showSubtitleDelayOverlay = true),
            seeking.copy(showSpeedDialog = true), seeking.copy(showMoreDialog = true),
            seeking.copy(showSceneBookmarks = true), seeking.copy(showEpisodesPanel = true),
            seeking.copy(showSourcesPanel = true), seeking.copy(showStreamInfoOverlay = true),
            seeking.copy(showPlayerEngineSwitchInfo = true)).forEach { assertFalse(allowed(it)) }
    }
    @Test fun LoadingErrorsCompletionAndExitNeverExposeAnOldFrame() {
        assertFalse(allowed(seeking.copy(isBuffering = true)))
        assertFalse(allowed(seeking.copy(showLoadingOverlay = true)))
        assertFalse(allowed(seeking.copy(error = "fixture")))
        assertFalse(allowed(seeking.copy(playbackEnded = true)))
        assertFalse(allowed(seeking.copy(pendingExitReason = PlayerExitReason.StillWatchingPrompt)))
    }
    @Test fun seekAtTheEndRequestsTheLastFiniteInstantWithoutInventingAnEndFrame() {
        assertTrue(allowed(seeking.copy(pendingPreviewSeekPosition = 10000)))
        assertEquals(9999L, normalizedTimelinePreviewPosition(10000, 10000))
        assertEquals(0L, normalizedTimelinePreviewPosition(0, 1))
        assertNull(normalizedTimelinePreviewPosition(-1, 10000))
        assertNull(normalizedTimelinePreviewPosition(10001, 10000))
        assertNull(normalizedTimelinePreviewPosition(0, 0))
    }
}
