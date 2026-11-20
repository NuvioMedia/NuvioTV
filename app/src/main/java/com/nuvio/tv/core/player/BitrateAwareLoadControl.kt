package com.nuvio.tv.core.player

import android.os.Handler
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.exoplayer.upstream.DefaultAllocator

/**
 * DefaultLoadControl with a byte target tied to the device memory budget, and a back
 * buffer / budget that can be adjusted at runtime once we know whether the stream is
 * really DV7.
 *
 * On a DV-capable display AUTO arms conversion for every file, but most never actually
 * convert. So we build with the full budget and the user's back buffer, then tighten
 * only for confirmed DV7 via the override setters below.
 */
@UnstableApi
class BitrateAwareLoadControl(
    minBufferMs: Int,
    maxBufferMs: Int,
    bufferForPlaybackMs: Int,
    bufferForPlaybackAfterRebufferMs: Int,
    prioritizeTimeOverSizeThresholds: Boolean,
    backBufferDurationMs: Int,
    retainBackBufferFromKeyframe: Boolean,
    /** Memory ceiling in bytes. */
    private val budgetBytes: Long,
    allocator: DefaultAllocator = DefaultAllocator(/* trimOnReset= */ true, C.DEFAULT_BUFFER_SEGMENT_SIZE, 64)
) : DefaultLoadControl(
    allocator,
    minBufferMs,
    maxBufferMs,
    bufferForPlaybackMs,
    bufferForPlaybackAfterRebufferMs,
    /* targetBufferBytes= */ C.LENGTH_UNSET,
    prioritizeTimeOverSizeThresholds,
    backBufferDurationMs,
    retainBackBufferFromKeyframe
) {

    // Effective back buffer (µs) when >= 0, else the constructed value.
    @Volatile
    private var backBufferOverrideUs: Long = -1L

    /**
     * Set the back buffer at runtime; negative restores the constructed value.
     *
     * Media3's `ExoPlayerImplInternal` caches `backBufferDurationUs` in a field during its
     * constructor and does not re-poll [getBackBufferDurationUs] mid-playback. When [player]
     * is provided, we also update `ExoPlayerImplInternal.backBufferDurationUs` so the new
     * back-buffer duration takes effect immediately for the active session.
     */
    fun setBackBufferDurationOverrideMs(ms: Int, player: ExoPlayer? = null) {
        backBufferOverrideUs = if (ms < 0) -1L else ms.toLong() * 1000L
        if (player != null) {
            val effectiveUs = getBackBufferDurationUs(PlayerId.UNSET)
            applyBackBufferToInternalPlayer(player, effectiveUs)
        }
    }

    // Effective byte budget when >= 0, else the constructed budget.
    @Volatile
    private var budgetBytesOverride: Long = -1L

    /**
     * Set the byte budget at runtime; negative restores the constructed budget.
     *
     * Also updates any already-created `PlayerLoadingState.targetBufferBytes` entries in
     * [DefaultLoadControl] and refreshes the allocator target size so overrides applied at
     * first frame (after `onTracksSelected`) take effect immediately.
     */
    fun setBudgetBytesOverride(bytes: Long, player: ExoPlayer? = null) {
        budgetBytesOverride = if (bytes < 0L) -1L else bytes
        val looper = runCatching { player?.playbackLooper }.getOrNull()
        if (looper != null && looper.thread.isAlive && Thread.currentThread() !== looper.thread) {
            Handler(looper).post { applyBudgetToActiveLoadingStates() }
        } else {
            applyBudgetToActiveLoadingStates()
        }
    }

    override fun getBackBufferDurationUs(playerId: PlayerId): Long {
        val override = backBufferOverrideUs
        return if (override >= 0L) override else super.getBackBufferDurationUs(playerId)
    }

    override fun calculateTargetBufferBytes(
        trackSelectionArray: Array<out ExoTrackSelection?>
    ): Int {
        // Target = the memory budget. Time (Max Buffer Duration) is the real limit; this is
        // just the memory cap. Sizing from advertised bitrate starved variable-bitrate peaks,
        // so let high-bitrate fill up to the budget and low-bitrate stop at the time limit.
        return currentTargetBufferBytes()
    }

    private fun currentTargetBufferBytes(): Int {
        val effectiveBudget = if (budgetBytesOverride >= 0L) budgetBytesOverride else budgetBytes
        return effectiveBudget.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private fun applyBudgetToActiveLoadingStates() {
        runCatching {
            val targetBytes = currentTargetBufferBytes()
            val loadingStatesField = DefaultLoadControl::class.java.getDeclaredField("loadingStates").apply {
                isAccessible = true
            }
            val statesMap = loadingStatesField.get(this) as? Map<*, *> ?: return
            for (state in statesMap.values) {
                if (state == null) continue
                val targetField = state.javaClass.getDeclaredField("targetBufferBytes").apply {
                    isAccessible = true
                }
                targetField.setInt(state, targetBytes)
            }
            val updateAllocatorMethod = DefaultLoadControl::class.java.getDeclaredMethod("updateAllocator").apply {
                isAccessible = true
            }
            updateAllocatorMethod.invoke(this)
        }
    }

    private fun applyBackBufferToInternalPlayer(player: ExoPlayer, effectiveBackBufferUs: Long) {
        runCatching {
            val internalPlayerField = findFieldInHierarchy(player.javaClass, "internalPlayer") ?: return
            internalPlayerField.isAccessible = true
            val internalPlayer = internalPlayerField.get(player) ?: return
            val backBufferField = findFieldInHierarchy(internalPlayer.javaClass, "backBufferDurationUs") ?: return
            backBufferField.isAccessible = true
            val looper = runCatching { player.playbackLooper }.getOrNull()
            if (looper != null && looper.thread.isAlive && Thread.currentThread() !== looper.thread) {
                Handler(looper).post {
                    runCatching { backBufferField.setLong(internalPlayer, effectiveBackBufferUs) }
                }
            } else {
                backBufferField.setLong(internalPlayer, effectiveBackBufferUs)
            }
        }
    }

    private fun findFieldInHierarchy(clazz: Class<*>, name: String): java.lang.reflect.Field? {
        var current: Class<*>? = clazz
        while (current != null) {
            try {
                return current.getDeclaredField(name)
            } catch (_: NoSuchFieldException) {
                current = current.superclass
            }
        }
        return null
    }
}
