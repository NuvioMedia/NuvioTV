package com.nuvio.tv.data.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.sync.LocalHomeCatalogSettingsState
import com.nuvio.tv.core.sync.SyncHomeCatalogPayload
import com.nuvio.tv.core.sync.buildHomeCatalogSyncPayload
import com.nuvio.tv.core.sync.homeCatalogKey
import com.nuvio.tv.core.sync.homeCollectionKey
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CardDepthStyle
import com.nuvio.tv.domain.model.CardDepthSurface
import com.nuvio.tv.domain.model.Collection
import com.nuvio.tv.domain.model.ContinueWatchingCardStyle
import com.nuvio.tv.domain.model.ContinueWatchingSortMode
import com.nuvio.tv.domain.model.DEFAULT_CARD_DEPTH_EDGE_COVERAGE
import com.nuvio.tv.domain.model.DEFAULT_CARD_DEPTH_EDGE_STRENGTH
import com.nuvio.tv.domain.model.DEFAULT_CARD_DEPTH_SHEEN_STRENGTH
import com.nuvio.tv.domain.model.DiscoverLocation
import com.nuvio.tv.domain.model.FocusedPosterTrailerPlaybackTarget
import com.nuvio.tv.domain.model.HomeLayout
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LayoutPreferenceDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val FEATURE = "layout_settings"
        private const val DEFAULT_POSTER_CARD_WIDTH_DP = 126
        private const val DEFAULT_POSTER_CARD_HEIGHT_DP = 189
        private const val DEFAULT_POSTER_CARD_CORNER_RADIUS_DP = 12
        private const val DEFAULT_FOCUSED_POSTER_BACKDROP_EXPAND_DELAY_SECONDS = 3
        private const val MIN_FOCUSED_POSTER_BACKDROP_EXPAND_DELAY_SECONDS = 0
        private const val ZOOM_MIRROR_PREFS = "nuvio_display_scale"
        private const val ZOOM_MIRROR_KEY = "fluid_mode_zoom"

        /**
         * Fluid Mode mirrored into SharedPreferences: `Activity.attachBaseContext`
         * runs too early to read the DataStore, but the Fire TV density scale must
         * be decided there. Warmed in `NuvioApplication.onCreate`.
         */
        internal fun readFluidZoomMirror(context: Context): Boolean =
            context.getSharedPreferences(ZOOM_MIRROR_PREFS, Context.MODE_PRIVATE)
                .getBoolean(ZOOM_MIRROR_KEY, false)

        /** @return true when the mirror changed and the activity must restart. */
        internal fun writeFluidZoomMirror(context: Context, enabled: Boolean): Boolean {
            val prefs = context.getSharedPreferences(ZOOM_MIRROR_PREFS, Context.MODE_PRIVATE)
            if (prefs.getBoolean(ZOOM_MIRROR_KEY, false) == enabled) return false
            prefs.edit().putBoolean(ZOOM_MIRROR_KEY, enabled).apply()
            return true
        }
    }

    private var featureName: String = FEATURE

    internal constructor(
        factory: ProfileDataStoreFactory,
        profileManager: ProfileManager,
        featureName: String
    ) : this(factory, profileManager) {
        this.featureName = featureName
    }

    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, featureName)

    private val gson = Gson()

    private val layoutKey = stringPreferencesKey("selected_layout")
    private val hasChosenKey = booleanPreferencesKey("has_chosen_layout")
    private val heroCatalogKey = stringPreferencesKey("hero_catalog_key")
    private val heroCatalogKeysKey = stringPreferencesKey("hero_catalog_keys")
    private val homeCatalogOrderKeysKey = stringPreferencesKey("home_catalog_order_keys")
    private val disabledHomeCatalogKeysKey = stringPreferencesKey("disabled_home_catalog_keys")
    private val customCatalogTitlesKey = stringPreferencesKey("custom_catalog_titles")
    private val sidebarCollapsedKey = booleanPreferencesKey("sidebar_collapsed_by_default")
    private val modernSidebarEnabledKey = booleanPreferencesKey("modern_sidebar_enabled")
    private val legacyModernSidebarEnabledKey = booleanPreferencesKey("glass_sidepanel_enabled")
    private val modernSidebarBlurEnabledKey = booleanPreferencesKey("modern_sidebar_blur_enabled")
    private val modernLandscapePostersEnabledKey = booleanPreferencesKey("modern_landscape_posters_enabled")
    private val heroSectionEnabledKey = booleanPreferencesKey("hero_section_enabled")
    private val posterLabelsEnabledKey = booleanPreferencesKey("poster_labels_enabled")
    private val catalogTypeSuffixEnabledKey = booleanPreferencesKey("catalog_type_suffix_enabled")
    private val classicFocusGradientEnabledKey = booleanPreferencesKey("classic_focus_gradient_enabled")
    private val focusedPosterBackdropExpandEnabledKey = booleanPreferencesKey("focused_poster_backdrop_expand_enabled")
    private val focusedPosterBackdropExpandDelaySecondsKey = intPreferencesKey("focused_poster_backdrop_expand_delay_seconds")
    private val focusedPosterBackdropTrailerEnabledKey = booleanPreferencesKey("focused_poster_backdrop_trailer_enabled")
    private val focusedPosterBackdropTrailerMutedKey = booleanPreferencesKey("focused_poster_backdrop_trailer_muted")
    private val focusedPosterBackdropTrailerPlaybackTargetKey =
        stringPreferencesKey("focused_poster_backdrop_trailer_playback_target")
    private val posterCardWidthDpKey = intPreferencesKey("poster_card_width_dp")
    private val posterCardHeightDpKey = intPreferencesKey("poster_card_height_dp")
    private val posterCardCornerRadiusDpKey = intPreferencesKey("poster_card_corner_radius_dp")
    private val cardDepthEnabledKey = booleanPreferencesKey("card_depth_enabled")
    private val cardDepthEdgeStrengthKey = intPreferencesKey("card_depth_edge_strength")
    private val cardDepthSheenStrengthKey = intPreferencesKey("card_depth_sheen_strength")
    private val cardDepthEdgeCoverageKey = intPreferencesKey("card_depth_edge_coverage")
    private val cardDepthPostersEnabledKey = booleanPreferencesKey("card_depth_posters_enabled")
    private val cardDepthContinueWatchingEnabledKey = booleanPreferencesKey("card_depth_continue_watching_enabled")
    private val cardDepthEpisodeCardsEnabledKey = booleanPreferencesKey("card_depth_episode_cards_enabled")
    private val cardDepthCastEnabledKey = booleanPreferencesKey("card_depth_cast_enabled")
    private val cardDepthTrailersEnabledKey = booleanPreferencesKey("card_depth_trailers_enabled")
    private val blurUnwatchedEpisodesKey = booleanPreferencesKey("blur_unwatched_episodes")
    private val useEpisodeThumbnailsInCwKey = booleanPreferencesKey("use_episode_thumbnails_in_cw")
    private val continueWatchingCardStyleKey = stringPreferencesKey("continue_watching_card_style")
    private val showUnairedNextUpKey = booleanPreferencesKey("show_unaired_next_up")
    private val nextUpFromFurthestEpisodeKey = booleanPreferencesKey("next_up_from_furthest_episode")
    private val blurContinueWatchingNextUpKey = booleanPreferencesKey("blur_continue_watching_next_up")
    private val continueWatchingSortModeKey = stringPreferencesKey("continue_watching_sort_mode")
    private val dismissedNextUpKeysKey = stringPreferencesKey("dismissed_next_up_keys")
    private val detailPageTrailerButtonEnabledKey = booleanPreferencesKey("detail_page_trailer_button_enabled")
    private val preferExternalMetaAddonDetailKey = booleanPreferencesKey("prefer_external_meta_addon_detail")
    private val modernHeroFullScreenBackdropKey = booleanPreferencesKey("modern_hero_full_screen_backdrop")
    private val hideUnreleasedContentKey = booleanPreferencesKey("hide_unreleased_content")
    private val showFullReleaseDateKey = booleanPreferencesKey("show_full_release_date")
    private val smoothBringIntoViewEnabledKey = booleanPreferencesKey("smooth_bring_into_view_enabled")
    private val fastHorizontalNavigationEnabledKey = booleanPreferencesKey("fast_horizontal_navigation_enabled")

    private val fluidModeEnabledKey = booleanPreferencesKey("fluid_mode_enabled")
    private val preFluidModeExpandEnabledKey =
        booleanPreferencesKey("pre_fluid_mode_expand_enabled")
    private val preFluidModeTrailerEnabledKey =
        booleanPreferencesKey("pre_fluid_mode_trailer_enabled")
    private val followAddonsOrderKey = booleanPreferencesKey("follow_addons_order")
    private val composeHighlighterEnabledKey = booleanPreferencesKey("compose_highlighter_enabled")
    private val animeTabVisibleKey = booleanPreferencesKey("anime_tab_visible")
    private val liveTvTabVisibleKey = booleanPreferencesKey("live_tv_tab_visible")
    private val extraTabVisibleKey = booleanPreferencesKey("extra_tab_visible")
    private val extraTabNameKey = stringPreferencesKey("extra_tab_name")
    private val calendarTabVisibleKey = booleanPreferencesKey("calendar_tab_visible")
    private val libraryTabVisibleKey = booleanPreferencesKey("library_tab_visible")
    private val extraTabLogoIndexKey = intPreferencesKey("extra_tab_logo_index")
    private val searchIncludeExtraTabKey = booleanPreferencesKey("search_include_extra_tab")
    private val searchIncludeAnimeTabKey = booleanPreferencesKey("search_include_anime_tab")

    private fun <T> profileFlow(extract: (prefs: androidx.datastore.preferences.core.Preferences) -> T): Flow<T> =
        profileManager.activeProfileId.flatMapLatest { pid ->
            factory.get(pid, featureName).data.map { prefs -> extract(prefs) }
        }

    /**
     * Profile used by the catalog settings flows: secondary profiles using the primary profile's
     * addons read order/disabled/titles from profile 1, so their writes must land there too —
     * otherwise the toggle persists to a file nobody reads (appears inert after leaving the screen).
     */
    private fun effectiveCatalogProfileId(): Int {
        val pid = profileManager.activeProfileId.value
        val profile = profileManager.profiles.value.find { it.id == pid }
        val usePrimary = profile != null && !profile.isPrimary && profile.usesPrimaryAddons
        return if (usePrimary) 1 else pid
    }

    private fun effectiveCatalogStore() = factory.get(effectiveCatalogProfileId(), featureName)

    /**
     * Read flow over the effective catalog profile — mirrors effectiveCatalogStore()
     * so reads land in the same file the order/disabled/title writes go to. Combines
     * activeProfileId with the profiles list so a profile switch or flag change
     * (e.g. usesPrimaryAddons flipping) re-evaluates the target store immediately.
     */
    private fun <T> effectiveCatalogFlow(extract: (prefs: Preferences) -> T): Flow<T> =
        combine(
            profileManager.activeProfileId,
            profileManager.profiles
        ) { pid, profiles ->
            val profile = profiles.find { it.id == pid }
            if (profile != null && !profile.isPrimary && profile.usesPrimaryAddons) 1 else pid
        }.distinctUntilChanged().flatMapLatest { effectivePid ->
            factory.get(effectivePid, featureName).data.map(extract)
        }

    private fun positiveOrDefault(value: Int?, defaultValue: Int): Int =
        value?.takeIf { it > 0 } ?: defaultValue

    val selectedLayout: Flow<HomeLayout> = profileFlow { prefs ->
        val layoutName = prefs[layoutKey] ?: HomeLayout.MODERN.name
        try {
            HomeLayout.valueOf(layoutName)
        } catch (e: IllegalArgumentException) {
            HomeLayout.MODERN
        }
    }

    val continueWatchingCardStyle: Flow<ContinueWatchingCardStyle> = profileFlow { prefs ->
        val styleName = prefs[continueWatchingCardStyleKey] ?: ContinueWatchingCardStyle.CARD.name
        try {
            ContinueWatchingCardStyle.valueOf(styleName)
        } catch (e: IllegalArgumentException) {
            ContinueWatchingCardStyle.CARD
        }
    }

    val hasChosenLayout: Flow<Boolean> = profileFlow { prefs ->
        prefs[hasChosenKey] ?: false
    }

    val heroCatalogSelections: Flow<List<String>> = profileFlow { prefs ->
        val multiSelection = parseCatalogKeys(prefs[heroCatalogKeysKey])
        if (multiSelection.isNotEmpty()) {
            multiSelection
        } else {
            prefs[heroCatalogKey]
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let(::listOf)
                .orEmpty()
        }
    }

    val heroCatalogSelection: Flow<String?> = heroCatalogSelections.map { selections ->
        selections.firstOrNull()
    }

    val homeCatalogOrderKeys: Flow<List<String>> = effectiveCatalogFlow { prefs ->
        parseCatalogKeys(prefs[homeCatalogOrderKeysKey])
    }

    val disabledHomeCatalogKeys: Flow<List<String>> = effectiveCatalogFlow { prefs ->
        parseCatalogKeys(prefs[disabledHomeCatalogKeysKey])
    }

    val customCatalogTitles: Flow<Map<String, String>> = effectiveCatalogFlow { prefs ->
        parseCustomTitles(prefs[customCatalogTitlesKey])
    }

    val sidebarCollapsedByDefault: Flow<Boolean> = profileFlow { prefs ->
        val modernSidebarEnabled =
            prefs[modernSidebarEnabledKey] ?: prefs[legacyModernSidebarEnabledKey] ?: false
        if (modernSidebarEnabled) {
            false
        } else {
            prefs[sidebarCollapsedKey] ?: false
        }
    }

    val modernSidebarEnabled: Flow<Boolean> = profileFlow { prefs ->
        prefs[modernSidebarEnabledKey] ?: prefs[legacyModernSidebarEnabledKey] ?: false
    }

    val modernSidebarBlurEnabled: Flow<Boolean> = profileFlow { prefs ->
        prefs[modernSidebarBlurEnabledKey] ?: false
    }

    val modernLandscapePostersEnabled: Flow<Boolean> = profileFlow { prefs ->
        prefs[modernLandscapePostersEnabledKey] ?: false
    }

    val modernHeroFullScreenBackdropEnabled: Flow<Boolean> = profileFlow { prefs ->
        prefs[modernHeroFullScreenBackdropKey] ?: false
    }

    val heroSectionEnabled: Flow<Boolean> = profileFlow { prefs ->
        prefs[heroSectionEnabledKey] ?: true
    }

    val discoverLocation: Flow<DiscoverLocation> = profileFlow { prefs ->
        val stored = prefs[discoverLocationKey] ?: DiscoverLocation.IN_SEARCH.name
        runCatching { DiscoverLocation.valueOf(stored) }
            .getOrDefault(DiscoverLocation.IN_SEARCH)
    }

    val lastNonOffDiscoverLocation: Flow<DiscoverLocation> = profileFlow { prefs ->
        val stored = prefs[lastNonOffDiscoverLocationKey]
            ?.takeIf { it != DiscoverLocation.OFF.name }
        val fallback = prefs[discoverLocationKey]
            ?.takeIf { it != DiscoverLocation.OFF.name }
        val source = stored ?: fallback ?: DiscoverLocation.IN_SEARCH.name
        runCatching { DiscoverLocation.valueOf(source) }
            .getOrDefault(DiscoverLocation.IN_SEARCH)
    }

    val posterLabelsEnabled: Flow<Boolean> = profileFlow { prefs ->
        prefs[posterLabelsEnabledKey] ?: true
    }

    val catalogTypeSuffixEnabled: Flow<Boolean> = profileFlow { prefs ->
        prefs[catalogTypeSuffixEnabledKey] ?: true
    }

    val classicFocusGradientEnabled: Flow<Boolean> = profileFlow { prefs ->
        prefs[classicFocusGradientEnabledKey] ?: false
    }

    val focusedPosterBackdropExpandEnabled: Flow<Boolean> = profileFlow { prefs ->
        prefs[focusedPosterBackdropExpandEnabledKey] ?: true
    }

    val focusedPosterBackdropExpandDelaySeconds: Flow<Int> = profileFlow { prefs ->
        (prefs[focusedPosterBackdropExpandDelaySecondsKey]
            ?: DEFAULT_FOCUSED_POSTER_BACKDROP_EXPAND_DELAY_SECONDS)
            .coerceAtLeast(MIN_FOCUSED_POSTER_BACKDROP_EXPAND_DELAY_SECONDS)
    }

    val focusedPosterBackdropTrailerEnabled: Flow<Boolean> = profileFlow { prefs ->
        prefs[focusedPosterBackdropTrailerEnabledKey] ?: false
    }

    val focusedPosterBackdropTrailerMuted: Flow<Boolean> = profileFlow { prefs ->
        prefs[focusedPosterBackdropTrailerMutedKey] ?: true
    }

    val focusedPosterBackdropTrailerPlaybackTarget: Flow<FocusedPosterTrailerPlaybackTarget> =
        profileFlow { prefs ->
            val stored = prefs[focusedPosterBackdropTrailerPlaybackTargetKey]
                ?: FocusedPosterTrailerPlaybackTarget.HERO_MEDIA.name
            runCatching { FocusedPosterTrailerPlaybackTarget.valueOf(stored) }
                .getOrDefault(FocusedPosterTrailerPlaybackTarget.HERO_MEDIA)
        }

    val posterCardWidthDp: Flow<Int> = profileFlow { prefs ->
        positiveOrDefault(prefs[posterCardWidthDpKey], DEFAULT_POSTER_CARD_WIDTH_DP)
    }

    val posterCardHeightDp: Flow<Int> = profileFlow { prefs ->
        positiveOrDefault(prefs[posterCardHeightDpKey], DEFAULT_POSTER_CARD_HEIGHT_DP)
    }

    val posterCardCornerRadiusDp: Flow<Int> = profileFlow { prefs ->
        prefs[posterCardCornerRadiusDpKey] ?: DEFAULT_POSTER_CARD_CORNER_RADIUS_DP
    }

    val cardDepthStyle: Flow<CardDepthStyle> = profileFlow { prefs ->
        CardDepthStyle(
            enabled = prefs[cardDepthEnabledKey] ?: false,
            edgeStrength = (prefs[cardDepthEdgeStrengthKey]
                ?: DEFAULT_CARD_DEPTH_EDGE_STRENGTH).coerceIn(0, 100),
            sheenStrength = (prefs[cardDepthSheenStrengthKey]
                ?: DEFAULT_CARD_DEPTH_SHEEN_STRENGTH).coerceIn(0, 100),
            edgeCoverage = (prefs[cardDepthEdgeCoverageKey]
                ?: DEFAULT_CARD_DEPTH_EDGE_COVERAGE).coerceIn(0, 100),
            postersEnabled = prefs[cardDepthPostersEnabledKey] ?: true,
            continueWatchingEnabled = prefs[cardDepthContinueWatchingEnabledKey] ?: true,
            episodeCardsEnabled = prefs[cardDepthEpisodeCardsEnabledKey] ?: true,
            castEnabled = prefs[cardDepthCastEnabledKey] ?: true,
            trailersEnabled = prefs[cardDepthTrailersEnabledKey] ?: true
        )
    }

    val blurUnwatchedEpisodes: Flow<Boolean> = profileFlow { prefs ->
        prefs[blurUnwatchedEpisodesKey] ?: false
    }

    val useEpisodeThumbnailsInCw: Flow<Boolean> = profileFlow { prefs ->
        prefs[useEpisodeThumbnailsInCwKey] ?: true
    }

    val showUnairedNextUp: Flow<Boolean> = profileFlow { prefs ->
        prefs[showUnairedNextUpKey] ?: true
    }

    val nextUpFromFurthestEpisode: Flow<Boolean> = profileFlow { prefs ->
        prefs[nextUpFromFurthestEpisodeKey] ?: true
    }

    val blurContinueWatchingNextUp: Flow<Boolean> = profileFlow { prefs ->
        prefs[blurContinueWatchingNextUpKey] ?: false
    }

    val continueWatchingSortMode: Flow<ContinueWatchingSortMode> = profileFlow { prefs ->
        val stored = prefs[continueWatchingSortModeKey] ?: ContinueWatchingSortMode.DEFAULT.name
        runCatching { ContinueWatchingSortMode.valueOf(stored) }
            .getOrDefault(ContinueWatchingSortMode.DEFAULT)
    }

    val dismissedNextUpKeys: Flow<Set<String>> = profileFlow { prefs ->
        parseCatalogKeys(prefs[dismissedNextUpKeysKey])
            .map(::normalizeDismissedNextUpKey)
            .toSet()
    }

    val detailPageTrailerButtonEnabled: Flow<Boolean> = profileFlow { prefs ->
        prefs[detailPageTrailerButtonEnabledKey] ?: true
    }

    val preferExternalMetaAddonDetail: Flow<Boolean> = profileFlow { prefs ->
        prefs[preferExternalMetaAddonDetailKey] ?: true
    }

    val hideUnreleasedContent: Flow<Boolean> = effectiveCatalogFlow { prefs ->
        prefs[hideUnreleasedContentKey] ?: false
    }

    val showFullReleaseDate: Flow<Boolean> = profileFlow { prefs ->
        prefs[showFullReleaseDateKey] ?: true
    }

    val smoothBringIntoViewEnabled: Flow<Boolean> = profileFlow { prefs ->
        prefs[smoothBringIntoViewEnabledKey] ?: true
    }

    val fastHorizontalNavigationEnabled: Flow<Boolean> = profileFlow { prefs ->
        prefs[fastHorizontalNavigationEnabledKey] ?: false
    }

    val fluidModeEnabled: Flow<Boolean> = profileFlow { prefs ->
        prefs[fluidModeEnabledKey] ?: false
    }

    val followAddonsOrder: Flow<Boolean> = effectiveCatalogFlow { prefs ->
        prefs[followAddonsOrderKey] ?: false
    }

    val composeHighlighterEnabled: Flow<Boolean> = profileFlow { prefs ->
        prefs[composeHighlighterEnabledKey] ?: false
    }

    val animeTabVisible: Flow<Boolean> = profileFlow { prefs ->
        prefs[animeTabVisibleKey] ?: true
    }

    val liveTvTabVisible: Flow<Boolean> = profileFlow { prefs ->
        prefs[liveTvTabVisibleKey] ?: true
    }

    val extraTabVisible: Flow<Boolean> = profileFlow { prefs ->
        prefs[extraTabVisibleKey] ?: true
    }

    val extraTabName: Flow<String> = profileFlow { prefs ->
        prefs[extraTabNameKey] ?: "Extra"
    }

    val calendarTabVisible: Flow<Boolean> = profileFlow { prefs ->
        prefs[calendarTabVisibleKey] ?: true
    }

    val libraryTabVisible: Flow<Boolean> = profileFlow { prefs ->
        prefs[libraryTabVisibleKey] ?: true
    }

    val extraTabLogoIndex: Flow<Int> = profileFlow { prefs ->
        prefs[extraTabLogoIndexKey] ?: 0
    }

    val searchIncludeExtraTab: Flow<Boolean> = profileFlow { prefs ->
        prefs[searchIncludeExtraTabKey] ?: true
    }

    val searchIncludeAnimeTab: Flow<Boolean> = profileFlow { prefs ->
        prefs[searchIncludeAnimeTabKey] ?: true
    }

    suspend fun setSmoothBringIntoViewEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[smoothBringIntoViewEnabledKey] = enabled
        }
    }

    suspend fun setFastHorizontalNavigationEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[fastHorizontalNavigationEnabledKey] = enabled
        }
    }

    /**
     * One-touch preset for slow devices: turns off every GPU-heavy effect
     * (blurs, poster expansion, trailer preview) and speeds up D-pad repeat.
     * Turning it off restores the corresponding defaults.
     *
     * The poster-expansion and trailer switches are the user's own choices, so
     * their current values are saved while Fluid Mode is on and written back
     * when it is turned off. Before this, turning Fluid Mode off always forced
     * the trailer switch to false, which silently killed trailer previews even
     * though the user had explicitly enabled them.
     */
    suspend fun setFluidModeEnabled(enabled: Boolean) {
        store().edit { prefs ->
            if (enabled) {
                prefs[preFluidModeExpandEnabledKey] =
                    prefs[focusedPosterBackdropExpandEnabledKey] ?: true
                prefs[preFluidModeTrailerEnabledKey] =
                    prefs[focusedPosterBackdropTrailerEnabledKey] ?: false
            }
            prefs[fluidModeEnabledKey] = enabled
            prefs[modernSidebarBlurEnabledKey] = false
            prefs[focusedPosterBackdropExpandEnabledKey] = if (enabled) {
                false
            } else {
                prefs[preFluidModeExpandEnabledKey] ?: true
            }
            prefs[focusedPosterBackdropTrailerEnabledKey] = if (enabled) {
                false
            } else {
                prefs[preFluidModeTrailerEnabledKey] ?: true
            }
            if (enabled) {
                prefs[focusedPosterBackdropTrailerMutedKey] = true
            }
            prefs[blurUnwatchedEpisodesKey] = false
            prefs[blurContinueWatchingNextUpKey] = false
            prefs[fastHorizontalNavigationEnabledKey] = enabled
        }
    }

    suspend fun setFollowAddonsOrder(enabled: Boolean) {
        effectiveCatalogStore().edit { prefs ->
            prefs[followAddonsOrderKey] = enabled
        }
    }

    suspend fun setComposeHighlighterEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[composeHighlighterEnabledKey] = enabled
        }
    }

    suspend fun setAnimeTabVisible(enabled: Boolean) {
        store().edit { prefs ->
            prefs[animeTabVisibleKey] = enabled
        }
    }

    suspend fun setLiveTvTabVisible(enabled: Boolean) {
        store().edit { prefs ->
            prefs[liveTvTabVisibleKey] = enabled
        }
    }

    suspend fun setExtraTabVisible(enabled: Boolean) {
        store().edit { prefs ->
            prefs[extraTabVisibleKey] = enabled
        }
    }

    suspend fun setExtraTabName(name: String) {
        store().edit { prefs ->
            prefs[extraTabNameKey] = name
        }
    }

    suspend fun setCalendarTabVisible(enabled: Boolean) {
        store().edit { prefs ->
            prefs[calendarTabVisibleKey] = enabled
        }
    }

    suspend fun setLibraryTabVisible(enabled: Boolean) {
        store().edit { prefs ->
            prefs[libraryTabVisibleKey] = enabled
        }
    }

    suspend fun setExtraTabLogoIndex(index: Int) {
        store().edit { prefs ->
            prefs[extraTabLogoIndexKey] = index
        }
    }

    suspend fun setSearchIncludeExtraTab(enabled: Boolean) {
        store().edit { prefs ->
            prefs[searchIncludeExtraTabKey] = enabled
        }
    }

    suspend fun setSearchIncludeAnimeTab(enabled: Boolean) {
        store().edit { prefs ->
            prefs[searchIncludeAnimeTabKey] = enabled
        }
    }

    suspend fun setLayout(layout: HomeLayout) {
        store().edit { prefs ->
            val hadChosenLayout = prefs[hasChosenKey] ?: false
            prefs[layoutKey] = layout.name
            if (
                layout == HomeLayout.MODERN &&
                !hadChosenLayout &&
                prefs[focusedPosterBackdropTrailerPlaybackTargetKey] == null
            ) {
                prefs[focusedPosterBackdropTrailerPlaybackTargetKey] =
                    FocusedPosterTrailerPlaybackTarget.HERO_MEDIA.name
            }
            prefs[hasChosenKey] = true
        }
    }

    suspend fun setHeroCatalogKeys(catalogKeys: List<String>) {
        val normalizedKeys = normalizeCatalogOrderKeys(catalogKeys)
        store().edit { prefs ->
            if (normalizedKeys.isEmpty()) {
                prefs.remove(heroCatalogKeysKey)
                prefs.remove(heroCatalogKey)
            } else {
                prefs[heroCatalogKeysKey] = gson.toJson(normalizedKeys)
                prefs[heroCatalogKey] = normalizedKeys.first()
            }
        }
    }

    suspend fun setHeroCatalogKey(catalogKey: String) {
        setHeroCatalogKeys(listOf(catalogKey))
    }

    suspend fun setHomeCatalogOrderKeys(keys: List<String>) {
        val normalizedKeys = normalizeCatalogOrderKeys(keys)
        effectiveCatalogStore().edit { prefs ->
            if (normalizedKeys.isEmpty()) {
                prefs.remove(homeCatalogOrderKeysKey)
            } else {
                prefs[homeCatalogOrderKeysKey] = gson.toJson(normalizedKeys)
            }
        }
    }

    suspend fun setDisabledHomeCatalogKeys(keys: List<String>) {
        val normalizedKeys = normalizeCatalogOrderKeys(keys)
        effectiveCatalogStore().edit { prefs ->
            if (normalizedKeys.isEmpty()) {
                prefs.remove(disabledHomeCatalogKeysKey)
            } else {
                prefs[disabledHomeCatalogKeysKey] = gson.toJson(normalizedKeys)
            }
        }
    }

    suspend fun setSidebarCollapsedByDefault(collapsed: Boolean) {
        store().edit { prefs ->
            val modernSidebarEnabled =
                prefs[modernSidebarEnabledKey] ?: prefs[legacyModernSidebarEnabledKey] ?: false
            prefs[sidebarCollapsedKey] = if (modernSidebarEnabled) false else collapsed
        }
    }

    suspend fun setModernSidebarEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[modernSidebarEnabledKey] = enabled
            prefs.remove(legacyModernSidebarEnabledKey)
            if (enabled) {
                prefs[sidebarCollapsedKey] = false
            }
        }
    }

    suspend fun setModernSidebarBlurEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[modernSidebarBlurEnabledKey] = enabled
        }
    }

    suspend fun setModernLandscapePostersEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[modernLandscapePostersEnabledKey] = enabled
        }
    }

    suspend fun setModernHeroFullScreenBackdropEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[modernHeroFullScreenBackdropKey] = enabled
        }
    }

    suspend fun setHeroSectionEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[heroSectionEnabledKey] = enabled
        }
    }

    suspend fun setDiscoverLocation(location: DiscoverLocation) {
        store().edit { prefs ->
            prefs[discoverLocationKey] = location.name
            if (location != DiscoverLocation.OFF) {
                prefs[lastNonOffDiscoverLocationKey] = location.name
            }
            prefs.remove(legacySearchDiscoverEnabledKey)
        }
    }

    suspend fun setPosterLabelsEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[posterLabelsEnabledKey] = enabled
        }
    }

    suspend fun setCatalogTypeSuffixEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[catalogTypeSuffixEnabledKey] = enabled
        }
    }

    suspend fun setClassicFocusGradientEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[classicFocusGradientEnabledKey] = enabled
        }
    }

    suspend fun setFocusedPosterBackdropExpandEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[focusedPosterBackdropExpandEnabledKey] = enabled
            if (!enabled) {
                prefs[focusedPosterBackdropTrailerEnabledKey] = false
                prefs[focusedPosterBackdropTrailerMutedKey] = true
            }
        }
    }

    suspend fun setFocusedPosterBackdropExpandDelaySeconds(seconds: Int) {
        store().edit { prefs ->
            prefs[focusedPosterBackdropExpandDelaySecondsKey] =
                seconds.coerceAtLeast(MIN_FOCUSED_POSTER_BACKDROP_EXPAND_DELAY_SECONDS)
        }
    }

    suspend fun setFocusedPosterBackdropTrailerEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[focusedPosterBackdropTrailerEnabledKey] = enabled
            if (!enabled) {
                prefs[focusedPosterBackdropTrailerMutedKey] = true
            }
        }
    }

    suspend fun setFocusedPosterBackdropTrailerMuted(muted: Boolean) {
        store().edit { prefs ->
            prefs[focusedPosterBackdropTrailerMutedKey] = muted
        }
    }

    suspend fun setFocusedPosterBackdropTrailerPlaybackTarget(
        target: FocusedPosterTrailerPlaybackTarget
    ) {
        store().edit { prefs ->
            prefs[focusedPosterBackdropTrailerPlaybackTargetKey] = target.name
        }
    }

    suspend fun setPosterCardWidthDp(widthDp: Int) {
        store().edit { prefs ->
            prefs[posterCardWidthDpKey] = positiveOrDefault(widthDp, DEFAULT_POSTER_CARD_WIDTH_DP)
        }
    }

    suspend fun setPosterCardHeightDp(heightDp: Int) {
        store().edit { prefs ->
            prefs[posterCardHeightDpKey] = positiveOrDefault(heightDp, DEFAULT_POSTER_CARD_HEIGHT_DP)
        }
    }

    suspend fun setPosterCardCornerRadiusDp(cornerRadiusDp: Int) {
        store().edit { prefs ->
            prefs[posterCardCornerRadiusDpKey] = cornerRadiusDp
        }
    }

    suspend fun setCardDepthEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[cardDepthEnabledKey] = enabled
        }
    }

    suspend fun setCardDepthEdgeStrength(strength: Int) {
        store().edit { prefs ->
            prefs[cardDepthEdgeStrengthKey] = strength.coerceIn(0, 100)
        }
    }

    suspend fun setCardDepthSheenStrength(strength: Int) {
        store().edit { prefs ->
            prefs[cardDepthSheenStrengthKey] = strength.coerceIn(0, 100)
        }
    }

    suspend fun setCardDepthEdgeCoverage(coverage: Int) {
        store().edit { prefs ->
            prefs[cardDepthEdgeCoverageKey] = coverage.coerceIn(0, 100)
        }
    }

    suspend fun setCardDepthSurfaceEnabled(surface: CardDepthSurface, enabled: Boolean) {
        store().edit { prefs ->
            val key = when (surface) {
                CardDepthSurface.POSTERS -> cardDepthPostersEnabledKey
                CardDepthSurface.CONTINUE_WATCHING -> cardDepthContinueWatchingEnabledKey
                CardDepthSurface.EPISODE_CARDS -> cardDepthEpisodeCardsEnabledKey
                CardDepthSurface.CAST -> cardDepthCastEnabledKey
                CardDepthSurface.TRAILERS -> cardDepthTrailersEnabledKey
            }
            prefs[key] = enabled
        }
    }

    suspend fun resetCardDepthStyle() {
        store().edit { prefs ->
            prefs.remove(cardDepthEnabledKey)
            prefs.remove(cardDepthEdgeStrengthKey)
            prefs.remove(cardDepthSheenStrengthKey)
            prefs.remove(cardDepthEdgeCoverageKey)
            prefs.remove(cardDepthPostersEnabledKey)
            prefs.remove(cardDepthContinueWatchingEnabledKey)
            prefs.remove(cardDepthEpisodeCardsEnabledKey)
            prefs.remove(cardDepthCastEnabledKey)
            prefs.remove(cardDepthTrailersEnabledKey)
        }
    }

    suspend fun setBlurUnwatchedEpisodes(enabled: Boolean) {
        store().edit { prefs ->
            prefs[blurUnwatchedEpisodesKey] = enabled
        }
    }

    suspend fun setUseEpisodeThumbnailsInCw(enabled: Boolean) {
        store().edit { prefs ->
            prefs[useEpisodeThumbnailsInCwKey] = enabled
        }
    }

    suspend fun setContinueWatchingCardStyle(style: ContinueWatchingCardStyle) {
        store().edit { prefs ->
            prefs[continueWatchingCardStyleKey] = style.name
        }
    }

    suspend fun setShowUnairedNextUp(enabled: Boolean) {
        store().edit { prefs ->
            prefs[showUnairedNextUpKey] = enabled
        }
    }

    suspend fun setNextUpFromFurthestEpisode(enabled: Boolean) {
        store().edit { prefs ->
            prefs[nextUpFromFurthestEpisodeKey] = enabled
        }
    }

    suspend fun setBlurContinueWatchingNextUp(enabled: Boolean) {
        store().edit { prefs ->
            prefs[blurContinueWatchingNextUpKey] = enabled
        }
    }

    suspend fun setContinueWatchingSortMode(mode: ContinueWatchingSortMode) {
        store().edit { prefs ->
            prefs[continueWatchingSortModeKey] = mode.name
        }
    }

    suspend fun addDismissedNextUpKey(key: String) {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) return
        store().edit { prefs ->
            val existing = parseCatalogKeys(prefs[dismissedNextUpKeysKey]).toMutableSet()
            if (existing.add(trimmed)) {
                prefs[dismissedNextUpKeysKey] = gson.toJson(existing.toList())
            }
        }
    }

    /**
     * Clears every dismiss variant for a content: bare id, typed "type|id" keys
     * and legacy "id|season|episode" keys. Called on progress save so a dismissed
     * Next Up row reappears once the user actually watches the show again.
     */
    suspend fun removeDismissedNextUpKeysForContent(contentId: String) {
        if (contentId.isBlank()) return
        val trimmed = contentId.trim()
        val prefix = "$trimmed|"
        store().edit { prefs ->
            val current = parseCatalogKeys(prefs[dismissedNextUpKeysKey])
            val filtered = current.filterNot {
                it == trimmed || it.startsWith(prefix) || it.endsWith("|$trimmed")
            }
            if (filtered.size != current.size) {
                prefs[dismissedNextUpKeysKey] = gson.toJson(filtered)
            }
        }
    }

    /**
     * Strips the "type|" discriminator ("series|kitsu:123") so every consumer
     * (including shared reconciliation that matches bare content ids) keeps working.
     */
    private fun normalizeDismissedNextUpKey(key: String): String {
        val separator = key.indexOf('|')
        if (separator > 0) {
            when (key.substring(0, separator).lowercase()) {
                "series", "movie", "tv", "show" -> return key.substring(separator + 1)
            }
        }
        return key
    }

    suspend fun setDetailPageTrailerButtonEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[detailPageTrailerButtonEnabledKey] = enabled
        }
    }

    suspend fun setPreferExternalMetaAddonDetail(enabled: Boolean) {
        store().edit { prefs ->
            prefs[preferExternalMetaAddonDetailKey] = enabled
        }
    }

    suspend fun setHideUnreleasedContent(enabled: Boolean) {
        effectiveCatalogStore().edit { prefs ->
            prefs[hideUnreleasedContentKey] = enabled
        }
    }

    suspend fun setShowFullReleaseDate(enabled: Boolean) {
        store().edit { prefs ->
            prefs[showFullReleaseDateKey] = enabled
        }
    }

    private fun parseCatalogKeys(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val type = object : TypeToken<List<String>>() {}.type
            val parsed = gson.fromJson<List<String>>(json, type).orEmpty()
            normalizeCatalogOrderKeys(parsed)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun normalizeCatalogOrderKeys(keys: List<String>): List<String> {
        return keys.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
    }

    private fun parseCustomTitles(json: String?): Map<String, String> {
        if (json.isNullOrBlank()) return emptyMap()
        return try {
            val type = object : TypeToken<Map<String, String>>() {}.type
            gson.fromJson<Map<String, String>>(json, type).orEmpty()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    suspend fun setCustomCatalogTitles(titles: Map<String, String>) {
        effectiveCatalogStore().edit { prefs ->
            val filtered = titles.filterValues { it.isNotBlank() }
            if (filtered.isEmpty()) {
                prefs.remove(customCatalogTitlesKey)
            } else {
                prefs[customCatalogTitlesKey] = gson.toJson(filtered)
            }
        }
    }

    internal suspend fun getHomeCatalogSettingsState(): LocalHomeCatalogSettingsState {
        return readHomeCatalogSettingsState(effectiveCatalogStore().data.first())
    }

    internal suspend fun exportCatalogSettingsToSyncPayload(
        addons: List<Addon>,
        collections: List<Collection>
    ): SyncHomeCatalogPayload {
        return buildHomeCatalogSyncPayload(
            addons = addons,
            collections = collections,
            localState = getHomeCatalogSettingsState()
        )
    }

    suspend fun applyCatalogSettingsFromRemote(payload: SyncHomeCatalogPayload) {
        val sortedItems = payload.items.sortedBy { it.order }
        val orderKeys = sortedItems.map { item ->
            if (item.isCollection) homeCollectionKey(item.collectionId)
            else homeCatalogKey(item.addonId, item.type, item.catalogId)
        }
        val disabledKeys = sortedItems.filter { !it.enabled }.map { item ->
            if (item.isCollection) homeCollectionKey(item.collectionId)
            else homeCatalogKey(item.addonId, item.type, item.catalogId)
        }
        val titles = sortedItems.associate { item ->
            val key = if (item.isCollection) homeCollectionKey(item.collectionId)
            else homeCatalogKey(item.addonId, item.type, item.catalogId)
            key to item.customTitle
        }.filterValues { it.isNotBlank() }

        effectiveCatalogStore().edit { prefs ->
            prefs[hideUnreleasedContentKey] = payload.hideUnreleasedContent
            if (orderKeys.isNotEmpty()) {
                prefs[homeCatalogOrderKeysKey] = gson.toJson(orderKeys)
            } else {
                prefs.remove(homeCatalogOrderKeysKey)
            }
            if (disabledKeys.isNotEmpty()) {
                prefs[disabledHomeCatalogKeysKey] = gson.toJson(disabledKeys)
            } else {
                prefs.remove(disabledHomeCatalogKeysKey)
            }
            if (titles.isNotEmpty()) {
                prefs[customCatalogTitlesKey] = gson.toJson(titles)
            } else {
                prefs.remove(customCatalogTitlesKey)
            }
        }
    }

    private fun readHomeCatalogSettingsState(prefs: Preferences): LocalHomeCatalogSettingsState {
        return LocalHomeCatalogSettingsState(
            orderKeys = parseCatalogKeys(prefs[homeCatalogOrderKeysKey]),
            disabledKeys = parseCatalogKeys(prefs[disabledHomeCatalogKeysKey]).toSet(),
            customTitles = parseCustomTitles(prefs[customCatalogTitlesKey]),
            hideUnreleasedContent = prefs[hideUnreleasedContentKey] ?: false
        )
    }
}

internal val legacySearchDiscoverEnabledKey = booleanPreferencesKey("search_discover_enabled")
internal val discoverLocationKey = stringPreferencesKey("discover_location")
internal val lastNonOffDiscoverLocationKey = stringPreferencesKey("last_non_off_discover_location")
