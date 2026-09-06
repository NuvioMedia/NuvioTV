package com.nuvio.tv

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
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
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.nuvio.tv.core.runtime.PluginRuntimeHooks
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.os.ConfigurationCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.lifecycleScope
import androidx.metrics.performance.JankStats
import androidx.metrics.performance.PerformanceMetricsState
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.DrawerValue
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.ModalNavigationDrawer
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import androidx.tv.material3.rememberDrawerState
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import com.nuvio.tv.R
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.auth.DeviceSessionRegistration
import com.nuvio.tv.core.deeplink.DeepLinkHandler
import com.nuvio.tv.core.deeplink.DeepLinkParser
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.sync.ProfileSyncService
import com.nuvio.tv.core.sync.StartupSyncService
import com.nuvio.tv.core.tracking.TrackingProgressRefreshCoordinator
import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import com.nuvio.tv.data.local.AppOnboardingDataStore
import com.nuvio.tv.data.local.AuthSessionNoticeDataStore
import com.nuvio.tv.data.local.ExperienceModeDataStore
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.StartupAuthNotice
import com.nuvio.tv.data.local.ThemeDataStore
import com.nuvio.tv.data.repository.MemberAccessRepository
import com.nuvio.tv.data.remote.supabase.AvatarRepository
import com.nuvio.tv.domain.model.AppFont
import com.nuvio.tv.domain.model.AppTheme
import com.nuvio.tv.domain.model.CustomThemeColors
import com.nuvio.tv.domain.model.AuthState
import com.nuvio.tv.domain.model.CardDepthStyle
import com.nuvio.tv.domain.model.CosmeticEntitlement
import com.nuvio.tv.domain.model.DiscoverLocation
import com.nuvio.tv.domain.model.ExperienceMode
import com.nuvio.tv.domain.model.MemberAccess
import com.nuvio.tv.domain.model.SettingsUiStyle
import com.nuvio.tv.domain.model.resolveAppTheme
import com.nuvio.tv.domain.deeplink.AppDeepLink
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.ui.components.NuvioScrollDefaults
import com.nuvio.tv.ui.components.BrandWordmark
import com.nuvio.tv.ui.components.LocalCardDepthStyle
import com.nuvio.tv.ui.components.ProfileAvatarCircle
import com.nuvio.tv.ui.navigation.NuvioNavHost
import com.nuvio.tv.ui.navigation.Screen
import com.nuvio.tv.ui.membership.LocalMemberAccess
import com.nuvio.tv.ui.screens.account.AuthQrSignInScreen
import com.nuvio.tv.ui.screens.addon.EssentialAddonSetupScreen
import com.nuvio.tv.ui.screens.profile.ProfileSelectionScreen
import com.nuvio.tv.ui.theme.NuvioComponents
import com.nuvio.tv.ui.theme.NuvioLayout
import com.nuvio.tv.ui.theme.NuvioMotion
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioRadii
import com.nuvio.tv.ui.theme.NuvioStrokes
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.theme.ThemeColors
import com.nuvio.tv.ui.theme.accentBrush
import com.nuvio.tv.ui.util.LocalFastHorizontalNavigationEnabled
import com.nuvio.tv.ui.util.LocalRecompositionHighlighterEnabled
import com.nuvio.tv.ui.util.rememberDrawerItemFocusRequesters
import com.nuvio.tv.updater.UpdateViewModel
import com.nuvio.tv.updater.ui.UpdateBannerHost
import dagger.hilt.android.AndroidEntryPoint
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

val LocalSidebarExpanded = compositionLocalOf { false }
val LocalContentFocusRequester = compositionLocalOf { FocusRequester.Default }

private const val SIDEBAR_AUTO_COLLAPSE_DELAY_MS = 3_000L

private const val MAX_SUPPORTED_FONT_SCALE = 1.15f

data class DrawerItem(
    val route: String,
    val label: String,
    val iconRes: Int? = null,
    val icon: ImageVector? = null
)

private data class MainUiPrefs(
    val theme: AppTheme = AppTheme.WHITE,
    val customThemeColors: CustomThemeColors = CustomThemeColors.Default,
    val memberAccess: MemberAccess = MemberAccess.None,
    val font: AppFont = AppFont.INTER,
    val amoledMode: Boolean = false,
    val amoledSurfacesMode: Boolean = false,
    val hasChosenLayout: Boolean? = null,
    val experienceMode: ExperienceMode? = null,
    val experienceModeLoaded: Boolean = false,
    val addonSetupSkipped: Boolean = false,
    val sidebarCollapsed: Boolean = false,
    val modernSidebarEnabled: Boolean = false,
    val modernSidebarBlurPref: Boolean = false,
    val discoverLocation: DiscoverLocation? = null,
    val smoothBringIntoViewEnabled: Boolean = true,
    val fastHorizontalNavigationEnabled: Boolean = false,
    val composeHighlighterEnabled: Boolean = false,
    val settingsUiStyle: SettingsUiStyle = SettingsUiStyle.CLASSIC,
    val cardDepthStyle: CardDepthStyle = CardDepthStyle()
)

@AndroidEntryPoint
open class MainActivity : ComponentActivity() {

    @Inject
    lateinit var themeDataStore: ThemeDataStore

    @Inject
    lateinit var layoutPreferenceDataStore: LayoutPreferenceDataStore

    @Inject
    lateinit var experienceModeDataStore: ExperienceModeDataStore

    @Inject
    lateinit var memberAccessRepository: MemberAccessRepository

    @Inject
    lateinit var addonRepository: AddonRepository

    @Inject
    lateinit var trackingProgressRefreshCoordinator: TrackingProgressRefreshCoordinator

    @Inject
    lateinit var startupSyncService: StartupSyncService

    @Inject
    lateinit var androidTvChannelSyncService: com.nuvio.tv.core.sync.androidtv.AndroidTvChannelSyncService

    @Inject
    lateinit var profileSyncService: ProfileSyncService

    @Inject
    lateinit var profileManager: ProfileManager

    @Inject
    lateinit var authManager: AuthManager

    @Inject
    lateinit var deviceSessionRegistration: DeviceSessionRegistration

    @Inject
    lateinit var authSessionNoticeDataStore: AuthSessionNoticeDataStore

    @Inject
    lateinit var appOnboardingDataStore: AppOnboardingDataStore

    @Inject
    lateinit var avatarRepository: AvatarRepository

    @Inject
    lateinit var trailerPlayerPool: com.nuvio.tv.core.player.TrailerPlayerPool

    @Inject
    lateinit var externalPlaybackTracker: com.nuvio.tv.core.player.ExternalPlaybackTracker

    @Inject
    lateinit var deepLinkHandler: DeepLinkHandler

    private val pendingDeepLinkUrl = MutableStateFlow<String?>(null)
    private val pendingLaunchIntent = MutableStateFlow<Intent?>(null)

    private lateinit var jankStats: JankStats

    /** Activity-level launcher for external video players. Survives all navigation changes. */
    private val externalPlayerLauncher = registerForActivityResult(
        com.nuvio.tv.core.player.ExternalPlayerResultContract()
    ) { result ->
        Log.d("MainActivity", "External player ActivityResult: $result")
        externalPlaybackTracker.onActivityResult(result)
    }

    /** True until the first onResume after onCreate completes. */
    private var isFirstResumeAfterCreate = false

    @OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class)
    override fun attachBaseContext(newBase: Context) {
        val tag = LocaleCache.localeTag.takeIf { it != LocaleCache.UNSET }

        if (!tag.isNullOrEmpty()) {
            val locale = Locale.forLanguageTag(tag)
            Locale.setDefault(locale)
            val config = Configuration(newBase.resources.configuration)
            config.setLocale(locale)
            super.attachBaseContext(newBase.createConfigurationContext(config))
        } else {
            // Cache not ready yet (very early cold start) — use system locale
            // The IO coroutine in Application.onCreate will finish before any activity
            // is usually created, but if not, we just use system locale until next launch
            super.attachBaseContext(newBase)
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        isFirstResumeAfterCreate = true
        window?.setBackgroundDrawable(null)

        // Wire the Activity-level launcher to the tracker
        externalPlaybackTracker.activityLauncher = externalPlayerLauncher

        PluginRuntimeHooks.onActivityCreate(this)

        window?.decorView?.post {
            val snapshot = com.nuvio.tv.core.player.DisplayCapabilities.detect(this)
            com.nuvio.tv.core.player.DisplayCapabilities.logSummary(snapshot)
        }

        // Extract extras set by the Continue Watching launcher channel preview programs.
        val launchContentId = intent?.getStringExtra("contentId")
        val launchContentType = intent?.getStringExtra("contentType")
        val launchMode = intent?.getStringExtra("launchMode")
        val launchVideoId = intent?.getStringExtra("videoId")
        val launchName = intent?.getStringExtra("name")
        val launchPoster = intent?.getStringExtra("poster")
        val launchBackdrop = intent?.getStringExtra("backdrop")
        val launchLogo = intent?.getStringExtra("logo")
        val launchSeason = intent?.getIntExtra("season", -1)?.takeIf { it >= 0 }
        val launchEpisode = intent?.getIntExtra("episode", -1)?.takeIf { it >= 0 }
        val launchEpisodeTitle = intent?.getStringExtra("episodeTitle")
        captureDeepLinkIntent(intent)

        setContent {
            var hasSelectedProfileThisSession by rememberSaveable { mutableStateOf(false) }
            var onboardingCompletedThisSession by remember { mutableStateOf(false) }
            var onboardingProfileSyncInProgress by remember { mutableStateOf(false) }
            val hasSeenAuthQrFlow = remember(appOnboardingDataStore) {
                appOnboardingDataStore.hasSeenAuthQrOnFirstLaunch.map<Boolean, Boolean?> { it }
            }
            val hasSeenAuthQrOnFirstLaunch by hasSeenAuthQrFlow.collectAsState(initial = null)
            val authState by authManager.authState.collectAsState()
            val context = LocalContext.current

            LaunchedEffect(authSessionNoticeDataStore, context) {
                authSessionNoticeDataStore.pendingNotice.collect { notice ->
                    if (notice == StartupAuthNotice.NUVIO) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.auth_notice_nuvio_logged_out),
                            Toast.LENGTH_LONG
                        ).show()
                        authSessionNoticeDataStore.consumeNotice(notice)
                    }
                }
            }

            LaunchedEffect(hasSeenAuthQrOnFirstLaunch, authState) {
                if (hasSeenAuthQrOnFirstLaunch == false && authState is AuthState.FullAccount) {
                    appOnboardingDataStore.setHasSeenAuthQrOnFirstLaunch(true)
                    onboardingCompletedThisSession = true
                }
            }

            val activeProfileId by profileManager.activeProfileId.collectAsState()
            val profiles by profileManager.profiles.collectAsState()
            val hasEverSelectedProfile by profileManager.hasEverSelectedProfile.collectAsState()
            val rememberLastProfileEnabled by profileManager.rememberLastProfileEnabled.collectAsState()
            val activeProfile = remember(activeProfileId, profiles) {
                profiles.firstOrNull { it.id == activeProfileId }
            }
            var profilePinStates by remember { mutableStateOf<Map<Int, Boolean>>(emptyMap()) }

            LaunchedEffect(authState, profiles) {
                if (authState is AuthState.FullAccount) {
                    profileSyncService.pullProfileLockStates()
                        .onSuccess { profilePinStates = it }
                        .onFailure { profilePinStates = emptyMap() }
                } else {
                    profilePinStates = emptyMap()
                }
            }

            val activeProfileHasPin = remember(activeProfileId, profilePinStates) {
                profilePinStates[activeProfileId] == true
            }

            LaunchedEffect(hasEverSelectedProfile, activeProfileHasPin, rememberLastProfileEnabled) {
                if (rememberLastProfileEnabled && hasEverSelectedProfile && !activeProfileHasPin && !hasSelectedProfileThisSession) {
                    hasSelectedProfileThisSession = true
                    if (authManager.authState.value is AuthState.FullAccount) {
                        startupSyncService.requestSyncNow()
                    }
                }
            }

            var avatarCatalog by remember { mutableStateOf(emptyList<com.nuvio.tv.data.remote.supabase.AvatarCatalogItem>()) }
            val avatarMemberAccess by memberAccessRepository.access.collectAsState()
            val hasProfileAvatarAccess = avatarMemberAccess.entitlements
                .includes(CosmeticEntitlement.PROFILE_AVATARS)

            LaunchedEffect(hasProfileAvatarAccess) {
                avatarCatalog = runCatching {
                    avatarRepository.getAvatarCatalog(hasProfileAvatarAccess)
                }
                    .getOrDefault(emptyList())
            }

            val activeProfileAvatarImageUrl = remember(activeProfile, avatarCatalog) {
                activeProfile?.avatarUrl?.takeIf { it.isNotBlank() }
                    ?: activeProfile?.avatarId?.let { avatarRepository.getAvatarImageUrl(it, avatarCatalog) }
            }

            val mainUiPrefsFlow = remember(
                themeDataStore,
                layoutPreferenceDataStore,
                experienceModeDataStore,
                memberAccessRepository
            ) {
                val activeThemeFlow = combine(
                    themeDataStore.themeSelection,
                    memberAccessRepository.access
                ) { selection, memberAccess ->
                    selection.copy(
                        theme = resolveAppTheme(selection.theme, memberAccess.entitlements, memberAccess.tier)
                    ) to memberAccess
                }
                // Group flows into two batches to reduce intermediate flow allocations.
                // Each batch uses a single combine() instead of chaining .combine() calls,
                // which avoids N intermediate flow objects and redundant emissions on startup.
                val themeAndExperienceFlow = combine(
                    activeThemeFlow,
                    themeDataStore.selectedFont,
                    themeDataStore.amoledMode,
                    themeDataStore.amoledSurfacesMode,
                    experienceModeDataStore.mode,
                ) { themeAndAccess, font, amoledMode, amoledSurfacesMode, experienceMode ->
                    MainUiPrefs(
                        theme = themeAndAccess.first.theme ?: AppTheme.WHITE,
                        customThemeColors = themeAndAccess.first.customColors,
                        memberAccess = themeAndAccess.second,
                        font = font,
                        amoledMode = amoledMode,
                        amoledSurfacesMode = amoledSurfacesMode,
                        experienceMode = experienceMode,
                        experienceModeLoaded = true,
                    )
                }
                val layoutAndFeaturesFlow = combine(
                    layoutPreferenceDataStore.hasChosenLayout,
                    layoutPreferenceDataStore.sidebarCollapsedByDefault,
                    layoutPreferenceDataStore.modernSidebarEnabled,
                    layoutPreferenceDataStore.modernSidebarBlurEnabled,
                    layoutPreferenceDataStore.discoverLocation,
                ) { hasChosenLayout, sidebarCollapsed, modernSidebarEnabled, modernSidebarBlurPref, discoverLocation ->
                    MainUiPrefs(
                        hasChosenLayout = hasChosenLayout,
                        sidebarCollapsed = sidebarCollapsed,
                        modernSidebarEnabled = modernSidebarEnabled,
                        modernSidebarBlurPref = modernSidebarBlurPref,
                        discoverLocation = discoverLocation,
                    )
                }
                val extraFeaturesFlow = combine(
                    experienceModeDataStore.addonSetupSkipped,
                    layoutPreferenceDataStore.smoothBringIntoViewEnabled,
                    layoutPreferenceDataStore.fastHorizontalNavigationEnabled,
                    layoutPreferenceDataStore.composeHighlighterEnabled,
                    themeDataStore.settingsUiStyle,
                ) { addonSetupSkipped, smoothBringIntoView, fastHorizontalNav, composeHighlighter, settingsUiStyle ->
                    MainUiPrefs(
                        addonSetupSkipped = addonSetupSkipped,
                        smoothBringIntoViewEnabled = smoothBringIntoView,
                        fastHorizontalNavigationEnabled = fastHorizontalNav,
                        composeHighlighterEnabled = composeHighlighter,
                        settingsUiStyle = settingsUiStyle,
                    )
                }
                combine(
                    themeAndExperienceFlow,
                    layoutAndFeaturesFlow,
                    extraFeaturesFlow,
                    layoutPreferenceDataStore.cardDepthStyle
                ) { themePrefs, layoutPrefs, extraPrefs, cardDepthStyle ->
                    themePrefs.copy(
                        hasChosenLayout = layoutPrefs.hasChosenLayout,
                        sidebarCollapsed = layoutPrefs.sidebarCollapsed,
                        modernSidebarEnabled = layoutPrefs.modernSidebarEnabled,
                        modernSidebarBlurPref = layoutPrefs.modernSidebarBlurPref,
                        discoverLocation = layoutPrefs.discoverLocation,
                        addonSetupSkipped = extraPrefs.addonSetupSkipped,
                        smoothBringIntoViewEnabled = extraPrefs.smoothBringIntoViewEnabled,
                        fastHorizontalNavigationEnabled = extraPrefs.fastHorizontalNavigationEnabled,
                        composeHighlighterEnabled = extraPrefs.composeHighlighterEnabled,
                        settingsUiStyle = extraPrefs.settingsUiStyle,
                        cardDepthStyle = cardDepthStyle
                    )
                }
            }
            val mainUiPrefs by mainUiPrefsFlow.collectAsState(initial = MainUiPrefs(hasChosenLayout = null))
            val installedAddons by remember(addonRepository) {
                addonRepository.getInstalledAddons()
            }.collectAsState(initial = null)
            val discoverLocation = mainUiPrefs.discoverLocation

            NuvioTheme(
                appTheme = mainUiPrefs.theme,
                customThemeColors = mainUiPrefs.customThemeColors,
                appFont = mainUiPrefs.font,
                amoledMode = mainUiPrefs.amoledMode,
                amoledSurfacesMode = mainUiPrefs.amoledSurfacesMode,
                settingsUiStyle = mainUiPrefs.settingsUiStyle
            ) {
                val defaultBringIntoViewSpec = LocalBringIntoViewSpec.current
                val bringIntoViewSpec = if (mainUiPrefs.smoothBringIntoViewEnabled) {
                    NuvioScrollDefaults.smoothScrollSpec
                } else {
                    defaultBringIntoViewSpec
                }
                val systemDensity = LocalDensity.current
                val clampedFontScaleDensity = remember(systemDensity) {
                    Density(
                        density = systemDensity.density,
                        fontScale = systemDensity.fontScale.coerceAtMost(MAX_SUPPORTED_FONT_SCALE)
                    )
                }
                CompositionLocalProvider(
                    LocalDensity provides clampedFontScaleDensity,
                    LocalBringIntoViewSpec provides bringIntoViewSpec,
                    LocalFastHorizontalNavigationEnabled provides mainUiPrefs.fastHorizontalNavigationEnabled,
                    LocalRecompositionHighlighterEnabled provides (BuildConfig.IS_DEBUG_BUILD && mainUiPrefs.composeHighlighterEnabled),
                    LocalCardDepthStyle provides mainUiPrefs.cardDepthStyle,
                    LocalMemberAccess provides mainUiPrefs.memberAccess,
                    com.nuvio.tv.core.player.LocalTrailerPlayerPool provides trailerPlayerPool
                ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    shape = RectangleShape,
                    colors = SurfaceDefaults.colors(
                        containerColor = NuvioTheme.colors.Background
                    )
                ) {
                    if (hasSeenAuthQrOnFirstLaunch == null) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(NuvioTheme.colors.Background)
                        )
                        return@Surface
                    }

                    if (authState is AuthState.Loading) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(NuvioTheme.colors.Background)
                        )
                        return@Surface
                    }

                    if (
                        hasSeenAuthQrOnFirstLaunch == false &&
                        authState !is AuthState.FullAccount &&
                        !onboardingCompletedThisSession
                    ) {
                        AuthQrSignInScreen(
                            onBackPress = { finish() },
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
                        !hasSelectedProfileThisSession && (profiles.size > 1 || activeProfileHasPin)

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
                    if (layoutChosen == null || !mainUiPrefs.experienceModeLoaded || installedAddons == null) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(NuvioTheme.colors.Background)
                        )
                        return@Surface
                    }
                    val effectiveExperienceMode = mainUiPrefs.experienceMode
                        ?: if (layoutChosen) ExperienceMode.ADVANCED else null
                    val needsExperienceSelection = effectiveExperienceMode == null
                    val needsEssentialAddonSetup =
                        effectiveExperienceMode == ExperienceMode.ESSENTIAL &&
                            installedAddons.orEmpty().isEmpty() &&
                            !mainUiPrefs.addonSetupSkipped
                    val pendingDeepLink by pendingDeepLinkUrl.collectAsState()

                    LaunchedEffect(pendingDeepLink) {
                        val url = pendingDeepLink ?: return@LaunchedEffect
                        val deepLink = DeepLinkParser.parse(url)
                        if (deepLink is AppDeepLink.AddonInstall && (needsEssentialAddonSetup || !layoutChosen)) {
                            Toast.makeText(context, context.getString(R.string.addon_installing), Toast.LENGTH_SHORT).show()
                            val installResult = deepLinkHandler.installAddon(deepLink.manifestUrl)
                            if (pendingDeepLinkUrl.value == url) {
                                pendingDeepLinkUrl.value = null
                            }
                            Toast.makeText(context, installResult.message, Toast.LENGTH_LONG).show()
                        }
                    }

                    if (needsEssentialAddonSetup) {
                        EssentialAddonSetupScreen(
                            onSkip = {
                                lifecycleScope.launch {
                                    experienceModeDataStore.setAddonSetupSkipped(true)
                                }
                            }
                        )
                        return@Surface
                    }
                    val sidebarCollapsed = mainUiPrefs.sidebarCollapsed
                    val modernSidebarEnabled = mainUiPrefs.modernSidebarEnabled
                    val modernSidebarBlurEnabled =
                        mainUiPrefs.modernSidebarBlurPref && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
                    val hideBuiltInHeadersForFloatingPill = modernSidebarEnabled && !sidebarCollapsed

                    val startDestination = when {
                        needsExperienceSelection -> Screen.ExperienceModeSelection.route
                        layoutChosen -> Screen.Home.route
                        else -> Screen.LayoutSelection.route
                    }
                    val navController = rememberNavController()
                    var optimisticRoute by remember { mutableStateOf<String?>(null) }
                    val navBackStackEntry by navController.currentBackStackEntryAsState()
                    val actualRoute = navBackStackEntry?.destination?.route
                    val currentRoute = optimisticRoute ?: actualRoute

                    LaunchedEffect(actualRoute) {
                        optimisticRoute = null
                    }

                    // Auto-play next episode for EXTERNAL players: the tracker resolves the
                    // next episode and we navigate into the same Screen.Stream auto-play route
                    // the internal onPlaybackEnded path uses. Collected from the root composable
                    // so it survives StreamScreen's self-pop and a process kill (metadata is
                    // recovered from disk and the event replayed).
                    LaunchedEffect(navController) {
                        externalPlaybackTracker.autoPlayNext.collect { next ->
                            if (!externalPlaybackTracker.claimAutoPlayNextNavigation(next)) {
                                return@collect
                            }
                            Log.d(
                                "MainActivity",
                                "autoPlayNext received: S${next.nextSeason}E${next.nextEpisode} " +
                                    "videoId=${next.nextVideoId}; navigating to Stream"
                            )
                            navController.navigate(
                                Screen.Stream.createRoute(
                                    videoId = next.nextVideoId,
                                    contentType = next.contentType,
                                    title = next.contentName,
                                    poster = next.poster,
                                    backdrop = next.backdrop,
                                    logo = next.logo,
                                    season = next.nextSeason,
                                    episode = next.nextEpisode,
                                    year = next.year,
                                    contentId = next.contentId,
                                    contentName = next.contentName,
                                    returnToDetailOnBack = next.contentType.equals("series", ignoreCase = true),
                                    profileId = next.profileId
                                )
                            ) {
                                // Replace any lingering Stream screen (e.g. the previous
                                // episode's, restored from the backstack after a process restart).
                                popUpTo(Screen.Stream.route) { inclusive = true }
                                launchSingleTop = true
                            }
                        }
                    }

                    // Navigate to content when launched from the Continue Watching channel row.
                    LaunchedEffect(navController) {
                        if (launchContentId != null && launchContentType != null && layoutChosen) {
                            if (launchMode == "stream" && launchVideoId != null && launchName != null) {
                                navController.navigate(
                                    Screen.Stream.createRoute(
                                        videoId = launchVideoId,
                                        contentType = launchContentType,
                                        title = launchName,
                                        poster = launchPoster,
                                        backdrop = launchBackdrop,
                                        logo = launchLogo,
                                        season = launchSeason,
                                        episode = launchEpisode,
                                        episodeName = launchEpisodeTitle,
                                        contentId = launchContentId,
                                        contentName = launchName,
                                        returnToDetailOnBack = launchContentType.equals("series", ignoreCase = true),
                                        returnToHomeOnBack = true
                                    )
                                )
                            } else {
                                navController.navigate(
                                    Screen.Detail.createRoute(
                                        itemId = launchContentId,
                                        itemType = launchContentType
                                    )
                                )
                            }
                        }
                    }

                    val pendingLaunch by pendingLaunchIntent.collectAsState()
                    LaunchedEffect(navController, layoutChosen, pendingLaunch) {
                        val intent = pendingLaunch ?: return@LaunchedEffect
                        if (!layoutChosen) return@LaunchedEffect
                        pendingLaunchIntent.value = null
                        val contentId = intent.getStringExtra("contentId") ?: return@LaunchedEffect
                        val contentType = intent.getStringExtra("contentType") ?: return@LaunchedEffect
                        val videoId = intent.getStringExtra("videoId")
                        val name = intent.getStringExtra("name")
                        if (videoId != null && name != null) {
                            navController.navigate(
                                Screen.Stream.createRoute(
                                    videoId = videoId,
                                    contentType = contentType,
                                    title = name,
                                    poster = intent.getStringExtra("poster"),
                                    backdrop = intent.getStringExtra("backdrop"),
                                    logo = intent.getStringExtra("logo"),
                                    season = intent.getIntExtra("season", -1).takeIf { it >= 0 },
                                    episode = intent.getIntExtra("episode", -1).takeIf { it >= 0 },
                                    episodeName = intent.getStringExtra("episodeTitle"),
                                    contentId = contentId,
                                    contentName = name,
                                    returnToDetailOnBack = contentType.equals("series", ignoreCase = true),
                                    returnToHomeOnBack = true
                                )
                            )
                        } else {
                            navController.navigate(
                                Screen.Detail.createRoute(
                                    itemId = contentId,
                                    itemType = contentType
                                )
                            )
                        }
                    }

                    LaunchedEffect(navController, layoutChosen, pendingDeepLink) {
                        val url = pendingDeepLink ?: return@LaunchedEffect
                        if (!layoutChosen) return@LaunchedEffect
                        when (val deepLink = DeepLinkParser.parse(url)) {
                            is AppDeepLink.Meta -> {
                                pendingDeepLinkUrl.value = null
                                navController.navigate(
                                    Screen.Detail.createRoute(
                                        itemId = deepLink.id,
                                        itemType = deepLink.type
                                    )
                                ) {
                                    launchSingleTop = true
                                }
                            }
                            is AppDeepLink.AddonInstall -> {
                                navController.navigate(Screen.AddonManager.route) {
                                    launchSingleTop = true
                                }
                                Toast.makeText(context, context.getString(R.string.addon_installing), Toast.LENGTH_SHORT).show()
                                val installResult = deepLinkHandler.installAddon(deepLink.manifestUrl)
                                if (pendingDeepLinkUrl.value == url) {
                                    pendingDeepLinkUrl.value = null
                                }
                                Toast.makeText(context, installResult.message, Toast.LENGTH_LONG).show()
                            }
                            null -> {
                                pendingDeepLinkUrl.value = null
                            }
                        }
                    }

                    val view = LocalView.current
                    LaunchedEffect(currentRoute) {
                        val holder = PerformanceMetricsState.getHolderForHierarchy(view)
                        if (currentRoute != null) {
                            holder.state?.putState("Screen", currentRoute)
                        }
                    }

                    LaunchedEffect(discoverLocation, currentRoute) {
                        if (discoverLocation == null) return@LaunchedEffect
                        val onDiscoverRoute = currentRoute == Screen.Discover.route ||
                            currentRoute?.startsWith("${Screen.Discover.route}/") == true
                        if (discoverLocation == DiscoverLocation.OFF && onDiscoverRoute) {
                            navController.navigate(Screen.Home.route) {
                                popUpTo(navController.graph.startDestinationId) { saveState = false }
                                launchSingleTop = true
                            }
                        }
                    }

                    val rootRoutes = remember(discoverLocation) {
                        buildSet {
                            add(Screen.Home.route)
                            add(Screen.Search.route)
                            add(Screen.Library.route)
                            add(Screen.Settings.route)
                            if (discoverLocation == DiscoverLocation.IN_SIDEBAR) {
                                add(Screen.Discover.route)
                            }
                        }
                    }

                    val strNavHome = stringResource(R.string.nav_home)
                    val strNavDiscover = stringResource(R.string.nav_discover)
                    val strNavSearch = stringResource(R.string.nav_search)
                    val strNavLibrary = stringResource(R.string.nav_library)
                    val strNavSettings = stringResource(R.string.nav_settings)
                    val drawerItems = remember(
                        strNavHome,
                        strNavDiscover,
                        strNavSearch,
                        strNavLibrary,
                        strNavSettings,
                        discoverLocation
                    ) {
                        buildList {
                            add(
                                DrawerItem(
                                    route = Screen.Home.route,
                                    label = strNavHome,
                                    icon = Icons.Default.Home
                                )
                            )
                            if (discoverLocation == DiscoverLocation.IN_SIDEBAR) {
                                add(
                                    DrawerItem(
                                        route = Screen.Discover.route,
                                        label = strNavDiscover,
                                        icon = Icons.Default.Explore
                                    )
                                )
                            }
                            add(
                                DrawerItem(
                                    route = Screen.Search.route,
                                    label = strNavSearch,
                                    iconRes = R.raw.sidebar_search
                                )
                            )
                            add(
                                DrawerItem(
                                    route = Screen.Library.route,
                                    label = strNavLibrary,
                                    iconRes = R.raw.sidebar_library
                                )
                            )
                            add(
                                DrawerItem(
                                    route = Screen.Settings.route,
                                    label = strNavSettings,
                                    iconRes = R.raw.sidebar_settings
                                )
                            )
                        }
                    }
                    val selectedDrawerRoute = drawerItems.firstOrNull { item ->
                        currentRoute == item.route || currentRoute?.startsWith("${item.route}/") == true
                    }?.route
                    val selectedDrawerItem = drawerItems.firstOrNull { it.route == selectedDrawerRoute } ?: drawerItems.first()

                    val confirmExitEnabled by profileManager.confirmExitEnabled.collectAsState()
                    var backPressedOnce by remember { mutableStateOf(false) }
                    LaunchedEffect(backPressedOnce) {
                        if (backPressedOnce) {
                            delay(2000L)
                            backPressedOnce = false
                        }
                    }
                    val handleExitApp: () -> Unit = {
                        if (!confirmExitEnabled || backPressedOnce) {
                            finishAffinity()
                            finishAndRemoveTask()
                            if (confirmExitEnabled) {
                                // Kill the process to free RAM on low-memory devices.
                                android.os.Process.killProcess(android.os.Process.myPid())
                            }
                        } else {
                            backPressedOnce = true
                            Toast.makeText(this@MainActivity, getString(R.string.confirm_exit_toast), Toast.LENGTH_SHORT).show()
                        }
                    }

                    val updateViewModel: UpdateViewModel = hiltViewModel(this@MainActivity)
                    val updateState by updateViewModel.uiState.collectAsState()
                    val updateBannerState = updateState.copy(
                        showBanner = updateState.showBanner && currentRoute?.startsWith("player/") != true
                    )

                    UpdateBannerHost(
                        state = updateBannerState,
                        onDismissBanner = updateViewModel::dismissBanner,
                        onDownload = updateViewModel::downloadUpdate,
                        onInstall = updateViewModel::installUpdateOrRequestPermission,
                        onDismissUnknownSources = updateViewModel::dismissUnknownSourcesDialog,
                        onOpenUnknownSources = updateViewModel::openUnknownSourcesSettings,
                        onFeedbackShown = updateViewModel::consumeFeedbackMessage
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            if (modernSidebarEnabled) {
                                ModernSidebarScaffold(
                                    longPressBackHeld = longPressBackHeld,
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
                                    activeProfileAvatarImageUrl = activeProfileAvatarImageUrl,
                                    showProfileSelector = profiles.size > 1,
                                    onSwitchProfile = { hasSelectedProfileThisSession = false },
                                    onNavigate = { optimisticRoute = it },
                                    onExitApp = handleExitApp
                                )
                            } else {
                                LegacySidebarScaffold(
                                    longPressBackHeld = longPressBackHeld,
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
                                    activeProfileAvatarImageUrl = activeProfileAvatarImageUrl,
                                    showProfileSelector = profiles.size > 1,
                                    onSwitchProfile = { hasSelectedProfileThisSession = false },
                                    onNavigate = { optimisticRoute = it },
                                    onExitApp = handleExitApp
                                )
                            }

                            val autoNextOverlay by externalPlaybackTracker.autoNextOverlay.collectAsState()
                            autoNextOverlay?.let { ov ->
                                com.nuvio.tv.ui.screens.player.LoadingOverlay(
                                    visible = true,
                                    backdropUrl = ov.backdrop,
                                    logoUrl = ov.logo,
                                    title = ov.title,
                                    message = ov.message ?: stringResource(R.string.external_auto_next_loading),
                                    progress = ov.progress,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    }
                }
            }
            }
        }

        jankStats = JankStats.createAndTrack(window) { frameData ->
            if (frameData.isJank) {
                Log.w(
                    "JankStats",
                    "JANK: ${frameData.frameDurationUiNanos / 1_000_000}ms | states: ${frameData.states}"
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::jankStats.isInitialized) jankStats.isTrackingEnabled = true
        memberAccessRepository.refreshIfStale()
        lifecycleScope.launch {
            deviceSessionRegistration.requestForegroundRegistration()
            startupSyncService.requestForegroundSync()
        }
        lifecycleScope.launch {
            val refreshIntent = if (isFirstResumeAfterCreate) {
                isFirstResumeAfterCreate = false
                TrackingRefreshIntent.INVALIDATED
            } else {
                TrackingRefreshIntent.AUTOMATIC
            }
            trackingProgressRefreshCoordinator.refreshConnected(refreshIntent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        captureDeepLinkIntent(intent)
        captureLaunchIntent(intent)
    }

    private fun captureDeepLinkIntent(intent: Intent?) {
        val url = intent?.dataString?.trim()?.takeIf(String::isNotBlank) ?: return
        pendingDeepLinkUrl.value = url
    }

    private fun captureLaunchIntent(intent: Intent?) {
        val contentId = intent?.getStringExtra("contentId") ?: return
        val launchMode = intent.getStringExtra("launchMode") ?: return
        if (launchMode != "stream") return
        pendingLaunchIntent.value = intent
    }

    override fun onPause() {
        super.onPause()
        if (::jankStats.isInitialized) jankStats.isTrackingEnabled = false
    }

    // Intercept Back at the Activity level, before any Compose BackHandler, so the auto-next loader
    // can always be dismissed. Compose back-dispatch ordering kept putting the destination screen's
    // handler above the loader's, so Back never reached it.
    // Tracks whether a long-press Back sequence is in progress. When true, all Back
    // key events are consumed at the Activity level so that repeated DOWN events from a
    // held Back key don't cascade through Compose BackHandlers (e.g. opening the sidebar
    // and then immediately exiting the app).
    val longPressBackHeld = mutableStateOf(false)

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (longPressBackHeld.value) {
                if (event.action == KeyEvent.ACTION_UP) longPressBackHeld.value = false
                return true
            }
        }
        if (event.keyCode == KeyEvent.KEYCODE_BACK &&
            externalPlaybackTracker.autoNextOverlay.value != null
        ) {
            if (event.action == KeyEvent.ACTION_UP) {
                Log.d("ExtAutoNext", "dispatchKeyEvent BACK -> dismissAutoNextOverlay (loader showing)")
                externalPlaybackTracker.dismissAutoNextOverlay()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onStart() {
        // Returning from an external player: raise the auto-next loader before the player's
        // result is dispatched and before the window repaints, so the transition shows the
        // loader instantly with no episode-list flash. No-op unless a series episode is being
        // tracked; onActivityResult keeps it for a completion or dismisses it otherwise.
        externalPlaybackTracker.raiseAutoNextOverlayOnReturn()
        super.onStart()
        startupSyncService.startPeriodicSurfacePulls()
        androidTvChannelSyncService.onForegroundChanged(true)
    }

    override fun onStop() {
        externalPlaybackTracker.onExternalPlayerCoveredApp()
        super.onStop()
        startupSyncService.stopPeriodicSurfacePulls()
        // App going to background (e.g. user returning to the launcher): reconcile the
        // Continue Watching channel once so Projectivy repaints it with fresh progress.
        androidTvChannelSyncService.onForegroundChanged(false)
    }

    override fun onDestroy() {
        super.onDestroy()
        PluginRuntimeHooks.onActivityDestroy()
    }
}

@Composable
private fun SidebarFocusRecoveryEffect(
    drawerItems: List<DrawerItem>,
    selectedDrawerRoute: String?,
    drawerItemFocusRequesters: Map<String, FocusRequester>,
    sidebarOwnsFocus: Boolean
) {
    LaunchedEffect(drawerItems, sidebarOwnsFocus, selectedDrawerRoute) {
        if (!sidebarOwnsFocus) {
            return@LaunchedEffect
        }
        if (selectedDrawerRoute != null && drawerItems.any { it.route == selectedDrawerRoute }) {
            return@LaunchedEffect
        }
        val fallbackRoute = drawerItems.firstOrNull()?.route ?: return@LaunchedEffect
        val requester = drawerItemFocusRequesters[fallbackRoute] ?: return@LaunchedEffect
        repeat(2) { withFrameNanos { } }
        runCatching { requester.requestFocus() }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LegacySidebarScaffold(
    longPressBackHeld: MutableState<Boolean>,
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
    activeProfileAvatarImageUrl: String?,
    showProfileSelector: Boolean,
    onSwitchProfile: () -> Unit,
    onNavigate: (String) -> Unit,
    onExitApp: () -> Unit
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val drawerItemFocusRequesters = rememberDrawerItemFocusRequesters(drawerItems)
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val showSidebar = currentRoute in rootRoutes

    LaunchedEffect(currentRoute) {
        longPressBackHeld.value = false
        drawerState.setValue(DrawerValue.Closed)
    }

    val sidebarTokens = NuvioComponents.tokens.sidebar
    val closedDrawerWidth = if (sidebarCollapsed) NuvioTheme.spacing.none else sidebarTokens.legacyCollapsedWidth
    val openDrawerWidth = sidebarTokens.legacyExpandedWidth
    val openDrawerItemWidth = sidebarTokens.itemWidth

    val focusManager = LocalFocusManager.current
    val isRtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    val contentFocusRequester = remember { FocusRequester() }
    var pendingContentFocusTransfer by remember { mutableStateOf(false) }
    var pendingSidebarFocusRequest by remember { mutableStateOf(false) }
    // Bumped on every key event the drawer sees so the auto-collapse timer
    // resets while the user navigates between drawer items.
    var legacyDrawerInteractionVersion by remember { mutableStateOf(0) }


    BackHandler(enabled = currentRoute in rootRoutes && drawerState.currentValue == DrawerValue.Closed) {
        pendingSidebarFocusRequest = true
        drawerState.setValue(DrawerValue.Open)
    }

    BackHandler(enabled = currentRoute in rootRoutes && drawerState.currentValue == DrawerValue.Open) {
        if (longPressBackHeld.value) return@BackHandler
        onExitApp()
    }

    LaunchedEffect(drawerState.currentValue, pendingContentFocusTransfer) {
        if (!pendingContentFocusTransfer || drawerState.currentValue != DrawerValue.Closed) {
            return@LaunchedEffect
        }
        repeat(2) { withFrameNanos { } }
        runCatching { contentFocusRequester.requestFocus() }
        pendingContentFocusTransfer = false
    }

    LaunchedEffect(drawerState.currentValue, selectedDrawerRoute, showSidebar, pendingSidebarFocusRequest) {
        if (!showSidebar || !pendingSidebarFocusRequest || drawerState.currentValue != DrawerValue.Open) {
            return@LaunchedEffect
        }
        val targetRoute = selectedDrawerRoute ?: drawerItems.firstOrNull()?.route ?: run {
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

    SidebarFocusRecoveryEffect(
        drawerItems = drawerItems,
        selectedDrawerRoute = selectedDrawerRoute,
        drawerItemFocusRequesters = drawerItemFocusRequesters,
        sidebarOwnsFocus = showSidebar && drawerState.currentValue == DrawerValue.Open
    )

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = { drawerValue ->
            if (showSidebar) {
                val drawerWidth = if (drawerValue == DrawerValue.Open) openDrawerWidth else closedDrawerWidth
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(drawerWidth)
                        .background(NuvioTheme.colors.Background)
                        .padding(NuvioTheme.spacing.card.outer)
                        .selectableGroup()
                        .onPreviewKeyEvent { keyEvent ->
                            if (keyEvent.type == KeyEventType.KeyDown) {
                                legacyDrawerInteractionVersion++
                            }
                            val closeKey = if (isRtl) Key.DirectionLeft else Key.DirectionRight
                            if (keyEvent.key == closeKey && keyEvent.type == KeyEventType.KeyDown) {
                                drawerState.setValue(DrawerValue.Closed)
                                pendingContentFocusTransfer = false
                                true
                            } else {
                                false
                            }
                        }
                ) {
                    val isExpanded = drawerValue == DrawerValue.Open
                    val itemWidth by animateDpAsState(
                        targetValue = if (isExpanded) openDrawerItemWidth else NuvioTheme.sizes.avatars.md,
                        animationSpec = tween(durationMillis = NuvioMotion.tokens.durations.fast, easing = NuvioMotion.tokens.easings.standard),
                        label = "legacySidebarItemWidth"
                    )

                    if (isExpanded) {
                        Column(
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .fillMaxWidth()
                        ) {
                            Spacer(modifier = Modifier.height(30.dp))
                            if (showProfileSelector && activeProfileName.isNotEmpty()) {
                                var isProfileFocused by remember { mutableStateOf(false) }
                                val profileItemShape = NuvioTheme.shapes.navItem
                                val profileLeadingInset = NuvioTheme.spacing.lg + NuvioTheme.spacing.xxs
                                val profileAvatarSize = NuvioTheme.sizes.sidebar.leadingVisual
                                val profileLabelStart = 60.dp
                                val profileGapAfterAvatar =
                                    (profileLabelStart - profileLeadingInset - profileAvatarSize).coerceAtLeast(NuvioTheme.spacing.none)
                                val profileBgColor = if (isProfileFocused) NuvioTheme.colors.FocusBackground else Color.Transparent
                                Box(
                                    modifier = Modifier.fillMaxWidth(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .width(itemWidth)
                                            .height(sidebarTokens.itemHeight)
                                            .background(color = profileBgColor, shape = profileItemShape)
                                            .onFocusChanged { isProfileFocused = it.isFocused }
                                            .clickable {
                                                onSwitchProfile()
                                                drawerState.setValue(DrawerValue.Closed)
                                            },
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Spacer(modifier = Modifier.width(profileLeadingInset))
                                        ProfileAvatarCircle(
                                            name = activeProfileName,
                                            colorHex = activeProfileColorHex,
                                            size = profileAvatarSize,
                                            avatarImageUrl = activeProfileAvatarImageUrl,
                                            imageCrossfade = false
                                        )
                                        Spacer(modifier = Modifier.width(profileGapAfterAvatar))
                                        Text(
                                            text = activeProfileName,
                                            color = if (isProfileFocused) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextSecondary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            textAlign = TextAlign.Start,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }
                            } else {
                                BrandWordmark(
                                    contentDescription = stringResource(R.string.app_name),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(42.dp)
                                )
                            }
                        }
                    }

                    Column(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .offset(y = 28.dp)
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        horizontalAlignment = Alignment.Start
                    ) {
                        drawerItems.forEach { item ->
                            key(item.route) {
                                LegacySidebarButton(
                                    label = item.label,
                                    iconRes = item.iconRes,
                                    icon = item.icon,
                                    selected = selectedDrawerRoute == item.route,
                                    expanded = isExpanded,
                                    onClick = {
                                        keyboardController?.hide()
                                        onNavigate(item.route)
                                        navigateToDrawerRoute(
                                            navController = navController,
                                            currentRoute = currentRoute,
                                            targetRoute = item.route
                                        )
                                        drawerState.setValue(DrawerValue.Closed)
                                        pendingContentFocusTransfer = currentRoute == item.route
                                    },
                                    modifier = Modifier.focusRequester(
                                        drawerItemFocusRequesters.getValue(item.route)
                                    )
                                        .width(itemWidth)
                                        .offset(x = NuvioTheme.spacing.md)
                                )
                        }
                    }
                }
            }
        }
        }
    ) {
        val contentStartPadding by animateDpAsState(
            targetValue = if (showSidebar && !sidebarCollapsed) {
                NuvioLayout.tokens.sidebarContentOffset
            } else {
                NuvioTheme.spacing.none
            },
            animationSpec = tween(NuvioMotion.tokens.durations.medium),
            label = "contentStartPadding"
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = contentStartPadding)
                .onPreviewKeyEvent { keyEvent ->
                    // Long-press Back on a root route directly opens the sidebar,
                    // bypassing the "scroll row to start" BackHandler in home content.
                    if (keyEvent.key == Key.Back) {
                        if (
                            keyEvent.type == KeyEventType.KeyDown &&
                            showSidebar &&
                            drawerState.currentValue == DrawerValue.Closed &&
                            currentRoute in rootRoutes &&
                            keyEvent.nativeKeyEvent.isLongPress
                        ) {
                            if (!longPressBackHeld.value) {
                                longPressBackHeld.value = true
                                pendingSidebarFocusRequest = true
                                drawerState.setValue(DrawerValue.Open)
                            }
                            return@onPreviewKeyEvent true
                        }
                        if (longPressBackHeld.value) {
                            if (keyEvent.type == KeyEventType.KeyUp) longPressBackHeld.value = false
                            return@onPreviewKeyEvent true
                        }
                    }
                    false
                }
                .onKeyEvent { keyEvent ->
                    val openKey = if (isRtl) Key.DirectionRight else Key.DirectionLeft
                    if (
                        showSidebar &&
                        drawerState.currentValue == DrawerValue.Closed &&
                        keyEvent.type == KeyEventType.KeyDown &&
                        keyEvent.key == openKey
                    ) {
                        if (focusManager.moveFocus(if (isRtl) FocusDirection.Right else FocusDirection.Left)) {
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
            CompositionLocalProvider(
                LocalSidebarExpanded provides (drawerState.currentValue == DrawerValue.Open),
                LocalContentFocusRequester provides contentFocusRequester
            ) {
                NuvioNavHost(
                    navController = navController,
                    startDestination = startDestination,
                    hideBuiltInHeaders = hideBuiltInHeaders
                )
            }
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
    val itemShape = NuvioTheme.shapes.navItem
    val backgroundColor by animateColorAsState(
        targetValue = when {
            isFocused -> NuvioTheme.colors.FocusBackground
            expanded && selected -> NuvioTheme.colors.Secondary
            else -> Color.Transparent
        },
        label = "legacySidebarItemBackground"
    )
    val contentColor by animateColorAsState(
        targetValue = when {
            isFocused -> NuvioTheme.colors.TextPrimary
            expanded && selected -> NuvioTheme.colors.OnSecondary
            else -> NuvioTheme.colors.TextSecondary
        },
        label = "legacySidebarItemContent"
    )
    val iconTint by animateColorAsState(
        targetValue = when {
            isFocused -> NuvioTheme.colors.TextPrimary
            expanded && selected -> NuvioTheme.colors.OnSecondary
            selected -> NuvioTheme.colors.Secondary
            !expanded -> NuvioTheme.colors.TextTertiary
            else -> NuvioTheme.colors.TextSecondary
        },
        label = "legacySidebarItemIconTint"
    )
    val selectedCollapsedIconBrush = if (selected && !expanded) {
        NuvioTheme.palette.accentBrush()
    } else {
        null
    }
    val itemScale by animateFloatAsState(
        targetValue = if (isFocused && expanded) 1.1f else 1f,
        animationSpec = tween(durationMillis = NuvioMotion.tokens.durations.fast, easing = NuvioMotion.tokens.easings.standard),
        label = "legacySidebarItemScale"
    )

    Card(
        onClick = onClick,
        modifier = modifier
            .height(NuvioComponents.tokens.sidebar.itemHeight)
            .graphicsLayer {
                scaleX = itemScale
                scaleY = itemScale
                transformOrigin = TransformOrigin.Center
            }
            .focusProperties { canFocus = expanded }
            .onFocusChanged { isFocused = it.hasFocus },
        colors = CardDefaults.colors(
            containerColor = backgroundColor,
            focusedContainerColor = backgroundColor,
        ),
        border = CardDefaults.border(
            border = androidx.tv.material3.Border.None,
            focusedBorder = androidx.tv.material3.Border(
                border = androidx.compose.foundation.BorderStroke(NuvioStrokes.tokens.thin, Color.Transparent),
                shape = itemShape
            )
        ),
        shape = CardDefaults.shape(shape = itemShape),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
        DrawerItemIcon(
            iconRes = iconRes,
            icon = icon,
            tint = iconTint,
            brush = selectedCollapsedIconBrush,
            modifier = Modifier
                .size(NuvioComponents.tokens.sidebar.iconSize)
                .align(Alignment.CenterStart)
                .offset(x = 13.dp)
        )
        if (expanded) {
            com.nuvio.tv.ui.components.AutoResizeText(
                text = label,
                color = contentColor,
                textAlign = TextAlign.Start,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 54.dp, end = 14.dp)
            )
        }
    }
}
}

@Composable
private fun ModernSidebarScaffold(
    longPressBackHeld: MutableState<Boolean>,
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
    activeProfileAvatarImageUrl: String?,
    showProfileSelector: Boolean,
    onSwitchProfile: () -> Unit,
    onNavigate: (String) -> Unit,
    onExitApp: () -> Unit
) {
    val showSidebar = currentRoute in rootRoutes
    val sidebarTokens = NuvioComponents.tokens.sidebar
    val collapsedSidebarWidth = if (sidebarCollapsed) NuvioTheme.spacing.none else sidebarTokens.collapsedWidth
    val openSidebarWidth = sidebarTokens.expandedWidth

    val focusManager = LocalFocusManager.current
    val isRtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    val contentFocusRequester = remember { FocusRequester() }
    val drawerItemFocusRequesters = rememberDrawerItemFocusRequesters(drawerItems)
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current

    var isSidebarExpanded by remember { mutableStateOf(false) }
    var sidebarCollapsePending by remember { mutableStateOf(false) }
    var pendingContentFocusTransfer by remember { mutableStateOf(false) }
    var pendingSidebarFocusRequest by remember { mutableStateOf(false) }
    var focusedDrawerIndex by remember { mutableStateOf(-1) }
    var isFloatingPillIconOnly by remember { mutableStateOf(false) }
    var pillExpandRequestCount by remember { mutableIntStateOf(0) }
    val keepFloatingPillExpanded = false
    val keepSidebarFocusDuringCollapse =
        isSidebarExpanded || sidebarCollapsePending || pendingContentFocusTransfer
    val hasSidebarProfileItem = showProfileSelector && activeProfileName.isNotEmpty()
    val sidebarTopBoundaryIndex = if (hasSidebarProfileItem) drawerItems.size else 0

    LaunchedEffect(showSidebar) {
        if (!showSidebar) {
            isSidebarExpanded = false
            sidebarCollapsePending = false
            pendingContentFocusTransfer = false
            pendingSidebarFocusRequest = false
            isFloatingPillIconOnly = false
        }
    }

    // Collapse sidebar when navigating between root routes (e.g. Settings -> Home via Back)
    LaunchedEffect(currentRoute) {
        if (isSidebarExpanded && showSidebar) {
            sidebarCollapsePending = true
        }
    }

    LaunchedEffect(keepFloatingPillExpanded, showSidebar) {
        if (!showSidebar || keepFloatingPillExpanded) {
            isFloatingPillIconOnly = false
        }
    }

    // Expand pill label briefly after navigating to a different root route
    LaunchedEffect(selectedDrawerRoute) {
        if (showSidebar && !isSidebarExpanded) {
            isFloatingPillIconOnly = false
            pillExpandRequestCount++
        }
    }

    BackHandler(enabled = currentRoute in rootRoutes && !isSidebarExpanded && !sidebarCollapsePending) {
        isSidebarExpanded = true
        sidebarCollapsePending = false
        pendingSidebarFocusRequest = true
    }

    BackHandler(enabled = currentRoute in rootRoutes && isSidebarExpanded && !sidebarCollapsePending) {
        if (longPressBackHeld.value) return@BackHandler
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

    // Auto-collapse the expanded sidebar after a short period of inactivity.
    // The timer resets every time focus moves between drawer items, so the

    // Auto-collapse the floating pill back to icon-only when the user reveals
    // its label (DPAD UP from content) and then leaves it idle. The DPAD DOWN
    // path already collapses it instantly, this just covers the case where the
    // user releases UP and walks away.
    LaunchedEffect(isFloatingPillIconOnly, keepFloatingPillExpanded, showSidebar, isSidebarExpanded, pillExpandRequestCount) {
        if (!showSidebar || isFloatingPillIconOnly || keepFloatingPillExpanded || isSidebarExpanded) {
            return@LaunchedEffect
        }
        delay(SIDEBAR_AUTO_COLLAPSE_DELAY_MS)
        isFloatingPillIconOnly = true
    }

    val sidebarVisible = showSidebar && (isSidebarExpanded || !sidebarCollapsed)
    val sidebarHazeState = remember { HazeState() }
    // Panel is always laid out at full expanded width; open/close is
    // purely a graphicsLayer transform (scale + alpha) so Compose never
    // re-layouts and haze doesn't re-render blur every frame.
    val sidebarWidth = if (sidebarVisible) openSidebarWidth else collapsedSidebarWidth
    val animationDuration = if (sidebarVisible) 400 else 300
    val animationEasing = if (sidebarVisible) FastOutSlowInEasing else FastOutLinearInEasing

    val sidebarSlideX = NuvioTheme.spacing.none
    val sidebarSurfaceAlpha by animateFloatAsState(
        targetValue = if (isSidebarExpanded) 1f else 0f,
        animationSpec = tween(durationMillis = if (isSidebarExpanded) 280 else 200, easing = animationEasing),
        label = "sidebarSurfaceAlpha"
    )
    val shouldApplySidebarHaze = showSidebar && modernSidebarBlurEnabled
    val sidebarTransition = updateTransition(
        targetState = isSidebarExpanded,
        label = "sidebarTransition"
    )
    // Labels and icons are always at full size — the panel is rendered
    // complete and the open/close animation is purely graphicsLayer.
    val sidebarLabelAlpha = 1f
    val sidebarExpandProgress by sidebarTransition.animateFloat(
        transitionSpec = {
            if (targetState) {
                tween(durationMillis = NuvioMotion.tokens.durations.sidebarPanelIn, easing = FastOutSlowInEasing)
            } else {
                tween(durationMillis = NuvioMotion.tokens.durations.sidebarPanelOut, easing = LinearOutSlowInEasing)
            }
        },
        label = "sidebarExpandProgress"
    ) { expanded ->
        if (expanded) 1f else 0f
    }

    // derivedStateOf prevents per-frame recomposition — only triggers when the boolean crosses the threshold
    val sidebarBlocksContentKeys by remember { derivedStateOf { sidebarExpandProgress > 0.2f } }
    val sidebarShowExpandedPanel by remember { derivedStateOf { sidebarExpandProgress > 0.01f } }
    val sidebarShowCollapsedPill by remember { derivedStateOf { sidebarExpandProgress < 0.98f } }

    val sidebarIconScale = 1f
    val sidebarBloomScale = 1f
    val sidebarDeflateOffsetX = NuvioTheme.spacing.none
    val sidebarDeflateOffsetY = NuvioTheme.spacing.none

    LaunchedEffect(isSidebarExpanded, sidebarCollapsePending, pendingContentFocusTransfer, showSidebar) {
        if (!showSidebar || !pendingContentFocusTransfer || isSidebarExpanded || sidebarCollapsePending) {
            return@LaunchedEffect
        }
        repeat(2) { withFrameNanos { } }
        runCatching { contentFocusRequester.requestFocus() }
        pendingContentFocusTransfer = false
    }

    LaunchedEffect(isSidebarExpanded, pendingSidebarFocusRequest, showSidebar, selectedDrawerRoute) {
        if (!showSidebar || !pendingSidebarFocusRequest || !isSidebarExpanded) {
            return@LaunchedEffect
        }
        val targetRoute = selectedDrawerRoute ?: drawerItems.firstOrNull()?.route ?: run {
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

    SidebarFocusRecoveryEffect(
        drawerItems = drawerItems,
        selectedDrawerRoute = selectedDrawerRoute,
        drawerItemFocusRequesters = drawerItemFocusRequesters,
        sidebarOwnsFocus = showSidebar && isSidebarExpanded
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { keyEvent ->
                // Consume all Back key events after long-press until released,
                // preventing the exit-app BackHandler from firing during the hold.
                if (longPressBackHeld.value && keyEvent.key == Key.Back) {
                    if (keyEvent.type == KeyEventType.KeyUp) longPressBackHeld.value = false
                    return@onPreviewKeyEvent true
                }
                false
            }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (shouldApplySidebarHaze) Modifier.hazeSource(state = sidebarHazeState)
                    else Modifier
                )
                .onPreviewKeyEvent { keyEvent ->
                    // Long-press Back on a root route directly opens the sidebar,
                    // bypassing the "scroll row to start" BackHandler in home content.
                    if (keyEvent.key == Key.Back) {
                        if (
                            keyEvent.type == KeyEventType.KeyDown &&
                            showSidebar &&
                            !isSidebarExpanded &&
                            !sidebarCollapsePending &&
                            currentRoute in rootRoutes &&
                            keyEvent.nativeKeyEvent.isLongPress
                        ) {
                            if (!longPressBackHeld.value) {
                                longPressBackHeld.value = true
                                isSidebarExpanded = true
                                sidebarCollapsePending = false
                                pendingSidebarFocusRequest = true
                            }
                            return@onPreviewKeyEvent true
                        }
                        if (longPressBackHeld.value) {
                            if (keyEvent.type == KeyEventType.KeyUp) longPressBackHeld.value = false
                            return@onPreviewKeyEvent true
                        }
                    }
                    if (
                        isSidebarExpanded &&
                        !sidebarCollapsePending &&
                        sidebarBlocksContentKeys &&
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
                                Key.DirectionUp -> {
                                    isFloatingPillIconOnly = false
                                    pillExpandRequestCount++
                                }
                                else -> Unit
                            }
                        }
                        val openKey = if (isRtl) Key.DirectionRight else Key.DirectionLeft
                        if (keyEvent.key == openKey) {
                            if (focusManager.moveFocus(if (isRtl) FocusDirection.Right else FocusDirection.Left)) {
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
            CompositionLocalProvider(
                LocalSidebarExpanded provides isSidebarExpanded,
                LocalContentFocusRequester provides contentFocusRequester
            ) {
                NuvioNavHost(
                    navController = navController,
                    startDestination = startDestination,
                    hideBuiltInHeaders = hideBuiltInHeaders
                )
            }
        }

        if (showSidebar && (sidebarVisible || sidebarShowExpandedPanel)) {
            val panelShape = RoundedCornerShape(sidebarTokens.panelRadius)
            val showExpandedPanel = isSidebarExpanded || sidebarShowExpandedPanel

            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .width(openSidebarWidth)
                    .padding(start = NuvioTheme.spacing.lg - NuvioTheme.spacing.xxs, top = NuvioTheme.spacing.lg, bottom = NuvioTheme.spacing.md, end = NuvioTheme.spacing.sm)
                    .graphicsLayer {
                        val progress = sidebarExpandProgress
                        alpha = sidebarSurfaceAlpha
                        val s = 0.92f + 0.08f * progress
                        scaleX = s
                        scaleY = s
                        transformOrigin = TransformOrigin(0f, 0f)
                    }
                    .selectableGroup()
                    .onPreviewKeyEvent { keyEvent ->
                        if (!isSidebarExpanded || keyEvent.type != KeyEventType.KeyDown) {
                            return@onPreviewKeyEvent false
                        }
                        when (keyEvent.key) {
                            Key.DirectionUp -> {
                                if (focusedDrawerIndex == sidebarTopBoundaryIndex) {
                                    true
                                } else {
                                    // Move focus within the sidebar; consume unconditionally
                                    // so focus never escapes into the content behind.
                                    focusManager.moveFocus(FocusDirection.Up)
                                    true
                                }
                            }

                            Key.DirectionDown -> {
                                if (focusedDrawerIndex == drawerItems.lastIndex) {
                                    // Already at the bottom drawer item — stay put.
                                    true
                                } else if (focusedDrawerIndex == drawerItems.size && hasSidebarProfileItem) {
                                    // Profile → first drawer item: skip moveFocus (the
                                    // Spacer gap causes it to land in content) and
                                    // request the first drawer item directly.
                                    drawerItems.firstOrNull()?.route?.let { route ->
                                        drawerItemFocusRequesters[route]?.requestFocus()
                                    }
                                    true
                                } else {
                                    focusManager.moveFocus(FocusDirection.Down)
                                    true
                                }
                            }

                            Key.DirectionRight, Key.DirectionLeft -> {
                                val collapseKey = if (isRtl) Key.DirectionLeft else Key.DirectionRight
                                if (keyEvent.key == collapseKey) {
                                    pendingContentFocusTransfer = false
                                    sidebarCollapsePending = true
                                    true
                                } else {
                                    false
                                }
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
                            keyboardController?.hide()
                            onNavigate(targetRoute)
                            navigateToDrawerRoute(
                                navController = navController,
                                currentRoute = currentRoute,
                                targetRoute = targetRoute
                            )
                            pendingSidebarFocusRequest = false
                            isSidebarExpanded = false
                            sidebarCollapsePending = false
                            pendingContentFocusTransfer = currentRoute == targetRoute
                        },
                        activeProfileName = activeProfileName,
                        activeProfileColorHex = activeProfileColorHex,
                        activeProfileAvatarImageUrl = activeProfileAvatarImageUrl,
                        showProfileSelector = showProfileSelector,
                        onSwitchProfile = onSwitchProfile
                    )
                }
            }

            if (
                !sidebarCollapsed &&
                sidebarShowCollapsedPill &&
                selectedDrawerRoute != Screen.Search.route
            ) {
                CollapsedSidebarPill(
                    label = selectedDrawerItem.label,
                    iconRes = selectedDrawerItem.iconRes,
                    icon = selectedDrawerItem.icon,
                    iconOnly = isFloatingPillIconOnly && !keepFloatingPillExpanded,
                    blurEnabled = modernSidebarBlurEnabled,
                    hazeState = if (modernSidebarBlurEnabled) sidebarHazeState else null,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset {
                            IntOffset(
                                (NuvioTheme.spacing.lg - NuvioTheme.spacing.xxs).roundToPx(),
                                (NuvioTheme.spacing.lg + sidebarDeflateOffsetY).roundToPx()
                            )
                        }
                        .graphicsLayer {
                            val progress = sidebarExpandProgress
                            alpha = 1f - progress
                            val s = 0.9f + (0.1f * (1f - progress))
                            scaleX = s
                            scaleY = s
                            transformOrigin = TransformOrigin(0f, 0f)
                        },
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
    blurEnabled: Boolean,
    hazeState: HazeState? = null,
    modifier: Modifier = Modifier,
    onExpand: () -> Unit
) {
    val pillShape = RoundedCornerShape(NuvioRadii.tokens.full)
    val colors = NuvioTheme.colors
    val bgElevated = colors.BackgroundElevated
    val bgCard = colors.BackgroundCard
    val borderBase = colors.Border
    val mediaColors = colors.media
    val pillBackgroundBrush = remember(blurEnabled) {
        val alpha = if (blurEnabled) 0.65f else 0.96f
        Brush.verticalGradient(listOf(
            Color(0xFF1C1C1E).copy(alpha = alpha),
            Color(0xFF1C1C1E).copy(alpha = alpha)
        ))
    }

    Row(
        modifier = modifier
            .focusProperties { canFocus = false }
            .clickable(onClick = onExpand)
            .padding(horizontal = NuvioTheme.spacing.hairline, vertical = NuvioTheme.spacing.xxs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .height(NuvioTheme.sizes.player.control)
                .clip(pillShape)
                .then(
                    if (blurEnabled && hazeState != null) {
                        Modifier.hazeEffect(state = hazeState) {
                            blurRadius = 24.dp
                            inputScale = HazeInputScale.Fixed(0.66f)
                        }
                    } else {
                        Modifier
                    }
                )
                .background(brush = pillBackgroundBrush, shape = pillShape)
        ) {
            Row(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight()
                    .padding(start = 5.dp, end = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(NuvioTheme.sizes.sidebar.leadingVisual),
                    contentAlignment = Alignment.Center
                ) {
                    DrawerItemIcon(
                        iconRes = iconRes,
                        icon = icon,
                        tint = NuvioTheme.colors.text.onOverlay,
                        modifier = Modifier
                            .size(NuvioTheme.sizes.sidebar.leadingVisual - NuvioTheme.spacing.md)
                            .offset(y = (-0.5).dp)
                    )
                }

                AnimatedVisibility(
                    visible = !iconOnly,
                    enter = expandHorizontally(
                            animationSpec = tween(NuvioMotion.tokens.durations.fast, easing = FastOutSlowInEasing),
                            expandFrom = Alignment.Start,
                            clip = true
                        ),
                    exit = shrinkHorizontally(
                            animationSpec = tween(NuvioMotion.tokens.durations.fast, easing = FastOutSlowInEasing),
                            shrinkTowards = Alignment.Start,
                            clip = true
                        )
                ) {
                    Text(
                        text = label,
                        color = NuvioTheme.colors.text.onOverlay,
                        style = androidx.tv.material3.MaterialTheme.typography.titleLarge.copy(
                            lineHeight = 30.sp
                        ),
                        modifier = Modifier
                            .padding(start = 9.dp, end = NuvioTheme.spacing.md - 5.dp)
                            .offset(y = (-0.5).dp),
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
        if (targetRoute == Screen.Home.route) {
            // Scroll Home to top by clearing saved focus/scroll state on the ViewModel.
            val homeEntry = try {
                navController.getBackStackEntry(Screen.Home.route)
            } catch (_: IllegalArgumentException) {
                // "home" not yet on the back stack (e.g. nav graph not fully initialized).
                return
            }
            val homeViewModel = androidx.lifecycle.ViewModelProvider(homeEntry)[com.nuvio.tv.ui.screens.home.HomeViewModel::class.java]
            homeViewModel.requestScrollToTop()
        }
        return
    }
    try {
        navController.navigate(targetRoute) {
            popUpTo(navController.graph.startDestinationId) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    } catch (e: IllegalArgumentException) {
        Log.w("NuvioNavigation", "Route not found in nav graph: $targetRoute", e)
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
    tint: Color = androidx.tv.material3.LocalContentColor.current,
    brush: Brush? = null
) {
    val iconModifier = if (brush == null) {
        modifier
    } else {
        modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithCache {
                onDrawWithContent {
                    drawContent()
                    drawRect(brush = brush, blendMode = BlendMode.SrcIn)
                }
            }
    }
    val iconTint = if (brush == null) tint else Color.White
    when {
        icon != null -> Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = iconModifier
        )

        iconRes != null -> Icon(
            painter = rememberRawSvgPainter(iconRes),
            contentDescription = null,
            tint = iconTint,
            modifier = iconModifier
        )
    }
}

@Composable
private fun rememberRawSvgPainter(rawIconRes: Int): Painter {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val sizePx = with(density) { NuvioTheme.spacing.xl.roundToPx() }
    return rememberAsyncImagePainter(
        model = ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
            .data(rawIconRes)
            .size(sizePx)
            .build()
    )
}

object LocaleCache {
    const val UNSET = "__UNSET__"

    @Volatile
    var localeTag: String = UNSET
}
