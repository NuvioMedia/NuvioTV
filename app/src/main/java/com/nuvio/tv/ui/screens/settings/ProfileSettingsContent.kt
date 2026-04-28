@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.NuvioColors

@Composable
internal fun ProfileSettingsContent(
    onManageProfiles: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        SettingsDetailHeader(
            title = stringResource(R.string.profile_title),
            subtitle = stringResource(R.string.profile_subtitle)
        )

        SettingsGroupCard(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopStart)
                .padding(top = 84.dp)
        ) {
            Card(
                onClick = onManageProfiles,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp),
                colors = CardDefaults.colors(
                    containerColor = NuvioColors.BackgroundElevated,
                    focusedContainerColor = NuvioColors.FocusBackground
                ),
                border = CardDefaults.border(
                    border = Border(
                        border = BorderStroke(1.dp, NuvioColors.Border),
                        shape = RoundedCornerShape(SettingsPillRadius)
                    ),
                    focusedBorder = Border(
                        border = BorderStroke(2.dp, NuvioColors.FocusRing),
                        shape = RoundedCornerShape(SettingsPillRadius)
                    )
                ),
                shape = CardDefaults.shape(RoundedCornerShape(SettingsPillRadius)),
                scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 18.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text(
                        text = stringResource(R.string.profile_manage_button),
                        color = NuvioColors.TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}
