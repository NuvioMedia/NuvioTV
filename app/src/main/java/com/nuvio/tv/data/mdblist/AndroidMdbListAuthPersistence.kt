package com.nuvio.tv.data.mdblist

import android.content.Context
import com.nuvio.tv.core.profile.ProtectedProfilePreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AndroidMdbListAuthPersistence @Inject constructor(
    @ApplicationContext context: Context
) : MdbListAuthPersistence {
    private val preferences = ProtectedProfilePreferences(context, "mdblist_auth", "com.nuvio.tv.mdblist.credentials.v1")

    override fun read(profileId: Int): String? = preferences.read(profileId)
    override fun write(profileId: Int, value: String?) = preferences.write(profileId, value)
    override fun clear() = preferences.clear()
}
