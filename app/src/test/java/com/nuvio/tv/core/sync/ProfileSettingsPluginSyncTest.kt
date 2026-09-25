package com.nuvio.tv.core.sync

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.TestPreferencesStore
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.PluginDataStore
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import com.nuvio.tv.domain.model.UserProfile
import com.squareup.moshi.Moshi
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
class ProfileSettingsPluginSyncTest {
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
    fun `grouping uploads and restores after app data is cleared`() = runTest(dispatcher) {
        val harness = harness()
        harness.plugins.setGroupStreamsByRepository(true)
        harness.service.pushCurrentProfileToRemote().getOrThrow()
        harness.pluginStore(1).edit { it.clear() }
        assertFalse(harness.plugins.groupStreamsByRepository.first())

        assertTrue(harness.service.pullCurrentProfileFromRemote().getOrThrow())

        assertTrue(harness.plugins.groupStreamsByRepository.first())
    }

    @Test
    fun `explicitly disabled grouping restores over an enabled local setting`() = runTest(dispatcher) {
        val harness = harness()
        harness.plugins.setGroupStreamsByRepository(false)
        harness.service.pushCurrentProfileToRemote().getOrThrow()
        harness.plugins.setGroupStreamsByRepository(true)

        harness.service.pullCurrentProfileFromRemote().getOrThrow()

        assertFalse(harness.plugins.groupStreamsByRepository.first())
    }

    @Test
    fun `plugin repository and scraper state is excluded from uploads and preserved on restore`() = runTest(dispatcher) {
        val harness = harness()
        val localState = mapOf(
            "repositories" to "local repositories",
            "scrapers" to "local scrapers",
            "scraper_settings" to "local scraper settings",
            "future_plugin_setting" to "local future value"
        )
        harness.pluginStore(1).edit { prefs ->
            localState.forEach { (key, value) -> prefs[stringPreferencesKey(key)] = value }
            prefs[booleanPreferencesKey("plugins_enabled")] = false
        }
        harness.plugins.setGroupStreamsByRepository(true)
        harness.service.pushCurrentProfileToRemote().getOrThrow()
        val uploaded = harness.remote.getValue(1).getValue("features").jsonObject
            .getValue(PluginDataStore.FEATURE).jsonObject
        assertEquals(setOf(PluginDataStore.GROUP_STREAMS_BY_REPOSITORY), uploaded.keys)
        harness.remote[1] = settings(buildJsonObject {
            put(PluginDataStore.GROUP_STREAMS_BY_REPOSITORY, encodedBoolean(false))
            put("repositories", encodedString("remote repositories"))
            put("scrapers", encodedString("remote scrapers"))
            put("scraper_settings", encodedString("remote scraper settings"))
            put("future_plugin_setting", encodedString("remote future value"))
            put("plugins_enabled", encodedBoolean(true))
        })

        harness.service.pullCurrentProfileFromRemote().getOrThrow()

        assertFalse(harness.plugins.groupStreamsByRepository.first())
        assertFalse(harness.plugins.pluginsEnabled.first())
        localState.forEach { (key, value) ->
            assertEquals(value, harness.pluginStore(1).value[stringPreferencesKey(key)])
        }
    }

    @Test
    fun `older cloud settings without grouping preserve the local choice`() = runTest(dispatcher) {
        val harness = harness()
        harness.plugins.setGroupStreamsByRepository(true)

        listOf(settings(null), settings(JsonObject(emptyMap()))).forEach { oldSettings ->
            harness.remote[1] = oldSettings
            harness.service.pullCurrentProfileFromRemote().getOrThrow()
            assertTrue(harness.plugins.groupStreamsByRepository.first())
        }
    }

    @Test
    fun `malformed remote grouping does not replace a valid boolean`() = runTest(dispatcher) {
        val harness = harness()
        harness.plugins.setGroupStreamsByRepository(true)
        val invalidValues = listOf(
            encodedString("false"),
            buildJsonObject {
                put("type", "boolean")
                put("value", "invalid")
            },
            buildJsonObject {
                put("type", "boolean")
                put("value", JsonArray(emptyList()))
            }
        )

        invalidValues.forEach { invalid ->
            harness.remote[1] = settings(buildJsonObject {
                put(PluginDataStore.GROUP_STREAMS_BY_REPOSITORY, invalid)
            })
            harness.service.pullCurrentProfileFromRemote().getOrThrow()
            assertTrue(harness.plugins.groupStreamsByRepository.first())
        }
    }

    @Test
    fun `changing grouping automatically syncs and local plugin changes do not`() = runTest(dispatcher) {
        val harness = harness(authenticated = true)
        runCurrent()

        harness.pluginStore(1).edit { prefs ->
            prefs[stringPreferencesKey("repositories")] = "local repositories"
        }
        advanceTimeBy(2_000)
        runCurrent()
        assertTrue(harness.pushes.isEmpty())

        harness.plugins.setGroupStreamsByRepository(true)
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()

        assertEquals(listOf(1), harness.pushes)
        val remoteGrouping = harness.remote.getValue(1).getValue("features").jsonObject
            .getValue(PluginDataStore.FEATURE).jsonObject
            .getValue(PluginDataStore.GROUP_STREAMS_BY_REPOSITORY).jsonObject
        assertEquals("true", remoteGrouping.getValue("value").jsonPrimitive.content)
    }

    @Test
    fun `restoring grouping does not echo a push back to the cloud`() = runTest(dispatcher) {
        val harness = harness(authenticated = true)
        runCurrent()
        harness.remote[1] = settings(grouping(true))

        harness.service.pullCurrentProfileFromRemote().getOrThrow()
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()

        assertTrue(harness.plugins.groupStreamsByRepository.first())
        assertTrue(harness.pushes.isEmpty())
    }

    @Test
    fun `independent profiles restore their own grouping without altering the primary profile`() = runTest(dispatcher) {
        val harness = harness(profileId = 2)
        harness.remote[1] = settings(grouping(true))
        harness.remote[2] = settings(grouping(true))

        harness.service.pullCurrentProfileFromRemote().getOrThrow()

        assertTrue(harness.plugins.groupStreamsByRepository.first())
        assertEquals(listOf(2), harness.pulls)
        assertTrue(harness.pluginStore(1).value.asMap().isEmpty())
        harness.plugins.setGroupStreamsByRepository(false)
        harness.service.pushCurrentProfileToRemote().getOrThrow()
        assertEquals(listOf(2), harness.pushes)
        assertEquals(settings(grouping(true)), harness.remote[1])
    }

    @Test
    fun `shared profiles restore primary grouping even without their own cloud settings`() = runTest(dispatcher) {
        val harness = harness(profileId = 3)
        harness.remote[1] = settings(grouping(true))

        assertTrue(harness.service.pullCurrentProfileFromRemote().getOrThrow())

        assertTrue(harness.plugins.groupStreamsByRepository.first())
        assertEquals(listOf(1, 3), harness.pulls)
        assertTrue(harness.pluginStore(3).value.asMap().isEmpty())
        harness.plugins.setGroupStreamsByRepository(false)
        assertTrue(harness.plugins.groupStreamsByRepository.first())
    }

    @Test
    fun `shared profile settings never overwrite the primary grouping or other primary settings`() = runTest(dispatcher) {
        val harness = harness(profileId = 3, authenticated = true)
        harness.remote[1] = settings(grouping(true), theme = "cloud primary theme")
        harness.remote[3] = settings(grouping(false))
        val primaryTheme = harness.store(1, "theme_settings")
        primaryTheme.edit { it[stringPreferencesKey("theme")] = "primary theme" }
        runCurrent()

        harness.service.pullCurrentProfileFromRemote().getOrThrow()
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()

        assertTrue(harness.plugins.groupStreamsByRepository.first())
        assertEquals(false, harness.pluginStore(3).value[booleanPreferencesKey(PluginDataStore.GROUP_STREAMS_BY_REPOSITORY)])
        assertEquals("primary theme", primaryTheme.value[stringPreferencesKey("theme")])
        assertTrue(harness.pushes.isEmpty())
    }

    private fun harness(profileId: Int = 1, authenticated: Boolean = false): Harness {
        val stores = mutableMapOf<Pair<Int, String>, TestPreferencesStore>()
        val factory = mockk<ProfileDataStoreFactory>()
        every { factory.get(any(), any()) } answers {
            stores.getOrPut(firstArg<Int>() to secondArg<String>()) { TestPreferencesStore() }
        }
        every { factory.corruptedFileNames } returns mutableSetOf()
        val activeProfileId = MutableStateFlow(profileId)
        val profiles = MutableStateFlow(listOf(
            UserProfile(1, "Primary", "#000000"),
            UserProfile(2, "Independent", "#000000"),
            UserProfile(3, "Shared", "#000000", usesPrimaryPlugins = true)
        ))
        val profileManager = mockk<ProfileManager>()
        every { profileManager.activeProfileId } returns activeProfileId
        every { profileManager.profiles } returns profiles
        every { profileManager.activeProfile } answers { profiles.value.first { it.id == activeProfileId.value } }
        val authManager = mockk<AuthManager>(relaxed = true)
        every { authManager.isAuthenticated } returns authenticated
        val identity = mockk<SyncClientIdentity>()
        every { identity.currentClientId() } returns "nuvio-tv-plugin-sync-test"
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
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true)
        )
        services += service
        val plugins = PluginDataStore(mockk(relaxed = true), Moshi.Builder().build(), factory, profileManager)
        return Harness(service, plugins, factory, remote, pulls, pushes)
    }

    private fun grouping(enabled: Boolean) = buildJsonObject {
        put(PluginDataStore.GROUP_STREAMS_BY_REPOSITORY, encodedBoolean(enabled))
    }

    private fun encodedBoolean(value: Boolean) = buildJsonObject {
        put("type", "boolean")
        put("value", value)
    }

    private fun encodedString(value: String) = buildJsonObject {
        put("type", "string")
        put("value", value)
    }

    private fun settings(pluginSettings: JsonObject?, theme: String? = null) = buildJsonObject {
        put("version", 1)
        put("features", buildJsonObject {
            if (pluginSettings != null) put(PluginDataStore.FEATURE, pluginSettings)
            if (theme != null) put("theme_settings", buildJsonObject {
                put("theme", encodedString(theme))
            })
        })
    }

    private data class Harness(
        val service: ProfileSettingsSyncService,
        val plugins: PluginDataStore,
        val factory: ProfileDataStoreFactory,
        val remote: MutableMap<Int, JsonObject>,
        val pulls: MutableList<Int>,
        val pushes: MutableList<Int>
    ) {
        fun store(profileId: Int, feature: String) = factory.get(profileId, feature) as TestPreferencesStore
        fun pluginStore(profileId: Int) = store(profileId, PluginDataStore.FEATURE)
    }
}
