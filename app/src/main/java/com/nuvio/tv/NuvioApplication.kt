package com.nuvio.tv

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.os.Build
import android.os.StrictMode
import android.util.Log
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.gif.GifDecoder
import coil3.gif.AnimatedImageDecoder
import coil3.svg.SvgDecoder
import coil3.request.crossfade
import coil3.request.allowHardware
import coil3.request.allowRgb565
import coil3.bitmapFactoryMaxParallelism

import okio.Path.Companion.toOkioPath
import com.nuvio.tv.core.diagnostics.SentryInitializer
import com.nuvio.tv.core.runtime.PluginRuntimeHooks
import com.nuvio.tv.core.sync.StartupSyncService
import com.nuvio.tv.core.sync.androidtv.AndroidTvChannelSyncService
import com.nuvio.tv.core.network.IPv4FirstDns
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.SentrySettingsDataStore
import com.nuvio.tv.data.simkl.SimklAnimeIdPreferenceHolder
import coil3.network.cachecontrol.CacheControlCacheStrategy
import dagger.hilt.android.HiltAndroidApp
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

@HiltAndroidApp
class NuvioApplication : Application(), SingletonImageLoader.Factory {

    @Inject lateinit var startupSyncService: StartupSyncService
    @Inject lateinit var startupHomePreloader: com.nuvio.tv.core.sync.StartupHomePreloader
    @Inject lateinit var androidTvChannelSyncService: AndroidTvChannelSyncService
    @Inject lateinit var sentrySettingsDataStore: SentrySettingsDataStore
    @Inject lateinit var simklAnimeIdPreferenceHolder: SimklAnimeIdPreferenceHolder
    @Inject lateinit var trailerPlayerPool: com.nuvio.tv.core.player.TrailerPlayerPool

    companion object {
        private const val TAG = "NuvioApplication"

        /**
         * Ultimo input dell'utente (tasto/comando premuto), marchiato da
         * [MainActivity.onUserInteraction]. Usato per rimandare le azioni di
         * sollievo che mettono l'UI in pausa (cache immagini + GC): mai a
         * metà di uno scroll — è la causa diretta del "si blocca" percepito
         * quando si scorre veloce sulla home.
         */
        @Volatile
        var lastUserInteractionAtMs = 0L
            private set

        fun markUserInteraction() {
            lastUserInteractionAtMs = android.os.SystemClock.elapsedRealtime()
        }

        /** Let the splash/profile render before background startup work begins. */
        private const val STARTUP_DEFERRED_INIT_DELAY_MS = 1_500L
        private const val STARTUP_CHANNEL_SYNC_DELAY_MS = 3_000L
        private const val STARTUP_HOME_PRELOAD_DELAY_MS = 4_500L

        // Battito cardiaco GC (vedi scheduleHeapHeartbeat): soglia heap,
        // intervallo minimo tra GC e cadenza del controllo.
        private const val HEAP_HEARTBEAT_USED_BYTES = 140L * 1024 * 1024
        private const val HEAP_HEARTBEAT_MIN_INTERVAL_MS = 20_000L
        private const val HEAP_HEARTBEAT_PERIOD_MS = 10_000L
        /**
         * Shared cookie jar for CloudStream extension HTTP requests.
         * Accessible so the player's OkHttpClient can share cookies
         * obtained during scraping (e.g., session tokens needed for playback).
         */
        val extensionCookieJar: CookieJar = object : CookieJar {
            private val store = ConcurrentHashMap<String, MutableList<Cookie>>()

            override fun loadForRequest(url: HttpUrl): List<Cookie> {
                val hostCookies = store[url.host] ?: return emptyList()
                synchronized(hostCookies) {
                    return hostCookies.filter { cookie ->
                        cookie.expiresAt > System.currentTimeMillis()
                    }
                }
            }

            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                val hostCookies = store.getOrPut(url.host) { mutableListOf() }
                synchronized(hostCookies) {
                    cookies.forEach { newCookie ->
                        hostCookies.removeAll { it.name == newCookie.name }
                        hostCookies.add(newCookie)
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Load locale synchronously so it's available before Activity.attachBaseContext.
        // SharedPreferences reads are fast (cached in memory after first access).
        val tag = getSharedPreferences("app_locale", Context.MODE_PRIVATE)
            .getString("locale_tag", null)
        LocaleCache.localeTag = tag ?: ""

        // In-process memory telemetry (Log.i, release-safe): captures the Java/
        // Native/Graphics curve every 10s even when dumpsys times out under
        // allocation bursts — the LMK kills left us blind until now.
        runCatching { com.nuvio.tv.core.diagnostics.MemoryWatch.start(this) }

        // Chiusura immediata delle app lasciate dall'utente: watcher dei
        // cambi di primo piano (usage stats) + pulizia cached all'apertura.
        // Su 1GB ogni app cached ruba RAM alla prossima app aperta.
        runCatching { com.nuvio.tv.core.memory.BackgroundAppCleaner.start(this) }

        // Governor di pressione: lmkd avvisa (onTrimMemory livello 15) solo
        // all'emergenza assoluta, e nei kill documentati l'avviso non arrivava
        // mai in tempo. MemoryWatch legge MemAvailable/SwapFree dal sistema
        // ogni 10s e chiama questo callback appena entrano in zona di kill:
        // liberiamo trailer + cache ~1 minuto prima che il processo venga
        // sacrificato, quando la liberatoria è ancora efficace.
        runCatching {
            com.nuvio.tv.core.diagnostics.MemoryWatch.onRelief = { reason ->
                relieveMemoryPressure(reason, full = true)
            }
            com.nuvio.tv.core.diagnostics.MemoryWatch.onSoftRelief = { reason ->
                // SOFT: solo il trailer (niente cache-clear/GC). Fermare la
                // riproduzione qui libera ~147MB di ION misurati e toglie il
                // processo dalla zona di kill prima che il sistema collassi.
                relieveMemoryPressure(reason, full = false)
            }
        }

        // Battito cardiaco GC: avviato qui, gira per tutta la vita del
        // processo (controlla l'heap ogni 10s, GC al massimo ogni 20s).
        heartbeatHandler.postDelayed({ scheduleHeapHeartbeat() }, 15_000L)

        // Warm the Fluid Mode zoom mirror for the same reason as the locale above:
        // MainActivity reads it in attachBaseContext to decide the density scale.
        LayoutPreferenceDataStore.readFluidZoomMirror(this)

        // Defer heavy initialization until the first frames are on screen,
        // and stagger it: firing Sentry I/O, the JobScheduler binder call and
        // the home preloader (ML Kit + catalog warm-up) in one shot recreates
        // the launch freeze we just removed, so each wave gets its own slot.
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        handler.postDelayed(
            {
                SentryInitializer.start(this, sentrySettingsDataStore)
                PluginRuntimeHooks.onApplicationCreate(this)
            },
            STARTUP_DEFERRED_INIT_DELAY_MS
        )
        handler.postDelayed(
            { androidTvChannelSyncService.start() },
            STARTUP_CHANNEL_SYNC_DELAY_MS
        )
        handler.postDelayed(
            // Warm Home catalogs/CW/layout while the splash + profile UI is up.
            { startupHomePreloader.ensureStarted() },
            STARTUP_HOME_PRELOAD_DELAY_MS
        )
    }

    /**
     * Thread dedicato per la liberatoria: su una Stick affamata, System.gc() e
     * il rilascio del codec (binder + teardown) sul main thread congelavano la
     * UI per secondi — il "blocco" percepito prima di alcuni kill. ExoPlayer è
     * thread-safe per release(); Coil e ART idem.
     */
    private val reliefExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "nuvio-memory-relief").apply { isDaemon = true }
    }

    @Volatile
    private var lastReliefAtMs = 0L

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Log.i sopravvive nel release: senza questo non era possibile capire se il
        // sistema aveva davvero avvisato l'app prima del kill LMK.
        Log.i(TAG, "onTrimMemory level=$level poolInitialized=${this::trailerPlayerPool.isInitialized}")
        if (!this::trailerPlayerPool.isInitialized) return
        when {
            // Livello 80 (COMPLETE): lmkd lo invia POCHI istANTI prima del kill —
            // qui si fa tutto, gc incluso, ogni risparmio conta.
            level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE ->
                relieveMemoryPressure("lmkd-level$level", full = true)
            // UI nascosta (utente tornato al launcher): è IL momento per
            // restituire tutto — niente trailer (yield senza cooldown: al
            // ritorno si ricostruisce subito), cache giù, gc completo.
            // Disciplina Netflix: quando non si vede, non occupa.
            level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN ->
                relieveUiHidden()
            // Su questa Fire OS il livello 15 arriva PRESTO (anche con 200 MB
            // liberi): solo azioni economiche, niente gc — un gc qui era la
            // causa dei blocchi percepiti sulla home.
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ->
                relieveMemoryPressure("lmkd-level$level", full = false)
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> {
                reliefExecutor.execute { runCatching { trailerPlayerPool.trim() } }
            }
        }
    }

    private var lastHeartbeatGcAtMs = 0L
    private val heartbeatHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * Battito cardiaco GC — la lezione dei kill del 14/10 (15:28: RSS 715MB,
     * other_free negativo, lmkd uccideva perfino i processi di sistema): i
     * livelli 10/15 di lmkd non triggerano GC (rimosso perché "bloccava la
     * home") e il sollievo completo non scattava mai in tempo — durante uno
     * scroll lungo l'heap cresceva senza raccolta fino al kill. Un GC breve
     * ogni ≥20s quando l'heap supera 140MB è una coda limitata (~100ms,
     * disciplina Netflix delle code brevi) invece della morte.
     */
    private fun scheduleHeapHeartbeat() {
        heartbeatHandler.postDelayed({ scheduleHeapHeartbeat() }, HEAP_HEARTBEAT_PERIOD_MS)
        reliefExecutor.execute {
            val runtime = Runtime.getRuntime()
            val usedBytes = runtime.totalMemory() - runtime.freeMemory()
            val now = android.os.SystemClock.elapsedRealtime()
            if (
                usedBytes >= HEAP_HEARTBEAT_USED_BYTES &&
                now - lastHeartbeatGcAtMs >= HEAP_HEARTBEAT_MIN_INTERVAL_MS
            ) {
                lastHeartbeatGcAtMs = now
                Log.w(TAG, "heap heartbeat used=${usedBytes / (1024 * 1024)}MB — gc")
                runCatching { System.gc() }
            }
        }
    }

    /**
     * Percorso unico di liberatoria di memoria, sempre su thread dedicato.
     * Usato dal governor di pressione di MemoryWatch (autonomo, ~1 minuto prima
     * del kill) e da onTrimMemory (segnali di lmkd). economico = trailer + cache
     * immagini; full = anche System.gc(). Il trailer è la risorsa più grande
     * che possiamo rilasciare su richiesta: misurato ~200 MB di heap nativo.
     */
    private fun relieveMemoryPressure(reason: String, full: Boolean) {
        Log.w(TAG, "relieveMemoryPressure($reason) full=$full")
        val now = android.os.SystemClock.elapsedRealtime()
        if (!full && now - lastReliefAtMs < 15_000L) {
            Log.i(TAG, "relief debounced ($reason)")
            return
        }
        // Anche il sollievo FULL ha un debounce: con le soglie di rilascio
        // calibrate (1.0.72) lo stato può rimbalzare HARD→NONE→HARD mentre
        // si scorre — ogni rimbalzo = cache immagini svuotate + GC = scroll
        // più lento. Mai due sollievi completi in meno di 30 secondi.
        if (full && now - lastReliefAtMs < 30_000L) {
            Log.i(TAG, "full relief debounced ($reason)")
            return
        }
        lastReliefAtMs = now
        reliefExecutor.execute {
            if (!this::trailerPlayerPool.isInitialized) return@execute
            // Il trailer si rilascia SUBITO: è la risorsa più grande che
            // possiamo liberare (~130 MB di ION) e il rilascio non tocca l'UI.
            runCatching { trailerPlayerPool.trim() }
            // Livello economico: solo il trailer. Svuotare la cache immagini
            // qui (arrivava ogni level15, più volte l'ora) obbligava a
            // riscaricare bitmaps visibili — jank gratuito.
            if (!full) return@execute
            // Cache immagini + GC mettono l'UI in pausa: i bitmaps visibili
            // vanno riscaricati e il GC è stop-the-world. Se l'utente sta
            // premendo tasti, rimandano finché non fa una pausa di 1,5 s
            // (max 8 s): l'emergenza resta, ma mai a metà di uno scroll.
            val deadline = android.os.SystemClock.elapsedRealtime() + 8_000L
            while (
                android.os.SystemClock.elapsedRealtime() < deadline &&
                android.os.SystemClock.elapsedRealtime() - lastUserInteractionAtMs < 1_500L
            ) {
                runCatching { Thread.sleep(200L) }
            }
            runCatching { SingletonImageLoader.get(this).memoryCache?.clear() }
            runCatching { System.gc() }
        }
    }

    /**
     * Rilascio completo quando l'UI va in background (tasto Home). Usa [yield]
     * invece di [com.nuvio.tv.core.player.TrailerPlayerPool.trim]: yield libera
     * il decoder senza armare il cooldown, così al ritorno l'anteprima
     * ricomincia senza attesa.
     */
    private fun relieveUiHidden() {
        Log.i(TAG, "relieveUiHidden (UI hidden)")
        if (!this::trailerPlayerPool.isInitialized) return
        reliefExecutor.execute {
            runCatching { trailerPlayerPool.yield() }
            runCatching { SingletonImageLoader.get(this).memoryCache?.clear() }
            runCatching { System.gc() }
        }
    }

    override fun newImageLoader(context: android.content.Context): ImageLoader {
        return ImageLoader.Builder(this)
            .components {
                if (Build.VERSION.SDK_INT >= 28) {
                    add(AnimatedImageDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
                add(SvgDecoder.Factory())
                // Clamp universale decode (stile Prime Video): nessuna immagine
                // decoda oltre 960px di lato lungo — le card sono ~400px a
                // schermo. Taglia la corsa RSS dello scroll di ~4x (kill del
                // 14/10: RSS 438MB durante scroll veloce, 572MB totali).
                add(com.nuvio.tv.core.image.ClampDecodeInterceptor())
                // CacheControlCacheStrategy respects server Cache-Control headers,
                // so dynamic images (e.g. BetterPosters with max-age) revalidate.
                add(
                    coil3.network.okhttp.OkHttpNetworkFetcherFactory(
                        callFactory = {
                            OkHttpClient.Builder()
                                .dns(IPv4FirstDns())
                                .followRedirects(true)
                                .followSslRedirects(true)
                                // OkHttp defaults are 64 in-flight requests and 5 per host;
                                // a single Home load was holding 6 parallel connections to
                                // aiometadata.elfhosted.com and 46 live OkHttp threads. Image
                                // fetches are the bulk of that traffic, so cap them here.
                                // 2 per host era troppo stretto: durante lo scroll si
                                // vedevano le card nude in attesa del turno. A 4 le immagini
                                // arrivano con lo scroll, mentre il totale resta fermo a 8.
                                .dispatcher(
                                    okhttp3.Dispatcher().apply {
                                        maxRequests = 8
                                        maxRequestsPerHost = 4
                                    }
                                )
                                // OkHttp defaults to 10s: poster/backdrop fetches on
                                // slow sticks frequently exceed it, leaving blank cards.
                                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                                .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                                .build()
                        },
                        cacheStrategy = { CacheControlCacheStrategy() },
                    )
                )
            }
            .memoryCache {
                // Bitmap pixels count against the Java heap on the Fire Stick (verified
                // with a heap dump), so the old "33% of maxMemory" budget alone pushed
                // the app past 250 MB and triggered low-memory kills. Fixed cap instead.
                // The stick only has 922 MB of RAM and the app was reaching 646 MB at the
                // Low Memory Killer threshold, so 32 MB of resident bitmaps on top of the
                // video/GPU buffers is more than it can afford: 16 MB keeps a full screen
                // of posters plus the hero backdrop hot and lets the rest fall back to the
                // 200 MB disk cache.
                MemoryCache.Builder()
                    .maxSizeBytes(16L * 1024 * 1024)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache").toOkioPath())
                    .maxSizeBytes(200L * 1024 * 1024)
                    .build()
            }
            .crossfade(false)
            .precision(coil3.size.Precision.INEXACT)
            .allowHardware(true)
            .allowRgb565(true)
            // 2 parallele significava che, scrollando, si alternavano momenti in
            // cui nessuna card stava venendo decodificata: le righe dense (Top 10,
            // "Potrebbero piacerti") restavano vuote più del necessario. 3 resta
            // sotto il numero di core utile di una stick.
            .bitmapFactoryMaxParallelism(3)
            .build()
    }
}
