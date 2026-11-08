package com.nuvio.tv.core.usenet

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.Job
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class UsenetUserAgentMigrationTest {
    private val values = mutableMapOf<String, Any>()
    private val context = mockk<Context>()

    @Before fun setUp() {
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
        every { editor.remove(any()) } answers { values.remove(firstArg<String>()); editor }
        every { editor.apply() } returns Unit
        mockkObject(UsenetSidecar.Companion)
        val sidecar = mockk<UsenetSidecar>()
        every { UsenetSidecar.get(context) } returns sidecar
        every { sidecar.settingsChanged() } returns Job()
    }

    @After fun tearDown() { unmockkObject(UsenetSidecar.Companion) }

    @Test fun `legacy override survives upgrade and saving an unrelated setting`() {
        values["userAgent"] = "ExistingClient/1.0"
        val settings = UsenetSettings(context)
        assertEquals("ExistingClient/1.0", settings.settings.value.searchAgent)
        assertEquals("ExistingClient/1.0", settings.settings.value.downloadAgent)
        settings.update(settings.settings.value.copy(cacheNzb = false))
        assertFalse(values.containsKey("userAgent"))
        val restored = UsenetSettings(context).settings.value
        assertEquals("ExistingClient/1.0", restored.searchAgent)
        assertEquals("ExistingClient/1.0", restored.downloadAgent)
        assertFalse(restored.cacheNzb)
    }

    @Test fun `resetting search after upgrade preserves the download override`() {
        values["userAgent"] = "ExistingClient/1.0"
        val settings = UsenetSettings(context)
        settings.update(settings.settings.value.copy(searchUserAgent = ""))
        val restored = UsenetSettings(context).settings.value
        assertEquals(NUVIO_USER_AGENT, restored.searchAgent)
        assertEquals("", restored.searchUserAgent)
        assertEquals("ExistingClient/1.0", restored.downloadAgent)
    }

    @Test fun `resetting download after upgrade preserves the search override`() {
        values["userAgent"] = "ExistingClient/1.0"
        val settings = UsenetSettings(context)
        settings.update(settings.settings.value.copy(downloadUserAgent = " $NUVIO_USER_AGENT "))
        val restored = UsenetSettings(context).settings.value
        assertEquals("ExistingClient/1.0", restored.searchAgent)
        assertEquals(NUVIO_USER_AGENT, restored.downloadAgent)
        assertEquals("", restored.downloadUserAgent)
    }

    @Test fun `saved blanks take precedence over a legacy override`() {
        values["userAgent"] = "ExistingClient/1.0"
        values["searchUserAgent"] = ""
        values["downloadUserAgent"] = ""
        val restored = UsenetSettings.read(context)
        assertEquals(NUVIO_USER_AGENT, restored.searchAgent)
        assertEquals(NUVIO_USER_AGENT, restored.downloadAgent)
    }

    @Test fun `invalid legacy override falls back to Nuvio for both fields`() {
        values["userAgent"] = "a\r\nb: c"
        val restored = UsenetSettings.read(context)
        assertEquals(NUVIO_USER_AGENT, restored.searchAgent)
        assertEquals(NUVIO_USER_AGENT, restored.downloadAgent)
    }
}
