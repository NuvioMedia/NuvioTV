@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.addon

import com.nuvio.tv.ui.theme.NuvioTheme

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.TvReorderList
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R

@Composable
fun CatalogOrderScreen(
    viewModel: CatalogOrderViewModel = hiltViewModel(),
    onBackPress: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    BackHandler(enabled = uiState.movingKey == null) { onBackPress() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = NuvioTheme.spacing.xxxl, vertical = NuvioTheme.spacing.xl),
    ) {
        Text(
            text = stringResource(R.string.catalog_order_title),
            style = MaterialTheme.typography.headlineLarge,
            color = NuvioTheme.colors.TextPrimary
        )
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))
        Text(
            text = stringResource(R.string.catalog_order_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = NuvioTheme.colors.TextSecondary
        )
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.catalog_order_follow_addons),
                    style = MaterialTheme.typography.titleMedium,
                    color = NuvioTheme.colors.TextPrimary
                )
                Text(
                    text = stringResource(R.string.catalog_order_follow_addons_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextSecondary
                )
            }
            Switch(
                checked = uiState.followAddonsOrder,
                onCheckedChange = { viewModel.toggleFollowAddonsOrder(it) },
                enabled = uiState.movingKey == null,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = NuvioTheme.colors.Primary,
                    checkedTrackColor = NuvioTheme.colors.Primary.copy(alpha = 0.5f)
                )
            )
        }
        if (uiState.movingKey != null) {
            Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))
            Text(
                text = stringResource(R.string.catalog_order_move_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.Primary,
            )
        }
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))

        when {
            uiState.isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    LoadingIndicator()
                }
            }

            uiState.items.isEmpty() -> {
                Text(
                    text = stringResource(R.string.catalog_order_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = NuvioTheme.colors.TextSecondary
                )
            }

            else -> {
                TvReorderList(
                    items = uiState.items,
                    key = { it.key },
                    canMove = { it.canMove && uiState.movingKey == null },
                    movingKey = uiState.movingKey,
                    onStartMove = viewModel::enterMoveMode,
                    onMoveBy = viewModel::moveDuringDrag,
                    onConfirmMove = { viewModel.confirmMove() },
                    onCancelMove = viewModel::cancelMove,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) { item, isMoving, onStartMove, onConfirmMove ->
                    CatalogOrderCard(
                        item = item,
                        isMoving = isMoving,
                        onStartMove = onStartMove,
                        onConfirmMove = onConfirmMove,
                        onToggleEnabled = { viewModel.toggleCatalogEnabled(item.disableKey) },
                        actionsEnabled = uiState.movingKey == null || isMoving,
                    )
                }
            }
        }
    }
}

@Composable
private fun CatalogOrderCard(
    item: CatalogOrderItem,
    isMoving: Boolean,
    onStartMove: () -> Unit,
    onConfirmMove: () -> Unit,
    onToggleEnabled: () -> Unit,
    actionsEnabled: Boolean,
) {
    val cardColor = when {
        isMoving -> NuvioTheme.colors.FocusBackground
        else -> NuvioTheme.colors.BackgroundCard
    }
    val cardBorder = if (isMoving) {
        BorderStroke(NuvioTheme.spacing.xxs, NuvioTheme.colors.Primary)
    } else {
        null
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = cardColor),
        shape = RoundedCornerShape(NuvioTheme.radii.md),
        border = cardBorder,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
            ) {
                if (isMoving) {
                    Icon(
                        imageVector = Icons.Default.DragHandle,
                        contentDescription = null,
                        tint = NuvioTheme.colors.Primary,
                    )
                }
                Column {
                    Text(
                        text = "${item.catalogName} - ${item.typeLabel.toDisplayTypeLabel()}",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = if (item.isDisabled) NuvioTheme.colors.TextSecondary else NuvioTheme.colors.TextPrimary
                    )
                    Spacer(modifier = Modifier.height(NuvioTheme.spacing.xs))
                    Text(
                        text = item.addonName,
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.TextSecondary
                    )
                    if (item.isDisabled) {
                        Spacer(modifier = Modifier.height(NuvioTheme.spacing.xs))
                        Text(
                            text = stringResource(R.string.catalog_order_disabled_on_home),
                            style = MaterialTheme.typography.bodySmall,
                            color = NuvioTheme.colors.Error
                        )
                    }
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isMoving) {
                    Button(
                        onClick = onConfirmMove,
                        colors = ButtonDefaults.colors(
                            containerColor = NuvioTheme.colors.Primary,
                            contentColor = NuvioTheme.colors.TextPrimary,
                            focusedContainerColor = NuvioTheme.colors.Primary,
                            focusedContentColor = NuvioTheme.colors.TextPrimary,
                        ),
                        border = ButtonDefaults.border(
                            focusedBorder = Border(
                                border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                                shape = RoundedCornerShape(NuvioTheme.radii.md)
                            )
                        ),
                        shape = ButtonDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md))
                    ) {
                        Text(text = stringResource(R.string.catalog_order_done))
                    }
                } else {
                    Button(
                        onClick = onStartMove,
                        enabled = item.canMove && actionsEnabled,
                        colors = ButtonDefaults.colors(
                            containerColor = NuvioTheme.colors.BackgroundCard,
                            contentColor = NuvioTheme.colors.TextSecondary,
                            focusedContainerColor = NuvioTheme.colors.FocusBackground,
                            focusedContentColor = NuvioTheme.colors.Primary
                        ),
                        border = ButtonDefaults.border(
                            focusedBorder = Border(
                                border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                                shape = RoundedCornerShape(NuvioTheme.radii.md)
                            )
                        ),
                        shape = ButtonDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md))
                    ) {
                        Text(text = stringResource(R.string.catalog_order_move))
                    }
                }

                Button(
                    onClick = onToggleEnabled,
                    enabled = actionsEnabled && !isMoving,
                    colors = ButtonDefaults.colors(
                        containerColor = NuvioTheme.colors.BackgroundCard,
                        contentColor = if (item.isDisabled) NuvioTheme.colors.Success else NuvioTheme.colors.TextSecondary,
                        focusedContainerColor = NuvioTheme.colors.FocusBackground,
                        focusedContentColor = if (item.isDisabled) NuvioTheme.colors.Success else NuvioTheme.colors.Error
                    ),
                    border = ButtonDefaults.border(
                        focusedBorder = Border(
                            border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                            shape = RoundedCornerShape(NuvioTheme.radii.md)
                        )
                    ),
                    shape = ButtonDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md))
                ) {
                    Text(
                        text = if (item.isDisabled) {
                            stringResource(R.string.catalog_order_enable)
                        } else {
                            stringResource(R.string.catalog_order_disable)
                        }
                    )
                }
            }
        }
    }
}

private fun String.toDisplayTypeLabel(): String {
    return replaceFirstChar { ch ->
        if (ch.isLowerCase()) ch.titlecase() else ch.toString()
    }
}
