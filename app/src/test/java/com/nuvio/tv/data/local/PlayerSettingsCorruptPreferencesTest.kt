package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerSettingsCorruptPreferencesTest {

    @Test
    fun `safeGetInt returns int when type matches`() {
        val key = intPreferencesKey("test_int")
        val prefs = mutablePreferencesOf(key to 42)

        assertEquals(42, prefs.safeGetInt(key))
        assertEquals(42, prefs.safeGetInt(key, 0))
    }

    @Test
    fun `safeGetInt coerces String to Int when key stored as String`() {
        val intKey = intPreferencesKey("test_int")
        val strKey = stringPreferencesKey("test_int")
        val prefs = mutablePreferencesOf(strKey to "100")

        // Direct read prefs[intKey] throws ClassCastException: java.lang.String cannot be cast to java.lang.Integer
        // safeGetInt intercepts and parses it safely
        assertEquals(100, prefs.safeGetInt(intKey))
        assertEquals(100, prefs.safeGetInt(intKey, 0))
    }

    @Test
    fun `safeGetInt returns fallback when String is unparseable`() {
        val intKey = intPreferencesKey("test_int")
        val strKey = stringPreferencesKey("test_int")
        val prefs = mutablePreferencesOf(strKey to "not_a_number")

        assertNull(prefs.safeGetInt(intKey))
        assertEquals(5, prefs.safeGetInt(intKey, 5))
    }

    @Test
    fun `safeGetInt coerces Number to Int`() {
        val intKey = intPreferencesKey("test_int")
        val floatKey = floatPreferencesKey("test_int")
        val prefs = mutablePreferencesOf(floatKey to 25.0f)

        assertEquals(25, prefs.safeGetInt(intKey))
    }

    @Test
    fun `safeGetBoolean returns boolean when type matches`() {
        val key = booleanPreferencesKey("test_bool")
        val prefs = mutablePreferencesOf(key to true)

        assertEquals(true, prefs.safeGetBoolean(key))
        assertTrue(prefs.safeGetBoolean(key, false))
    }

    @Test
    fun `safeGetBoolean coerces String to Boolean`() {
        val boolKey = booleanPreferencesKey("test_bool")
        val strKey = stringPreferencesKey("test_bool")

        val truePrefs = mutablePreferencesOf(strKey to "true")
        assertTrue(truePrefs.safeGetBoolean(boolKey, false))

        val onePrefs = mutablePreferencesOf(strKey to "1")
        assertTrue(onePrefs.safeGetBoolean(boolKey, false))

        val falsePrefs = mutablePreferencesOf(strKey to "false")
        assertFalse(falsePrefs.safeGetBoolean(boolKey, true))

        val zeroPrefs = mutablePreferencesOf(strKey to "0")
        assertFalse(zeroPrefs.safeGetBoolean(boolKey, true))
    }

    @Test
    fun `safeGetBoolean returns fallback when String is invalid`() {
        val boolKey = booleanPreferencesKey("test_bool")
        val strKey = stringPreferencesKey("test_bool")
        val prefs = mutablePreferencesOf(strKey to "random_text")

        assertNull(prefs.safeGetBoolean(boolKey))
        assertTrue(prefs.safeGetBoolean(boolKey, true))
    }

    @Test
    fun `safeGetFloat coerces String to Float`() {
        val floatKey = floatPreferencesKey("test_float")
        val strKey = stringPreferencesKey("test_float")
        val prefs = mutablePreferencesOf(strKey to "98.5")

        assertEquals(98.5f, prefs.safeGetFloat(floatKey) ?: 0f, 0.001f)
        assertEquals(98.5f, prefs.safeGetFloat(floatKey, 0f), 0.001f)
    }

    @Test
    fun `safeGetString returns string or stringified value`() {
        val strKey = stringPreferencesKey("test_str")
        val intKey = intPreferencesKey("test_str")
        val prefs = mutablePreferencesOf(intKey to 123)

        assertEquals("123", prefs.safeGetString(strKey))
    }

    @Test
    fun `safeGetStringSet handles Sets and JSON strings`() {
        val setKey = stringSetPreferencesKey("test_set")
        val normalPrefs = mutablePreferencesOf(setKey to setOf("a", "b"))
        assertEquals(setOf("a", "b"), normalPrefs.safeGetStringSet(setKey))

        val strKey = stringPreferencesKey("test_set")
        val jsonPrefs = mutablePreferencesOf(strKey to "[\"x\",\"y\"]")
        assertEquals(setOf("x", "y"), jsonPrefs.safeGetStringSet(setKey))
    }

    @Test
    fun `healCorruptedPreferences converts string-stored keys into typed keys`() {
        val prefs = mutablePreferencesOf(
            stringPreferencesKey("center_mix_level_db") to "15",
            stringPreferencesKey("subtitle_size") to "120",
            stringPreferencesKey("use_libass") to "true",
            stringPreferencesKey("next_episode_threshold_percent_v2") to "98.5",
            stringPreferencesKey("post_play_movie_threshold_percent") to "invalid_int"
        )

        PlayerSettingsDataStore.healCorruptedPreferences(prefs)

        val centerMixKey = intPreferencesKey("center_mix_level_db")
        val subtitleSizeKey = intPreferencesKey("subtitle_size")
        val useLibassKey = booleanPreferencesKey("use_libass")
        val nextThresholdKey = floatPreferencesKey("next_episode_threshold_percent_v2")
        val postPlayKey = intPreferencesKey("post_play_movie_threshold_percent")

        // Typed getters work correctly
        assertEquals(15, prefs[centerMixKey])
        assertEquals(120, prefs[subtitleSizeKey])
        assertEquals(true, prefs[useLibassKey])
        assertEquals(98.5f, prefs[nextThresholdKey] ?: 0f, 0.001f)

        // Invalid int string removed without crash
        assertNull(prefs[postPlayKey])

        // Underlying stored types are healed to correct types
        val centerMixEntry = prefs.asMap().entries.first { it.key.name == "center_mix_level_db" }
        assertTrue(centerMixEntry.value is Int)
        assertEquals(15, centerMixEntry.value)

        val subtitleSizeEntry = prefs.asMap().entries.first { it.key.name == "subtitle_size" }
        assertTrue(subtitleSizeEntry.value is Int)
        assertEquals(120, subtitleSizeEntry.value)

        val useLibassEntry = prefs.asMap().entries.first { it.key.name == "use_libass" }
        assertTrue(useLibassEntry.value is Boolean)
        assertEquals(true, useLibassEntry.value)

        val nextThresholdEntry = prefs.asMap().entries.first { it.key.name == "next_episode_threshold_percent_v2" }
        assertTrue(nextThresholdEntry.value is Float)
        assertEquals(98.5f, nextThresholdEntry.value as Float, 0.001f)

        // Invalid string entry completely removed from DataStore map
        assertNull(prefs.asMap().entries.firstOrNull { it.key.name == "post_play_movie_threshold_percent" })
    }
}