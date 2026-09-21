package com.nuvio.tv.data.mapper

import com.nuvio.tv.data.remote.dto.MetaResponseDto
import com.squareup.moshi.Moshi
import org.junit.Assert.assertEquals
import org.junit.Test

class MetaMapperTest {

    private val adapter = Moshi.Builder().build().adapter(MetaResponseDto::class.java)

    @Test
    fun `localized addon certification takes priority`() {
        val result = parse(
            """
            {
              "meta": {
                "id": "show",
                "type": "series",
                "name": "Show",
                "app_extras": {
                  "certificationLocal": " 16 ",
                  "certification": "TV-MA"
                }
              }
            }
            """.trimIndent()
        )

        assertEquals("16", result.ageRating)
    }

    @Test
    fun `default addon certification is used when localized value is blank`() {
        val result = parse(
            """
            {
              "meta": {
                "id": "movie",
                "type": "movie",
                "name": "Movie",
                "app_extras": {
                  "certificationLocal": " ",
                  "certification": " PG-13 "
                }
              }
            }
            """.trimIndent()
        )

        assertEquals("PG-13", result.ageRating)
    }

    private fun parse(json: String) = requireNotNull(adapter.fromJson(json)?.meta).toDomain()
}
