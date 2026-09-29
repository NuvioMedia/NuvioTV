package com.nuvio.tv.data.repository

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.AddonStreams
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class StreamSearchRequestKey(
    val profileId: Int,
    val type: String,
    val videoId: String,
    val season: Int?,
    val episode: Int?,
    val sourceConfiguration: String
)

/**
 * Keeps a small set of source searches alive independently of UI collectors.
 * Completed results are memory-only and short-lived because addon URLs may expire.
 */
internal class StreamSearchSessionCache(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val completedTtlMs: Long = DEFAULT_COMPLETED_TTL_MS,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES
) {
    private data class Snapshot(
        val result: NetworkResult<List<AddonStreams>>,
        val version: Long,
        val isComplete: Boolean
    )

    private class Session(
        val state: MutableStateFlow<Snapshot>
    ) {
        lateinit var job: Job
        @Volatile var invalidated: Boolean = false
        var completedAtMs: Long? = null
        var lastSuccessfulResult: NetworkResult.Success<List<AddonStreams>>? = null
    }

    private val mutex = Mutex()
    private val sessions = LinkedHashMap<StreamSearchRequestKey, Session>(16, 0.75f, true)

    fun observe(
        key: StreamSearchRequestKey,
        forceRefresh: Boolean,
        producer: () -> Flow<NetworkResult<List<AddonStreams>>>
    ): Flow<NetworkResult<List<AddonStreams>>> = flow {
        val session = acquireSession(key, forceRefresh, producer)
        var emittedVersion = -1L
        emitAll(
            session.state.transformWhile { snapshot ->
                if (snapshot.version != emittedVersion) {
                    emittedVersion = snapshot.version
                    emit(snapshot.result)
                }
                !snapshot.isComplete
            }
        )
    }

    private suspend fun acquireSession(
        key: StreamSearchRequestKey,
        forceRefresh: Boolean,
        producer: () -> Flow<NetworkResult<List<AddonStreams>>>
    ): Session {
        var created: Session? = null
        val selected = mutex.withLock {
            removeExpiredLocked()
            removeObsoleteSessionsLocked(key)

            if (forceRefresh) {
                sessions.remove(key)?.cancelAndComplete()
            } else {
                sessions[key]?.let { return@withLock it }
            }

            createSession(key, producer).also { session ->
                sessions[key] = session
                trimToSizeLocked()
                created = session
            }
        }
        withContext(NonCancellable) {
            created?.job?.start()
        }
        return selected
    }

    private fun createSession(
        key: StreamSearchRequestKey,
        producer: () -> Flow<NetworkResult<List<AddonStreams>>>
    ): Session {
        val session = Session(
            MutableStateFlow(
                Snapshot(
                    result = NetworkResult.Loading,
                    version = 0L,
                    isComplete = false
                )
            )
        )
        session.job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                producer().collect { result ->
                    if (result is NetworkResult.Success && result.data.isNotEmpty()) {
                        session.lastSuccessfulResult = result
                    }
                    session.state.update { current ->
                        Snapshot(
                            result = result,
                            version = current.version + 1,
                            isComplete = false
                        )
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                session.state.update { current ->
                    Snapshot(
                        result = NetworkResult.Error(error.message ?: "Failed to fetch streams"),
                        version = current.version + 1,
                        isComplete = false
                    )
                }
            } finally {
                completeSession(key, session)
            }
        }
        return session
    }

    private suspend fun completeSession(key: StreamSearchRequestKey, session: Session) {
        if (session.invalidated) return
        session.state.update { current ->
            val lastSuccess = session.lastSuccessfulResult
            if (lastSuccess != null && current.result !is NetworkResult.Success) {
                Snapshot(
                    result = lastSuccess,
                    version = current.version + 1,
                    isComplete = true
                )
            } else {
                current.copy(isComplete = true)
            }
        }
        mutex.withLock {
            if (sessions[key] !== session) return@withLock
            if (session.lastSuccessfulResult != null) {
                session.completedAtMs = nowMs()
            } else {
                sessions.remove(key)
            }
        }
    }

    private fun removeObsoleteSessionsLocked(requestedKey: StreamSearchRequestKey) {
        val iterator = sessions.entries.iterator()
        while (iterator.hasNext()) {
            val (existingKey, session) = iterator.next()
            val belongsToAnotherProfile = existingKey.profileId != requestedKey.profileId
            val sourceConfigurationChanged = existingKey.matchesMediaRequest(requestedKey) &&
                existingKey.sourceConfiguration != requestedKey.sourceConfiguration
            if (belongsToAnotherProfile || sourceConfigurationChanged) {
                iterator.remove()
                session.cancelAndComplete()
            }
        }
    }

    private fun removeExpiredLocked() {
        val now = nowMs()
        val iterator = sessions.entries.iterator()
        while (iterator.hasNext()) {
            val session = iterator.next().value
            val completedAt = session.completedAtMs ?: continue
            if (now - completedAt >= completedTtlMs) {
                iterator.remove()
                session.cancelAndComplete()
            }
        }
    }

    private fun trimToSizeLocked() {
        while (sessions.size > maxEntries) {
            val iterator = sessions.entries.iterator()
            if (!iterator.hasNext()) return
            val session = iterator.next().value
            iterator.remove()
            session.cancelAndComplete()
        }
    }

    private fun Session.cancelAndComplete() {
        invalidated = true
        state.update { current -> current.copy(isComplete = true) }
        job.cancel()
    }

    private fun StreamSearchRequestKey.matchesMediaRequest(other: StreamSearchRequestKey): Boolean =
        profileId == other.profileId &&
            type == other.type &&
            videoId == other.videoId &&
            season == other.season &&
            episode == other.episode

    private companion object {
        const val DEFAULT_COMPLETED_TTL_MS = 15 * 60 * 1_000L
        const val DEFAULT_MAX_ENTRIES = 12
    }
}
