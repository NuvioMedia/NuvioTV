package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.data.mediaserver.ServerConnection
import com.nuvio.tv.data.mediaserver.ServerException
import com.nuvio.tv.data.mediaserver.ServerFailure
import com.nuvio.tv.data.mediaserver.ServerRepository
import com.nuvio.tv.data.mediaserver.serverFailure
import com.nuvio.tv.data.mediaserver.silo.SiloCode
import com.nuvio.tv.data.mediaserver.silo.SiloCodeEnd
import com.nuvio.tv.data.mediaserver.silo.SiloCodeStatus
import com.nuvio.tv.data.mediaserver.silo.SiloProfile
import com.nuvio.tv.data.mediaserver.silo.SiloProvider
import com.nuvio.tv.data.mediaserver.silo.SiloServer
import com.nuvio.tv.data.mediaserver.silo.SiloSignInProblem
import com.nuvio.tv.data.mediaserver.silo.SiloTokens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SiloSignInStep { ADDRESS, FINDING, CODE, PASSWORD, SIGNING_IN, PROFILES, PIN, FAILED }

enum class SiloCodePhase { STARTING, WAITING, EXPIRED, DENIED, UNAVAILABLE }

data class SiloSignInState(
    val step: SiloSignInStep = SiloSignInStep.ADDRESS,
    val server: SiloServer? = null,
    val code: SiloCode? = null,
    val codePhase: SiloCodePhase = SiloCodePhase.STARTING,
    val busy: Boolean = false,
    val error: ServerFailure? = null,
    val problem: SiloSignInProblem? = null,
    val profiles: List<SiloProfile> = emptyList(),
    val pinFor: SiloProfile? = null,
    val pinWrong: Boolean = false
)

/** Signs in to Silo: the server's address, a code approved on another device or the password, then a Silo profile. */
class SiloSignInFlow(
    private val repository: ServerRepository,
    private val scope: CoroutineScope,
    private val cleanup: CoroutineScope = scope,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val mutableState = MutableStateFlow(SiloSignInState())
    val state: StateFlow<SiloSignInState> = mutableState.asStateFlow()

    private val provider: SiloProvider?
        get() = repository.providers.filterIsInstance<SiloProvider>().firstOrNull()

    private var job: Job? = null
    private var codeJob: Job? = null
    private var tokens: SiloTokens? = null
    private var onConnected: (ServerConnection) -> Unit = {}

    fun start(onConnected: (ServerConnection) -> Unit) {
        close()
        this.onConnected = onConnected
    }

    fun find(address: String) {
        val provider = provider ?: return fail(ServerException(ServerFailure.UNSUPPORTED))
        if (address.isBlank() || mutableState.value.step == SiloSignInStep.FINDING) return
        mutableState.value = SiloSignInState(step = SiloSignInStep.FINDING)
        job = scope.launch {
            val server = try {
                provider.findServer(address)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.value = SiloSignInState(step = SiloSignInStep.ADDRESS).withError(error)
                return@launch
            }
            mutableState.value = SiloSignInState(step = SiloSignInStep.CODE, server = server)
            when {
                server.codeSignIn -> startCode(server)
                server.passwordSignIn -> usePassword()
                else -> mutableState.update { it.copy(codePhase = SiloCodePhase.UNAVAILABLE) }
            }
        }
    }

    fun backToAddress() {
        if (mutableState.value.busy) return
        withdrawCode()
        mutableState.value = SiloSignInState()
    }

    fun newCode() {
        val server = mutableState.value.server ?: return
        mutableState.update { it.copy(step = SiloSignInStep.CODE, error = null, problem = null) }
        startCode(server)
    }

    fun useCode() {
        val state = mutableState.value
        if (state.server?.codeSignIn != true || state.busy) return
        if (state.code == null || state.codePhase != SiloCodePhase.WAITING) {
            newCode()
        } else {
            mutableState.update { it.copy(step = SiloSignInStep.CODE, error = null, problem = null) }
        }
    }

    fun usePassword() {
        if (mutableState.value.server?.passwordSignIn != true) return
        mutableState.update { it.copy(step = SiloSignInStep.PASSWORD, busy = false, error = null, problem = null) }
    }

    private fun startCode(server: SiloServer) {
        val provider = provider ?: return
        val previous = codeJob
        val previousCode = mutableState.value.code.takeIf { mutableState.value.codePhase == SiloCodePhase.WAITING }
        previous?.cancel()
        mutableState.update { it.copy(code = null, codePhase = SiloCodePhase.STARTING) }
        codeJob = scope.launch {
            previous?.join()
            previousCode?.let { provider.cancelCode(server, it) }
            runCode(provider, server)
        }
    }

    /** Codes that run out unseen are replaced for a while; one someone opened is not. */
    private suspend fun runCode(provider: SiloProvider, server: SiloServer) {
        val startedAt = clock()
        while (true) {
            val code = try {
                provider.startCode(server)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val phase = if (error.serverFailure() == ServerFailure.NOT_FOUND) SiloCodePhase.UNAVAILABLE else SiloCodePhase.EXPIRED
                mutableState.update { it.copy(codePhase = phase).withError(error) }
                return
            }
            mutableState.update { it.copy(code = code, codePhase = SiloCodePhase.WAITING, error = null, problem = null) }
            var opened = false
            when (awaitCode(provider, server, code) { opened = true }) {
                null -> return
                SiloCodeEnd.EXPIRED -> {
                    if (opened || clock() - startedAt >= CODE_RENEW_FOR_MS) {
                        mutableState.update { it.copy(codePhase = SiloCodePhase.EXPIRED) }
                        return
                    }
                    provider.cancelCode(server, code)
                }
                SiloCodeEnd.DENIED, SiloCodeEnd.CANCELED -> {
                    mutableState.update { it.copy(codePhase = SiloCodePhase.DENIED) }
                    return
                }
                SiloCodeEnd.USED -> {
                    mutableState.update { it.copy(codePhase = SiloCodePhase.EXPIRED) }
                    return
                }
            }
        }
    }

    /** Null once approved (and handed on), otherwise why the code ended. */
    private suspend fun awaitCode(provider: SiloProvider, server: SiloServer, code: SiloCode, onOpened: () -> Unit): SiloCodeEnd? {
        var waitMs = 0L
        var backoffMs = code.pollEveryMs
        var deadline = code.expiresAtMs
        while (true) {
            if (waitMs > 0L) delay(waitMs.coerceAtMost((deadline - clock()).coerceAtLeast(POLL_MIN_MS)))
            if (clock() >= deadline) return SiloCodeEnd.EXPIRED
            val status = try {
                provider.pollCode(server, code)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                waitMs = backoffMs
                backoffMs = (backoffMs * 2).coerceAtMost(POLL_BACKOFF_MAX_MS)
                continue
            }
            backoffMs = code.pollEveryMs
            when (status) {
                is SiloCodeStatus.Waiting -> {
                    deadline = maxOf(deadline, status.expiresAtMs)
                    if (status.opened) onOpened()
                    waitMs = status.pollAfterMs
                }
                is SiloCodeStatus.Approved -> {
                    signedIn(server, status.tokens)
                    return null
                }
                is SiloCodeStatus.Ended -> return status.reason
            }
        }
    }

    fun signInWithPassword(username: String, password: String) {
        val provider = provider ?: return
        val server = mutableState.value.server ?: return
        if (mutableState.value.busy || username.isBlank() || password.isEmpty()) return
        mutableState.update { it.copy(busy = true, error = null, problem = null) }
        job = scope.launch {
            val signedIn = try {
                provider.passwordSignIn(server, username, password)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.update { it.copy(busy = false).withError(error) }
                return@launch
            }
            signedIn(server, signedIn)
        }
    }

    private suspend fun signedIn(server: SiloServer, signedIn: SiloTokens) {
        val provider = provider ?: return
        val polling = codeJob
        if (polling != null && polling != currentCoroutineContext()[Job]) {
            polling.cancel()
            withdrawCode()
        }
        tokens = signedIn
        mutableState.update { it.copy(step = SiloSignInStep.SIGNING_IN, busy = true, error = null, problem = null) }
        val profiles = try {
            provider.checkUsable(server, signedIn)
            provider.profiles(server, signedIn)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            abandon()
            fail(error)
            return
        }
        val only = profiles.singleOrNull()
        when {
            profiles.isEmpty() -> {
                abandon()
                fail(ServerException(ServerFailure.FAILED))
            }
            only != null && !only.hasPin -> finish(only, null)
            only != null -> mutableState.update {
                it.copy(step = SiloSignInStep.PIN, busy = false, profiles = profiles, pinFor = only, pinWrong = false)
            }
            else -> mutableState.update { it.copy(step = SiloSignInStep.PROFILES, busy = false, profiles = profiles) }
        }
    }

    fun pickProfile(profile: SiloProfile) {
        if (mutableState.value.busy) return
        if (profile.hasPin) {
            mutableState.update { it.copy(step = SiloSignInStep.PIN, pinFor = profile, pinWrong = false, error = null) }
        } else {
            job = scope.launch { finish(profile, null) }
        }
    }

    fun backToProfiles() {
        if (mutableState.value.busy || mutableState.value.profiles.size < 2) return
        mutableState.update { it.copy(step = SiloSignInStep.PROFILES, pinFor = null, pinWrong = false, error = null) }
    }

    fun submitPin(pin: String) {
        val provider = provider ?: return
        val state = mutableState.value
        val server = state.server ?: return
        val profile = state.pinFor ?: return
        val signedIn = tokens ?: return
        if (state.busy || pin.isBlank()) return
        mutableState.update { it.copy(busy = true, pinWrong = false, error = null) }
        job = scope.launch {
            val profileToken = try {
                provider.verifyPin(server, signedIn, profile, pin.trim())
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.update { it.copy(busy = false).withError(error) }
                return@launch
            }
            if (profileToken == null) {
                mutableState.update { it.copy(busy = false, pinWrong = true) }
            } else {
                finish(profile, profileToken)
            }
        }
    }

    private suspend fun finish(profile: SiloProfile, profileToken: String?) {
        val provider = provider ?: return
        val state = mutableState.value
        val server = state.server ?: return
        val signedIn = tokens ?: return
        mutableState.update { it.copy(step = SiloSignInStep.SIGNING_IN, busy = true, error = null) }
        val connection = try {
            repository.connect(provider) { provider.signIn(server, signedIn, profile, profileToken, state.profiles.size) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            abandon()
            fail(error)
            return
        }
        tokens = null
        mutableState.value = SiloSignInState()
        onConnected(connection)
    }

    /** Leaves the flow: an unused code is withdrawn and a sign-in that wasn't saved is ended on the server. */
    fun close() {
        job?.cancel()
        codeJob?.cancel()
        job = null
        codeJob = null
        withdrawCode()
        abandon()
        mutableState.value = SiloSignInState()
    }

    private fun withdrawCode() {
        val state = mutableState.value
        val server = state.server ?: return
        val code = state.code ?: return
        val provider = provider ?: return
        if (state.codePhase != SiloCodePhase.WAITING) return
        mutableState.update { it.copy(code = null) }
        cleanup.launch { provider.cancelCode(server, code) }
    }

    private fun abandon() {
        val signedIn = tokens ?: return
        val server = mutableState.value.server ?: return
        val provider = provider ?: return
        tokens = null
        cleanup.launch { provider.abandon(server, signedIn) }
    }

    private fun fail(error: Exception) {
        mutableState.update { it.copy(step = SiloSignInStep.FAILED, busy = false).withError(error) }
    }

    private fun SiloSignInState.withError(error: Exception): SiloSignInState =
        copy(error = error.serverFailure(), problem = SiloProvider.signInProblem(error))

    private companion object {
        const val POLL_MIN_MS = 250L
        const val POLL_BACKOFF_MAX_MS = 30_000L
        const val CODE_RENEW_FOR_MS = 30 * 60_000L
    }
}
