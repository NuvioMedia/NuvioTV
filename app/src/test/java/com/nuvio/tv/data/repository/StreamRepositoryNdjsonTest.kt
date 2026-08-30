package com.nuvio.tv.data.repository

import android.content.Context
import com.nuvio.tv.R
import com.nuvio.tv.core.debrid.DebridStreamPresentation
import com.nuvio.tv.core.debrid.LocalDebridAvailabilityService
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.plugin.PluginManager
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.data.remote.NdjsonHttpException
import com.nuvio.tv.data.remote.NdjsonStreamFetcher
import com.nuvio.tv.data.remote.api.AddonApi
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.AddonResource
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.DebridSettings
import com.nuvio.tv.domain.repository.AddonRepository
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Covers the `/stream` line reader: NDJSON batches and plain JSON documents. */
class StreamRepositoryNdjsonTest {

    private val addon = Addon(
        id = "ndjson-addon",
        name = "NDJSON Addon",
        version = "1.0.0",
        description = null,
        logo = null,
        baseUrl = "https://addon.example",
        catalogs = emptyList(),
        types = emptyList(),
        resources = listOf(
            AddonResource(
                name = "stream",
                types = listOf("movie"),
                idPrefixes = listOf("tt")
            )
        )
    )

    @Test
    fun `each ndjson batch is rendered as it arrives, best resolution first`() = runBlocking {
        val repository = newRepository(
            NdjsonResponse(
                contentType = "application/x-ndjson",
                lines = listOf(
                    """{"streams":[{"name":"A 1080p","url":"https://a.example/1"}]}""",
                    """{"streams":[{"name":"B 2160p","url":"https://b.example/2"}]}"""
                )
            )
        )

        val successes = repository.getStreamsFromAllAddons(type = "movie", videoId = "tt1")
            .toList()
            .filterIsInstance<NetworkResult.Success<List<AddonStreams>>>()

        // First batch renders before the response completes, then the accumulated set.
        // The final emission (isFinal=true) is a duplicate of the last batch and flips the chip.
        val sizes = successes.map { it.data.single().streams.size }
        assertEquals(listOf(1, 2), sizes.distinct())
        assertTrue(sizes.size >= 2)
        // The slower 2160p source arrived last but still sorts above the 1080p one.
        assertEquals(
            listOf("B 2160p", "A 1080p"),
            successes.last().data.single().streams.map { it.name }
        )
        assertTrue(successes.last().data.single().isFinal)
    }

    @Test
    fun `repeated stream keys update in place instead of duplicating`() = runBlocking {
        val repository = newRepository(
            NdjsonResponse(
                contentType = "application/x-ndjson",
                lines = listOf(
                    """{"streams":[{"name":"A","url":"https://a.example/1"}]}""",
                    """{"streams":[{"name":"A refreshed","url":"https://a.example/1"}]}"""
                )
            )
        )

        val streams = repository.getStreamsFromAllAddons(type = "movie", videoId = "tt1")
            .toList()
            .filterIsInstance<NetworkResult.Success<List<AddonStreams>>>()
            .last()
            .data
            .single()
            .streams

        assertEquals(listOf("A refreshed"), streams.map { it.name })
    }

    @Test
    fun `a plain json response keeps the order the addon sent`() = runBlocking {
        val repository = newRepository(
            NdjsonResponse(
                contentType = "application/json",
                lines = listOf(
                    """{"streams":[{"name":"A 1080p","url":"https://a.example/1"},{"name":"B 2160p","url":"https://b.example/2"}]}"""
                )
            )
        )

        val streams = repository.getStreamsFromAllAddons(type = "movie", videoId = "tt1")
            .toList()
            .filterIsInstance<NetworkResult.Success<List<AddonStreams>>>()
            .last()
            .data
            .single()
            .streams

        // No reordering: buffered JSON is left exactly as the addon ranked it.
        assertEquals(listOf("A 1080p", "B 2160p"), streams.map { it.name })
    }

    @Test
    fun `a 404 is reported as missing streams rather than a request failure`() = runBlocking {
        // "HTTP 404" is what the fetcher reports; only the status code can classify it,
        // since the message no longer spells out "Not Found".
        val repository = newRepository(NdjsonResponse(failure = NdjsonHttpException(404, "HTTP 404")))

        val error = repository.getStreamsFromAllAddons(type = "movie", videoId = "tt1")
            .toList()
            .filterIsInstance<NetworkResult.Error>()
            .last()

        assertEquals(MISSING_STREAMS_MESSAGE, error.message)
    }

    @Test
    fun `a 500 is still reported as a request failure`() = runBlocking {
        val repository = newRepository(NdjsonResponse(failure = NdjsonHttpException(500, "Server Error")))

        val error = repository.getStreamsFromAllAddons(type = "movie", videoId = "tt1")
            .toList()
            .filterIsInstance<NetworkResult.Error>()
            .last()

        assertEquals(ADDON_ISSUES_MESSAGE, error.message)
    }

    private class NdjsonResponse(
        val contentType: String? = null,
        val lines: List<String> = emptyList(),
        val failure: Exception? = null
    )

    private fun newRepository(response: NdjsonResponse): StreamRepositoryImpl {
        val fetcher = mockk<NdjsonStreamFetcher>()
        coEvery { fetcher.fetchLines(any(), any(), any()) } coAnswers {
            val onContentType = arg<(String?) -> Unit>(1)
            val onLine = arg<suspend (String) -> Unit>(2)
            response.failure?.let { failure ->
                onContentType(response.contentType)
                throw failure
            }
            onContentType(response.contentType)
            response.lines.forEach { line -> onLine(line) }
        }

        val addonRepository = mockk<AddonRepository>()
        every { addonRepository.getInstalledAddons() } returns flowOf(listOf(addon))
        coEvery { addonRepository.fetchAddon(addon.baseUrl) } returns NetworkResult.Success(addon)

        val pluginManager = mockk<PluginManager>(relaxed = true)
        every { pluginManager.enabledScrapers } returns flowOf(emptyList())
        every { pluginManager.pluginsEnabled } returns flowOf(false)
        every { pluginManager.groupStreamsByRepository } returns flowOf(false)
        every { pluginManager.repositories } returns flowOf(emptyList())

        val profileManager = mockk<ProfileManager>(relaxed = true)
        every { profileManager.activeProfileId } returns MutableStateFlow(1)

        val debridSettingsDataStore = mockk<DebridSettingsDataStore>()
        every { debridSettingsDataStore.settings } returns flowOf(DebridSettings())

        val presentation = mockk<DebridStreamPresentation>()
        every { presentation.apply(any(), any<DebridSettings>(), any(), any()) } answers {
            firstArg<List<AddonStreams>>()
        }

        val availability = mockk<LocalDebridAvailabilityService>()
        coEvery { availability.markChecking(any()) } coAnswers { firstArg<List<AddonStreams>>() }
        coEvery { availability.annotateCachedAvailability(any()) } coAnswers {
            firstArg<List<AddonStreams>>()
        }

        val context = mockk<Context>(relaxed = true)
        every {
            context.getString(eq(R.string.error_stream_tried_none), *anyVararg())
        } returns MISSING_STREAMS_MESSAGE
        every {
            context.getString(eq(R.string.error_stream_tried_issues), *anyVararg())
        } returns ADDON_ISSUES_MESSAGE
        every {
            context.getString(eq(R.string.error_stream_tried_generic), *anyVararg())
        } returns ADDON_GENERIC_MESSAGE

        return StreamRepositoryImpl(
            context = context,
            api = mockk(relaxed = true),
            addonRepository = addonRepository,
            pluginManager = pluginManager,
            profileManager = profileManager,
            debridSettingsDataStore = debridSettingsDataStore,
            tmdbService = mockk<TmdbService>(relaxed = true),
            debridStreamPresentation = presentation,
            localDebridAvailabilityService = availability,
            ndjsonStreamFetcher = fetcher,
            moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        )
    }

    private companion object {
        const val MISSING_STREAMS_MESSAGE = "no-streams-for-id"
        const val ADDON_ISSUES_MESSAGE = "addon-issues"
        const val ADDON_GENERIC_MESSAGE = "addon-generic"
    }
}
