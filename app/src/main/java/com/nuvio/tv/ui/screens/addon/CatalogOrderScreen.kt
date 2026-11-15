@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.addon

import com.nuvio.tv.ui.theme.NuvioMotion
import com.nuvio.tv.ui.theme.NuvioTheme

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
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

    BackHandler(enabled = uiState.movingKey == null) {
        Log.d("CatalogOrder", "screen BACK leave")
        onBackPress()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioTheme.colors.Background)
            .padding(horizontal = NuvioTheme.spacing.xxxl, vertical = NuvioTheme.spacing.xl),
    ) {
        when {
            uiState.isLoading -> {
                Column(modifier = Modifier.fillMaxSize()) {
                    CatalogOrderHeader(
                        followAddonsOrder = uiState.followAddonsOrder,
                        movingKey = uiState.movingKey,
                        onToggleFollowAddons = viewModel::toggleFollowAddonsOrder,
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        LoadingIndicator()
                    }
                }
            }

            uiState.items.isEmpty() -> {
                Column(modifier = Modifier.fillMaxSize()) {
                    CatalogOrderHeader(
                        followAddonsOrder = uiState.followAddonsOrder,
                        movingKey = uiState.movingKey,
                        onToggleFollowAddons = viewModel::toggleFollowAddonsOrder,
                    )
                    Text(
                        text = stringResource(R.string.catalog_order_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = NuvioTheme.colors.TextSecondary,
                    )
                }
            }

            else -> {
                TvReorderList(
                    items = uiState.items,
                    key = { it.key },
                    canMove = { it.canMove && uiState.movingKey == null },
                    movingKey = uiState.movingKey,
                    onStartMove = viewModel::enterMoveMode,
                    onMoveBy = viewModel::moveDuringDrag,
                    onConfirmMove = {
                        Log.d("CatalogOrder", "screen Done/confirm requested")
                        viewModel.confirmMove()
                    },
                    onCancelMove = {
                        Log.d("CatalogOrder", "screen cancel requested")
                        viewModel.cancelMove()
                    },
                    modifier = Modifier.fillMaxSize(),
                    header = {
                        CatalogOrderHeader(
                            followAddonsOrder = uiState.followAddonsOrder,
                            movingKey = uiState.movingKey,
                            onToggleFollowAddons = viewModel::toggleFollowAddonsOrder,
                        )
                    },
                    useHeaderAwareBringIntoView = true,
                    scrollToTopOnEdgeItem = true,
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
private fun CatalogOrderHeader(
    followAddonsOrder: Boolean,
    movingKey: String?,
    onToggleFollowAddons: (Boolean) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
    Text(
        text = stringResource(R.string.catalog_order_title),
        style = MaterialTheme.typography.headlineLarge,
        color = NuvioTheme.colors.TextPrimary,
    )
    Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))
    Text(
        text = stringResource(R.string.catalog_order_subtitle),
        style = MaterialTheme.typography.bodyMedium,
        color = NuvioTheme.colors.TextSecondary,
    )
    Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.catalog_order_follow_addons),
                style = MaterialTheme.typography.titleMedium,
                color = NuvioTheme.colors.TextPrimary,
            )
            Text(
                text = stringResource(R.string.catalog_order_follow_addons_desc),
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextSecondary,
            )
        }
        Surface(
            onClick = {
                if (movingKey == null) {
                    onToggleFollowAddons(!followAddonsOrder)
                }
            },
            modifier = Modifier.semantics {
                role = Role.Switch
                toggleableState = if (followAddonsOrder) ToggleableState.On else ToggleableState.Off
            },
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                focusedContainerColor = NuvioTheme.colors.FocusBackground
            ),
            border = ClickableSurfaceDefaults.border(
                focusedBorder = Border(
                    border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                    shape = RoundedCornerShape(NuvioTheme.radii.md)
                )
            ),
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md)),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
        ) {
            Box(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Switch(
                    checked = followAddonsOrder,
                    onCheckedChange = null,
                    enabled = movingKey == null,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = NuvioTheme.colors.Secondary,
                        checkedTrackColor = NuvioTheme.colors.Secondary.copy(alpha = 0.3f)
                    ),
                )
            }
        }
    }
    Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))
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
    val cardColor by animateColorAsState(
        targetValue = if (isMoving) {
            NuvioTheme.colors.FocusBackground
        } else {
            NuvioTheme.colors.BackgroundCard
        },
        animationSpec = NuvioMotion.focusTween(),
        label = "catalogOrderCardColor",
    )
    val cardBorder = if (isMoving) {
        BorderStroke(NuvioTheme.spacing.xxs, NuvioTheme.colors.Primary)
    } else {
        null
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = cardColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
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
                Box(
                    modifier = Modifier.size(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.DragHandle,
                        contentDescription = null,
                        tint = NuvioTheme.colors.Primary,
                        modifier = Modifier.alpha(if (isMoving) 1f else 0f),
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
                Button(
                    onClick = if (isMoving) onConfirmMove else onStartMove,
                    enabled = isMoving || (item.canMove && actionsEnabled),
                    colors = if (isMoving) {
                        ButtonDefaults.colors(
                            containerColor = NuvioTheme.colors.Primary,
                            contentColor = NuvioTheme.colors.TextPrimary,
                            focusedContainerColor = NuvioTheme.colors.Primary,
                            focusedContentColor = NuvioTheme.colors.TextPrimary,
                        )
                    } else {
                        ButtonDefaults.colors(
                            containerColor = NuvioTheme.colors.BackgroundCard,
                            contentColor = NuvioTheme.colors.TextSecondary,
                            focusedContainerColor = NuvioTheme.colors.FocusBackground,
                            focusedContentColor = NuvioTheme.colors.Primary
                        )
                    },
                    border = ButtonDefaults.border(
                        focusedBorder = Border(
                            border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                            shape = RoundedCornerShape(NuvioTheme.radii.md)
                        )
                    ),
                    shape = ButtonDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md))
                ) {
                    Text(
                        text = stringResource(
                            if (isMoving) R.string.catalog_order_done else R.string.catalog_order_move,
                        ),
                    )
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
