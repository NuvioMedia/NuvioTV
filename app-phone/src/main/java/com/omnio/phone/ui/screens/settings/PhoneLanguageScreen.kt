package com.omnio.phone.ui.screens.settings

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.omnio.phone.R
import kotlinx.coroutines.delay
import java.util.Locale

private const val LOCALE_PREFS = "app_locale"
private const val LOCALE_KEY = "locale_tag"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneLanguageScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val systemLabel = stringResource(R.string.language_system_default)
    val supportedLocales = remember(systemLabel) {
        val tags = listOf(
            "en", "ar", "de", "el", "es", "es-419", "hu", "fr", "it", "no", "pl",
            "pt-PT", "pt-BR", "tr", "cs", "sk", "sl", "sv", "ro", "ja",
            "nl", "vi", "hi", "lt", "he"
        )
        listOf(null to systemLabel) + tags.map { tag ->
            val locale = Locale.forLanguageTag(tag)
            tag to locale.getDisplayName(locale).replaceFirstChar { it.uppercase() }
        }.sortedBy { it.second }
    }
    var selectedTag by remember {
        mutableStateOf(
            context.getSharedPreferences(LOCALE_PREFS, Context.MODE_PRIVATE)
                .getString(LOCALE_KEY, null)?.takeIf { it.isNotEmpty() }
        )
    }
    var pendingRestart by remember { mutableStateOf(false) }
    val restartHint = stringResource(R.string.language_restart_hint)

    LaunchedEffect(pendingRestart) {
        if (pendingRestart) {
            delay(150)
            (context as? Activity ?: context.findActivity())?.recreate()
            pendingRestart = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.language_screen_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            supportedLocales.forEach { (tag, label) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !pendingRestart) {
                            if (tag == selectedTag) return@clickable
                            selectedTag = tag
                            val prefs = context.getSharedPreferences(
                                LOCALE_PREFS, Context.MODE_PRIVATE
                            )
                            prefs.edit().apply {
                                if (tag == null) remove(LOCALE_KEY) else putString(LOCALE_KEY, tag)
                            }.apply()
                            pendingRestart = true
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = tag == selectedTag,
                        onClick = null
                    )
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
            if (pendingRestart) {
                Text(
                    text = restartHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(20.dp)
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}
