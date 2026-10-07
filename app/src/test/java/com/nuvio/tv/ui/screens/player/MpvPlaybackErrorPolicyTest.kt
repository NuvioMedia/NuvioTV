package com.nuvio.tv.ui.screens.player

import `is`.xyz.mpv.MPVNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MpvPlaybackErrorPolicyTest {

    @Test
    fun parseEndFile_readsStringReasonAndFileError() {
        val event = parseMpvEndFile(
            MPVNode.MapNode(
                mapOf(
                    "reason" to MPVNode.StringNode("error"),
                    "file_error" to MPVNode.StringNode("audio output initialization failed"),
                    "playlist_entry_id" to MPVNode.IntNode(7)
                )
            )
        )

        assertTrue(event.isPlaybackError)
        assertEquals("audio output initialization failed", event.fileError)
        assertEquals(7L, event.playlistEntryId)
        assertEquals(14, mpvErrorCodeForFileError(event.fileError))
    }

    @Test
    fun parseEndFile_ignoresEofAndStop() {
        assertFalse(parseMpvEndFile(endFile(reason = "eof")).isPlaybackError)
        assertFalse(parseMpvEndFile(endFile(reason = "stop")).isPlaybackError)
        assertFalse(parseMpvEndFile(endFile(reason = "redirect")).isPlaybackError)
    }

    @Test
    fun parseEndFile_integerReasonIsNotAFailure() {
        val event = parseMpvEndFile(
            MPVNode.MapNode(
                mapOf(
                    "reason" to MPVNode.IntNode(4),
                    "file_error" to MPVNode.IntNode(13)
                )
            )
        )

        assertFalse(event.isPlaybackError)
        assertNull(event.fileError)
    }

    @Test
    fun fileErrorStrings_mapToReportCodes() {
        assertEquals(13, mpvErrorCodeForFileError(null))
        assertEquals(13, mpvErrorCodeForFileError("loading failed"))
        assertEquals(15, mpvErrorCodeForFileError("video output initialization failed"))
        assertEquals(17, mpvErrorCodeForFileError("unrecognized file format"))
        assertEquals(16, mpvErrorCodeForFileError("no audio or video data played"))
        assertEquals(20, mpvErrorCodeForFileError("something happened"))
    }

    @Test
    fun staleEndFile_requiresBothIdsAndAMismatch() {
        assertFalse(isStaleMpvEndFile(activePlaylistEntryId = null, endedPlaylistEntryId = 3))
        assertFalse(isStaleMpvEndFile(activePlaylistEntryId = 3, endedPlaylistEntryId = null))
        assertFalse(isStaleMpvEndFile(activePlaylistEntryId = 3, endedPlaylistEntryId = 3))
        assertTrue(isStaleMpvEndFile(activePlaylistEntryId = 4, endedPlaylistEntryId = 3))
    }

    @Test
    fun watchdog_surfaceWaitDoesNotCountAsIdle() {
        val before = advance(MpvStartupWatchdogPolicy.surfaceWaitTicks - 1, waitingForSurface = true)
        assertEquals(MpvStartupWatchdogPolicy.Action.Continue, before.action)

        val fired = advance(MpvStartupWatchdogPolicy.surfaceWaitTicks, waitingForSurface = true)
        assertEquals(MpvStartupWatchdogPolicy.Action.SurfaceTimeout, fired.action)
    }

    @Test
    fun watchdog_idleAfterLoadFailsFast() {
        val before = advance(MpvStartupWatchdogPolicy.idleTicksLimit - 1, idleActive = true)
        assertEquals(MpvStartupWatchdogPolicy.Action.Continue, before.action)

        val fired = advance(MpvStartupWatchdogPolicy.idleTicksLimit, idleActive = true)
        assertEquals(MpvStartupWatchdogPolicy.Action.IdleError, fired.action)
    }

    @Test
    fun watchdog_noCacheGrowthFailsAtStallTimeout() {
        val before = advance(MpvStartupWatchdogPolicy.stallTicksLimit - 1)
        assertEquals(MpvStartupWatchdogPolicy.Action.Continue, before.action)

        val fired = advance(MpvStartupWatchdogPolicy.stallTicksLimit)
        assertEquals(MpvStartupWatchdogPolicy.Action.StallTimeout, fired.action)
    }

    @Test
    fun watchdog_cacheGrowthExtendsWaitUntilAbsoluteTimeout() {
        val stillWaiting = advance(
            ticks = MpvStartupWatchdogPolicy.stallTicksLimit,
            cacheProgressing = true
        )
        assertEquals(MpvStartupWatchdogPolicy.Action.Continue, stillWaiting.action)
        assertEquals(0, stillWaiting.counters.stallTicks)

        val fired = advance(
            ticks = MpvStartupWatchdogPolicy.absoluteTicksLimit,
            cacheProgressing = true
        )
        assertEquals(MpvStartupWatchdogPolicy.Action.AbsoluteTimeout, fired.action)
    }

    @Test
    fun watchdog_idleBlipsDoNotResetTheAbsoluteClock() {
        var counters = MpvStartupWatchdogPolicy.Counters()
        var action = MpvStartupWatchdogPolicy.Action.Continue
        for (index in 0 until MpvStartupWatchdogPolicy.absoluteTicksLimit + 8) {
            val step = MpvStartupWatchdogPolicy.step(
                MpvStartupWatchdogPolicy.Input(
                    enabled = true,
                    waitingForSurface = false,
                    idleActive = index % 8 != 7,
                    cacheProgressing = false,
                    counters = counters
                )
            )
            counters = step.counters
            action = step.action
            if (action != MpvStartupWatchdogPolicy.Action.Continue) break
        }

        assertEquals(MpvStartupWatchdogPolicy.Action.AbsoluteTimeout, action)
    }

    @Test
    fun watchdog_pauseClearsAccumulatedTicks() {
        val partial = advance(10)
        val paused = MpvStartupWatchdogPolicy.step(
            MpvStartupWatchdogPolicy.Input(
                enabled = false,
                waitingForSurface = false,
                idleActive = true,
                cacheProgressing = false,
                counters = partial.counters
            )
        )

        assertEquals(MpvStartupWatchdogPolicy.Action.Continue, paused.action)
        assertEquals(MpvStartupWatchdogPolicy.Counters(), paused.counters)
    }

    @Test
    fun directError_showsHttpCauseWithoutUrl() {
        val message = mpvDirectErrorMessage(
            fileError = "loading failed",
            logLine = "[ffmpeg] https: HTTP error 428 \n" +
                "[stream] Failed to open https://example.com/video.mp4?psig=secret\n" +
                "[ytdl_hook] Subprocess failed: init",
            fallback = "Playback error",
            httpExplanation = "\n\nThe stream source is blocked or restricted. Try a different source."
        )

        assertEquals(
            "The stream source is blocked or restricted. Try a different source.\n\nHTTP 428 [loading failed]",
            message
        )
        assertFalse(message.contains("example.com"))
        assertFalse(message.contains("psig"))
        assertFalse(message.contains("ytdl_hook"))
        assertFalse(message.contains("Failed to open"))
    }

    @Test
    fun directError_appendsTheSameHttpExplanationAsExo() {
        val message = mpvDirectErrorMessage(
            fileError = "loading failed",
            logLine = "[ffmpeg] HTTP error 403 Forbidden",
            fallback = "Playback error",
            httpExplanation = "\n\nThe stream source is blocked or restricted. Try a different source."
        )

        assertEquals(
            "The stream source is blocked or restricted. Try a different source.\n\nHTTP 403 Forbidden [loading failed]",
            message
        )
    }

    @Test
    fun directError_keepsDecoderDetailWithFileError() {
        assertEquals(
            "video output initialization failed\n" +
                "Could not open codec.\n" +
                "Failed (re)creating swapchain!\n" +
                "hevc: No decoder available for hardware decoding",
            mpvDirectErrorMessage(
                fileError = "video output initialization failed",
                logLine = "[vd] Could not open codec.\n" +
                    "[cplayer] Could not initialize video chain.\n" +
                    "[vo/gpu/libplacebo] Failed (re)creating swapchain!\n" +
                    "[ffmpeg] hevc @ 0x55aa: No decoder available for hardware decoding\n" +
                    "[ytdl_hook] Subprocess failed: init",
                fallback = "Playback error"
            )
        )
    }

    @Test
    fun directError_putsFormatExplanationAboveCodecDetail() {
        val message = mpvDirectErrorMessage(
            fileError = "video output initialization failed",
            logLine = "[vd] Could not open codec.\n" +
                "[ffmpeg] hevc: No decoder available for hardware decoding\n" +
                "mp4, video hevc, 1920x1080, yuv420p10, hwdec mediacodec",
            fallback = "Playback error",
            statusExplanation = "This stream uses a format your device may not support. Try a different source. [video output initialization failed]"
        )

        assertEquals(
            "This stream uses a format your device may not support. Try a different source. [video output initialization failed]\n\n" +
                "video output initialization failed\n" +
                "Could not open codec.\n" +
                "hevc: No decoder available for hardware decoding\n" +
                "mp4, video hevc, 1920x1080, yuv420p10, hwdec mediacodec",
            message
        )
    }

    @Test
    fun directError_showsOpenAndFormatCausesUnderTheFileError() {
        assertEquals(
            "loading failed\nVO: [gpu] 1920x1080 yuv420p\nUsing hardware decoding (mediacodec).",
            mpvDirectErrorMessage(
                fileError = "loading failed",
                logLine = "[cplayer] VO: [gpu] 1920x1080 yuv420p\n[vd] Using hardware decoding (mediacodec).",
                fallback = "Playback error"
            )
        )
        assertEquals(
            "loading failed\nConnection refused",
            mpvDirectErrorMessage(
                fileError = "loading failed",
                logLine = "[stream] Failed to open\n[ffmpeg] Connection refused",
                fallback = "Playback error"
            )
        )
        assertEquals(
            "unrecognized file format\nInvalid data found when processing input",
            mpvDirectErrorMessage(
                fileError = "unrecognized file format",
                logLine = "[lavf] Invalid data found when processing input",
                fallback = "Playback error"
            )
        )
        assertEquals(
            "audio output initialization failed\nFailed to initialize audio driver 'audiotrack'",
            mpvDirectErrorMessage(
                fileError = "audio output initialization failed",
                logLine = "[ao] Failed to initialize audio driver 'audiotrack'\n" +
                    "[cplayer] Could not open/initialize audio device -> no sound.",
                fallback = "Playback error"
            )
        )
    }

    @Test
    fun directError_keepsPlaybackFileErrorsUnderTheExistingExplanation() {
        listOf(
            "loading failed",
            "audio output initialization failed",
            "video output initialization failed",
            "unrecognized file format",
            "no audio or video data played",
            "not supported",
            "something happened"
        ).forEach { fileError ->
            val message = mpvDirectErrorMessage(
                fileError = fileError,
                logLine = null,
                fallback = "Playback error",
                statusExplanation = "Existing explanation"
            )
            assertTrue(message, message.contains(fileError))
            assertTrue(message.startsWith("Existing explanation\n\n$fileError"))
        }
    }

    @Test
    fun directError_putsOpenExplanationAboveLoadingFailed() {
        assertEquals(
            "The stream server is currently unavailable. Try a different source.\n\nloading failed",
            mpvDirectErrorMessage(
                fileError = "loading failed",
                logLine = null,
                fallback = "Playback error",
                statusExplanation = "The stream server is currently unavailable. Try a different source."
            )
        )
    }

    @Test
    fun nonHttpKind_separatesFormatInvalidContentAndOpenFailure() {
        assertEquals(
            MpvNonHttpExplanationKind.UnsupportedFormat,
            mpvNonHttpExplanationKind("video output initialization failed", "[vd] Could not open codec.")
        )
        assertEquals(
            MpvNonHttpExplanationKind.InvalidContent,
            mpvNonHttpExplanationKind("unrecognized file format", null)
        )
        assertEquals(
            MpvNonHttpExplanationKind.OpenFailed,
            mpvNonHttpExplanationKind("loading failed", null)
        )
    }

    @Test
    fun warnLine_keepsCodecReasonAndDropsUnrelatedNoise() {
        assertTrue(mpvWarnLineIsUseful("vd", "Hardware decoding of hevc is not supported"))
        assertTrue(mpvWarnLineIsUseful("ffmpeg/video", "h264_mediacodec: Failed to initialize decoder"))
        assertTrue(mpvWarnLineIsUseful("vo/gpu/libplacebo", "Failed (re)creating swapchain!"))
        assertTrue(mpvWarnLineIsUseful("cplayer", "Could not initialize video chain."))
        assertTrue(mpvWarnLineIsUseful("vd", "Error while decoding frame!"))
        assertTrue(mpvWarnLineIsUseful("mkv", "EBML header parsing failed"))
        assertTrue(mpvWarnLineIsUseful("ffmpeg", "certificate verify failed"))
        assertTrue(mpvWarnLineIsUseful("autoconvert", "can't find video conversion for yuv420p10"))
        assertFalse(mpvWarnLineIsUseful("cplayer", "Timestamp discontinuity"))
        assertFalse(mpvWarnLineIsUseful("ffmpeg", "Queue overflow"))
        assertFalse(mpvWarnLineIsUseful("stream", "Failed to open"))
        assertFalse(mpvWarnLineIsUseful("ytdl_hook", "codec not supported"))
        assertTrue(mpvInfoLineIsUseful("VO: [gpu] 1920x1080 yuv420p"))
        assertTrue(mpvInfoLineIsUseful("Using hardware decoding (mediacodec)."))
        assertFalse(mpvInfoLineIsUseful("fps 23.976"))
    }

    @Test
    fun rememberLog_keepsTheCauseWhenLaterLinesFillTheBuffer() {
        var remembered: String? = "[ffmpeg] Connection refused"
        repeat(8) { index ->
            remembered = rememberMpvErrorLog(remembered, "[cplayer] packet $index")
        }
        val message = mpvDirectErrorMessage(
            fileError = "loading failed",
            logLine = remembered,
            fallback = "Playback error"
        )
        assertTrue(message.contains("Connection refused"))
    }

    @Test
    fun rememberLog_keepsOpenFailureAheadOfYtdlNoise() {
        val remembered = rememberMpvErrorLog(current = null, line = "[ffmpeg] https: HTTP error 428")
            .let { rememberMpvErrorLog(it, "[stream] Failed to open") }
            .let { rememberMpvErrorLog(it, "[ytdl_hook] Subprocess failed: init") }
            .let { rememberMpvErrorLog(it, "[ytdl_hook] youtube-dl failed: not found or not enough permissions") }
            .let { rememberMpvErrorLog(it, "[cplayer] loading failed") }

        assertEquals(
            "HTTP 428 [loading failed]",
            mpvDirectErrorMessage(fileError = "loading failed", logLine = remembered, fallback = "Playback error")
        )
    }

    @Test
    fun directError_usesFileErrorWhenMpvLoggedNothing() {
        assertEquals(
            "audio output initialization failed",
            mpvDirectErrorMessage(
                fileError = "audio output initialization failed",
                logLine = null,
                fallback = "Playback error"
            )
        )
    }

    @Test
    fun cacheGrowth_ignoresNoiseBelowAQuarterSecond() {
        assertFalse(MpvStartupWatchdogPolicy.cacheIsProgressing(previousSec = 1.0, currentSec = 1.2))
        assertTrue(MpvStartupWatchdogPolicy.cacheIsProgressing(previousSec = 1.0, currentSec = 1.3))
    }

    private fun endFile(reason: String): MPVNode {
        return MPVNode.MapNode(mapOf("reason" to MPVNode.StringNode(reason)))
    }

    private fun advance(
        ticks: Int,
        waitingForSurface: Boolean = false,
        idleActive: Boolean = false,
        cacheProgressing: Boolean = false,
    ): MpvStartupWatchdogPolicy.Step {
        var counters = MpvStartupWatchdogPolicy.Counters()
        var step = MpvStartupWatchdogPolicy.Step(MpvStartupWatchdogPolicy.Action.Continue, counters)
        repeat(ticks) {
            step = MpvStartupWatchdogPolicy.step(
                MpvStartupWatchdogPolicy.Input(
                    enabled = true,
                    waitingForSurface = waitingForSurface,
                    idleActive = idleActive,
                    cacheProgressing = cacheProgressing,
                    counters = counters
                )
            )
            counters = step.counters
            if (step.action != MpvStartupWatchdogPolicy.Action.Continue) return step
        }
        return step
    }
}
