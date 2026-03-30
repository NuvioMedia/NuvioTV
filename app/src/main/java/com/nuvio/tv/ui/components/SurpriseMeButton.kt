package com.nuvio.tv.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.NuvioColors

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SurpriseMeButton(
    onClick: () -> Unit,
    isLoading: Boolean,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        enabled = !isLoading,
        modifier = modifier,
        colors = ButtonDefaults.colors(
            containerColor = NuvioColors.BackgroundCard,
            contentColor = NuvioColors.TextPrimary
        )
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (isLoading) {
                    stringResource(R.string.surprise_me_loading)
                } else {
                    stringResource(R.string.surprise_me)
                },
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}
