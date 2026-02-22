@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.domain.model.AppTheme
import com.nuvio.tv.ui.theme.NuvioColors
import com.nuvio.tv.ui.theme.ThemeColors

@Composable
fun ThemeSettingsScreen(
    viewModel: ThemeSettingsViewModel = hiltViewModel(),
    onBackPress: () -> Unit
) {
    BackHandler { onBackPress() }

    SettingsStandaloneScaffold(
        title = "Appearance",
        subtitle = "Choose your color theme"
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

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SettingsDetailHeader(
            title = "Appearance",
            subtitle = "Choose your color theme"
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
                itemsIndexed(uiState.availableThemes) { index, theme ->
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
    }
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
                .padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(palette.secondary),
                contentAlignment = Alignment.Center
            ) {
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Selected",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = theme.displayName,
                style = MaterialTheme.typography.titleMedium,
                color = if (isFocused || isSelected) NuvioColors.TextPrimary else NuvioColors.TextSecondary
            )

            Spacer(modifier = Modifier.height(8.dp))

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
