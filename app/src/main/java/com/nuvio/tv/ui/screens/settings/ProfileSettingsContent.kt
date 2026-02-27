@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nuvio.tv.domain.model.PROFILE_AVATAR_COLORS
import com.nuvio.tv.domain.model.UserProfile
import com.nuvio.tv.ui.components.ProfileAvatarCircle
import com.nuvio.tv.ui.screens.account.InputField
import com.nuvio.tv.ui.theme.NuvioColors
import com.nuvio.tv.R

private enum class ProfileSettingsMode {
    List,
    Edit,
    Create
}

@Composable
internal fun ProfileSettingsContent(
    viewModel: ProfileSettingsViewModel = hiltViewModel()
) {
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val isCreating by viewModel.isCreating.collectAsStateWithLifecycle()
    var mode by remember { mutableStateOf(ProfileSettingsMode.List) }
    var editingProfile by remember { mutableStateOf<UserProfile?>(null) }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SettingsDetailHeader(
            title = stringResource(R.string.profile_title),
            subtitle = stringResource(R.string.profile_subtitle)
        )

        SettingsGroupCard(modifier = Modifier.fillMaxSize()) {
            when (mode) {
                ProfileSettingsMode.Edit -> {
                    val profile = editingProfile ?: run {
                        mode = ProfileSettingsMode.List
                        return@SettingsGroupCard
                    }
                    ProfileEditForm(
                        profile = profile,
                        onSave = { updated ->
                            viewModel.updateProfile(updated)
                            editingProfile = null
                            mode = ProfileSettingsMode.List
                        },
                        onDelete = if (!profile.isPrimary) {
                            {
                                viewModel.deleteProfile(profile.id)
                                editingProfile = null
                                mode = ProfileSettingsMode.List
                            }
                        } else null,
                        onCancel = {
                            editingProfile = null
                            mode = ProfileSettingsMode.List
                        }
                    )
                }

                ProfileSettingsMode.Create -> {
                    ProfileCreateForm(
                        existingProfiles = profiles,
                        isCreating = isCreating,
                        onCreate = { name, color, useAddons, usePlugins ->
                            viewModel.createProfile(name, color, useAddons, usePlugins)
                            mode = ProfileSettingsMode.List
                        },
                        onCancel = { mode = ProfileSettingsMode.List }
                    )
                }

                ProfileSettingsMode.List -> {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(
                            items = profiles,
                            key = { it.id }
                        ) { profile ->
                            ProfileListItem(
                                profile = profile,
                                onClick = {
                                    editingProfile = profile
                                    mode = ProfileSettingsMode.Edit
                                }
                            )
                        }

                        if (viewModel.canAddProfile) {
                            item(key = "add_profile") {
                                AddProfileButton(
                                    onClick = { mode = ProfileSettingsMode.Create }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileListItem(
    profile: UserProfile,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 62.dp),
        colors = CardDefaults.colors(
            containerColor = NuvioColors.BackgroundElevated,
            focusedContainerColor = NuvioColors.BackgroundElevated
        ),
        border = CardDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, NuvioColors.FocusRing),
                shape = RoundedCornerShape(SettingsPillRadius)
            )
        ),
        shape = CardDefaults.shape(RoundedCornerShape(SettingsPillRadius)),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ProfileAvatarCircle(
                name = profile.name,
                colorHex = profile.avatarColorHex,
                size = 44.dp
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = profile.name,
                    color = NuvioColors.TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
                if (profile.isPrimary) {
                    Text(
                        text = stringResource(R.string.profile_primary_label),
                        color = NuvioColors.TextSecondary,
                        fontSize = 12.sp
                    )
                } else {
                    val sharing = buildList {
                        if (profile.usesPrimaryAddons) add(stringResource(R.string.profile_addons))
                        if (profile.usesPrimaryPlugins) add(stringResource(R.string.profile_plugins))
                    }
                    if (sharing.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.profile_shares_primary, sharing.joinToString(" & ")),
                            color = NuvioColors.TextSecondary,
                            fontSize = 12.sp
                        )
                    }
                }
            }

            Text(
                text = stringResource(R.string.profile_edit_label),
                color = NuvioColors.TextSecondary,
                fontSize = 14.sp
            )
        }
    }
}

@Composable
private fun AddProfileButton(
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        colors = CardDefaults.colors(
            containerColor = NuvioColors.Background,
            focusedContainerColor = NuvioColors.Background
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
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "+",
                    color = NuvioColors.TextSecondary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Light
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.profile_add),
                    color = NuvioColors.TextSecondary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun ProfileCreateForm(
    existingProfiles: List<UserProfile>,
    isCreating: Boolean,
    onCreate: (name: String, color: String, useAddons: Boolean, usePlugins: Boolean) -> Unit,
    onCancel: () -> Unit
) {
    var name by remember { mutableStateOf("Profile ${existingProfiles.size + 1}") }
    val defaultColor = remember(existingProfiles) {
        val usedColors = existingProfiles.map { it.avatarColorHex }.toSet()
        PROFILE_AVATAR_COLORS.firstOrNull { it !in usedColors } ?: PROFILE_AVATAR_COLORS.first()
    }
    var selectedColor by remember { mutableStateOf(defaultColor) }
    var usesPrimaryAddons by remember { mutableStateOf(false) }
    var usesPrimaryPlugins by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = stringResource(R.string.profile_create_title),
            color = NuvioColors.TextPrimary,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold
        )

        InputField(
            value = name,
            onValueChange = { if (it.length <= 20) name = it },
            placeholder = stringResource(R.string.profile_name_placeholder)
        )

        Text(
            text = stringResource(R.string.profile_avatar_color),
            color = NuvioColors.TextSecondary,
            fontSize = 14.sp
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PROFILE_AVATAR_COLORS.forEach { colorHex ->
                ColorPickerCircle(
                    colorHex = colorHex,
                    isSelected = colorHex == selectedColor,
                    onClick = { selectedColor = colorHex }
                )
            }
        }

        SettingsToggleRow(
            title = stringResource(R.string.profile_use_primary_addons),
            subtitle = null,
            checked = usesPrimaryAddons,
            onToggle = { usesPrimaryAddons = !usesPrimaryAddons }
        )
        SettingsToggleRow(
            title = stringResource(R.string.profile_use_primary_plugins),
            subtitle = null,
            checked = usesPrimaryPlugins,
            onToggle = { usesPrimaryPlugins = !usesPrimaryPlugins }
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            ProfileFormButton(text = stringResource(R.string.action_cancel), onClick = onCancel, modifier = Modifier.weight(1f))
            ProfileFormButton(
                text = if (isCreating) stringResource(R.string.profile_creating) else stringResource(R.string.profile_create_btn),
                enabled = name.isNotBlank() && !isCreating,
                onClick = { onCreate(name.trim(), selectedColor, usesPrimaryAddons, usesPrimaryPlugins) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun ProfileEditForm(
    profile: UserProfile,
    onSave: (UserProfile) -> Unit,
    onDelete: (() -> Unit)?,
    onCancel: () -> Unit
) {
    var name by remember { mutableStateOf(profile.name) }
    var selectedColor by remember { mutableStateOf(profile.avatarColorHex) }
    var usesPrimaryAddons by remember { mutableStateOf(profile.usesPrimaryAddons) }
    var usesPrimaryPlugins by remember { mutableStateOf(profile.usesPrimaryPlugins) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = stringResource(R.string.profile_edit_title_id, profile.id),
            color = NuvioColors.TextPrimary,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold
        )

        InputField(
            value = name,
            onValueChange = { if (it.length <= 20) name = it },
            placeholder = stringResource(R.string.profile_name_placeholder)
        )

        Text(
            text = stringResource(R.string.profile_avatar_color),
            color = NuvioColors.TextSecondary,
            fontSize = 14.sp
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PROFILE_AVATAR_COLORS.forEach { colorHex ->
                ColorPickerCircle(
                    colorHex = colorHex,
                    isSelected = colorHex == selectedColor,
                    onClick = { selectedColor = colorHex }
                )
            }
        }

        if (!profile.isPrimary) {
            SettingsToggleRow(
                title = stringResource(R.string.profile_use_primary_addons),
                subtitle = null,
                checked = usesPrimaryAddons,
                onToggle = { usesPrimaryAddons = !usesPrimaryAddons }
            )
            SettingsToggleRow(
                title = stringResource(R.string.profile_use_primary_plugins),
                subtitle = null,
                checked = usesPrimaryPlugins,
                onToggle = { usesPrimaryPlugins = !usesPrimaryPlugins }
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            ProfileFormButton(text = stringResource(R.string.action_cancel), onClick = onCancel, modifier = Modifier.weight(1f))
            ProfileFormButton(
                text = stringResource(R.string.action_save),
                onClick = {
                    onSave(
                        profile.copy(
                            name = name.trim().ifBlank { profile.name },
                            avatarColorHex = selectedColor,
                            usesPrimaryAddons = usesPrimaryAddons,
                            usesPrimaryPlugins = usesPrimaryPlugins
                        )
                    )
                },
                modifier = Modifier.weight(1f)
            )
            if (onDelete != null) {
                var confirmDelete by remember { mutableStateOf(false) }
                ProfileFormButton(
                    text = if (confirmDelete) stringResource(R.string.profile_confirm) else stringResource(R.string.profile_delete),
                    onClick = {
                        if (confirmDelete) onDelete() else confirmDelete = true
                    },
                    modifier = Modifier.weight(1f),
                    isDestructive = true
                )
            }
        }
    }
}

@Composable
private fun ColorPickerCircle(
    colorHex: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val color = remember(colorHex) {
        runCatching { Color(android.graphics.Color.parseColor(colorHex)) }
            .getOrDefault(Color(0xFF1E88E5))
    }

    Card(
        onClick = onClick,
        modifier = Modifier.size(32.dp),
        colors = CardDefaults.colors(
            containerColor = color,
            focusedContainerColor = color
        ),
        border = CardDefaults.border(
            border = if (isSelected) Border(
                border = BorderStroke(2.dp, Color.White),
                shape = CircleShape
            ) else Border.None,
            focusedBorder = Border(
                border = BorderStroke(2.dp, Color.White),
                shape = CircleShape
            )
        ),
        shape = CardDefaults.shape(CircleShape),
        scale = CardDefaults.scale(focusedScale = 1.15f, pressedScale = 1f)
    ) { }
}

@Composable
private fun ProfileFormButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isDestructive: Boolean = false
) {
    val containerColor = when {
        !enabled -> NuvioColors.Border
        isDestructive -> Color(0xFF5D1F1F)
        else -> NuvioColors.BackgroundElevated
    }
    val focusedColor = when {
        !enabled -> NuvioColors.Border
        isDestructive -> Color(0xFFD32F2F)
        else -> NuvioColors.FocusBackground
    }

    Card(
        onClick = { if (enabled) onClick() },
        modifier = modifier.height(44.dp),
        colors = CardDefaults.colors(
            containerColor = containerColor,
            focusedContainerColor = focusedColor
        ),
        border = CardDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, if (isDestructive) Color(0xFFD32F2F) else NuvioColors.FocusRing),
                shape = RoundedCornerShape(SettingsPillRadius)
            )
        ),
        shape = CardDefaults.shape(RoundedCornerShape(SettingsPillRadius)),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                color = if (enabled) NuvioColors.TextPrimary else NuvioColors.TextSecondary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
