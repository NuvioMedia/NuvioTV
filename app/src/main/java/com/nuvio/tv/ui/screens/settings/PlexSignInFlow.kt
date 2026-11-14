package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.data.mediaserver.ServerConnection
import com.nuvio.tv.data.mediaserver.ServerException
import com.nuvio.tv.data.mediaserver.ServerFailure
import com.nuvio.tv.data.mediaserver.ServerRepository
import com.nuvio.tv.data.mediaserver.plex.PlexAccount
import com.nuvio.tv.data.mediaserver.plex.PlexHomeUser
import com.nuvio.tv.data.mediaserver.plex.PlexPin
import com.nuvio.tv.data.mediaserver.plex.PlexPinStatus
import com.nuvio.tv.data.mediaserver.plex.PlexProvider
import com.nuvio.tv.data.mediaserver.plex.PlexReach
import com.nuvio.tv.data.mediaserver.plex.PlexServer
import com.nuvio.tv.data.mediaserver.serverFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class PlexSignInStep { CODE, SERVERS, USERS, PIN, SIGNING_IN, FAILED }

enum class PlexCodePhase { STARTING, WAITING, EXPIRED }

data class PlexSignInState(
    val step: PlexSignInStep = PlexSignInStep.CODE,
    val codePhase: PlexCodePhase = PlexCodePhase.STARTING,
    val pin: PlexPin? = null,
    val accountName: String? = null,
    val servers: List<PlexServer> = emptyList(),
    val serversLoading: Boolean = false,
    val server: PlexServer? = null,
    val users: List<PlexHomeUser> = emptyList(),
    val pinFor: PlexHomeUser? = null,
    val pinWrong: Boolean = false,
    val busy: Boolean = false,
    val error: ServerFailure? = null
)

/** Signs in to Plex: a code at plex.tv/link, then one of the account's servers, then a Plex Home user when there are several. */
class PlexSignInFlow(
    private val repository: ServerRepository,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val mutableState = MutableStateFlow(PlexSignInState())
    val state: StateFlow<PlexSignInState> = mutableState.asStateFlow()

    private val provider: PlexProvider?
        get() = repository.providers.filterIsInstance<PlexProvider>().firstOrNull()

    private var job: Job? = null
    private var owner: PlexAccount? = null
    private var onConnected: (ServerConnection) -> Unit = {}
    private var paused = false

    fun start(onConnected: (ServerConnection) -> Unit) {
        close()
        this.onConnected = onConnected
        newCode()
    }

    fun newCode() {
        val provider = provider ?: return fail(ServerException(ServerFailure.UNSUPPORTED))
        job?.cancel()
        owner = null
        mutableState.value = PlexSignInState()
        job = scope.launch { runCode(provider) }
    }

    /** No asking plex.tv while the app is in the background; a fresh code is shown when it comes back. */
    fun pause() {
        if (mutableState.value.step != PlexSignInStep.CODE || job?.isActive != true) return
        job?.cancel()
        paused = true
    }

    fun resume() {
        if (!paused) return
        paused = false
        if (mutableState.value.step == PlexSignInStep.CODE) newCode()
    }

    /** Plex codes last a few minutes, so new ones keep coming for a while when nobody links one. */
    private suspend fun runCode(provider: PlexProvider) {
        val startedAt = clock()
        while (clock() - startedAt < CODE_RENEW_FOR_MS) {
            val pin = try {
                provider.account.startPin()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                fail(error)
                return
            }
            mutableState.update { it.copy(pin = pin, codePhase = PlexCodePhase.WAITING, error = null) }
            val token = awaitLink(provider, pin)
            if (token != null) {
                linked(provider, token)
                return
            }
        }
        mutableState.update { it.copy(codePhase = PlexCodePhase.EXPIRED) }
    }

    private suspend fun awaitLink(provider: PlexProvider, pin: PlexPin): String? {
        var waitMs = POLL_MS
        while (clock() < pin.expiresAtMs) {
            delay(waitMs)
            val status = try {
                provider.account.checkPin(pin).also { waitMs = POLL_MS }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                waitMs = (waitMs * 2).coerceAtMost(POLL_BACKOFF_MAX_MS)
                continue
            }
            when (status) {
                is PlexPinStatus.Linked -> return status.accountToken
                PlexPinStatus.Expired -> return null
                PlexPinStatus.Waiting -> Unit
            }
        }
        return null
    }

    private suspend fun linked(provider: PlexProvider, token: String) {
        mutableState.update { it.copy(step = PlexSignInStep.SERVERS, serversLoading = true) }
        val reached = try {
            val signedIn = provider.account.account(token)
            owner = signedIn
            mutableState.update { it.copy(accountName = signedIn.name) }
            coroutineScope {
                val users = async { provider.account.homeUsers(signedIn) }
                val servers = provider.account.servers(token)
                mutableState.update { it.copy(servers = servers) }
                val reached = servers.map { server -> async { provider.reach(server) } }.awaitAll()
                mutableState.update { it.copy(users = users.await()) }
                reached
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            fail(error)
            return
        }
        val ordered = reached.sortedWith(compareBy({ it.reach.ordinal }, { !it.owned }, { it.name.lowercase() }))
        mutableState.update { it.copy(servers = ordered, serversLoading = false) }
        ordered.singleOrNull()?.takeIf { it.reach != PlexReach.OFFLINE }?.let(::pickServer)
    }

    fun pickServer(server: PlexServer) {
        val state = mutableState.value
        if (state.busy || server.reach == PlexReach.OFFLINE) return
        mutableState.update { it.copy(server = server, error = null) }
        val only = state.users.singleOrNull()
        when {
            state.users.isEmpty() || only != null && !only.hasPin -> finish(server, only, pin = null)
            only != null -> mutableState.update { it.copy(step = PlexSignInStep.PIN, pinFor = only, pinWrong = false) }
            else -> mutableState.update { it.copy(step = PlexSignInStep.USERS) }
        }
    }

    fun pickUser(user: PlexHomeUser) {
        val server = mutableState.value.server ?: return
        if (mutableState.value.busy) return
        if (user.hasPin) {
            mutableState.update { it.copy(step = PlexSignInStep.PIN, pinFor = user, pinWrong = false, error = null) }
        } else {
            finish(server, user, pin = null)
        }
    }

    fun submitPin(pin: String) {
        val state = mutableState.value
        val server = state.server ?: return
        val user = state.pinFor ?: return
        if (state.busy || pin.isBlank()) return
        finish(server, user, pin.trim())
    }

    fun back() {
        val state = mutableState.value
        if (state.busy) return
        val previous = when (state.step) {
            PlexSignInStep.PIN -> if (state.users.size > 1) PlexSignInStep.USERS else PlexSignInStep.SERVERS
            PlexSignInStep.USERS -> PlexSignInStep.SERVERS
            else -> return
        }
        mutableState.update { it.copy(step = previous, pinFor = null, pinWrong = false, error = null) }
    }

    private fun finish(server: PlexServer, user: PlexHomeUser?, pin: String?) {
        val provider = provider ?: return
        val owner = owner ?: return
        val step = mutableState.value.step
        mutableState.update { it.copy(busy = true, pinWrong = false, error = null) }
        job = scope.launch {
            val connection = try {
                val switched = if (user == null) owner else provider.account.switchUser(owner, user, pin)
                if (switched == null) {
                    mutableState.update { it.copy(busy = false, pinWrong = true) }
                    return@launch
                }
                mutableState.update { it.copy(step = PlexSignInStep.SIGNING_IN) }
                val serverForUser = provider.serverFor(switched, owner, server)
                repository.connect(provider) { provider.signIn(serverForUser, switched) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.update { it.copy(step = step, busy = false, error = error.serverFailure()) }
                return@launch
            }
            this@PlexSignInFlow.owner = null
            mutableState.value = PlexSignInState()
            onConnected(connection)
        }
    }

    fun close() {
        paused = false
        job?.cancel()
        job = null
        owner = null
        mutableState.value = PlexSignInState()
    }

    private fun fail(error: Exception) {
        mutableState.update {
            it.copy(step = PlexSignInStep.FAILED, busy = false, serversLoading = false, error = error.serverFailure())
        }
    }

    private companion object {
        const val POLL_MS = 1_000L
        const val POLL_BACKOFF_MAX_MS = 15_000L
        const val CODE_RENEW_FOR_MS = 30 * 60_000L
    }
}
