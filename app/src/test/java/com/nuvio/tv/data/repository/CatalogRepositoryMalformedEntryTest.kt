package com.nuvio.tv.data.repository

import android.content.Context
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.remote.api.AddonApi
import com.nuvio.tv.data.remote.dto.CatalogResponseDto
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.Response

class CatalogRepositoryMalformedEntryTest {
    @Test
    fun `catalog skips entries without id or name and preserves pagination count`() = runTest {
        val payload =
            """
            {
              "metas": [
                { "id": "tt1", "type": "series", "name": "First" },
                { "id": "tt2", "type": "series" },
                null,
                { "id": " ", "type": "series", "name": "Missing ID" },
                { "id": "tt3", "type": "series", "name": "  Third  " }
              ]
            }
            """.trimIndent()
        val moshi = Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()
        val response = moshi.adapter(CatalogResponseDto::class.java).fromJson(payload)!!
        val api = mockk<AddonApi>()
        coEvery { api.getCatalog(any()) } returns Response.success(response)
        val layoutPrefs = mockk<LayoutPreferenceDataStore> {
            every { customPosterUrlPattern } returns flowOf("")
            every { customPosterEnabledScreens } returns flowOf(com.nuvio.tv.core.poster.CustomPosterScreen.ALL)
        }
        val repository = CatalogRepositoryImpl(
            context = mockk<Context>(relaxed = true),
            api = api,
            layoutPreferenceDataStore = layoutPrefs
        )

        val result = repository.getCatalog(
            addonBaseUrl = "https://addon.example",
            addonId = "addon",
            addonName = "Addon",
            catalogId = "catalog",
            catalogName = "Catalog",
            type = "series",
            skip = 10,
            skipStep = 100,
            extraArgs = emptyMap(),
            supportsSkip = true
        ).last() as NetworkResult.Success

        assertEquals(listOf("tt1", "tt3"), result.data.items.map { it.id })
        assertEquals(listOf("First", "  Third  "), result.data.items.map { it.name })
        assertEquals(15, result.data.nextSkip)
    }
}
