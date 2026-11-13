package com.nuvio.tv.data.wetrakr

import android.content.Context
import com.nuvio.tv.core.profile.ProtectedProfilePreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AndroidWeTrakrAuthPersistence @Inject constructor(
    @ApplicationContext context: Context
) : WeTrakrAuthPersistence {
    private val preferences = ProtectedProfilePreferences(context, "wetrakr_auth", "com.nuvio.tv.wetrakr.credentials.v1")

    override fun read(profileId: Int): String? = preferences.read(profileId)
    override fun write(profileId: Int, value: String?) = preferences.write(profileId, value)
    override fun clear() = preferences.clear()
}
