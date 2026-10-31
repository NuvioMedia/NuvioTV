package com.nuvio.tv.core.profile

import org.junit.Assert.assertEquals
import org.junit.Test

class LastProfileStartTest {

    @Test
    fun `without Remember Last Profile the picker rules apply`() {
        listOf(null, false, true).forEach { hasPin ->
            listOf(null, false, true).forEach { asks ->
                assertEquals(LastProfileStart.NotRemembered, lastProfileStart(false, true, hasPin, asks))
                assertEquals(LastProfileStart.NotRemembered, lastProfileStart(true, false, hasPin, asks))
            }
        }
    }

    @Test
    fun `a profile without a PIN opens`() {
        listOf(null, false, true).forEach { asks ->
            assertEquals(LastProfileStart.Open, lastProfileStart(true, true, hasPin = false, asksPinOnAppStart = asks))
        }
    }

    @Test
    fun `a profile with a PIN asks for it unless the profile turned it off`() {
        assertEquals(LastProfileStart.AskPin, lastProfileStart(true, true, hasPin = true, asksPinOnAppStart = true))
        assertEquals(LastProfileStart.Open, lastProfileStart(true, true, hasPin = true, asksPinOnAppStart = false))
    }

    @Test
    fun `nothing is decided before the PIN state and the profile's choice are read`() {
        listOf(null, false, true).forEach { asks ->
            assertEquals(LastProfileStart.Undecided, lastProfileStart(true, true, hasPin = null, asksPinOnAppStart = asks))
        }
        assertEquals(LastProfileStart.Undecided, lastProfileStart(true, true, hasPin = true, asksPinOnAppStart = null))
    }
}
