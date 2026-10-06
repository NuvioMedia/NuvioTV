package com.nuvio.tv.ui.screens.player.autosync.bubble

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** What an AutoSync message means for the bubble: still working, or how it ended. */
internal enum class AutoSyncBubbleKind { Working, Success, Failure }

/**
 * One AutoSync message as the bubble shows it. Messages of one run share [session], so the
 * bubble that shows "Analyzing…" is the one that turns into the check mark or the red card.
 */
internal data class AutoSyncBubbleMessage(
    val id: Long,
    val session: Long,
    val kind: AutoSyncBubbleKind,
    val headline: String,
    val detail: String?,
)

/**
 * The optional frosted bubble that replaces AutoSync's plain toasts. On by default. Without a
 * player on screen to draw it (or with the setting off) [post] returns false, so the caller
 * shows its plain toast instead.
 */
internal object AutoSyncBubbleToasts {
    private const val PREFS_NAME = "nuvio_autosync_bubble_settings"
    private const val KEY_ENABLED = "enabled"

    private val lock = Any()
    @Volatile private var initialized = false

    private val _enabled = MutableStateFlow(true)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _current = MutableStateFlow<AutoSyncBubbleMessage?>(null)
    val current: StateFlow<AutoSyncBubbleMessage?> = _current.asStateFlow()

    private val hosts = AtomicInteger(0)
    private val nextId = AtomicLong(0L)
    /** The run whose bubble is fading away; a message arriving now starts a fresh bubble. */
    @Volatile private var leavingSession = -1L

    fun ensureLoaded(context: Context) {
        if (initialized) return
        synchronized(lock) {
            if (initialized) return
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            _enabled.value = prefs.getBoolean(KEY_ENABLED, true)
            initialized = true
        }
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        ensureLoaded(context)
        _enabled.value = enabled
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
        if (!enabled) _current.value = null
    }

    /** Shows [text] (an "Auto Sync • headline • detail" toast string) in the bubble, if it can. */
    fun post(kind: AutoSyncBubbleKind, text: String): Boolean {
        if (!_enabled.value || hosts.get() <= 0) return false
        val (headline, detail) = split(kind, text)
        val id = nextId.incrementAndGet()
        _current.update { previous ->
            // A run continues while the bubble is still working; anything else (a result, or a
            // bubble already fading away) starts a new bubble.
            val session = if (
                previous != null && previous.kind == AutoSyncBubbleKind.Working &&
                previous.session != leavingSession
            ) previous.session else id
            AutoSyncBubbleMessage(id, session, kind, headline, detail)
        }
        return true
    }

    /** Called by the bubble as it starts fading [session] away, so later messages do not join it. */
    fun leaving(session: Long) {
        leavingSession = session
    }

    /** Called by the bubble once it has finished animating [id] away. */
    fun finished(id: Long) {
        _current.update { if (it?.id == id) null else it }
    }

    fun attachHost() {
        hosts.incrementAndGet()
    }

    fun detachHost() {
        if (hosts.decrementAndGet() <= 0) {
            hosts.set(0)
            _current.value = null
        }
    }

    /**
     * AutoSync's toast strings read "Auto Sync • headline • detail" in every language. The bubble
     * already says it is AutoSync, so it drops that part. While working it shows only what is
     * happening now ("syncing to the audio instead…"); a result keeps the rest as its explanation.
     */
    internal fun split(kind: AutoSyncBubbleKind, text: String): Pair<String, String?> {
        val parts = text.split(" • ").map { it.trim() }.filter { it.isNotEmpty() }
        val body = if (parts.size > 1) parts.drop(1) else parts.ifEmpty { listOf(text) }
        return when (kind) {
            AutoSyncBubbleKind.Working -> body.last().capitalized() to null
            else -> body.first().capitalized() to
                body.drop(1).joinToString(" · ").takeIf { it.isNotEmpty() }?.capitalized()
        }
    }

    private fun String.capitalized(): String = replaceFirstChar { it.uppercaseChar() }
}

private val autoSyncMessageHandler = Handler(Looper.getMainLooper())

/**
 * Shows an AutoSync message in the frosted bubble when it is turned on and the player is on screen,
 * else as the plain toast it always was. Safe to call from any thread.
 */
internal fun showAutoSyncMessage(
    context: Context,
    kind: AutoSyncBubbleKind,
    message: String,
    duration: Int = Toast.LENGTH_SHORT,
) {
    val appContext = context.applicationContext
    AutoSyncBubbleToasts.ensureLoaded(appContext)
    autoSyncMessageHandler.post {
        if (!AutoSyncBubbleToasts.post(kind, message)) {
            Toast.makeText(appContext, message, duration).show()
        }
    }
}
