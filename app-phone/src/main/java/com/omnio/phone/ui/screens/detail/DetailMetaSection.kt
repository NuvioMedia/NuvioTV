package com.omnio.phone.ui.screens.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.omnio.tv.domain.model.Meta

@Composable
fun DetailMetaSection(
    meta: Meta,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = meta.name,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )

        val factParts = listOfNotNull(
            meta.releaseInfo?.takeIf { it.isNotBlank() },
            meta.runtime?.takeIf { it.isNotBlank() },
            meta.ageRating?.takeIf { it.isNotBlank() }
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            meta.imdbRating?.let { rating ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Star,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary
                    )
                    Text(
                        text = "%.1f".format(rating),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
            }
            if (factParts.isNotEmpty()) {
                Text(
                    text = factParts.joinToString("  •  "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (meta.genres.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                meta.genres.take(6).forEach { genre ->
                    AssistChip(
                        onClick = {},
                        enabled = false,
                        label = { Text(genre) },
                        colors = AssistChipDefaults.assistChipColors(
                            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            disabledLabelColor = MaterialTheme.colorScheme.onSurface
                        )
                    )
                }
            }
        }
    }
}
