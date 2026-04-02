package com.nuvio.tv.ui.screens.profile

import android.graphics.Rect
import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.focusable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
import kotlinx.coroutines.delay

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
    val PinKickerToHeading = 14.dp
    val PinHeadingToBoxes = 42.dp
    val PinBoxesToSupport = 26.dp
    val PinBoxSize = 118.dp
    val PinBoxGap = 14.dp
    val PinSupportMaxWidth = 720.dp
}

private val ProfileCardFocusEasing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
private const val ProfilePinLength = 4

enum class ProfileSelectionMode {
    Selection,
    Management
}

private sealed interface ProfilePinOverlayState {
    val profile: UserProfile

    data class Unlock(override val profile: UserProfile) : ProfilePinOverlayState
    data class Set(override val profile: UserProfile, val currentPin: String? = null) : ProfilePinOverlayState
    data class VerifyCurrentForChange(override val profile: UserProfile) : ProfilePinOverlayState
    data class VerifyCurrentForRemove(override val profile: UserProfile) : ProfilePinOverlayState
}

private enum class ProfilePinEntryStage {
    Create,
    Confirm
}

private data class KeyboardVisibilityState(
    val isVisible: Boolean,
    val bottomPx: Int
)

@Composable
fun ProfileSelectionScreen(
    onProfileSelected: () -> Unit,
    screenMode: ProfileSelectionMode = ProfileSelectionMode.Selection,
    onBackPress: (() -> Unit)? = null,
    viewModel: ProfileSelectionViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val profiles by viewModel.profiles.collectAsState()
    val activeProfileId by viewModel.activeProfileId.collectAsState()
    val avatarCatalog by viewModel.avatarCatalog.collectAsState()
    val isCreating by viewModel.isCreating.collectAsState()
    val isSaving by viewModel.isSaving.collectAsState()
    val profilePinEnabled by viewModel.profilePinEnabled.collectAsState()
    val isPinOperationInProgress by viewModel.isPinOperationInProgress.collectAsState()
    val avatarImageUrlsById = remember(avatarCatalog) {
        avatarCatalog.associate { it.id to it.imageUrl }
    }
    var focusedAvatarColor by remember { mutableStateOf(Color(0xFF1E88E5)) }
    var showCreateProfile by remember { mutableStateOf(false) }
    var longPressedProfile by remember { mutableStateOf<UserProfile?>(null) }
    var suppressOptionsDialogFirstKeyUp by remember { mutableStateOf(true) }
    var profileToDelete by remember { mutableStateOf<UserProfile?>(null) }
    var profileToEdit by remember { mutableStateOf<UserProfile?>(null) }
    var pinOverlayState by remember { mutableStateOf<ProfilePinOverlayState?>(null) }
    var pinOverlayError by remember { mutableStateOf<String?>(null) }
    var pinActionMessage by remember { mutableStateOf<String?>(null) }
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
        val overlayProfileColor = pinOverlayState?.profile?.avatarColorHex?.let(::parseProfileColor)
        ProfileSelectionBackground(focusedAvatarColor = overlayProfileColor ?: focusedAvatarColor)

        AnimatedContent(
            targetState = pinOverlayState,
            transitionSpec = {
                val entering = fadeIn(tween(240)) + slideInHorizontally(
                    animationSpec = tween(320, easing = ProfileCardFocusEasing),
                    initialOffsetX = { fullWidth ->
                        if (targetState == null) -fullWidth / 10 else fullWidth / 12
                    }
                )
                val exiting = fadeOut(tween(180)) + slideOutHorizontally(
                    animationSpec = tween(240),
                    targetOffsetX = { fullWidth ->
                        if (targetState == null) fullWidth / 12 else -fullWidth / 12
                    }
                )
                entering togetherWith exiting
            },
            modifier = Modifier.fillMaxSize(),
            label = "profilePinTransition"
        ) { activePinOverlay ->
            if (activePinOverlay == null) {
                ProfileSelectionMainContent(
                    screenTitle = screenTitle,
                    screenSubtitle = screenSubtitle,
                    screenHint = screenHint,
                    isManagementMode = isManagementMode,
                    profiles = profiles,
                    activeProfileId = activeProfileId,
                    canAddProfile = viewModel.canAddProfile,
                    profilePinEnabled = profilePinEnabled,
                    avatarImageUrlsById = avatarImageUrlsById,
                    onProfileFocused = onProfileFocusedColorChange,
                    onProfileSelected = { profile ->
                        if (isManagementMode) {
                            suppressOptionsDialogFirstKeyUp = false
                            longPressedProfile = profile
                        } else {
                            if (profilePinEnabled[profile.id] == true) {
                                pinOverlayError = null
                                pinOverlayState = ProfilePinOverlayState.Unlock(profile)
                            } else {
                                viewModel.selectProfile(profile.id, onComplete = onProfileSelected)
                            }
                        }
                    },
                    onProfileLongPress = { profile ->
                        suppressOptionsDialogFirstKeyUp = true
                        longPressedProfile = profile
                    },
                    onAddProfileClick = { showCreateProfile = true }
                )
            } else {
                ProfilePinOverlay(
                    state = activePinOverlay,
                    isWorking = isPinOperationInProgress,
                    errorMessage = pinOverlayError,
                    onClearError = { pinOverlayError = null },
                    onDismiss = {
                        pinOverlayState = null
                        pinOverlayError = null
                    },
                    onSubmit = { pin ->
                        when (activePinOverlay) {
                            is ProfilePinOverlayState.Set -> {
                                viewModel.setProfilePin(
                                    activePinOverlay.profile.id,
                                    pin,
                                    activePinOverlay.currentPin
                                ) { success ->
                                    if (success) {
                                        pinOverlayState = null
                                        pinOverlayError = null
                                        pinActionMessage = "PIN saved for ${activePinOverlay.profile.name}."
                                    } else {
                                        pinOverlayError = context.getString(R.string.profile_pin_save_error)
                                    }
                                }
                            }

                            is ProfilePinOverlayState.Unlock -> {
                                viewModel.verifyProfilePin(activePinOverlay.profile.id, pin) { result ->
                                    result.onSuccess { verify ->
                                        if (verify.unlocked) {
                                            pinOverlayError = null
                                            pinOverlayState = null
                                            viewModel.selectProfile(
                                                activePinOverlay.profile.id,
                                                onComplete = onProfileSelected
                                            )
                                        } else {
                                            pinOverlayError = if (verify.retryAfterSeconds > 0) {
                                                context.getString(R.string.profile_pin_locked, verify.retryAfterSeconds)
                                            } else {
                                                context.getString(R.string.profile_pin_invalid)
                                            }
                                        }
                                    }.onFailure {
                                        pinOverlayError = context.getString(R.string.profile_pin_verify_error)
                                    }
                                }
                            }

                            is ProfilePinOverlayState.VerifyCurrentForChange -> {
                                viewModel.verifyProfilePin(activePinOverlay.profile.id, pin) { result ->
                                    result.onSuccess { verify ->
                                        if (verify.unlocked) {
                                            pinOverlayError = null
                                            pinOverlayState = ProfilePinOverlayState.Set(
                                                profile = activePinOverlay.profile,
                                                currentPin = pin
                                            )
                                        } else {
                                            pinOverlayError = if (verify.retryAfterSeconds > 0) {
                                                context.getString(R.string.profile_pin_locked, verify.retryAfterSeconds)
                                            } else {
                                                context.getString(R.string.profile_pin_incorrect)
                                            }
                                        }
                                    }.onFailure {
                                        pinOverlayError = context.getString(R.string.profile_pin_verify_error)
                                    }
                                }
                            }

                            is ProfilePinOverlayState.VerifyCurrentForRemove -> {
                                viewModel.clearProfilePin(
                                    profileId = activePinOverlay.profile.id,
                                    currentPin = pin
                                ) { success ->
                                    if (success) {
                                        pinOverlayError = null
                                        pinOverlayState = null
                                        pinActionMessage = "PIN lock removed for ${activePinOverlay.profile.name}."
                                    } else {
                                        pinOverlayError = context.getString(R.string.profile_pin_incorrect)
                                    }
                                }
                            }
                        }
                    }
                )
            }
        }

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

        LaunchedEffect(pinActionMessage) {
            if (pinActionMessage != null) {
                delay(2600)
                pinActionMessage = null
            }
        }

        AnimatedVisibility(
            visible = pinActionMessage != null,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(180)),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            pinActionMessage?.let { message ->
                Box(
                    modifier = Modifier
                        .padding(bottom = 34.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color.Black.copy(alpha = 0.78f))
                        .border(
                            width = 1.dp,
                            color = Color.White.copy(alpha = 0.14f),
                            shape = RoundedCornerShape(999.dp)
                        )
                        .padding(horizontal = 24.dp, vertical = 14.dp)
                ) {
                    Text(
                        text = message,
                        color = NuvioColors.TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
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

                Button(
                    onClick = {
                        longPressedProfile = null
                        pinOverlayError = null
                        pinOverlayState = if (profilePinEnabled[profile.id] == true) {
                            ProfilePinOverlayState.VerifyCurrentForChange(profile)
                        } else {
                            ProfilePinOverlayState.Set(profile)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.colors(
                        containerColor = NuvioColors.BackgroundCard,
                        contentColor = NuvioColors.TextPrimary
                    )
                ) {
                    Text(
                        if (profilePinEnabled[profile.id] == true) {
                            stringResource(R.string.profile_pin_change)
                        } else {
                            stringResource(R.string.profile_pin_set)
                        }
                    )
                }

                if (profilePinEnabled[profile.id] == true) {
                    Button(
                        onClick = {
                            longPressedProfile = null
                            pinOverlayError = null
                            pinOverlayState = ProfilePinOverlayState.VerifyCurrentForRemove(profile)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.colors(
                            containerColor = NuvioColors.BackgroundCard,
                            contentColor = NuvioColors.TextPrimary
                        )
                    ) {
                        Text(stringResource(R.string.profile_pin_remove))
                    }
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
    profilePinEnabled: Map<Int, Boolean>,
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
            contentDescription = stringResource(R.string.cd_nuvio_logo),
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
            profilePinEnabled = profilePinEnabled,
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
    profilePinEnabled: Map<Int, Boolean>,
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

private fun keyCodeToDigit(keyCode: Int): Char? {
    return when (keyCode) {
        AndroidKeyEvent.KEYCODE_0,
        AndroidKeyEvent.KEYCODE_NUMPAD_0 -> '0'
        AndroidKeyEvent.KEYCODE_1,
        AndroidKeyEvent.KEYCODE_NUMPAD_1 -> '1'
        AndroidKeyEvent.KEYCODE_2,
        AndroidKeyEvent.KEYCODE_NUMPAD_2 -> '2'
        AndroidKeyEvent.KEYCODE_3,
        AndroidKeyEvent.KEYCODE_NUMPAD_3 -> '3'
        AndroidKeyEvent.KEYCODE_4,
        AndroidKeyEvent.KEYCODE_NUMPAD_4 -> '4'
        AndroidKeyEvent.KEYCODE_5,
        AndroidKeyEvent.KEYCODE_NUMPAD_5 -> '5'
        AndroidKeyEvent.KEYCODE_6,
        AndroidKeyEvent.KEYCODE_NUMPAD_6 -> '6'
        AndroidKeyEvent.KEYCODE_7,
        AndroidKeyEvent.KEYCODE_NUMPAD_7 -> '7'
        AndroidKeyEvent.KEYCODE_8,
        AndroidKeyEvent.KEYCODE_NUMPAD_8 -> '8'
        AndroidKeyEvent.KEYCODE_9,
        AndroidKeyEvent.KEYCODE_NUMPAD_9 -> '9'
        else -> null
    }
}

@Composable
private fun rememberKeyboardVisibilityState(): KeyboardVisibilityState {
    val view = LocalView.current
    var state by remember(view) { mutableStateOf(KeyboardVisibilityState(isVisible = false, bottomPx = 0)) }

    DisposableEffect(view) {
        val rect = Rect()
        val listener = android.view.ViewTreeObserver.OnGlobalLayoutListener {
            view.getWindowVisibleDisplayFrame(rect)
            val rootHeight = view.rootView.height
            val obscuredHeight = (rootHeight - rect.height()).coerceAtLeast(0)
            val rootInsets = ViewCompat.getRootWindowInsets(view)
            val imeInsets = rootInsets?.getInsets(WindowInsetsCompat.Type.ime())
            val imeVisible = rootInsets?.isVisible(WindowInsetsCompat.Type.ime()) == true
            val threshold = (rootHeight * 0.12f).toInt()
            val keyboardVisible = imeVisible || obscuredHeight > threshold
            val bottomPx = when {
                imeVisible && imeInsets != null -> imeInsets.bottom
                keyboardVisible -> obscuredHeight
                else -> 0
            }
            state = KeyboardVisibilityState(
                isVisible = keyboardVisible,
                bottomPx = bottomPx
            )
        }

        view.viewTreeObserver.addOnGlobalLayoutListener(listener)
        listener.onGlobalLayout()
        onDispose {
            view.viewTreeObserver.removeOnGlobalLayoutListener(listener)
        }
    }

    return state
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
                        text = stringResource(R.string.profile_edit_header),
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
private fun ProfilePinOverlay(
    state: ProfilePinOverlayState,
    isWorking: Boolean,
    errorMessage: String?,
    onClearError: () -> Unit,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit
) {
    val density = LocalDensity.current
    val keyboardState = rememberKeyboardVisibilityState()
    val focusManager = LocalFocusManager.current
    val focusRequester = remember(state) { FocusRequester() }
    val isSingleEntryMode = state !is ProfilePinOverlayState.Set
    var pin by remember(state) { mutableStateOf("") }
    var draftPin by remember(state) { mutableStateOf<String?>(null) }
    var entryStage by remember(state) { mutableStateOf(ProfilePinEntryStage.Create) }
    var internalErrorMessage by remember(state) { mutableStateOf<String?>(null) }
    var isInputFocused by remember(state) { mutableStateOf(false) }
    var keyboardWasVisible by remember(state) { mutableStateOf(false) }
    val shakeOffset = remember(state) { androidx.compose.animation.core.Animatable(0f) }
    val cursorAlpha by rememberInfiniteTransition(label = "pinCursor").animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(520),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pinCursorAlpha"
    )
    val resolvedErrorMessage = if (isSingleEntryMode) errorMessage else internalErrorMessage ?: errorMessage
    val isErrorState = !resolvedErrorMessage.isNullOrEmpty()
    val mismatchMessage = stringResource(R.string.profile_pin_overlay_mismatch)
    val shouldUseCompactLayout = keyboardState.isVisible
    val headerFontSize by animateFloatAsState(
        targetValue = if (shouldUseCompactLayout) 28f else 42f,
        animationSpec = tween(180),
        label = "pinHeaderFontSize"
    )
    val headerLineHeight by animateFloatAsState(
        targetValue = if (shouldUseCompactLayout) 34f else 48f,
        animationSpec = tween(180),
        label = "pinHeaderLineHeight"
    )
    val supportFontSize by animateFloatAsState(
        targetValue = if (shouldUseCompactLayout) 16f else 18f,
        animationSpec = tween(180),
        label = "pinSupportFontSize"
    )
    val topPadding by animateDpAsState(
        targetValue = if (shouldUseCompactLayout) 40.dp else 0.dp,
        animationSpec = tween(180),
        label = "pinOverlayTopPadding"
    )
    val headingGap by animateDpAsState(
        targetValue = if (shouldUseCompactLayout) 10.dp else ProfileSelectionSpacing.PinKickerToHeading,
        animationSpec = tween(180),
        label = "pinHeadingGap"
    )
    val boxesGap by animateDpAsState(
        targetValue = if (shouldUseCompactLayout) 24.dp else ProfileSelectionSpacing.PinHeadingToBoxes,
        animationSpec = tween(180),
        label = "pinBoxesGap"
    )
    val supportGap by animateDpAsState(
        targetValue = if (shouldUseCompactLayout) 18.dp else ProfileSelectionSpacing.PinBoxesToSupport,
        animationSpec = tween(180),
        label = "pinSupportGap"
    )
    val backHintGap by animateDpAsState(
        targetValue = if (shouldUseCompactLayout) 10.dp else 14.dp,
        animationSpec = tween(180),
        label = "pinBackHintGap"
    )
    val contentBottomPadding = if (keyboardState.isVisible) {
        with(density) { keyboardState.bottomPx.toDp() } + 28.dp
    } else {
        28.dp
    }

    BackHandler(enabled = isInputFocused && keyboardState.isVisible) {
        focusManager.clearFocus(force = true)
    }

    BackHandler(onBack = onDismiss)

    suspend fun playErrorAnimation() {
        shakeOffset.snapTo(0f)
        listOf(-22f, 18f, -14f, 10f, -6f, 0f).forEach { offset ->
            shakeOffset.animateTo(offset, animationSpec = tween(42))
        }
    }

    LaunchedEffect(state) {
        repeat(2) { withFrameNanos { } }
        runCatching { focusRequester.requestFocus() }
    }

    LaunchedEffect(keyboardState.isVisible, isInputFocused) {
        if (keyboardState.isVisible) {
            keyboardWasVisible = true
        } else if (keyboardWasVisible && isInputFocused) {
            keyboardWasVisible = false
            focusManager.clearFocus(force = true)
        }
    }

    LaunchedEffect(errorMessage) {
        if (!errorMessage.isNullOrEmpty()) {
            pin = ""
            playErrorAnimation()
        }
    }

    LaunchedEffect(pin, entryStage, isWorking) {
        if (pin.length != ProfilePinLength || isWorking) return@LaunchedEffect
        if (isSingleEntryMode) {
            onSubmit(pin)
        } else {
            if (entryStage == ProfilePinEntryStage.Create) {
                draftPin = pin
                pin = ""
                internalErrorMessage = null
                entryStage = ProfilePinEntryStage.Confirm
            } else if (draftPin == pin) {
                onSubmit(pin)
            } else {
                pin = ""
                draftPin = null
                entryStage = ProfilePinEntryStage.Create
                internalErrorMessage = mismatchMessage
                playErrorAnimation()
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { runCatching { focusRequester.requestFocus() } }
            )
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                if (native.action != AndroidKeyEvent.ACTION_DOWN) {
                    return@onPreviewKeyEvent false
                }
                when (native.keyCode) {
                    AndroidKeyEvent.KEYCODE_BACK,
                    AndroidKeyEvent.KEYCODE_ESCAPE -> {
                        if (isInputFocused) {
                            focusManager.clearFocus(force = true)
                        } else {
                            onDismiss()
                        }
                        true
                    }

                    AndroidKeyEvent.KEYCODE_DEL,
                    AndroidKeyEvent.KEYCODE_CLEAR -> {
                        if (!isWorking && pin.isNotEmpty()) {
                            pin = pin.dropLast(1)
                            if (!errorMessage.isNullOrEmpty()) onClearError()
                            if (!isSingleEntryMode) internalErrorMessage = null
                        }
                        true
                    }

                    else -> {
                        val digit = keyCodeToDigit(native.keyCode)
                        if (digit != null && !isWorking && pin.length < ProfilePinLength) {
                            pin += digit
                            if (!errorMessage.isNullOrEmpty()) onClearError()
                            if (!isSingleEntryMode) internalErrorMessage = null
                            true
                        } else {
                            false
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        val headingText = when {
            state is ProfilePinOverlayState.Unlock -> stringResource(R.string.profile_pin_overlay_unlock_heading, state.profile.name)
            state is ProfilePinOverlayState.VerifyCurrentForChange -> stringResource(R.string.profile_pin_overlay_change_verify_heading, state.profile.name)
            state is ProfilePinOverlayState.VerifyCurrentForRemove -> stringResource(R.string.profile_pin_overlay_remove_verify_heading, state.profile.name)
            entryStage == ProfilePinEntryStage.Confirm -> stringResource(R.string.profile_pin_overlay_confirm_heading)
            else -> stringResource(R.string.profile_pin_overlay_set_heading, state.profile.name)
        }
        val supportText = when {
            !resolvedErrorMessage.isNullOrEmpty() -> resolvedErrorMessage
            isWorking && isSingleEntryMode -> stringResource(R.string.profile_pin_verifying)
            isWorking -> stringResource(R.string.action_saving)
            state is ProfilePinOverlayState.Unlock -> stringResource(R.string.profile_pin_overlay_unlock_support)
            state is ProfilePinOverlayState.VerifyCurrentForChange -> stringResource(R.string.profile_pin_overlay_change_verify_support)
            state is ProfilePinOverlayState.VerifyCurrentForRemove -> stringResource(R.string.profile_pin_overlay_remove_verify_support)
            entryStage == ProfilePinEntryStage.Confirm -> stringResource(R.string.profile_pin_overlay_confirm_support)
            else -> stringResource(R.string.profile_pin_overlay_set_support)
        }
        val supportTextColor = if (isErrorState) {
            Color(0xFFFF8E8E)
        } else {
            NuvioColors.TextSecondary
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = ProfileSelectionSpacing.ScreenPaddingHorizontal),
            verticalArrangement = if (shouldUseCompactLayout) Arrangement.Top else Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(topPadding))

            Spacer(modifier = Modifier.height(headingGap))

            Text(
                text = headingText,
                color = NuvioColors.TextPrimary,
                fontSize = headerFontSize.sp,
                lineHeight = headerLineHeight.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(boxesGap))

            ProfilePinBoxes(
                value = pin,
                isWorking = isWorking,
                isErrorState = isErrorState,
                isInputFocused = isInputFocused,
                cursorAlpha = cursorAlpha,
                compactMode = shouldUseCompactLayout,
                modifier = Modifier.graphicsLayer {
                    translationX = shakeOffset.value
                }
            )

            Spacer(modifier = Modifier.height(supportGap))

            Text(
                text = supportText,
                modifier = Modifier.widthIn(max = ProfileSelectionSpacing.PinSupportMaxWidth),
                color = supportTextColor,
                fontSize = supportFontSize.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                lineHeight = 24.sp
            )

            if (state is ProfilePinOverlayState.Unlock ||
                state is ProfilePinOverlayState.VerifyCurrentForChange ||
                state is ProfilePinOverlayState.VerifyCurrentForRemove
            ) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.profile_pin_overlay_forgot_hint),
                    modifier = Modifier.widthIn(max = ProfileSelectionSpacing.PinSupportMaxWidth),
                    color = NuvioColors.TextTertiary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    lineHeight = 20.sp
                )
            }

            Spacer(modifier = Modifier.height(backHintGap))

            Text(
                text = stringResource(R.string.profile_pin_overlay_back_hint),
                color = NuvioColors.TextTertiary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )

            Spacer(modifier = Modifier.height(contentBottomPadding))
        }

        BasicTextField(
            value = pin,
            onValueChange = { value ->
                if (!isWorking) {
                    pin = value.filter(Char::isDigit).take(ProfilePinLength)
                    if (!errorMessage.isNullOrEmpty()) onClearError()
                    if (!isSingleEntryMode) internalErrorMessage = null
                }
            },
            modifier = Modifier
                .size(1.dp)
                .graphicsLayer { alpha = 0f }
                .focusRequester(focusRequester)
                .onFocusChanged { state ->
                    isInputFocused = state.isFocused
                },
            singleLine = true,
            textStyle = TextStyle(color = Color.Transparent),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.NumberPassword,
                imeAction = ImeAction.Done
            ),
            cursorBrush = SolidColor(Color.Transparent)
        )
    }
}

@Composable
private fun ProfilePinBoxes(
    value: String,
    isWorking: Boolean,
    isErrorState: Boolean,
    isInputFocused: Boolean,
    cursorAlpha: Float,
    compactMode: Boolean,
    modifier: Modifier = Modifier
) {
    val boxSize by animateDpAsState(
        targetValue = if (compactMode) 90.dp else ProfileSelectionSpacing.PinBoxSize,
        animationSpec = tween(180),
        label = "pinBoxSize"
    )
    val boxGap by animateDpAsState(
        targetValue = if (compactMode) 10.dp else ProfileSelectionSpacing.PinBoxGap,
        animationSpec = tween(180),
        label = "pinBoxGap"
    )
    val activeCursorHeight by animateDpAsState(
        targetValue = if (compactMode) 28.dp else 40.dp,
        animationSpec = tween(180),
        label = "pinCursorHeight"
    )
    val dotSize by animateDpAsState(
        targetValue = if (compactMode) 12.dp else 16.dp,
        animationSpec = tween(180),
        label = "pinDotSize"
    )

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(boxGap),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(ProfilePinLength) { index ->
            val isFilled = index < value.length
            val isActive = index == value.length.coerceAtMost(ProfilePinLength - 1) &&
                value.length < ProfilePinLength &&
                isInputFocused &&
                !isWorking
            val borderColor by animateColorAsState(
                targetValue = when {
                    isErrorState -> Color(0xFFE35D5D)
                    isFilled -> Color.White.copy(alpha = 0.92f)
                    isActive -> Color.White
                    else -> Color.White.copy(alpha = 0.72f)
                },
                animationSpec = tween(140),
                label = "pinBoxBorder"
            )
            val backgroundColor by animateColorAsState(
                targetValue = when {
                    isErrorState -> Color(0xFF311818).copy(alpha = 0.76f)
                    isFilled -> Color.White.copy(alpha = 0.07f)
                    isActive -> Color.White.copy(alpha = 0.04f)
                    else -> Color.Transparent
                },
                animationSpec = tween(140),
                label = "pinBoxBackground"
            )
            val borderWidth by animateDpAsState(
                targetValue = if (isActive || isErrorState) 2.dp else 1.dp,
                animationSpec = tween(140),
                label = "pinBoxBorderWidth"
            )

            Box(
                modifier = Modifier
                    .size(boxSize)
                    .clip(RoundedCornerShape(2.dp))
                    .background(backgroundColor)
                    .border(
                        width = borderWidth,
                        color = borderColor,
                        shape = RoundedCornerShape(2.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                when {
                    isFilled -> {
                        Box(
                            modifier = Modifier
                                .size(dotSize)
                                .clip(CircleShape)
                                .background(Color.White)
                        )
                    }

                    isActive -> {
                        Box(
                            modifier = Modifier
                                .width(2.dp)
                                .height(activeCursorHeight)
                                .background(Color.White.copy(alpha = cursorAlpha))
                        )
                    }
                }
            }
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
            .focusable(enabled = enabled, interactionSource = interactionSource)
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                if (enabled && native.action == AndroidKeyEvent.ACTION_UP && isProfileSelectKey(native.keyCode)) {
                    onClick()
                    true
                } else {
                    false
                }
            }
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
