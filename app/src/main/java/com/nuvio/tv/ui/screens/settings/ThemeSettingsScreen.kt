@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import android.widget.Toast
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.AppTheme
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioColors
import com.nuvio.tv.ui.theme.ThemeColors
import kotlinx.coroutines.delay

@Composable
fun ThemeSettingsScreen(
    viewModel: ThemeSettingsViewModel = hiltViewModel(),
    onBackPress: () -> Unit
) {
    BackHandler { onBackPress() }

    SettingsStandaloneScaffold(
        title = stringResource(R.string.appearance_title),
        subtitle = stringResource(R.string.appearance_subtitle)
    ) {
        ThemeSettingsContent(viewModel = viewModel)
    }
}

@Composable
fun ThemeSettingsContent(
    viewModel: ThemeSettingsViewModel = hiltViewModel(),
    initialFocusRequester: FocusRequester? = null
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showLanguageDialog by remember { mutableStateOf(false) }
    var pendingLanguageRestart by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val strLanguageSystem = stringResource(R.string.appearance_language_system)
    val supportedLocales = remember(strLanguageSystem) {
        listOf(
            null to strLanguageSystem,
            "en" to "English",
            "pl" to "Polski"
        )
    }
    var selectedTag by remember {
        mutableStateOf(
            context.getSharedPreferences("app_locale", android.content.Context.MODE_PRIVATE)
                .getString("locale_tag", null)?.takeIf { it.isNotEmpty() }
        )
    }
    val currentLocaleName = supportedLocales.firstOrNull { it.first == selectedTag }?.second ?: stringResource(R.string.appearance_language_system)
    val strRestartHint = stringResource(R.string.appearance_language_restart_hint)

    LaunchedEffect(pendingLanguageRestart, showLanguageDialog) {
        if (pendingLanguageRestart && !showLanguageDialog) {
            // Let the dialog window detach before recreating the Activity to avoid focus/window ANRs.
            delay(150)
            context.findActivity()?.recreate()
                ?: Toast.makeText(context, strRestartHint, Toast.LENGTH_LONG).show()
            pendingLanguageRestart = false
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SettingsDetailHeader(
            title = stringResource(R.string.appearance_title),
            subtitle = stringResource(R.string.appearance_subtitle)
        )

        SettingsGroupCard(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                itemsIndexed(
                    items = uiState.availableThemes,
                    key = { _, theme -> theme.name }
                ) { index, theme ->
                    ThemeCard(
                        theme = theme,
                        isSelected = theme == uiState.selectedTheme,
                        onClick = { viewModel.onEvent(ThemeSettingsEvent.SelectTheme(theme)) },
                        modifier = if (index == 0 && initialFocusRequester != null) {
                            Modifier.focusRequester(initialFocusRequester)
                        } else {
                            Modifier
                        }
                    )
                }
            }
        }

        SettingsGroupCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            SettingsActionRow(
                title = stringResource(R.string.appearance_language),
                subtitle = stringResource(R.string.appearance_language_subtitle),
                value = currentLocaleName,
                onClick = { showLanguageDialog = true }
            )
        }
    }

    if (showLanguageDialog) {
        val firstFocusRequester = remember { FocusRequester() }
        LaunchedEffect(Unit) { firstFocusRequester.requestFocus() }
        NuvioDialog(
            onDismiss = { showLanguageDialog = false },
            title = stringResource(R.string.appearance_language_dialog_title),
            width = 400.dp,
            suppressFirstKeyUp = false
        ) {
            supportedLocales.forEachIndexed { index, (tag, name) ->
                val isSelected = tag == selectedTag
                Button(
                    onClick = {
                        val previousTag = selectedTag
                        context.getSharedPreferences("app_locale", android.content.Context.MODE_PRIVATE)
                            .edit().putString("locale_tag", tag ?: "").apply()
                        selectedTag = tag
                        showLanguageDialog = false
                        if (previousTag != tag) {
                            pendingLanguageRestart = true
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (index == 0) Modifier.focusRequester(firstFocusRequester) else Modifier),
                    colors = ButtonDefaults.colors(
                        containerColor = if (isSelected) NuvioColors.FocusBackground else NuvioColors.BackgroundCard,
                        contentColor = NuvioColors.TextPrimary
                    )
                ) {
                    Text(name)
                }
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
private fun ThemeCard(
    theme: AppTheme,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }
    val palette = ThemeColors.getColorPalette(theme)

    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { state ->
                val nowFocused = state.isFocused
                if (isFocused != nowFocused) {
                    isFocused = nowFocused
                }
            },
        colors = CardDefaults.colors(
            containerColor = NuvioColors.Background,
            focusedContainerColor = NuvioColors.Background
        ),
        border = CardDefaults.border(
            border = if (isSelected) Border(
                border = BorderStroke(1.dp, NuvioColors.FocusRing),
                shape = RoundedCornerShape(SettingsSecondaryCardRadius)
            ) else Border.None,
            focusedBorder = Border(
                border = BorderStroke(2.dp, NuvioColors.FocusRing),
                shape = RoundedCornerShape(SettingsSecondaryCardRadius)
            )
        ),
        shape = CardDefaults.shape(RoundedCornerShape(SettingsSecondaryCardRadius)),
        scale = CardDefaults.scale(focusedScale = 1f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(17.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(55.dp)
                    .clip(CircleShape)
                    .background(palette.secondary),
                contentAlignment = Alignment.Center
            ) {
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = stringResource(R.string.cd_selected),
                        tint = palette.onSecondary,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(11.dp))

            Text(
                text = theme.displayName,
                style = MaterialTheme.typography.titleMedium,
                color = if (isFocused || isSelected) NuvioColors.TextPrimary else NuvioColors.TextSecondary
            )

            Spacer(modifier = Modifier.height(7.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(SettingsPillRadius))
                    .background(palette.focusRing)
            )
        }
    }
}
