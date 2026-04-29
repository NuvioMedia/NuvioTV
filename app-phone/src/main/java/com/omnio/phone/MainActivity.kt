package com.omnio.phone

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.os.ConfigurationCompat
import com.omnio.phone.ui.PhoneApp
import com.omnio.phone.ui.screens.player.PhonePlayerPipController
import com.omnio.phone.ui.theme.OmnioTheme
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        val tag = newBase.getSharedPreferences("app_locale", Context.MODE_PRIVATE)
            .getString("locale_tag", null)
        if (!tag.isNullOrEmpty()) {
            val locale = Locale.forLanguageTag(tag)
            Locale.setDefault(locale)
            val config = Configuration(newBase.resources.configuration)
            config.setLocale(locale)
            super.attachBaseContext(newBase.createConfigurationContext(config))
        } else {
            val systemLocale = ConfigurationCompat.getLocales(newBase.resources.configuration)[0]
                ?: Locale.getDefault(Locale.Category.DISPLAY)
            Locale.setDefault(systemLocale)
            super.attachBaseContext(newBase)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            OmnioTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    PhoneApp()
                }
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        PhonePlayerPipController.onUserLeaveHint(this)
    }
}
