@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.domain.model.HomeLayout
import com.nuvio.tv.ui.components.ClassicLayoutPreview
import com.nuvio.tv.ui.components.GridLayoutPreview
import com.nuvio.tv.ui.components.ModernLayoutPreview
import com.nuvio.tv.ui.screens.settings.LayoutSettingsEvent
import com.nuvio.tv.ui.screens.settings.LayoutSettingsViewModel
import com.nuvio.tv.ui.theme.NuvioColors
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R

@Composable
fun LayoutSelectionScreen(
    viewModel: LayoutSettingsViewModel = hiltViewModel(),
    onContinue: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    var selectedLayout by remember { mutableStateOf(HomeLayout.MODERN) }
    LaunchedEffect(uiState.selectedLayout) {
        selectedLayout = uiState.selectedLayout
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioColors.Background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 80.dp, vertical = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header
            Text(
                text = stringResource(R.string.layout_selection_welcome),
                style = MaterialTheme.typography.headlineLarge,
                color = NuvioColors.TextPrimary
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.layout_selection_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = NuvioColors.TextSecondary
            )

            Spacer(modifier = Modifier.height(40.dp))

            // Layout cards
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(32.dp, Alignment.CenterHorizontally)
            ) {
                LayoutOptionCard(
                    layout = HomeLayout.MODERN,
                    isSelected = selectedLayout == HomeLayout.MODERN,
                    onSelect = { selectedLayout = HomeLayout.MODERN },
                    modifier = Modifier.weight(1f)
                )

                LayoutOptionCard(
                    layout = HomeLayout.GRID,
                    isSelected = selectedLayout == HomeLayout.GRID,
                    onSelect = { selectedLayout = HomeLayout.GRID },
                    modifier = Modifier.weight(1f)
                )

                LayoutOptionCard(
                    layout = HomeLayout.CLASSIC,
                    isSelected = selectedLayout == HomeLayout.CLASSIC,
                    onSelect = { selectedLayout = HomeLayout.CLASSIC },
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Continue button
            Button(
                onClick = {
                    viewModel.onEvent(LayoutSettingsEvent.SelectLayout(selectedLayout))
                    onContinue()
                },
                modifier = Modifier
                    .width(200.dp)
                    .height(48.dp),
                shape = ButtonDefaults.shape(
                    shape = RoundedCornerShape(24.dp)
                ),
                colors = ButtonDefaults.colors(
                    containerColor = NuvioColors.Primary,
                    focusedContainerColor = NuvioColors.FocusBackground
                ),
                border = ButtonDefaults.border(
                    focusedBorder = Border(
                        border = BorderStroke(2.dp, NuvioColors.FocusRing),
                        shape = RoundedCornerShape(24.dp)
                    )
                )
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.layout_selection_continue),
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White
                    )
                }
            }
        }
    }
}

@Composable
private fun LayoutOptionCard(
    layout: HomeLayout,
    isSelected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }

    Card(
        onClick = onSelect,
        modifier = modifier
            .onFocusChanged { isFocused = it.isFocused },
        colors = CardDefaults.colors(
            containerColor = NuvioColors.BackgroundCard,
            focusedContainerColor = NuvioColors.BackgroundCard
        ),
        border = CardDefaults.border(
            border = Border.None,
            focusedBorder = Border(
                border = BorderStroke(2.dp, NuvioColors.FocusRing),
                shape = RoundedCornerShape(16.dp)
            )
        ),
        shape = CardDefaults.shape(RoundedCornerShape(16.dp)),
        scale = CardDefaults.scale(focusedScale = 1.03f)
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Animated preview
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                ) {
                    when (layout) {
                        HomeLayout.CLASSIC -> ClassicLayoutPreview(
                            modifier = Modifier.fillMaxSize()
                        )
                        HomeLayout.GRID -> GridLayoutPreview(
                            modifier = Modifier.fillMaxSize()
                        )
                        HomeLayout.MODERN -> ModernLayoutPreview(
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = when (layout) {
                        HomeLayout.CLASSIC -> stringResource(R.string.layout_classic)
                        HomeLayout.GRID -> stringResource(R.string.layout_grid)
                        HomeLayout.MODERN -> stringResource(R.string.layout_modern)
                    },
                    style = MaterialTheme.typography.titleLarge,
                    color = if (isSelected || isFocused) NuvioColors.TextPrimary else NuvioColors.TextSecondary
                )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = when (layout) {
                    HomeLayout.CLASSIC -> stringResource(R.string.layout_classic_desc)
                    HomeLayout.GRID -> stringResource(R.string.layout_grid_desc)
                    HomeLayout.MODERN -> stringResource(R.string.layout_modern_desc)
                },
                style = MaterialTheme.typography.bodySmall,
                color = NuvioColors.TextTertiary
            )
            }

            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Selected",
                    tint = NuvioColors.FocusRing,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .size(24.dp)
                )
            }
        }
    }
}
