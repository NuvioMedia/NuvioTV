package com.nuvio.tv.data.simkl

import com.nuvio.tv.core.tracking.TrackingMediaReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** A finished playback the user can still choose to record as a rewatch. */
data class RewatchPrompt(
    val media: TrackingMediaReference,
    val watchedAtEpochMs: Long
)

/** What an answer to the prompt actually did, shown briefly so the tap has visible feedback. */
enum class RewatchNoticeKind {
    /** The rewatch reached Simkl. */
    RECORDED,

    /** The user answered No, or the question timed out. */
    NOT_RECORDED,

    /** The write to Simkl failed. */
    FAILED
}

data class RewatchNotice(val kind: RewatchNoticeKind)

/**
 * Holds the rewatch question Nuvio shows after a playback that Simkl accepted as a repeat viewing.
 *
 * Nothing is written until the user confirms, which is why the prompt is the only place that turns
 * a playback into a rewatch session in manual mode. The prompt is cleared on every answer and never
 * survives the session. The answer also leaves a [notice] behind, so the user sees what happened
 * instead of having to trust that a tap did something.
 *
 * The answer does not decide anything about Continue Watching: a series joins the row once the
 * account shows two episodes of the run rewatched, which is a rule that reads the same on every
 * device (see `deriveSimklRewatchRuns`).
 *
 * TV equivalent of mobile `RewatchPromptRepository.kt`. Mobile deliberately keeps it outside DI and
 * outside the lifecycle (an `object` with its own scope) so the write survives the popup closing; TV
 * makes it a `@Singleton` that carries that scope in itself, so the same holds for the overlay in
 * `PlayerScreen`, which is unmounted after an answer. The answer is written through
 * [SimklRewatchWriter] and not `SimklMutationService` directly, so it can be tested without HTTP.
 */
@Singleton
class SimklRewatchPromptRepository @Inject constructor(
    private val writer: SimklRewatchWriter
) {
    /**
     * Where the write of a confirmed rewatch runs. It cannot be the scope of the overlay that asked
     * the question: clearing the prompt takes that overlay out of the composition, which cancels its
     * scope, and the write must not be cancelled halfway.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _prompt = MutableStateFlow<RewatchPrompt?>(null)
    val prompt: StateFlow<RewatchPrompt?> = _prompt.asStateFlow()

    private val _notice = MutableStateFlow<RewatchNotice?>(null)
    val notice: StateFlow<RewatchNotice?> = _notice.asStateFlow()

    fun request(prompt: RewatchPrompt) {
        _prompt.value = prompt
    }

    /** Called when the prompt is dismissed, including by its own timeout. */
    fun dismiss() {
        _prompt.value = null
    }

    /** The user answered No: nothing is written, and the app says so. */
    fun decline() {
        _prompt.value = null
        _notice.value = RewatchNotice(RewatchNoticeKind.NOT_RECORDED)
    }

    /**
     * Records the pending rewatch and closes the prompt.
     *
     * The answer is shown when the write is done, not when the button is tapped, and it survives the
     * overlay closing: that is the difference between a tap that says what happened and one that does
     * not.
     */
    fun confirm() {
        val active = _prompt.value ?: return
        _prompt.value = null
        scope.launch {
            val recorded = writer.recordConfirmedRewatch(
                media = active.media,
                watchedAtEpochMs = active.watchedAtEpochMs
            )
            _notice.value = RewatchNotice(
                if (recorded) RewatchNoticeKind.RECORDED else RewatchNoticeKind.FAILED
            )
        }
    }

    /** Hides the feedback of the last answer. */
    fun dismissNotice() {
        _notice.value = null
    }
}
