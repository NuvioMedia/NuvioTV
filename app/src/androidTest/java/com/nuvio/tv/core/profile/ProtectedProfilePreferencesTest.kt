package com.nuvio.tv.core.profile

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.KeyStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProtectedProfilePreferencesTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "protected_profile_instrumentation"
    private val alias = "protected_profile_instrumentation.v1"
    private val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    @After
    fun clearTestCredentials() {
        preferences.edit().clear().commit()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
    }

    @Test
    fun encryptedCredentialsSurviveRecreationAndUseUniqueNonces() {
        val store = ProtectedProfilePreferences(context, name, alias)
        store.write(1, "private-token-one")
        store.write(2, "private-token-two")
        val firstCiphertext = preferences.getString("profile.1", null)
        assertFalse(firstCiphertext.orEmpty().contains("private-token"))
        store.write(1, "private-token-one")
        assertNotEquals(firstCiphertext, preferences.getString("profile.1", null))
        val restored = ProtectedProfilePreferences(context, name, alias)
        assertEquals("private-token-one", restored.read(1))
        assertEquals("private-token-two", restored.read(2))
        restored.write(1, null)
        assertNull(restored.read(1))
        assertEquals("private-token-two", restored.read(2))
    }

    @Test
    fun copiedOrCorruptedCredentialsAreRejectedWithoutAffectingOtherProfiles() {
        val store = ProtectedProfilePreferences(context, name, alias)
        store.write(1, "profile-one-token")
        preferences.edit().putString("profile.2", preferences.getString("profile.1", null)).commit()
        assertNull(store.read(2))
        assertFalse(preferences.contains("profile.2"))
        assertEquals("profile-one-token", store.read(1))
        preferences.edit().putString("profile.2", "invalid-ciphertext").commit()
        assertNull(store.read(2))
        assertEquals("profile-one-token", store.read(1))
    }
}
