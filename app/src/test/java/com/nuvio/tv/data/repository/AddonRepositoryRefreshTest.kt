package com.nuvio.tv.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.sync.AddonRefreshConflictException
import com.nuvio.tv.core.sync.AddonSyncService
import com.nuvio.tv.core.sync.RemoteAddonEntry
import com.nuvio.tv.core.sync.RemoteAddonSnapshot
import com.nuvio.tv.data.local.AddonPreferences
import com.nuvio.tv.data.remote.api.AddonApi
import com.nuvio.tv.data.remote.dto.AddonManifestDto
import com.nuvio.tv.domain.model.AuthState
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.fail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

class AddonRepositoryRefreshTest {

    private val api = mockk<AddonApi>()
    private val preferences = mockk<AddonPreferences>()
    private val addonSyncService = mockk<AddonSyncService>()
    private val authManager = mockk<AuthManager>()
    private val context = mockk<Context>(relaxed = true)
    private val sharedPreferences = mockk<SharedPreferences>(relaxed = true)

    private lateinit var repository: AddonRepositoryImpl

    @Before
    fun setUp() {
        every { context.getSharedPreferences(any(), any()) } returns sharedPreferences
        every { sharedPreferences.contains(any()) } returns false
        every { sharedPreferences.getString(any(), any()) } returns null
        every { authManager.isAuthenticated } returns false
        every { authManager.authState } returns MutableStateFlow(AuthState.SignedOut)
        repository = AddonRepositoryImpl(
            api = api,
            preferences = preferences,
            addonSyncService = addonSyncService,
            authManager = authManager,
            context = context
        )
    }

    @Test
    fun `successful empty remote snapshot clears the explicit profile`() = runTest {
        coEvery { preferences.getInstalledAddonUrls(1) } returns listOf(TEST_URL)
        coJustRun {
            preferences.replaceFromRemote(
                profileId = 1,
                orderedUrls = emptyList(),
                names = emptyMap(),
                enabledStates = emptyMap()
            )
        }

        val summary = repository.applyRemoteAddonSnapshot(
            RemoteAddonSnapshot(profileId = 1, addons = emptyList())
        )

        assertEquals(0, summary.addonCount)
        assertTrue(summary.completedFully)
        assertEquals(1L, repository.refreshRevision.value)
        coVerify(exactly = 1) {
            preferences.replaceFromRemote(
                profileId = 1,
                orderedUrls = emptyList(),
                names = emptyMap(),
                enabledStates = emptyMap()
            )
        }
        coVerify(exactly = 0) { api.getManifest(any()) }
    }

    @Test
    fun `manual refresh fetches an unchanged addon URL again`() = runTest {
        coEvery { preferences.getInstalledAddonUrls(1) } returns listOf(TEST_URL)
        coJustRun {
            preferences.replaceFromRemote(
                profileId = 1,
                orderedUrls = listOf(TEST_URL),
                names = emptyMap(),
                enabledStates = mapOf(TEST_URL to true)
            )
        }
        coEvery { api.getManifest(any()) } returnsMany listOf(
            Response.success(manifest(name = "Old name")),
            Response.success(manifest(name = "New name"))
        )

        assertTrue(repository.fetchAddon(TEST_URL) is NetworkResult.Success)
        val summary = repository.applyRemoteAddonSnapshot(
            RemoteAddonSnapshot(
                profileId = 1,
                addons = listOf(RemoteAddonEntry(TEST_URL, null, enabled = true))
            )
        )

        assertEquals(1, summary.refreshedManifestCount)
        assertEquals(0, summary.failedManifestCount)
        assertEquals(1L, repository.refreshRevision.value)
        coVerify(exactly = 2) { api.getManifest("$TEST_URL/manifest.json") }
    }

    @Test
    fun `manifest failure reports partial refresh after applying cloud snapshot`() = runTest {
        coEvery { preferences.getInstalledAddonUrls(2) } returns emptyList()
        coJustRun {
            preferences.replaceFromRemote(
                profileId = 2,
                orderedUrls = listOf(TEST_URL),
                names = mapOf(TEST_URL to "Custom"),
                enabledStates = mapOf(TEST_URL to true)
            )
        }
        coEvery { api.getManifest(any()) } throws IllegalStateException("offline")

        val summary = repository.applyRemoteAddonSnapshot(
            RemoteAddonSnapshot(
                profileId = 2,
                addons = listOf(RemoteAddonEntry(TEST_URL, "Custom", enabled = true))
            )
        )

        assertEquals(1, summary.addonCount)
        assertEquals(0, summary.refreshedManifestCount)
        assertEquals(1, summary.failedManifestCount)
        assertFalse(summary.completedFully)
        assertEquals(1L, repository.refreshRevision.value)
        coVerify(exactly = 1) {
            preferences.replaceFromRemote(
                profileId = 2,
                orderedUrls = listOf(TEST_URL),
                names = mapOf(TEST_URL to "Custom"),
                enabledStates = mapOf(TEST_URL to true)
            )
        }
    }

    @Test
    fun `newer local mutation is not overwritten by an in flight refresh`() = runTest {
        val expectedRevision = repository.currentLocalMutationRevision()
        coJustRun { preferences.addAddon(TEST_URL) }
        repository.addAddon(TEST_URL)

        try {
            repository.applyRemoteAddonSnapshot(
                snapshot = RemoteAddonSnapshot(profileId = 1, addons = emptyList()),
                expectedLocalMutationRevision = expectedRevision
            )
            fail("Expected AddonRefreshConflictException")
        } catch (_: AddonRefreshConflictException) {
            // Expected: the remote snapshot must not replace the newer local edit.
        }

        coVerify(exactly = 0) {
            preferences.replaceFromRemote(
                profileId = any(),
                orderedUrls = any(),
                names = any(),
                enabledStates = any()
            )
        }
    }

    private fun manifest(name: String) = AddonManifestDto(
        id = "com.example.addon",
        name = name,
        version = "1.0.0"
    )

    private companion object {
        const val TEST_URL = "https://example.com/addon"
    }
}
