package com.nuvio.tv.core.tracking

enum class TrackingScrobbleAction(val wireValue: String) {
    START("start"),
    PAUSE("pause"),
    STOP("stop")
}

enum class TrackingSeekScrobblePolicy {
    NONE,
    STOP_AND_RESTART
}

/**
 * Where a playback counts as already past the point the trackers report a completion at.
 *
 * One number for two sides that have to agree on it: the player, which reports a finished playback
 * from here up, and each provider, which decides for itself what a start sent above it means. Trakt
 * treats such a start as the beginning of a viewing, and the stop that follows would record the
 * watch twice, so its scrobbler skips the start; a tracker whose "watching now" state is its own
 * wants to hear about the playback anyway.
 */
internal const val TRACKING_COMPLETION_BAR_PERCENT = 80.0

enum class TrackingListStatus(val wireValue: String) {
    WATCHING("watching"),
    PLAN_TO_WATCH("plantowatch"),
    ON_HOLD("hold"),
    COMPLETED("completed"),
    DROPPED("dropped");

    companion object {
        fun fromWireValue(value: String?): TrackingListStatus? =
            entries.firstOrNull { status -> status.wireValue.equals(value, ignoreCase = true) }
    }
}

data class TrackingHistoryItem(
    val media: TrackingMediaReference,
    val watchedAtEpochMs: Long? = null,
    /**
     * The viewing session this write belongs to, for a provider that keeps rewatches as sessions
     * beside the watch history. Simkl asks for it on every write after the one that opened the
     * session, so a run is continued rather than forked. Providers without the concept leave it unset
     * and it is then never sent.
     */
    val rewatchId: Long? = null,

    /**
     * Whether this write records a repeat viewing rather than a first watch.
     *
     * Simkl records a plain add of something the account has already watched as nothing at all, so a
     * mark that is a repeat has to say so for the account to book it: the write then joins the run of
     * the item, or opens one. Providers without the concept leave it unset and nothing is sent.
     */
    val isRewatch: Boolean = false
) {
    /**
     * Whether this mark describes a whole series rather than something that was watched.
     *
     * A mark without episode coordinates turns into a show-level entry, and providers such as Simkl
     * and MDBList answer it by marking every episode of the show watched, including episodes the user
     * never opened, which is how a single ill-timed mark wiped a full series. Only films are allowed
     * through without coordinates. An anime mark is dropped too: without more metadata the app cannot
     * tell an anime film from an anime series.
     */
    val isWholeSeriesMark: Boolean
        get() = media.episode == null && media.kind != TrackingMediaKind.MOVIE
}

data class TrackingScrobbleEvent(
    val media: TrackingMediaReference,
    val progressPercent: Double,
    /**
     * Where the content ends for the tracker, in percent of its duration, when IntroDB knows the
     * credits: the marker read a fixed few seconds early, the way the player reads it.
     *
     * A playback that reached the credits is over, even when the user set the completion point higher
     * than that. Null means no marker is known for this item, and the percentage decides on its own.
     */
    val contentEndPercent: Double? = null,
    /**
     * A scrobble of a playback no session owns, for the trackers that keep a watching state of
     * their own.
     *
     * A playback that sits above the completion bar never arms the pause and stop policy, so the
     * events it produces belong to no session. Simkl wants them anyway; for Trakt a pause is a stop,
     * and a stop above the bar records a watch, so such an event would write a viewing that never
     * happened. The player marks them and the coordinator leaves out the scrobblers that do not
     * accept them (see [TrackingScrobbler.acceptsSessionlessScrobbles]).
     */
    val sessionless: Boolean = false,

    /**
     * Whether the playback behind this event was reported to the trackers from its start, below the
     * completion bar.
     *
     * A playback that began above the bar has no session on the account: its stop is the finish of a
     * viewing the account already has, not a viewing that can be repeated, so a rewatch must not be
     * opened for it.
     */
    val hasPlaybackSession: Boolean = false,

    /**
     * Where the playback began, in percent of its duration, when the player reported its start.
     *
     * A playback that resumes above [TRACKING_COMPLETION_BAR_PERCENT] is sessionless for the
     * trackers that record a watch on a stop, but the point that ends the viewing can be higher
     * than that bar. Simkl reads this to tell a resumed viewing from the tail of a finished one.
     * Null when the caller does not report it (an external player).
     */
    val startProgressPercent: Double? = null,

    /**
     * Identifies one run of the player for the item.
     *
     * A tracker that records a watch on a stop uses it to collapse a completion it already recorded
     * when the same playback ends again after a pause, without suppressing a later, separate viewing.
     * Zero means the caller has no playback identity (an external player), and nothing is collapsed.
     */
    val playbackId: Long = 0L
)

data class TrackingMutationResult(
    val attemptedCount: Int,
    val notFoundCount: Int = 0,
    val resolutions: List<TrackingMutationResolution> = emptyList()
) {
    val resolvedListStatuses: List<TrackingListStatus>
        get() = resolutions.mapNotNull(TrackingMutationResolution::listStatus)

    val isComplete: Boolean
        get() = notFoundCount == 0
}

data class TrackingMutationResolution(
    val listStatus: TrackingListStatus? = null,
    val mediaKind: TrackingMediaKind? = null,
    val providerSubtype: String? = null,
    /**
     * How a provider that keeps rewatches as sessions booked the view: the answer it gave back for the
     * item, and null for a provider without the concept and for a plain first watch.
     */
    val rewatchStatus: String? = null
)

interface TrackingListWriter {
    val providerId: TrackingProviderId

    suspend fun moveToList(
        profileId: Int,
        items: Collection<TrackingMediaReference>,
        destination: TrackingListStatus
    ): TrackingMutationResult

    suspend fun removeFromList(
        profileId: Int,
        items: Collection<TrackingMediaReference>
    ): TrackingMutationResult
}

interface TrackingHistoryWriter {
    val providerId: TrackingProviderId

    suspend fun addToHistory(
        profileId: Int,
        items: Collection<TrackingHistoryItem>
    ): TrackingMutationResult

    suspend fun removeFromHistory(
        profileId: Int,
        items: Collection<TrackingMediaReference>
    ): TrackingMutationResult
}

interface TrackingScrobbler {
    val providerId: TrackingProviderId
    val seekScrobblePolicy: TrackingSeekScrobblePolicy
        get() = TrackingSeekScrobblePolicy.NONE

    /**
     * Whether a scrobble of a playback no session owns is welcome here.
     *
     * Off by default. For Trakt a pause is a stop and a stop above the completion bar records a
     * watch, so a sessionless pause or stop would write a viewing the user never finished. A tracker
     * that keeps its own "watching now" state, such as Simkl, turns it on.
     */
    val acceptsSessionlessScrobbles: Boolean
        get() = false

    suspend fun scrobble(action: TrackingScrobbleAction, event: TrackingScrobbleEvent)
}
