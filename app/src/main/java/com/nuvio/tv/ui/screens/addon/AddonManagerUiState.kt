package com.nuvio.tv.ui.screens.addon

import android.graphics.Bitmap
import com.nuvio.tv.core.usenet.BuiltInUsenetOrder
import com.nuvio.tv.domain.model.Addon

internal const val BUILT_IN_USENET_KEY = "built-in-usenet"

data class AddonManagerUiState(
    val isLoading: Boolean = false,
    val isInstalling: Boolean = false,
    val installUrl: String = "",
    val installedAddons: List<Addon> = emptyList(),
    val movingAddonKey: String? = null,
    val error: String? = null,
    val transientMessage: String? = null,
    val transientMessageIsError: Boolean = false,
    // QR mode
    val isQrModeActive: Boolean = false,
    val qrCodeBitmap: Bitmap? = null,
    val serverUrl: String? = null,
    // Pending change from phone
    val pendingChange: PendingChangeInfo? = null,
    /** Null when built-in Usenet has no provider or indexer yet. */
    val builtInUsenet: BuiltInUsenetRow? = null,
    internal val draftUsenetIndex: Int? = null
) {
    internal val addonOrderKeys: List<String>
        get() = installedAddons.mapTo(mutableListOf()) { it.baseUrl }.apply {
            builtInUsenet?.let { row ->
                val index = draftUsenetIndex ?: BuiltInUsenetOrder.index(this, row.addonsAbove)
                add(index, BUILT_IN_USENET_KEY)
            }
        }
}

data class BuiltInUsenetRow(val enabled: Boolean, val addonsAbove: List<String>?, val firstUseAccepted: Boolean)

data class PendingChangeInfo(
    val changeId: String,
    val proposedUrls: List<String>,
    val proposedCatalogOrderKeys: List<String> = emptyList(),
    val proposedDisabledCatalogKeys: List<String> = emptyList(),
    val addedUrls: List<String>,
    val removedUrls: List<String>,
    val catalogsReordered: Boolean = false,
    val disabledCatalogNames: List<String> = emptyList(),
    val enabledCatalogNames: List<String> = emptyList(),
    val addedNames: Map<String, String> = emptyMap(),
    val removedNames: Map<String, String> = emptyMap(),
    val collectionsChanged: Boolean = false,
    val proposedCollectionsJson: String? = null,
    val proposedCollectionCount: Int = 0,
    val proposedDisabledCollectionKeys: List<String> = emptyList(),
    val proposedFollowAddonsOrder: Boolean? = null,
    val isApplying: Boolean = false,
    val trackingAddonNames: List<String>? = null,
    val trackingConsentUrls: List<String> = emptyList(),
    val awaitingTrackingConsent: Boolean = false
)
