package com.nuvio.tv.data.simkl

import com.nuvio.tv.core.profile.ProfileManager
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
class SimklRewatchConsentRepository @Inject constructor(
    private val profileManager: ProfileManager,
) {
    /**
     * The answers are written from the UI and read from the scrobbler on IO, so every access goes
     * through this lock and the series keys are kept as sets: a decision about one series must not
     * forget the previous one.
     */
    private val lock = Any()

    /**
     * The series the user said yes to, until the account holds a session for it.
     *
     * The yes opens the run the user was asked about, not every run of the series: once a session
     * is seen the answer is released, and a later run asks again.
     */
    private val grantedSeriesKeys = mutableSetOf<String>()

    /** The series the user said no to, for the rest of the app run. */
    private val declinedSeriesKeys = mutableSetOf<String>()

    /** The series the user dismissed the question for, until the playback that raised it ends. */
    private val dismissedSeriesKeys = mutableSetOf<String>()

    private val _question = MutableStateFlow<RewatchQuestion?>(null)
    val question: StateFlow<RewatchQuestion?> = _question.asStateFlow()

    /** The profile the answers belong to; another profile starts with no answers. */
    private var answersProfileId: Int? = null

    private val profileWatcher = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    /*
     * The overlay reads the question whether or not anything asks again, so a profile switch has to
     * drop the previous profile's question at the moment it happens. The answers field is declared
     * above this block so the first emission sets it instead of being overwritten by its initializer.
     */
    init {
        profileWatcher.launch { profileManager.activeProfileId.collect { ensureProfile() } }
    }

    /**
     * Drops every answer when the active profile changed since the last one. The answers are about
     * the account the user was asked against, and another profile's account must inherit neither a
     * yes nor a no.
     */
    private fun ensureProfile() {
        val profileId = profileManager.activeProfileId.value
        synchronized(lock) {
            val previous = answersProfileId
            answersProfileId = profileId
            if (previous != null && previous != profileId) clearLocked()
        }
    }

    /** Drops every answer. Used when the account is disconnected. */
    fun clear() {
        synchronized(lock) { clearLocked() }
    }

    private fun clearLocked() {
        grantedSeriesKeys.clear()
        declinedSeriesKeys.clear()
        dismissedSeriesKeys.clear()
        _question.value = null
    }

    /**
     * Raises the question for a playback that is about to start, unless the user has already said
     * something about this series: an answer of either kind stands for the whole app run, so the
     * same series is never asked about twice.
     */
    fun ask(media: TrackingMediaReference) {
        ensureProfile()
        val seriesKey = media.seriesKey()
        val answered = synchronized(lock) {
            seriesKey in grantedSeriesKeys || seriesKey in declinedSeriesKeys || seriesKey in dismissedSeriesKeys
        }
        if (answered) return
        if (_question.value?.media?.seriesKey() == seriesKey) return
        _question.value = RewatchQuestion(media)
    }

    /** The user said yes: the series may be written into a run until the account has one. */
    fun grant() {
        ensureProfile()
        val question = _question.value ?: return
        _question.value = null
        synchronized(lock) { grantedSeriesKeys += question.media.seriesKey() }
    }

    /** The user said no: the series stays out of the account for the rest of this app run. */
    fun decline() {
        ensureProfile()
        val question = _question.value ?: return
        _question.value = null
        synchronized(lock) { declinedSeriesKeys += question.media.seriesKey() }
    }

    /**
     * Drops a question that was never answered. The dismissal is remembered for the playback that
     * raised it, so a pause and its resume do not ask again; it is cleared when that playback stops.
     */
    fun dismiss() {
        ensureProfile()
        val question = _question.value ?: return
        _question.value = null
        synchronized(lock) { dismissedSeriesKeys += question.media.seriesKey() }
    }

    /** The playback that raised the dismissed question has ended, so the series may ask again. */
    fun clearDismissal(media: TrackingMediaReference) {
        ensureProfile()
        val seriesKey = media.seriesKey()
        synchronized(lock) { dismissedSeriesKeys -= seriesKey }
    }

    /** True when a yes of this app run covers the media's series and no session has replaced it. */
    fun grantedFor(media: TrackingMediaReference): Boolean {
        ensureProfile()
        val seriesKey = media.seriesKey()
        return synchronized(lock) { seriesKey in grantedSeriesKeys }
    }

    /**
     * The account runs this series now, so the answer has done its job and is released: the next
     * run of the series asks again.
     */
    fun releaseGrant(media: TrackingMediaReference) {
        ensureProfile()
        val seriesKey = media.seriesKey()
        synchronized(lock) { grantedSeriesKeys -= seriesKey }
    }

    /**
     * The series a reference belongs to, whatever episode it carries. An answer is about the series,
     * because the run it opens covers the episodes that follow the one that was asked about.
     */
    private fun TrackingMediaReference.seriesKey(): String = copy(episode = null).stableKey
}
