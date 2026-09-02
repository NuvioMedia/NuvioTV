package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LivePlaybackUiPolicyTest {

    @Test
    fun `vod hls is not live without player flag or channel type`() {
        assertFalse(
            LivePlaybackUiPolicy.isLivePlayback(
                playerReportsLive = false,
                contentType = "movie"
            )
        )
        assertFalse(
            LivePlaybackUiPolicy.isLivePlayback(
                playerReportsLive = false,
                contentType = "series"
            )
        )
        assertFalse(
            LivePlaybackUiPolicy.isLivePlayback(
                playerReportsLive = false,
                contentType = "tv"
            )
        )
    }

    @Test
    fun `channel catalog type is live even before player reports`() {
        assertTrue(
            LivePlaybackUiPolicy.isLivePlayback(
                playerReportsLive = false,
                contentType = "channel"
            )
        )
    }

    @Test
    fun `player live window flag marks live hls regardless of movie type`() {
        assertTrue(
            LivePlaybackUiPolicy.isLivePlayback(
                playerReportsLive = true,
                contentType = "movie"
            )
        )
    }

    @Test
    fun `live latch sticks after the player reported live once`() {
        val latched = LivePlaybackUiPolicy.nextLiveLatch(
            playerReportsLive = true,
            previouslyLatched = false
        )
        assertTrue(latched)
        assertTrue(
            LivePlaybackUiPolicy.isLivePlayback(
                playerReportsLive = false,
                contentType = "movie",
                latchedLive = latched
            )
        )
    }

    @Test
    fun `watch clock accumulates only while playing live`() {
        val clock = LivePlaybackWatchClock()
        assertEquals(
            0L,
            clock.watchedDurationMs(isLive = true, isPlaying = true, nowElapsedMs = 1_000L)
        )
        assertEquals(
            6_000L,
            clock.watchedDurationMs(isLive = true, isPlaying = true, nowElapsedMs = 7_000L)
        )
        assertEquals(
            6_000L,
            clock.watchedDurationMs(isLive = true, isPlaying = false, nowElapsedMs = 7_000L)
        )
        assertEquals(
            6_000L,
            clock.watchedDurationMs(isLive = true, isPlaying = false, nowElapsedMs = 20_000L)
        )
        assertEquals(
            6_000L,
            clock.watchedDurationMs(isLive = true, isPlaying = true, nowElapsedMs = 22_000L)
        )
        assertEquals(
            8_000L,
            clock.watchedDurationMs(isLive = true, isPlaying = true, nowElapsedMs = 24_000L)
        )
    }

    @Test
    fun `watch clock resets when leaving live`() {
        val clock = LivePlaybackWatchClock()
        clock.watchedDurationMs(isLive = true, isPlaying = true, nowElapsedMs = 0L)
        clock.watchedDurationMs(isLive = true, isPlaying = false, nowElapsedMs = 4_000L)
        assertEquals(
            0L,
            clock.watchedDurationMs(isLive = false, isPlaying = true, nowElapsedMs = 10_000L)
        )
    }

    @Test
    fun `live progress is full when no forward buffer or within live edge buffer`() {
        val (progress, bufferedProgress) = LivePlaybackUiPolicy.calculateLiveProgress(
            currentPosition = 50_000L,
            bufferedPosition = 50_000L,
            maxBufferMs = 60_000L
        )
        assertEquals(1f, progress, 0.001f)
        assertEquals(1f, bufferedProgress, 0.001f)

        // 3s forward buffer is within live edge buffer (LIVE_EDGE_BUFFER_MS)
        val (p3s, _) = LivePlaybackUiPolicy.calculateLiveProgress(
            currentPosition = 50_000L,
            bufferedPosition = 53_000L,
            maxBufferMs = 60_000L
        )
        assertEquals(1f, p3s, 0.001f)
    }

    @Test
    fun `live progress comes back when buffer accumulates while paused`() {
        val (progress, bufferedProgress) = LivePlaybackUiPolicy.calculateLiveProgress(
            currentPosition = 50_000L,
            bufferedPosition = 68_000L, // 18s forward buffer = 15s accumulated above 3s live edge
            maxBufferMs = 60_000L
        )
        // 1f - (15_000 / 60_000) = 0.75f
        assertEquals(0.75f, progress, 0.001f)
        assertEquals(1f, bufferedProgress, 0.001f)
    }

    @Test
    fun `live progress scales directly with configured buffer setting`() {
        // With 30s buffer setting, 18s forward buffer (15s accumulated) gives 50%
        val (p30, _) = LivePlaybackUiPolicy.calculateLiveProgress(
            currentPosition = 10_000L,
            bufferedPosition = 28_000L,
            maxBufferMs = 30_000L
        )
        assertEquals(0.5f, p30, 0.001f)

        // With 45s buffer setting (default), 18s forward buffer (15s accumulated) gives 66.7%
        val (p45, _) = LivePlaybackUiPolicy.calculateLiveProgress(
            currentPosition = 10_000L,
            bufferedPosition = 28_000L,
            maxBufferMs = 45_000L
        )
        assertEquals(1f - (15f / 45f), p45, 0.001f)
    }

    @Test
    fun `live backward seek is disabled when backbuffer is disabled`() {
        val target = LivePlaybackUiPolicy.calculateLiveSeekTarget(
            currentPosition = 50_000L,
            bufferedPosition = 50_000L,
            deltaMs = -10_000L,
            isBackBufferEnabled = false,
            backBufferDurationMs = 0L
        )
        assertEquals(null, target)
    }

    @Test
    fun `live backward seek is allowed when backbuffer is enabled and clamps to limit`() {
        val target = LivePlaybackUiPolicy.calculateLiveSeekTarget(
            currentPosition = 50_000L,
            bufferedPosition = 50_000L,
            deltaMs = -10_000L,
            isBackBufferEnabled = true,
            backBufferDurationMs = 15_000L
        )
        assertEquals(40_000L, target)

        // Exceeding backbuffer duration clamps to minPosition
        val targetClamped = LivePlaybackUiPolicy.calculateLiveSeekTarget(
            currentPosition = 50_000L,
            bufferedPosition = 50_000L,
            deltaMs = -30_000L,
            isBackBufferEnabled = true,
            backBufferDurationMs = 15_000L
        )
        assertEquals(35_000L, targetClamped)
    }

    @Test
    fun `live forward seek goes to buffered end when buffer is less than 10s and greater than 1s`() {
        val target = LivePlaybackUiPolicy.calculateLiveSeekTarget(
            currentPosition = 50_000L,
            bufferedPosition = 59_000L, // 9s buffer = 6s accumulated
            deltaMs = 10_000L,
            isBackBufferEnabled = false
        )
        // 6s accumulated (< 10s, > 1s) -> goes to live edge (buffered - 3s = 56_000L)
        assertEquals(56_000L, target)

        val target1500 = LivePlaybackUiPolicy.calculateLiveSeekTarget(
            currentPosition = 50_000L,
            bufferedPosition = 54_500L, // 4.5s buffer = 1.5s accumulated
            deltaMs = 10_000L,
            isBackBufferEnabled = false
        )
        // 1.5s accumulated (> 1s) -> goes to live edge (buffered - 3s = 51_500L)
        assertEquals(51_500L, target1500)
    }

    @Test
    fun `live forward seek escapes buffer when buffer is 1s or less`() {
        val target = LivePlaybackUiPolicy.calculateLiveSeekTarget(
            currentPosition = 50_000L,
            bufferedPosition = 53_800L, // 3.8s buffer = 0.8s accumulated
            deltaMs = 10_000L,
            isBackBufferEnabled = false
        )
        // 0.8s accumulated (<= 1s) -> escapes buffer (target = bufferedPosition)
        assertEquals(53_800L, target)

        val targetExact1s = LivePlaybackUiPolicy.calculateLiveSeekTarget(
            currentPosition = 50_000L,
            bufferedPosition = 54_000L, // 4.0s buffer = exactly 1.0s accumulated
            deltaMs = 10_000L,
            isBackBufferEnabled = false
        )
        // 1.0s accumulated (<= 1s) -> escapes buffer
        assertEquals(54_000L, targetExact1s)
    }

    @Test
    fun `live forward seek advances normally within buffer when buffer is 10s or more`() {
        val target = LivePlaybackUiPolicy.calculateLiveSeekTarget(
            currentPosition = 50_000L,
            bufferedPosition = 78_000L, // 28s buffer = 25s accumulated
            deltaMs = 10_000L,
            isBackBufferEnabled = false
        )
        // 25s accumulated (>= 10s) -> target = 50_000 + 10_000 = 60_000
        assertEquals(60_000L, target)
    }

    @Test
    fun `live seekTo target checks backbuffer for backward seek`() {
        val targetBackwardDisabled = LivePlaybackUiPolicy.calculateLiveSeekToTarget(
            currentPosition = 50_000L,
            bufferedPosition = 60_000L,
            targetPosition = 40_000L,
            isBackBufferEnabled = false
        )
        val targetBackwardEnabled = LivePlaybackUiPolicy.calculateLiveSeekToTarget(
            currentPosition = 50_000L,
            bufferedPosition = 60_000L,
            targetPosition = 40_000L,
            isBackBufferEnabled = true,
            backBufferDurationMs = 15_000L
        )
        assertNull(targetBackwardDisabled)
        assertEquals(40_000L, targetBackwardEnabled)
    }

    @Test
    fun `incremental seek does not overshoot buffer when remaining buffer is between 1s and 10s`() {
        val bufferedPos = 21_000L // 21 seconds buffer = 18 seconds accumulated above 3s live edge

        // Step 1: User is at 0s, presses +10s
        val step1 = LivePlaybackUiPolicy.calculateLiveSeekTarget(
            currentPosition = 0L,
            bufferedPosition = bufferedPos,
            deltaMs = 10_000L,
            isBackBufferEnabled = false
        )
        // 18s accumulated >= 10s -> advances normally to 10s
        assertEquals(10_000L, step1)

        // Step 2: User is now at 10s, presses +10s again (remaining accumulated = 8s)
        val step2 = LivePlaybackUiPolicy.calculateLiveSeekTarget(
            currentPosition = step1!!,
            bufferedPosition = bufferedPos,
            deltaMs = 10_000L,
            isBackBufferEnabled = false
        )
        // 8s is < 10s but > 1s -> caps at liveEdgePos (18s), preventing freezing by exceeding buffer
        assertEquals(18_000L, step2)

        // Step 3: User is now at 18s (live edge, accumulated buffer = 0s <= 1s), presses +10s again
        val step3 = LivePlaybackUiPolicy.calculateLiveSeekTarget(
            currentPosition = step2!!,
            bufferedPosition = bufferedPos,
            deltaMs = 10_000L,
            isBackBufferEnabled = false
        )
        // Accumulated buffer is 0s (<= 1s) -> escapes to live (target = bufferedPos >= liveEdgePos, triggers seekToDefaultPosition)
        assertEquals(bufferedPos, step3)
    }

    @Test
    fun `buffer filter stays locked to live during normal playback despite chunk oscillations`() {
        val filter = LivePlaybackBufferFilter(
            liveEdgeThresholdMs = 3_000L,
            deadbandMs = 5_000L,
            liveEdgeBufferBaselineMs = 3_000L
        )

        // Initial state: locked to live
        assertTrue(filter.lockedToLive)

        // Chunk 1 finishes downloading (forward buffer jumps to 5.5s, raw accumulated = 2.5s)
        val (delay1, prog1) = filter.update(
            currentPosition = 10_000L,
            bufferedPosition = 15_500L,
            isPlaying = true,
            maxBufferMs = 45_000L
        )
        assertEquals(0L, delay1)
        assertEquals(1f, prog1)
        assertTrue(filter.lockedToLive)

        // Playback plays 2s (forward buffer drops to 3.5s, raw accumulated = 0.5s)
        val (delay2, prog2) = filter.update(
            currentPosition = 12_000L,
            bufferedPosition = 15_500L,
            isPlaying = true,
            maxBufferMs = 45_000L
        )
        assertEquals(0L, delay2)
        assertEquals(1f, prog2)
        assertTrue(filter.lockedToLive)

        // Chunk 2 finishes downloading (forward buffer jumps to 6.5s, raw accumulated = 3.5s)
        // Since isPlaying=true and lockedToLive=true, chunk spikes do not unlock live!
        val (delay3, prog3) = filter.update(
            currentPosition = 12_000L,
            bufferedPosition = 18_500L,
            isPlaying = true,
            maxBufferMs = 45_000L
        )
        assertEquals(0L, delay3)
        assertEquals(1f, prog3)
        assertTrue(filter.lockedToLive)
    }

    @Test
    fun `buffer filter unlocks when paused delay reaches 1 second`() {
        val filter = LivePlaybackBufferFilter(
            liveEdgeThresholdMs = 3_000L,
            deadbandMs = 5_000L,
            liveEdgeBufferBaselineMs = 3_000L
        )

        // User pauses at t=10_000L
        filter.update(
            currentPosition = 10_000L,
            bufferedPosition = 14_500L,
            isPlaying = false,
            maxBufferMs = 45_000L,
            nowElapsedMs = 10_000L
        )
        assertTrue(filter.lockedToLive)

        // 0.5s passed (now = 10_500L)
        val (delay0, prog0) = filter.update(
            currentPosition = 10_000L,
            bufferedPosition = 20_000L,
            isPlaying = false,
            maxBufferMs = 45_000L,
            nowElapsedMs = 10_500L
        )
        assertEquals(0L, delay0)
        assertEquals(1f, prog0)
        assertTrue(filter.lockedToLive)

        // 1.5s passed (now = 11_500L) -> unlocks directly at >= 1s!
        val (delay1, prog1) = filter.update(
            currentPosition = 10_000L,
            bufferedPosition = 20_000L,
            isPlaying = false,
            maxBufferMs = 45_000L,
            nowElapsedMs = 11_500L
        )
        assertFalse(filter.lockedToLive)
        assertEquals(1_500L, delay1)

        // 8s passed (now = 18_000L)
        val (delay2, prog2) = filter.update(
            currentPosition = 10_000L,
            bufferedPosition = 25_000L,
            isPlaying = false,
            maxBufferMs = 45_000L,
            nowElapsedMs = 18_000L
        )
        assertFalse(filter.lockedToLive)
        assertEquals(8_000L, delay2)
        assertEquals(1f - (8f / 45f), prog2, 0.001f)
    }

    @Test
    fun `buffer filter accumulates delay smoothly second by second while paused without chunk jumps`() {
        val filter = LivePlaybackBufferFilter(
            liveEdgeThresholdMs = 3_000L,
            deadbandMs = 5_000L,
            liveEdgeBufferBaselineMs = 3_000L
        )

        var time = 100_000L
        // User pauses
        filter.update(
            currentPosition = 0L,
            bufferedPosition = 3_000L,
            isPlaying = false,
            maxBufferMs = 60_000L,
            nowElapsedMs = time
        )

        // Simulate 10 seconds of pause, advancing 1s at a time
        // Starts directly from 1s (1s, 2s, 3s, 4s...)
        for (second in 1..10) {
            time += 1_000L
            val (delay, _) = filter.update(
                currentPosition = 0L,
                bufferedPosition = 20_000L, // network buffered plenty ahead
                isPlaying = false,
                maxBufferMs = 60_000L,
                nowElapsedMs = time
            )
            assertEquals(second * 1_000L, delay) // 1s, 2s, 3s, 4s, 5s, 6s, 7s, 8s, 9s, 10s smoothly!
        }
    }

    @Test
    fun `resuming playback maintains stable delay and tolerates pre-fetched chunk buffer`() {
        val filter = LivePlaybackBufferFilter(
            liveEdgeThresholdMs = 3_000L,
            deadbandMs = 5_000L,
            liveEdgeBufferBaselineMs = 3_000L
        )

        // Paused at 17s delay
        filter.applySeekDelta(deltaMs = -17_000L, maxBufferMs = 45_000L)
        assertFalse(filter.lockedToLive)

        // Now user unpauses and plays. Initial delay = 17s.
        val (initDelay, initProg) = filter.update(
            currentPosition = 10_000L,
            bufferedPosition = 30_000L,
            isPlaying = true,
            maxBufferMs = 45_000L
        )
        assertEquals(17_000L, initDelay)

        // ExoPlayer pre-fetches chunks ahead to 24s in the background
        val (resumedDelay, resumedProg) = filter.update(
            currentPosition = 10_500L,
            bufferedPosition = 37_500L, // 24s accumulated
            isPlaying = true,
            maxBufferMs = 45_000L
        )
        // Delay does NOT jump to 24s! It stays firmly at 17s!
        assertEquals(17_000L, resumedDelay)
        assertEquals(initProg, resumedProg)
    }

    @Test
    fun `buffer filter dynamically scales progress bar when buffer exceeds 45 seconds`() {
        val filter = LivePlaybackBufferFilter(
            liveEdgeThresholdMs = 3_000L,
            deadbandMs = 5_000L,
            liveEdgeBufferBaselineMs = 3_000L
        )

        // Stream has buffered 90 seconds
        val (delay, prog) = filter.applySeekDelta(deltaMs = -90_000L, maxBufferMs = 45_000L)
        assertEquals(90_000L, delay)
        // With dynamic effectiveMaxBufferMs = 90s, progress is 0f (start of buffer), not broken/negative
        assertEquals(0f, prog, 0.001f)

        // Delayed by 45s out of 90s buffer
        filter.reset()
        val (delay45, prog45) = filter.applySeekDelta(deltaMs = -45_000L, maxBufferMs = 90_000L)
        assertEquals(45_000L, delay45)
        assertEquals(0.5f, prog45, 0.001f) // Exactly 50% of the bar, perfectly visible!
    }

    @Test
    fun `buffer filter snaps back to live when catching up or seeking to live edge`() {
        val filter = LivePlaybackBufferFilter(
            liveEdgeThresholdMs = 3_000L,
            deadbandMs = 5_000L,
            liveEdgeBufferBaselineMs = 3_000L
        )

        // Start delayed (seek back to -12s)
        filter.applySeekDelta(deltaMs = -12_000L, maxBufferMs = 45_000L)
        assertFalse(filter.lockedToLive)

        // Seeking to live edge
        val (seekDelay, seekProg) = filter.applySeekDelta(deltaMs = 12_000L, maxBufferMs = 45_000L)
        assertTrue(filter.lockedToLive)
        assertEquals(0L, seekDelay)
        assertEquals(1f, seekProg)

        // Or explicit snapToLive
        filter.unlockFromLive()
        assertFalse(filter.lockedToLive)
        filter.snapToLive()
        assertTrue(filter.lockedToLive)
    }

    @Test
    fun `live backward seek is disabled when available back slice is less than 10 seconds`() {
        // Back buffer is enabled but only 8s configured
        val targetSmallConfig = LivePlaybackUiPolicy.calculateLiveSeekTarget(
            currentPosition = 50_000L,
            bufferedPosition = 50_000L,
            deltaMs = -10_000L,
            isBackBufferEnabled = true,
            backBufferDurationMs = 8_000L
        )
        assertNull(targetSmallConfig)

        // Back buffer is 15s, but stream has only played for 5s (currentPosition = 5_000L)
        val targetSmallStream = LivePlaybackUiPolicy.calculateLiveSeekTarget(
            currentPosition = 5_000L,
            bufferedPosition = 5_000L,
            deltaMs = -10_000L,
            isBackBufferEnabled = true,
            backBufferDurationMs = 15_000L
        )
        assertNull(targetSmallStream)

        // Back buffer is 15s and currentPosition is 50s -> 15s slice >= 10s -> seek allowed!
        val targetAllowed = LivePlaybackUiPolicy.calculateLiveSeekTarget(
            currentPosition = 50_000L,
            bufferedPosition = 50_000L,
            deltaMs = -10_000L,
            isBackBufferEnabled = true,
            backBufferDurationMs = 15_000L
        )
        assertEquals(40_000L, targetAllowed)
    }

    @Test
    fun `first forward seek after pause follows the overlay delay and consumes hidden buffer`() {
        val current = 10_000L
        val displayedDelayMs = 30_000L
        val realDelayMs = 45_000L
        val buffered = current + realDelayMs + LivePlaybackUiPolicy.LIVE_EDGE_BUFFER_MS

        val filter = LivePlaybackBufferFilter()
        filter.applySeekDelta(deltaMs = -displayedDelayMs, maxBufferMs = 45_000L)
        assertEquals(displayedDelayMs, filter.displayedDelayMs)

        val target = LivePlaybackUiPolicy.calculateLiveSeekTarget(
            currentPosition = current,
            bufferedPosition = buffered,
            deltaMs = 10_000L,
            isBackBufferEnabled = true,
            backBufferDurationMs = 15_000L,
            displayedDelayMs = filter.displayedDelayMs
        )
        // Overlay 30s -> 20s; real 45s delay must jump +25s to meet at 20s.
        assertEquals(current + 25_000L, target)

        val (newDelay, newProg) = filter.applySeekDelta(deltaMs = 10_000L, maxBufferMs = 45_000L)
        assertEquals(20_000L, newDelay)
        assertEquals(1f - (20f / 45f), newProg, 0.001f)
    }

    @Test
    fun `full buffer forward seek stays inside the window instead of snapping to live`() {
        val current = 10_000L
        val displayedDelayMs = 45_000L
        val rawDelayMs = 45_000L
        // Exo often reports buffered ≈ current once the window is full.
        val staleBuffered = current + 3_000L

        val liveEdge = LivePlaybackUiPolicy.liveEdgePositionMs(
            currentPosition = current,
            bufferedPosition = staleBuffered,
            displayedDelayMs = displayedDelayMs,
            rawDelayMs = rawDelayMs
        )
        assertEquals(current + displayedDelayMs, liveEdge)

        val target = LivePlaybackUiPolicy.calculateLiveSeekTarget(
            currentPosition = current,
            bufferedPosition = staleBuffered,
            deltaMs = 10_000L,
            isBackBufferEnabled = true,
            backBufferDurationMs = 15_000L,
            displayedDelayMs = displayedDelayMs,
            rawDelayMs = rawDelayMs
        )
        assertEquals(current + 10_000L, target)
        assertTrue(target!! < liveEdge)
        assertFalse(LivePlaybackUiPolicy.shouldSeekToLiveEdge(displayedDelayMs, 10_000L))
    }

    @Test
    fun `full buffer with matching positions seeks by the overlay delta only`() {
        val current = 10_000L
        val displayedDelayMs = 45_000L
        val buffered = current + displayedDelayMs + LivePlaybackUiPolicy.LIVE_EDGE_BUFFER_MS

        val target = LivePlaybackUiPolicy.calculateLiveSeekTarget(
            currentPosition = current,
            bufferedPosition = buffered,
            deltaMs = 10_000L,
            isBackBufferEnabled = true,
            backBufferDurationMs = 15_000L,
            displayedDelayMs = displayedDelayMs
        )
        assertEquals(current + 10_000L, target)
    }

    @Test
    fun `paused progress window ignores raw chunk spikes so bar and clock stay aligned`() {
        val filter = LivePlaybackBufferFilter(
            liveEdgeThresholdMs = 3_000L,
            deadbandMs = 5_000L,
            liveEdgeBufferBaselineMs = 3_000L
        )

        filter.update(
            currentPosition = 10_000L,
            bufferedPosition = 13_000L,
            isPlaying = false,
            maxBufferMs = 45_000L,
            nowElapsedMs = 0L
        )

        val (delay5, prog5) = filter.update(
            currentPosition = 10_000L,
            bufferedPosition = 33_000L, // raw accumulated 20s
            isPlaying = false,
            maxBufferMs = 45_000L,
            nowElapsedMs = 5_000L
        )
        assertEquals(5_000L, delay5)
        assertEquals(1f - (5f / 45f), prog5, 0.001f)

        // Chunk dump jumps the raw buffer to 50s; displayed delay only slews +1s
        val (delay6, prog6) = filter.update(
            currentPosition = 10_000L,
            bufferedPosition = 63_000L, // raw accumulated 50s
            isPlaying = false,
            maxBufferMs = 45_000L,
            nowElapsedMs = 6_000L
        )
        assertEquals(6_000L, delay6)
        assertEquals(1f - (6f / 45f), prog6, 0.001f)
        assertFalse(filter.lockedToLive)
    }

    @Test
    fun `pause delay keeps ticking when raw buffer stalls then catches up`() {
        val filter = LivePlaybackBufferFilter(
            liveEdgeThresholdMs = 3_000L,
            deadbandMs = 5_000L,
            liveEdgeBufferBaselineMs = 3_000L
        )

        filter.update(
            currentPosition = 10_000L,
            bufferedPosition = 34_000L, // 21s accumulated
            isPlaying = false,
            maxBufferMs = 45_000L,
            nowElapsedMs = 0L
        )

        val (delay21, _) = filter.update(
            currentPosition = 10_000L,
            bufferedPosition = 34_000L,
            isPlaying = false,
            maxBufferMs = 45_000L,
            nowElapsedMs = 21_000L
        )
        assertEquals(21_000L, delay21)

        // Raw report stalls at 21s while live keeps moving.
        val (delay25, _) = filter.update(
            currentPosition = 10_000L,
            bufferedPosition = 34_000L,
            isPlaying = false,
            maxBufferMs = 45_000L,
            nowElapsedMs = 25_000L
        )
        assertEquals(25_000L, delay25)

        // Raw later jumps to 28s; overlay does not snap, it is already at 25s.
        val (delay26, prog26) = filter.update(
            currentPosition = 10_000L,
            bufferedPosition = 41_000L, // 28s accumulated
            isPlaying = false,
            maxBufferMs = 45_000L,
            nowElapsedMs = 26_000L
        )
        assertEquals(26_000L, delay26)
        assertEquals(1f - (26f / 45f), prog26, 0.001f)
    }

    @Test
    fun `seek preview delay is stable when live edge buffer grows during scrub`() {
        val filter = LivePlaybackBufferFilter(
            liveEdgeThresholdMs = 3_000L,
            deadbandMs = 5_000L,
            liveEdgeBufferBaselineMs = 3_000L
        )

        val (delay1, prog1) = filter.applySeekDelta(deltaMs = -17_000L, maxBufferMs = 45_000L)
        assertEquals(17_000L, delay1)
        assertEquals(1f - (17f / 45f), prog1, 0.001f)

        val (delay2, prog2) = filter.update(
            currentPosition = 10_000L,
            bufferedPosition = 42_000L, // live edge jumped +12s
            isPlaying = false,
            maxBufferMs = 45_000L,
            isSeeking = true
        )
        assertEquals(17_000L, delay2)
        assertEquals(prog1, prog2)
    }

    @Test
    fun `playing at live edge stays locked while buffer grows as during rebuffer`() {
        val filter = LivePlaybackBufferFilter(
            liveEdgeThresholdMs = 3_000L,
            deadbandMs = 5_000L,
            liveEdgeBufferBaselineMs = 3_000L
        )

        repeat(8) { tick ->
            val (delay, prog) = filter.update(
                currentPosition = 10_000L,
                bufferedPosition = 14_000L + tick * 2_000L,
                isPlaying = true,
                maxBufferMs = 45_000L,
                nowElapsedMs = tick * 500L
            )
            assertEquals(0L, delay)
            assertEquals(1f, prog)
            assertTrue(filter.lockedToLive)
        }
    }
}
