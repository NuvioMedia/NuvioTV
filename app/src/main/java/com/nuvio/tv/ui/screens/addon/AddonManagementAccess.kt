package com.nuvio.tv.ui.screens.addon

import com.nuvio.tv.core.server.AddonWebConfigMode
import com.nuvio.tv.domain.model.UserProfile

internal object AddonManagementAccess {

    fun isReadOnly(profile: UserProfile?): Boolean {
        return profile?.let { !it.isPrimary && it.usesPrimaryAddons } == true
    }

    fun webConfigMode(profile: UserProfile?): AddonWebConfigMode {
        return if (isReadOnly(profile)) {
            AddonWebConfigMode.COLLECTIONS_ONLY
        } else {
            AddonWebConfigMode.FULL
        }
    }
}
