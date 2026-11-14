package com.nuvio.tv.core.usenet

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.Lazy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UsenetSourceSettingsTest {
    @Test fun newznabXmlParsesOnAndroid() {
        val indexer = UsenetIndexer(name = "Test", apiUrl = "https://index.test/api")
        val caps = NewznabProtocol.capabilities("""<caps><searching><movie-search available="yes" supportedParams="imdbid"/></searching></caps>""")
        assertTrue("imdbid" in caps.movieParams)
        val page = NewznabProtocol.releases("""<rss xmlns:n="urn:newznab"><channel><item><title>Movie.1080p</title><enclosure type="application/x-nzb" url="/get?id=1" length="1000"/><n:attr name="size" value="2000"/></item></channel></rss>""", indexer)
        assertEquals(2000L, page.releases.single().size)
    }

    @Test fun encryptedCredentialsRoundTripAndDeletedProfilesDoNotInheritThem() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val context = object : ContextWrapper(target) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                target.getSharedPreferences("${name}_instrumentation", mode)
        }
        val settings = UsenetSourceSettings(context, Lazy { error("Explicit profile IDs only") })
        settings.clearAllProfiles()
        try {
            val config = UsenetSourceConfiguration(enabled = true,
                providers = listOf(UsenetProvider(name = "Test", host = "news.test", password = "test-secret-password")),
                indexers = listOf(UsenetIndexer(name = "Index", apiUrl = "https://index.test/api", apiKey = "test-secret-key")))
            settings.update(config, 2)
            assertEquals(config, settings.read(2))
            assertEquals(UsenetSourceConfiguration(), settings.read(3))
            val raw = context.getSharedPreferences("usenet_sources", Context.MODE_PRIVATE).getString("profile_2", "")!!
            assertFalse(raw.contains("test-secret"))
            val bytes = android.util.Base64.decode(raw, android.util.Base64.NO_WRAP).toString(Charsets.ISO_8859_1)
            assertFalse(bytes.contains("test-secret"))
            settings.removeProfile(2)
            assertEquals(UsenetSourceConfiguration(), settings.read(2))
        } finally { settings.clearAllProfiles() }
    }
}
