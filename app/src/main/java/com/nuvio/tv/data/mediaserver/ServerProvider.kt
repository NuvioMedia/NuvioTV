package com.nuvio.tv.data.mediaserver

import com.nuvio.tv.core.tracking.TrackingExternalIds

enum class ServerCapability {
    SEARCH,
    EXTERNAL_ID_LOOKUP,
    USER_STATE_READ,
    USER_STATE_WRITE,
    TRANSCODING
}

interface ServerProvider {
    val id: String
    val displayName: String
    val capabilities: Set<ServerCapability>
    val minimumVersion: String

    /** False when the sign-in stays on this device instead of syncing to the Nuvio account with the other servers. */
    val syncsSignIn: Boolean
        get() = true

    suspend fun signIn(address: String, username: String, password: String): ServerSignIn = unsupported()

    suspend fun libraries(session: ServerSession): List<ServerLibrary>

    suspend fun libraryPage(
        session: ServerSession,
        library: ServerLibrary,
        start: Int,
        limit: Int
    ): ServerPage<ServerTitle>

    suspend fun details(session: ServerSession, itemId: String): ServerItemDetails

    suspend fun candidates(session: ServerSession, itemId: String): List<ServerCandidate>

    suspend fun preparePlayback(session: ServerSession, request: ServerPlaybackRequest): ServerPlaybackSession

    suspend fun report(session: ServerSession, playback: ServerPlaybackSession, event: ServerPlaybackEvent) = Unit

    suspend fun signOut(session: ServerSession) = Unit

    /** True when [session]'s token runs out soon and is better renewed before the next request. */
    fun renewalDue(session: ServerSession): Boolean = false

    /**
     * A renewed token for [session], ahead of time when [failure] is null, or null when renewing would not help.
     * Throws [ServerFailure.AUTH_REQUIRED] once the server has ended the sign-in.
     */
    suspend fun renewToken(session: ServerSession, failure: ServerException?): String? = null

    /** The copy of [token] uploaded to the Nuvio account. */
    fun tokenForAccount(token: String): String = token

    /** True when the device's [local] token should stay although the account holds [fromAccount]. */
    fun keepsLocalToken(local: String, fromAccount: String): Boolean = false

    /** False when a renewal from [previous] to [renewed] doesn't need uploading to the account. */
    fun renewalChangesAccount(previous: String, renewed: String): Boolean = true

    suspend fun collectionPage(
        session: ServerSession,
        collectionId: String,
        start: Int,
        limit: Int
    ): ServerPage<ServerTitle> = unsupported()

    suspend fun search(
        session: ServerSession,
        kind: ServerMediaKind,
        libraries: List<ServerLibrary>,
        query: String,
        limit: Int
    ): List<ServerTitle> = unsupported()

    suspend fun resumeItems(session: ServerSession, limit: Int): List<ServerTitle> = unsupported()

    suspend fun setPlayed(session: ServerSession, itemId: String, played: Boolean): Unit = unsupported()

    suspend fun externalIdIndex(
        session: ServerSession,
        library: ServerLibrary,
        start: Int,
        limit: Int
    ): ServerPage<ServerIndexEntry> = unsupported()

    /** Items of [library] that may be [query]; null when the server cannot look up a single title this way. */
    suspend fun lookupTitle(
        session: ServerSession,
        library: ServerLibrary,
        query: ServerTitleQuery
    ): List<ServerIndexEntry>? = null

    suspend fun findEpisode(
        session: ServerSession,
        seriesItemId: String,
        season: Int,
        episode: Int
    ): ServerEpisode? = unsupported()
}

data class ServerIndexEntry(
    val itemId: String,
    val ids: TrackingExternalIds,
    val year: Int? = null
)

data class ServerTitleQuery(
    val ids: TrackingExternalIds,
    val name: String?,
    val year: Int?,
    val originalName: String? = null
)

data class ServerEpisode(
    val itemId: String,
    val premiereDate: String?
)

fun ServerProvider.supports(capability: ServerCapability): Boolean = capability in capabilities

private fun unsupported(): Nothing = throw ServerException(ServerFailure.UNSUPPORTED)
