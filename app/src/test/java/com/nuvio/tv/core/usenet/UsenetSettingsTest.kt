package com.nuvio.tv.core.usenet

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.Job
import org.junit.Assert.assertFalse
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
            assertFalse(UsenetConfiguration().fallbackEnabled)
            assertFalse(settings.settings.value.fallbackEnabled)
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
