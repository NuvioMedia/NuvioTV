package com.nuvio.tv.data.simkl

import com.nuvio.tv.core.tracking.TrackingMediaReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** An episode that is about to play, held while the user decides whether it opens a run. */
data class RewatchQuestion(val media: TrackingMediaReference)

/**
 * Holds what the user said about recording rewatches, asked before the playbacks they are about.
 *
 * The answer is what [SimklRewatchMode.SEMI_AUTOMATIC] writes from at the end of a playback, so it
 * has to outlive both the question and the playback: a yes for a series covers its episodes until
 * the account runs a session for it, and a no keeps the series quiet for the rest of the app run.
 * Only that series-level answer is kept, nothing reaches the disk, and the next app run asks
 * against what the account holds at that point.
 *
 * The state exists because a question is not a write: nothing is sent until the playback ends and
 * Simkl accepts the scrobble, and a question left unanswered records nothing.
 *
 * TV equivalent of mobile `RewatchPromptRepository.kt`. Mobile keeps it outside DI and outside the
 * lifecycle (an `object` with its own scope) so a pending answer survives its popup; TV makes it a
 * `@Singleton`, which is what the overlay in the activity reads its question from.
 */
@Singleton
class SimklRewatchConsentRepository @Inject constructor() {
    /**
     * The series the user said yes to, until the account holds a session for it.
     *
     * The yes opens the run the user was asked about, not every run of the series: once a session
     * is seen the answer is released, and a later run asks again.
     */
    private var grantedSeriesKey: String? = null

    /** The series the user said no to, for the rest of the app run. */
    private var declinedSeriesKey: String? = null

    private val _question = MutableStateFlow<RewatchQuestion?>(null)
    val question: StateFlow<RewatchQuestion?> = _question.asStateFlow()

    /**
     * Raises the question for a playback that is about to start, unless the user has already said
     * something about this series: an answer of either kind stands for the whole app run, so the
     * same series is never asked about twice.
     */
    fun ask(media: TrackingMediaReference) {
        val seriesKey = media.seriesKey()
        if (grantedSeriesKey == seriesKey || declinedSeriesKey == seriesKey) return
        if (_question.value?.media?.seriesKey() == seriesKey) return
        _question.value = RewatchQuestion(media)
    }

    /** The user said yes: the series may be written into a run until the account has one. */
    fun grant() {
        val question = _question.value ?: return
        _question.value = null
        grantedSeriesKey = question.media.seriesKey()
    }

    /** The user said no: the series stays out of the account for the rest of this app run. */
    fun decline() {
        val question = _question.value ?: return
        _question.value = null
        declinedSeriesKey = question.media.seriesKey()
    }

    /** Drops a question that was never answered, so the next playback can ask again. */
    fun dismiss() {
        _question.value = null
    }

    /** True when a yes of this app run covers the media's series and no session has replaced it. */
    fun grantedFor(media: TrackingMediaReference): Boolean = grantedSeriesKey == media.seriesKey()

    /**
     * The account runs this series now, so the answer has done its job and is released: the next
     * run of the series asks again.
     */
    fun releaseGrant(media: TrackingMediaReference) {
        if (grantedSeriesKey == media.seriesKey()) grantedSeriesKey = null
    }

    /**
     * The series a reference belongs to, whatever episode it carries. An answer is about the series,
     * because the run it opens covers the episodes that follow the one that was asked about.
     */
    private fun TrackingMediaReference.seriesKey(): String = copy(episode = null).stableKey
}
