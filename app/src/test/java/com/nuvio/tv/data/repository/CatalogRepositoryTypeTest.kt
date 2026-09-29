package com.nuvio.tv.data.repository

import android.content.Context
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.remote.api.AddonApi
import com.nuvio.tv.data.remote.dto.CatalogResponseDto
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.ContentType
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class CatalogRepositoryTypeTest {
    @Test
    fun `initial and paginated requests retain advertised Series type`() = runTest {
        val response = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
            .adapter(CatalogResponseDto::class.java)
            .fromJson("""{"metas":[{"id":"tt1","type":"series","name":"A show"}]}""")!!
        val requestedUrls = mutableListOf<String>()
        val api = mockk<AddonApi>()
        coEvery { api.getCatalog(capture(requestedUrls)) } returns Response.success(response)
        val preferences = mockk<LayoutPreferenceDataStore> {
            every { customPosterUrlPattern } returns flowOf("")
        }
        val repository = CatalogRepositoryImpl(mockk<Context>(relaxed = true), api, preferences)
        val descriptor = CatalogDescriptor(ContentType.SERIES, "Series", "mdblist.123", "My shows")
        val first = repository.getCatalog(
            addonBaseUrl = "https://example.com",
            addonId = "aio-metadata",
            addonName = "AIOMetadata",
            catalogId = descriptor.id,
            catalogName = descriptor.name,
            type = descriptor.apiType,
            skip = 0,
            skipStep = 100,
            extraArgs = emptyMap(),
            supportsSkip = true
        ).last() as NetworkResult.Success
        val row = first.data
        repository.getCatalog(
            addonBaseUrl = row.addonBaseUrl,
            addonId = row.addonId,
            addonName = row.addonName,
            catalogId = row.catalogId,
            catalogName = row.catalogName,
            type = row.apiType,
            skip = row.nextSkip,
            skipStep = row.skipStep,
            extraArgs = row.extraArgs,
            supportsSkip = row.supportsSkip
        ).last()

        assertEquals(2, requestedUrls.size)
        assertTrue(requestedUrls.all { it.contains("/catalog/Series/mdblist.123") })
        assertEquals(ContentType.SERIES, row.type)
        assertEquals("series", row.items.single().apiType)
    }
}
