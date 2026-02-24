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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.ui.theme.NuvioColors
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R

@Composable
fun AuthSignInScreen(
    onBackPress: () -> Unit = {},
    onNavigateToQrSignIn: () -> Unit = {},
    onSuccess: () -> Unit = {}
) {
    BackHandler { onBackPress() }

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
                text = stringResource(R.string.auth_signin_title),
                style = MaterialTheme.typography.headlineSmall,
                color = NuvioColors.TextPrimary,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.auth_signin_tv_disabled),
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioColors.TextSecondary,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(22.dp))
            Button(
                onClick = onNavigateToQrSignIn,
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
                    text = stringResource(R.string.auth_signin_qr_btn),
                    modifier = Modifier.padding(vertical = 4.dp),
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}
