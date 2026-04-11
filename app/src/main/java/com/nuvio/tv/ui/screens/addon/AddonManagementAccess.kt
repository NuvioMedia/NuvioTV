package com.nuvio.tv.ui.screens.addon

import com.nuvio.tv.core.server.AddonConfigServer
import com.nuvio.tv.domain.model.UserProfile

internal object AddonManagementAccess {

    fun isReadOnly(profile: UserProfile?): Boolean {
        return profile?.let { !it.isPrimary && it.usesPrimaryAddons } == true
    }

    fun webConfigMode(profile: UserProfile?): AddonConfigServer.WebConfigMode {
        return if (isReadOnly(profile)) {
            AddonConfigServer.WebConfigMode.COLLECTIONS_ONLY
        } else {
            AddonConfigServer.WebConfigMode.FULL
        }
    }
}