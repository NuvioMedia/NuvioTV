package com.nuvio.tv.core.usenet

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.Job
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UsenetSettingsTest {
    @Test fun `fallback defaults off and toggle persists across settings instances`() {
        val values = mutableMapOf<String, Any>()
        val context = mockk<Context>()
        val preferences = mockk<SharedPreferences>()
        val editor = mockk<SharedPreferences.Editor>()
        every { context.getSharedPreferences("usenet_performance", Context.MODE_PRIVATE) } returns preferences
        every { preferences.getString(any(), any()) } answers { values[firstArg()] as? String ?: secondArg() }
        every { preferences.getInt(any(), any()) } answers { values[firstArg()] as? Int ?: secondArg() }
        every { preferences.getBoolean(any(), any()) } answers { values[firstArg()] as? Boolean ?: secondArg() }
        every { preferences.edit() } returns editor
        every { editor.putString(any(), any()) } answers { values[firstArg()] = secondArg<String>(); editor }
        every { editor.putInt(any(), any()) } answers { values[firstArg()] = secondArg<Int>(); editor }
        every { editor.putBoolean(any(), any()) } answers { values[firstArg()] = secondArg<Boolean>(); editor }
        every { editor.apply() } returns Unit

        mockkObject(UsenetSidecar.Companion)
        try {
            val sidecar = mockk<UsenetSidecar>()
            every { UsenetSidecar.get(context) } returns sidecar
            every { sidecar.settingsChanged() } returns Job()
            val settings = UsenetSettings(context)
            assertEquals(5, UsenetConfiguration().fallbackMaxAttempts)
            assertEquals(5, settings.settings.value.fallbackMaxAttempts)
            settings.update(settings.settings.value.copy(fallbackMaxAttempts = 50))
            assertEquals(50, settings.settings.value.fallbackMaxAttempts)
            assertEquals(50, UsenetSettings(context).settings.value.fallbackMaxAttempts)
            settings.update(settings.settings.value.copy(fallbackMaxAttempts = 1))
            assertEquals(1, UsenetSettings(context).settings.value.fallbackMaxAttempts)
            for (invalid in listOf(0, 51)) {
                assertThrows(IllegalArgumentException::class.java) {
                    settings.update(settings.settings.value.copy(fallbackMaxAttempts = invalid))
                }
            }
            values["fallbackMaxAttempts"] = 100
            assertEquals(50, UsenetSettings.read(context).fallbackMaxAttempts)
            values["fallbackMaxAttempts"] = -1
            assertEquals(1, UsenetSettings.read(context).fallbackMaxAttempts)
            assertFalse(UsenetConfiguration().fallbackEnabled)
            assertFalse(settings.settings.value.fallbackEnabled)
            assertFalse(UsenetConfiguration().prewarmOnLaunch)
            assertFalse(UsenetConfiguration().prefetchResults)
            assertFalse(UsenetConfiguration().allowPrivateNetwork)
            assertFalse(settings.settings.value.allowPrivateNetwork)
            settings.update(settings.settings.value.copy(allowPrivateNetwork = true))
            assertTrue(UsenetSettings(context).settings.value.allowPrivateNetwork)
            settings.update(settings.settings.value.copy(allowPrivateNetwork = false))
            assertFalse(UsenetSettings(context).settings.value.allowPrivateNetwork)
            assertFalse(settings.settings.value.prewarmOnLaunch)
            assertFalse(settings.settings.value.prefetchResults)
            settings.update(settings.settings.value.copy(prewarmOnLaunch = true, prefetchResults = true))
            assertTrue(UsenetSettings(context).settings.value.prewarmOnLaunch)
            assertTrue(UsenetSettings(context).settings.value.prefetchResults)
            settings.update(settings.settings.value.copy(fallbackEnabled = true))
            assertTrue(settings.settings.value.fallbackEnabled)
            assertTrue(UsenetSettings(context).settings.value.fallbackEnabled)
            settings.update(settings.settings.value.copy(fallbackEnabled = false))
            assertFalse(settings.settings.value.fallbackEnabled)
            assertFalse(UsenetSettings(context).settings.value.fallbackEnabled)
        } finally {
            unmockkObject(UsenetSidecar.Companion)
        }
    }
}
