package com.nuvio.tv.core.sync

import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.TestPreferencesStore
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.PluginDataStore
import com.nuvio.tv.data.local.PluginSyncProfile
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import com.nuvio.tv.domain.model.PluginRepository
import com.nuvio.tv.domain.model.UserProfile
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.result.PostgrestResult
import io.github.jan.supabase.serializer.KotlinXSerializer
import io.ktor.http.Headers
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PluginSyncProfileTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        mockkStatic(Dispatchers::class)
        every { Dispatchers.IO } returns dispatcher
    }

    @After
    fun tearDown() {
        unmockkStatic(Dispatchers::class)
    }

    @Test
    fun `pinned plugin sync keeps writing the profile it started for`() = runTest(dispatcher) {
        val harness = harness()
        val repo = repo("profile-1")

        withContext(PluginSyncProfile(1)) {
            harness.activeProfileId.value = 2
            harness.plugins.saveRepositories(listOf(repo))
            assertEquals(listOf("profile-1"), harness.plugins.repositories.first().map { it.id })
        }

        assertEquals(listOf("profile-1"), harness.storedRepositoryIds(1))
        assertTrue(harness.storedRepositoryIds(2).isEmpty())
        harness.activeProfileId.value = 2
        assertTrue(harness.plugins.repositories.first().isEmpty())
        assertNull(PluginSyncProfile.current())
    }

    @Test
    fun `shared profile does not block a sync pinned to the primary plugin store`() = runTest(dispatcher) {
        val harness = harness(profileId = 3)
        val repo = repo("primary")

        harness.plugins.saveRepositories(listOf(repo))
        assertTrue(harness.storedRepositoryIds(1).isEmpty())

        withContext(PluginSyncProfile(1)) {
            harness.plugins.saveRepositories(listOf(repo))
        }

        assertEquals(listOf("primary"), harness.storedRepositoryIds(1))
        assertTrue(harness.storedRepositoryIds(3).isEmpty())
    }

    @Test
    fun `pinned push sends the captured profile after the active profile changes`() = runTest(dispatcher) {
        val harness = harness()
        val repo = repo("profile-1")
        withContext(PluginSyncProfile(1)) {
            harness.plugins.saveRepositories(listOf(repo))
            harness.activeProfileId.value = 2
            harness.service.pushToRemote().getOrThrow()
        }

        assertEquals(listOf(1), harness.pushedProfiles)
        assertEquals(listOf("profile-1"), harness.pushedRepositoryIds)
        assertTrue(harness.storedRepositoryIds(2).isEmpty())
    }

    private fun repo(id: String) = PluginRepository(
        id = id,
        name = id,
        url = "https://example.com/$id"
    )

    private fun harness(profileId: Int = 1): Harness {
        val stores = mutableMapOf<Pair<Int, String>, TestPreferencesStore>()
        val factory = mockk<ProfileDataStoreFactory>()
        every { factory.get(any(), any()) } answers {
            stores.getOrPut(firstArg<Int>() to secondArg<String>()) { TestPreferencesStore() }
        }
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
        val identity = mockk<SyncClientIdentity>()
        every { identity.currentClientId() } returns "nuvio-tv-plugin-sync-test"
        val pushedProfiles = mutableListOf<Int>()
        val pushedRepositoryIds = mutableListOf<String>()
        val postgrest = mockk<Postgrest>()
        every { postgrest.serializer } returns KotlinXSerializer()
        coEvery { postgrest.rpc(any(), any<JsonObject>()) } answers {
            val params = secondArg<JsonObject>()
            pushedProfiles += params.getValue("p_profile_id").jsonPrimitive.int
            pushedRepositoryIds += params.getValue("p_plugins").jsonArray.map { plugin ->
                plugin.jsonObject.getValue("url").jsonPrimitive.content.substringAfterLast('/')
            }
            PostgrestResult("", Headers.Empty, postgrest)
        }
        val plugins = PluginDataStore(
            mockk(relaxed = true),
            Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build(),
            factory,
            profileManager
        )
        val service = PluginSyncService(
            postgrest,
            authManager,
            plugins,
            profileManager,
            identity
        )
        return Harness(plugins, service, activeProfileId, factory, pushedProfiles, pushedRepositoryIds)
    }

    private data class Harness(
        val plugins: PluginDataStore,
        val service: PluginSyncService,
        val activeProfileId: MutableStateFlow<Int>,
        val factory: ProfileDataStoreFactory,
        val pushedProfiles: MutableList<Int>,
        val pushedRepositoryIds: MutableList<String>
    ) {
        fun storedRepositoryIds(profileId: Int): List<String> {
            val json = (factory.get(profileId, PluginDataStore.FEATURE) as TestPreferencesStore)
                .value[stringPreferencesKey("repositories")]
                ?: return emptyList()
            return Regex(""""id":"([^"]+)"""")
                .findAll(json)
                .map { it.groupValues[1] }
                .toList()
        }
    }
}
