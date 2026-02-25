@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.account

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.ui.theme.NuvioColors
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R

@Composable
fun SyncCodeGenerateScreen(
    onBackPress: () -> Unit = {},
    viewModel: AccountViewModel = hiltViewModel()
) {
    BackHandler { onBackPress() }

    val uiState by viewModel.uiState.collectAsState()
    var pin by remember { mutableStateOf("") }
    val keyboardController = LocalSoftwareKeyboardController.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioColors.Background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.5f)
                .background(
                    color = NuvioColors.BackgroundElevated,
                    shape = RoundedCornerShape(20.dp)
                )
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.sync_generate_title),
                style = MaterialTheme.typography.headlineSmall,
                color = NuvioColors.TextPrimary,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.sync_generate_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioColors.TextSecondary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(24.dp))

            if (uiState.generatedSyncCode != null) {
                // Show the generated code
                Text(
                    text = stringResource(R.string.sync_generate_code_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = NuvioColors.TextSecondary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            color = NuvioColors.Secondary.copy(alpha = 0.1f),
                            shape = RoundedCornerShape(12.dp)
                        )
                        .padding(20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = uiState.generatedSyncCode ?: "",
                        style = MaterialTheme.typography.titleLarge.copy(
                            letterSpacing = 1.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        color = NuvioColors.Secondary,
                        textAlign = TextAlign.Center,
                        maxLines = 1
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.sync_generate_instruction),
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioColors.TextTertiary,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(24.dp))
                Button(
                    onClick = {
                        viewModel.clearGeneratedSyncCode()
                        onBackPress()
                    },
                    colors = ButtonDefaults.colors(
                        containerColor = NuvioColors.Secondary,
                        focusedContainerColor = NuvioColors.SecondaryVariant,
                        contentColor = NuvioColors.OnSecondary,
                        focusedContentColor = NuvioColors.OnSecondaryVariant
                    ),
                    shape = ButtonDefaults.shape(RoundedCornerShape(50)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.sync_generate_done), modifier = Modifier.padding(vertical = 4.dp))
                }
            } else {
                // PIN input form
                Text(
                    text = stringResource(R.string.sync_generate_pin_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = NuvioColors.TextSecondary,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(4.dp))
                InputField(
                    value = pin,
                    onValueChange = { if (it.length <= 8) pin = it },
                    placeholder = stringResource(R.string.sync_generate_pin_placeholder),
                    keyboardType = KeyboardType.NumberPassword,
                    isPassword = true,
                    imeAction = ImeAction.Done,
                    onImeAction = {
                        keyboardController?.hide()
                        if (pin.length >= 4) viewModel.generateSyncCode(pin)
                    }
                )

                if (uiState.error != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = uiState.error ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFF44336)
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = { viewModel.generateSyncCode(pin) },
                    enabled = !uiState.isLoading && pin.length >= 4,
                    colors = ButtonDefaults.colors(
                        containerColor = NuvioColors.Secondary,
                        focusedContainerColor = NuvioColors.SecondaryVariant,
                        contentColor = NuvioColors.OnSecondary,
                        focusedContentColor = NuvioColors.OnSecondaryVariant
                    ),
                    shape = ButtonDefaults.shape(RoundedCornerShape(50)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (uiState.isLoading) "Generating..." else "Generate Code",
                        modifier = Modifier.padding(vertical = 4.dp),
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}
