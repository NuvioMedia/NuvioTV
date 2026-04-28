package com.nuvio.tv.data.local

import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.AddonCatalogCollectionSource
import com.nuvio.tv.domain.model.TmdbCollectionMediaType
import com.nuvio.tv.domain.model.TmdbCollectionSource
import com.nuvio.tv.domain.model.TmdbCollectionSourceType
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionsDataStoreSourceMigrationTest {
    private val store = CollectionsDataStore(
        factory = mockk<ProfileDataStoreFactory>(relaxed = true),
        profileManager = mockk<ProfileManager>(relaxed = true)
    )

    @Test
    fun `import converts legacy catalogSources to addon sources`() {
        val json = """
            [
              {
                "id": "collection",
                "title": "Legacy",
                "folders": [
                  {
                    "id": "folder",
                    "title": "Movies",
                    "catalogSources": [
                      {
                        "addonId": "addon",
                        "type": "movie",
                        "catalogId": "popular",
                        "genre": "Action"
                      }
                    ]
                  }
                ]
              }
            ]
        """.trimIndent()

        val source = store.importFromJson(json).single().folders.single().sources.single()

        assertTrue(source is AddonCatalogCollectionSource)
        source as AddonCatalogCollectionSource
        assertEquals("addon", source.addonId)
        assertEquals("movie", source.type)
        assertEquals("popular", source.catalogId)
        assertEquals("Action", source.genre)
    }

    @Test
    fun `export includes provider aware tmdb sources`() {
        val collection = com.nuvio.tv.domain.model.Collection(
            id = "collection",
            title = "TMDB",
            folders = listOf(
                com.nuvio.tv.domain.model.CollectionFolder(
                    id = "folder",
                    title = "Marvel",
                    sources = listOf(
                        TmdbCollectionSource(
                            sourceType = TmdbCollectionSourceType.COMPANY,
                            title = "Marvel Studios",
                            tmdbId = 420,
                            mediaType = TmdbCollectionMediaType.MOVIE
                        )
                    )
                )
            )
        )

        val json = store.exportToJson(listOf(collection))

        assertTrue(json.contains("\"sources\""))
        assertTrue(json.contains("\"provider\":\"tmdb\""))
        assertTrue(json.contains("\"tmdbSourceType\":\"COMPANY\""))
        assertTrue(json.contains("\"tmdbId\":420"))
    }
}
