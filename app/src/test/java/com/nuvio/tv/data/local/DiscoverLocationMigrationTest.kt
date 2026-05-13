package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.emptyPreferences
import com.nuvio.tv.domain.model.DiscoverLocation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoverLocationMigrationTest {

    private val legacyKey = legacySearchDiscoverEnabledKey
    private val newKey = discoverLocationKey
    private val lastNonOffKey = lastNonOffDiscoverLocationKey

    @Test
    fun `shouldMigrate is true when legacy key present`() = runBlocking {
        val prefs = emptyPreferences().toMutablePreferences().apply {
            this[legacyKey] = true
        }.toPreferences()
        assertTrue(discoverLocationMigration.shouldMigrate(prefs))
    }

    @Test
    fun `shouldMigrate is false when legacy key absent`() = runBlocking {
        assertFalse(discoverLocationMigration.shouldMigrate(emptyPreferences()))
    }

    @Test
    fun `migrate with legacy true and no remembered yields IN_SEARCH`() = runBlocking {
        val prefs = emptyPreferences().toMutablePreferences().apply {
            this[legacyKey] = true
        }.toPreferences()
        val migrated = discoverLocationMigration.migrate(prefs)
        assertEquals(DiscoverLocation.IN_SEARCH.name, migrated[newKey])
        assertNull(migrated[legacyKey])
    }

    @Test
    fun `migrate with legacy false yields OFF`() = runBlocking {
        val prefs = emptyPreferences().toMutablePreferences().apply {
            this[legacyKey] = false
        }.toPreferences()
        val migrated = discoverLocationMigration.migrate(prefs)
        assertEquals(DiscoverLocation.OFF.name, migrated[newKey])
        assertNull(migrated[legacyKey])
    }

    @Test
    fun `migrate with legacy true and remembered IN_SIDEBAR restores IN_SIDEBAR`() = runBlocking {
        val prefs = emptyPreferences().toMutablePreferences().apply {
            this[legacyKey] = true
            this[lastNonOffKey] = DiscoverLocation.IN_SIDEBAR.name
        }.toPreferences()
        val migrated = discoverLocationMigration.migrate(prefs)
        assertEquals(DiscoverLocation.IN_SIDEBAR.name, migrated[newKey])
    }

    @Test
    fun `migrate ignores OFF as remembered fallback`() = runBlocking {
        val prefs = emptyPreferences().toMutablePreferences().apply {
            this[legacyKey] = true
            this[lastNonOffKey] = DiscoverLocation.OFF.name
        }.toPreferences()
        val migrated = discoverLocationMigration.migrate(prefs)
        assertEquals(DiscoverLocation.IN_SEARCH.name, migrated[newKey])
    }

    @Test
    fun `migrate does not overwrite existing new key`() = runBlocking {
        val prefs = emptyPreferences().toMutablePreferences().apply {
            this[legacyKey] = false
            this[newKey] = DiscoverLocation.IN_SIDEBAR.name
        }.toPreferences()
        val migrated = discoverLocationMigration.migrate(prefs)
        assertEquals(DiscoverLocation.IN_SIDEBAR.name, migrated[newKey])
        assertNull(migrated[legacyKey])
    }

    @Test
    fun `migrate is idempotent across runs`() = runBlocking {
        val prefs = emptyPreferences().toMutablePreferences().apply {
            this[legacyKey] = true
        }.toPreferences()
        val first = discoverLocationMigration.migrate(prefs)
        assertFalse(discoverLocationMigration.shouldMigrate(first))
        val second = discoverLocationMigration.migrate(first)
        assertEquals(first[newKey], second[newKey])
    }
}
