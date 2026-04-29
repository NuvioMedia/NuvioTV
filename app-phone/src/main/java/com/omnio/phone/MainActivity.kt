package com.omnio.phone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.omnio.phone.ui.PhoneApp
import com.omnio.phone.ui.theme.OmnioTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
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
}
