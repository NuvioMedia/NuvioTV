package com.nuvio.tv.ui.screens.profile

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Text
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import com.nuvio.tv.data.remote.supabase.AvatarCatalogItem
import com.nuvio.tv.domain.model.UserProfile
import com.nuvio.tv.ui.components.AvatarPickerGrid
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.components.ProfileAvatarCircle
import com.nuvio.tv.ui.theme.NuvioColors

private object ProfileSelectionSpacing {
    val ScreenPaddingHorizontal = 56.dp
    val ScreenPaddingVertical = 48.dp
    val LogoWidth = 190.dp
    val LogoHeight = 44.dp
    val LogoToHeading = 28.dp
    val HeadingToSubheading = 12.dp
    val GridItemGap = 28.dp
    val CardWidth = 152.dp
    val CardPaddingHorizontal = 10.dp
    val CardPaddingVertical = 8.dp
    val AvatarContainer = 126.dp
    val AvatarToName = 12.dp
    val NameToMeta = 8.dp
    val MetaSlotHeight = 16.dp
    val EditorPanelMaxWidth = 980.dp
    val EditorPanelGap = 28.dp
    val EditorPreviewWidth = 280.dp
    val EditorPreviewAvatarSize = 112.dp
    val EditorFieldRadius = 14.dp
    val EditorPreviewTopOffset = 28.dp
    val EditorDividerHeight = 320.dp
    val EditorDividerSpacing = 18.dp
}

private val ProfileCardFocusEasing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

enum class ProfileSelectionMode {
    Selection,
    Management
}

@Composable
fun ProfileSelectionScreen(
    onProfileSelected: () -> Unit,
    screenMode: ProfileSelectionMode = ProfileSelectionMode.Selection,
    onBackPress: (() -> Unit)? = null,
    viewModel: ProfileSelectionViewModel = hiltViewModel()
) {
    val profiles by viewModel.profiles.collectAsState()
    val activeProfileId by viewModel.activeProfileId.collectAsState()
    val avatarCatalog by viewModel.avatarCatalog.collectAsState()
    val isCreating by viewModel.isCreating.collectAsState()
    val isSaving by viewModel.isSaving.collectAsState()
    val avatarImageUrlsById = remember(avatarCatalog) {
        avatarCatalog.associate { it.id to it.imageUrl }
    }
    var focusedAvatarColor by remember { mutableStateOf(Color(0xFF1E88E5)) }
    var showCreateProfile by remember { mutableStateOf(false) }
    var longPressedProfile by remember { mutableStateOf<UserProfile?>(null) }
    var suppressOptionsDialogFirstKeyUp by remember { mutableStateOf(true) }
    var profileToDelete by remember { mutableStateOf<UserProfile?>(null) }
    var profileToEdit by remember { mutableStateOf<UserProfile?>(null) }
    val onProfileFocusedColorChange = remember {
        { colorHex: String ->
            focusedAvatarColor = parseProfileColor(colorHex)
        }
    }
    val isManagementMode = screenMode == ProfileSelectionMode.Management
    val screenTitle = if (isManagementMode) {
        stringResource(R.string.profile_manage_title)
    } else {
        stringResource(R.string.profile_selection_title)
    }
    val screenSubtitle = if (isManagementMode) {
        stringResource(R.string.profile_manage_subtitle)
    } else {
        stringResource(R.string.profile_selection_subtitle)
    }
    val screenHint = if (isManagementMode) {
        stringResource(R.string.profile_manage_hint)
    } else {
        stringResource(R.string.profile_selection_hint)
    }

    if (onBackPress != null) {
        BackHandler(onBack = onBackPress)
    }

    LaunchedEffect(profiles, activeProfileId) {
        profiles.firstOrNull { it.id == activeProfileId }?.let { activeProfile ->
            focusedAvatarColor = parseProfileColor(activeProfile.avatarColorHex)
        } ?: profiles.firstOrNull()?.let { firstProfile ->
            focusedAvatarColor = parseProfileColor(firstProfile.avatarColorHex)
        }
    }

    // Close overlay when profile creation succeeds
    LaunchedEffect(isCreating) {
        if (!isCreating && showCreateProfile) {
            // Check if a new profile was just added
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        ProfileSelectionBackground(focusedAvatarColor = focusedAvatarColor)

        ProfileSelectionMainContent(
            screenTitle = screenTitle,
            screenSubtitle = screenSubtitle,
            screenHint = screenHint,
            isManagementMode = isManagementMode,
            profiles = profiles,
            activeProfileId = activeProfileId,
            canAddProfile = viewModel.canAddProfile,
            avatarImageUrlsById = avatarImageUrlsById,
            onProfileFocused = onProfileFocusedColorChange,
            onProfileSelected = { profile ->
                if (isManagementMode) {
                    suppressOptionsDialogFirstKeyUp = false
                    longPressedProfile = profile
                } else {
                    viewModel.selectProfile(profile.id, onComplete = onProfileSelected)
                }
            },
            onProfileLongPress = { profile ->
                suppressOptionsDialogFirstKeyUp = true
                longPressedProfile = profile
            },
            onAddProfileClick = { showCreateProfile = true }
        )

        // Create Profile Overlay
        AnimatedVisibility(
            visible = showCreateProfile,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(150))
        ) {
            CreateProfileOverlay(
                avatarCatalog = avatarCatalog,
                isCreating = isCreating,
                onDismiss = { showCreateProfile = false },
                onCreateProfile = { name, colorHex, avatarId ->
                    viewModel.createProfile(name, colorHex, avatarId)
                    showCreateProfile = false
                }
            )
        }

        // Edit Profile Overlay
        profileToEdit?.let { profile ->
            EditProfileOverlay(
                profile = profile,
                avatarCatalog = avatarCatalog,
                isSaving = isSaving,
                avatarUrlResolver = { avatarId -> viewModel.getAvatarImageUrl(avatarId) },
                onDismiss = { profileToEdit = null },
                onSaveProfile = { updated ->
                    viewModel.updateProfile(updated)
                    profileToEdit = null
                }
            )
        }

        // Long-press options dialog (Edit / Delete)
        longPressedProfile?.let { profile ->
            val primaryDialogFocusRequester = remember(profile.id) { FocusRequester() }
            LaunchedEffect(profile.id) {
                repeat(2) { withFrameNanos { } }
                runCatching { primaryDialogFocusRequester.requestFocus() }
            }
            NuvioDialog(
                onDismiss = { longPressedProfile = null },
                title = stringResource(R.string.profile_selection_options_title),
                width = 360.dp,
                suppressFirstKeyUp = suppressOptionsDialogFirstKeyUp
            ) {
                Button(
                    onClick = {
                        longPressedProfile = null
                        profileToEdit = profile
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(primaryDialogFocusRequester),
                    colors = ButtonDefaults.colors(
                        containerColor = NuvioColors.BackgroundCard,
                        contentColor = NuvioColors.TextPrimary
                    )
                ) {
                    Text(stringResource(R.string.profile_edit_label))
                }

                if (!profile.isPrimary) {
                    Button(
                        onClick = {
                            longPressedProfile = null
                            profileToDelete = profile
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.colors(
                            containerColor = Color(0xFF4A2323),
                            contentColor = NuvioColors.TextPrimary
                        )
                    ) {
                        Text(stringResource(R.string.profile_delete))
                    }
                }
            }
        }

        // Delete confirmation dialog
        profileToDelete?.let { profile ->
            val primaryDialogFocusRequester = remember(profile.id) { FocusRequester() }
            LaunchedEffect(profile.id) {
                repeat(2) { withFrameNanos { } }
                runCatching { primaryDialogFocusRequester.requestFocus() }
            }
            NuvioDialog(
                onDismiss = { profileToDelete = null },
                title = stringResource(R.string.profile_delete_confirm_title),
                subtitle = stringResource(R.string.profile_delete_confirm_subtitle),
                width = 420.dp,
                suppressFirstKeyUp = false
            ) {
                Button(
                    onClick = {
                        viewModel.deleteProfile(profile.id)
                        profileToDelete = null
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(primaryDialogFocusRequester),
                    colors = ButtonDefaults.colors(
                        containerColor = Color(0xFF4A2323),
                        contentColor = NuvioColors.TextPrimary
                    )
                ) {
                    Text(stringResource(R.string.profile_delete_btn))
                }
            }
        }
    }
}

@Composable
private fun ProfileSelectionBackground(
    focusedAvatarColor: Color
) {
    val animatedAvatarColor by animateColorAsState(
        targetValue = focusedAvatarColor,
        animationSpec = tween(durationMillis = 520),
        label = "focusedAvatarColor"
    )
    val gradientTop = lerp(NuvioColors.BackgroundElevated, animatedAvatarColor, 0.3f)
    val gradientMid = lerp(NuvioColors.Background, animatedAvatarColor, 0.14f)
    val halfFadeStrong = animatedAvatarColor.copy(alpha = 0.26f)
    val halfFadeSoft = animatedAvatarColor.copy(alpha = 0.08f)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioColors.Background)
            .background(
                brush = Brush.verticalGradient(
                    colorStops = arrayOf(
                        0f to gradientTop,
                        0.42f to gradientMid,
                        1f to NuvioColors.Background
                    )
                )
            )
            .background(
                brush = Brush.horizontalGradient(
                    colorStops = arrayOf(
                        0f to halfFadeStrong,
                        0.45f to halfFadeSoft,
                        0.72f to Color.Transparent,
                        1f to Color.Transparent
                    )
                )
            )
    )
}

@Composable
private fun ProfileSelectionMainContent(
    screenTitle: String,
    screenSubtitle: String,
    screenHint: String,
    isManagementMode: Boolean,
    profiles: List<UserProfile>,
    activeProfileId: Int,
    canAddProfile: Boolean,
    avatarImageUrlsById: Map<String, String>,
    onProfileFocused: (String) -> Unit,
    onProfileSelected: (UserProfile) -> Unit,
    onProfileLongPress: (UserProfile) -> Unit,
    onAddProfileClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(
                horizontal = ProfileSelectionSpacing.ScreenPaddingHorizontal,
                vertical = ProfileSelectionSpacing.ScreenPaddingVertical
            ),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            painter = painterResource(id = R.drawable.app_logo_wordmark),
            contentDescription = "NuvioTV",
            modifier = Modifier
                .width(ProfileSelectionSpacing.LogoWidth)
                .height(ProfileSelectionSpacing.LogoHeight),
            contentScale = ContentScale.Fit
        )

        Spacer(modifier = Modifier.height(ProfileSelectionSpacing.LogoToHeading))

        Text(
            text = screenTitle,
            color = NuvioColors.TextPrimary,
            fontSize = 44.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.5).sp
        )

        Spacer(modifier = Modifier.height(ProfileSelectionSpacing.HeadingToSubheading))

        Text(
            text = screenSubtitle,
            color = NuvioColors.TextSecondary,
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium
        )

        Spacer(modifier = Modifier.weight(1f, fill = true))

        ProfileGrid(
            profiles = profiles,
            activeProfileId = activeProfileId,
            isManagementMode = isManagementMode,
            canAddProfile = canAddProfile,
            avatarImageUrlsById = avatarImageUrlsById,
            onProfileFocused = onProfileFocused,
            onProfileSelected = onProfileSelected,
            onProfileLongPress = onProfileLongPress,
            onAddProfileClick = onAddProfileClick
        )

        Spacer(modifier = Modifier.weight(1f, fill = true))

        Text(
            text = screenHint,
            color = NuvioColors.TextTertiary.copy(alpha = 0.9f),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun ProfileGrid(
    profiles: List<UserProfile>,
    activeProfileId: Int,
    isManagementMode: Boolean,
    canAddProfile: Boolean,
    avatarImageUrlsById: Map<String, String>,
    onProfileFocused: (String) -> Unit,
    onProfileSelected: (UserProfile) -> Unit,
    onProfileLongPress: (UserProfile) -> Unit,
    onAddProfileClick: () -> Unit
) {
    val totalItems = profiles.size + if (canAddProfile) 1 else 0
    val initialFocusIndex = remember(profiles, activeProfileId, canAddProfile) {
        profiles.indexOfFirst { it.id == activeProfileId }
            .takeIf { it >= 0 }
            ?: if (profiles.isNotEmpty()) 0 else if (canAddProfile) 0 else -1
    }
    val focusRequesters = remember(totalItems) {
        List(totalItems) { FocusRequester() }
    }

    LaunchedEffect(totalItems, initialFocusIndex, isManagementMode) {
        repeat(2) { withFrameNanos { } }
        if (focusRequesters.isNotEmpty() && initialFocusIndex in focusRequesters.indices) {
            runCatching { focusRequesters[initialFocusIndex].requestFocus() }
        }
    }

    if (profiles.isEmpty() && !canAddProfile) {
        Text(
            text = stringResource(R.string.profile_selection_empty),
            color = NuvioColors.TextSecondary,
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium
        )
    } else {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(ProfileSelectionSpacing.GridItemGap),
                verticalAlignment = Alignment.Top
            ) {
                profiles.forEachIndexed { index, profile ->
                    ProfileCard(
                        profile = profile,
                        avatarImageUrl = profile.avatarId?.let(avatarImageUrlsById::get),
                        focusRequester = focusRequesters[index],
                        onFocused = { onProfileFocused(profile.avatarColorHex) },
                        onClick = { onProfileSelected(profile) },
                        onLongPress = { onProfileLongPress(profile) }
                    )
                }
                if (canAddProfile) {
                    AddProfileCard(
                        focusRequester = focusRequesters[profiles.size],
                        onFocused = { onProfileFocused("#555555") },
                        onClick = onAddProfileClick
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfileCard(
    profile: UserProfile,
    avatarImageUrl: String?,
    focusRequester: FocusRequester,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    onLongPress: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    var longPressTriggered by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val focusProgress by animateFloatAsState(
        targetValue = if (isFocused) 1f else 0f,
        animationSpec = tween(durationMillis = 210, easing = ProfileCardFocusEasing),
        label = "profileFocusProgress"
    )
    val itemScale = 1f + (0.04f * focusProgress)
    val avatarSize = androidx.compose.ui.unit.lerp(96.dp, 102.dp, focusProgress)
    val outerAvatarSize = androidx.compose.ui.unit.lerp(114.dp, 122.dp, focusProgress)
    val ringWidth = androidx.compose.ui.unit.lerp(1.dp, 3.dp, focusProgress)
    val ringColor = lerp(
        NuvioColors.Border.copy(alpha = 0.75f),
        NuvioColors.Secondary,
        focusProgress
    )
    val nameColor = lerp(
        NuvioColors.TextSecondary,
        NuvioColors.TextPrimary,
        focusProgress
    )
    val nameWeight = if (isFocused) FontWeight.SemiBold else FontWeight.Medium

    Column(
        modifier = Modifier
            .width(ProfileSelectionSpacing.CardWidth)
            .graphicsLayer {
                scaleX = itemScale
                scaleY = itemScale
            }
            .focusRequester(focusRequester)
            .onFocusChanged {
                isFocused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                if (native.action == AndroidKeyEvent.ACTION_DOWN) {
                    if (native.keyCode == AndroidKeyEvent.KEYCODE_MENU) {
                        longPressTriggered = true
                        onLongPress()
                        return@onPreviewKeyEvent true
                    }
                    val isLongPress = native.isLongPress || native.repeatCount > 0
                    if (isLongPress && isProfileSelectKey(native.keyCode)) {
                        longPressTriggered = true
                        onLongPress()
                        return@onPreviewKeyEvent true
                    }
                }
                if (native.action == AndroidKeyEvent.ACTION_UP &&
                    longPressTriggered &&
                    isProfileSelectKey(native.keyCode)
                ) {
                    longPressTriggered = false
                    return@onPreviewKeyEvent true
                }
                false
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(
                horizontal = ProfileSelectionSpacing.CardPaddingHorizontal,
                vertical = ProfileSelectionSpacing.CardPaddingVertical
            ),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier.size(ProfileSelectionSpacing.AvatarContainer),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(outerAvatarSize)
                    .clip(CircleShape)
                    .border(
                        width = ringWidth,
                        color = ringColor,
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                ProfileAvatarCircle(
                    name = profile.name,
                    colorHex = profile.avatarColorHex,
                    size = avatarSize,
                    avatarImageUrl = avatarImageUrl
                )
            }

            if (profile.isPrimary) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = 2.dp, y = 1.dp)
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFFFB300), CircleShape)
                        .border(
                            width = 2.dp,
                            color = NuvioColors.Background,
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "\u2605",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(ProfileSelectionSpacing.AvatarToName))

        Text(
            text = profile.name,
            color = nameColor,
            fontSize = 17.sp,
            fontWeight = nameWeight,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(modifier = Modifier.height(ProfileSelectionSpacing.NameToMeta))

        Box(
            modifier = Modifier.height(ProfileSelectionSpacing.MetaSlotHeight),
            contentAlignment = Alignment.TopCenter
        ) {
            if (profile.isPrimary) {
                Text(
                    text = stringResource(R.string.profile_selection_primary_badge),
                    color = Color(0xFFFFB300),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.8.sp
                )
            }
        }
    }
}

private fun parseProfileColor(colorHex: String): Color {
    return runCatching { Color(android.graphics.Color.parseColor(colorHex)) }
        .getOrDefault(Color(0xFF1E88E5))
}

@Composable
private fun AddProfileCard(
    focusRequester: FocusRequester,
    onFocused: () -> Unit,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val focusProgress by animateFloatAsState(
        targetValue = if (isFocused) 1f else 0f,
        animationSpec = tween(durationMillis = 210, easing = ProfileCardFocusEasing),
        label = "addFocusProgress"
    )
    val itemScale = 1f + (0.04f * focusProgress)
    val outerAvatarSize = androidx.compose.ui.unit.lerp(114.dp, 122.dp, focusProgress)
    val ringWidth = androidx.compose.ui.unit.lerp(1.dp, 3.dp, focusProgress)
    val ringColor = lerp(
        NuvioColors.Border.copy(alpha = 0.5f),
        NuvioColors.Secondary,
        focusProgress
    )
    val nameColor = lerp(
        NuvioColors.TextTertiary,
        NuvioColors.TextPrimary,
        focusProgress
    )
    val plusColor = lerp(
        NuvioColors.TextTertiary,
        Color.White,
        focusProgress
    )
    val addBackgroundColor = lerp(
        Color.White.copy(alpha = 0.06f),
        Color.White.copy(alpha = 0.12f),
        focusProgress
    )

    Column(
        modifier = Modifier
            .width(ProfileSelectionSpacing.CardWidth)
            .graphicsLayer {
                scaleX = itemScale
                scaleY = itemScale
            }
            .focusRequester(focusRequester)
            .onFocusChanged {
                isFocused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(
                horizontal = ProfileSelectionSpacing.CardPaddingHorizontal,
                vertical = ProfileSelectionSpacing.CardPaddingVertical
            ),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier.size(ProfileSelectionSpacing.AvatarContainer),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(outerAvatarSize)
                    .clip(CircleShape)
                    .border(
                        width = ringWidth,
                        color = ringColor,
                        shape = CircleShape
                    )
                    .background(addBackgroundColor, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier.size(34.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .width(26.dp)
                            .height(3.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(plusColor)
                    )
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .height(26.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(plusColor)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(ProfileSelectionSpacing.AvatarToName))

        Text(
            text = stringResource(R.string.profile_add_new),
            color = nameColor,
            fontSize = 17.sp,
            fontWeight = if (isFocused) FontWeight.SemiBold else FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 1
        )

        Spacer(modifier = Modifier.height(ProfileSelectionSpacing.NameToMeta))
        Box(modifier = Modifier.height(ProfileSelectionSpacing.MetaSlotHeight))
    }
}

@Composable
private fun CreateProfileOverlay(
    avatarCatalog: List<AvatarCatalogItem>,
    isCreating: Boolean,
    onDismiss: () -> Unit,
    onCreateProfile: (name: String, colorHex: String, avatarId: String?) -> Unit
) {
    BackHandler(onBack = onDismiss)

    var profileName by remember { mutableStateOf("") }
    var selectedColorHex by remember { mutableStateOf("#1E88E5") }
    var selectedAvatarId by remember { mutableStateOf<String?>(null) }
    var focusedAvatarName by remember { mutableStateOf<String?>(null) }
    val selectedAvatar = remember(avatarCatalog, selectedAvatarId) {
        avatarCatalog.find { it.id == selectedAvatarId }
    }
    val nameFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        repeat(2) { withFrameNanos { } }
        runCatching { nameFocusRequester.requestFocus() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.85f))
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                if (native.action == AndroidKeyEvent.ACTION_UP &&
                    native.keyCode == AndroidKeyEvent.KEYCODE_BACK
                ) {
                    onDismiss()
                    true
                } else {
                    false
                }
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .widthIn(max = ProfileSelectionSpacing.EditorPanelMaxWidth)
                .clip(RoundedCornerShape(20.dp))
                .background(NuvioColors.BackgroundElevated)
                .border(1.dp, NuvioColors.Border, RoundedCornerShape(20.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {} // prevent dismiss when clicking the panel
                )
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header row: title left, create button right
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.profile_create_title),
                    color = Color.White,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold
                )
                OverlayButton(
                    text = if (isCreating) stringResource(R.string.profile_creating)
                           else stringResource(R.string.profile_create_btn),
                    isPrimary = true,
                    enabled = profileName.isNotBlank() && !isCreating,
                    onClick = {
                        onCreateProfile(profileName, selectedColorHex, selectedAvatarId)
                    }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 360.dp),
                verticalAlignment = Alignment.Top
            ) {
                Column(
                    modifier = Modifier
                        .width(ProfileSelectionSpacing.EditorPreviewWidth)
                        .padding(start = 24.dp, top = 24.dp + ProfileSelectionSpacing.EditorPreviewTopOffset, end = 24.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    ProfileAvatarCircle(
                        name = profileName.ifEmpty { "?" },
                        colorHex = selectedColorHex,
                        size = ProfileSelectionSpacing.EditorPreviewAvatarSize,
                        avatarImageUrl = selectedAvatar?.imageUrl
                    )

                    Text(
                        text = profileName.ifBlank { stringResource(R.string.profile_name_placeholder) },
                        color = if (profileName.isBlank()) NuvioColors.TextSecondary else NuvioColors.TextPrimary,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    ProfileNameField(
                        value = profileName,
                        onValueChange = { if (it.length <= 20) profileName = it },
                        focusRequester = nameFocusRequester
                    )

                    OverlayButton(
                        text = stringResource(R.string.profile_cancel),
                        isPrimary = true,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onDismiss
                    )
                }

                Spacer(modifier = Modifier.width(ProfileSelectionSpacing.EditorDividerSpacing))
                EditorSectionDivider()
                Spacer(modifier = Modifier.width(ProfileSelectionSpacing.EditorDividerSpacing))

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.profile_choose_avatar),
                        modifier = Modifier.fillMaxWidth(),
                        color = NuvioColors.TextSecondary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center
                    )

                    if (avatarCatalog.isNotEmpty()) {
                        AvatarPickerGrid(
                            avatars = avatarCatalog,
                            selectedAvatarId = selectedAvatarId,
                            onAvatarSelected = { avatar ->
                                if (selectedAvatarId == avatar.id) {
                                    selectedAvatarId = null
                                    selectedColorHex = "#1E88E5"
                                } else {
                                    selectedAvatarId = avatar.id
                                    avatar.bgColor?.let { selectedColorHex = it }
                                }
                            },
                            onAvatarFocused = { avatar ->
                                focusedAvatarName = avatar?.displayName
                            },
                            modifier = Modifier.heightIn(max = 320.dp)
                        )

                        Text(
                            text = focusedAvatarName ?: stringResource(R.string.profile_avatar_focus_hint),
                            modifier = Modifier.fillMaxWidth(),
                            color = if (focusedAvatarName != null) NuvioColors.TextPrimary else NuvioColors.TextTertiary,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp)
                                .clip(RoundedCornerShape(18.dp))
                                .background(NuvioColors.BackgroundCard)
                                .border(1.dp, NuvioColors.Border, RoundedCornerShape(18.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.profile_choose_avatar),
                                color = NuvioColors.TextTertiary,
                                fontSize = 15.sp
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

private fun isProfileSelectKey(keyCode: Int): Boolean {
    return keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
        keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
        keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER
}

@Composable
private fun EditProfileOverlay(
    profile: UserProfile,
    avatarCatalog: List<AvatarCatalogItem>,
    isSaving: Boolean,
    avatarUrlResolver: (String?) -> String?,
    onDismiss: () -> Unit,
    onSaveProfile: (UserProfile) -> Unit
) {
    BackHandler(onBack = onDismiss)

    var profileName by remember { mutableStateOf(profile.name) }
    var selectedColorHex by remember { mutableStateOf(profile.avatarColorHex) }
    var selectedAvatarId by remember(profile.id, profile.avatarId) {
        mutableStateOf(profile.avatarId)
    }
    var focusedAvatarName by remember { mutableStateOf<String?>(null) }
    val selectedAvatar = remember(avatarCatalog, selectedAvatarId) {
        avatarCatalog.find { it.id == selectedAvatarId }
    }
    val previewAvatarImageUrl = when {
        selectedAvatar != null -> selectedAvatar.imageUrl
        selectedAvatarId == profile.avatarId -> avatarUrlResolver(profile.avatarId)
        else -> null
    }
    val nameFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        repeat(2) { withFrameNanos { } }
        runCatching { nameFocusRequester.requestFocus() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.85f))
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                if (native.action == AndroidKeyEvent.ACTION_UP &&
                    native.keyCode == AndroidKeyEvent.KEYCODE_BACK
                ) {
                    onDismiss()
                    true
                } else {
                    false
                }
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .widthIn(max = ProfileSelectionSpacing.EditorPanelMaxWidth)
                .clip(RoundedCornerShape(20.dp))
                .background(NuvioColors.BackgroundElevated)
                .border(1.dp, NuvioColors.Border, RoundedCornerShape(20.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                )
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header row: title left, save button right
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "Edit Profile",
                        color = NuvioColors.TextSecondary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = profile.name,
                        color = Color.White,
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Black
                    )
                }
                OverlayButton(
                    text = if (isSaving) stringResource(R.string.profile_saving)
                           else stringResource(R.string.profile_save),
                    isPrimary = true,
                    enabled = profileName.isNotBlank() && !isSaving,
                    onClick = {
                        onSaveProfile(
                            profile.copy(
                                name = profileName,
                                avatarColorHex = selectedColorHex,
                                avatarId = selectedAvatarId
                            )
                        )
                    }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 360.dp),
                verticalAlignment = Alignment.Top
            ) {
                Column(
                    modifier = Modifier
                        .width(ProfileSelectionSpacing.EditorPreviewWidth)
                        .padding(start = 24.dp, top = 24.dp + ProfileSelectionSpacing.EditorPreviewTopOffset, end = 24.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    ProfileAvatarCircle(
                        name = profileName.ifEmpty { "?" },
                        colorHex = selectedColorHex,
                        size = ProfileSelectionSpacing.EditorPreviewAvatarSize,
                        avatarImageUrl = previewAvatarImageUrl
                    )

                    Text(
                        text = profileName.ifBlank { stringResource(R.string.profile_name_placeholder) },
                        color = if (profileName.isBlank()) NuvioColors.TextSecondary else NuvioColors.TextPrimary,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    ProfileNameField(
                        value = profileName,
                        onValueChange = { if (it.length <= 20) profileName = it },
                        focusRequester = nameFocusRequester
                    )

                    OverlayButton(
                        text = stringResource(R.string.profile_cancel),
                        isPrimary = true,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onDismiss
                    )
                }

                Spacer(modifier = Modifier.width(ProfileSelectionSpacing.EditorDividerSpacing))
                EditorSectionDivider()
                Spacer(modifier = Modifier.width(ProfileSelectionSpacing.EditorDividerSpacing))

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.profile_choose_avatar),
                        modifier = Modifier.fillMaxWidth(),
                        color = NuvioColors.TextSecondary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center
                    )

                    if (avatarCatalog.isNotEmpty()) {
                        AvatarPickerGrid(
                            avatars = avatarCatalog,
                            selectedAvatarId = selectedAvatarId,
                            onAvatarSelected = { avatar ->
                                if (selectedAvatarId == avatar.id) {
                                    selectedAvatarId = null
                                    selectedColorHex = profile.avatarColorHex
                                } else {
                                    selectedAvatarId = avatar.id
                                    avatar.bgColor?.let { selectedColorHex = it }
                                }
                            },
                            onAvatarFocused = { avatar ->
                                focusedAvatarName = avatar?.displayName
                            },
                            modifier = Modifier.heightIn(max = 320.dp)
                        )

                        Text(
                            text = focusedAvatarName ?: stringResource(R.string.profile_avatar_focus_hint),
                            modifier = Modifier.fillMaxWidth(),
                            color = if (focusedAvatarName != null) NuvioColors.TextPrimary else NuvioColors.TextTertiary,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp)
                                .clip(RoundedCornerShape(18.dp))
                                .background(NuvioColors.BackgroundCard)
                                .border(1.dp, NuvioColors.Border, RoundedCornerShape(18.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.profile_choose_avatar),
                                color = NuvioColors.TextTertiary,
                                fontSize = 15.sp
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun EditorSectionDivider() {
    Box(
        modifier = Modifier
            .padding(top = ProfileSelectionSpacing.EditorPreviewTopOffset)
            .width(1.dp)
            .height(ProfileSelectionSpacing.EditorDividerHeight)
            .background(NuvioColors.Border.copy(alpha = 0.9f))
    )
}

@Composable
private fun ProfileNameField(
    value: String,
    onValueChange: (String) -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val borderColor by animateColorAsState(
        targetValue = if (isFocused) NuvioColors.FocusRing else NuvioColors.Border,
        animationSpec = tween(120),
        label = "profileNameBorder"
    )
    val backgroundColor by animateColorAsState(
        targetValue = if (isFocused) NuvioColors.FocusBackground else Color.White.copy(alpha = 0.05f),
        animationSpec = tween(120),
        label = "profileNameBackground"
    )
    val borderWidth by animateDpAsState(
        targetValue = if (isFocused) 2.dp else 1.dp,
        animationSpec = tween(120),
        label = "profileNameBorderWidth"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ProfileSelectionSpacing.EditorFieldRadius))
            .background(backgroundColor)
            .border(
                width = borderWidth,
                color = borderColor,
                shape = RoundedCornerShape(ProfileSelectionSpacing.EditorFieldRadius)
            )
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        if (value.isEmpty()) {
            Text(
                text = stringResource(R.string.profile_name_placeholder),
                color = NuvioColors.TextTertiary,
                fontSize = 16.sp
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .onFocusChanged { isFocused = it.isFocused },
            textStyle = TextStyle(
                color = Color.White,
                fontSize = 16.sp
            ),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = {
                    focusManager.moveFocus(FocusDirection.Down)
                }
            ),
            cursorBrush = SolidColor(NuvioColors.FocusRing)
        )
    }
}

@Composable
private fun OverlayButton(
    text: String,
    isPrimary: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }

    val bgColor by animateColorAsState(
        targetValue = when {
            !enabled -> Color.White.copy(alpha = 0.04f)
            isPrimary && isFocused -> NuvioColors.FocusBackground
            isPrimary -> NuvioColors.Secondary
            isFocused -> NuvioColors.FocusBackground
            else -> Color.White.copy(alpha = 0.06f)
        },
        animationSpec = tween(120),
        label = "btnBg"
    )
    val borderColor by animateColorAsState(
        targetValue = when {
            !enabled -> NuvioColors.Border
            isFocused -> NuvioColors.FocusRing
            isPrimary -> NuvioColors.Secondary
            else -> NuvioColors.Border
        },
        animationSpec = tween(120),
        label = "btnBorder"
    )
    val borderWidth by animateDpAsState(
        targetValue = if (isFocused) 2.dp else 1.dp,
        animationSpec = tween(120),
        label = "btnBorderWidth"
    )
    val textColor = when {
        !enabled -> NuvioColors.TextDisabled
        else -> if (bgColor.luminance() > 0.55f) Color.Black else Color.White
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .border(borderWidth, borderColor, RoundedCornerShape(12.dp))
            .onFocusChanged { isFocused = it.isFocused }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick
            )
            .padding(horizontal = 28.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = textColor,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}
