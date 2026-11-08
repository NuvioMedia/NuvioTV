package com.nuvio.tv.ui.screens.home

import android.content.Context
import android.util.Log
import android.os.SystemClock
import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.LocaleCache
import com.nuvio.tv.core.player.StreamAutoPlayPolicy
import com.nuvio.tv.core.recommendations.TvRecommendationManager
import com.nuvio.tv.core.tmdb.TmdbMetadataService
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.local.AuthSessionNoticeDataStore
import com.nuvio.tv.data.local.CollectionsDataStore
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.data.local.StartupAuthNotice
import com.nuvio.tv.data.local.MDBListSettingsDataStore
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.local.TvdbSettingsDataStore
import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.data.local.TrailerSettingsDataStore
import com.nuvio.tv.data.local.ContinueWatchingEnrichmentCache
import com.nuvio.tv.data.local.LatestReleaseArtworkDiskCache
import com.nuvio.tv.data.trailer.TrailerService
import com.nuvio.tv.data.translation.MetadataTextTranslator
import com.nuvio.tv.data.tvdb.TvdbMetadataService
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CalendarItem
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.Collection
import com.nuvio.tv.domain.model.ContinueWatchingSortMode
import com.nuvio.tv.domain.model.LibraryEntryInput
import com.nuvio.tv.domain.model.ListMembershipChanges
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.isPlaceholder
import com.nuvio.tv.data.repository.MDBListRepository
import com.nuvio.tv.data.repository.OmdbAwardsRepository
import com.nuvio.tv.data.repository.TraktTop10Repository
import com.nuvio.tv.domain.model.MDBListSettings
import com.nuvio.tv.domain.model.TmdbSettings
import com.nuvio.tv.domain.model.TvdbSettings
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.CalendarRepository
import com.nuvio.tv.domain.repository.CatalogRepository
import com.nuvio.tv.domain.repository.ExtraAddonRepository
import com.nuvio.tv.domain.repository.LibraryRepository
import com.nuvio.tv.domain.repository.MetaRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import com.nuvio.tv.core.util.installedExtraAddonsNow
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

@OptIn(kotlinx.coroutines.FlowPreview::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext internal val appContext: Context,
    internal val addonRepository: AddonRepository,
    internal val extraAddonRepository: ExtraAddonRepository,
    internal val catalogRepository: CatalogRepository,
    internal val watchProgressRepository: WatchProgressRepository,
    internal val libraryRepository: LibraryRepository,
    internal val metaRepository: MetaRepository,
    internal val metadataTextTranslator: MetadataTextTranslator,
    internal val omdbAwardsRepository: OmdbAwardsRepository,
    internal val calendarRepository: CalendarRepository,
    internal val traktTop10Repository: TraktTop10Repository,
    internal val collectionsDataStore: CollectionsDataStore,
    internal val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    internal val playerSettingsDataStore: PlayerSettingsDataStore,
    internal val tmdbSettingsDataStore: TmdbSettingsDataStore,
    internal val tvdbSettingsDataStore: TvdbSettingsDataStore,
    internal val mdbListSettingsDataStore: MDBListSettingsDataStore,
    internal val traktSettingsDataStore: TraktSettingsDataStore,
    internal val trailerSettingsDataStore: TrailerSettingsDataStore,
    internal val authSessionNoticeDataStore: AuthSessionNoticeDataStore,
    internal val tmdbService: TmdbService,
    internal val tmdbMetadataService: TmdbMetadataService,
    internal val tvdbMetadataService: TvdbMetadataService,
    internal val mdbListRepository: MDBListRepository,
    internal val trailerService: TrailerService,
    internal val watchedSeriesStateHolder: com.nuvio.tv.data.local.WatchedSeriesStateHolder,
    internal val profileManager: ProfileManager,
    internal val cwEnrichmentCache: ContinueWatchingEnrichmentCache,
    internal val tvRecommendationManager: com.nuvio.tv.core.recommendations.TvRecommendationManager,
) : ViewModel(), HomeRouteViewModelInternal {
    companion object {
        internal const val TAG = "HomeViewModel"
        internal const val STARTUP_GRACE_PERIOD_MS = 500L
        internal const val CONTINUE_WATCHING_ENRICHMENT_GRACE_PERIOD_MS = 400L
        private const val CONTINUE_WATCHING_WINDOW_MS = 30L * 24 * 60 * 60 * 1000
        private const val MAX_RECENT_PROGRESS_ITEMS = 300
        // Fallback locale per "Ultimi uscite" quando la cache disco è vuota:
        // abbastanza per una riga piena, senza aspettare rete o pipeline.
        private const val CW_LOCAL_FALLBACK_ITEMS = 20
        private const val MAX_NEXT_UP_LOOKUPS = 24
        private const val MAX_NEXT_UP_CONCURRENCY = 4
        private const val MAX_CATALOG_LOAD_CONCURRENCY = 3
        internal const val EXTERNAL_META_PREFETCH_FOCUS_DEBOUNCE_MS = 220L
        internal const val EXTERNAL_META_PREFETCH_ADJACENT_DEBOUNCE_MS = 120L
        private const val MAX_ENRICHMENT_CACHE_SIZE = 64
        private const val MAX_PREFETCH_CACHE_SIZE = 64
        private const val PREVIEW_TRANSLATION_MAX_ATTEMPTS = 3
        // Retry ravvicinato: i fallimenti transitori (modello ML Kit ancora in
        // download, rilevazione lingua in timeout) erano separati da 4 s, così
        // la descrizione restava inglese a lungo anche quando il secondo
        // tentativo sarebbe riuscito subito.
        private const val PREVIEW_TRANSLATION_RETRY_DELAY_MS = 1_500L

        private fun <K, V> createLruMap(maxSize: Int): MutableMap<K, V> {
            val lru = object : LinkedHashMap<K, V>(maxSize + 4, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean =
                    size > maxSize
            }
            return java.util.Collections.synchronizedMap(lru)
        }
    }

    internal val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    internal val _movieWatchedStatus = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val movieWatchedStatus: StateFlow<Map<String, Boolean>> = _movieWatchedStatus.asStateFlow()

    // Pending batch of watched status updates — debounced before emission.
    internal val _pendingWatchedBatch = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    /** True once the CW pipeline has completed its first emission (items or empty). */
    internal val _initialCwResolved = MutableStateFlow(false)
    val initialCwResolved: StateFlow<Boolean> = _initialCwResolved.asStateFlow()
    val effectiveAutoplayEnabled = playerSettingsDataStore.playerSettings
        .map(StreamAutoPlayPolicy::isEffectivelyEnabled)
        .distinctUntilChanged()
    internal val _fullCatalogRows = MutableStateFlow<List<CatalogRow>>(emptyList())
    val fullCatalogRows: StateFlow<List<CatalogRow>> = _fullCatalogRows.asStateFlow()

    private val _focusState = MutableStateFlow(HomeScreenFocusState())
    override val focusState: StateFlow<HomeScreenFocusState> = _focusState.asStateFlow()

    private val _gridFocusState = MutableStateFlow(HomeScreenFocusState())
    override val gridFocusState: StateFlow<HomeScreenFocusState> = _gridFocusState.asStateFlow()

    private val _scrollToTopTrigger = MutableStateFlow(0)
    override val scrollToTopTrigger: StateFlow<Int> = _scrollToTopTrigger.asStateFlow()

    internal val _currentLocaleTag = MutableStateFlow(LocaleCache.localeTag)

    fun notifyLocaleChanged() {
        val tag = LocaleCache.localeTag
        if (_currentLocaleTag.value != tag) {
            _currentLocaleTag.value = tag
            scheduleUpdateCatalogRows()
        }
    }

    fun requestScrollToTop() {
        clearFocusState()
        _gridFocusState.value = HomeScreenFocusState()
        _scrollToTopTrigger.value++
    }

    internal val _loadingCatalogs = MutableStateFlow<Set<String>>(emptySet())
    val loadingCatalogs: StateFlow<Set<String>> = _loadingCatalogs.asStateFlow()

    internal val _enrichingItemId = MutableStateFlow<String?>(null)
    override val enrichingItemId: StateFlow<String?> = _enrichingItemId.asStateFlow()
    internal fun setEnrichingItemId(id: String?) { _enrichingItemId.value = id }

    internal val _lastEnrichedPreview = MutableStateFlow<MetaPreview?>(null)
    override val lastEnrichedPreview: StateFlow<MetaPreview?> = _lastEnrichedPreview.asStateFlow()

    internal val _enrichedPreviews = MutableStateFlow<Map<String, MetaPreview>>(emptyMap())
    override val enrichedPreviews: StateFlow<Map<String, MetaPreview>> = _enrichedPreviews.asStateFlow()

    /** Last description already handed to the translator, per item. */
    private val previewTranslationProcessed: MutableMap<String, String> = createLruMap(MAX_ENRICHMENT_CACHE_SIZE)

    /**
     * Traduzioni arrivate PRIMA che l'arricchimento pubblicasse l'item (chiave:
     * id → descrizione originale → traduzione). Senza questo "pending" una
     * traduzione avviata al primo focus veniva buttata via: al termine non
     * esisteva ancora nessuna entry in _enrichedPreviews su cui applicarla, e
     * il ciclo successivo saltava per "già processata" — il testo restava
     * inglese.
     */
    private val pendingPreviewTranslations: MutableMap<String, Pair<String, String>> =
        createLruMap(MAX_ENRICHMENT_CACHE_SIZE)

    private val previewTranslationLock = Any()

    /**
     * Coda seriale per le traduzioni di sfondo dei preview: le pubblicazioni
     * di arricchimento arrivano a raffica (prefetch adiacenti, hero) e ML Kit
     * processa le stringhe in pratica una alla volta, così ogni richiesta
     * aggiunge ~1 s di attesa a TUTTE quelle successive — durante lo scroll la
     * traduzione dell'hero visibile finiva in coda dietro quelle di sfondo
     * ("le traduzioni sono lente a comparire"). Una sola coroutine di sfondo
     * alla volta tiene la coda lunga al massimo uno. La coda è ESPLICITA
     * (non una catena di join) così la corsia prioritaria del focus può
     * RUBARE la propria voce e tradurla subito.
     */
    private val backgroundTranslationQueue: ArrayDeque<Pair<String, String>> = ArrayDeque()
    private var backgroundTranslationWorker: Job? = null

    /**
     * Corsia prioritaria per il focus: solo la traduzione dell'item che
     * l'utente sta guardando conta, quindi ne resta comunque una sola — al
     * cambio di focus quella precedente viene annullata (il claim viene
     * rilasciato in invokeOnCompletion, così l'item annullato verrà ritentato).
     */
    private var focusPreviewTranslationJob: Job? = null

    internal fun addEnrichedPreview(id: String, preview: MetaPreview, priority: Boolean = false) {
        putEnrichedPreview(id, preview)
        schedulePreviewDescriptionTranslation(id, preview, priority)
    }

    private fun putEnrichedPreview(id: String, preview: MetaPreview) {
        // Se la traduzione di questa stessa descrizione era già pronta, applichila
        // subito: la UI vede italiano dal primo fotogramma.
        val stored = synchronized(previewTranslationLock) {
            val pending = pendingPreviewTranslations[id]
            when {
                pending != null && pending.first == preview.description ->
                    preview.copy(description = pending.second)
                else -> {
                    // "Flash inglese": se il traduttore ha già in cache (su
                    // disco) la traduzione di questa descrizione, pubblichiamola
                    // direttamente tradotta — l'originale non compare nemmeno
                    // per un frame. Altrimenti si pubblica l'originale e la
                    // coroutine di traduzione lo sostituisce appena pronta.
                    val cached = metadataTextTranslator.peekCachedTranslation(
                        currentTmdbSettings.language,
                        preview.description
                    )
                    if (cached != null && cached != preview.description) {
                        preview.copy(description = cached)
                    } else preview
                }
            }
        }
        _enrichedPreviews.update { current ->
            val updated = current + (id to stored)
            if (updated.size <= MAX_ENRICHMENT_CACHE_SIZE) updated
            else LinkedHashMap(updated).apply { while (size > MAX_ENRICHMENT_CACHE_SIZE) remove(keys.first()) }
        }
    }

    /**
     * Enrichment reads raw addon/TMDB text; the detail screen runs it through
     * [MetadataTextTranslator] but the home hero had no equivalent, leaving
     * English synopses on screen. Schedule one translation per distinct
     * description (async, so the raw text may flash briefly) and re-publish
     * only while the stored entry still holds the string we translated.
     */
    internal fun schedulePreviewDescriptionTranslation(
        id: String,
        preview: MetaPreview,
        priority: Boolean = false
    ) {
        val description = preview.description?.takeIf { it.isNotBlank() }
        if (description == null) {
            return
        }
        // Sotto la soglia di lunghezza (o con lingua non traducibile) il traduttore
        // risponde sempre "null": non serve nemmeno avviare il ciclo di tentativi.
        if (!com.nuvio.tv.data.translation.shouldTranslate(description, currentTmdbSettings.language)) {
            return
        }
        val job: Job? = synchronized(previewTranslationLock) {
            val claimed = previewTranslationProcessed[id] == description
            if (priority) {
                if (claimed) {
                    // Corsia prioritaria (focus): se l'item è ancora IN CODA di
                    // sfondo lo ruba e lo traduce subito. Prima il "return" per
                    // claim già presente lo lasciava dietro TUTTE le traduzioni
                    // di prefetch: al focus l'hero restava in inglese per
                    // diversi secondi ("le traduzioni arrivano in ritardo").
                    val stolen = backgroundTranslationQueue.removeAll {
                        it.first == id && it.second == description
                    }
                    // Non in coda → già in esecuzione o tradotta: niente da fare.
                    if (!stolen) return
                } else {
                    previewTranslationProcessed[id] = description
                }
                focusPreviewTranslationJob?.cancel()
                viewModelScope.launch { runPreviewTranslation(id, description) }
                    .also { focusPreviewTranslationJob = it }
            } else {
                if (claimed) return
                previewTranslationProcessed[id] = description
                backgroundTranslationQueue.addLast(id to description)
                if (backgroundTranslationWorker?.isActive != true) {
                    backgroundTranslationWorker = viewModelScope.launch {
                        while (true) {
                            val next = synchronized(previewTranslationLock) {
                                backgroundTranslationQueue.removeFirstOrNull()
                            } ?: break
                            runPreviewTranslation(next.first, next.second)
                        }
                    }
                }
                null
            }
        }
        if (priority) {
            // Annullata (o fallita) → rilascia il claim: senza questo l'item
            // non verrebbe mai ritentato al prossimo focus.
            job?.invokeOnCompletion { cause ->
                if (cause != null) {
                    synchronized(previewTranslationLock) {
                        if (previewTranslationProcessed[id] == description) {
                            previewTranslationProcessed.remove(id)
                        }
                        if (focusPreviewTranslationJob === job) focusPreviewTranslationJob = null
                    }
                }
            }
        }
    }

    /** Corpo della traduzione di un preview: tentativi, apply e pending. */
    private suspend fun runPreviewTranslation(id: String, description: String) {
        var translated: String? = null
        for (attempt in 0 until PREVIEW_TRANSLATION_MAX_ATTEMPTS) {
            if (attempt > 0) delay(PREVIEW_TRANSLATION_RETRY_DELAY_MS)
            val candidate = runCatching {
                metadataTextTranslator.translateDescription(description, currentTmdbSettings.language)
            }.getOrNull()
            if (!candidate.isNullOrBlank() && candidate != description) {
                translated = candidate
                break
            }
        }
        val result = translated
        if (result == null) {
            // The ML Kit models may still have been downloading — release the
            // claim so the next publish/focus for this item retries instead
            // of leaving the raw English text on screen forever.
            synchronized(previewTranslationLock) {
                if (previewTranslationProcessed[id] == description) {
                    previewTranslationProcessed.remove(id)
                }
            }
            return
        }
        var applied = false
        _enrichedPreviews.update { current ->
            val existing = current[id]
            val pend = synchronized(previewTranslationLock) { pendingPreviewTranslations[id] }
            Log.d(
                TAG,
                "apply id=$id existingDesc=${existing?.description?.take(30)} " +
                    "result=${result.take(30)} pending=${pend?.first?.take(30)} pendingOk=${pend != null}"
            )
            if (existing != null && existing.description == description) {
                applied = true
                current + (id to existing.copy(description = result))
            } else {
                current
            }
        }
        _lastEnrichedPreview.update { current ->
            if (current != null && current.id == id && current.description == description) {
                current.copy(description = result)
            } else {
                current
            }
        }
        if (!applied) {
            // L'arricchimento non ha ancora pubblicato l'item: tieni la
            // traduzione da parte, putEnrichedPreview la applica appena il
            // testo identico entra nella mappa.
            synchronized(previewTranslationLock) {
                pendingPreviewTranslations[id] = description to result
            }
        }
    }

    /** Items for which enrichment was attempted but produced no enriched data. */
    internal val _failedEnrichmentIds = MutableStateFlow<Set<String>>(emptySet())
    override val failedEnrichmentIds: StateFlow<Set<String>> = _failedEnrichmentIds.asStateFlow()

    internal fun markEnrichmentFailed(id: String) {
        _failedEnrichmentIds.update { current ->
            if (id in current) return@update current
            val updated = LinkedHashSet(current).apply { add(id) }
            while (updated.size > MAX_ENRICHMENT_CACHE_SIZE) {
                updated.remove(updated.first())
            }
            updated
        }
    }

    /** Enrichment succeeded after a previous failure, so the item is no longer a failed one. */
    internal fun clearEnrichmentFailure(id: String) {
        _failedEnrichmentIds.update { current -> if (id in current) current - id else current }
    }

    /** Drops every failure marker, for the resets that clear the caches beside this one. */
    internal fun clearEnrichmentFailures() {
        _failedEnrichmentIds.value = emptySet()
    }

    internal val catalogStateLock = Any()
    internal val catalogsMap = linkedMapOf<String, CatalogRow>()
    internal val catalogItemKeyIndex = mutableMapOf<String, MutableSet<String>>()
    internal val catalogOrder = mutableListOf<String>()
    internal var addonsCache: List<Addon> = emptyList()
    internal var collectionsCache: List<Collection> = emptyList()
    internal var homeCatalogOrderKeys: List<String> = emptyList()
    internal var disabledHomeCatalogKeys: Set<String> = emptySet()
    internal var followAddonsOrderEnabled: Boolean = false
    internal var customCatalogTitles: Map<String, String> = emptyMap()
    internal var currentHeroCatalogKeys: List<String> = emptyList()
    internal var catalogUpdateJob: Job? = null
    internal var hasRenderedFirstCatalog = false
    internal val catalogLoadSemaphore = Semaphore(MAX_CATALOG_LOAD_CONCURRENCY)
    internal var pendingCatalogLoads = 0
    internal val activeCatalogLoadJobs = mutableSetOf<Job>()
    internal var activeCatalogLoadSignature: String? = null
    internal var catalogLoadGeneration: Long = 0L
    internal var catalogsLoadInProgress: Boolean = false
    internal data class TruncatedRowCacheEntry(
        val sourceRow: CatalogRow,
        val truncatedRow: CatalogRow
    )
    internal val truncatedRowCache = mutableMapOf<String, TruncatedRowCacheEntry>()
    internal val trailerPreviewLoadingIds = mutableSetOf<String>()
    internal val trailerPreviewNegativeCache = mutableSetOf<String>()
    internal val trailerPreviewUrlsState = mutableStateMapOf<String, String>()
    internal val trailerPreviewAudioUrlsState = mutableStateMapOf<String, String>()
    internal var activeTrailerPreviewItemId: String? = null
    internal var trailerPreviewRequestVersion: Long = 0L
    internal var trailerPreviewJob: Job? = null
    internal var currentTmdbSettings: TmdbSettings = TmdbSettings()
    internal var currentTvdbSettings: TvdbSettings = TvdbSettings()
    internal var currentMdbListSettings: MDBListSettings = MDBListSettings()
    internal var heroEnrichmentJob: Job? = null
    internal var lastHeroEnrichmentSignature: String? = null
    internal var lastHeroEnrichedItems: List<MetaPreview> = emptyList()
    internal var heroItemOrder: List<String> = emptyList()
    internal val modernCarouselRowBuildCache = ModernCarouselRowBuildCache()
    internal val prefetchedExternalMetaIds: MutableSet<String> = Collections.newSetFromMap(createLruMap(MAX_PREFETCH_CACHE_SIZE))
    internal val backgroundMetaPrefetchedIds: MutableSet<String> = Collections.newSetFromMap(createLruMap(MAX_PREFETCH_CACHE_SIZE))
    internal val externalMetaPrefetchInFlightIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
    internal var externalMetaPrefetchJob: Job? = null
    internal var pendingExternalMetaPrefetchItemId: String? = null
    internal val prefetchedTmdbIds: MutableSet<String> = Collections.newSetFromMap(createLruMap(MAX_PREFETCH_CACHE_SIZE))
    internal val prefetchedTvdbIds: MutableSet<String> = Collections.newSetFromMap(createLruMap(MAX_PREFETCH_CACHE_SIZE))
    /** Items an enrichment merge was applied for. */
    internal val enrichmentMergedIds: MutableSet<String> = Collections.newSetFromMap(createLruMap(MAX_PREFETCH_CACHE_SIZE))
    internal val cwMetaCache = Collections.synchronizedMap(mutableMapOf<String, CwMetaSummary?>())
    internal val cwMetaNegativeCacheTimestamps = ConcurrentHashMap<String, Long>()
    /** Ultra-light cache for badge evaluation: contentId → set of aired (season, episode) pairs. */
    internal val cwBadgeEpisodeCache = Collections.synchronizedMap(mutableMapOf<String, Set<Pair<Int, Int>>?>())
    /** Per-series earliest upcoming season release date (epochMs) for smart TTL scheduling. */
    internal val cwBadgeNextSeasonMs = ConcurrentHashMap<String, Long>()
    /** Snapshot of watchedShowEpisodes keys from the last badge evaluation cycle. */
    @Volatile
    internal var cwLastBadgeEpisodeKeys: Set<String> = emptySet()
    /** Cached show ID siblings from the last badge evaluation cycle (for anime ID expansion). */
    @Volatile
    internal var cwLastShowIdSiblings: Map<String, Set<String>> = emptyMap()
    internal val cwTmdbIdCache = Collections.synchronizedMap(mutableMapOf<String, String?>())
    internal val cwNextUpResolutionCache = Collections.synchronizedMap(mutableMapOf<String, NextUpResolution?>())
    internal val cwNextUpNegativeCacheTimestamps = ConcurrentHashMap<String, Long>()
    internal val discoveredOlderNextUpItems = Collections.synchronizedList(mutableListOf<ContinueWatchingItem.NextUp>())
    internal val cwLastProcessedNextUpContentIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
    internal val cwEnrichedNextUpOverlay = ConcurrentHashMap<String, NextUpInfo>()
    /** In-memory cache of enriched InProgress items per contentId+episode key. */
    internal val cwEnrichedInProgressOverlay = ConcurrentHashMap<String, ContinueWatchingItem.InProgress>()
    /** Bumped to force the CW pipeline to re-run (e.g. after cache clear). */
    internal val cwPipelineRefreshTrigger = kotlinx.coroutines.flow.MutableStateFlow(0)
    /** Tracks the active CW pipeline coroutine so it can be cancelled on profile switch. */
    internal var cwPipelineJob: Job? = null
    internal val fullyWatchedSeriesIds get() = watchedSeriesStateHolder
    internal var tmdbEnrichFocusJob: Job? = null
    internal var pendingTmdbEnrichItemId: String? = null
    /** Dedup + job state for fetching MDBList ratings for the focused home hero. */
    internal val mdbListRatingFetchedIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
    internal var mdbListRatingFocusJob: Job? = null
    internal var pendingMdbListRatingItemId: String? = null
    /** Item that was focused during startup grace period — will be enriched once grace ends. */
    internal var deferredEnrichItem: MetaPreview? = null
    internal var adjacentItemPrefetchJob: Job? = null
    internal var pendingAdjacentPrefetchItemId: String? = null
    internal val movieWatchedObserverJobs = mutableMapOf<String, Job>()
    internal var movieWatchedBatchJob: Job? = null
    internal var lastMovieWatchedItemKeys: Set<String> = emptySet()
    internal var seriesWatchedObserverJob: Job? = null
    internal var libraryTabsObserverJob: Job? = null
    internal var activePosterListPickerInput: LibraryEntryInput? = null
    internal var pendingPosterListPickerChanges: ListMembershipChanges? = null
    @Volatile
    internal var externalMetaPrefetchEnabled: Boolean = false
    @Volatile
    internal var continueWatchingSortMode: ContinueWatchingSortMode = ContinueWatchingSortMode.DEFAULT
    internal val startupStartedAtMs: Long = SystemClock.elapsedRealtime()
    @Volatile
    internal var startupGracePeriodActive: Boolean = true
    internal var startupAuthNoticeJob: Job? = null

    // Lazy catalog loading
    internal val eagerCatalogLoadCount: Int = 4
    internal val lazyLoadRequestedKeys: MutableSet<String> = ConcurrentHashMap.newKeySet()
    internal val pendingLazyCatalogs = linkedMapOf<String, Pair<Addon, CatalogDescriptor>>()
    /** All placeholder descriptors for homeRow construction. */
    internal data class PlaceholderDescriptor(
        val catalogKey: String,
        val addonId: String,
        val addonName: String,
        val addonBaseUrl: String,
        val catalogId: String,
        val catalogName: String,
        val apiType: String,
        val displayTitle: String
    )
    internal val placeholderDescriptors = mutableListOf<PlaceholderDescriptor>()
    override val trailerPreviewUrls: Map<String, String>
        get() = trailerPreviewUrlsState
    override val trailerPreviewAudioUrls: Map<String, String>
        get() = trailerPreviewAudioUrlsState

    init {
        // Accumulates individual watched status changes and flushes them as a single
        // update after 150ms of inactivity, preventing N separate recompositions.
        viewModelScope.launch {
            _pendingWatchedBatch
                .debounce(150L)
                .collect { batch ->
                    if (batch.isNotEmpty()) {
                        val snapshot = _pendingWatchedBatch.value
                        _pendingWatchedBatch.value = emptyMap()
                        if (snapshot.isNotEmpty()) {
                            _movieWatchedStatus.update { current -> current + snapshot }
                        }
                    }
                }
        }

        observeStartupAuthNotice()
        viewModelScope.launch {
            profileManager.activeProfileReady.first { it }
            observeLayoutPreferences()
            observeModernHomePresentation()
            loadContinueWatching()
            observeLatestReleases()
            observeTop10()
            watchedSeriesStateHolder.loadFromDisk()
            observeExternalMetaPrefetchPreference()
            observeContinueWatchingSortMode()
            loadHomeCatalogOrderPreference()
            loadFollowAddonsOrder()
            loadDisabledHomeCatalogPreference()
            loadCustomCatalogTitles()
            observeLibraryState()
            observeTmdbSettings()
            observeTvdbSettings()
            observeMdbListSettings()
            observeBlurUnwatchedEpisodes()
            observeProgressSourceChanges()
            observeCollections()
            observeInstalledAddons()

            viewModelScope.launch {
                combine(
                    _uiState.map { it.continueWatchingItems + it.upcomingItems }.distinctUntilChanged(),
                    TvRecommendationManager.isPlaybackActive
                ) { items, isPlaying ->
                    Pair(items, isPlaying)
                }.collect { (items, isPlaying) ->
                    if (!isPlaying) {
                        runCatching { tvRecommendationManager.updateWatchNextFromCwItems(items) }
                    }
                }
            }

            // Clear CW state when profile changes so items don't leak between profiles.
            var previousProfileId = profileManager.activeProfileId.value
            profileManager.activeProfileId.collect { newId ->
                if (newId != previousProfileId) {
                    previousProfileId = newId
                    // Cancel old pipeline — prevents racing writes from stale coroutines.
                    cwPipelineJob?.cancel()
                    cwPipelineJob = null
                    // Clear all in-memory CW caches so data from the previous
                    // profile doesn't leak into the new one.
                    cwMetaCache.clear()
                    cwMetaNegativeCacheTimestamps.clear()
                    cwBadgeEpisodeCache.clear()
                    cwBadgeNextSeasonMs.clear()
                    cwTmdbIdCache.clear()
                    cwNextUpResolutionCache.clear()
                    cwNextUpNegativeCacheTimestamps.clear()
                    discoveredOlderNextUpItems.clear()
                    cwLastProcessedNextUpContentIds.clear()
                    cwEnrichedNextUpOverlay.clear()
                    cwEnrichedInProgressOverlay.clear()
                    cwLastBadgeEpisodeKeys = emptySet()
                    cwLastShowIdSiblings = emptyMap()
                    _uiState.update {
                        it.copy(layoutPreferencesReady = false, continueWatchingItems = emptyList())
                    }
                    clearFocusState()
                    _gridFocusState.value = HomeScreenFocusState()
                    // Reset so the new profile's pipeline signals first completion correctly.
                    _initialCwResolved.value = false
                    loadContinueWatching()
                    // Clear watched badges so they don't leak between profiles.
                    watchedSeriesStateHolder.clearInMemory()
                    watchedSeriesStateHolder.loadFromDisk(profileId = newId)
                    _movieWatchedStatus.value = emptyMap()
                    _pendingWatchedBatch.value = emptyMap()
                    _uiState.update { it.copy(movieWatchedStatus = emptyMap()) }
                }
            }
        }
        viewModelScope.launch {
            delay(STARTUP_GRACE_PERIOD_MS)
            startupGracePeriodActive = false
            // Trigger enrichment for the initial focused item once grace ends.
            deferredEnrichItem?.let { item ->
                deferredEnrichItem = null
                onItemFocusPipeline(item)
            }
        }

        // Observe manual cache clear from Advanced settings.
        viewModelScope.launch {
            var lastSeen = cwEnrichmentCache.cacheCleared.value
            cwEnrichmentCache.cacheCleared.collect { version ->
                if (version != lastSeen) {
                    lastSeen = version
                    clearAllCwInMemoryCaches()
                }
            }
        }
    }

    private fun clearAllCwInMemoryCaches() {
        cwMetaCache.clear()
        cwMetaNegativeCacheTimestamps.clear()
        cwBadgeEpisodeCache.clear()
        cwBadgeNextSeasonMs.clear()
        cwTmdbIdCache.clear()
        cwNextUpResolutionCache.clear()
        cwNextUpNegativeCacheTimestamps.clear()
        discoveredOlderNextUpItems.clear()
        cwLastProcessedNextUpContentIds.clear()
        cwEnrichedNextUpOverlay.clear()
        cwEnrichedInProgressOverlay.clear()
        cwLastBadgeEpisodeKeys = emptySet()
        cwLastShowIdSiblings = emptyMap()
        watchedSeriesStateHolder.clearValidationState()
        _uiState.update { it.copy(continueWatchingItems = emptyList(), upcomingItems = emptyList()) }
        // Bump trigger so the pipeline's collectLatest restarts with fresh state.
        cwPipelineRefreshTrigger.value++
    }

    internal fun remainingStartupGraceMs(nowMs: Long = SystemClock.elapsedRealtime()): Long {
        if (!startupGracePeriodActive) return 0L
        return (STARTUP_GRACE_PERIOD_MS - (nowMs - startupStartedAtMs)).coerceAtLeast(0L)
    }

    internal fun remainingContinueWatchingEnrichmentGraceMs(
        nowMs: Long = SystemClock.elapsedRealtime()
    ): Long {
        return (CONTINUE_WATCHING_ENRICHMENT_GRACE_PERIOD_MS - (nowMs - startupStartedAtMs))
            .coerceAtLeast(0L)
    }

    private fun observeLayoutPreferences() = observeLayoutPreferencesPipeline()

    private fun observeModernHomePresentation() = observeModernHomePresentationPipeline()

    private fun observeExternalMetaPrefetchPreference() = observeExternalMetaPrefetchPreferencePipeline()

    private fun observeContinueWatchingSortMode() {
        viewModelScope.launch {
            var initial = true
            layoutPreferenceDataStore.continueWatchingSortMode
                .distinctUntilChanged()
                .collect { mode ->
                    continueWatchingSortMode = mode
                    if (initial) {
                        initial = false
                        return@collect
                    }
                    // Clear caches so the new sort is applied immediately on next pipeline run
                    clearAllCwInMemoryCaches()
                }
        }
    }

    private fun observeBlurUnwatchedEpisodes() {
        viewModelScope.launch {
            layoutPreferenceDataStore.blurContinueWatchingNextUp
                .distinctUntilChanged()
                .collect { enabled ->
                    _uiState.update { it.copy(blurUnwatchedEpisodes = enabled) }
                }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.useEpisodeThumbnailsInCw
                .distinctUntilChanged()
                .collect { enabled ->
                    _uiState.update { it.copy(useEpisodeThumbnailsInCw = enabled) }
                }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.continueWatchingCardStyle
                .distinctUntilChanged()
                .collect { style ->
                    _uiState.update { it.copy(continueWatchingCardStyle = style) }
                }
        }
        // When "next up from furthest episode" changes, clear CW caches and retrigger pipeline
        viewModelScope.launch {
            var initial = true
            layoutPreferenceDataStore.nextUpFromFurthestEpisode
                .distinctUntilChanged()
                .collect {
                    if (initial) {
                        initial = false
                        return@collect
                    }
                    clearAllCwInMemoryCaches()
                }
        }
        // Episode artwork is chosen while enriching, so cached items must be rebuilt when the setting changes.
        viewModelScope.launch {
            var initial = true
            layoutPreferenceDataStore.useEpisodeThumbnailsInCw
                .distinctUntilChanged()
                .collect {
                    if (initial) {
                        initial = false
                        return@collect
                    }
                    clearAllCwInMemoryCaches()
                }
        }
    }

    override fun requestTrailerPreview(item: MetaPreview) = requestTrailerPreviewPipeline(item)

    override fun requestTrailerPreview(
        itemId: String,
        title: String,
        releaseInfo: String?,
        apiType: String
    ) = requestTrailerPreviewPipeline(
        itemId = itemId,
        title = title,
        releaseInfo = releaseInfo,
        apiType = apiType
    )

    override fun onItemFocus(item: MetaPreview) = onItemFocusPipeline(item)

    override fun preloadAdjacentItem(item: MetaPreview) = preloadAdjacentItemPipeline(item)

    private fun loadHomeCatalogOrderPreference() = loadHomeCatalogOrderPreferencePipeline()

    private fun loadFollowAddonsOrder() = loadFollowAddonsOrderPipeline()

    private fun loadDisabledHomeCatalogPreference() = loadDisabledHomeCatalogPreferencePipeline()

    private fun loadCustomCatalogTitles() = loadCustomCatalogTitlesPipeline()

    private fun observeTmdbSettings() = observeTmdbSettingsPipeline()
    private fun observeTvdbSettings() = observeTvdbSettingsPipeline()

    private fun observeMdbListSettings() {
        viewModelScope.launch {
            mdbListSettingsDataStore.settings
                .distinctUntilChanged()
                .collectLatest { settings ->
                    currentMdbListSettings = settings
                }
        }
    }

    /**
     * When the watch-progress source changes (e.g. Trakt login/logout, or
     * switching between Trakt and Nuvio Sync), clear the CW disk cache and
     * in-memory state so items from the old source don't leak into the new one.
     */
    private fun observeProgressSourceChanges() {
        viewModelScope.launch {
            var previousSource: com.nuvio.tv.data.local.WatchProgressSource? = null
            traktSettingsDataStore.watchProgressSource
                .distinctUntilChanged()
                .collect { source ->
                    if (previousSource != null && previousSource != source) {
                        // Source changed — clear CW caches to prevent mixing.
                        cwMetaCache.clear()
                        cwEnrichedNextUpOverlay.clear()
                        cwEnrichedInProgressOverlay.clear()
                        discoveredOlderNextUpItems.clear()
                        cwLastProcessedNextUpContentIds.clear()
                        // Clear disk cache for current profile.
                        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            runCatching { cwEnrichmentCache.saveNextUpSnapshot(emptyList(), force = true) }
                            runCatching { cwEnrichmentCache.saveInProgressSnapshot(emptyList(), force = true) }
                        }
                        // Reload CW from fresh source.
                        loadContinueWatching()
                    }
                    previousSource = source
                }
        }
    }

    private fun observeStartupAuthNotice() {
        viewModelScope.launch {
            authSessionNoticeDataStore.pendingNotice.collect { notice ->
                if (notice == null) return@collect
                _uiState.update { state ->
                    if (state.startupAuthNotice == notice) state else state.copy(startupAuthNotice = notice)
                }
                startupAuthNoticeJob?.cancel()
                startupAuthNoticeJob = viewModelScope.launch {
                    delay(3200)
                    clearStartupAuthNotice(notice)
                }
                authSessionNoticeDataStore.consumeNotice(notice)
            }
        }
    }

    private fun clearStartupAuthNotice(notice: StartupAuthNotice) {
        _uiState.update { state ->
            if (state.startupAuthNotice == notice) {
                state.copy(startupAuthNotice = null)
            } else {
                state
            }
        }
    }

    override fun onEvent(event: HomeEvent) {
        when (event) {
            is HomeEvent.OnItemClick -> navigateToDetail(event.itemId, event.itemType)
            is HomeEvent.OnLoadMoreCatalog -> loadMoreCatalogItems(event.catalogId, event.addonId, event.type)
            is HomeEvent.OnRemoveContinueWatching -> removeContinueWatching(
                contentId = event.contentId,
                season = event.season,
                episode = event.episode,
                isNextUp = event.isNextUp
            )
            HomeEvent.OnRetry -> viewModelScope.launch { loadAllCatalogs(addonsCache, forceReload = true) }
        }
    }

    private fun loadContinueWatching() {
        // Immediately restore last known CW from disk cache for instant display.
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            // One-shot pool check so the bootstrap never renders cached items
            // that belong to the Extra tab (older builds wrote them here).
            // Anime items are kept: their details open with the Anime tab settings.
            val extraBaseUrls = extraAddonRepository.installedExtraAddonsNow()
                .map { normalizeExtraAddonBaseUrl(it.baseUrl) }.filter { it.isNotEmpty() }.toSet()
            val cachedInProgress = runCatching { cwEnrichmentCache.getInProgressSnapshot() }.getOrDefault(emptyList())
                .filterNot { isExtraPoolAddon(it.addonBaseUrl, extraBaseUrls) }
            val cachedNextUp = runCatching { cwEnrichmentCache.getNextUpSnapshot() }.getOrDefault(emptyList())
                .filterNot { isExtraPoolAddon(it.addonBaseUrl, extraBaseUrls) }
            if (cachedInProgress.isEmpty() && cachedNextUp.isEmpty()) {
                // Cache disco vuota (es. sessioni interrotte da kill): costruisci
                // la riga dai progressi di visione LOCALI così "Ultimi uscite"
                // compare insieme alla home invece di aspettare il pipeline
                // completo (che su una Stick satura può impiegare decine di
                // secondi per via dell'interrogaggio addon). Stile Netflix:
                // la riga è first-class, l'enrichment è solo un miglioramento
                // progressivo che la sostituisce appena pronto.
                val localProgress = runCatching { watchProgressRepository.allProgress.first() }
                    .getOrDefault(emptyList())
                val localItems = localProgress
                    .filter { !watchProgressRepository.isDroppedShow(it.contentId) }
                    .sortedByDescending { it.lastWatched }
                    .take(CW_LOCAL_FALLBACK_ITEMS)
                    .map { progress -> ContinueWatchingItem.InProgress(progress = progress) }
                if (localItems.isNotEmpty()) {
                    _uiState.update { it.copy(continueWatchingItems = localItems) }
                    _initialCwResolved.value = true
                }
                return@launch
            }
            val dismissedNextUp = traktSettingsDataStore.dismissedNextUpKeys.first()
            // Render cached items immediately — don't wait for Trakt/allProgress.
            // The pipeline will replace these with live data once it completes.
            val inProgressItems = cachedInProgress
                .filter { !watchProgressRepository.isDroppedShow(it.contentId) }
                .map { cached ->
                    ContinueWatchingItem.InProgress(
                        progress = com.nuvio.tv.domain.model.WatchProgress(
                            contentId = cached.contentId,
                            contentType = cached.contentType,
                            name = cached.name,
                            poster = cached.poster,
                            backdrop = cached.backdrop,
                            logo = cached.logo,
                            videoId = cached.videoId,
                            season = cached.season,
                            episode = cached.episode,
                            episodeTitle = cached.episodeTitle,
                            position = cached.position,
                            duration = cached.duration,
                            lastWatched = cached.lastWatched,
                            progressPercent = cached.progressPercent
                        ),
                        episodeThumbnail = cached.episodeThumbnail,
                        episodeDescription = cached.episodeDescription,
                        episodeImdbRating = cached.episodeImdbRating,
                        genres = cached.genres,
                        releaseInfo = cached.releaseInfo
                    )
                }
            val nextUpItems = cachedNextUp
                .filter { !watchProgressRepository.isDroppedShow(it.contentId) }
                .filter { nextUpDismissKey(it.contentId, it.seedSeason, it.seedEpisode) !in dismissedNextUp }
                .map { cached ->
                ContinueWatchingItem.NextUp(
                    info = NextUpInfo(
                        contentId = cached.contentId,
                        contentType = cached.contentType,
                        name = cached.name,
                        poster = cached.poster,
                        backdrop = cached.backdrop,
                        logo = cached.logo,
                        videoId = cached.videoId,
                        season = cached.season,
                        episode = cached.episode,
                        episodeTitle = cached.episodeTitle,
                        episodeDescription = cached.episodeDescription,
                        thumbnail = cached.thumbnail,
                        released = cached.released,
                        hasAired = cached.hasAired,
                        airDateLabel = cached.airDateLabel,
                        lastWatched = cached.lastWatched,
                        imdbRating = cached.imdbRating,
                        genres = cached.genres,
                        releaseInfo = cached.releaseInfo,
                        sortTimestamp = cached.sortTimestamp,
                        releaseTimestamp = cached.releaseTimestamp,
                        isReleaseAlert = cached.isReleaseAlert,
                        isNewSeasonRelease = cached.isNewSeasonRelease,
                        seedSeason = cached.seedSeason,
                        seedEpisode = cached.seedEpisode
                    )
                )
            }
            val sortMode = layoutPreferenceDataStore.continueWatchingSortMode.first()
            val items = mergeContinueWatchingItems(
                inProgressItems = inProgressItems,
                nextUpItems = nextUpItems,
                mode = sortMode
            )
            if (items.isNotEmpty()) {
                val (mainItems, upcomingOnly) = splitUpcomingItems(items, sortMode)
                _uiState.update { it.copy(continueWatchingItems = mainItems, upcomingItems = upcomingOnly) }
                _initialCwResolved.value = true
            }
        }
        loadContinueWatchingPipeline()
    }

    private fun removeContinueWatching(
        contentId: String,
        season: Int? = null,
        episode: Int? = null,
        isNextUp: Boolean = false
    ) = removeContinueWatchingPipeline(
        contentId = contentId,
        season = season,
        episode = episode,
        isNextUp = isNextUp
    )

    private fun observeCollections() = observeCollectionsPipeline()

    private fun observeInstalledAddons() = observeInstalledAddonsPipeline()

    private suspend fun loadAllCatalogs(addons: List<Addon>, forceReload: Boolean = false) =
        loadAllCatalogsPipeline(addons, forceReload)

    private fun loadCatalog(addon: Addon, catalog: CatalogDescriptor, generation: Long) =
        loadCatalogPipeline(addon, catalog, generation)

    private fun loadMoreCatalogItems(catalogId: String, addonId: String, type: String) =
        loadMoreCatalogItemsPipeline(catalogId, addonId, type)

    internal fun scheduleUpdateCatalogRows() {
        catalogUpdateJob?.cancel()
        catalogUpdateJob = viewModelScope.launch {
            val debounceMs = when {
                // First render: use a moderate debounce so near-simultaneous
                // catalog arrivals are batched into a single heavy update pass.
                !hasRenderedFirstCatalog && hasAnyCatalogRows() -> {
                    hasRenderedFirstCatalog = true
                    150L
                }
                // During bulk loading, batch aggressively — placeholders are
                // already visible so the user won't notice the delay.
                pendingCatalogLoads > 8 -> 300L
                pendingCatalogLoads > 3 -> 250L
                pendingCatalogLoads > 0 -> 200L
                else -> 80L
            }
            delay(debounceMs)
            updateCatalogRows()
        }
    }

    /**
     * Called from the UI when a placeholder catalog row becomes visible.
     */
    override fun requestLazyCatalogLoad(catalogKey: String) {
        if (catalogKey in lazyLoadRequestedKeys) {
            return
        }
        val pair = synchronized(catalogStateLock) {
            pendingLazyCatalogs.remove(catalogKey)
        }
        if (pair == null) {
            return
        }
        if (!lazyLoadRequestedKeys.add(catalogKey)) {
            return
        }
        val (addon, catalog) = pair
        val generation = catalogLoadGeneration
        pendingCatalogLoads = (pendingCatalogLoads + 1)
        loadCatalogPipeline(addon, catalog, generation)
    }

    /**
     * Load all pending lazy catalogs at once. Used when switching to GRID layout
     * which needs all catalogs available upfront.
     */
    internal fun loadAllPendingLazyCatalogs() {
        val pending = synchronized(catalogStateLock) {
            val copy = pendingLazyCatalogs.toMap()
            pendingLazyCatalogs.clear()
            copy
        }
        if (pending.isEmpty()) return
        val generation = catalogLoadGeneration
        pending.forEach { (key, pair) ->
            if (lazyLoadRequestedKeys.add(key)) {
                val (addon, catalog) = pair
                pendingCatalogLoads = (pendingCatalogLoads + 1)
                loadCatalogPipeline(addon, catalog, generation)
            }
        }
    }

    private suspend fun updateCatalogRows() = updateCatalogRowsPipeline()

    // Titoli di "Ultime Uscite" già arricchiti (o già provati): così ogni
    // titolo viene interrogato su TMDB al massimo una volta per sessione.
    private val latestReleaseArtworkIds = ConcurrentHashMap.newKeySet<String>()

    // Artwork persistito su disco: alla prima emissione del calendario la
    // riga dipinge subito con i logo/ poster già noti, senza attendere la
    // rete (decine di secondi su una Fire TV Stick con cache fredda).
    private val latestReleaseArtworkCache = LatestReleaseArtworkDiskCache(
        File(appContext.filesDir, "latest_release_artwork.json")
    )

    private fun observeLatestReleases() {
        viewModelScope.launch {
            calendarRepository.getMonthReleaseItems()
                .retryWhen { _, attempt ->
                    if (attempt >= 5L) {
                        false
                    } else {
                        delay(10_000L * (attempt + 1))
                        true
                    }
                }
                .catch { e ->
                    Log.w(TAG, "latest releases failed: ${e.message}")
                }
                .collect { items ->
                    // Applica subito l'artwork salvato su disco: la riga
                    // diventa visibile con la prima emissione del calendario.
                    val withCachedArtwork = applyCachedLatestReleaseArtwork(items)
                    // Primo publish immediato: gli item che hanno già un logo
                    // (sessione precedente o Trakt) appaiono subito.
                    publishLatestReleaseItems(withCachedArtwork)
                    // Poi la riga si riempie in modo incrementale: ogni titolo
                    // arricchito viene pubblicato appena pronto, senza aspettare
                    // il completamento di tutti (l'awaitAll precedente teneva la
                    // riga vuota per l'intera durata dell'arricchimento).
                    fillLatestReleaseArtwork(withCachedArtwork)
                }
        }
    }

    private fun applyCachedLatestReleaseArtwork(items: List<CalendarItem>): List<CalendarItem> {
        if (items.isEmpty()) return items
        return items.map { item ->
            val cached = latestReleaseArtworkCache.get(item.meta.id) ?: return@map item
            var meta = item.meta
            if (meta.logo.isNullOrBlank() && !cached.logo.isNullOrBlank()) {
                meta = meta.copy(logo = cached.logo)
            }
            if (meta.poster.isNullOrBlank() && !cached.poster.isNullOrBlank()) {
                meta = meta.copy(poster = cached.poster)
            }
            if (meta.background.isNullOrBlank() && !cached.background.isNullOrBlank()) {
                meta = meta.copy(background = cached.background)
            }
            if (meta.description.isNullOrBlank() && !cached.description.isNullOrBlank()) {
                meta = meta.copy(description = cached.description)
            }
            if (meta == item.meta) item else item.copy(meta = meta)
        }
    }

    private fun publishLatestReleaseItems(items: List<CalendarItem>) {
        // Rende visibili i titoli con un artwork utilizzabile: logo
        // stilizzato oppure poster (le card della riga mostrano il poster,
        // il logo serve solo alla hero che finché manca mostra il titolo in
        // testo). Il filtro gira dopo l'arricchimento TMDB, quindi i titoli
        // che acquistano artwork in seguito vengono mostrati senza popup.
        val ready = items.filter { item ->
            val logo = item.meta.logo
            val poster = item.meta.poster
            (!logo.isNullOrBlank() && !logo.isPlaceholder()) ||
                (!poster.isNullOrBlank() && !poster.isPlaceholder())
        }
        if (_uiState.value.latestReleaseItems == ready) return
        _uiState.update { it.copy(latestReleaseItems = ready) }
        scheduleUpdateCatalogRows()
    }

    /**
     * Riempe artwork/descrizione dei titoli di "Ultime Uscite": TMDB discover
     * restituisce poster e sfondo ma non il logo, per cui la hero mostrava il
     * titolo in testo al posto del logo usato dalle altre righe. Ogni titolo
     * mancante viene arricchito una volta sola con la variante leggera
     * fetchArtworkEnrichment (2 chiamate TMDB invece di 6) e la riga viene
     * **ripubblicata a ogni arricchimento completato**, così i primi titoli
     * appaiono in pochi secondi invece di aspettare l'awaitAll di tutti.
     * L'artwork risultante viene salvato su disco per i riavvii successivi.
     */
    private suspend fun fillLatestReleaseArtwork(items: List<CalendarItem>) {
        if (items.isEmpty()) return
        val settings = currentTmdbSettings
        if (!settings.enabled) return
        val pending = items.filter { item ->
            item.meta.id !in latestReleaseArtworkIds &&
                (item.meta.logo.isNullOrBlank() ||
                    item.meta.background.isNullOrBlank() ||
                    item.meta.poster.isNullOrBlank() ||
                    item.meta.description.isNullOrBlank())
        }
        if (pending.isEmpty()) return

        // Snapshot degli item correnti: ogni risultato viene fuso qui e
        // pubblicato subito dopo (sotto mutex, per non serializzare però le
        // richieste di rete — il semaforo resta a 5 in parallelo).
        val byId = LinkedHashMap<String, CalendarItem>(items.size)
        items.forEach { byId[it.meta.id] = it }
        val mergeMutex = Mutex()
        val semaphore = Semaphore(5)
        coroutineScope {
            pending.map { item ->
                async {
                    semaphore.withPermit {
                        val enrichment = runCatching {
                            val tmdbId = tmdbService.ensureTmdbId(item.meta.id, item.meta.apiType)
                                ?: return@runCatching null
                            tmdbMetadataService.fetchArtworkEnrichment(
                                tmdbId = tmdbId,
                                contentType = item.meta.type,
                                language = settings.language
                            )
                        }.getOrNull()
                        latestReleaseArtworkIds.add(item.meta.id)
                        if (enrichment == null) return@withPermit
                        mergeMutex.withLock {
                            var meta = item.meta
                            if (settings.useArtwork) {
                                meta = meta.copy(
                                    logo = enrichment.logo ?: meta.logo,
                                    background = enrichment.backdrop ?: meta.background,
                                    poster = enrichment.poster ?: meta.poster
                                )
                            }
                            if (settings.useBasicInfo) {
                                meta = meta.copy(description = enrichment.description ?: meta.description)
                            }
                            if (meta == item.meta) return@withPermit
                            byId[item.meta.id] = item.copy(meta = meta)
                            // Persisti l'artwork per i riavvii: la prossima
                            // sessione dipinge la riga senza rete.
                            latestReleaseArtworkCache.put(
                                item.meta.id,
                                LatestReleaseArtworkDiskCache.Entry(
                                    logo = meta.logo,
                                    poster = meta.poster,
                                    background = meta.background,
                                    description = meta.description,
                                    fetchedAtMs = System.currentTimeMillis()
                                )
                            )
                            // Publish incrementale: la riga cresce man mano che
                            // ogni logo arriva; l'uguaglianza del publish è un
                            // no-op se il filtro a logo non cambia la lista.
                            publishLatestReleaseItems(items.map { byId[it.meta.id] ?: it })
                        }
                    }
                }
            }.awaitAll()
        }
    }

    internal var posterStatusReconcileJob: Job? = null

    private fun observeTop10() {
        viewModelScope.launch {
            flow { emit(traktTop10Repository.getTopShows()) }
                .retryWhen { _, attempt ->
                    if (attempt >= 3L) {
                        false
                    } else {
                        delay(10_000L * (attempt + 1))
                        true
                    }
                }
                .catch { e ->
                    Log.w(TAG, "top10 failed: ${e.message}")
                }
                .collect { items ->
                    if (_uiState.value.top10Items != items) {
                        _uiState.update { it.copy(top10Items = items) }
                        scheduleUpdateCatalogRows()
                    }
                }
        }
    }

    private fun schedulePosterStatusReconcile(rows: List<CatalogRow>) =
        schedulePosterStatusReconcilePipeline(rows)

    private fun reconcilePosterStatusObservers(rows: List<CatalogRow>) =
        reconcilePosterStatusObserversPipeline(rows)

    private fun navigateToDetail(itemId: String, itemType: String) {
        _uiState.update { it.copy(selectedItemId = itemId) }
    }

    private suspend fun enrichHeroItems(
        items: List<MetaPreview>,
        settings: TmdbSettings
    ): List<MetaPreview> = enrichHeroItemsPipeline(items, settings)

    private fun replaceGridHeroItems(
        gridItems: List<GridItem>,
        heroItems: List<MetaPreview>
    ): List<GridItem> = replaceGridHeroItemsPipeline(gridItems, heroItems)

    private fun heroEnrichmentSignature(items: List<MetaPreview>, settings: TmdbSettings): String =
        heroEnrichmentSignaturePipeline(items, settings)

    /**
     * Saves the current focus and scroll state for restoration when returning to this screen.
     */
    // When true, the next saveFocusState call is suppressed and the flag
    // is reset.  Used during layout switches to prevent the outgoing
    // layout's onDispose from poisoning the incoming layout's focus state.
    internal var suppressFocusSave: Boolean = false

    override fun saveFocusState(
        verticalScrollIndex: Int,
        verticalScrollOffset: Int,
        focusedRowKey: String?,
        focusedItemKeyByRow: Map<String, String>,
        catalogRowScrollStates: Map<String, Int>,
        focusedRowIndex: Int,
        focusedItemIndex: Int
    ) {
        if (suppressFocusSave) {
            suppressFocusSave = false
            return
        }
        val nextState = _focusState.value.copy(
            verticalScrollIndex = verticalScrollIndex,
            verticalScrollOffset = verticalScrollOffset,
            focusedRowKey = focusedRowKey,
            focusedItemKeyByRow = focusedItemKeyByRow,
            catalogRowScrollStates = catalogRowScrollStates,
            focusedRowIndex = focusedRowIndex,
            focusedItemIndex = focusedItemIndex,
            hasSavedFocus = true
        )
        if (_focusState.value == nextState) return
        _focusState.value = nextState
    }

    /**
     * Updates the stable focus target for a specific row.
     */
    fun updateFocusedItemKey(rowKey: String, itemKey: String) {
        _focusState.update { state ->
            val nextMap = state.focusedItemKeyByRow.toMutableMap()
            if (nextMap[rowKey] == itemKey) return@update state
            nextMap[rowKey] = itemKey
            state.copy(focusedItemKeyByRow = nextMap)
        }
    }

    /**
     * Updates the currently focused row key.
     */
    fun updateFocusedRowKey(rowKey: String?) {
        _focusState.update { state ->
            if (state.focusedRowKey == rowKey) state else state.copy(focusedRowKey = rowKey)
        }
    }

    /**
     * Clears the saved focus state.
     */
    fun clearFocusState() {
        _focusState.value = HomeScreenFocusState()
    }

    /**
     * Saves the grid layout focus and scroll state.
     */
    override fun saveGridFocusState(
        verticalScrollIndex: Int,
        verticalScrollOffset: Int,
        focusedRowIndex: Int,
        focusedItemIndex: Int,
        focusedItemKey: String?
    ) {
        _gridFocusState.value = HomeScreenFocusState(
            verticalScrollIndex = verticalScrollIndex,
            verticalScrollOffset = verticalScrollOffset,
            focusedRowIndex = focusedRowIndex,
            focusedItemIndex = focusedItemIndex,
            focusedItemKey = focusedItemKey,
            hasSavedFocus = true
        )
    }

    override fun onCleared() {
        startupAuthNoticeJob?.cancel()
        posterStatusReconcileJob?.cancel()
        movieWatchedBatchJob?.cancel()
        seriesWatchedObserverJob?.cancel()
        cancelInFlightCatalogLoads()
        movieWatchedObserverJobs.values.forEach { it.cancel() }
        movieWatchedObserverJobs.clear()
        super.onCleared()
    }
}
