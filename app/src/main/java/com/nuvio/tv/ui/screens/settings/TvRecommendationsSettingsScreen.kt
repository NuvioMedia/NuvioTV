@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.NuvioColors

@Composable
fun TvRecommendationsSettingsContent(
    initialFocusRequester: FocusRequester? = null,
    viewModel: TvRecommendationsSettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isRecommendationsEnabled by viewModel.isRecommendationsEnabled.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SettingsDetailHeader(
            title = stringResource(R.string.settings_tv_recommendations),
            subtitle = stringResource(R.string.settings_tv_recommendations_subtitle)
        )

        SettingsGroupCard(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item(key = "tv_recommendations_toggle") {
                    SettingsToggleRow(
                        title = stringResource(R.string.settings_tv_recommendations_toggle),
                        subtitle = stringResource(R.string.settings_tv_recommendations_toggle_sub),
                        checked = isRecommendationsEnabled,
                        onToggle = {
                            viewModel.setRecommendationsEnabled(!isRecommendationsEnabled)
                        },
                        modifier = initialFocusRequester?.let { Modifier.focusRequester(it) } ?: Modifier,
                        onFocused = {}
                    )
                }

                item(key = "tv_recommendations_catalogs_selection") {
                    AnimatedVisibility(
                        visible = isRecommendationsEnabled && uiState.availableCatalogs.isNotEmpty(),
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.settings_tv_recommendations_catalogs),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = NuvioColors.TextSecondary
                                )
                                Text(
                                    text = stringResource(R.string.settings_tv_recommendations_catalogs_sub),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = NuvioColors.TextTertiary
                                )
                            }

                            LazyRow(
                                contentPadding = PaddingValues(end = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(
                                    items = uiState.availableCatalogs,
                                    key = { it.key }
                                ) { catalog ->
                                    SettingsChoiceChip(
                                        label = "${catalog.name} (${catalog.addonName})",
                                        selected = uiState.enabledCatalogs.contains(catalog.key),
                                        onClick = {
                                            viewModel.toggleCatalog(catalog.key)
                                        },
                                        onFocused = {}
                                    )
                                }
                            }
                        }
                    }
                }

                item(key = "tv_recommendations_appearance_header") {
                    AnimatedVisibility(
                        visible = isRecommendationsEnabled,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.padding(top = 10.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.settings_tv_recommendations_appearance),
                                style = MaterialTheme.typography.labelLarge,
                                color = NuvioColors.TextSecondary
                            )
                            Text(
                                text = stringResource(R.string.settings_tv_recommendations_appearance_sub),
                                style = MaterialTheme.typography.bodySmall,
                                color = NuvioColors.TextTertiary
                            )
                        }
                    }
                }
                
                item(key = "tv_recommendations_poster_style") {
                    AnimatedVisibility(
                        visible = isRecommendationsEnabled,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        SettingsToggleRow(
                            title = stringResource(R.string.settings_tv_recommendations_poster_style_title),
                            subtitle = stringResource(R.string.settings_tv_recommendations_poster_style_sub),
                            checked = uiState.useWidePoster,
                            onToggle = { viewModel.setUseWidePoster(!uiState.useWidePoster) }
                        )
                    }
                }

                item(key = "tv_recommendations_item_limit") {
                    AnimatedVisibility(
                        visible = isRecommendationsEnabled,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        val limits = listOf(15, 25, 40, 60)
                        val currentIndex = limits.indexOf(uiState.maxItemsPerChannel).takeIf { it >= 0 } ?: 1
                        
                        SettingsActionRow(
                            title = stringResource(R.string.settings_tv_recommendations_item_limit_title),
                            subtitle = stringResource(R.string.settings_tv_recommendations_item_limit_sub),
                            value = uiState.maxItemsPerChannel.toString(),
                            onClick = { 
                                val nextIndex = (currentIndex + 1) % limits.size
                                viewModel.setMaxItemsPerChannel(limits[nextIndex])
                            }
                        )
                    }
                }

                item(key = "tv_recommendations_sync_interval") {
                    AnimatedVisibility(
                        visible = isRecommendationsEnabled,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        val intervals = listOf(1, 3, 6, 12, 24, 0)
                        val currentIndex = intervals.indexOf(uiState.syncIntervalHours).takeIf { it >= 0 } ?: 1
                        val valueDisplay = if (uiState.syncIntervalHours > 0) {
                            stringResource(R.string.settings_tv_recommendations_interval_hours, uiState.syncIntervalHours)
                        } else {
                            stringResource(R.string.settings_tv_recommendations_interval_off)
                        }
                        
                        SettingsActionRow(
                            title = stringResource(R.string.settings_tv_recommendations_sync_interval_title),
                            subtitle = stringResource(R.string.settings_tv_recommendations_sync_interval_sub),
                            value = valueDisplay,
                            onClick = { 
                                val nextIndex = (currentIndex + 1) % intervals.size
                                viewModel.setSyncIntervalHours(intervals[nextIndex])
                            }
                        )
                    }
                }
            }
        }
    }
}
