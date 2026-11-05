package com.nuvio.tv.data.translation

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.nuvio.tv.domain.model.Meta
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

@Singleton
class MetadataTextTranslator @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "MetaTextTranslator"
        private const val CACHE_FILE = "metadata_translation_cache_v2.json"

        /** Older caches could hold poisoned translations and sticky negatives. */
        private val LEGACY_CACHE_FILES = listOf("metadata_translation_cache.json")
        private const val MAX_CACHE_ENTRIES = 1_000
        // Generous budgets: on low-end sticks (e.g. Fire TV) model downloads and
        // local inference are slow; failing fast here meant translations never landed.
        private const val DETECT_TIMEOUT_MS = 6_000L
        private const val MODEL_TIMEOUT_MS = 90_000L
        private const val TRANSLATE_TIMEOUT_MS = 15_000L
        // Each ML Kit sentence costs ~2 s on a Fire Stick at PARALLELISM=4, so the old
        // 45 s budget saturated the CPU for three quarters of a minute after every
        // detail open. Budget enough for the season on screen, then keep the original.
        private const val BATCH_DEADLINE_MS = 15_000L
        private const val PARALLELISM = 4

        /**
         * Coalesces disk writes: a Home row hands the translator a handful of
         * descriptions at once and rewriting the whole JSON under the cache
         * mutex for every one of them serialized all of them behind disk I/O.
         */
        private const val PERSIST_DEBOUNCE_MS = 1_500L
    }

    private val gson = Gson()
    private val mutex = Mutex()
    private val languageIdentifier = LanguageIdentification.getClient()
    private val persistScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val persistScheduled = AtomicBoolean(false)
    @Volatile private var cache: MutableMap<String, String>? = null
    @Volatile private var dirty = false

    /**
     * Snapshot lock-free della cache per le letture rapide della UI.
     *
     * Alla pubblicazione di un item la Home fa un lookup **non sospeso** qui:
     * se la traduzione è già in cache (persistente su disco) pubblica
     * direttamente l'italiano e la descrizione inglese non si vede nemmeno un
     * frame ("flash inglese"). Tenuta in sincronia con [cache] in ogni punto
     * di lettura/scrittura sotto mutex.
     */
    private val fastCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * Rilevamenti di lingua già calcolati (testo → codice), LRU in memoria.
     *
     * [identifyLanguage] è una chiamata sospesa con timeout di 6 s: ogni
     * ritentativo e ogni testo ripetuto (stessa descrizione pubblicata due
     * volte, focus che torna sull'item) la pagava di nuovo. Con questa cache
     * il secondo passaggio sullo stesso testo va direttamente alla traduzione.
     */
    private val detectionCache = object : LinkedHashMap<String, String>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > 500
    }

    /**
     * Coppie "source->target" il cui modello ML Kit è già pronto.
     *
     * [Translator.downloadModelIfNeeded] è una await su un Task che, anche a
     * modello già scaricato, aggiunge decine di millisecondi a OGNI stringa:
     * dopo il primo successo per la coppia lo saltiamo, così l'ondata di
     * traduzioni del dettaglio parte senza attese (richiesta "le traduzioni
     * devono essere più veloci"). Se il download non va a buon fine la coppia
     * non viene marcata e il prossimo tentativo riprova da capo.
     */
    private val downloadedKeys: MutableSet<String> =
        java.util.Collections.synchronizedSet(java.util.HashSet())

    /**
     * One live [com.google.mlkit.nl.translate.Translator] at a time.
     *
     * Building a fresh client per string made ML Kit re-instantiate the wordpiece
     * model for every synopsis (the Fire Stick logged dozens of
     * "Model loaded from pb string" bursts per Home load), which burned CPU and
     * kept the GC busy for seconds. Swapping on pair change keeps the loaded model
     * warm while bounding retained memory to a single model.
     */
    private val translatorLock = Any()
    private var openTranslator: Translator? = null
    private var openTranslatorKey: String = ""

    private fun translatorFor(source: String, target: String): Translator =
        synchronized(translatorLock) {
            val key = "$source->$target"
            val existing = openTranslator
            if (existing == null || openTranslatorKey != key) {
                existing?.close()
                openTranslator = Translation.getClient(
                    TranslatorOptions.Builder()
                        .setSourceLanguage(source)
                        .setTargetLanguage(target)
                        .build()
                )
                openTranslatorKey = key
            }
            openTranslator!!
        }

    /**
     * Pre-downloads the English→target ML Kit model in the background so the
     * first metadata translation doesn't stall on a multi-MB download.
     * No-op when the target language is unsupported or already English.
     */
    suspend fun warmUp(targetLanguage: String) = withContext(Dispatchers.IO) {
        // Language identification downloads its own model on first use and has
        // its own short per-string timeout — probe it with a sample so the first
        // home-hero translation doesn't stall (or silently fail) on that download,
        // even when no translation model is needed for the target language.
        runCatching {
            languageIdentifier
                .identifyLanguage("Language identification warm-up sample text.")
                .awaitText()
        }
        val target = normalizeLanguageCode(targetLanguage) ?: return@withContext
        if (target !in TRANSLATION_SUPPORTED_LANGUAGES || target == "en") return@withContext
        // Kept open on purpose: see translatorFor. Closing it here would throw away
        // the model the first Home row is about to need again.
        val translator = translatorFor("en", target)
        if (translator.downloadModelIfNeeded().awaitOk()) {
            downloadedKeys.add("en->$target")
            Log.d(TAG, "Translation model warm-up completed for $target")
        }
    }

    /**
     * Translates a [Meta], handing the synopsis back through [onDescriptionReady]
     * as soon as it is ready instead of waiting for every episode overview: on a
     * series the detail screen would otherwise keep showing the original
     * description for as long as the whole batch takes.
     */
    suspend fun translateMeta(
        meta: Meta,
        targetLanguage: String,
        onDescriptionReady: (suspend (String) -> Unit)? = null
    ): Meta = withContext(Dispatchers.IO) {
        if (normalizeLanguageCode(targetLanguage) !in TRANSLATION_SUPPORTED_LANGUAGES) {
            return@withContext meta
        }
        val startedAtMs = System.currentTimeMillis()
        try {
            val newDescription = meta.description?.let { translateText(it, targetLanguage, startedAtMs) }
            if (newDescription != null && newDescription != meta.description) {
                onDescriptionReady?.invoke(newDescription)
            }
            val newVideos = translateVideos(meta, targetLanguage, startedAtMs)
            if (newDescription == null && newVideos == meta.videos) {
                meta
            } else {
                meta.copy(description = newDescription ?: meta.description, videos = newVideos)
            }
        } finally {
            withContext(NonCancellable) { schedulePersistCache() }
        }
    }

    /**
     * Translates short single-line metadata (e.g. OMDb awards text) that is
     * below the synopsis length gate. Same detection, timeout and persistent
     * cache rules as [translateMeta]; returns null when nothing to show.
     */
    suspend fun translateAwardText(text: String, targetLanguage: String): String? =
        withContext(Dispatchers.IO) {
            val target = normalizeLanguageCode(targetLanguage) ?: return@withContext null
            if (target !in TRANSLATION_SUPPORTED_LANGUAGES || target == "en") {
                return@withContext null
            }
            val startedAtMs = System.currentTimeMillis()
            try {
                translateText(
                    text = text,
                    targetLanguage = target,
                    startedAtMs = startedAtMs,
                    minLength = TRANSLATION_SHORT_MIN_TEXT_LENGTH
                )
            } finally {
                withContext(NonCancellable) { schedulePersistCache() }
            }
        }

    /**
     * Translates a standalone description/overview string with the same
     * length, detection and persistent-cache rules as [translateMeta].
     * Returns null when nothing needs to change (too short, already in the
     * target language, detection failed).
     */
    suspend fun translateDescription(text: String, targetLanguage: String): String? =
        withContext(Dispatchers.IO) {
            val target = normalizeLanguageCode(targetLanguage) ?: return@withContext null
            if (target !in TRANSLATION_SUPPORTED_LANGUAGES) return@withContext null
            try {
                translateText(text, targetLanguage, System.currentTimeMillis())
            } finally {
                withContext(NonCancellable) { schedulePersistCache() }
            }
        }

    private suspend fun translateVideos(
        meta: Meta,
        targetLanguage: String,
        startedAtMs: Long
    ): List<com.nuvio.tv.domain.model.Video> {
        if (meta.videos.none { !it.overview.isNullOrBlank() }) return meta.videos
        return coroutineScope {
            val semaphore = Semaphore(PARALLELISM)
            meta.videos
                .map { video ->
                    async {
                        val overview = video.overview
                        if (overview.isNullOrBlank()) return@async video
                        semaphore.withPermit {
                            val translated = translateText(overview, targetLanguage, startedAtMs)
                            if (translated != null) video.copy(overview = translated) else video
                        }
                    }
                }
                .awaitAll()
        }
    }

    private suspend fun translateText(
        text: String,
        targetLanguage: String,
        startedAtMs: Long,
        minLength: Int = TRANSLATION_MIN_TEXT_LENGTH
    ): String? {
        val target = normalizeLanguageCode(targetLanguage) ?: return null
        if (target !in TRANSLATION_SUPPORTED_LANGUAGES) return null
        val body = text.trim()
        if (body.length < minLength || body.length > TRANSLATION_MAX_TEXT_LENGTH) {
            return null
        }
        val key = translationCacheKey(target, text)
        // Lookup lock-free prima del mutex: sulla UI la cache in memoria è già
        // calda e non serve nemmeno toccare il caricamento da disco.
        fastCache[key]?.let { cached ->
            if (cached.isNotBlank()) return cached
        }
        val cached = cachedValue(key)
        if (cached != null) {
            // Un negativo non dovrebbe più esistere (non lo scriviamo più), ma
            // quello lasciato in memoria da una build precedente non deve
            // bloccare per sempre il testo: lo scartiamo e ripartiamo da zero.
            if (cached.isNotBlank()) {
                return cached
            }
            clearCachedValue(key)
        }
        if (System.currentTimeMillis() - startedAtMs > BATCH_DEADLINE_MS) {
            return null
        }

        val detected = withTimeoutOrNull(DETECT_TIMEOUT_MS) {
            identifyLanguage(body)
        }
        if (detected == null) {
            return null
        }
        if (!shouldTranslateDetected(body, detected, target, minLength)) {
            // Non memorizzare MAI un negativo in cache. Una singola volta che il
            // rilevamento lingua torna "stesso linguaggio" (o "und" per un testo
            // ambiguo, o l'entry appena pubblicata è un'altra descrizione dello
            // stesso item) bloccava quel testo per TUTTA la sessione: l'hero
            // restava in inglese anche quando la traduzione era fattibile. La
            // rilevazione è un'operazione locale e veloce, quindi ritentare al
            // prossimo focus non costa nulla e fa sí che un testo davvero
            // inglese venga tradotto appena il testo giusto arriva in cache.
            return null
        }

        // Reused across calls; never closed here (see translatorFor).
        val translator = translatorFor(detected, target)
        val modelKey = "$detected->$target"
        if (modelKey !in downloadedKeys) {
            val modelReady = withTimeoutOrNull(MODEL_TIMEOUT_MS) {
                translator.downloadModelIfNeeded().awaitOk()
            }
            if (modelReady != true) {
                return null
            }
            downloadedKeys.add(modelKey)
        }
        val translated = withTimeoutOrNull(TRANSLATE_TIMEOUT_MS) {
            translator.translate(body).awaitText()
        }?.takeIf { it.isNotBlank() && it != body }
        if (translated == null) {
            return null
        }
        rememberTranslation(key, translated)
        return translated
    }

    private suspend fun identifyLanguage(text: String): String? {
        synchronized(detectionCache) { detectionCache[text] }?.let { cached ->
            if (cached.isNotBlank()) return cached
        }
        val detected = languageIdentifier.identifyLanguage(text).awaitText()
        if (!detected.isNullOrBlank()) {
            synchronized(detectionCache) { detectionCache[text] = detected }
        }
        return detected
    }

    private suspend fun cachedValue(key: String): String? = mutex.withLock {
        ensureLoadedLocked()[key]
    }

    /** Drops a stale blank (negative) entry so the text can be retried. */
    private suspend fun clearCachedValue(key: String) = mutex.withLock {
        val removed = ensureLoadedLocked().remove(key) != null
        fastCache.remove(key)
        if (removed) dirty = true
    }

    /** Only successful translations are cached: [value] is always non-blank. */
    private suspend fun rememberTranslation(key: String, value: String) = mutex.withLock {
        ensureLoadedLocked()[key] = value
        fastCache[key] = value
        dirty = true
    }

    /**
     * Lookup **non sospeso** della traduzione già in cache, per la UI: se
     * ritorna un valore, la pubblicazione parte subito in italiano e la
     * descrizione inglese non compare nemmeno per un frame.
     */
    fun peekCachedTranslation(targetLanguage: String?, text: String?): String? {
        val target = normalizeLanguageCode(targetLanguage) ?: return null
        if (text.isNullOrEmpty()) return null
        if (target !in TRANSLATION_SUPPORTED_LANGUAGES) return null
        return fastCache[translationCacheKey(target, text)]
    }

    /** Coalesces [persistCacheIfDirty] writes; never blocks the caller. */
    private fun schedulePersistCache() {
        if (!dirty || !persistScheduled.compareAndSet(false, true)) return
        persistScope.launch {
            try {
                delay(PERSIST_DEBOUNCE_MS)
                persistCacheIfDirty()
            } finally {
                persistScheduled.set(false)
            }
        }
    }

    private suspend fun persistCacheIfDirty() {
        mutex.withLock {
            if (!dirty) return
            val snapshot = cache?.filterValues { it.isNotBlank() } ?: return
            try {
                File(context.filesDir, CACHE_FILE).writeText(gson.toJson(snapshot))
                dirty = false
            } catch (e: Exception) {
                Log.w(TAG, "Failed to write translation cache: ${e.message}")
            }
        }
    }

    private fun ensureLoadedLocked(): MutableMap<String, String> {
        cache?.let { return it }
        val loaded = readCacheFromDisk()
        cache = loaded
        // Popola lo snapshot lock-free (solo voci valide: un negativo residuo
        // di una vecchia build non deve mai essere proposto alla UI).
        loaded.forEach { (entryKey, value) -> if (value.isNotBlank()) fastCache[entryKey] = value }
        return loaded
    }

    private fun readCacheFromDisk(): MutableMap<String, String> {
        val target: MutableMap<String, String> = newCache()
        LEGACY_CACHE_FILES.forEach { legacy ->
            runCatching { File(context.filesDir, legacy).delete() }
        }
        return try {
            val file = File(context.filesDir, CACHE_FILE)
            if (file.exists()) {
                val parsed: Map<String, String>? = gson.fromJson(
                    file.readText(),
                    object : TypeToken<Map<String, String>>() {}.type
                )
                parsed?.forEach { (entryKey, value) -> target[entryKey] = value }
            }
            target
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read translation cache: ${e.message}")
            newCache()
        }
    }

    private fun newCache(): MutableMap<String, String> =
        object : LinkedHashMap<String, String>() {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
                size > MAX_CACHE_ENTRIES
        }

    private suspend fun Task<String>.awaitText(): String? = suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { value -> if (continuation.isActive) continuation.resume(value) }
        addOnFailureListener { if (continuation.isActive) continuation.resume(null) }
    }

    private suspend fun Task<Void>.awaitOk(): Boolean = suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { if (continuation.isActive) continuation.resume(true) }
        addOnFailureListener { if (continuation.isActive) continuation.resume(false) }
    }
}
