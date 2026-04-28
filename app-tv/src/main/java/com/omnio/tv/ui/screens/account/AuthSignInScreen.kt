@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.omnio.tv.ui.screens.account

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.omnio.tv.R
import com.omnio.tv.domain.model.AuthState
import com.omnio.tv.ui.theme.OmnioColors

@Composable
fun AuthSignInScreen(
    onBackPress: () -> Unit = {},
    viewModel: AccountViewModel = hiltViewModel(),
    onSuccess: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    BackHandler { onBackPress() }

    LaunchedEffect(uiState.authState) {
        if (uiState.authState is AuthState.FullAccount) {
            onSuccess()
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.5f)
                .background(
                    color = OmnioColors.BackgroundElevated,
                    shape = RoundedCornerShape(20.dp)
                )
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.auth_signin_title),
                style = MaterialTheme.typography.headlineSmall,
                color = OmnioColors.TextPrimary,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.auth_signin_tv_enabled),
                style = MaterialTheme.typography.bodyMedium,
                color = OmnioColors.TextSecondary,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(22.dp))

            InputField(
                value = email,
                onValueChange = { email = it },
                placeholder = stringResource(R.string.auth_signin_email_placeholder),
                keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Next
            )
            Spacer(modifier = Modifier.height(14.dp))
            InputField(
                value = password,
                onValueChange = { password = it },
                placeholder = stringResource(R.string.auth_signin_password_placeholder),
                keyboardType = KeyboardType.Password,
                isPassword = true,
                imeAction = ImeAction.Done,
                onImeAction = {
                    if (!uiState.isLoading) {
                        viewModel.signIn(email.trim(), password)
                    }
                }
            )

            if (uiState.error != null) {
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = uiState.error.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = OmnioColors.Error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.height(22.dp))
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = { viewModel.signIn(email.trim(), password) },
                    enabled = !uiState.isLoading,
                    colors = ButtonDefaults.colors(
                        containerColor = OmnioColors.Secondary,
                        focusedContainerColor = OmnioColors.SecondaryVariant,
                        contentColor = OmnioColors.OnSecondary,
                        focusedContentColor = OmnioColors.OnSecondaryVariant
                    ),
                    shape = ButtonDefaults.shape(RoundedCornerShape(50)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (uiState.isLoading) stringResource(R.string.account_loading) else stringResource(R.string.auth_signin_submit_btn),
                        modifier = Modifier.padding(vertical = 4.dp),
                        fontWeight = FontWeight.Medium
                    )
                }

                Button(
                    onClick = { viewModel.signUp(email.trim(), password) },
                    enabled = !uiState.isLoading,
                    colors = ButtonDefaults.colors(
                        containerColor = OmnioColors.BackgroundCard,
                        focusedContainerColor = OmnioColors.FocusBackground,
                        contentColor = OmnioColors.TextPrimary,
                        focusedContentColor = OmnioColors.TextPrimary
                    ),
                    shape = ButtonDefaults.shape(RoundedCornerShape(50)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(R.string.auth_signup_submit_btn),
                        modifier = Modifier.padding(vertical = 4.dp),
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.auth_signin_hint),
                style = MaterialTheme.typography.bodySmall,
                color = OmnioColors.TextTertiary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
