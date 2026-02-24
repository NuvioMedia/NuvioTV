package com.nuvio.tv

import android.os.Bundle
import android.content.Context
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import java.util.Locale
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.tv.material3.DrawerValue
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.ModalNavigationDrawer
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import androidx.tv.material3.rememberDrawerState
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.data.local.AppOnboardingDataStore
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.ThemeDataStore
import com.nuvio.tv.data.repository.TraktProgressService
import com.nuvio.tv.domain.model.AppTheme
import com.nuvio.tv.domain.model.AuthState
import com.nuvio.tv.core.sync.ProfileSyncService
import com.nuvio.tv.core.sync.StartupSyncService
import com.nuvio.tv.ui.navigation.NuvioNavHost
import com.nuvio.tv.ui.navigation.Screen
import com.nuvio.tv.ui.components.ProfileAvatarCircle
import com.nuvio.tv.ui.screens.account.AuthQrSignInScreen
import com.nuvio.tv.ui.screens.profile.ProfileSelectionScreen
import com.nuvio.tv.ui.theme.NuvioColors
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.updater.UpdateViewModel
import com.nuvio.tv.updater.ui.UpdatePromptDialog
import dagger.hilt.android.AndroidEntryPoint
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze
import dev.chrisbanes.haze.hazeChild
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import coil.compose.rememberAsyncImagePainter
import coil.decode.SvgDecoder
import coil.request.ImageRequest
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R

data class DrawerItem(
    val route: String,
    val label: String,
    val iconRes: Int? = null,
    val icon: ImageVector? = null
)

private data class MainUiPrefs(
    val theme: AppTheme = AppTheme.WHITE,
    val hasChosenLayout: Boolean? = null,
    val sidebarCollapsed: Boolean = false,
    val modernSidebarEnabled: Boolean = false,
    val modernSidebarBlurPref: Boolean = false
)

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var themeDataStore: ThemeDataStore

    @Inject
    lateinit var layoutPreferenceDataStore: LayoutPreferenceDataStore

    @Inject
    lateinit var traktProgressService: TraktProgressService

    @Inject
    lateinit var startupSyncService: StartupSyncService

    @Inject
    lateinit var profileSyncService: ProfileSyncService

    @Inject
    lateinit var profileManager: ProfileManager

    @Inject
    lateinit var authManager: AuthManager

    @Inject
    lateinit var appOnboardingDataStore: AppOnboardingDataStore

    @OptIn(ExperimentalTvMaterial3Api::class)
    override fun attachBaseContext(newBase: Context) {
        val tag = newBase.getSharedPreferences("app_locale", Context.MODE_PRIVATE)
            .getString("locale_tag", null)
        if (!tag.isNullOrEmpty()) {
            val locale = Locale.forLanguageTag(tag)
            Locale.setDefault(locale)
            val config = Configuration(newBase.resources.configuration)
            config.setLocale(locale)
            super.attachBaseContext(newBase.createConfigurationContext(config))
        } else {
            super.attachBaseContext(newBase)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setContent {
            var hasSelectedProfileThisSession by remember { mutableStateOf(false) }
            var onboardingCompletedThisSession by remember { mutableStateOf(false) }
            var onboardingProfileSyncInProgress by remember { mutableStateOf(false) }
            val hasSeenAuthQrOnFirstLaunch by appOnboardingDataStore
                .hasSeenAuthQrOnFirstLaunch
                .map<Boolean, Boolean?> { it }
                .collectAsState(initial = null)
            val authState by authManager.authState.collectAsState()

            LaunchedEffect(hasSeenAuthQrOnFirstLaunch, authState) {
                if (hasSeenAuthQrOnFirstLaunch == false && authState is AuthState.FullAccount) {
                    appOnboardingDataStore.setHasSeenAuthQrOnFirstLaunch(true)
                    onboardingCompletedThisSession = true
                }
            }

            val activeProfileId by profileManager.activeProfileId.collectAsState()
            val profiles by profileManager.profiles.collectAsState()
            val activeProfile = remember(activeProfileId, profiles) {
                profiles.firstOrNull { it.id == activeProfileId }
            }

            val mainUiPrefsFlow = remember(themeDataStore, layoutPreferenceDataStore) {
                combine(
                    themeDataStore.selectedTheme,
                    layoutPreferenceDataStore.hasChosenLayout,
                    layoutPreferenceDataStore.sidebarCollapsedByDefault,
                    layoutPreferenceDataStore.modernSidebarEnabled,
                    layoutPreferenceDataStore.modernSidebarBlurEnabled
                ) { theme, hasChosenLayout, sidebarCollapsed, modernSidebarEnabled, modernSidebarBlurPref ->
                    MainUiPrefs(
                        theme = theme,
                        hasChosenLayout = hasChosenLayout,
                        sidebarCollapsed = sidebarCollapsed,
                        modernSidebarEnabled = modernSidebarEnabled,
                        modernSidebarBlurPref = modernSidebarBlurPref
                    )
                }
            }
            val mainUiPrefs by mainUiPrefsFlow.collectAsState(initial = MainUiPrefs(hasChosenLayout = null))

            NuvioTheme(appTheme = mainUiPrefs.theme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    shape = RectangleShape
                ) {
                    if (hasSeenAuthQrOnFirstLaunch == null) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(NuvioColors.Background)
                        )
                        return@Surface
                    }

                    if (
                        hasSeenAuthQrOnFirstLaunch == false &&
                        authState !is AuthState.FullAccount &&
                        !onboardingCompletedThisSession
                    ) {
                        AuthQrSignInScreen(
                            onBackPress = {},
                            onContinue = {
                                lifecycleScope.launch {
                                    val shouldRunRemoteOnboardingSync =
                                        authManager.authState.value is AuthState.FullAccount

                                    if (shouldRunRemoteOnboardingSync) {
                                        if (onboardingProfileSyncInProgress) return@launch
                                        onboardingProfileSyncInProgress = true
                                        val maxAttempts = 3
                                        var synced = false
                                        for (attempt in 0 until maxAttempts) {
                                            val result = profileSyncService.pullFromRemote()
                                            if (result.isSuccess) {
                                                synced = true
                                                break
                                            }
                                            if (attempt < maxAttempts - 1) {
                                                delay(1_000)
                                            }
                                        }
                                        if (!synced) {
                                            android.util.Log.w(
                                                "MainActivity",
                                                "Onboarding profile sync failed after retries; continuing"
                                            )
                                        }
                                    }
                                    appOnboardingDataStore.setHasSeenAuthQrOnFirstLaunch(true)
                                    onboardingCompletedThisSession = true
                                    onboardingProfileSyncInProgress = false
                                }
                                if (authManager.authState.value is AuthState.FullAccount) {
                                    startupSyncService.requestSyncNow()
                                }
                            }
                        )
                        return@Surface
                    }

                    val shouldShowProfileSelection =
                        !hasSelectedProfileThisSession && profiles.size > 1

                    if (shouldShowProfileSelection) {
                        ProfileSelectionScreen(
                            onProfileSelected = {
                                hasSelectedProfileThisSession = true
                                if (authManager.authState.value is AuthState.FullAccount) {
                                    startupSyncService.requestSyncNow()
                                }
                            }
                        )
                        return@Surface
                    }

                    val layoutChosen = mainUiPrefs.hasChosenLayout
                    if (layoutChosen == null) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(NuvioColors.Background)
                        )
                        return@Surface
                    }
                    val sidebarCollapsed = mainUiPrefs.sidebarCollapsed
                    val modernSidebarEnabled = mainUiPrefs.modernSidebarEnabled
                    val modernSidebarBlurEnabled =
                        mainUiPrefs.modernSidebarBlurPref && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
                    val hideBuiltInHeadersForFloatingPill = modernSidebarEnabled && !sidebarCollapsed

                    val updateViewModel: UpdateViewModel = hiltViewModel(this@MainActivity)
                    val updateState by updateViewModel.uiState.collectAsState()

                    val startDestination = if (layoutChosen) Screen.Home.route else Screen.LayoutSelection.route
                    val navController = rememberNavController()
                    val navBackStackEntry by navController.currentBackStackEntryAsState()
                    val currentRoute = navBackStackEntry?.destination?.route

                    val rootRoutes = remember {
                        setOf(
                            Screen.Home.route,
                            Screen.Search.route,
                            Screen.Library.route,
                            Screen.Settings.route,
                            Screen.AddonManager.route
                        )
                    }

                    val strNavHome = stringResource(R.string.nav_home)
                    val strNavSearch = stringResource(R.string.nav_search)
                    val strNavLibrary = stringResource(R.string.nav_library)
                    val strNavAddons = stringResource(R.string.nav_addons)
                    val strNavSettings = stringResource(R.string.nav_settings)
                    val drawerItems = remember(
                        strNavHome,
                        strNavSearch,
                        strNavLibrary,
                        strNavAddons,
                        strNavSettings
                    ) {
                        listOf(
                            DrawerItem(
                                route = Screen.Home.route,
                                label = strNavHome,
                                icon = Icons.Default.Home
                            ),
                            DrawerItem(
                                route = Screen.Search.route,
                                label = strNavSearch,
                                iconRes = R.raw.sidebar_search
                            ),
                            DrawerItem(
                                route = Screen.Library.route,
                                label = strNavLibrary,
                                iconRes = R.raw.sidebar_library
                            ),
                            DrawerItem(
                                route = Screen.AddonManager.route,
                                label = strNavAddons,
                                iconRes = R.raw.sidebar_plugin
                            ),
                            DrawerItem(
                                route = Screen.Settings.route,
                                label = strNavSettings,
                                iconRes = R.raw.sidebar_settings
                            )
                        )
                    }
                    val selectedDrawerRoute = drawerItems.firstOrNull { item ->
                        currentRoute == item.route || currentRoute?.startsWith("${item.route}/") == true
                    }?.route
                    val selectedDrawerItem = drawerItems.firstOrNull { it.route == selectedDrawerRoute } ?: drawerItems.first()

                    if (modernSidebarEnabled) {
                        ModernSidebarScaffold(
                            navController = navController,
                            startDestination = startDestination,
                            currentRoute = currentRoute,
                            rootRoutes = rootRoutes,
                            drawerItems = drawerItems,
                            selectedDrawerRoute = selectedDrawerRoute,
                            selectedDrawerItem = selectedDrawerItem,
                            sidebarCollapsed = sidebarCollapsed,
                            modernSidebarBlurEnabled = modernSidebarBlurEnabled,
                            hideBuiltInHeaders = hideBuiltInHeadersForFloatingPill,
                            activeProfileName = activeProfile?.name ?: "",
                            activeProfileColorHex = activeProfile?.avatarColorHex ?: "#1E88E5",
                            onSwitchProfile = { hasSelectedProfileThisSession = false },
                            onExitApp = {
                                finishAffinity()
                                finishAndRemoveTask()
                            }
                        )
                    } else {
                        LegacySidebarScaffold(
                            navController = navController,
                            startDestination = startDestination,
                            currentRoute = currentRoute,
                            rootRoutes = rootRoutes,
                            drawerItems = drawerItems,
                            selectedDrawerRoute = selectedDrawerRoute,
                            sidebarCollapsed = sidebarCollapsed,
                            hideBuiltInHeaders = false,
                            activeProfileName = activeProfile?.name ?: "",
                            activeProfileColorHex = activeProfile?.avatarColorHex ?: "#1E88E5",
                            onSwitchProfile = { hasSelectedProfileThisSession = false },
                            onExitApp = {
                                finishAffinity()
                                finishAndRemoveTask()
                            }
                        )
                    }

                    UpdatePromptDialog(
                        state = updateState,
                        onDismiss = { updateViewModel.dismissDialog() },
                        onDownload = { updateViewModel.downloadUpdate() },
                        onInstall = { updateViewModel.installUpdateOrRequestPermission() },
                        onIgnore = { updateViewModel.ignoreThisVersion() },
                        onOpenUnknownSources = { updateViewModel.openUnknownSourcesSettings() }
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        startupSyncService.requestSyncNow()
        lifecycleScope.launch {
            traktProgressService.refreshNow()
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LegacySidebarScaffold(
    navController: NavHostController,
    startDestination: String,
    currentRoute: String?,
    rootRoutes: Set<String>,
    drawerItems: List<DrawerItem>,
    selectedDrawerRoute: String?,
    sidebarCollapsed: Boolean,
    hideBuiltInHeaders: Boolean,
    activeProfileName: String,
    activeProfileColorHex: String,
    onSwitchProfile: () -> Unit,
    onExitApp: () -> Unit
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val drawerItemFocusRequesters = remember(drawerItems) {
        drawerItems.associate { item -> item.route to FocusRequester() }
    }
    val showSidebar = currentRoute in rootRoutes

    LaunchedEffect(currentRoute) {
        drawerState.setValue(DrawerValue.Closed)
    }

    val closedDrawerWidth = if (sidebarCollapsed) 0.dp else 72.dp
    val openDrawerWidth = 216.dp

    val focusManager = LocalFocusManager.current
    var pendingContentFocusTransfer by remember { mutableStateOf(false) }
    var pendingSidebarFocusRequest by remember { mutableStateOf(false) }

    BackHandler(enabled = currentRoute in rootRoutes && drawerState.currentValue == DrawerValue.Closed) {
        pendingSidebarFocusRequest = true
        drawerState.setValue(DrawerValue.Open)
    }

    BackHandler(enabled = currentRoute in rootRoutes && drawerState.currentValue == DrawerValue.Open) {
        onExitApp()
    }

    LaunchedEffect(drawerState.currentValue, pendingContentFocusTransfer) {
        if (!pendingContentFocusTransfer || drawerState.currentValue != DrawerValue.Closed) {
            return@LaunchedEffect
        }
        repeat(2) { withFrameNanos { } }
        focusManager.moveFocus(FocusDirection.Right)
        pendingContentFocusTransfer = false
    }

    LaunchedEffect(drawerState.currentValue, selectedDrawerRoute, showSidebar, pendingSidebarFocusRequest) {
        if (!showSidebar || !pendingSidebarFocusRequest || drawerState.currentValue != DrawerValue.Open) {
            return@LaunchedEffect
        }
        val targetRoute = selectedDrawerRoute ?: run {
            pendingSidebarFocusRequest = false
            return@LaunchedEffect
        }
        val requester = drawerItemFocusRequesters[targetRoute] ?: run {
            pendingSidebarFocusRequest = false
            return@LaunchedEffect
        }
        repeat(2) { withFrameNanos { } }
        runCatching { requester.requestFocus() }
        pendingSidebarFocusRequest = false
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = { drawerValue ->
            if (showSidebar) {
                val drawerWidth = if (drawerValue == DrawerValue.Open) openDrawerWidth else closedDrawerWidth
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(drawerWidth)
                        .background(NuvioColors.Background)
                        .padding(12.dp)
                        .selectableGroup()
                        .onPreviewKeyEvent { keyEvent ->
                            if (keyEvent.key == Key.DirectionRight && keyEvent.type == KeyEventType.KeyDown) {
                                drawerState.setValue(DrawerValue.Closed)
                                pendingContentFocusTransfer = false
                                true
                            } else {
                                false
                            }
                        }
                ) {
                    val isExpanded = drawerValue == DrawerValue.Open
                    if (isExpanded) {
                        Spacer(modifier = Modifier.height(30.dp))
                        Image(
                            painter = painterResource(id = R.drawable.app_logo_wordmark),
                            contentDescription = "NuvioTV",
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(42.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    val itemWidth = if (isExpanded) 176.dp else 48.dp
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        drawerItems.forEach { item ->
                            LegacySidebarButton(
                                label = item.label,
                                iconRes = item.iconRes,
                                icon = item.icon,
                                selected = selectedDrawerRoute == item.route,
                                expanded = isExpanded,
                                onClick = {
                                    navigateToDrawerRoute(
                                        navController = navController,
                                        currentRoute = currentRoute,
                                        targetRoute = item.route
                                    )
                                    drawerState.setValue(DrawerValue.Closed)
                                    pendingContentFocusTransfer = true
                                },
                                modifier = Modifier.focusRequester(
                                    drawerItemFocusRequesters.getValue(item.route)
                                ).width(itemWidth)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    if (isExpanded && activeProfileName.isNotEmpty()) {
                        var isProfileFocused by remember { mutableStateOf(false) }
                        val profileItemShape = RoundedCornerShape(32.dp)
                        val profileBgColor by animateColorAsState(
                            targetValue = if (isProfileFocused) NuvioColors.FocusBackground else Color.Transparent,
                            label = "legacyProfileItemBg"
                        )
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                modifier = Modifier
                                    .width(itemWidth)
                                    .height(52.dp)
                                    .clip(profileItemShape)
                                    .background(color = profileBgColor, shape = profileItemShape)
                                    .onFocusChanged { isProfileFocused = it.isFocused }
                                    .clickable {
                                        onSwitchProfile()
                                        drawerState.setValue(DrawerValue.Closed)
                                    },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Spacer(modifier = Modifier.width(10.dp))
                                ProfileAvatarCircle(
                                    name = activeProfileName,
                                    colorHex = activeProfileColorHex,
                                    size = 34.dp
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = activeProfileName,
                                    color = if (isProfileFocused) NuvioColors.TextPrimary else NuvioColors.TextSecondary,
                                    maxLines = 1,
                                    textAlign = TextAlign.Start
                                )
                            }
                        }
                    }
                }
            }
        }
    ) {
        val contentStartPadding = if (showSidebar) closedDrawerWidth else 0.dp
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = contentStartPadding)
                .onKeyEvent { keyEvent ->
                    if (
                        showSidebar &&
                        drawerState.currentValue == DrawerValue.Closed &&
                        keyEvent.type == KeyEventType.KeyDown &&
                        keyEvent.key == Key.DirectionLeft
                    ) {
                        if (focusManager.moveFocus(FocusDirection.Left)) {
                            true
                        } else {
                            pendingSidebarFocusRequest = true
                            drawerState.setValue(DrawerValue.Open)
                            true
                        }
                    } else {
                        false
                    }
                }
        ) {
            NuvioNavHost(
                navController = navController,
                startDestination = startDestination,
                hideBuiltInHeaders = hideBuiltInHeaders
            )
        }
    }
}

@Composable
private fun LegacySidebarButton(
    label: String,
    iconRes: Int?,
    icon: ImageVector?,
    selected: Boolean,
    expanded: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val itemShape = RoundedCornerShape(32.dp)
    val backgroundColor by animateColorAsState(
        targetValue = when {
            isFocused -> NuvioColors.FocusBackground
            selected -> NuvioColors.BackgroundCard
            else -> Color.Transparent
        },
        label = "legacySidebarItemBackground"
    )
    val contentColor by animateColorAsState(
        targetValue = when {
            isFocused -> NuvioColors.TextPrimary
            selected -> NuvioColors.TextPrimary
            else -> NuvioColors.TextSecondary
        },
        label = "legacySidebarItemContent"
    )

    Box(
        modifier = modifier
            .height(52.dp)
            .focusProperties { canFocus = expanded }
            .clip(itemShape)
            .background(color = backgroundColor, shape = itemShape)
            .onFocusChanged { isFocused = it.isFocused }
            .clickable(onClick = onClick),
    ) {
        DrawerItemIcon(
            iconRes = iconRes,
            icon = icon,
            tint = contentColor,
            modifier = if (expanded) {
                Modifier
                    .size(22.dp)
                    .align(Alignment.CenterStart)
                    .offset(x = 18.dp)
            } else {
                Modifier
                    .size(22.dp)
                    .align(Alignment.Center)
            }
        )
        if (expanded) {
            Text(
                text = label,
                color = contentColor,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                textAlign = TextAlign.Start,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxWidth()
                    .padding(start = 54.dp, end = 14.dp)
            )
        }
    }
}

@Composable
private fun ModernSidebarScaffold(
    navController: NavHostController,
    startDestination: String,
    currentRoute: String?,
    rootRoutes: Set<String>,
    drawerItems: List<DrawerItem>,
    selectedDrawerRoute: String?,
    selectedDrawerItem: DrawerItem,
    sidebarCollapsed: Boolean,
    modernSidebarBlurEnabled: Boolean,
    hideBuiltInHeaders: Boolean,
    activeProfileName: String,
    activeProfileColorHex: String,
    onSwitchProfile: () -> Unit,
    onExitApp: () -> Unit
) {
    val showSidebar = currentRoute in rootRoutes
    val collapsedSidebarWidth = if (sidebarCollapsed) 0.dp else 184.dp
    val openSidebarWidth = 262.dp

    val focusManager = LocalFocusManager.current
    val drawerItemFocusRequesters = remember(drawerItems) {
        drawerItems.associate { item -> item.route to FocusRequester() }
    }

    var isSidebarExpanded by remember { mutableStateOf(false) }
    var sidebarCollapsePending by remember { mutableStateOf(false) }
    var pendingContentFocusTransfer by remember { mutableStateOf(false) }
    var pendingSidebarFocusRequest by remember { mutableStateOf(false) }
    var focusedDrawerIndex by remember { mutableStateOf(-1) }
    var isFloatingPillIconOnly by remember { mutableStateOf(false) }
    val keepFloatingPillExpanded = selectedDrawerRoute == Screen.Settings.route
    val keepSidebarFocusDuringCollapse =
        isSidebarExpanded || sidebarCollapsePending || pendingContentFocusTransfer

    LaunchedEffect(showSidebar) {
        if (!showSidebar) {
            isSidebarExpanded = false
            sidebarCollapsePending = false
            pendingContentFocusTransfer = false
            pendingSidebarFocusRequest = false
            isFloatingPillIconOnly = false
        }
    }

    LaunchedEffect(keepFloatingPillExpanded, showSidebar) {
        if (!showSidebar || keepFloatingPillExpanded) {
            isFloatingPillIconOnly = false
        }
    }

    BackHandler(enabled = currentRoute in rootRoutes && !isSidebarExpanded && !sidebarCollapsePending) {
        isSidebarExpanded = true
        sidebarCollapsePending = false
        pendingSidebarFocusRequest = true
    }

    BackHandler(enabled = currentRoute in rootRoutes && isSidebarExpanded && !sidebarCollapsePending) {
        onExitApp()
    }

    LaunchedEffect(sidebarCollapsePending, isSidebarExpanded, showSidebar) {
        if (!showSidebar || !sidebarCollapsePending) {
            return@LaunchedEffect
        }
        if (!isSidebarExpanded) {
            sidebarCollapsePending = false
            return@LaunchedEffect
        }
        delay(95L)
        isSidebarExpanded = false
        sidebarCollapsePending = false
    }

    val sidebarVisible = showSidebar && (isSidebarExpanded || !sidebarCollapsed)
    val sidebarHazeState = remember { HazeState() }
    val targetSidebarWidth = when {
        !sidebarVisible -> 0.dp
        isSidebarExpanded -> openSidebarWidth
        else -> collapsedSidebarWidth
    }
    val sidebarWidth by animateDpAsState(
        targetValue = targetSidebarWidth,
        animationSpec = if (isSidebarExpanded) {
            keyframes {
                durationMillis = 365
                (openSidebarWidth + 12.dp) at 175
            }
        } else {
            tween(durationMillis = 385, easing = LinearOutSlowInEasing)
        },
        label = "sidebarWidth"
    )
    val sidebarSlideX by animateDpAsState(
        targetValue = if (sidebarVisible) 0.dp else (-24).dp,
        animationSpec = tween(durationMillis = 205, easing = FastOutSlowInEasing),
        label = "sidebarSlideX"
    )
    val sidebarSurfaceAlpha by animateFloatAsState(
        targetValue = if (sidebarVisible) 1f else 0f,
        animationSpec = tween(durationMillis = 135, easing = FastOutSlowInEasing),
        label = "sidebarSurfaceAlpha"
    )
    val shouldApplySidebarHaze = showSidebar && (
        sidebarVisible ||
            isSidebarExpanded ||
            sidebarCollapsePending ||
            sidebarWidth > 0.dp
        )
    val sidebarTransition = updateTransition(
        targetState = isSidebarExpanded,
        label = "sidebarTransition"
    )
    val sidebarLabelAlpha by sidebarTransition.animateFloat(
        transitionSpec = {
            if (targetState) {
                tween(durationMillis = 125, easing = FastOutSlowInEasing)
            } else {
                tween(durationMillis = 145, easing = LinearOutSlowInEasing)
            }
        },
        label = "sidebarLabelAlpha"
    ) { expanded ->
        if (expanded) 1f else 0f
    }
    val sidebarExpandProgress by sidebarTransition.animateFloat(
        transitionSpec = {
            if (targetState) {
                tween(durationMillis = 345, easing = FastOutSlowInEasing)
            } else {
                tween(durationMillis = 385, easing = LinearOutSlowInEasing)
            }
        },
        label = "sidebarExpandProgress"
    ) { expanded ->
        if (expanded) 1f else 0f
    }
    val sidebarIconScale by sidebarTransition.animateFloat(
        transitionSpec = { tween(durationMillis = 145, easing = FastOutSlowInEasing) },
        label = "sidebarIconScale"
    ) { expanded ->
        if (expanded) 1f else 0.92f
    }
    val sidebarBloomScale by sidebarTransition.animateFloat(
        transitionSpec = {
            if (targetState) {
                tween(durationMillis = 345, easing = FastOutSlowInEasing)
            } else {
                tween(durationMillis = 395, easing = LinearOutSlowInEasing)
            }
        },
        label = "sidebarBloomScale"
    ) { expanded ->
        if (expanded) 1f else 0.9f
    }
    val sidebarDeflateOffsetX by sidebarTransition.animateDp(
        transitionSpec = {
            if (targetState) {
                tween(durationMillis = 345, easing = FastOutSlowInEasing)
            } else {
                tween(durationMillis = 395, easing = LinearOutSlowInEasing)
            }
        },
        label = "sidebarDeflateOffsetX"
    ) { expanded ->
        if (expanded) 0.dp else (-10).dp
    }
    val sidebarDeflateOffsetY by sidebarTransition.animateDp(
        transitionSpec = {
            if (targetState) {
                tween(durationMillis = 345, easing = FastOutSlowInEasing)
            } else {
                tween(durationMillis = 395, easing = LinearOutSlowInEasing)
            }
        },
        label = "sidebarDeflateOffsetY"
    ) { expanded ->
        if (expanded) 0.dp else (-8).dp
    }

    LaunchedEffect(isSidebarExpanded, sidebarCollapsePending, pendingContentFocusTransfer, showSidebar) {
        if (!showSidebar || !pendingContentFocusTransfer || isSidebarExpanded || sidebarCollapsePending) {
            return@LaunchedEffect
        }
        repeat(2) { withFrameNanos { } }
        focusManager.moveFocus(FocusDirection.Right)
        pendingContentFocusTransfer = false
    }

    LaunchedEffect(isSidebarExpanded, pendingSidebarFocusRequest, showSidebar, selectedDrawerRoute) {
        if (!showSidebar || !pendingSidebarFocusRequest || !isSidebarExpanded) {
            return@LaunchedEffect
        }
        val targetRoute = selectedDrawerRoute ?: run {
            pendingSidebarFocusRequest = false
            return@LaunchedEffect
        }
        val requester = drawerItemFocusRequesters[targetRoute] ?: run {
            pendingSidebarFocusRequest = false
            return@LaunchedEffect
        }
        repeat(2) { withFrameNanos { } }
        runCatching { requester.requestFocus() }
        pendingSidebarFocusRequest = false
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (shouldApplySidebarHaze) {
                        Modifier.haze(sidebarHazeState)
                    } else {
                        Modifier
                    }
                )
                .onPreviewKeyEvent { keyEvent ->
                    if (
                        isSidebarExpanded &&
                        !sidebarCollapsePending &&
                        sidebarExpandProgress > 0.2f &&
                        keyEvent.type == KeyEventType.KeyDown &&
                        isBlockedContentKey(keyEvent.key)
                    ) {
                        true
                    } else {
                        false
                    }
                }
                .onKeyEvent { keyEvent ->
                    if (showSidebar && !isSidebarExpanded && keyEvent.type == KeyEventType.KeyDown) {
                        if (!keepFloatingPillExpanded) {
                            when (keyEvent.key) {
                                Key.DirectionDown -> isFloatingPillIconOnly = true
                                Key.DirectionUp -> isFloatingPillIconOnly = false
                                else -> Unit
                            }
                        }
                        if (keyEvent.key == Key.DirectionLeft) {
                            if (focusManager.moveFocus(FocusDirection.Left)) {
                                true
                            } else {
                                isSidebarExpanded = true
                                sidebarCollapsePending = false
                                pendingSidebarFocusRequest = true
                                true
                            }
                        } else {
                            false
                        }
                    } else {
                        false
                    }
                }
        ) {
            NuvioNavHost(
                navController = navController,
                startDestination = startDestination,
                hideBuiltInHeaders = hideBuiltInHeaders
            )
        }

        if (showSidebar && (sidebarVisible || sidebarWidth > 0.dp)) {
            val panelShape = RoundedCornerShape(30.dp)
            val showExpandedPanel = isSidebarExpanded || sidebarExpandProgress > 0.01f

            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .width(sidebarWidth)
                    .padding(start = 14.dp, top = 16.dp, bottom = 12.dp, end = 8.dp)
                    .offset(x = sidebarSlideX + sidebarDeflateOffsetX, y = sidebarDeflateOffsetY)
                    .graphicsLayer(
                        alpha = sidebarSurfaceAlpha,
                        scaleX = sidebarBloomScale,
                        scaleY = sidebarBloomScale,
                        transformOrigin = TransformOrigin(0f, 0f)
                    )
                    .selectableGroup()
                    .onPreviewKeyEvent { keyEvent ->
                        if (!isSidebarExpanded || keyEvent.type != KeyEventType.KeyDown) {
                            return@onPreviewKeyEvent false
                        }
                        when (keyEvent.key) {
                            Key.DirectionUp -> {
                                focusedDrawerIndex == 0
                            }

                            Key.DirectionDown -> {
                                focusedDrawerIndex > drawerItems.lastIndex
                            }

                            Key.DirectionRight -> {
                                pendingContentFocusTransfer = false
                                sidebarCollapsePending = true
                                true
                            }

                            else -> false
                        }
                    }
            ) {
                if (showExpandedPanel) {
                    ModernSidebarBlurPanel(
                        drawerItems = drawerItems,
                        selectedDrawerRoute = selectedDrawerRoute,
                        keepSidebarFocusDuringCollapse = keepSidebarFocusDuringCollapse,
                        sidebarLabelAlpha = sidebarLabelAlpha,
                        sidebarIconScale = sidebarIconScale,
                        sidebarExpandProgress = sidebarExpandProgress,
                        isSidebarExpanded = isSidebarExpanded,
                        sidebarCollapsePending = sidebarCollapsePending,
                        blurEnabled = modernSidebarBlurEnabled,
                        sidebarHazeState = sidebarHazeState,
                        panelShape = panelShape,
                        drawerItemFocusRequesters = drawerItemFocusRequesters,
                        onDrawerItemFocused = { focusedDrawerIndex = it },
                        onDrawerItemClick = { targetRoute ->
                            navigateToDrawerRoute(
                                navController = navController,
                                currentRoute = currentRoute,
                                targetRoute = targetRoute
                            )
                            pendingSidebarFocusRequest = false
                            isSidebarExpanded = false
                            sidebarCollapsePending = false
                            pendingContentFocusTransfer = true
                        },
                        activeProfileName = activeProfileName,
                        activeProfileColorHex = activeProfileColorHex,
                        onSwitchProfile = onSwitchProfile
                    )
                }
            }

            if (
                !sidebarCollapsed &&
                sidebarExpandProgress < 0.98f &&
                selectedDrawerRoute != Screen.Search.route
            ) {
                CollapsedSidebarPill(
                    label = selectedDrawerItem.label,
                    iconRes = selectedDrawerItem.iconRes,
                    icon = selectedDrawerItem.icon,
                    iconOnly = isFloatingPillIconOnly && !keepFloatingPillExpanded,
                    hazeState = sidebarHazeState,
                    blurEnabled = modernSidebarBlurEnabled,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset(
                            x = 40.dp + sidebarSlideX + sidebarDeflateOffsetX,
                            y = 16.dp + sidebarDeflateOffsetY
                        )
                        .graphicsLayer(
                            alpha = 1f - sidebarExpandProgress,
                            scaleX = 0.9f + (0.1f * (1f - sidebarExpandProgress)),
                            scaleY = 0.9f + (0.1f * (1f - sidebarExpandProgress)),
                            transformOrigin = TransformOrigin(0f, 0f)
                        ),
                    onExpand = {
                        isSidebarExpanded = true
                        sidebarCollapsePending = false
                        pendingSidebarFocusRequest = true
                    }
                )
            }
        }
    }
}

@Composable
private fun CollapsedSidebarPill(
    label: String,
    iconRes: Int?,
    icon: ImageVector?,
    iconOnly: Boolean,
    hazeState: HazeState,
    blurEnabled: Boolean,
    modifier: Modifier = Modifier,
    onExpand: () -> Unit
) {
    val pillShape = RoundedCornerShape(999.dp)
    val innerBlurShape = RoundedCornerShape(999.dp)

    Row(
        modifier = modifier
            .focusProperties { canFocus = false }
            .animateContentSize()
            .clickable(onClick = onExpand)
            .padding(horizontal = 1.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(0.25.dp)
    ) {
        if (!iconOnly) {
            Image(
                painter = painterResource(id = R.drawable.ic_chevron_compact_left),
                contentDescription = stringResource(R.string.cd_expand_sidebar),
                modifier = Modifier
                    .width(8.5.dp)
                    .height(16.dp)
                    .offset(y = (-0.5).dp)
            )
        }

        Box(
            modifier = Modifier
                .height(44.dp)
                .graphicsLayer {
                    shape = pillShape
                    clip = true
                    compositingStrategy = CompositingStrategy.Offscreen
                }
                .clip(pillShape)
                .background(
                    brush = if (blurEnabled) {
                        Brush.verticalGradient(
                            colors = listOf(
                                Color(0xD1424851),
                                Color(0xC73B4149)
                            )
                        )
                    } else {
                        Brush.verticalGradient(
                            colors = listOf(
                                NuvioColors.BackgroundElevated,
                                NuvioColors.BackgroundCard
                            )
                        )
                    },
                    shape = pillShape
                )
                .border(
                    width = 1.dp,
                    color = if (blurEnabled) {
                        Color.White.copy(alpha = 0.14f)
                    } else {
                        NuvioColors.Border.copy(alpha = 0.9f)
                    },
                    shape = pillShape
                )
        ) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .padding(start = 2.25.dp, top = 2.25.dp, end = 5.dp, bottom = 2.25.dp)
                    .graphicsLayer {
                        shape = innerBlurShape
                        clip = true
                        compositingStrategy = CompositingStrategy.Offscreen
                    }
                    .clip(innerBlurShape)
                    .then(
                        if (blurEnabled) {
                            Modifier.hazeChild(
                                state = hazeState,
                                shape = innerBlurShape,
                                tint = Color.Unspecified,
                                blurRadius = 3.dp,
                                noiseFactor = 0f
                            )
                        } else {
                            Modifier
                        }
                    )
            )

            Row(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight()
                    .padding(start = 5.dp, end = if (iconOnly) 5.dp else 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (iconOnly) 0.dp else 9.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF4F555E)),
                    contentAlignment = Alignment.Center
                ) {
                    DrawerItemIcon(
                        iconRes = iconRes,
                        icon = icon,
                        tint = Color.White,
                        modifier = Modifier
                            .size(22.dp)
                            .offset(y = (-0.5).dp)
                    )
                }

                if (!iconOnly) {
                    Text(
                        text = label,
                        color = Color.White,
                        style = androidx.tv.material3.MaterialTheme.typography.titleLarge.copy(
                            lineHeight = 30.sp
                        ),
                        modifier = Modifier.offset(y = (-0.5).dp),
                        maxLines = 1
                    )
                }
            }
        }
    }
}

private fun navigateToDrawerRoute(
    navController: NavHostController,
    currentRoute: String?,
    targetRoute: String
) {
    if (currentRoute == targetRoute) {
        return
    }
    navController.navigate(targetRoute) {
        popUpTo(navController.graph.startDestinationId) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = true
    }
}

private fun isBlockedContentKey(key: Key): Boolean {
    return key == Key.DirectionUp ||
        key == Key.DirectionDown ||
        key == Key.DirectionLeft ||
        key == Key.DirectionRight ||
        key == Key.DirectionCenter ||
        key == Key.Enter
}

@Composable
private fun DrawerItemIcon(
    iconRes: Int?,
    icon: ImageVector?,
    modifier: Modifier = Modifier,
    tint: Color = androidx.tv.material3.LocalContentColor.current
) {
    when {
        icon != null -> Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = modifier
        )

        iconRes != null -> Icon(
            painter = rememberRawSvgPainter(iconRes),
            contentDescription = null,
            tint = tint,
            modifier = modifier
        )
    }
}

@Composable
private fun rememberRawSvgPainter(rawIconRes: Int): Painter = rememberAsyncImagePainter(
    model = ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
        .data(rawIconRes)
        .decoderFactory(SvgDecoder.Factory())
        .build()
)
