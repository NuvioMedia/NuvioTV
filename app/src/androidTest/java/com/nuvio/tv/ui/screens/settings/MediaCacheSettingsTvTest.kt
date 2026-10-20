package com.nuvio.tv.ui.screens.settings

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.system.Os
import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.core.storage.*
import com.nuvio.tv.domain.model.NavigationMotion
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.ui.theme.NuvioTheme
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaCacheSettingsTvTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val fixtureId = "telumia_cache_test_" + UUID.randomUUID().toString()
    private val fixtureDirectory by lazy { File(instrumentation.targetContext.cacheDir, fixtureId).apply { mkdirs() } }
    private val context by lazy { object : ContextWrapper(instrumentation.targetContext) {
        override fun getApplicationContext(): Context = this
        override fun getCacheDir(): File = fixtureDirectory
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            baseContext.getSharedPreferences(fixtureId + "_" + name, mode)
    } }
    @After fun removeOnlyOwnedFixtures() {
        instrumentation.targetContext.deleteSharedPreferences(fixtureId + "_telumia_media_cache_v1")
        require(fixtureDirectory.canonicalFile.parentFile == instrumentation.targetContext.cacheDir.canonicalFile)
        require(fixtureDirectory.name == fixtureId)
        fixtureDirectory.deleteRecursively()
    }

    @Test fun remoteSelectsManualBudgetAndAutoWithoutChangingTheActiveBudget() {
        val focus = FocusRequester()
        val settings = mutableStateOf(MediaCacheSettings(MediaCacheMode.AUTO, 256L * MIB))
        compose.setContent { NuvioTheme(navigationMotion = NavigationMotion.OFF) {
            Column(Modifier.fillMaxSize().background(NuvioTheme.colors.Background).padding(24.dp)) {
                SettingsGroupCard { MediaCacheSettingsRows(settings.value,
                    MediaCacheBudget(256L * MIB, 256L * MIB, emptySet()), initialFocusRequester = focus) { settings.value = it } }
                LaunchedEffect(Unit) { focus.requestFocusAfterFrames() }
            }
        } }
        compose.waitForIdle()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.runOnIdle { assertEquals(MediaCacheMode.MANUAL, settings.value.mode) }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.runOnIdle { assertEquals(512L * MIB, settings.value.manualBytes) }
        compose.onNodeWithText("512 MiB").assertIsDisplayed()
        File(context.getExternalFilesDir(null), "telumia-cache-settings-tv.png").outputStream().use {
            instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.runOnIdle { assertEquals(MediaCacheMode.AUTO, settings.value.mode); assertEquals(512L * MIB, settings.value.manualBytes) }
        compose.onNodeWithText("512 MiB").assertDoesNotExist()
    }

    @Test fun manualPreferenceSurvivesAStoreRecreationAndAutoKeepsTheChosenValue() {
        val cache = TvMediaCache(context)
        val previous = cache.loadSettings()
        try {
            assertTrue(cache.saveSettings(MediaCacheSettings(MediaCacheMode.MANUAL, 2048L * MIB)))
            val reopened = TvMediaCache(context).loadSettings()
            assertEquals(MediaCacheMode.MANUAL, reopened.mode)
            assertEquals(2048L * MIB, reopened.manualBytes)
            assertTrue(cache.saveSettings(reopened.copy(mode = MediaCacheMode.AUTO)))
            assertEquals(2048L * MIB, TvMediaCache(context).loadSettings().manualBytes)
        } finally { assertTrue(cache.saveSettings(previous)) }
    }

    @Test fun futureSchemaFallsBackSafelyWithoutErasingThePreferenceFile() {
        val preferences = context.getSharedPreferences("telumia_media_cache_v1", Context.MODE_PRIVATE)
        assertTrue(preferences.edit().putInt("schema", 999).putString("mode", "FUTURE")
            .putString("unknown_future_field", "preserve-me").commit())
        val original = preferences.all.toMap()
        val cache = TvMediaCache(context)
        assertEquals(MediaCacheSettings(), cache.loadSettings())
        assertFalse(cache.saveSettings(MediaCacheSettings(MediaCacheMode.MANUAL, 2048L * MIB)))
        assertEquals(original, preferences.all)
        assertEquals(999, preferences.getInt("schema", 0))
        assertEquals(MediaCacheSettings(), TvMediaCache(context).loadSettings())
    }

    @Test fun wrongTypedSchemaCannotBeDowngradedBySaving() {
        val preferences = context.getSharedPreferences("telumia_media_cache_v1", Context.MODE_PRIVATE)
        assertTrue(preferences.edit().putString("schema", "next").putLong("future_limit", 42).commit())
        val original = preferences.all.toMap()
        val cache = TvMediaCache(context)
        assertEquals(MediaCacheSettings(), cache.loadSettings())
        assertFalse(cache.saveSettings(MediaCacheSettings()))
        assertEquals(original, preferences.all)
    }

    @Test fun runtimeMeasurementCountsImageBadgeAndGuideCachesAndExcludesDurableAndLinkedFiles() {
        fun write(name: String, size: Int): File = File(context.cacheDir, name).apply {
            parentFile!!.mkdirs(); writeBytes(ByteArray(size))
        }
        write("image_cache/image", 101)
        write("badge_cache/badge", 23)
        write("live_tv_guide/guide.json.gz", 17)
        val avatar = write("profile-studio/avatar", 211)
        val download = write("downloads/movie", 307)
        val link = File(context.cacheDir, "image_cache/linked-profile")
        Os.symlink(avatar.parentFile!!.absolutePath, link.absolutePath)
        try {
            val cache = TvMediaCache(context)
            assertTrue(cache.occupiedCache.complete)
            assertEquals(141L, cache.occupiedCache.bytes)
            assertEquals(211L, avatar.length())
            assertEquals(307L, download.length())
            assertEquals(141L, cache.deviceSnapshot.occupiedCacheBytes)
            assertEquals(MediaCachePolicy.resolve(MediaCachePlatform.TV, cache.activeSettings, cache.deviceSnapshot), cache.activeBudget)
        } finally { java.nio.file.Files.delete(link.toPath()) }
    }

    @Test fun nativeDirectoryIterationStopsAtItsEntryBudget() {
        val images = File(context.cacheDir, "image_cache").apply { mkdirs() }
        repeat(20) { File(images, "$it").writeBytes(ByteArray(4)) }
        val result = OwnedMediaCacheOccupancy.measure(context.cacheDir.toPath(), listOf(listOf("image_cache")),
            maxEntries = 3, maxMillis = 10_000)
        assertFalse(result.complete)
        assertEquals(3, result.visitedEntries)
        assertEquals(8L, result.bytes)
    }
}
