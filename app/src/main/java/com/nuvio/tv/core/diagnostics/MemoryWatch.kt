package com.nuvio.tv.core.diagnostics

import android.content.Context
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lightweight in-process memory telemetry.
 *
 * The Fire TV Stick kills NuvioTV via LMK once the process reaches ~650-750 MB,
 * but `dumpsys meminfo` needs a binder round-trip into the app: during the
 * allocation bursts that precede the kill the main thread is so busy that
 * dumpsys times out, so the category breakdown (Java vs Native vs Graphics)
 * was never capturable from outside.
 *
 * This sampler runs inside the process on the main handler, reads
 * [Debug.getMemoryInfo] (process-local libmemtrack walk, no binder) plus
 * /proc/self/status, and emits ONE compact line every [INTERVAL_MS] via Log.i
 * (Log.i survives in release builds; Log.d does not). A PC-side logcat capture
 * then preserves the full memory curve across kills.
 *
 * Ogni riga viene inoltre **appenduta su file** (memwatch.log, ring-buffer con
 * rotazione a [MAX_LOG_BYTES] → memwatch.prev.log): logcat viene perso quando
 * il processo viene ucciso o quando non c'è una cattura PC attiva, mentre il
 * file sopravvive al kill e resta leggibile con `adb pull`
 * (getExternalFilesDir → /sdcard/Android/data/com.nuvio.tv/files/). Il
 * prefisso wall-clock di ogni riga permette di ricollegare le curve di
 * sessioni diverse.
 */
object MemoryWatch {
    private const val TAG = "MemWatch"
    private const val INTERVAL_MS = 10_000L
    private const val FIRST_DELAY_MS = 5_000L

    /** Soglia di rotazione del ring-buffer su file (~1000 campioni, ore di curva). */
    private const val MAX_LOG_BYTES = 256L * 1024
    private const val LOG_FILE_NAME = "memwatch.log"
    private const val LOG_PREV_NAME = "memwatch.prev.log"

    /**
     * Campionamento rapido (3 s) quando si è in pressione o la RSS sta
     * fugendo: l'ondata del kill delle 12:46 ha fatto +220 MB in 6 secondi —
     * con il campione ogni 10 s il processo moriva tra due campioni.
     * In modalità rapida si saltano Debug.getMemoryInfo (il passo più caro,
     * eseguito sul main handler): basta lo stato letto da /proc.
     */
    private const val FAST_INTERVAL_MS = 3_000L

    /** Delta di RSS tra due campioni che segnala una fuga in corso. */
    private const val RUNAWAY_RSS_DELTA_KB = 100_000L // 100 MB

    /**
     * La fuga RSS vale solo su RSS già alte: la crescita normale di avvio
     * (processo fresco: 30 -> 140 MB in pochi secondi) NON è una fuga.
     * Senza questo gate il governor entrava in HARD a ogni avvio app.
     */
    private const val RUNAWAY_MIN_RSS_KB = 300_000L

    /**
     * Soglie di pressione del SISTEMA lette da /proc/meminfo.
     *
     * Tutti i kill LMK documentati seguono lo stesso schema: lo SwapFree della
     * Stick collassa da ~115 MB a 0 in ~80 s (lo zRAM si riempie delle nostre
     * pagine anonime) e lmkd sacrifica il processo in primo piano. A quel punto
     * l'avviso di lmkd (onTrimMemory livello 15) non serve più: serve liberare
     * PRIMA. I valori under-soglia di MemAvailable/SwapFree sono raggiungibili
     * in-process a costo zero e anticipano il kill di circa un minuto.
     */
    private const val HARD_PRESSURE_SWAP_KB = 40_000L
    private const val HARD_PRESSURE_AVAIL_KB = 90_000L
    private const val SOFT_PRESSURE_AVAIL_KB = 150_000L
    private const val SOFT_PRESSURE_SWAP_KB = 100_000L
    // Per uscire da HARD/NONE servono ENTRAMBE le soglie, ma calibrate
    // sulla realtà di queste Stick: a riposo vivono a ~180-260MB
    // disponibili. Le soglie vecchie (>250MB/180MB) non si raggiungevano
    // quasi mai → lo stato restava piantato e tutto ciò che è gatellato
    // dalla pressione (arrivo CW, enrichment, trailer) restava in attesa.
    private const val RELEASE_AVAIL_KB = 150_000L
    private const val RELEASE_SWAP_KB = 100_000L

    /**
     * Pressione AUTO-imposta: la RSS del processo. Il kill delle 23:01 è
     * arrivato con rss=511 MB mentre il sistema era borderline — guardare
     * solo il sistema non basta: su una Stick da 1 GB il processo deve
     * tenere d'occhio anche SE STESSO. 380 MB di RSS occupano già ~40% della
     * RAM fisica; la soglia vale solo sulle classi di RAM basse, dove quei
     * MB sono critici (su TV da 2+ GB 380 MB sono normali).
     */
    private const val HARD_SELF_RSS_KB = 380_000L
    private const val LOW_RAM_TOTAL_KB = 1_677_721L // 1.6 GB (classe gentle)

    /** Evita di liberare più di una volta quando il valore oscilla attorno alla soglia. */
    private const val RELIEF_MIN_INTERVAL_MS = 30_000L

    /**
     * Riedizione del sollievo mentre lo stato resta HARD: la prima
     * liberazione non basta se l'app continua a crescere (scroll rapido +
     * trailer). Finché dura la pressione si ripete al massimo ogni 45 s.
     */
    private const val RELIEF_REPEAT_MS = 45_000L

    private enum class Pressure { NONE, SOFT, HARD }

    /**
     * Invocato (una volta per episodio) quando il sistema entra in pressione
     * critica: l'application libera trailer + cache immagini prima che lmkd
     * uccida il processo. Al callback NON vanno fatte chiamate bloccanti lunghe:
     * gira sul main handler del sampler.
     */
    @Volatile
    var onRelief: ((String) -> Unit)? = null

    /**
     * Pressione SOFT (MemAvailable < 150MB ma non ancora zona di kill):
     * qui si rilascia SOLO il trailer. Fermare la riproduzione — libera
     * ~147 MB di ION, misurato al kill delle 14:17 — appena il sistema
     * respira male costa meno che aspettare la zona di kill.
     */
    var onSoftRelief: ((String) -> Unit)? = null

    /**
     * Vero quando il sistema è in zona di kill (stato HARD). Usato da trailer
     * pool e preloader per NON aggiungere allocazioni mentre l'LMK sta per
     * colpire: sotto pressione la home mostra lo sfondo statico invece di
     * avviare decoder, e il preload viene differito.
     */
    @Volatile
    var underPressure: Boolean = false
        private set

    @Volatile
    private var pressureState = Pressure.NONE

    @Volatile
    private var lastReliefAtMs = 0L

    /** RAM fisica del dispositivo (letta una volta in [start]). */
    @Volatile
    private var totalMemKb = -1L

    /** RSS del campione precedente — base per rilevare fughe tra due letture. */
    @Volatile
    private var lastRssKb = -1L

    private val handler = Handler(Looper.getMainLooper())
    private var startedAtMs = 0L

    // --- ring-buffer su file (vedi KDoc): tutte le scritture girano sul main
    // thread del sampler, il lock serve solo contro eventuali emit esterni. ---
    @Volatile
    private var logFile: File? = null
    private val logLock = Any()
    private val wallFmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    /**
     * Logcat + append sul file di curva. Il file viene aperto in append per
     * singola riga e flussato: le pagine sporche restano nel page-cache del
     * kernel e sopravvivono al kill LMK del processo. Rotazione a
     * [MAX_LOG_BYTES]: la vecchia versione va su memwatch.prev.log.
     */
    private fun emit(priority: Int, message: String) {
        Log.println(priority, TAG, message)
        val file = logFile ?: return
        synchronized(logLock) {
            runCatching {
                if (file.length() > MAX_LOG_BYTES) {
                    val prev = File(file.parentFile, LOG_PREV_NAME)
                    prev.delete() // false se non esiste: l'esito rilevante è il rename
                    if (!file.renameTo(prev)) {
                        // Rotazione fallita: meglio fermarsi che far crescere
                        // il file senza limite.
                        logFile = null
                        return
                    }
                }
                val line = "${wallFmt.format(Date())} $message\n"
                FileOutputStream(file, true).use { out ->
                    out.write(line.toByteArray(Charsets.UTF_8))
                    out.flush()
                }
            }.onFailure {
                Log.w(TAG, "file log write failed: ${it.javaClass.simpleName}")
            }
        }
    }

    @Volatile
    private var running = false

    private val sampler = object : Runnable {
        override fun run() {
            if (!running) return
            val nextDelayMs = runCatching { sampleOnce() }
                .onFailure { Log.w(TAG, "sample failed: ${it.javaClass.simpleName}") }
                .getOrDefault(INTERVAL_MS)
            handler.postDelayed(this, nextDelayMs)
        }
    }

    fun start(context: Context) {
        if (running) return
        running = true
        startedAtMs = android.os.SystemClock.elapsedRealtime()
        runCatching {
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            logFile = File(dir, LOG_FILE_NAME)
        }
        runCatching {
            File("/proc/meminfo").forEachLine { line ->
                if (line.startsWith("MemTotal:")) {
                    totalMemKb = line.substringAfter(':').removeSuffix("kB").trim().toLongOrNull() ?: -1L
                }
            }
        }
        val rt = Runtime.getRuntime()
        emit(
            Log.INFO,
            "watch started heapMax=${rt.maxMemory() / 1024}kB " +
                "heapTotal=${rt.totalMemory() / 1024}kB nativeHeap=${Debug.getNativeHeapAllocatedSize() / 1024}kB " +
                "pid=${android.os.Process.myPid()}"
        )
        handler.postDelayed(sampler, FIRST_DELAY_MS)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(sampler)
    }

    /** Legge il prossimo intervallo: 3 s in emergenza, 10 s a regime. */
    private fun sampleOnce(): Long {
        val elapsedS = (android.os.SystemClock.elapsedRealtime() - startedAtMs) / 1000

        // 1) Lettura economica da /proc: basta per decidere se c'è emergenza
        //    (pressione di sistema o fuga di RSS) senza toccare Debug API.
        var rssKb = -1L
        var swapKb = -1L
        var vmSizeKb = -1L
        var threads = -1
        runCatching {
            File("/proc/self/status").forEachLine { line ->
                val value = line.substringAfter(':', "").trim()
                when {
                    line.startsWith("VmRSS:") -> rssKb = value.removeSuffix("kB").trim().toLongOrNull() ?: -1L
                    line.startsWith("VmSwap:") -> swapKb = value.removeSuffix("kB").trim().toLongOrNull() ?: -1L
                    line.startsWith("VmSize:") -> vmSizeKb = value.removeSuffix("kB").trim().toLongOrNull() ?: -1L
                    line.startsWith("Threads:") -> threads = value.trim().toIntOrNull() ?: -1
                }
            }
        }

        var memAvailKb = -1L
        var swapFreeKb = -1L
        var swapTotalKb = -1L
        runCatching {
            File("/proc/meminfo").forEachLine { line ->
                val value = line.substringAfter(':').trim()
                when {
                    line.startsWith("MemAvailable:") -> memAvailKb = value.removeSuffix("kB").trim().toLongOrNull() ?: -1L
                    line.startsWith("SwapFree:") -> swapFreeKb = value.removeSuffix("kB").trim().toLongOrNull() ?: -1L
                    line.startsWith("SwapTotal:") -> swapTotalKb = value.removeSuffix("kB").trim().toLongOrNull() ?: -1L
                }
            }
        }

        // 2) Fuga RSS: tra due campioni il processo è cresciuto di troppo
        //    (scroll veloce che decodifica senza sosta, trailer + modelli).
        //    Anche se il sistema è in salute, questa curva porta al kill.
        //    Vale solo su RSS già alte (vedi RUNAWAY_MIN_RSS_KB).
        val runaway = rssKb >= RUNAWAY_MIN_RSS_KB &&
            lastRssKb >= 0L && rssKb - lastRssKb >= RUNAWAY_RSS_DELTA_KB
        if (rssKb >= 0L) lastRssKb = rssKb
        evaluatePressure(memAvailKb, swapFreeKb, swapTotalKb, rssKb, swapKb, runaway)

        // 3) Modalità rapida sotto pressione/fuga: saltiamo il breakdown PSS
        //    (Debug.getMemoryInfo sul main handler) — serve solo a log.
        val fast = pressureState != Pressure.NONE || runaway
        var totalPssKb = -1L
        var javaHeapKb = -1L
        var nativeHeapKb = -1L
        var codeKb = -1L
        var graphicsKb = -1L
        var privOtherKb = -1L
        if (!fast) {
            val mi = Debug.MemoryInfo()
            Debug.getMemoryInfo(mi)
            fun stat(key: String): Long =
                runCatching { mi.getMemoryStat(key).toLongOrNull() ?: -1L }.getOrDefault(-1L)
            totalPssKb = stat("summary.total-pss")
            javaHeapKb = stat("summary.java-heap")
            nativeHeapKb = stat("summary.native-heap")
            codeKb = stat("summary.code")
            graphicsKb = stat("summary.graphics")
            privOtherKb = stat("summary.private-other")
        }

        val rt = Runtime.getRuntime()
        val heapUsedKb = (rt.totalMemory() - rt.freeMemory()) / 1024

        emit(
            Log.INFO,
            "t=${elapsedS}s${if (fast) " fast" else ""} totalPss=${totalPssKb}kB javaHeap=${javaHeapKb}kB nativeHeap=${nativeHeapKb}kB " +
                "code=${codeKb}kB graphics=${graphicsKb}kB privOther=${privOtherKb}kB | " +
                "rtHeap=${heapUsedKb}/${rt.maxMemory() / 1024}kB nativeAlloc=${Debug.getNativeHeapAllocatedSize() / 1024}kB | " +
                "rss=${rssKb}kB swap=${swapKb}kB vmSize=${vmSizeKb}kB thr=$threads " +
                "| sysAvail=${memAvailKb}kB sysSwapFree=${swapFreeKb}kB"
        )
        return if (fast) FAST_INTERVAL_MS else INTERVAL_MS
    }

    /**
     * Macchina a stati con isteresi su MemAvailable/SwapFree.
     *
     * - HARD  → zona di kill LMK (SwapFree < 40 MB oppure MemAvailable < 90 MB):
     *           si invoca [onRelief] per liberare subito trailer + cache.
     * - SOFT  → log diagnostico senza azioni (evita trim inutili).
     * - NONE  → condizioni di recupero: la prossima ripresa è di nuovo libera.
     * Valori di parse non validi (-1) mantengono lo stato corrente.
     *
     * Le soglie SwapFree valgono solo quando esiste davvero dello swap
     * ([swapTotalKb] > 0): i dispositivi senza swap (emulatori, TV senza zRAM)
     * dichiarano SwapFree=0 a prescindere dal carico e con la regola in OR
     * restavano in HARD perennemente, bloccando trailer e preloader anche con
     * gigabyte liberi (#7 "non ci sono più trailer").
     */
    private fun evaluatePressure(
        memAvailKb: Long,
        swapFreeKb: Long,
        swapTotalKb: Long,
        rssKb: Long,
        swapKb: Long,
        runaway: Boolean = false
    ) {
        val within = { value: Long, limit: Long -> value >= 0L && value <= limit }
        val hasSwap = swapTotalKb > 0L
        val swapExhausted = hasSwap && within(swapFreeKb, HARD_PRESSURE_SWAP_KB)
        val swapSoftOk = !hasSwap || within(swapFreeKb, SOFT_PRESSURE_SWAP_KB)
        val swapReleased = !hasSwap || swapFreeKb > RELEASE_SWAP_KB
        // Pressione auto-imposta sulla nostra RSS (solo classi RAM basse):
        // se il processo supera il tetto, siamo noi il problema, non il sistema.
        // La fuga RSS (delta >= 100 MB tra due campioni) vale PER TUTTI i
        // dispositivi: la curva del kill delle 12:46 (+220 MB in 6 s) uccide
        // prima che il sistema abbia tempo di avvisare.
        val selfRssHard = totalMemKb in 1L until LOW_RAM_TOTAL_KB && rssKb >= HARD_SELF_RSS_KB
        val newState = when {
            swapExhausted || within(memAvailKb, HARD_PRESSURE_AVAIL_KB) || selfRssHard || runaway -> Pressure.HARD
            within(memAvailKb, SOFT_PRESSURE_AVAIL_KB) && swapSoftOk -> Pressure.SOFT
            memAvailKb > RELEASE_AVAIL_KB && swapReleased -> Pressure.NONE
            else -> pressureState
        }
        val old = pressureState
        pressureState = newState
        underPressure = newState == Pressure.HARD

        when {
            newState == Pressure.HARD && old != Pressure.HARD -> {
                emit(
                    Log.WARN,
                    "PRESSURE HARD avail=${memAvailKb}kB swapFree=${swapFreeKb}kB " +
                        "rss=${rssKb}kB swap=${swapKb}kB selfRss=$selfRssHard runaway=$runaway — releasing trailer+cache"
                )
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastReliefAtMs >= RELIEF_MIN_INTERVAL_MS) {
                    lastReliefAtMs = now
                    onRelief?.let { relief ->
                        runCatching { relief("system-hard avail=${memAvailKb}kB swapFree=${swapFreeKb}kB rss=${rssKb}kB") }
                            .onFailure { Log.w(TAG, "relief callback failed: ${it.javaClass.simpleName}") }
                    }
                }
            }
            newState == Pressure.HARD -> {
                // Stato HARD sostenuto: l'app è ancora viva e sta ancora
                // crescendo — si ripete il sollievo ogni RELIEF_REPEAT_MS
                // (il debounce interno impedisce di eccedere).
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastReliefAtMs >= RELIEF_REPEAT_MS) {
                    emit(Log.WARN, "PRESSURE HARD sostenuta rss=${rssKb}kB avail=${memAvailKb}kB — repeat relief")
                    lastReliefAtMs = now
                    onRelief?.let { relief ->
                        runCatching { relief("system-hard-sustained rss=${rssKb}kB avail=${memAvailKb}kB") }
                            .onFailure { Log.w(TAG, "relief callback failed: ${it.javaClass.simpleName}") }
                    }
                }
            }
            newState == Pressure.SOFT && old == Pressure.NONE -> {
                emit(Log.WARN, "PRESSURE SOFT avail=${memAvailKb}kB swapFree=${swapFreeKb}kB rss=${rssKb}kB — yielding trailer")
                onSoftRelief?.let { relief ->
                    runCatching { relief("system-soft avail=${memAvailKb}kB swapFree=${swapFreeKb}kB rss=${rssKb}kB") }
                        .onFailure { Log.w(TAG, "soft relief callback failed: ${it.javaClass.simpleName}") }
                }
            }
            newState == Pressure.NONE && old != Pressure.NONE -> {
                emit(Log.INFO, "pressure released, avail=${memAvailKb}kB swapFree=${swapFreeKb}kB")
            }
        }
    }
}
