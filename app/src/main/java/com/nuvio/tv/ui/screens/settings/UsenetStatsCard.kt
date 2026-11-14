package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Movie
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.usenet.IndexerCounts
import com.nuvio.tv.core.usenet.ProviderCounts
import com.nuvio.tv.core.usenet.StatsContent
import com.nuvio.tv.core.usenet.StatsPeriod
import com.nuvio.tv.core.usenet.UsenetIndexer
import com.nuvio.tv.core.usenet.UsenetProvider
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioTheme
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** How a period and a content filter are named. */
private val StatsPeriod.label: Int
    get() = when (this) {
        StatsPeriod.LAST_7_DAYS -> R.string.usenet_stats_period_7
        StatsPeriod.LAST_30_DAYS -> R.string.usenet_stats_period_30
        StatsPeriod.ALL_TIME -> R.string.usenet_stats_period_all
    }

private val StatsContent.label: Int
    get() = when (this) {
        StatsContent.ALL -> R.string.usenet_stats_content_all
        StatsContent.MOVIES -> R.string.usenet_stats_content_movies
        StatsContent.SHOWS -> R.string.usenet_stats_content_shows
    }

/**
 * Source statistics under the startup diagnostics: the two filters, bars that compare the sources,
 * a card per indexer and per provider, and a reset. Separate lazy items, like the diagnostics, so
 * the remote reaches each one.
 */
internal fun LazyListScope.usenetStatsCardItems(
    indexers: List<UsenetIndexer>,
    providers: List<UsenetProvider>,
    stats: UsenetStatsUi,
    onPeriod: (StatsPeriod) -> Unit,
    onContent: (StatsContent) -> Unit,
    onReset: () -> Unit
) {
    if (indexers.isEmpty() && providers.isEmpty()) return
    item(key = "usenet_stats_period") {
        StatsChoiceRow(R.string.usenet_stats_period, R.string.usenet_stats_period_desc, Icons.Default.DateRange,
            StatsPeriod.entries, stats.period, { it.label }, onPeriod)
    }
    item(key = "usenet_stats_content") {
        StatsChoiceRow(R.string.usenet_stats_content, R.string.usenet_stats_content_desc, Icons.Default.Movie,
            StatsContent.entries, stats.content, { it.label }, onContent)
    }
    fun bars(value: (IndexerCounts) -> Int) =
        statsBars(indexers.map { StatsBarSource(it.id, it.name, stats.indexers[it.id]?.counts?.let(value)?.toLong() ?: 0) })
    val played = bars { it.played }
    if (played.isNotEmpty()) item(key = "usenet_stats_bars_played") { StatsBarsCard(R.string.usenet_stats_played_chart, played) }
    val grabs = bars { it.grabs }
    if (grabs.isNotEmpty()) item(key = "usenet_stats_bars_grabs") { StatsBarsCard(R.string.usenet_stats_grabs, grabs) }
    val served = statsBars(providers.map {
        StatsBarSource(it.id, it.name, stats.providers[it.id]?.counts?.articles ?: 0)
    })
    if (served.isNotEmpty()) item(key = "usenet_stats_bars_articles") { StatsBarsCard(R.string.usenet_stats_articles, served) }
    indexers.forEach { indexer ->
        item(key = "usenet_stats_indexer_${indexer.id}") {
            IndexerStatsCard(indexer.name, stats.indexers[indexer.id] ?: IndexerStatsUi(), stats.period)
        }
    }
    providers.forEach { provider ->
        item(key = "usenet_stats_provider_${provider.id}") {
            ProviderStatsCard(provider.name, stats.providers[provider.id] ?: ProviderStatsUi(), stats.period)
        }
    }
    item(key = "usenet_stats_reset") { StatsResetRow(onReset) }
}

@Composable
private fun <T> StatsChoiceRow(title: Int, description: Int, icon: androidx.compose.ui.graphics.vector.ImageVector,
    options: List<T>, selected: T, label: (T) -> Int, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    SettingsActionRow(title = stringResource(title), subtitle = stringResource(description),
        value = stringResource(label(selected)), onClick = { open = true }, leadingIcon = icon)
    if (open) SettingsSingleChoiceDialog(title = stringResource(title),
        options = options.map { SettingsPickerOption(it, stringResource(label(it))) },
        selectedValue = selected, onOptionSelected = { onSelect(it); open = false }, onDismiss = { open = false })
}

@Composable
private fun StatsResetRow(onReset: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    SettingsActionRow(title = stringResource(R.string.usenet_stats_reset), subtitle = stringResource(R.string.usenet_stats_reset_desc),
        onClick = { confirm = true }, leadingIcon = Icons.Default.DeleteSweep)
    if (confirm) NuvioDialog(title = stringResource(R.string.usenet_stats_reset),
        subtitle = stringResource(R.string.usenet_stats_reset_confirm), onDismiss = { confirm = false }) {
        SettingsDialogActionRow {
            SettingsDialogActionButton(text = stringResource(R.string.action_cancel), onClick = { confirm = false })
            SettingsDialogActionButton(text = stringResource(R.string.usenet_stats_reset_action), onClick = {
                onReset(); confirm = false
            })
        }
    }
}

@Composable
private fun StatsHeader(title: String) {
    Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, color = NuvioTheme.colors.Primary,
        maxLines = 1, overflow = TextOverflow.Ellipsis)
    Spacer(Modifier.height(NuvioTheme.spacing.xs))
}

@Composable
private fun IndexerStatsCard(name: String, stats: IndexerStatsUi, period: StatsPeriod) = DiagnosticsSectionCard {
    StatsHeader(name)
    val counts = stats.counts
    if (counts == null) {
        Text(stringResource(R.string.usenet_stats_no_data), style = MaterialTheme.typography.bodySmall,
            color = NuvioTheme.colors.TextSecondary)
        return@DiagnosticsSectionCard
    }
    val today = stats.today
    val searchParts = listOfNotNull(
        counts.failedSearches.takeIf { it > 0 }?.let { stringResource(R.string.usenet_stats_failed, it) },
        counts.pausedSearches.takeIf { it > 0 }?.let { stringResource(R.string.usenet_stats_paused, it) })
    DiagnosticRow(stringResource(R.string.usenet_stats_searches),
        if (searchParts.isEmpty()) "${counts.searches}" else "${counts.searches} (${searchParts.joinToString(", ")})")
    counts.searchYield()?.let { yield ->
        DiagnosticRow(stringResource(R.string.usenet_stats_with_results), pluralStringResource(
            R.plurals.usenet_stats_releases_average, yield.averageReleases, yield.percent, yield.averageReleases))
    }
    DiagnosticRow(stringResource(R.string.usenet_stats_requests), today?.requests?.takeIf { it > 0 }
        ?.let { stringResource(R.string.usenet_stats_with_today, counts.requests, it) } ?: "${counts.requests}")
    counts.averageResponseMs()?.let {
        DiagnosticRow(stringResource(R.string.usenet_stats_response), stringResource(R.string.usenet_stats_ms, it))
    }
    DiagnosticRow(stringResource(R.string.usenet_stats_limited), "${counts.limitedRequests}")
    DiagnosticRow(stringResource(R.string.usenet_stats_failed_requests), "${counts.failedRequests}")
    DiagnosticRow(stringResource(R.string.usenet_stats_grabs), today?.grabs?.takeIf { it > 0 }
        ?.let { stringResource(R.string.usenet_stats_with_today, counts.grabs, it) } ?: "${counts.grabs}")
    DiagnosticRow(stringResource(R.string.usenet_stats_outcomes), "${counts.played} / ${counts.unavailable}")
    if (period == StatsPeriod.ALL_TIME) stats.since?.let {
        DiagnosticRow(stringResource(R.string.usenet_stats_since), DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)))
    }
}

@Composable
private fun ProviderStatsCard(name: String, stats: ProviderStatsUi, period: StatsPeriod) = DiagnosticsSectionCard {
    StatsHeader(name)
    val counts = stats.counts
    if (counts == null) {
        Text(stringResource(R.string.usenet_stats_no_data), style = MaterialTheme.typography.bodySmall,
            color = NuvioTheme.colors.TextSecondary)
        return@DiagnosticsSectionCard
    }
    DiagnosticRow(stringResource(R.string.usenet_stats_articles), "${counts.articles}")
    counts.completion()?.let { percent ->
        DiagnosticRow(stringResource(R.string.usenet_stats_completion),
            stringResource(R.string.usenet_stats_completion_value, percent, counts.missing))
    }
    DiagnosticRow(stringResource(R.string.usenet_stats_errors), "${counts.errors}")
    DiagnosticRow(stringResource(R.string.usenet_stats_downloaded),
        stringResource(R.string.usenet_stats_gib, counts.bytes / (1024.0 * 1024 * 1024)))
    counts.averageFirstByteMs()?.let {
        DiagnosticRow(stringResource(R.string.usenet_stats_first_byte), stringResource(R.string.usenet_stats_ms, it))
    }
    DiagnosticRow(stringResource(R.string.usenet_stats_sessions), if (counts.failedOpens > 0) {
        stringResource(R.string.usenet_stats_sessions_failed, counts.sessions, counts.failedOpens)
    } else "${counts.sessions}")
    if (period == StatsPeriod.ALL_TIME) stats.since?.let {
        DiagnosticRow(stringResource(R.string.usenet_stats_since), DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)))
    }
}

@Composable
private fun StatsBarsCard(title: Int, bars: List<StatsBar>) = DiagnosticsSectionCard {
    StatsHeader(stringResource(title))
    val track = NuvioTheme.colors.TextTertiary.copy(alpha = 0.25f)
    val fill = NuvioTheme.colors.Primary
    bars.forEach { bar ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(bar.name, style = MaterialTheme.typography.bodySmall, color = NuvioTheme.colors.TextSecondary,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(170.dp))
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(5.dp)).background(track)) {
                if (bar.fraction > 0f) Box(Modifier.fillMaxHeight().fillMaxWidth(bar.fraction).background(fill))
            }
            Spacer(Modifier.width(10.dp))
            Text("${bar.value}", style = MaterialTheme.typography.bodySmall, color = NuvioTheme.colors.TextPrimary,
                textAlign = TextAlign.End, maxLines = 1, modifier = Modifier.width(56.dp))
        }
    }
}

/** How often an indexer's answered searches found something, and how much on average when they did. */
internal data class SearchYield(val percent: Int, val averageReleases: Int)

/** Null without an answered search: failed and paused searches say nothing about what it finds. */
internal fun IndexerCounts.searchYield(): SearchYield? {
    val answeredSearches = searches - failedSearches - pausedSearches
    if (answeredSearches <= 0) return null
    val withResults = (answeredSearches - emptySearches).coerceIn(0, answeredSearches)
    return SearchYield((withResults * 100.0 / answeredSearches).roundToInt(),
        if (withResults == 0) 0 else (releases.toDouble() / withResults).roundToInt())
}

/** Null when no request was answered. */
internal fun IndexerCounts.averageResponseMs(): Long? =
    if (answered <= 0) null else (responseMs.toDouble() / answered).roundToInt().toLong()

/**
 * The share of the articles asked of a provider that it had, to one decimal; null before it was
 * asked for any. Errors are left out: they say nothing about what it holds.
 */
internal fun ProviderCounts.completion(): Double? {
    val asked = articles + missing
    if (asked <= 0) return null
    return (articles * 1000.0 / asked).roundToLong() / 10.0
}

/** Null when no session measured one. */
internal fun ProviderCounts.averageFirstByteMs(): Long? =
    if (ttfbSessions <= 0) null else (ttfbMs.toDouble() / ttfbSessions).roundToLong()

internal data class StatsBarSource(val id: String, val name: String, val value: Long)

internal data class StatsBar(val id: String, val name: String, val value: Long, val fraction: Float)

/**
 * One bar per source, largest first, as a share of the largest. Empty when there is nothing to
 * compare: fewer than two sources, or no source with a count.
 */
internal fun statsBars(sources: List<StatsBarSource>): List<StatsBar> {
    val max = sources.maxOfOrNull { it.value } ?: 0L
    if (sources.size < 2 || max <= 0) return emptyList()
    return sources.sortedWith(compareByDescending<StatsBarSource> { it.value }.thenBy { it.name.lowercase() })
        .map { StatsBar(it.id, it.name, it.value, (it.value.coerceAtLeast(0).toDouble() / max).toFloat()) }
}
