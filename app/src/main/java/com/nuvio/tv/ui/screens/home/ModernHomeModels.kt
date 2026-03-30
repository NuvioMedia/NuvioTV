package com.nuvio.tv.ui.screens.home

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.ui.util.localizeEpisodeTitle
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.formatContinueWatchingProgressLabel

internal val YEAR_REGEX = Regex("""\b(19|20)\d{2}\b""")
internal const val MODERN_HERO_TEXT_WIDTH_FRACTION = 0.42f
internal const val MODERN_HERO_MEDIA_WIDTH_FRACTION = 0.72f
internal const val MODERN_TRAILER_OVERSCAN_ZOOM = 1.35f
internal const val MODERN_HERO_FOCUS_DEBOUNCE_MS = 90L
internal val MODERN_ROW_HEADER_FOCUS_INSET = 40.dp
internal val MODERN_LANDSCAPE_LOGO_GRADIENT = Brush.verticalGradient(
    colorStops = arrayOf(
        0.0f to Color.Transparent,
        0.58f to Color.Transparent,
        1.0f to Color.Black.copy(alpha = 0.75f)
    )
)

@Immutable
data class HeroPreview(
    val title: String,
    val logo: String?,
    val description: String?,
    val contentTypeText: String?,
    val isSeries: Boolean = false,
    val yearText: String?,
    val runtimeText: String? = null,
    val secondaryHighlightText: String? = null,
    val imdbText: String?,
    val ageRatingText: String? = null,
    val statusText: String? = null,
    val countryText: String? = null,
    val languageText: String? = null,
    val genres: List<String>,
    val poster: String?,
    val backdrop: String?,
    val imageUrl: String?,
    /** Snapshot of the backdrop URL captured before TMDB enrichment.
     *  Survives cache rebuilds so landscape cards keep their original art
     *  even after navigation away and back. */
    val frozenBackdropUrl: String? = null,
    /** Same idea for the logo URL. */
    val frozenLogoUrl: String? = null
)

@Immutable
sealed class ModernPayload {
    data class ContinueWatching(val item: ContinueWatchingItem) : ModernPayload()
    data class Catalog(
        val focusKey: String,
        val itemId: String,
        val itemType: String,
        val addonBaseUrl: String,
        val trailerTitle: String,
        val trailerReleaseInfo: String?,
        val trailerApiType: String
    ) : ModernPayload()
}

@Immutable
internal data class FocusedCatalogSelection(
    val focusKey: String,
    val payload: ModernPayload.Catalog
)

@Immutable
data class ModernCarouselItem(
    val key: String,
    val title: String,
    val subtitle: String?,
    val imageUrl: String?,
    val heroPreview: HeroPreview,
    val payload: ModernPayload,
    val metaPreview: MetaPreview? = null
)

@Immutable
data class HeroCarouselRow(
    val key: String,
    val title: String,
    val globalRowIndex: Int,
    val items: List<ModernCarouselItem>,
    val catalogId: String? = null,
    val addonId: String? = null,
    val apiType: String? = null,
    val supportsSkip: Boolean = false,
    val hasMore: Boolean = false,
    val isLoading: Boolean = false
)

@Immutable
data class CarouselRowLookups(
    val rowIndexByKey: Map<String, Int>,
    val rowByKey: Map<String, HeroCarouselRow>,
    val rowKeyByGlobalRowIndex: Map<Int, String>,
    val firstHeroPreviewByRow: Map<String, HeroPreview>,
    val fallbackBackdropByRow: Map<String, String>,
    val activeRowKeys: Set<String>,
    val activeItemKeysByRow: Map<String, Set<String>>,
    val activeCatalogItemIds: Set<String>
)

@Immutable
data class ModernHomePresentationState(
    val rows: List<HeroCarouselRow> = emptyList(),
    val lookups: CarouselRowLookups = CarouselRowLookups(
        rowIndexByKey = emptyMap(),
        rowByKey = emptyMap(),
        rowKeyByGlobalRowIndex = emptyMap(),
        firstHeroPreviewByRow = emptyMap(),
        fallbackBackdropByRow = emptyMap(),
        activeRowKeys = emptySet(),
        activeItemKeysByRow = emptyMap(),
        activeCatalogItemIds = emptySet()
    )
)

@Immutable
internal data class ModernHeroSceneState(
    val heroBackdrop: String?,
    val preview: HeroPreview?,
    val enrichmentActive: Boolean,
    val shouldPlayTrailer: Boolean,
    val trailerFirstFrameRendered: Boolean,
    val trailerUrl: String?,
    val trailerAudioUrl: String?,
    val trailerMuted: Boolean,
    val fullScreenBackdrop: Boolean
)

internal data class ModernCatalogRowBuildCacheEntry(
    val source: CatalogRow,
    val useLandscapePosters: Boolean,
    val showCatalogTypeSuffix: Boolean,
    val mappedRow: HeroCarouselRow
)

@Stable
internal class ModernHomeUiCaches {
    val focusedItemByRow = mutableMapOf<String, Int>()
    val itemFocusRequesters = mutableMapOf<String, MutableMap<String, FocusRequester>>()
    val rowListStates = mutableMapOf<String, LazyListState>()
    val loadMoreRequestedTotals = mutableMapOf<String, Int>()

    fun requesterFor(rowKey: String, itemKey: String): FocusRequester {
        val byIndex = itemFocusRequesters.getOrPut(rowKey) { mutableMapOf() }
        return byIndex.getOrPut(itemKey) { FocusRequester() }
    }
}

@Stable
internal class ModernCarouselRowBuildCache {
    var continueWatchingItems: List<ContinueWatchingItem> = emptyList()
    var continueWatchingTitle: String = ""
    var continueWatchingAirsDateTemplate: String = ""
    var continueWatchingUpcomingLabel: String = ""
    var continueWatchingUseLandscapePosters: Boolean = false
    var continueWatchingRow: HeroCarouselRow? = null
    val catalogRows = mutableMapOf<String, ModernCatalogRowBuildCacheEntry>()
    // per-item cache: rowKey -> (itemId -> cached carousel item + source MetaPreview)
    val catalogItemCache = mutableMapOf<String, MutableMap<String, CachedCarouselItem>>()
}

internal data class CachedCarouselItem(
    val source: MetaPreview,
    val useLandscapePosters: Boolean,
    val showFullReleaseDate: Boolean,
    val carouselItem: ModernCarouselItem
)

@Immutable
internal data class ModernCatalogCardMetrics(
    val width: androidx.compose.ui.unit.Dp,
    val height: androidx.compose.ui.unit.Dp
)

internal fun ModernCarouselItem.catalogCardMetrics(
    useLandscapePosters: Boolean,
    portraitCardWidth: androidx.compose.ui.unit.Dp,
    portraitCardHeight: androidx.compose.ui.unit.Dp,
    landscapeCardWidth: androidx.compose.ui.unit.Dp,
    landscapeCardHeight: androidx.compose.ui.unit.Dp
): ModernCatalogCardMetrics {
    if (payload !is ModernPayload.Catalog) {
        return ModernCatalogCardMetrics(
            width = if (useLandscapePosters) landscapeCardWidth else portraitCardWidth,
            height = if (useLandscapePosters) landscapeCardHeight else portraitCardHeight
        )
    }

    if (useLandscapePosters) {
        return ModernCatalogCardMetrics(
            width = landscapeCardWidth,
            height = landscapeCardHeight
        )
    }

    return when (metaPreview?.posterShape ?: PosterShape.POSTER) {
        PosterShape.LANDSCAPE -> ModernCatalogCardMetrics(
            width = landscapeCardWidth,
            height = landscapeCardHeight
        )

        PosterShape.SQUARE -> ModernCatalogCardMetrics(
            width = portraitCardHeight,
            height = portraitCardHeight
        )

        PosterShape.POSTER -> ModernCatalogCardMetrics(
            width = portraitCardWidth,
            height = portraitCardHeight
        )
    }
}


internal fun buildContinueWatchingItem(
    item: ContinueWatchingItem,
    useLandscapePosters: Boolean,
    airsDateTemplate: String,
    upcomingLabel: String,
    context: android.content.Context
): ModernCarouselItem {
    val secondaryHighlightText = when (item) {
        is ContinueWatchingItem.InProgress -> {
            val progress = item.progress
            formatContinueWatchingProgressLabel(
                progress = progress,
                resumeLabel = context.getString(R.string.cw_resume),
                percentWatchedLabel = context.getString(R.string.cw_percent_watched),
                hoursMinLeftLabel = context.getString(R.string.cw_hours_min_left),
                minLeftLabel = context.getString(R.string.cw_min_left)
            )
        }
        is ContinueWatchingItem.NextUp -> {
            if (item.info.isReleaseAlert) {
                if (item.info.isNewSeasonRelease) {
                    context.getString(R.string.cw_new_season)
                } else {
                    context.getString(R.string.cw_new_episode)
                }
            } else if (!item.info.hasAired) {
                item.info.airDateLabel?.let { context.getString(R.string.cw_airs_date, it) }
                    ?: context.getString(R.string.cw_upcoming)
            } else {
                context.getString(R.string.cw_next_up)
            }
        }
    }.uppercase()

    val heroPreview = when (item) {
        is ContinueWatchingItem.InProgress -> {
            val isSeries = isSeriesType(item.progress.contentType)
            val episodeCode = item.progress.episodeDisplayString
            val episodeTitle = item.progress.episodeTitle?.takeIf { it.isNotBlank() }?.localizeEpisodeTitle(context)
            val episodeLabel = when {
                isSeries && episodeCode != null && episodeTitle != null -> "$episodeCode · $episodeTitle"
                isSeries && episodeCode != null -> episodeCode
                isSeries && episodeTitle != null -> episodeTitle
                else -> item.progress.contentType.replaceFirstChar { ch -> ch.uppercase() }
            }
            HeroPreview(
                title = item.progress.name,
                logo = item.progress.logo,
                description = item.episodeDescription ?: item.progress.episodeTitle?.localizeEpisodeTitle(context),
                contentTypeText = episodeLabel,
                isSeries = isSeries,
                yearText = extractYear(item.releaseInfo),
                secondaryHighlightText = secondaryHighlightText,
                imdbText = item.episodeImdbRating?.let { String.format("%.1f", it) },
                genres = item.genres,
                poster = item.progress.poster,
                backdrop = item.progress.backdrop,
                imageUrl = if (useLandscapePosters) {
                    item.progress.backdrop ?: item.progress.poster
                } else {
                    item.progress.poster ?: item.progress.backdrop
                }
            )
        }
        is ContinueWatchingItem.NextUp -> {
            val episodeCode = "S${item.info.season}E${item.info.episode}"
            val episodeTitle = item.info.episodeTitle?.takeIf { it.isNotBlank() }?.localizeEpisodeTitle(context)
            val episodeLabel = if (episodeTitle != null) "$episodeCode · $episodeTitle" else episodeCode
            HeroPreview(
                title = item.info.name,
                logo = item.info.logo,
                description = item.info.episodeDescription
                    ?: item.info.episodeTitle?.localizeEpisodeTitle(context)
                    ?: item.info.airDateLabel?.let { airsDateTemplate.format(it) },
                contentTypeText = episodeLabel,
                isSeries = true,
                yearText = extractYear(item.info.releaseInfo),
                secondaryHighlightText = secondaryHighlightText,
                imdbText = item.info.imdbRating?.let { String.format("%.1f", it) },
                genres = item.info.genres,
                poster = item.info.poster,
                backdrop = item.info.backdrop,
                imageUrl = if (useLandscapePosters) {
                    firstNonBlank(item.info.backdrop, item.info.poster, item.info.thumbnail)
                } else {
                    firstNonBlank(item.info.poster, item.info.backdrop, item.info.thumbnail)
                }
            )
        }
    }

    val imageUrl = when (item) {
        is ContinueWatchingItem.InProgress -> if (useLandscapePosters) {
            if (isSeriesType(item.progress.contentType)) {
                firstNonBlank(item.episodeThumbnail, item.progress.poster, item.progress.backdrop)
            } else {
                firstNonBlank(item.progress.backdrop, item.progress.poster)
            }
        } else {
            if (isSeriesType(item.progress.contentType)) {
                firstNonBlank(heroPreview.poster, item.progress.poster, item.progress.backdrop)
            } else {
                firstNonBlank(item.progress.poster, item.progress.backdrop)
            }
        }
        is ContinueWatchingItem.NextUp -> if (useLandscapePosters) {
            if (item.info.hasAired) {
                firstNonBlank(item.info.thumbnail, item.info.poster, item.info.backdrop)
            } else {
                firstNonBlank(item.info.backdrop, item.info.poster, item.info.thumbnail)
            }
        } else {
            firstNonBlank(item.info.poster, item.info.backdrop, item.info.thumbnail)
        }
    }

    return ModernCarouselItem(
        key = continueWatchingItemKey(item),
        title = when (item) {
            is ContinueWatchingItem.InProgress -> item.progress.name
            is ContinueWatchingItem.NextUp -> item.info.name
        },
        subtitle = when (item) {
            is ContinueWatchingItem.InProgress -> item.progress.episodeDisplayString ?: item.progress.episodeTitle
            is ContinueWatchingItem.NextUp -> {
                val code = "S${item.info.season}E${item.info.episode}"
                if (item.info.hasAired) {
                    code
                } else {
                    item.info.airDateLabel?.let { "$code • ${airsDateTemplate.format(it)}" } ?: "$code • $upcomingLabel"
                }
            }
        },
        imageUrl = imageUrl,
        heroPreview = heroPreview.copy(imageUrl = imageUrl ?: heroPreview.imageUrl),
        payload = ModernPayload.ContinueWatching(item)
    )
}

internal fun buildCatalogItem(
    item: MetaPreview,
    row: CatalogRow,
    useLandscapePosters: Boolean,
    occurrence: Int,
    strTypeMovie: String = "",
    strTypeSeries: String = "",
    showFullReleaseDate: Boolean = true,
    previousCachedItem: ModernCarouselItem? = null
): ModernCarouselItem {
    // Carry forward the frozen URLs from the previous cache entry so that
    // TMDB enrichment never changes the image shown on landscape cards,
    // even after the composable remember-state is lost (e.g. navigation).
    val carriedBackdrop = previousCachedItem?.heroPreview?.frozenBackdropUrl
    val carriedLogo = previousCachedItem?.heroPreview?.frozenLogoUrl

    val currentBackdrop = item.backdropUrl
    val currentLogo = item.logo

    // First non-blank value wins and is never replaced.
    val frozenBackdrop = carriedBackdrop?.takeIf { it.isNotBlank() }
        ?: currentBackdrop
    val frozenLogo = carriedLogo?.takeIf { it.isNotBlank() }
        ?: currentLogo

    val heroPreview = HeroPreview(
        title = item.name,
        logo = item.logo,
        description = item.description,
        contentTypeText = when (item.apiType.lowercase()) {
            "movie" -> strTypeMovie.ifBlank { item.apiType.replaceFirstChar { ch -> ch.uppercase() } }
            "series" -> strTypeSeries.ifBlank { item.apiType.replaceFirstChar { ch -> ch.uppercase() } }
            else -> item.apiType.replaceFirstChar { ch -> ch.uppercase() }
        },
        isSeries = isSeriesType(item.apiType),
        yearText = extractYearText(item.type, item.releaseInfo, item.released, showFullReleaseDate),
        runtimeText = formatHeroRuntime(item.runtime),
        imdbText = item.imdbRating?.let { String.format("%.1f", it) },
        ageRatingText = item.ageRating,
        statusText = item.status,
        countryText = item.country,
        languageText = item.language?.uppercase(),
        genres = item.genres.take(3),
        poster = item.poster,
        backdrop = item.backdropUrl,
        imageUrl = if (useLandscapePosters) {
            item.backdropUrl ?: item.poster
        } else {
            item.poster ?: item.backdropUrl
        },
        frozenBackdropUrl = frozenBackdrop,
        frozenLogoUrl = frozenLogo
    )

    return ModernCarouselItem(
        key = "catalog_${row.key()}_${item.id}_${occurrence}",
        title = item.name,
        subtitle = item.releaseInfo,
        imageUrl = if (useLandscapePosters) {
            item.backdropUrl ?: item.poster
        } else {
            item.poster ?: item.backdropUrl
        },
        heroPreview = heroPreview,
        payload = ModernPayload.Catalog(
            focusKey = "${row.key()}::${item.id}",
            itemId = item.id,
            itemType = item.apiType,
            addonBaseUrl = row.addonBaseUrl,
            trailerTitle = item.name,
            trailerReleaseInfo = item.releaseInfo,
            trailerApiType = item.apiType
        ),
        metaPreview = item
    )
}

internal fun continueWatchingItemKey(item: ContinueWatchingItem): String {
    return when (item) {
        is ContinueWatchingItem.InProgress ->
            "cw_inprogress_${item.progress.contentId}_${item.progress.videoId}_${item.progress.season ?: -1}_${item.progress.episode ?: -1}"
        is ContinueWatchingItem.NextUp ->
            "cw_nextup_${item.info.contentId}_${item.info.videoId}_${item.info.season}_${item.info.episode}"
    }
}

internal fun catalogRowKey(row: CatalogRow): String {
    return "${row.addonId}_${row.apiType}_${row.catalogId}"
}

internal fun catalogRowTitle(
    row: CatalogRow,
    showCatalogTypeSuffix: Boolean,
    strTypeMovie: String = "",
    strTypeSeries: String = ""
): String {
    val catalogName = row.catalogName.replaceFirstChar { it.uppercase() }
    if (!showCatalogTypeSuffix) return catalogName
    val typeLabel = when (row.apiType.lowercase()) {
        "movie" -> strTypeMovie.ifBlank { row.apiType.replaceFirstChar { it.uppercase() } }
        "series" -> strTypeSeries.ifBlank { row.apiType.replaceFirstChar { it.uppercase() } }
        else -> row.apiType.replaceFirstChar { it.uppercase() }
    }
    return "$catalogName - $typeLabel"
}

internal fun CatalogRow.key(): String {
    return "${addonId}_${apiType}_${catalogId}"
}

internal fun isSeriesType(type: String?): Boolean {
    return type.equals("series", ignoreCase = true) || type.equals("tv", ignoreCase = true)
}

internal fun firstNonBlank(vararg candidates: String?): String? {
    return candidates.firstOrNull { !it.isNullOrBlank() }?.trim()
}

internal fun extractYear(releaseInfo: String?): String? {
    if (releaseInfo.isNullOrBlank()) return null
    return YEAR_REGEX.find(releaseInfo)?.value
}

internal fun extractYearText(type: ContentType, releaseInfo: String?, released: String?, showFullDate: Boolean = true): String? {
    if (showFullDate && type == ContentType.MOVIE) {
        val full = released
            ?.let { runCatching { java.time.OffsetDateTime.parse(it).toLocalDate() }.getOrNull() }
            ?.let {
                val locale = java.util.Locale.getDefault()
                val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, "dMMMMy")
                java.text.SimpleDateFormat(pattern, locale).format(
                    java.util.Date(it.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli())
                )
            }
        if (full != null) return full
    }
    return extractYear(releaseInfo)
}

private fun formatHeroRuntime(runtime: String?): String? {
    val normalized = runtime?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
    val hours = "(\\d+)\\s*h".toRegex().find(normalized)?.groupValues?.getOrNull(1)?.toIntOrNull()
    val minutes = "(\\d+)\\s*m(?:in)?".toRegex().find(normalized)?.groupValues?.getOrNull(1)?.toIntOrNull()
    val totalMinutes = when {
        hours != null || minutes != null -> (hours ?: 0) * 60 + (minutes ?: 0)
        else -> normalized.filter(Char::isDigit).toIntOrNull()
    } ?: return runtime

    val wholeHours = totalMinutes / 60
    val remainingMinutes = totalMinutes % 60
    return when {
        wholeHours > 0 && remainingMinutes > 0 -> "${wholeHours}h ${remainingMinutes}m"
        wholeHours > 0 -> "${wholeHours}h"
        else -> "${remainingMinutes}m"
    }
}

internal fun ContinueWatchingItem.contentId(): String {
    return when (this) {
        is ContinueWatchingItem.InProgress -> progress.contentId
        is ContinueWatchingItem.NextUp -> info.contentId
    }
}

internal fun ContinueWatchingItem.contentType(): String {
    return when (this) {
        is ContinueWatchingItem.InProgress -> progress.contentType
        is ContinueWatchingItem.NextUp -> info.contentType
    }
}

internal fun ContinueWatchingItem.season(): Int? {
    return when (this) {
        is ContinueWatchingItem.InProgress -> progress.season
        is ContinueWatchingItem.NextUp -> info.seedSeason
    }
}

internal fun ContinueWatchingItem.episode(): Int? {
    return when (this) {
        is ContinueWatchingItem.InProgress -> progress.episode
        is ContinueWatchingItem.NextUp -> info.seedEpisode
    }
}
