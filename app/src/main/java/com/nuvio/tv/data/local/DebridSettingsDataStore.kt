package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.gson.Gson
import com.nuvio.tv.core.debrid.DebridProviders
import com.nuvio.tv.core.debrid.DebridStreamFormatterDefaults
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.DebridSettings
import com.nuvio.tv.domain.model.DebridStreamCodecFilter
import com.nuvio.tv.domain.model.DebridStreamEncode
import com.nuvio.tv.domain.model.DebridStreamFeatureFilter
import com.nuvio.tv.domain.model.DebridStreamMinimumQuality
import com.nuvio.tv.domain.model.DebridStreamPreferences
import com.nuvio.tv.domain.model.DebridStreamResolution
import com.nuvio.tv.domain.model.DebridStreamSortCriterion
import com.nuvio.tv.domain.model.DebridStreamSortDirection
import com.nuvio.tv.domain.model.DebridStreamSortKey
import com.nuvio.tv.domain.model.DebridStreamSortMode
import com.nuvio.tv.domain.model.DebridStreamVisualTag
import com.nuvio.tv.domain.model.normalizeDebridInstantPlaybackPreparationLimit
import com.nuvio.tv.domain.model.normalizeDebridStreamMaxResults
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DebridSettingsDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    private val gson = Gson()

    companion object {
        private const val FEATURE = "debrid_settings"
    }

    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, FEATURE)

    private val enabledKey = booleanPreferencesKey("debrid_enabled")
    private val cloudLibraryEnabledKey = booleanPreferencesKey("cloud_library_enabled")
    private val torboxApiKeyKey = stringPreferencesKey("torbox_api_key")
    private val premiumizeApiKeyKey = stringPreferencesKey("premiumize_api_key")
    private val realDebridApiKeyKey = stringPreferencesKey("real_debrid_api_key")
    private val preferredResolverProviderIdKey = stringPreferencesKey("preferred_resolver_provider_id")
    private val instantPlaybackPreparationLimitKey = intPreferencesKey("instant_playback_preparation_limit")
    private val streamMaxResultsKey = intPreferencesKey("stream_max_results")
    private val streamSortModeKey = stringPreferencesKey("stream_sort_mode")
    private val streamMinimumQualityKey = stringPreferencesKey("stream_minimum_quality")
    private val streamDolbyVisionFilterKey = stringPreferencesKey("stream_dolby_vision_filter")
    private val streamHdrFilterKey = stringPreferencesKey("stream_hdr_filter")
    private val streamCodecFilterKey = stringPreferencesKey("stream_codec_filter")
    private val streamPreferencesKey = stringPreferencesKey("stream_preferences")
    private val streamNameTemplateKey = stringPreferencesKey("debrid_stream_name_template")
    private val streamDescriptionTemplateKey = stringPreferencesKey("debrid_stream_description_template")

    val settings: Flow<DebridSettings> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { prefs ->
            val storedStreamSortMode = enumValueOrDefault(
                prefs[streamSortModeKey],
                DebridStreamSortMode.DEFAULT
            )
            val streamPreferences = parseStreamPreferences(prefs[streamPreferencesKey])
                ?: legacyStreamPreferences(
                    maxResults = prefs[streamMaxResultsKey] ?: 0,
                    sortMode = storedStreamSortMode,
                    minimumQuality = enumValueOrDefault(prefs[streamMinimumQualityKey], DebridStreamMinimumQuality.ANY),
                    dolbyVisionFilter = enumValueOrDefault(prefs[streamDolbyVisionFilterKey], DebridStreamFeatureFilter.ANY),
                    hdrFilter = enumValueOrDefault(prefs[streamHdrFilterKey], DebridStreamFeatureFilter.ANY),
                    codecFilter = enumValueOrDefault(prefs[streamCodecFilterKey], DebridStreamCodecFilter.ANY)
                )
            val streamSortMode = legacyModeForSortCriteria(streamPreferences.sortCriteria)
            DebridSettings(
                enabled = prefs[enabledKey] ?: false,
                cloudLibraryEnabled = prefs[cloudLibraryEnabledKey] ?: true,
                torboxApiKey = prefs[torboxApiKeyKey] ?: "",
                premiumizeApiKey = prefs[premiumizeApiKeyKey] ?: "",
                realDebridApiKey = prefs[realDebridApiKeyKey] ?: "",
                preferredResolverProviderId = preferredResolverProviderId(
                    stored = prefs[preferredResolverProviderIdKey],
                    torboxApiKey = prefs[torboxApiKeyKey] ?: "",
                    premiumizeApiKey = prefs[premiumizeApiKeyKey] ?: "",
                    realDebridApiKey = prefs[realDebridApiKeyKey] ?: ""
                ),
                instantPlaybackPreparationLimit = normalizeDebridInstantPlaybackPreparationLimit(
                    prefs[instantPlaybackPreparationLimitKey] ?: 0
                ),
                streamMaxResults = normalizeDebridStreamMaxResults(prefs[streamMaxResultsKey] ?: 0),
                streamSortMode = streamSortMode,
                streamMinimumQuality = enumValueOrDefault(
                    prefs[streamMinimumQualityKey],
                    DebridStreamMinimumQuality.ANY
                ),
                streamDolbyVisionFilter = enumValueOrDefault(
                    prefs[streamDolbyVisionFilterKey],
                    DebridStreamFeatureFilter.ANY
                ),
                streamHdrFilter = enumValueOrDefault(
                    prefs[streamHdrFilterKey],
                    DebridStreamFeatureFilter.ANY
                ),
                streamCodecFilter = enumValueOrDefault(
                    prefs[streamCodecFilterKey],
                    DebridStreamCodecFilter.ANY
                ),
                streamPreferences = streamPreferences,
                streamNameTemplate = prefs[streamNameTemplateKey]
                    ?: DebridStreamFormatterDefaults.NAME_TEMPLATE,
                streamDescriptionTemplate = prefs[streamDescriptionTemplateKey]
                    ?: DebridStreamFormatterDefaults.DESCRIPTION_TEMPLATE
            )
        }
    }

    suspend fun setEnabled(enabled: Boolean) {
        store().edit { it[enabledKey] = enabled }
    }

    suspend fun setCloudLibraryEnabled(enabled: Boolean) {
        store().edit { it[cloudLibraryEnabledKey] = enabled }
    }

    suspend fun setPreferredResolverProviderId(providerId: String) {
        val normalized = DebridProviders.byId(providerId)?.id.orEmpty()
        store().edit { it[preferredResolverProviderIdKey] = normalized }
    }

    suspend fun setProviderApiKey(providerId: String, apiKey: String) {
        val provider = DebridProviders.byId(providerId) ?: return
        val normalized = apiKey.trim()
        store().edit { prefs ->
            providerKey(provider.id)?.let { key -> prefs[key] = normalized }
            if (normalized.isBlank() && !hasAnyVisibleApiKeyAfter(prefs, provider.id)) {
                prefs[enabledKey] = false
            }
            val preferred = preferredResolverProviderId(
                stored = prefs[preferredResolverProviderIdKey],
                torboxApiKey = prefs[torboxApiKeyKey] ?: "",
                premiumizeApiKey = prefs[premiumizeApiKeyKey] ?: "",
                realDebridApiKey = prefs[realDebridApiKeyKey] ?: ""
            )
            prefs[preferredResolverProviderIdKey] = preferred
        }
    }

    suspend fun setTorboxApiKey(apiKey: String) {
        setProviderApiKey(DebridProviders.TORBOX_ID, apiKey)
    }

    suspend fun setPremiumizeApiKey(apiKey: String) {
        setProviderApiKey(DebridProviders.PREMIUMIZE_ID, apiKey)
    }

    suspend fun setRealDebridApiKey(apiKey: String) {
        setProviderApiKey(DebridProviders.REAL_DEBRID_ID, apiKey)
    }

    suspend fun setInstantPlaybackPreparationLimit(limit: Int) {
        store().edit {
            it[instantPlaybackPreparationLimitKey] = normalizeDebridInstantPlaybackPreparationLimit(limit)
        }
    }

    suspend fun setStreamMaxResults(maxResults: Int) {
        store().edit {
            val normalized = normalizeDebridStreamMaxResults(maxResults)
            it[streamMaxResultsKey] = normalized
            it[streamPreferencesKey] = gson.toJson(currentStreamPreferences(it[streamPreferencesKey]).copy(maxResults = normalized))
        }
    }

    suspend fun setStreamSortMode(mode: DebridStreamSortMode) {
        store().edit {
            it[streamSortModeKey] = mode.name
            it[streamPreferencesKey] = gson.toJson(
                currentStreamPreferences(it[streamPreferencesKey]).copy(sortCriteria = sortCriteriaForLegacyMode(mode))
            )
        }
    }

    suspend fun setStreamMinimumQuality(quality: DebridStreamMinimumQuality) {
        store().edit {
            it[streamMinimumQualityKey] = quality.name
            it[streamPreferencesKey] = gson.toJson(
                currentStreamPreferences(it[streamPreferencesKey]).copy(requiredResolutions = resolutionsForMinimumQuality(quality))
            )
        }
    }

    suspend fun setStreamDolbyVisionFilter(filter: DebridStreamFeatureFilter) {
        store().edit {
            it[streamDolbyVisionFilterKey] = filter.name
            val current = currentStreamPreferences(it[streamPreferencesKey])
            it[streamPreferencesKey] = gson.toJson(
                when (filter) {
                    DebridStreamFeatureFilter.ANY -> current.copy(
                        requiredVisualTags = current.requiredVisualTags - DebridStreamVisualTag.DV - DebridStreamVisualTag.DV_ONLY - DebridStreamVisualTag.HDR_DV,
                        excludedVisualTags = current.excludedVisualTags - DebridStreamVisualTag.DV - DebridStreamVisualTag.DV_ONLY - DebridStreamVisualTag.HDR_DV
                    )
                    DebridStreamFeatureFilter.EXCLUDE -> current.copy(
                        requiredVisualTags = current.requiredVisualTags - DebridStreamVisualTag.DV - DebridStreamVisualTag.DV_ONLY - DebridStreamVisualTag.HDR_DV,
                        excludedVisualTags = (current.excludedVisualTags + listOf(DebridStreamVisualTag.DV, DebridStreamVisualTag.DV_ONLY, DebridStreamVisualTag.HDR_DV)).distinct()
                    )
                    DebridStreamFeatureFilter.ONLY -> current.copy(
                        requiredVisualTags = (current.requiredVisualTags + listOf(DebridStreamVisualTag.DV, DebridStreamVisualTag.DV_ONLY, DebridStreamVisualTag.HDR_DV)).distinct(),
                        excludedVisualTags = current.excludedVisualTags - DebridStreamVisualTag.DV - DebridStreamVisualTag.DV_ONLY - DebridStreamVisualTag.HDR_DV
                    )
                }
            )
        }
    }

    suspend fun setStreamHdrFilter(filter: DebridStreamFeatureFilter) {
        store().edit {
            it[streamHdrFilterKey] = filter.name
            val hdrTags = listOf(DebridStreamVisualTag.HDR, DebridStreamVisualTag.HDR10, DebridStreamVisualTag.HDR10_PLUS, DebridStreamVisualTag.HLG, DebridStreamVisualTag.HDR_ONLY, DebridStreamVisualTag.HDR_DV)
            val current = currentStreamPreferences(it[streamPreferencesKey])
            it[streamPreferencesKey] = gson.toJson(
                when (filter) {
                    DebridStreamFeatureFilter.ANY -> current.copy(
                        requiredVisualTags = current.requiredVisualTags - hdrTags.toSet(),
                        excludedVisualTags = current.excludedVisualTags - hdrTags.toSet()
                    )
                    DebridStreamFeatureFilter.EXCLUDE -> current.copy(
                        requiredVisualTags = current.requiredVisualTags - hdrTags.toSet(),
                        excludedVisualTags = (current.excludedVisualTags + hdrTags).distinct()
                    )
                    DebridStreamFeatureFilter.ONLY -> current.copy(
                        requiredVisualTags = (current.requiredVisualTags + hdrTags).distinct(),
                        excludedVisualTags = current.excludedVisualTags - hdrTags.toSet()
                    )
                }
            )
        }
    }

    suspend fun setStreamCodecFilter(filter: DebridStreamCodecFilter) {
        store().edit {
            it[streamCodecFilterKey] = filter.name
            it[streamPreferencesKey] = gson.toJson(
                currentStreamPreferences(it[streamPreferencesKey]).copy(
                    requiredEncodes = when (filter) {
                        DebridStreamCodecFilter.ANY -> emptyList()
                        DebridStreamCodecFilter.H264 -> listOf(DebridStreamEncode.AVC)
                        DebridStreamCodecFilter.HEVC -> listOf(DebridStreamEncode.HEVC)
                        DebridStreamCodecFilter.AV1 -> listOf(DebridStreamEncode.AV1)
                    }
                )
            )
        }
    }

    suspend fun setStreamPreferences(preferences: DebridStreamPreferences) {
        store().edit {
            val normalized = preferences.normalized()
            it[streamPreferencesKey] = gson.toJson(normalized)
            it[streamMaxResultsKey] = normalizeDebridStreamMaxResults(normalized.maxResults)
            it[streamSortModeKey] = legacyModeForSortCriteria(normalized.sortCriteria).name
        }
    }

    suspend fun setStreamTemplates(nameTemplate: String, descriptionTemplate: String) {
        store().edit {
            it[streamNameTemplateKey] = nameTemplate
            it[streamDescriptionTemplateKey] = descriptionTemplate
        }
    }

    suspend fun resetStreamTemplates() {
        setStreamTemplates(
            nameTemplate = DebridStreamFormatterDefaults.NAME_TEMPLATE,
            descriptionTemplate = DebridStreamFormatterDefaults.DESCRIPTION_TEMPLATE
        )
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String?, default: T): T {
        return runCatching { enumValueOf<T>(value.orEmpty()) }.getOrDefault(default)
    }

    private fun providerKey(providerId: String) = when (providerId) {
        DebridProviders.TORBOX_ID -> torboxApiKeyKey
        DebridProviders.PREMIUMIZE_ID -> premiumizeApiKeyKey
        DebridProviders.REAL_DEBRID_ID -> realDebridApiKeyKey
        else -> null
    }

    private fun hasAnyVisibleApiKeyAfter(
        prefs: androidx.datastore.preferences.core.MutablePreferences,
        updatedProviderId: String
    ): Boolean {
        return DebridProviders.visible().any { provider ->
            val key = providerKey(provider.id) ?: return@any false
            provider.id != updatedProviderId && !prefs[key].isNullOrBlank()
        }
    }

    private fun preferredResolverProviderId(
        stored: String?,
        torboxApiKey: String,
        premiumizeApiKey: String,
        realDebridApiKey: String
    ): String {
        val connected = listOf(
            DebridProviders.TORBOX_ID to torboxApiKey,
            DebridProviders.PREMIUMIZE_ID to premiumizeApiKey,
            DebridProviders.REAL_DEBRID_ID to realDebridApiKey
        ).mapNotNull { (id, key) ->
            DebridProviders.byId(id)
                ?.takeIf { provider -> provider.visibleInUi && key.isNotBlank() }
                ?.id
        }
        val normalizedStored = DebridProviders.byId(stored)?.id
        return connected.firstOrNull { it == normalizedStored } ?: connected.firstOrNull().orEmpty()
    }

    private fun parseStreamPreferences(value: String?): DebridStreamPreferences? {
        return runCatching {
            gson.fromJson(value, DebridStreamPreferences::class.java)?.normalized()
        }.getOrNull()
    }

    private fun currentStreamPreferences(value: String?): DebridStreamPreferences {
        return parseStreamPreferences(value) ?: DebridStreamPreferences()
    }

    private fun legacyStreamPreferences(
        maxResults: Int,
        sortMode: DebridStreamSortMode,
        minimumQuality: DebridStreamMinimumQuality,
        dolbyVisionFilter: DebridStreamFeatureFilter,
        hdrFilter: DebridStreamFeatureFilter,
        codecFilter: DebridStreamCodecFilter
    ): DebridStreamPreferences {
        var preferences = DebridStreamPreferences(
            maxResults = normalizeDebridStreamMaxResults(maxResults),
            sortCriteria = sortCriteriaForLegacyMode(sortMode),
            requiredResolutions = resolutionsForMinimumQuality(minimumQuality)
        )
        preferences = when (dolbyVisionFilter) {
            DebridStreamFeatureFilter.ANY -> preferences
            DebridStreamFeatureFilter.EXCLUDE -> preferences.copy(excludedVisualTags = preferences.excludedVisualTags + listOf(DebridStreamVisualTag.DV, DebridStreamVisualTag.DV_ONLY, DebridStreamVisualTag.HDR_DV))
            DebridStreamFeatureFilter.ONLY -> preferences.copy(requiredVisualTags = preferences.requiredVisualTags + listOf(DebridStreamVisualTag.DV, DebridStreamVisualTag.DV_ONLY, DebridStreamVisualTag.HDR_DV))
        }
        preferences = when (hdrFilter) {
            DebridStreamFeatureFilter.ANY -> preferences
            DebridStreamFeatureFilter.EXCLUDE -> preferences.copy(excludedVisualTags = preferences.excludedVisualTags + listOf(DebridStreamVisualTag.HDR, DebridStreamVisualTag.HDR10, DebridStreamVisualTag.HDR10_PLUS, DebridStreamVisualTag.HLG, DebridStreamVisualTag.HDR_ONLY, DebridStreamVisualTag.HDR_DV))
            DebridStreamFeatureFilter.ONLY -> preferences.copy(requiredVisualTags = preferences.requiredVisualTags + listOf(DebridStreamVisualTag.HDR, DebridStreamVisualTag.HDR10, DebridStreamVisualTag.HDR10_PLUS, DebridStreamVisualTag.HLG, DebridStreamVisualTag.HDR_ONLY, DebridStreamVisualTag.HDR_DV))
        }
        preferences = when (codecFilter) {
            DebridStreamCodecFilter.ANY -> preferences
            DebridStreamCodecFilter.H264 -> preferences.copy(requiredEncodes = listOf(DebridStreamEncode.AVC))
            DebridStreamCodecFilter.HEVC -> preferences.copy(requiredEncodes = listOf(DebridStreamEncode.HEVC))
            DebridStreamCodecFilter.AV1 -> preferences.copy(requiredEncodes = listOf(DebridStreamEncode.AV1))
        }
        return preferences.normalized()
    }

    private fun resolutionsForMinimumQuality(quality: DebridStreamMinimumQuality): List<DebridStreamResolution> {
        return DebridStreamResolution.defaultOrder.filter { it.value >= quality.minResolution && it != DebridStreamResolution.UNKNOWN }
    }

    private fun sortCriteriaForLegacyMode(mode: DebridStreamSortMode): List<DebridStreamSortCriterion> {
        return when (mode) {
            DebridStreamSortMode.DEFAULT -> DebridStreamSortCriterion.originalOrder
            DebridStreamSortMode.QUALITY_DESC -> listOf(
                DebridStreamSortCriterion(DebridStreamSortKey.RESOLUTION, DebridStreamSortDirection.DESC),
                DebridStreamSortCriterion(DebridStreamSortKey.QUALITY, DebridStreamSortDirection.DESC),
                DebridStreamSortCriterion(DebridStreamSortKey.SIZE, DebridStreamSortDirection.DESC)
            )
            DebridStreamSortMode.SIZE_DESC -> listOf(DebridStreamSortCriterion(DebridStreamSortKey.SIZE, DebridStreamSortDirection.DESC))
            DebridStreamSortMode.SIZE_ASC -> listOf(DebridStreamSortCriterion(DebridStreamSortKey.SIZE, DebridStreamSortDirection.ASC))
        }
    }

    private fun legacyModeForSortCriteria(criteria: List<DebridStreamSortCriterion>): DebridStreamSortMode {
        val normalized = criteria.map { it.key to it.direction }
        val bestQuality = DebridStreamSortCriterion.defaultOrder.map { it.key to it.direction }
        fun legacySignature(mode: DebridStreamSortMode) = sortCriteriaForLegacyMode(mode).map { it.key to it.direction }
        return when {
            normalized.isEmpty() -> DebridStreamSortMode.DEFAULT
            normalized == bestQuality -> DebridStreamSortMode.QUALITY_DESC
            normalized == legacySignature(DebridStreamSortMode.QUALITY_DESC) -> DebridStreamSortMode.QUALITY_DESC
            normalized == legacySignature(DebridStreamSortMode.SIZE_DESC) -> DebridStreamSortMode.SIZE_DESC
            normalized == legacySignature(DebridStreamSortMode.SIZE_ASC) -> DebridStreamSortMode.SIZE_ASC
            else -> DebridStreamSortMode.DEFAULT
        }
    }

    private fun DebridStreamPreferences.normalized(): DebridStreamPreferences {
        val preferredResolutionsValue: List<DebridStreamResolution>? = preferredResolutions
        val requiredResolutionsValue: List<DebridStreamResolution>? = requiredResolutions
        val excludedResolutionsValue: List<DebridStreamResolution>? = excludedResolutions
        val preferredQualitiesValue: List<com.nuvio.tv.domain.model.DebridStreamQuality>? = preferredQualities
        val requiredQualitiesValue: List<com.nuvio.tv.domain.model.DebridStreamQuality>? = requiredQualities
        val excludedQualitiesValue: List<com.nuvio.tv.domain.model.DebridStreamQuality>? = excludedQualities
        val preferredVisualTagsValue: List<DebridStreamVisualTag>? = preferredVisualTags
        val requiredVisualTagsValue: List<DebridStreamVisualTag>? = requiredVisualTags
        val excludedVisualTagsValue: List<DebridStreamVisualTag>? = excludedVisualTags
        val preferredAudioTagsValue: List<com.nuvio.tv.domain.model.DebridStreamAudioTag>? = preferredAudioTags
        val requiredAudioTagsValue: List<com.nuvio.tv.domain.model.DebridStreamAudioTag>? = requiredAudioTags
        val excludedAudioTagsValue: List<com.nuvio.tv.domain.model.DebridStreamAudioTag>? = excludedAudioTags
        val preferredAudioChannelsValue: List<com.nuvio.tv.domain.model.DebridStreamAudioChannel>? = preferredAudioChannels
        val requiredAudioChannelsValue: List<com.nuvio.tv.domain.model.DebridStreamAudioChannel>? = requiredAudioChannels
        val excludedAudioChannelsValue: List<com.nuvio.tv.domain.model.DebridStreamAudioChannel>? = excludedAudioChannels
        val preferredEncodesValue: List<DebridStreamEncode>? = preferredEncodes
        val requiredEncodesValue: List<DebridStreamEncode>? = requiredEncodes
        val excludedEncodesValue: List<DebridStreamEncode>? = excludedEncodes
        val preferredLanguagesValue: List<com.nuvio.tv.domain.model.DebridStreamLanguage>? = preferredLanguages
        val requiredLanguagesValue: List<com.nuvio.tv.domain.model.DebridStreamLanguage>? = requiredLanguages
        val excludedLanguagesValue: List<com.nuvio.tv.domain.model.DebridStreamLanguage>? = excludedLanguages
        val requiredReleaseGroupsValue: List<String>? = requiredReleaseGroups
        val excludedReleaseGroupsValue: List<String>? = excludedReleaseGroups
        val sortCriteriaValue: List<DebridStreamSortCriterion>? = sortCriteria
        return copy(
            maxResults = normalizeDebridStreamMaxResults(maxResults),
            maxPerResolution = maxPerResolution.coerceIn(0, 100),
            maxPerQuality = maxPerQuality.coerceIn(0, 100),
            sizeMinGb = sizeMinGb.coerceIn(0, 100),
            sizeMaxGb = sizeMaxGb.coerceIn(0, 100),
            preferredResolutions = preferredResolutionsValue?.ifEmpty { DebridStreamResolution.defaultOrder } ?: DebridStreamResolution.defaultOrder,
            requiredResolutions = requiredResolutionsValue.orEmpty(),
            excludedResolutions = excludedResolutionsValue.orEmpty(),
            preferredQualities = preferredQualitiesValue?.ifEmpty { com.nuvio.tv.domain.model.DebridStreamQuality.defaultOrder } ?: com.nuvio.tv.domain.model.DebridStreamQuality.defaultOrder,
            requiredQualities = requiredQualitiesValue.orEmpty(),
            excludedQualities = excludedQualitiesValue.orEmpty(),
            preferredVisualTags = preferredVisualTagsValue?.ifEmpty { DebridStreamVisualTag.defaultOrder } ?: DebridStreamVisualTag.defaultOrder,
            requiredVisualTags = requiredVisualTagsValue.orEmpty(),
            excludedVisualTags = excludedVisualTagsValue.orEmpty(),
            preferredAudioTags = preferredAudioTagsValue?.ifEmpty { com.nuvio.tv.domain.model.DebridStreamAudioTag.defaultOrder } ?: com.nuvio.tv.domain.model.DebridStreamAudioTag.defaultOrder,
            requiredAudioTags = requiredAudioTagsValue.orEmpty(),
            excludedAudioTags = excludedAudioTagsValue.orEmpty(),
            preferredAudioChannels = preferredAudioChannelsValue?.ifEmpty { com.nuvio.tv.domain.model.DebridStreamAudioChannel.defaultOrder } ?: com.nuvio.tv.domain.model.DebridStreamAudioChannel.defaultOrder,
            requiredAudioChannels = requiredAudioChannelsValue.orEmpty(),
            excludedAudioChannels = excludedAudioChannelsValue.orEmpty(),
            preferredEncodes = preferredEncodesValue?.ifEmpty { DebridStreamEncode.defaultOrder } ?: DebridStreamEncode.defaultOrder,
            requiredEncodes = requiredEncodesValue.orEmpty(),
            excludedEncodes = excludedEncodesValue.orEmpty(),
            preferredLanguages = preferredLanguagesValue.orEmpty(),
            requiredLanguages = requiredLanguagesValue.orEmpty(),
            excludedLanguages = excludedLanguagesValue.orEmpty(),
            requiredReleaseGroups = requiredReleaseGroupsValue.orEmpty().map { it.trim() }.filter { it.isNotBlank() }.distinct(),
            excludedReleaseGroups = excludedReleaseGroupsValue.orEmpty().map { it.trim() }.filter { it.isNotBlank() }.distinct(),
            sortCriteria = sortCriteriaValue ?: DebridStreamSortCriterion.originalOrder
        )
    }
}
