package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.data.floppy.FloppyApiClient
import com.nuvio.tv.data.floppy.FloppyAuthStore
import com.nuvio.tv.data.floppy.MemoryPersistence
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FloppyTrackerViewModelTest {
    @get:Rule
    val mainDispatcher = MainDispatcherRule(UnconfinedTestDispatcher())

    @Test
    fun `a good address and token connect and are saved`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"results":[]}"""))
            val harness = Harness()

            harness.viewModel.onConnect(server.url("/").toString(), "  flp_secret  ")
            val state = harness.viewModel.uiState.first { !it.isLoading }

            assertTrue(state.isConnected)
            assertNull(state.error)
            assertEquals("flp_secret", harness.auth.credentials()?.token)
            assertEquals("Bearer flp_secret", server.takeRequest().getHeader("Authorization"))
        }
    }

    @Test
    fun `an address that is not a server never touches the network`() = runTest {
        val harness = Harness()

        harness.viewModel.onConnect("not a url", "flp_secret")

        assertEquals(FloppyConnectionError.INVALID_ADDRESS, harness.viewModel.uiState.value.error)
        assertFalse(harness.viewModel.uiState.value.isConnected)
    }

    @Test
    fun `an empty token is refused before connecting`() = runTest {
        val harness = Harness()

        harness.viewModel.onConnect("https://floppy.example.com", "   ")

        assertEquals(FloppyConnectionError.MISSING_TOKEN, harness.viewModel.uiState.value.error)
    }

    @Test
    fun `a refused token is reported and nothing is saved`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403))
            val harness = Harness()

            harness.viewModel.onConnect(server.url("/").toString(), "flp_bad")
            val state = harness.viewModel.uiState.first { !it.isLoading }

            assertEquals(FloppyConnectionError.REJECTED, state.error)
            assertFalse(state.isConnected)
            assertNull(harness.auth.credentials())
        }
    }

    @Test
    fun `an unreachable address is reported`() = runTest {
        val dead = MockWebServer().use { it.url("/").toString() }
        val harness = Harness()

        harness.viewModel.onConnect(dead, "flp_secret")
        val state = harness.viewModel.uiState.first { !it.isLoading }

        assertEquals(FloppyConnectionError.UNREACHABLE, state.error)
        assertFalse(state.isConnected)
    }

    @Test
    fun `a failed save is reported instead of claiming a connection`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{}"))
            val harness = Harness().apply { persistence.failWrites = true }

            harness.viewModel.onConnect(server.url("/").toString(), "flp_secret")
            val state = harness.viewModel.uiState.first { !it.isLoading }

            assertEquals(FloppyConnectionError.SAVE_FAILED, state.error)
            assertFalse(state.isConnected)
        }
    }

    @Test
    fun `disconnecting clears the connection`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{}"))
            val harness = Harness()
            harness.viewModel.onConnect(server.url("/").toString(), "flp_secret")
            harness.viewModel.uiState.first { !it.isLoading }

            harness.viewModel.onDisconnect()

            assertFalse(harness.viewModel.uiState.value.isConnected)
            assertNull(harness.auth.credentials())
            assertNull(harness.viewModel.uiState.value.error)
        }
    }

    @Test
    fun `reopening the dialog clears an old error`() = runTest {
        val harness = Harness()
        harness.viewModel.onConnect("not a url", "x")

        harness.viewModel.onDialogOpened()

        assertNull(harness.viewModel.uiState.value.error)
    }

    private class Harness {
        val persistence = MemoryPersistence()
        val auth = FloppyAuthStore(persistence)
        val viewModel = FloppyTrackerViewModel(auth, FloppyApiClient(OkHttpClient(), "9.9.9"))
    }
}
