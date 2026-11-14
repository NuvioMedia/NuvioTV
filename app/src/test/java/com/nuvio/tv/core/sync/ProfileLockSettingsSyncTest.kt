package com.nuvio.tv.core.sync

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.TestPreferencesStore
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import com.nuvio.tv.data.local.ProfileLockSettingsDataStore
import com.nuvio.tv.domain.model.UserProfile
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.result.PostgrestResult
import io.github.jan.supabase.serializer.KotlinXSerializer
import io.ktor.http.Headers
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileLockSettingsSyncTest {
    private val dispatcher = StandardTestDispatcher()
    private val services = mutableListOf<ProfileSettingsSyncService>()

    @Before
    fun setUp() {
        mockkStatic(Dispatchers::class)
        every { Dispatchers.IO } returns dispatcher
    }

    @After
    fun tearDown() {
        services.forEach { service ->
            val scope = ProfileSettingsSyncService::class.java.getDeclaredField("scope")
                .apply { isAccessible = true }.get(service) as CoroutineScope
            scope.cancel()
        }
        unmockkStatic(Dispatchers::class)
    }

    @Test
    fun `the switch is uploaded with the profile's settings and restored from them`() = runTest(dispatcher) {
        val harness = harness()
        harness.lock.setAskPinOnAppStart(1, false)

        harness.service.pushCurrentProfileToRemote().getOrThrow()
        assertEquals("false", harness.remoteAsk(1))
        harness.lockStore(1).edit { it.clear() }
        assertTrue(harness.lock.askPinOnAppStart(1).first())

        harness.service.pullCurrentProfileFromRemote().getOrThrow()

        assertFalse(harness.lock.askPinOnAppStart(1).first())
    }

    @Test
    fun `changing another profile's switch keeps that profile's newer cloud settings`() = runTest(dispatcher) {
        val harness = harness(authenticated = true)
        harness.remote[2] = settings(theme = "cloud theme")
        harness.store(2, "theme_settings").edit { it[stringPreferencesKey("theme")] = "old local theme" }
        runCurrent()

        harness.service.updateProfileSettings(2) { harness.lock.setAskPinOnAppStart(2, false) }
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()

        assertEquals(listOf(2), harness.pulls)
        assertEquals(listOf(2), harness.pushes)
        assertEquals("cloud theme", harness.remoteTheme(2))
        assertEquals("false", harness.remoteAsk(2))
        assertEquals("cloud theme", harness.store(2, "theme_settings").value[stringPreferencesKey("theme")])
        assertFalse(harness.lock.askPinOnAppStart(2).first())
        assertTrue(harness.lock.askPinOnAppStart(1).first())
    }

    @Test
    fun `changing the active profile's switch is uploaded by the usual settings sync`() = runTest(dispatcher) {
        val harness = harness(authenticated = true)
        runCurrent()

        harness.service.updateProfileSettings(1) { harness.lock.setAskPinOnAppStart(1, false) }
        assertTrue(harness.pulls.isEmpty())
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()

        assertEquals(listOf(1), harness.pushes)
        assertEquals("false", harness.remoteAsk(1))
    }

    @Test
    fun `signed out, the switch changes on this device only`() = runTest(dispatcher) {
        val harness = harness(authenticated = false)

        harness.service.updateProfileSettings(2) { harness.lock.setAskPinOnAppStart(2, false) }

        assertTrue(harness.pulls.isEmpty())
        assertTrue(harness.pushes.isEmpty())
        assertFalse(harness.lock.askPinOnAppStart(2).first())
    }

    @Test
    fun `when the cloud copy cannot be read, the change stays on this device without an upload`() = runTest(dispatcher) {
        val harness = harness(authenticated = true, failPulls = true)
        harness.remote[2] = settings(theme = "cloud theme")

        harness.service.updateProfileSettings(2) { harness.lock.setAskPinOnAppStart(2, false) }

        assertTrue(harness.pushes.isEmpty())
        assertEquals("cloud theme", harness.remoteTheme(2))
        assertFalse(harness.lock.askPinOnAppStart(2).first())
    }

    private fun harness(authenticated: Boolean = false, failPulls: Boolean = false): Harness {
        val stores = mutableMapOf<Pair<Int, String>, TestPreferencesStore>()
        val factory = mockk<ProfileDataStoreFactory>()
        every { factory.get(any(), any()) } answers {
            stores.getOrPut(firstArg<Int>() to secondArg<String>()) { TestPreferencesStore() }
        }
        every { factory.corruptedFileNames } returns mutableSetOf()
        val activeProfileId = MutableStateFlow(1)
        val profiles = MutableStateFlow(listOf(
            UserProfile(1, "Main", "#000000"),
            UserProfile(2, "Kid", "#000000")
        ))
        val profileManager = mockk<ProfileManager>()
        every { profileManager.activeProfileId } returns activeProfileId
        every { profileManager.profiles } returns profiles
        every { profileManager.activeProfile } answers { profiles.value.first { it.id == activeProfileId.value } }
        val authManager = mockk<AuthManager>(relaxed = true)
        every { authManager.isAuthenticated } returns authenticated
        val identity = mockk<SyncClientIdentity>()
        every { identity.currentClientId() } returns "nuvio-tv-lock-settings-test"
        val remote = mutableMapOf<Int, JsonObject>()
        val pulls = mutableListOf<Int>()
        val pushes = mutableListOf<Int>()
        val postgrest = mockk<Postgrest>()
        every { postgrest.serializer } returns KotlinXSerializer()
        coEvery { postgrest.rpc(any(), any<JsonObject>()) } answers {
            val params = secondArg<JsonObject>()
            val requestedProfile = params.getValue("p_profile_id").jsonPrimitive.int
            val response = when (firstArg<String>()) {
                "sync_push_profile_settings_blob" -> {
                    pushes += requestedProfile
                    remote[requestedProfile] = params.getValue("p_settings_json").jsonObject
                    ""
                }
                "sync_pull_profile_settings_blob" -> {
                    pulls += requestedProfile
                    if (failPulls) error("offline")
                    val blob = remote[requestedProfile]
                    JsonArray(if (blob == null) emptyList() else listOf(buildJsonObject {
                        put("profile_id", requestedProfile)
                        put("settings_json", blob)
                    })).toString()
                }
                else -> error("Unexpected RPC")
            }
            PostgrestResult(response, Headers.Empty, postgrest)
        }
        val service = ProfileSettingsSyncService(
            authManager, postgrest, profileManager, factory, identity,
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true)
        )
        services += service
        return Harness(service, ProfileLockSettingsDataStore(factory), factory, remote, pulls, pushes)
    }

    private fun encodedString(value: String) = buildJsonObject {
        put("type", "string")
        put("value", value)
    }

    private fun settings(theme: String) = buildJsonObject {
        put("version", 1)
        put("features", buildJsonObject {
            put("theme_settings", buildJsonObject {
                put("theme", encodedString(theme))
            })
        })
    }

    private data class Harness(
        val service: ProfileSettingsSyncService,
        val lock: ProfileLockSettingsDataStore,
        val factory: ProfileDataStoreFactory,
        val remote: MutableMap<Int, JsonObject>,
        val pulls: MutableList<Int>,
        val pushes: MutableList<Int>
    ) {
        fun store(profileId: Int, feature: String) = factory.get(profileId, feature) as TestPreferencesStore
        fun lockStore(profileId: Int) = store(profileId, ProfileLockSettingsDataStore.FEATURE)

        private fun remoteValue(profileId: Int, feature: String, key: String): String? =
            remote[profileId]?.get("features")?.jsonObject
                ?.get(feature)?.jsonObject
                ?.get(key)?.jsonObject
                ?.get("value")?.jsonPrimitive?.content

        fun remoteAsk(profileId: Int) =
            remoteValue(profileId, ProfileLockSettingsDataStore.FEATURE, "ask_pin_on_app_start")

        fun remoteTheme(profileId: Int) = remoteValue(profileId, "theme_settings", "theme")
    }
}
