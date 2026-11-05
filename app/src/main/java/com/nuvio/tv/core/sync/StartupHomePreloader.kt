package com.nuvio.tv.core.sync

import android.util.Log
import com.nuvio.tv.core.build.AppFeaturePolicy
import com.nuvio.tv.core.perf.FluidModeState
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.local.ContinueWatchingEnrichmentCache
import com.nuvio.tv.data.local.ExperienceModeDataStore
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.StartupWarmupPreferences
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.repository.TraktTop10Repository
import com.nuvio.tv.data.translation.MetadataTextTranslator
import com.nuvio.tv.data.trailer.TrailerService
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.CalendarRepository
import com.nuvio.tv.domain.repository.CatalogRepository
import com.nuvio.tv.ui.screens.home.extractYear
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Preloads Home-critical data during the startup splash and profile selection
 * so the first Home frame is already warm.
 *
 * Readiness is dynamic: phases are re-attempted until every one of them has
 * actually satisfied (no timeout), so the post-profile loading screen holds
 * for exactly as long as the data needs. A hard [TOTAL_DEADLINE_MS] cap
 * force-releases the gate so a slow or unreachable source can never block
 * Home for more than a minute.
 *
 * Waits for: active profile, installed addons, layout/experience prefs,
 * catalog first-page warm-up, the on-disk image warm-up for every Home card,
 * calendar warm-up, and a CW disk-cache touch.
 */
@Singleton
class StartupHomePreloader @Inject constructor(
    private val profileManager: com.nuvio.tv.core.profile.ProfileManager,
    private val addonRepository: AddonRepository,
    private val catalogRepository: CatalogRepository,
    private val calendarRepository: CalendarRepository,
    private val traktTop10Repository: TraktTop10Repository,
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    private val experienceModeDataStore: ExperienceModeDataStore,
    private val cwEnrichmentCache: ContinueWatchingEnrichmentCache,
    private val metadataTextTranslator: MetadataTextTranslator,
    private val tmdbSettingsDataStore: TmdbSettingsDataStore,
    private val fluidModeState: FluidModeState,
    private val homeImagePreloader: HomeImagePreloader,
    private val tmdbService: TmdbService,
    private val trailerService: TrailerService,
    private val startupWarmupPreferences: StartupWarmupPreferences
) {
    companion object {
        private const val TAG = "StartupHomePreloader"

        // Fluid Mode: longer phases plus a retry loop with a hard deadline, so the
        // loading screen stays up until the catalogs are actually warm.
        private const val PHASE_TIMEOUT_MS = 40_000L
        private const val TOTAL_DEADLINE_MS = 120_000L
        private const val RETRY_DELAY_MS = 2_000L

        // Classic (Fluid Mode off): the single pass of the build before the speed-ups.
        private const val CLASSIC_PHASE_TIMEOUT_MS = 20_000L

        // Trailer warm-up: bounded so a slow extraction never holds the gate.
        private const val TRAILER_WARM_BUDGET_MS = 30_000L
        private const val TRAILER_WARM_ITEM_MS = 6_000L
        private const val TRAILER_WARM_COUNT = 6

        // Modalità graduale (stile Netflix) per dispositivi con poca RAM: sotto
        // questa classe di memoria il burst di avvio (tutti i cataloghi + tutte
        // le immagini scheda + 6 estrazioni trailer in un minuto) satura la
        // Stick e lmkd uccide il processo. Invece di un temporale: il gate
        // aspetta solo il minimo per una prima schermata utile, il resto
        // arriva a rate dopo, mentre l'utente naviga.
        private const val GENTLE_RAM_KB_THRESHOLD = 1_677_721L // 1.6 GB
        private const val GENTLE_PHASE_PACE_MS = 1_500L
        // La riga "Ultimi uscite" è la prima che l'utente apre dopo la home:
        // le sue immagini partono subito (+1s), prima ancora di quelle di
        // catalogo (+10s). Priorità Netflix: pronta, non "arriva dopo".
        private const val GENTLE_CW_IMAGE_WARM_DELAY_MS = 1_000L
        private const val GENTLE_IMAGE_WARM_DELAY_MS = 10_000L
        private const val GENTLE_TRANSLATION_WARM_DELAY_MS = 5_000L

        /**
         * Il warm immagini compete con lo scroll per CPU e decodifica: su una
         * Stick da 1 GB, avviato mentre l'utente naviga, è una causa diretta
         * di blocchi. Parte solo dopo 2 s di inattività (atteso max 60 s).
         */
        private const val GENTLE_WARM_IDLE_MAX_WAIT_MS = 60_000L
    }

    // Warm-ups run on a background-priority thread: on low-end TV sticks the
    // preload must yield CPU to the UI (it still runs at full speed whenever
    // the UI is idle) instead of competing with the first Home frames.
    private val preloadDispatcher = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "startup-home-preload").apply {
            priority = Thread.MIN_PRIORITY
        }
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + preloadDispatcher)

    // The ML Kit warm-up is mostly network (model download) and used to share the
    // background-priority thread above, so it queued behind every preload phase and
    // routinely finished after the first Home row had already asked for a
    // translation — leaving that row to wait on the download itself.
    private val warmUpDispatcher = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "translation-warm-up").apply {
            priority = Thread.NORM_PRIORITY
        }
    }.asCoroutineDispatcher()
    private val warmUpScope = CoroutineScope(SupervisorJob() + warmUpDispatcher)
    private var preloadJob: Job? = null
    private var profileWatchJob: Job? = null

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private val _started = MutableStateFlow(false)

    /**
     * true su dispositivi con RAM sotto soglia (Fire TV Stick da 1 GB):
     * attiva la politica di caricamento graduale invece del burst di avvio.
     */
    private val gentleProfile: Boolean by lazy {
        runCatching {
            var memTotalKb = 0L
            java.io.File("/proc/meminfo").forEachLine { line ->
                if (line.startsWith("MemTotal:")) {
                    memTotalKb = line.substringAfter(':').removeSuffix("kB").trim().toLongOrNull() ?: 0L
                }
            }
            memTotalKb in 1L until GENTLE_RAM_KB_THRESHOLD
        }.getOrDefault(false)
    }

    fun ensureStarted() {
        if (!_started.compareAndSet(expect = false, update = true)) return
        Log.i(TAG, "preload requested (startup gate opened) profile=${if (gentleProfile) "gentle-lowram" else "full"}")

        // Fire-and-forget: pre-download the translation model once so the first
        // detail screen doesn't stall waiting for a multi-MB ML Kit download.
        // In modalità graduale parte con un piccolo ritardo: il modello è un
        // carico nativo da alcuni MB e non deve competere con il primo
        // rendering della home. Prima erano 30 secondi — abbastanza perché le
        // prime hero apparissero non tradotte e solo poi cambiassero; a 5s il
        // modello è pronto mentre la home si sta ancora popolando.
        warmUpScope.launch {
            if (gentleProfile) delay(GENTLE_TRANSLATION_WARM_DELAY_MS)
            warmUpTranslationModel()
        }

        profileWatchJob = scope.launch {
            var lastProfileId = profileManager.activeProfileId.value
            profileManager.activeProfileId.collect { profileId ->
                if (profileId != lastProfileId) {
                    lastProfileId = profileId
                    _ready.value = false
                    startPreload()
                }
            }
        }
        startPreload()
    }

    private suspend fun warmUpTranslationModel() {
        runCatching {
            val language = tmdbSettingsDataStore.settings.first().language
            metadataTextTranslator.warmUp(language)
        }.onFailure { e ->
            Log.w(TAG, "Translation model warm-up failed: ${e.message}")
        }
    }
    private fun startPreload() {
        preloadJob?.cancel()
        preloadJob = scope.launch {
            // Differisce il burst di preload (cataloghi + immagini + trailer
            // warm: il momento di massima allocazione dell'app) finché il
            // sistema è in zona di kill. Su una Stick sature da 1 GB partire
            // subito significa alimentare la spirale swap→morte per LMK.
            // Il retry ogni 10s costa zero; dopo 2 minuti si parte comunque
            // perché la home deve pur aprirsi.
            var waitedMs = 0L
            while (com.nuvio.tv.core.diagnostics.MemoryWatch.underPressure && waitedMs < 120_000L) {
                if (waitedMs == 0L) Log.i(TAG, "preload deferred: system under memory pressure")
                delay(10_000L)
                waitedMs += 10_000L
            }
            _ready.value = false
            val startedAt = android.os.SystemClock.elapsedRealtime()
            val fluidPerformance = fluidModeState.isEnabled()
            var complete = false
            if (fluidPerformance) {
                // Keep re-attempting until every phase has genuinely completed, so
                // the black loading screen waits exactly as long as the data needs;
                // the hard deadline guarantees Home after two minutes max.
                while (!complete) {
                    val elapsed = android.os.SystemClock.elapsedRealtime() - startedAt
                    val remaining = TOTAL_DEADLINE_MS - elapsed
                    if (remaining <= 0L) break
                    complete = try {
                        withTimeoutOrNull(remaining) { runPreloadPhases(PHASE_TIMEOUT_MS) } == true
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Preload attempt failed: ${e.message}")
                        false
                    }
                    if (!complete) {
                        val left = TOTAL_DEADLINE_MS - (android.os.SystemClock.elapsedRealtime() - startedAt)
                        if (left <= RETRY_DELAY_MS) break
                        delay(RETRY_DELAY_MS)
                    }
                }
            } else {
                // Classic: a single pass with the classic per-phase timeout, then
                // Home either way — the structure of the build before the speed-ups.
                complete = try {
                    runPreloadPhases(CLASSIC_PHASE_TIMEOUT_MS)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Preload interrupted: ${e.message}")
                    false
                }
            }
            _ready.value = true
            if (gentleProfile) {
                // Priorità alla riga "Ultimi uscite": le sue immagini (poster,
                // sfondi, miniature episodi) partono subito dopo la home,
                // prima ancora di quelle di catalogo.
                scope.launch {
                    delay(GENTLE_CW_IMAGE_WARM_DELAY_MS)
                    awaitUserIdle()
                    val cwUrls = runCatching {
                        cwEnrichmentCache.getNextUpSnapshot().mapNotNull { it.thumbnail ?: it.backdrop ?: it.poster } +
                            cwEnrichmentCache.getInProgressSnapshot().mapNotNull { it.episodeThumbnail ?: it.backdrop ?: it.poster }
                    }.getOrDefault(emptyList())
                    runCatching { homeImagePreloader.warmUrls(cwUrls, "continue-watching") }
                }
                // Modalità graduale: le immagini scheda (poster/sfondi/loghi)
                // scaricano su disco DOPO che la home è visibile, sfalsate —
                // non nel gate. Lo scroll le trova già in disco man mano.
                scope.launch {
                    delay(GENTLE_IMAGE_WARM_DELAY_MS)
                    awaitUserIdle()
                    runCatching { homeImagePreloader.warmImages() }
                }
            }
            // Solo se tutto è andato davvero a buon fine: il prossimo avvio è caldo.
            if (complete) {
                runCatching { startupWarmupPreferences.markFirstHomeReady() }
            }
            Log.i(
                TAG,
                "Home preload ready in ${android.os.SystemClock.elapsedRealtime() - startedAt}ms " +
                    "(fluid=$fluidPerformance complete=$complete) profile=${profileManager.activeProfileId.value}"
            )
        }
    }

    /** Runs every warm-up phase once; returns true only when all of them satisfied. */
    private suspend fun runPreloadPhases(phaseTimeoutMs: Long): Boolean {
        var complete = true

        // Kick warm-ups early (no-ops if already running).
        if (gentleProfile) {
            // Pacing: i tre fetch partono sfalsati invece che insieme, così il
            // picco di allocazione del parsing si spezza in piccoletti che il
            // GC di una Stick da 1 GB riesce a smaltire.
            catalogRepository.warmUp()
            delay(GENTLE_PHASE_PACE_MS)
            calendarRepository.warmUp()
            delay(GENTLE_PHASE_PACE_MS)
            traktTop10Repository.warmUp()
        } else {
            catalogRepository.warmUp()
            calendarRepository.warmUp()
            traktTop10Repository.warmUp()
        }

        suspend fun phase(block: suspend () -> Unit) {
            if (withTimeoutOrNull(phaseTimeoutMs) { block() } == null) complete = false
        }

        phase { profileManager.activeProfileReady.first { it } }
        phase { addonRepository.getInstalledAddons().first() }
        phase { layoutPreferenceDataStore.hasChosenLayout.first() }
        phase { experienceModeDataStore.mode.first() }
        phase { layoutPreferenceDataStore.homeCatalogOrderKeys.first() }
        if (gentleProfile) {
            // Solo abbastanza per una prima schermata utile: la prima riga di
            // catalogo basta per mostrare home e iniziare a navigare. Le righe
            // e le immagini successive arrivano graduale in background
            // (come Netflix: scheletro subito, riempimento poi).
            phase {
                while (catalogRepository.firstPageRows().isEmpty()) delay(500)
            }
        } else {
            // Held on purpose: Home's own row loaders are heavy on a stick, so the
            // loading screen stays up until every catalog answered its first page
            // (still capped by the phase timeout and the total deadline force-release).
            phase { catalogRepository.warmComplete.first { it } }
            // Immagini: scarica su disco poster/sfondi/loghi delle prime pagine, così
            // Home parte già pronta e lo scroll non scarica più nulla.
            phase { homeImagePreloader.warmImages() }
            // Trailer: best effort con budget proprio (non blocca la partenza).
            phase { withTimeoutOrNull(TRAILER_WARM_BUDGET_MS) { warmTrailers() } }
        }
        // Touch CW disk cache so first Home render hits warm FS state.
        phase {
            cwEnrichmentCache.getInProgressSnapshot()
            cwEnrichmentCache.getNextUpSnapshot()
        }

        return complete
    }

    /**
     * Aspetta che l'utente smetta di premere tasti (max
     * [GENTLE_WARM_IDLE_MAX_WAIT_MS]). Chiamata prima dei warm immagini: il
     * decode di decine di schede non deve mai competere con lo scroll attivo.
     */
    private suspend fun awaitUserIdle() {
        var waitedMs = 0L
        while (
            waitedMs < GENTLE_WARM_IDLE_MAX_WAIT_MS &&
            android.os.SystemClock.elapsedRealtime() -
            com.nuvio.tv.NuvioApplication.lastUserInteractionAtMs < 2_000L
        ) {
            delay(1_000L)
            waitedMs += 1_000L
        }
    }

    /** Risolve gli URL dei trailer dei primi titoli per farli partire subito in Home. */
    private suspend fun warmTrailers() {
        if (!AppFeaturePolicy.inAppTrailerPlaybackEnabled) return
        val items = catalogRepository.firstPageRows()
            .flatMap { it.items }
            .distinctBy { it.id }
            .take(TRAILER_WARM_COUNT)
        if (items.isEmpty()) return
        items.forEach { item ->
            val tmdbId = runCatching { tmdbService.ensureTmdbId(item.id, item.apiType) }.getOrNull()
            // Per-item budget: a single slow title must not cancel the lookups
            // still in flight, because a cancelled lookup used to be cached as
            // "no trailer" for the whole session.
            withTimeoutOrNull(TRAILER_WARM_ITEM_MS) {
                trailerService.getTrailerPlaybackSource(
                    title = item.name,
                    year = extractYear(item.releaseInfo),
                    tmdbId = tmdbId,
                    type = item.apiType
                )
            }
        }
        Log.i(TAG, "Warmed ${items.size} trailer lookups")
    }
}
