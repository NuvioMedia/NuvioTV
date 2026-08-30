package com.nuvio.tv.data.local

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MemberCatalogStorage @Inject constructor(
    @ApplicationContext context: Context
) {
    private val preferences = context.getSharedPreferences("member_catalogs", Context.MODE_PRIVATE)

    fun loadAvatarCatalogPayload(): String? = preferences.getString(AvatarCatalogKey, null)

    fun saveAvatarCatalogPayload(payload: String) {
        preferences.edit().putString(AvatarCatalogKey, payload).apply()
    }

    fun loadProfileBackgroundCatalogPayload(): String? =
        preferences.getString(ProfileBackgroundCatalogKey, null)

    fun saveProfileBackgroundCatalogPayload(payload: String) {
        preferences.edit().putString(ProfileBackgroundCatalogKey, payload).apply()
    }

    private companion object {
        const val AvatarCatalogKey = "avatar_catalog"
        const val ProfileBackgroundCatalogKey = "profile_background_catalog"
    }
}
