package com.nuvio.tv.core.usenet

import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer

/** One measured setting: download speed with [connections] connections on [port]. */
data class TuneReading(val port: Int, val tls: Boolean, val connections: Int, val mbps: Double)

/** What a run is doing now; [readings] are the settings measured so far. */
data class TuneProgress(
    val phase: Phase,
    /** Connections signed in so far while the limit is checked, or the count being measured. */
    val connections: Int,
    val port: Int,
    val tls: Boolean,
    val readings: List<TuneReading>,
    /** While [Phase.WAITING]: how long until the next attempt. */
    val waitMs: Long = 0
) {
    /** [WAITING]: the provider refused connections; the run waits for it to free them and tries again. */
    enum class Phase { LIMIT, PORT, CONNECTIONS, WAITING }
}

data class TuneResult(
    /** Connections the provider accepted before it refused one; null when no refusal was confirmed. */
    val limit: Int?,
    /** Confirmed simultaneous connections, including the refused slot if a limit was found. */
    val tried: Int,
    val port: Int,
    val tls: Boolean,
    /** The connection allowance to use: within the limit, never lower than needed for the measured speed. */
    val connections: Int,
    /** The fewest connections within reach of the best measured speed. */
    val fastestConnections: Int,
    val mbps: Double,
    val readings: List<TuneReading>,
    val downloadedBytes: Long
)

enum class TuneFailure {
    /** Nothing could be measured: the provider is unreachable, refused, or the sign-in failed. See [TuneOutcome.connect]. */
    CONNECT,
    /** The test NZB could not be downloaded. */
    TEST_DATA,
    /** The provider does not have the test articles. */
    MISSING_ARTICLES,
    /** Too little arrived to measure a speed. */
    TOO_SLOW,
    /**
     * The provider kept refusing connections after the run had signed in: it still counts closed
     * ones as open. Some providers free them only after minutes.
     */
    BUSY
}

sealed interface TuneOutcome {
    data class Success(val result: TuneResult) : TuneOutcome
    data class Failed(
        val reason: TuneFailure,
        val connect: ProviderTestResult? = null,
        /** The limit found before the failure; null when the limit check did not confirm one. */
        val limit: Int? = null,
        /** The usable share of [limit] when the current allowance is above it; that much can still be saved. */
        val allowance: Int? = null
    ) : TuneOutcome
}

/**
 * The decisions of a tuning run, kept apart from the network so they can be tested.
 *
 * The limit check finds how many connections the account really accepts: a connection allowance
 * above it gets refusals, and the engine keeps retrying them during playback. The port and
 * connection rounds then measure download speed with real articles.
 */
internal object TunePlan {
    /** At most this many connections are opened to find the limit, unless the allowance is higher. */
    const val PROBE_CAP = 100
    const val MAX_PROBE = 200
    /** Connections opened together before confirming a refusal with one more sign-in. */
    const val PROBE_BATCH = 8
    /** Connections used to compare ports: few enough to leave the line room, enough to fill most. */
    const val PORT_CONNECTIONS = 8
    /** Readings within this share of the best are as fast. */
    const val GOOD_ENOUGH = 0.95
    /** Another port must be this much faster than the current one to replace it. */
    const val PORT_WIN = 1.10
    /** A step adding less than this over the best so far counts as no gain; two in a row end the round. */
    const val GAIN = 1.03
    private val LADDER = listOf(4, 8, 12, 16, 24, 32, 48, 64, 80, 100, 128, 160, 200)

    fun probeCap(allowance: Int) = maxOf(PROBE_CAP, allowance).coerceAtMost(MAX_PROBE)

    /**
     * Connections that may be used: the limit less a tenth (at least one), so a second device or
     * a slot the server has not yet freed does not cause refusals. Without a limit, what was tried.
     */
    fun usable(limit: Int?, tried: Int): Int = if (limit == null) tried else maxOf(1, limit - maxOf(1, limit / 10))

    /** The counts the connection round measures, up to [usable]. */
    fun ladder(usable: Int): List<Int> = (LADDER.filter { it < usable } + usable).distinct()

    /** TLS ports stay TLS: a plain port would send the login unencrypted. The current port first. */
    fun ports(provider: UsenetProvider): List<Pair<Int, Boolean>> =
        (listOf(provider.port to provider.tls) + if (provider.tls) listOf(563 to true, 443 to true)
            else listOf(119 to false)).distinct()

    fun averages(readings: List<TuneReading>, key: (TuneReading) -> Int): Map<Int, Double> =
        readings.groupBy(key).mapValues { (_, same) -> same.map { it.mbps }.average() }

    /** The fewest connections within [GOOD_ENOUGH] of the best average; null without a reading. */
    fun fastestConnections(readings: List<TuneReading>): Int? {
        val speeds = averages(readings) { it.connections }
        val best = speeds.values.maxOrNull()?.takeIf { it > 0 } ?: return null
        return speeds.filterValues { it >= best * GOOD_ENOUGH }.keys.min()
    }

    /**
     * The allowance to recommend. With a known [limit] it is never above the usable share of it.
     * It is not lowered otherwise: unused connections cost nothing and help seeks and slow periods,
     * and without a refusal there is no evidence of a limit. It is raised when more connections
     * measured clearly faster.
     */
    fun recommend(current: Int, fastest: Int, limit: Int?, tried: Int,
        initial: Map<Int, Double>, repeats: Map<Int, Double>): Int {
        fun fasterThanCurrent(speeds: Map<Int, Double>): Boolean {
            val baseline = speeds[current] ?: return false
            val candidate = speeds[fastest] ?: return false
            return baseline < candidate * GOOD_ENOUGH
        }
        val wanted = if (fastest > current && fasterThanCurrent(initial) && fasterThanCurrent(repeats)) fastest else current
        return if (limit == null) wanted else wanted.coerceAtMost(usable(limit, tried))
    }

    /** Compare working ports at the same number of signed-in connections. */
    fun pickPort(readings: List<TuneReading>, current: Pair<Int, Boolean>,
        remeasure: (TuneReading, Int) -> TuneReading?): Pair<Int, Boolean>? {
        val working = readings.filter { it.mbps > 0 }
        val count = working.minOfOrNull { it.connections } ?: return null
        val comparable = working.mapNotNull { reading ->
            if (reading.connections == count) reading
            else remeasure(reading, count)?.takeIf { it.connections == count }
        }
        fun isCurrent(reading: TuneReading) = reading.port == current.first && reading.tls == current.second
        val now = comparable.find(::isCurrent)
        // Keep a working current port if its equal-count recheck failed.
        if (now == null && working.any(::isCurrent)) return current
        val best = comparable.maxBy { it.mbps }
        return if (now != null && best.mbps < now.mbps * PORT_WIN) current else best.port to best.tls
    }

    /** True when the last two steps of a rising round each added less than [GAIN] over the best before them. */
    fun stopRising(speeds: List<Double>): Boolean {
        if (speeds.size < 3) return false
        fun noGain(i: Int) = speeds[i] < speeds.subList(0, i).max() * GAIN
        return noGain(speeds.lastIndex) && noGain(speeds.lastIndex - 1)
    }

    /** Waits a batch may spend on refused connections, and on a refused first batch. */
    const val BUSY_TRIES = 3
    const val FIRST_BATCH_TRIES = 2

    /**
     * How long to wait before trying a refused batch again: [base], then twice as long each time,
     * within the run's waiting time [left]. 0 when the batch is not tried again.
     */
    fun busyWait(attempt: Int, tries: Int, base: Long, left: Long): Long =
        if (attempt >= tries) 0 else minOf(base shl attempt, left).coerceAtLeast(0)

    /** Message ids of an NZB's segments, in order. */
    fun messageIds(nzb: String): List<String> =
        Regex("<segment\\b[^>]*>\\s*<?([^<>\\s]+?)>?\\s*</segment>").findAll(nzb).map { it.groupValues[1] }.toList()
}

/**
 * Measures, with real articles, the connection limit of a provider account and the port and
 * connection allowance that download fastest on this network. Runs on demand from settings; playback
 * only uses what the user then saves.
 */
@Singleton
class NntpProviderTuner internal constructor(
    private val tester: NntpProviderTester,
    private val testArticles: () -> List<String>,
    private val stepMs: Long = STEP_MS,
    private val settleMs: Long = SETTLE_MS,
    private val warmupMs: Long = WARMUP_MS,
    private val busyWaitMs: Long = BUSY_WAIT_MS,
    private val busyBudgetMs: Long = BUSY_BUDGET_MS
) {
    @Inject constructor(tester: NntpProviderTester) : this(tester, ::downloadTestArticles)

    // A restart may arrive while cancellation is still releasing the previous provider slots.
    private val runMutex = Mutex()

    suspend fun tune(provider: UsenetProvider, allowPrivateNetwork: Boolean, pipeline: Int,
        onProgress: (TuneProgress) -> Unit): TuneOutcome = runMutex.withLock {
        runInterruptible(Dispatchers.IO) {
            Run(provider, allowPrivateNetwork, pipeline.coerceAtLeast(1), onProgress).execute()
        }
    }

    private inner class Run(
        val provider: UsenetProvider,
        val allowPrivate: Boolean,
        val pipeline: Int,
        val onProgress: (TuneProgress) -> Unit
    ) {
        val readings = mutableListOf<TuneReading>()
        val downloaded = AtomicLong()
        lateinit var addresses: List<InetAddress>
        lateinit var articles: List<String>
        val next = AtomicInteger()
        /** Connections the provider accepted before it refused one, once the limit check confirmed it. */
        var limit: Int? = null
        var tried = 0
        /** Time left for waiting on refused connections, shared by the whole run. */
        var busyLeftMs = busyBudgetMs

        /** A failure that still carries the limit found, so an allowance above it can be lowered. */
        fun failed(reason: TuneFailure, connect: ProviderTestResult? = null): TuneOutcome.Failed {
            val found = limit ?: return TuneOutcome.Failed(reason, connect)
            val usable = TunePlan.usable(found, tried)
            return TuneOutcome.Failed(reason, connect, found, usable.takeIf { provider.connections > it })
        }

        fun execute(): TuneOutcome {
            val (usableAddresses, unusable) = tester.resolveUsable(provider, allowPrivate)
            if (unusable != null) return TuneOutcome.Failed(TuneFailure.CONNECT, unusable)
            addresses = usableAddresses
            // The test articles download while the limit is checked.
            val testData = FutureTask { testArticles() }
            thread(isDaemon = true, name = "usenet-tune-nzb") { testData.run() }

            // 1. The limit: sign in batch after batch, holding every connection, until one is refused.
            val cap = TunePlan.probeCap(provider.connections)
            val held = mutableListOf<NntpSession>()
            try {
                var firstWaits = 0
                while (held.size < cap) {
                    progress(TuneProgress.Phase.LIMIT, held.size, provider.port, provider.tls)
                    val batch = openBatch(provider, minOf(TunePlan.PROBE_BATCH, cap - held.size))
                    batch.mapNotNull { it.session }.let(held::addAll)
                    val failure = batch.firstOrNull { it.session == null }?.result
                    if (failure != null) {
                        if (held.isEmpty()) {
                            // Refused outright: the slots of a run or stream that just ended may not be free yet.
                            val wait = if (batch.all { it.result == ProviderTestResult.REFUSED })
                                TunePlan.busyWait(firstWaits++, TunePlan.FIRST_BATCH_TRIES, busyWaitMs, busyLeftMs) else 0
                            if (wait > 0) { waitForSlots(wait, provider.port, provider.tls, 0); continue }
                            // The first connection failing is a setup problem, not a limit.
                            return TuneOutcome.Failed(TuneFailure.CONNECT, failure)
                        }
                        // Confirmed after a pause, so a slot the provider frees late does not pass for the limit.
                        settle()
                        val retry = openBatch(provider, 1).single()
                        if (retry.session == null) {
                            if (retry.result == ProviderTestResult.REFUSED && batch.any { it.result == ProviderTestResult.REFUSED }) {
                                limit = held.size
                            }
                            break
                        }
                        held += retry.session
                    }
                }
            } finally {
                held.forEach(::quit)
            }
            tried = limit?.let { it + 1 } ?: held.size
            settle()

            articles = try { testData.get() } catch (_: ExecutionException) { emptyList() }
            if (articles.size < MIN_ARTICLES) return failed(TuneFailure.TEST_DATA)
            next.set((articles.indices).random())
            val usable = TunePlan.usable(limit, tried)

            // 2. The port, at a few connections.
            val ports = TunePlan.ports(provider)
            val portConnections = minOf(TunePlan.PORT_CONNECTIONS, usable)
            val portFailures = mutableListOf<Pair<TuneFailure, ProviderTestResult?>>()
            for ((port, tls) in ports) {
                if (measure(TuneProgress.Phase.PORT, port, tls, portConnections) != null) continue
                val reason = failure ?: TuneFailure.TOO_SLOW
                if (reason == TuneFailure.MISSING_ARTICLES) return failed(reason)
                portFailures += reason to connectFailure
            }
            val (port, tls) = TunePlan.pickPort(readings, provider.port to provider.tls) { reading, count ->
                measure(TuneProgress.Phase.PORT, reading.port, reading.tls, count)
            } ?: run {
                // A provider still refusing explains more than a port that does not answer; otherwise the current port's reason.
                val (reason, connect) = portFailures.firstOrNull { it.first == TuneFailure.BUSY }
                    ?: portFailures.firstOrNull() ?: (TuneFailure.TOO_SLOW to null)
                return failed(reason, connect)
            }

            // 3. The connection count on that port, rising until more stop helping or the limit is reached.
            val rising = mutableListOf<Double>()
            for (count in TunePlan.ladder(usable)) {
                val reading = measure(TuneProgress.Phase.CONNECTIONS, port, tls, count)
                rising += reading?.mbps ?: 0.0
                if (reading == null && (failure == TuneFailure.CONNECT || failure == TuneFailure.BUSY)) break
                if (TunePlan.stopRising(rising)) break
            }
            val current = provider.connections
            if (current <= usable && readings.none { it.port == port && it.tls == tls && it.connections == current }) {
                measure(TuneProgress.Phase.CONNECTIONS, port, tls, current)
            }
            val round = readings.filter { it.port == port && it.tls == tls }
            val initial = TunePlan.averages(round) { it.connections }
            val candidate = TunePlan.fastestConnections(round) ?: return failed(TuneFailure.TOO_SLOW)
            val countsToRepeat = if (candidate > current && current in initial) listOf(current, candidate)
                else initial.entries.sortedByDescending { it.value }.take(2).map { it.key }
            val repeats = mutableMapOf<Int, Double>()
            for (count in countsToRepeat) {
                val repeat = measure(TuneProgress.Phase.CONNECTIONS, port, tls, count)
                if (repeat?.connections == count) repeats[count] = repeat.mbps
            }
            val final = readings.filter { it.port == port && it.tls == tls }
            val fastest = TunePlan.fastestConnections(final) ?: return failed(TuneFailure.TOO_SLOW)
            val recommended = TunePlan.recommend(current, fastest, limit, tried, initial, repeats)
            val speed = TunePlan.averages(final) { it.connections }
            val mbps = speed[recommended] ?: speed.filterKeys { it <= recommended }.maxByOrNull { it.key }?.value ?: speed.getValue(fastest)
            return TuneOutcome.Success(TuneResult(limit, tried, port, tls, recommended, fastest, mbps, readings.toList(), downloaded.get()))
        }

        var failure: TuneFailure? = null
        /** Why the last [measure] signed in no connection, when it failed with [TuneFailure.CONNECT] or [TuneFailure.BUSY]. */
        var connectFailure: ProviderTestResult? = null

        fun progress(phase: TuneProgress.Phase, connections: Int, port: Int, tls: Boolean) =
            onProgress(TuneProgress(phase, connections, port, tls, readings.toList()))

        /** Signs out before closing: an idle session, so the provider can free its slot at once. */
        fun quit(session: NntpSession) {
            runCatching { session.command("QUIT") }
            session.close()
        }

        fun waitForSlots(waitMs: Long, port: Int, tls: Boolean, connections: Int) {
            onProgress(TuneProgress(TuneProgress.Phase.WAITING, connections, port, tls, readings.toList(), waitMs))
            Thread.sleep(waitMs)
            busyLeftMs -= waitMs
        }

        /**
         * Opens [count] connections. Some providers free the slots of closed connections only after a
         * while and refuse new ones meanwhile, so a refused batch is signed out and tried again after a
         * wait, within the run's waiting time; then whatever signed in is used. Empty, with [failure]
         * and [connectFailure], when nothing signed in.
         */
        fun openWhenFree(phase: TuneProgress.Phase, target: UsenetProvider, count: Int): List<NntpSession> {
            var attempt = 0
            while (true) {
                val opened = openBatch(target, count)
                val sessions = opened.mapNotNull { it.session }
                val refused = opened.mapNotNull { result -> result.result.takeIf { result.session == null } }
                // The limit check signed in with these credentials, so a refused sign-in now is the provider out of slots too.
                val busy = refused.any { it == ProviderTestResult.REFUSED || it == ProviderTestResult.AUTH }
                val wait = if (busy) TunePlan.busyWait(attempt++, TunePlan.BUSY_TRIES, busyWaitMs, busyLeftMs) else 0
                if (wait <= 0) {
                    if (sessions.isEmpty()) {
                        failure = if (busy) TuneFailure.BUSY else TuneFailure.CONNECT
                        connectFailure = refused.firstOrNull()
                    }
                    return sessions
                }
                sessions.forEach(::quit)
                waitForSlots(wait, target.port, target.tls, count)
                progress(phase, count, target.port, target.tls)
            }
        }

        /** Opens [count] connections at once; each result keeps its session when it signed in. */
        fun openBatch(target: UsenetProvider, count: Int): List<OpenResult> {
            val results = arrayOfNulls<OpenResult>(count)
            val done = CountDownLatch(count)
            val sockets = mutableListOf<Socket>()
            var cancelled = false
            fun newSocket(): Socket = synchronized(sockets) {
                if (cancelled) throw IOException("Connection batch cancelled")
                Socket().also(sockets::add)
            }
            repeat(count) { i ->
                thread(isDaemon = true, name = "usenet-tune-open") {
                    try { results[i] = tester.open(target, allowPrivate, addresses, ::newSocket) }
                    catch (_: Exception) { results[i] = OpenResult(ProviderTestResult.UNREACHABLE) }
                    finally { done.countDown() }
                }
            }
            try { done.await() } catch (e: InterruptedException) {
                synchronized(sockets) { cancelled = true }
                sockets.forEach { runCatching { it.close() } }
                done.await()
                results.forEach { it?.session?.close() }
                throw e
            }
            return results.map { it ?: OpenResult(ProviderTestResult.UNREACHABLE) }
        }

        /**
         * Downloads with [connections] connections for [stepMs] after a short warm-up; null (and
         * [failure]) when nothing could be measured. Sign-in time is not measured.
         */
        fun measure(phase: TuneProgress.Phase, port: Int, tls: Boolean, connections: Int): TuneReading? {
            failure = null
            connectFailure = null
            progress(phase, connections, port, tls)
            val sessions = openWhenFree(phase, provider.copy(port = port, tls = tls), connections)
            if (sessions.isEmpty()) {
                settle()
                return null
            }
            val received = AtomicLong()
            val answered = AtomicInteger()
            val missing = AtomicInteger()
            val workers = sessions.map { session -> thread(isDaemon = true, name = "usenet-tune") { download(session, received, answered, missing) } }
            try {
                Thread.sleep(warmupMs)
                val startBytes = received.get()
                val start = System.nanoTime()
                while (true) {
                    Thread.sleep(POLL_MS)
                    val elapsed = (System.nanoTime() - start) / 1_000_000
                    if (elapsed >= stepMs) break
                    if (elapsed >= MIN_STEP_MS && received.get() - startBytes >= MAX_STEP_BYTES) break
                    if (workers.none { it.isAlive }) break
                }
                val seconds = (System.nanoTime() - start) / 1e9
                val bytes = received.get() - startBytes
                if (answered.get() >= MISSING_SAMPLE && missing.get() * 2 > answered.get()) {
                    failure = TuneFailure.MISSING_ARTICLES
                    return null
                }
                if (bytes <= 0) { failure = TuneFailure.TOO_SLOW; return null }
                val reading = TuneReading(port, tls, sessions.size, bytes * 8 / 1e6 / seconds)
                readings += reading
                progress(phase, connections, port, tls)
                return reading
            } finally {
                sessions.forEach { it.close() }
                workers.forEach { it.join(1_000) }
                downloaded.addAndGet(received.get())
                settle()
            }
        }

        /** BODY requests, [pipeline] in flight, until the session is closed. */
        fun download(session: NntpSession, received: AtomicLong, answered: AtomicInteger, missing: AtomicInteger) {
            fun nextId() = articles[Math.floorMod(next.getAndIncrement(), articles.size)]
            try {
                session.timeoutMs = READ_TIMEOUT_MS
                session.commands(List(pipeline) { "BODY <${nextId()}>" })
                while (true) {
                    val status = session.readLine() ?: return
                    answered.incrementAndGet()
                    when (status.take(3)) {
                        "222" -> session.skipBody { received.addAndGet(it.toLong()) }
                        "430", "423" -> missing.incrementAndGet()
                        else -> return
                    }
                    session.command("BODY <${nextId()}>")
                }
            } catch (_: IOException) {
                // Closed at the end of the step, or the connection failed: either way this worker is done.
            }
        }

        /** Gives the server time to free the slots of closed connections. */
        fun settle() = Thread.sleep(settleMs)
    }

    internal companion object {
        /** A public test NZB kept current by SABnzbd: about 1 GB in 1561 articles posted for speed tests. */
        const val TEST_NZB_URL = "https://sabnzbd.org/tests/test_download_1GB.nzb"
        const val STEP_MS = 8_000L
        const val SETTLE_MS = 2_500L
        const val WARMUP_MS = 1_500L
        const val MIN_STEP_MS = 3_000L
        const val POLL_MS = 100L
        /** A step on a fast line ends early once this much was measured, to spare block accounts. */
        const val MAX_STEP_BYTES = 250L * 1024 * 1024
        const val READ_TIMEOUT_MS = 15_000
        const val MIN_ARTICLES = 50
        /** First wait for refused connections; each further one is twice as long. */
        const val BUSY_WAIT_MS = 5_000L
        /** At most this long is spent waiting for refused connections in one run. */
        const val BUSY_BUDGET_MS = 60_000L
        /** Missing articles are judged once this many replies arrived. */
        const val MISSING_SAMPLE = 10

        private val http by lazy {
            OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
        }

        fun downloadTestArticles(): List<String> {
            val request = Request.Builder().url(TEST_NZB_URL).header(USER_AGENT, NUVIO_USER_AGENT).build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Test NZB: HTTP ${response.code}")
                val source = (response.body ?: throw IOException("Test NZB: empty")).source()
                val buffer = Buffer()
                while (buffer.size <= MAX_NZB_BYTES && source.read(buffer, 64 * 1024L) != -1L) Unit
                if (buffer.size > MAX_NZB_BYTES) throw IOException("Test NZB too large")
                return TunePlan.messageIds(buffer.readUtf8())
            }
        }

        private const val MAX_NZB_BYTES = 4 * 1024 * 1024
    }
}
