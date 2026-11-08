package com.nuvio.tv.core.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StaleRevalidationTest {

    @Test
    fun `poster without validators is not revalidated`() {
        assertFalse(cachedResponseCanRevalidate(etag = null, lastModified = null))
        assertFalse(cachedResponseCanRevalidate(etag = "  ", lastModified = ""))
    }

    @Test
    fun `etag or last-modified allows a conditional revalidation`() {
        assertTrue(cachedResponseCanRevalidate(etag = "\"abc\"", lastModified = null))
        assertTrue(cachedResponseCanRevalidate(etag = null, lastModified = "Wed, 07 Oct 2026 04:55:13 GMT"))
    }

    @Test
    fun `304 and identical validators do not reload the image`() {
        assertEquals(
            StaleRevalidationResult.Unchanged,
            classifyRevalidationResponse(
                cachedEtag = "\"abc\"",
                cachedLastModified = null,
                responseCode = 304,
                responseEtag = null,
                responseLastModified = null,
            )
        )
        assertEquals(
            StaleRevalidationResult.Unchanged,
            classifyRevalidationResponse(
                cachedEtag = "\"abc\"",
                cachedLastModified = "Wed, 07 Oct 2026 04:55:13 GMT",
                responseCode = 200,
                responseEtag = "\"abc\"",
                responseLastModified = "Thu, 08 Oct 2026 12:00:00 GMT",
            )
        )
        assertEquals(
            StaleRevalidationResult.Unchanged,
            classifyRevalidationResponse(
                cachedEtag = null,
                cachedLastModified = "Wed, 07 Oct 2026 04:55:13 GMT",
                responseCode = 200,
                responseEtag = null,
                responseLastModified = "Wed, 07 Oct 2026 04:55:13 GMT",
            )
        )
    }

    @Test
    fun `200 without a differing validator does not reload the image`() {
        assertEquals(
            StaleRevalidationResult.Unchanged,
            classifyRevalidationResponse(
                cachedEtag = null,
                cachedLastModified = null,
                responseCode = 200,
                responseEtag = null,
                responseLastModified = null,
            )
        )
        assertEquals(
            StaleRevalidationResult.Unchanged,
            classifyRevalidationResponse(
                cachedEtag = "\"abc\"",
                cachedLastModified = null,
                responseCode = 200,
                responseEtag = null,
                responseLastModified = null,
            )
        )
        assertEquals(
            StaleRevalidationResult.Unchanged,
            classifyRevalidationResponse(
                cachedEtag = "\"abc\"",
                cachedLastModified = null,
                responseCode = 404,
                responseEtag = "\"other\"",
                responseLastModified = null,
            )
        )
    }

    @Test
    fun `changed etag or last-modified reloads the image`() {
        assertEquals(
            StaleRevalidationResult.Changed,
            classifyRevalidationResponse(
                cachedEtag = "\"abc\"",
                cachedLastModified = null,
                responseCode = 200,
                responseEtag = "\"def\"",
                responseLastModified = null,
            )
        )
        assertEquals(
            StaleRevalidationResult.Changed,
            classifyRevalidationResponse(
                cachedEtag = null,
                cachedLastModified = "Wed, 07 Oct 2026 04:55:13 GMT",
                responseCode = 200,
                responseEtag = null,
                responseLastModified = "Thu, 08 Oct 2026 12:00:00 GMT",
            )
        )
    }
}
