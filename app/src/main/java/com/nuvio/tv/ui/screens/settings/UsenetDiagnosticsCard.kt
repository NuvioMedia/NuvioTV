package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.usenet.UsenetStartupDiagnostics
import com.nuvio.tv.ui.theme.NuvioTheme
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToLong

/** Uses the same dense, focusable cards and label/value rows as DV diagnostics.
 * Separate lazy items let the remote reach every section without clipping. */
internal fun LazyListScope.usenetDiagnosticsCardItems() {
    item(key = "usenet_startup_summary") { UsenetDiagnosticsCard(0) }
    item(key = "usenet_startup_engine") { UsenetDiagnosticsCard(1) }
    item(key = "usenet_startup_cache") { UsenetDiagnosticsCard(2) }
}

@Composable
internal fun UsenetDiagnosticsCard(section: Int, reportOverride: String? = null) {
    val latest by UsenetStartupDiagnostics.latest.collectAsStateWithLifecycle()
    val source = reportOverride ?: latest
    val report = remember(source) { runCatching { JSONObject(source) }.getOrNull() }
    if (report == null) {
        if (section == 0) DiagnosticsSectionCard {
            UsenetDiagnosticHeader(stringResource(R.string.usenet_last_startup))
            Text(stringResource(R.string.usenet_diag_empty), style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextSecondary)
        }
        return
    }
    val marks = report.optJSONObject("marksMs") ?: JSONObject()
    val engine = report.optJSONObject("engine") ?: JSONObject()
    val engineMarks = engine.optJSONObject("marksMs") ?: JSONObject()
    fun ms(value: Double?): String = value?.takeIf { it.isFinite() && it >= 0 }?.let { "${it.roundToLong()} ms" } ?: "—"
    fun value(objectValue: JSONObject, key: String): Double? =
        if (objectValue.has(key)) objectValue.optDouble(key).takeIf { it.isFinite() } else null
    fun duration(objectValue: JSONObject, end: String, start: String? = null): String {
        val endMs = value(objectValue, end) ?: return "—"
        val startMs = if (start == null) 0.0 else value(objectValue, start) ?: return "—"
        return ms(endMs - startMs)
    }
    DiagnosticsSectionCard {
        when (section) {
            0 -> {
                UsenetDiagnosticHeader(stringResource(R.string.usenet_last_startup))
                DiagnosticRow(stringResource(R.string.diag_label_when), report.optLong("recordedAtMs").takeIf { it > 0 }?.let {
                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(it))
                } ?: "—")
                DiagnosticRow(stringResource(R.string.usenet_fast_mkv), stringResource(
                    if (report.optBoolean("fastMkvStartup")) R.string.diag_value_on else R.string.diag_value_off))
                DiagnosticRow(stringResource(R.string.usenet_fast_nzb), stringResource(
                    if (report.optBoolean("fastNzbFetch", true)) R.string.diag_value_on else R.string.diag_value_off))
                DiagnosticRow(stringResource(R.string.usenet_diag_total), duration(marks, "first_frame"), NuvioTheme.colors.Primary)
                DiagnosticRow(stringResource(R.string.usenet_diag_boot), duration(marks, "engine_ready", "resolve_lock_acquired"))
                DiagnosticRow(stringResource(R.string.usenet_diag_session), duration(marks, "session_response", "session_request"))
                DiagnosticRow(stringResource(R.string.usenet_diag_handoff), duration(marks, "prepare", "resolved"))
                DiagnosticRow(stringResource(R.string.usenet_diag_player), duration(marks, "first_frame", "prepare"))
                DiagnosticRow(stringResource(R.string.usenet_diag_decoder), duration(marks, "decoder_init_duration"))
            }
            1 -> {
                UsenetDiagnosticHeader(stringResource(R.string.usenet_diag_engine))
                DiagnosticRow(stringResource(R.string.usenet_diag_prefetch), stringResource(when {
                    engineMarks.has("warmup_skipped_connection_limit") -> R.string.usenet_diag_limited_connections
                    engineMarks.has("head_article_ready") && engineMarks.has("tail_article_ready") -> R.string.usenet_diag_head_tail_ready
                    engineMarks.has("warmup_started") -> R.string.usenet_diag_prefetch_partial
                    !report.optBoolean("fastMkvStartup") -> R.string.diag_value_off
                    else -> R.string.usenet_diag_not_applicable
                }))
                DiagnosticRow(stringResource(R.string.usenet_diag_nzb), duration(engineMarks, "nzb_loaded", "pool_created"))
                DiagnosticRow(stringResource(R.string.usenet_diag_selection), duration(engineMarks, "content_selected", "nzb_loaded"))
                DiagnosticRow(stringResource(R.string.usenet_diag_archive), ms(value(engine, "archiveDiscoveryMs")))
                val ranges = engine.optJSONArray("ranges")
                val waits = (0 until (ranges?.length() ?: 0)).mapNotNull { i ->
                    val range = ranges!!.optJSONObject(i) ?: return@mapNotNull null
                    val first = value(range, "firstByteMs")?.takeIf { it >= 0 } ?: return@mapNotNull null
                    first - (value(range, "startMs") ?: return@mapNotNull null)
                }
                DiagnosticRow(stringResource(R.string.usenet_diag_range_wait), ms(waits.maxOrNull()))
                DiagnosticRow(stringResource(R.string.usenet_diag_head_ready), duration(engineMarks, "head_article_ready", "warmup_started"))
                DiagnosticRow(stringResource(R.string.usenet_diag_tail_ready), duration(engineMarks, "tail_article_ready", "warmup_started"))
                Text(stringResource(R.string.usenet_diag_overlap), style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextTertiary)
            }
            2 -> {
                UsenetDiagnosticHeader(stringResource(R.string.usenet_diag_cache))
                val nzb = engine.optJSONObject("nzbCache")
                val lookup = when (nzb?.optString("lookup")) {
                    "hit" -> stringResource(R.string.usenet_diag_nzb_hit)
                    "disabled" -> stringResource(R.string.diag_value_off)
                    "miss" -> stringResource(when (nzb?.optString("reason")) {
                        "expired" -> R.string.usenet_diag_nzb_expired
                        "invalid" -> R.string.usenet_diag_nzb_invalid
                        "disk_error" -> R.string.usenet_diag_nzb_disk_error
                        else -> R.string.usenet_diag_nzb_miss
                    })
                    else -> "—"
                }
                DiagnosticRow(stringResource(R.string.usenet_diag_nzb_cache), lookup)
                val write = when (nzb?.optString("write")) {
                    "saved" -> stringResource(R.string.usenet_diag_nzb_saved)
                    "oversized" -> stringResource(R.string.usenet_diag_nzb_oversized)
                    "cancelled" -> stringResource(R.string.usenet_diag_nzb_cancelled)
                    "invalid" -> stringResource(R.string.usenet_diag_nzb_not_saved)
                    "disk_error" -> stringResource(R.string.usenet_diag_nzb_disk_error)
                    "busy" -> stringResource(R.string.usenet_diag_nzb_busy)
                    else -> "—"
                }
                DiagnosticRow(stringResource(R.string.usenet_diag_nzb_write), write)
                DiagnosticRow(stringResource(R.string.usenet_diag_nzb_size),
                    nzb?.takeIf { it.optString("lookup") == "hit" || it.optString("write") == "saved" }
                        ?.let { String.format(Locale.getDefault(), "%.1f MiB", it.optLong("bytes") / 1048576.0) } ?: "—")
                val store = engine.optJSONObject("store") ?: JSONObject()
                fun count(key: String): String = if (store.has(key)) store.optLong(key).toString() else "—"
                DiagnosticRow(stringResource(R.string.usenet_diag_hits), count("cacheHits"))
                DiagnosticRow(stringResource(R.string.usenet_diag_joins), count("sharedJoins"))
                DiagnosticRow(stringResource(R.string.usenet_diag_downloads), count("downloads"))
                DiagnosticRow(stringResource(R.string.usenet_diag_cancelled), count("cancellations"))
                DiagnosticRow(stringResource(R.string.usenet_diag_memory), value(engine, "articleSlabsBytes")?.let {
                    String.format(Locale.getDefault(), "%.1f MiB", it / 1048576)
                } ?: "—")
            }
        }
    }
}

@Composable
private fun UsenetDiagnosticHeader(title: String) {
    Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, color = NuvioTheme.colors.Primary)
    Spacer(Modifier.height(NuvioTheme.spacing.xs))
}
