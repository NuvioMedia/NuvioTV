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
    val watchedAtEpochMs: Long? = null
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
    val progressPercent: Double
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
    val providerSubtype: String? = null
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

    suspend fun scrobble(action: TrackingScrobbleAction, event: TrackingScrobbleEvent)
}
