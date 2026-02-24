@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.NuvioColors
import com.nuvio.tv.updater.UpdateViewModel

@Composable
fun AboutScreen(
    onBackPress: () -> Unit = {}
) {
    BackHandler { onBackPress() }

    SettingsStandaloneScaffold(
        title = stringResource(R.string.about_title),
        subtitle = stringResource(R.string.about_subtitle)
    ) {
        AboutSettingsContent()
    }
}

@Composable
fun AboutSettingsContent(
    initialFocusRequester: FocusRequester? = null
) {
    val context = LocalContext.current
    val updateViewModel: UpdateViewModel = hiltViewModel(context as ComponentActivity)

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SettingsDetailHeader(
            title = stringResource(R.string.about_title),
            subtitle = stringResource(R.string.about_subtitle)
        )

        SettingsGroupCard(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            title = null
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Image(
                    painter = painterResource(id = R.drawable.app_logo_wordmark),
                    contentDescription = "NuvioTV",
                    modifier = Modifier
                        .width(180.dp)
                        .height(50.dp),
                    contentScale = ContentScale.Fit
                )

                Text(
                    text = stringResource(R.string.about_made_with_love),
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioColors.TextSecondary,
                    textAlign = TextAlign.Center
                )

                Text(
                    text = stringResource(R.string.about_version, BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioColors.TextSecondary,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(4.dp))

                SettingsActionRow(
                    title = stringResource(R.string.about_check_updates),
                    subtitle = stringResource(R.string.about_check_updates_subtitle),
                    trailingIcon = Icons.Default.OpenInNew,
                    modifier = if (initialFocusRequester != null) {
                        Modifier.focusRequester(initialFocusRequester)
                    } else {
                        Modifier
                    },
                    onClick = {
                        updateViewModel.checkForUpdates(force = true, showNoUpdateFeedback = true)
                    }
                )

                SettingsActionRow(
                    title = stringResource(R.string.about_privacy_policy),
                    subtitle = stringResource(R.string.about_privacy_policy_subtitle),
                    trailingIcon = Icons.Default.OpenInNew,
                    onClick = {
                        val intent = Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://tapframe.github.io/NuvioStreaming/#privacy-policy")
                        )
                        context.startActivity(intent)
                    }
                )
            }
        }
    }
}
