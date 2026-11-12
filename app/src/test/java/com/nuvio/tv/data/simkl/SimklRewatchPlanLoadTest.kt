package com.nuvio.tv.data.simkl

import com.nuvio.tv.TestPreferencesStore
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import com.nuvio.tv.data.local.TraktSettingsDataStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The plan gates every rewatch read and write. A device that never stored it (an account connected
 * before the plan was stored, or a mode that arrived through profile settings sync) has to read it on
 * its own, or rewatches stay off there while the setting says otherwise.
 */
class SimklRewatchPlanLoadTest {

    @Test
    fun `an unknown plan is read while rewatches are on`() {
        val state = SimklAuthState(isAuthenticated = true, accountType = null)

        assertTrue(shouldLoadSimklPlanForRewatches(SimklRewatchMode.AUTOMATIC, state))
        assertTrue(shouldLoadSimklPlanForRewatches(SimklRewatchMode.SEMI_AUTOMATIC, state))
    }

    @Test
    fun `the plan is not read while rewatches are off`() {
        val state = SimklAuthState(isAuthenticated = true, accountType = null)

        assertFalse(shouldLoadSimklPlanForRewatches(SimklRewatchMode.OFF, state))
    }

    @Test
    fun `a known plan is not read again, free included`() {
        assertFalse(
            shouldLoadSimklPlanForRewatches(
                SimklRewatchMode.AUTOMATIC,
                SimklAuthState(isAuthenticated = true, accountType = "free")
            )
        )
        assertFalse(
            shouldLoadSimklPlanForRewatches(
                SimklRewatchMode.AUTOMATIC,
                SimklAuthState(isAuthenticated = true, accountType = "vip")
            )
        )
    }

    @Test
    fun `a disconnected account reads no plan`() {
        assertFalse(
            shouldLoadSimklPlanForRewatches(
                SimklRewatchMode.AUTOMATIC,
                SimklAuthState(isAuthenticated = false, accountType = null)
            )
        )
    }

    @Test
    fun `a sync reads the unknown plan before the engine runs`() = runTest {
        val fixture = Fixture(mode = SimklRewatchMode.AUTOMATIC, accountType = null)

        fixture.repository.refresh(TrackingRefreshIntent.USER_INITIATED)

        coVerifyOrder {
            fixture.authRepository.ensurePlanLoaded()
            fixture.engine.synchronize(any())
        }
    }

    @Test
    fun `a sync with rewatches off does not read the plan`() = runTest {
        val fixture = Fixture(mode = SimklRewatchMode.OFF, accountType = null)

        fixture.repository.refresh(TrackingRefreshIntent.USER_INITIATED)

        coVerify(exactly = 1) { fixture.engine.synchronize(any()) }
        coVerify(exactly = 0) { fixture.authRepository.ensurePlanLoaded() }
    }

    @Test
    fun `a sync with a known plan does not read it again`() = runTest {
        val fixture = Fixture(mode = SimklRewatchMode.AUTOMATIC, accountType = "free")

        fixture.repository.refresh(TrackingRefreshIntent.USER_INITIATED)

        coVerify(exactly = 1) { fixture.engine.synchronize(any()) }
        coVerify(exactly = 0) { fixture.authRepository.ensurePlanLoaded() }
    }

    private class Fixture(mode: SimklRewatchMode, accountType: String?) {
        val engine = mockk<SimklSyncEngine>().also { engine ->
            coEvery { engine.synchronize(any()) } answers { firstArg() }
        }
        val authRepository = mockk<SimklAuthRepository>(relaxed = true)
        private val settingsDataStore = TraktSettingsDataStore(
            mockk<ProfileDataStoreFactory>().also { factory ->
                every { factory.get(any(), any()) } returns TestPreferencesStore()
            },
            mockk<ProfileManager>().also { manager ->
                every { manager.activeProfileId } returns MutableStateFlow(1)
            }
        )
        val repository: SimklSyncRepository

        init {
            runBlocking { settingsDataStore.setSimklRewatchMode(mode) }
            repository = SimklSyncRepository(
                engine = engine,
                storage = InMemoryStorage(),
                authRepository = authRepository,
                authStorage = mockk<SimklAuthStorage>(relaxed = true).also { storage ->
                    every { storage.state } returns MutableStateFlow(
                        SimklAuthState(isAuthenticated = true, accountType = accountType)
                    )
                },
                profileManager = mockk<ProfileManager>().also { manager ->
                    every { manager.activeProfileId } returns MutableStateFlow(1)
                },
                settingsDataStore = settingsDataStore
            )
        }
    }

    private class InMemoryStorage : SimklSyncStorage {
        private val snapshots = mutableMapOf<Int, String>()
        private val playbacks = mutableMapOf<Int, String>()

        override suspend fun load(profileId: Int): String? = snapshots[profileId]
        override suspend fun save(profileId: Int, payload: String) {
            snapshots[profileId] = payload
        }
        override suspend fun remove(profileId: Int) {
            snapshots.remove(profileId)
        }
        override suspend fun loadPlayback(profileId: Int): String? = playbacks[profileId]
        override suspend fun savePlayback(profileId: Int, payload: String) {
            playbacks[profileId] = payload
        }
        override suspend fun clearPlayback(profileId: Int) {
            playbacks.remove(profileId)
        }
    }
}
