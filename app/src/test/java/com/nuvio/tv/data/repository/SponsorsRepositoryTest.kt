package com.nuvio.tv.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class SponsorsRepositoryTest {

    private val repository = SponsorsRepository()

    @Test
    fun `parses comma separated sponsor names`() {
        val sponsors = repository.parseSponsorNames("ragmehos., Alice, Bob")

        assertEquals(listOf("ragmehos.", "Alice", "Bob"), sponsors.map { it.name })
        assertEquals(listOf(null, null, null), sponsors.map { it.channelUrl })
    }

    @Test
    fun `drops blank sponsor names`() {
        val sponsors = repository.parseSponsorNames("ragmehos., , Alice,,")

        assertEquals(listOf("ragmehos.", "Alice"), sponsors.map { it.name })
    }
}
