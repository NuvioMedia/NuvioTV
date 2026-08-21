package com.nuvio.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nuvio.tv.domain.model.CosmeticEntitlement
import com.nuvio.tv.domain.model.CosmeticEntitlements
import com.nuvio.tv.domain.model.MemberAccess
import com.nuvio.tv.domain.model.MemberTier
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private val Context.memberAccessDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "member_access"
)

@Singleton
class MemberAccessDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dataStore = context.memberAccessDataStore
    private val userIdKey = stringPreferencesKey("user_id")
    private val tierKey = stringPreferencesKey("tier")
    private val entitlementsKey = stringSetPreferencesKey("entitlements")
    private val legacyBrandingFileKey = stringPreferencesKey("branding_file")
    private val legacyBrandingPathKey = stringPreferencesKey("branding_path")

    suspend fun get(userId: String): MemberAccess? = withContext(Dispatchers.IO) {
        val preferences = dataStore.data.first()
        if (preferences[userIdKey] != userId) return@withContext null

        val tier = preferences[tierKey]?.let { storedTier ->
            MemberTier.entries.firstOrNull { it.name == storedTier }
        } ?: return@withContext MemberAccess.None
        val entitlements = preferences[entitlementsKey]
            .orEmpty()
            .mapNotNull { storedEntitlement ->
                CosmeticEntitlement.entries.firstOrNull { it.name == storedEntitlement }
            }
            .toSet()
        MemberAccess(
            tier = tier,
            entitlements = CosmeticEntitlements(entitlements)
        )
    }

    suspend fun save(userId: String, access: MemberAccess): MemberAccess = withContext(Dispatchers.IO) {
        dataStore.edit { preferences ->
            preferences[userIdKey] = userId
            preferences.remove(legacyBrandingFileKey)
            preferences.remove(legacyBrandingPathKey)
            val tier = access.tier
            if (tier == null) {
                preferences.remove(tierKey)
                preferences.remove(entitlementsKey)
            } else {
                preferences[tierKey] = tier.name
                preferences[entitlementsKey] = access.entitlements.unlocked.map { it.name }.toSet()
            }
        }

        clearLegacyBrandingFiles()
        if (access.tier == null) MemberAccess.None else access
    }

    private fun clearLegacyBrandingFiles() {
        context.filesDir.resolve("member_access").listFiles()?.forEach { file -> file.delete() }
    }
}
