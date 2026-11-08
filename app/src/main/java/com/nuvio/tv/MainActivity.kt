package com.nuvio.tv

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
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
import androidx.compose.material.icons.filled.FilterDrama
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material.icons.filled.Diamond
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Rocket
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.VideogameAsset
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.nuvio.tv.core.perf.FluidModeState
import com.nuvio.tv.core.perf.LocalFluidMode
import com.nuvio.tv.core.runtime.PluginRuntimeHooks
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
import com.nuvio.tv.core.sync.ProfileSettingsSyncService
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
import com.nuvio.tv.data.remote.supabase.AvatarRepository
import com.nuvio.tv.domain.model.AppFont
import com.nuvio.tv.domain.model.AppTheme
import com.nuvio.tv.domain.model.AuthState
import com.nuvio.tv.domain.model.CardDepthStyle
import com.nuvio.tv.domain.model.DiscoverLocation
import com.nuvio.tv.domain.model.ExperienceMode
import com.nuvio.tv.domain.model.SettingsUiStyle
import com.nuvio.tv.domain.deeplink.AppDeepLink
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.ui.components.NuvioScrollDefaults
import com.nuvio.tv.ui.components.LocalCardDepthStyle
import com.nuvio.tv.ui.components.ProfileAvatarCircle
import com.nuvio.tv.ui.components.NuvioSectionTopBar
import com.nuvio.tv.ui.components.TopBarSectionTab
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.ui.navigation.NuvioNavHost
import com.nuvio.tv.ui.navigation.Screen
import com.nuvio.tv.ui.navigation.SectionRouteProbeState
import com.nuvio.tv.ui.notifications.NewEpisodeNoticeHost
import com.nuvio.tv.ui.notifications.NewEpisodeNoticeViewModel
import com.nuvio.tv.ui.screens.account.AuthQrSignInScreen
import com.nuvio.tv.ui.screens.addon.EssentialAddonSetupScreen
import com.nuvio.tv.ui.screens.profile.ProfileSelectionScreen
import com.nuvio.tv.ui.screens.splash.NuvioSplashScreen
import com.nuvio.tv.ui.screens.splash.StartupLoadingScreen
import com.nuvio.tv.ui.theme.NuvioComponents
import com.nuvio.tv.ui.theme.NuvioLayout
import com.nuvio.tv.ui.theme.NuvioMotion
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioRadii
import com.nuvio.tv.ui.theme.NuvioStrokes
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.LocalFastHorizontalNavigationEnabled
import com.nuvio.tv.ui.util.LocalRecompositionHighlighterEnabled
import com.nuvio.tv.ui.util.PrimeRailFocusGuard
import com.nuvio.tv.ui.util.rememberDrawerItemFocusRequesters
import com.nuvio.tv.updater.UpdateViewModel
import com.nuvio.tv.updater.ui.UpdateBannerHost
import dagger.hilt.android.AndroidEntryPoint
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze
import java.util.Locale
import javax.inject.Inject
import javax.inject.Named
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

val LocalSidebarExpanded = compositionLocalOf { false }
val LocalContentFocusRequester = compositionLocalOf { FocusRequester.Default }
// True mentre MainActivity sta trasferendo il focus dalla barra al contenuto
// (entry via tab/sidebar). Anime lo usa per distinguere l'entry — dove il
// focus deve atterrare sulla prima card di "Continua a guardare" — dal
// ritorno dalla scheda dettaglio, che deve invece ripristinare il focus
// dove era.
val LocalPendingContentFocusTransfer = compositionLocalOf { mutableStateOf(false) }
// Piazza il focus sulla tab di sezione attiva nella barra alta. Le schermate
// lo usano quando SU dalla riga più in alto delle righe non può avanzare
// (un moveFocus alla cieca qui aggancia il rail laterale): MainWindow intercetta
// solo SU non consumato, ma dentro rowsListUpRecovery il tasto è già stato
// consumato, quindi serve un modo esplicito di raggiungere la barra.
val LocalFocusSectionTopBar = compositionLocalOf<(() -> Boolean)?> { null }

private const val SIDEBAR_AUTO_COLLAPSE_DELAY_MS = 4_000L

// Fluid Mode: the short holds tuned for a Fire TV stick.
private const val MIN_STARTUP_SPLASH_FLUID_MS = 1_500L
private const val MIN_POST_PROFILE_LOADING_FLUID_MS = 300L

// Classic (Fluid Mode off): the holds of the build before the speed-ups.
private const val MIN_STARTUP_SPLASH_MS = 5_000L
private const val MIN_POST_PROFILE_LOADING_MS = 600L

// First launch: the black splash stays up until the whole startup preload has
// finished (rows, images, trailers saved on disk) and at least this long; every
// later launch is the fast warm path above.
private const val MIN_STARTUP_SPLASH_FIRST_RUN_MS = 15_000L

/**
 * Density scale that shrinks a Fire TV stick's UI toward the ~1280dp-wide
 * layout (clamped to [0.5, 1.0]); devices already ≥1280dp get 1f (no-op).
 * The stock 960dp TV profile renders enlarged on sticks, so normalizing to
 * 960dp itself was a no-op there.
 */
internal fun fireTvUiDensityScale(widthDp: Int): Float =
    if (widthDp <= 0) 1f else (widthDp / 1280f).coerceIn(0.5f, 1.0f)

data class DrawerItem(
    val route: String,
    val label: String,
    val iconRes: Int? = null,
    val icon: ImageVector? = null
)

private val extraLogoIcons = listOf(
    Icons.Default.Star,
    Icons.Default.Movie,
    Icons.Default.LiveTv,
    Icons.Default.Bookmark,
    Icons.Default.Favorite,
    Icons.Default.Whatshot,
    Icons.Default.Diamond,
    Icons.Default.Bolt,
    Icons.Default.Rocket,
    Icons.Default.AutoAwesome,
    Icons.Default.PlayArrow,
    Icons.Default.Explore,
    Icons.Default.VideogameAsset,
    Icons.Default.MusicNote,
    Icons.Default.Palette,
    Icons.Default.Cloud,
    Icons.Default.FileDownload,
    Icons.Default.Cast,
    Icons.Default.Extension,
    Icons.Default.Headphones
)

private data class MainUiPrefs(
    val theme: AppTheme = AppTheme.WHITE,
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
    val cardDepthStyle: CardDepthStyle = CardDepthStyle(),
    val animeTabVisible: Boolean = true,
    val liveTvTabVisible: Boolean = true,
    val extraTabVisible: Boolean = true,
    val calendarTabVisible: Boolean = true,
    val libraryTabVisible: Boolean = true,
    val extraTabLogoIndex: Int = 0,
    val extraTabName: String = "Extra"
)

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var fluidModeState: FluidModeState

    @Inject
    lateinit var themeDataStore: ThemeDataStore

    @Inject
    lateinit var layoutPreferenceDataStore: LayoutPreferenceDataStore

    @Inject
    @field:Named("anime_layout")
    lateinit var animeLayoutPreferenceDataStore: LayoutPreferenceDataStore

    @Inject
    @field:Named("extra_layout")
    lateinit var extraLayoutPreferenceDataStore: LayoutPreferenceDataStore

    @Inject
    lateinit var experienceModeDataStore: ExperienceModeDataStore

    @Inject
    lateinit var addonRepository: AddonRepository

    @Inject
    lateinit var trackingProgressRefreshCoordinator: TrackingProgressRefreshCoordinator

    @Inject
    lateinit var startupSyncService: StartupSyncService

    @Inject
    lateinit var startupHomePreloader: com.nuvio.tv.core.sync.StartupHomePreloader

    @Inject
    lateinit var startupWarmupPreferences: com.nuvio.tv.data.local.StartupWarmupPreferences

    @Inject
    lateinit var androidTvChannelSyncService: com.nuvio.tv.core.sync.androidtv.AndroidTvChannelSyncService

    @Inject
    lateinit var profileSettingsSyncService: ProfileSettingsSyncService

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

    /** Holds the Android system splash until the first Compose frame of NuvioSplashScreen is ready. */
    private val systemSplashReady = java.util.concurrent.atomic.AtomicBoolean(false)

    @OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class)
    override fun attachBaseContext(newBase: Context) {
        val tag = LocaleCache.localeTag.takeIf { it != LocaleCache.UNSET }

        val localizedBase = if (!tag.isNullOrEmpty()) {
            val locale = Locale.forLanguageTag(tag)
            Locale.setDefault(locale)
            val config = Configuration(newBase.resources.configuration)
            config.setLocale(locale)
            newBase.createConfigurationContext(config)
        } else {
            // Cache not ready yet (very early cold start) — use system locale
            // The IO coroutine in Application.onCreate will finish before any activity
            // is usually created, but if not, we just use system locale until next launch
            newBase
        }
        super.attachBaseContext(applyFireTvUiScale(localizedBase))
    }

    /** Amazon Fire TV sticks (AFT*) are the only devices the UI density scale applies to. */
    private fun isFireTvDensityScaleDevice(): Boolean {
        val manufacturer = Build.MANUFACTURER ?: return false
        val model = Build.MODEL ?: return false
        return manufacturer.equals("amazon", ignoreCase = true) &&
            model.startsWith("AFT", ignoreCase = true)
    }

    /**
     * Amazon Fire TV sticks (AFT*) report a density that renders the UI
     * enlarged versus standard TVs; shrink it toward the ~1280dp profile.
     * Only active while Layout > Fluid Mode is on (mirrored for attachBaseContext).
     * Other devices are returned unchanged.
     */
    private fun applyFireTvUiScale(base: Context): Context {
        if (!isFireTvDensityScaleDevice()) return base
        if (!LayoutPreferenceDataStore.readFluidZoomMirror(base)) return base
        val config = Configuration(base.resources.configuration)
        if (config.screenWidthDp <= 0 || config.screenHeightDp <= 0) {
            // Very early cold start can report an empty screen size — fall
            // back to the raw display metrics so the scale still applies.
            val metrics = base.resources.displayMetrics
            if (metrics.widthPixels > 0 && metrics.heightPixels > 0 && metrics.densityDpi > 0) {
                val dpPerPx = 160f / metrics.densityDpi
                if (config.screenWidthDp <= 0) config.screenWidthDp = (metrics.widthPixels * dpPerPx).toInt()
                if (config.screenHeightDp <= 0) config.screenHeightDp = (metrics.heightPixels * dpPerPx).toInt()
            }
        }
        val scale = fireTvUiDensityScale(config.screenWidthDp)
        if (scale >= 1f) return base
        config.densityDpi = (config.densityDpi * scale).toInt().coerceAtLeast(1)
        val expand = 1f / scale
        config.screenWidthDp = (config.screenWidthDp * expand).toInt()
        if (config.screenHeightDp > 0) config.screenHeightDp = (config.screenHeightDp * expand).toInt()
        if (config.smallestScreenWidthDp > 0) {
            config.smallestScreenWidthDp = (config.smallestScreenWidthDp * expand).toInt()
        }
        return base.createConfigurationContext(config)
    }

    /**
     * Ogni tasto premuto marchia l'ora in [com.nuvio.tv.NuvioApplication]:
     * il governor di memoria rimanda cache-clear/GC finché l'utente non
     * naviga — mai un blocco a metà di uno scroll.
     */
    override fun onUserInteraction() {
        super.onUserInteraction()
        NuvioApplication.markUserInteraction()
    }

    @OptIn(ExperimentalFoundationApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        val systemSplash = installSplashScreen()
        systemSplash.setKeepOnScreenCondition { !systemSplashReady.get() }
        super.onCreate(savedInstanceState)
        isFirstResumeAfterCreate = true
        android.util.Log.i("MainActivity", "onCreate")
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
        captureDeepLinkIntent(intent)

        // Warm Home data during the splash + profile selection.
        startupHomePreloader.ensureStarted()

        // The Fire TV density scale is fixed at attachBaseContext: mirror Fluid Mode
        // (OR of every layout store, so any Layout screen can toggle it) and restart
        // the activity when it changes so the new scale applies.
        if (isFireTvDensityScaleDevice()) {
            lifecycleScope.launch {
                combine(
                    layoutPreferenceDataStore.fluidModeEnabled,
                    animeLayoutPreferenceDataStore.fluidModeEnabled,
                    extraLayoutPreferenceDataStore.fluidModeEnabled
                ) { base, anime, extra -> base || anime || extra }
                    .distinctUntilChanged()
                    .collect { enabled ->
                        if (LayoutPreferenceDataStore.writeFluidZoomMirror(
                                this@MainActivity, enabled
                            ) && !isFinishing && !isDestroyed
                        ) {
                            recreate()
                        }
                    }
            }
        }

        setContent {
            var hasSelectedProfileThisSession by rememberSaveable { mutableStateOf(false) }
            var onboardingCompletedThisSession by remember { mutableStateOf(false) }
            var onboardingProfileSyncInProgress by remember { mutableStateOf(false) }
            var splashMinElapsed by rememberSaveable { mutableStateOf(false) }
            var postProfileMinElapsed by rememberSaveable { mutableStateOf(false) }
            val hasSeenAuthQrFlow = remember(appOnboardingDataStore) {
                appOnboardingDataStore.hasSeenAuthQrOnFirstLaunch.map<Boolean, Boolean?> { it }
            }
            val hasSeenAuthQrOnFirstLaunch by hasSeenAuthQrFlow.collectAsState(initial = null)
            val authState by authManager.authState.collectAsState()
            val context = LocalContext.current

            LaunchedEffect(Unit) {
                withFrameNanos { }
                systemSplashReady.set(true)
            }
            val firstHomeReadyDone by startupWarmupPreferences.firstHomeReadyDone.collectAsState(initial = null)
            LaunchedEffect(firstHomeReadyDone) {
                when {
                    // Primo avvio: splash lungo (il precaricamento gira dietro),
                    // poi il caricamento post-profilo resta finché non è pronto tutto.
                    firstHomeReadyDone == false -> {
                        delay(MIN_STARTUP_SPLASH_FIRST_RUN_MS)
                        splashMinElapsed = true
                    }
                    firstHomeReadyDone == true -> {
                        delay(
                            if (fluidModeState.isEnabled()) MIN_STARTUP_SPLASH_FLUID_MS
                            else MIN_STARTUP_SPLASH_MS
                        )
                        splashMinElapsed = true
                    }
                    else -> Unit // flag ancora in lettura: si rilancia appena è noto
                }
            }
            LaunchedEffect(hasSelectedProfileThisSession) {
                if (hasSelectedProfileThisSession) {
                    postProfileMinElapsed = false
                    delay(
                        if (fluidModeState.isEnabled()) MIN_POST_PROFILE_LOADING_FLUID_MS
                        else MIN_POST_PROFILE_LOADING_MS
                    )
                    postProfileMinElapsed = true
                } else {
                    postProfileMinElapsed = false
                }
            }

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
                // Always show profile selection on launch for background loading
                // rememberLastProfileEnabled is intentionally ignored here
            }

            var avatarCatalog by remember { mutableStateOf(emptyList<com.nuvio.tv.data.remote.supabase.AvatarCatalogItem>()) }

            LaunchedEffect(Unit) {
                avatarCatalog = runCatching { avatarRepository.getAvatarCatalog() }
                    .getOrDefault(emptyList())
            }

            val activeProfileAvatarImageUrl = remember(activeProfile, avatarCatalog) {
                activeProfile?.avatarUrl?.takeIf { it.isNotBlank() }
                    ?: activeProfile?.avatarId?.let { avatarRepository.getAvatarImageUrl(it, avatarCatalog) }
            }

            val mainUiPrefsFlow = remember(themeDataStore, layoutPreferenceDataStore, experienceModeDataStore) {
                // Group flows into two batches to reduce intermediate flow allocations.
                // Each batch uses a single combine() instead of chaining .combine() calls,
                // which avoids N intermediate flow objects and redundant emissions on startup.
                val themeAndExperienceFlow = combine(
                    themeDataStore.selectedTheme,
                    themeDataStore.selectedFont,
                    themeDataStore.amoledMode,
                    themeDataStore.amoledSurfacesMode,
                    experienceModeDataStore.mode,
                ) { theme, font, amoledMode, amoledSurfacesMode, experienceMode ->
                    MainUiPrefs(
                        theme = theme,
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
                val tabVisibilityFlow = combine(
                    layoutPreferenceDataStore.animeTabVisible,
                    layoutPreferenceDataStore.liveTvTabVisible,
                    layoutPreferenceDataStore.extraTabVisible,
                    layoutPreferenceDataStore.calendarTabVisible,
                    layoutPreferenceDataStore.libraryTabVisible
                ) { animeVisible, liveTvVisible, extraVisible, calendarVisible, libraryVisible ->
                    arrayOf(animeVisible, liveTvVisible, extraVisible, calendarVisible, libraryVisible)
                }
                val extraFeaturesBaseFlow = combine(
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
                val extraFeaturesFlow = extraFeaturesBaseFlow.combine(tabVisibilityFlow) { prefs, tabVisibility ->
                    prefs.copy(
                        animeTabVisible = tabVisibility[0],
                        liveTvTabVisible = tabVisibility[1],
                        extraTabVisible = tabVisibility[2],
                        calendarTabVisible = tabVisibility[3],
                        libraryTabVisible = tabVisibility[4]
                    )
                }
                val extraLogoFlow = layoutPreferenceDataStore.extraTabLogoIndex
                val extraTabNameFlow = layoutPreferenceDataStore.extraTabName
                val extraTabExtrasFlow = combine(extraLogoFlow, extraTabNameFlow) { logoIndex, tabName ->
                    logoIndex to tabName
                }
                combine(
                    themeAndExperienceFlow,
                    layoutAndFeaturesFlow,
                    extraFeaturesFlow,
                    layoutPreferenceDataStore.cardDepthStyle,
                    extraTabExtrasFlow
                ) { themePrefs, layoutPrefs, extraPrefs, cardDepthStyle, (logoIndex, tabName) ->
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
                        cardDepthStyle = cardDepthStyle,
                        animeTabVisible = extraPrefs.animeTabVisible,
                        liveTvTabVisible = extraPrefs.liveTvTabVisible,
                        extraTabVisible = extraPrefs.extraTabVisible,
                        calendarTabVisible = extraPrefs.calendarTabVisible,
                        libraryTabVisible = extraPrefs.libraryTabVisible,
                        extraTabLogoIndex = logoIndex,
                        extraTabName = tabName
                    )
                }
            }
            val mainUiPrefs by mainUiPrefsFlow.collectAsState(initial = MainUiPrefs(hasChosenLayout = null))
            val installedAddons by remember(addonRepository) {
                addonRepository.getInstalledAddons()
            }.collectAsState(initial = null)

            NuvioTheme(
                appTheme = mainUiPrefs.theme,
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
                CompositionLocalProvider(
                    LocalBringIntoViewSpec provides bringIntoViewSpec,
                    LocalFastHorizontalNavigationEnabled provides mainUiPrefs.fastHorizontalNavigationEnabled,
                    LocalRecompositionHighlighterEnabled provides (BuildConfig.IS_DEBUG_BUILD && mainUiPrefs.composeHighlighterEnabled),
                    LocalCardDepthStyle provides mainUiPrefs.cardDepthStyle,
                    com.nuvio.tv.core.player.LocalTrailerPlayerPool provides trailerPlayerPool
                ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    shape = RectangleShape,
                    colors = SurfaceDefaults.colors(
                        containerColor = NuvioTheme.colors.Background
                    )
                ) {
                    if (
                        hasSeenAuthQrOnFirstLaunch == null ||
                        authState is AuthState.Loading ||
                        !splashMinElapsed
                    ) {
                        NuvioSplashScreen()
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

                    val shouldShowProfileSelection = !hasSelectedProfileThisSession

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
                        NuvioSplashScreen()
                        return@Surface
                    }
                    val effectiveExperienceMode = mainUiPrefs.experienceMode
                        ?: if (layoutChosen) ExperienceMode.ADVANCED else null
                    val needsExperienceSelection = effectiveExperienceMode == null
                    val needsEssentialAddonSetup =
                        effectiveExperienceMode == ExperienceMode.ESSENTIAL &&
                            installedAddons.orEmpty().isEmpty() &&
                            !mainUiPrefs.addonSetupSkipped

                    // After profile select, hold a black loading screen until Home data is
                    // actually ready — the preloader keeps re-attempting phases and only
                    // force-releases after its hard 120s deadline. Skip for onboarding flows
                    // that don't open Home.
                    val willEnterMainApp = !needsExperienceSelection && !needsEssentialAddonSetup && layoutChosen
                    val homePreloadReady by startupHomePreloader.ready.collectAsState()
                    if (willEnterMainApp && (!homePreloadReady || !postProfileMinElapsed)) {
                        StartupLoadingScreen()
                        return@Surface
                    }

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
                    val fluidModeEnabled by fluidModeState.enabled.collectAsState(initial = false)

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
                                    returnToDetailOnBack = next.contentType.equals("series", ignoreCase = true)
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
                            navController.navigate(
                                Screen.Detail.createRoute(
                                    itemId = launchContentId,
                                    itemType = launchContentType
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
                        if (currentRoute != null) {
                            val holder = PerformanceMetricsState.getHolderForHierarchy(view)
                            holder.state?.putState("Screen", currentRoute)
                        }
                    }

                    val strNavHome = stringResource(R.string.nav_home)
                    val strNavAnime = stringResource(R.string.nav_anime)
                    val strNavSearch = stringResource(R.string.nav_search)
                    val strNavLibrary = stringResource(R.string.nav_library)
                    val strNavLiveTv = stringResource(R.string.nav_live_tv)
                    val strNavExtra = stringResource(R.string.nav_extra)
                    val strNavCalendar = stringResource(R.string.nav_calendar)
                    val strNavSettings = stringResource(R.string.nav_settings)
                    val rootRoutes = remember {
                        buildSet {
                            add(Screen.Home.route)
                            add(Screen.Anime.route)
                            add(Screen.Discover.route)
                            add(Screen.Search.route)
                            add(Screen.Library.route)
                            add(Screen.LiveTv.route)
                            add(Screen.Extra.route)
                            add(Screen.Calendar.route)
                            add(Screen.Settings.route)
                        }
                    }
                    val drawerItems = remember(
                        strNavHome,
                        strNavAnime,
                        strNavSearch,
                        strNavLibrary,
                        strNavLiveTv,
                        strNavSettings,
                        mainUiPrefs.animeTabVisible,
                        mainUiPrefs.liveTvTabVisible,
                        mainUiPrefs.extraTabVisible,
                        mainUiPrefs.calendarTabVisible,
                        mainUiPrefs.libraryTabVisible,
                        mainUiPrefs.extraTabLogoIndex,
                        mainUiPrefs.extraTabName
                    ) {
                        buildList {
                            add(
                                DrawerItem(
                                    route = Screen.Home.route,
                                    label = strNavHome,
                                    icon = Icons.Default.Home
                                )
                            )
                            if (mainUiPrefs.animeTabVisible) {
                            add(
                                DrawerItem(
                                    route = Screen.Anime.route,
                                    label = strNavAnime,
                                    icon = Icons.Default.FilterDrama
                                )
                            )
                            }
                            if (mainUiPrefs.extraTabVisible) {
                            val extraLogoIcon = extraLogoIcons[mainUiPrefs.extraTabLogoIndex.coerceIn(0, extraLogoIcons.lastIndex)]
                            add(
                                DrawerItem(
                                    route = Screen.Extra.route,
                                    label = mainUiPrefs.extraTabName.ifBlank { strNavExtra },
                                    icon = extraLogoIcon
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
                            if (mainUiPrefs.libraryTabVisible) {
                            add(
                                DrawerItem(
                                    route = Screen.Library.route,
                                    label = strNavLibrary,
                                    iconRes = R.raw.sidebar_library
                                )
                            )
                            }
                            if (mainUiPrefs.liveTvTabVisible) {
                            add(
                                DrawerItem(
                                    route = Screen.LiveTv.route,
                                    label = strNavLiveTv,
                                    icon = Icons.Default.LiveTv
                                )
                            )
                            }
                            if (mainUiPrefs.calendarTabVisible) {
                            add(
                                DrawerItem(
                                    route = Screen.Calendar.route,
                                    label = strNavCalendar,
                                    icon = Icons.Default.CalendarMonth
                                )
                            )
                            }
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

                    val updateViewModel: UpdateViewModel = hiltViewModel(this@MainActivity)
                    val updateState by updateViewModel.uiState.collectAsState()

                    val newEpisodeViewModel: NewEpisodeNoticeViewModel =
                        hiltViewModel(this@MainActivity)
                    val newEpisodeState by newEpisodeViewModel.uiState.collectAsState()

                    UpdateBannerHost(
                        state = updateState,
                        onDismissBanner = updateViewModel::dismissBanner,
                        onDownload = updateViewModel::downloadUpdate,
                        onInstall = updateViewModel::installUpdateOrRequestPermission,
                        onDismissUnknownSources = updateViewModel::dismissUnknownSourcesDialog,
                        onOpenUnknownSources = updateViewModel::openUnknownSourcesSettings,
                        onFeedbackShown = updateViewModel::consumeFeedbackMessage
                    ) {
                        NewEpisodeNoticeHost(
                            state = newEpisodeState,
                            onCheck = newEpisodeViewModel::onAppOpen,
                            onDismiss = newEpisodeViewModel::dismiss
                        ) {
                            Box(modifier = Modifier.fillMaxSize()) {
                                CompositionLocalProvider(LocalFluidMode provides fluidModeEnabled) {
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
                                        activeProfileAvatarImageUrl = activeProfileAvatarImageUrl,
                                        showProfileSelector = profiles.size > 1,
                                        onSwitchProfile = { hasSelectedProfileThisSession = false },
                                        onNavigate = { optimisticRoute = it },
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
                                        activeProfileAvatarImageUrl = activeProfileAvatarImageUrl,
                                        showProfileSelector = profiles.size > 1,
                                        onSwitchProfile = { hasSelectedProfileThisSession = false },
                                        onNavigate = { optimisticRoute = it },
                                        onExitApp = {
                                            finishAffinity()
                                            finishAndRemoveTask()
                                        }
                                    )
                                }
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

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            android.util.Log.i("MainActivity", "window focus gained (UI interactive)")
        }
    }

    override fun onResume() {
        super.onResume()
        if (::jankStats.isInitialized) jankStats.isTrackingEnabled = true
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
    }

    private fun captureDeepLinkIntent(intent: Intent?) {
        val url = intent?.dataString?.trim()?.takeIf(String::isNotBlank) ?: return
        pendingDeepLinkUrl.value = url
    }

    override fun onPause() {
        super.onPause()
        if (::jankStats.isInitialized) jankStats.isTrackingEnabled = false
    }

    // Intercept Back at the Activity level, before any Compose BackHandler, so the auto-next loader
    // can always be dismissed. Compose back-dispatch ordering kept putting the destination screen's
    // handler above the loader's, so Back never reached it.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
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
        profileSettingsSyncService.requestForegroundPull()
        androidTvChannelSyncService.onForegroundChanged(true)
        // Appena Nuvio si apre, uccidi subito tutto ciò che l'utente ha
        // appena lasciato (cached): la RAM è libera quando servirà al
        // contenuto e alla prossima app.
        com.nuvio.tv.core.memory.BackgroundAppCleaner.cleanOnNuvioLaunch(this)
    }

    override fun onStop() {
        externalPlaybackTracker.onExternalPlayerCoveredApp()
        super.onStop()
        startupSyncService.stopPeriodicSurfacePulls()
        // App going to background (e.g. user returning to the launcher): reconcile the
        // Continue Watching channel once so Projectivy repaints it with fresh progress.
        androidTvChannelSyncService.onForegroundChanged(false)
        // Uscendo da Nuvio tutto ciò che è tornato in cached muore subito: la
        // prossima app che si apre trova la RAM libera.
        com.nuvio.tv.core.memory.BackgroundAppCleaner.cleanAllCached(this)
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
        drawerState.setValue(DrawerValue.Closed)
    }

    val sidebarTokens = NuvioComponents.tokens.sidebar
    val closedDrawerWidth = if (sidebarCollapsed) NuvioTheme.spacing.none else sidebarTokens.legacyCollapsedWidth
    val openDrawerWidth = sidebarTokens.legacyExpandedWidth
    val openDrawerItemWidth = sidebarTokens.itemWidth

    val focusManager = LocalFocusManager.current
    val isRtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    val contentFocusRequester = remember { FocusRequester() }
    val pendingContentFocusTransferState = remember { mutableStateOf(false) }
    var pendingContentFocusTransfer by pendingContentFocusTransferState
    var pendingSidebarFocusRequest by remember { mutableStateOf(false) }
    // Bumped on every key event the drawer sees so the auto-collapse timer
    // resets while the user navigates between drawer items.
    var legacyDrawerInteractionVersion by remember { mutableStateOf(0) }


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
        val requested = runCatching { contentFocusRequester.requestFocus() }.getOrDefault(false)
        if (!requested) {
            focusManager.moveFocus(if (isRtl) FocusDirection.Left else FocusDirection.Right)
        }
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
                                pendingContentFocusTransfer = true
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
                                Spacer(modifier = Modifier.height(42.dp))
                            }
                        }
                    }

                    Column(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .offset(y = 28.dp)
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
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
                LocalContentFocusRequester provides contentFocusRequester,
                LocalPendingContentFocusTransfer provides pendingContentFocusTransferState
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

    // Barra alta sezioni (stile Prime Video): solo le voci di CONTENUTO
    // (Home · Anime · Extra · Calendario) salgono in alto con le loro
    // etichette; il rail a sinistra resta a icone. Le richieste di focus
    // per tab permettono a SU (dalla cima dei contenuti) di atterrare
    // direttamente sulla sezione attiva.
    val sectionRoutes = remember {
        setOf(Screen.Home.route, Screen.Anime.route, Screen.Extra.route, Screen.Calendar.route)
    }
    val topBarTabs = remember(drawerItems) {
        drawerItems
            .filter { it.route in sectionRoutes }
            .map { TopBarSectionTab(route = it.route, label = it.label) }
    }
    val topBarTabFocusRequesters = remember(topBarTabs) {
        topBarTabs.associate { it.route to FocusRequester() }
    }

    // Rail laterale stile Prime Video: solo le utility (Cerca, Libreria,
    // Live TV, Impostazioni). Le sezioni vivono nella barra alta; il rail
    // è sempre visibile a icone nude e non si espande mai con le etichette:
    // BACK ci porta il focus sopra, un secondo BACK esce dall'app.
    val primeRailRoutes = remember {
        setOf(
            Screen.Home.route,
            Screen.Search.route,
            Screen.Library.route,
            Screen.LiveTv.route,
            Screen.Settings.route
        )
    }
    val primeRailItems = remember(drawerItems) {
        drawerItems.filter { it.route in primeRailRoutes }
    }
    var primeRailHasFocus by remember { mutableStateOf(false) }
    val focusPrimeRail: () -> Boolean = {
        val targetRoute = primeRailItems.firstOrNull { it.route == currentRoute }?.route
            ?: primeRailItems.firstOrNull()?.route
        if (targetRoute != null) {
            runCatching {
                drawerItemFocusRequesters.getValue(targetRoute).requestFocus()
            }.getOrDefault(false)
        } else {
            false
        }
    }
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current

    var isSidebarExpanded by remember { mutableStateOf(false) }
    var sidebarCollapsePending by remember { mutableStateOf(false) }
    val pendingContentFocusTransferState = remember { mutableStateOf(false) }
    var pendingContentFocusTransfer by pendingContentFocusTransferState
    var pendingSidebarFocusRequest by remember { mutableStateOf(false) }
    var focusedDrawerIndex by remember { mutableStateOf(-1) }
    var isFloatingPillIconOnly by remember { mutableStateOf(false) }
    val keepFloatingPillExpanded = selectedDrawerRoute == Screen.Settings.route
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

    // La pill in Impostazioni è forzata a mostrare l'etichetta: appena se ne
    // esce va richiusa subito, altrimenti resta appesa sopra il contenuto per
    // l'intero timeout di auto-collapse (4 secondi di "barra fantasma").
    var floatingPillWasExpanded by remember { mutableStateOf(keepFloatingPillExpanded) }
    LaunchedEffect(keepFloatingPillExpanded, showSidebar) {
        if (!showSidebar) {
            floatingPillWasExpanded = false
            isFloatingPillIconOnly = false
        } else if (keepFloatingPillExpanded) {
            floatingPillWasExpanded = true
            isFloatingPillIconOnly = false
        } else if (floatingPillWasExpanded) {
            floatingPillWasExpanded = false
            isFloatingPillIconOnly = true
        }
    }

    BackHandler(enabled = currentRoute in rootRoutes && !isSidebarExpanded && !sidebarCollapsePending) {
        // Rail stile Prime: nessun pannello con le etichette. Il primo BACK
        // porta il focus sul rail; se è già lì (o il rail non ha voci) esce.
        if (primeRailHasFocus || !focusPrimeRail()) {
            onExitApp()
        }
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

    // Auto-collapse the expanded sidebar after a short period of inactivity.
    // The timer resets every time focus moves between drawer items, so the

    // Auto-collapse the floating pill back to icon-only when the user reveals
    // its label (DPAD UP from content) and then leaves it idle. The DPAD DOWN
    // path already collapses it instantly, this just covers the case where the
    // user releases UP and walks away.
    LaunchedEffect(isFloatingPillIconOnly, keepFloatingPillExpanded, showSidebar, isSidebarExpanded) {
        if (!showSidebar || isFloatingPillIconOnly || keepFloatingPillExpanded || isSidebarExpanded) {
            return@LaunchedEffect
        }
        delay(SIDEBAR_AUTO_COLLAPSE_DELAY_MS)
        isFloatingPillIconOnly = true
    }

    val sidebarVisible = showSidebar && (isSidebarExpanded || !sidebarCollapsed)
    val sidebarHazeState = remember { HazeState() }
    val targetSidebarWidth = when {
        !sidebarVisible -> NuvioTheme.spacing.none
        isSidebarExpanded -> openSidebarWidth
        else -> collapsedSidebarWidth
    }
    val sidebarWidth by animateDpAsState(
        targetValue = targetSidebarWidth,
        animationSpec = if (isSidebarExpanded) {
            keyframes {
                durationMillis = 250
                (openSidebarWidth + NuvioTheme.spacing.md) at 120
            }
        } else {
            tween(durationMillis = NuvioMotion.tokens.durations.sidebarEnter, easing = NuvioMotion.tokens.easings.decelerate)
        },
        label = "sidebarWidth"
    )
    val animationDuration = if (sidebarVisible) 280 else 200
    val animationEasing = if (sidebarVisible) FastOutSlowInEasing else FastOutLinearInEasing

    val sidebarSlideX by animateDpAsState(
        targetValue = if (sidebarVisible) NuvioTheme.spacing.none else (-24).dp,
        animationSpec = tween(durationMillis = animationDuration, easing = animationEasing),
        label = "sidebarSlideX"
    )
    val sidebarSurfaceAlpha by animateFloatAsState(
        targetValue = if (sidebarVisible) 1f else 0f,
        animationSpec = tween(durationMillis = animationDuration, easing = animationEasing),
        label = "sidebarSurfaceAlpha"
    )
    val shouldApplySidebarHaze = showSidebar && modernSidebarBlurEnabled && (
        isSidebarExpanded || sidebarCollapsePending
        )
    val sidebarTransition = updateTransition(
        targetState = isSidebarExpanded,
        label = "sidebarTransition"
    )
    val sidebarLabelAlpha by sidebarTransition.animateFloat(
        transitionSpec = {
            if (targetState) {
                tween(durationMillis = NuvioMotion.tokens.durations.sidebarLabelIn, easing = FastOutSlowInEasing)
            } else {
                tween(durationMillis = NuvioMotion.tokens.durations.sidebarLabelOut, easing = LinearOutSlowInEasing)
            }
        },
        label = "sidebarLabelAlpha"
    ) { expanded ->
        if (expanded) 1f else 0f
    }
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

    val sidebarIconScale by sidebarTransition.animateFloat(
        transitionSpec = { tween(durationMillis = NuvioMotion.tokens.durations.sidebarLabelOut, easing = FastOutSlowInEasing) },
        label = "sidebarIconScale"
    ) { expanded ->
        if (expanded) 1f else 0.92f
    }
    val sidebarBloomScale by sidebarTransition.animateFloat(
        transitionSpec = {
            if (targetState) {
                tween(durationMillis = NuvioMotion.tokens.durations.sidebarPanelIn, easing = FastOutSlowInEasing)
            } else {
                tween(durationMillis = NuvioMotion.tokens.durations.sidebarBloomOut, easing = LinearOutSlowInEasing)
            }
        },
        label = "sidebarBloomScale"
    ) { expanded ->
        if (expanded) 1f else 0.9f
    }
    val sidebarDeflateOffsetX by sidebarTransition.animateDp(
        transitionSpec = {
            if (targetState) {
                tween(durationMillis = NuvioMotion.tokens.durations.sidebarPanelIn, easing = FastOutSlowInEasing)
            } else {
                tween(durationMillis = NuvioMotion.tokens.durations.sidebarBloomOut, easing = LinearOutSlowInEasing)
            }
        },
        label = "sidebarDeflateOffsetX"
    ) { expanded ->
        if (expanded) NuvioTheme.spacing.none else (-10).dp
    }
    val sidebarDeflateOffsetY by sidebarTransition.animateDp(
        transitionSpec = {
            if (targetState) {
                tween(durationMillis = NuvioMotion.tokens.durations.sidebarPanelIn, easing = FastOutSlowInEasing)
            } else {
                tween(durationMillis = NuvioMotion.tokens.durations.sidebarBloomOut, easing = LinearOutSlowInEasing)
            }
        },
        label = "sidebarDeflateOffsetY"
    ) { expanded ->
        if (expanded) NuvioTheme.spacing.none else (-8).dp
    }

    LaunchedEffect(isSidebarExpanded, sidebarCollapsePending, pendingContentFocusTransfer, showSidebar) {
        if (!showSidebar || !pendingContentFocusTransfer || isSidebarExpanded || sidebarCollapsePending) {
            return@LaunchedEffect
        }
        var requested = false
        if (currentRoute in sectionRoutes) {
            // Le sezioni registrano il contentFocusRequester, ma sulle TV lente
            // (Fire TV Stick 1GB) il contenuto diventa focusabile DOPO il salto
            // di sezione: riprova per qualche secondo, altrimenti il focus
            // resta intrappolato su barra e rail.
            val deadline = SystemClock.uptimeMillis() + 6000L
            while (!requested && SystemClock.uptimeMillis() < deadline) {
                requested = contentFocusRequester.requestFocusAfterFrames(6)
                if (!requested) delay(250)
            }
        } else {
            requested = contentFocusRequester.requestFocusAfterFrames(8)
        }
        if (!requested) {
            // Root screens without a registered content requester (Search, Library, ...):
            // steer the d-pad at the content so the remote does not go dead.
            focusManager.moveFocus(if (isRtl) FocusDirection.Left else FocusDirection.Right)
        }
        pendingContentFocusTransfer = false
    }

    // Ritorno da una sotto-schermata (detail/stream/player/impostazioni) a una
    // sezione: durante il pop il focus si perde e finisce sulla barra alta.
    // Riusare pendingContentFocusTransfer (= entry via tab) fa due cose:
    // 1) MainActivity rimette il focus sul contenuto (focusRestorer.onEnter);
    // 2) AnimeHomeScreen porta la lista in cima e piazza il focus sulla prima
    //    card di "Continua a guardare", che e' il comportamento voluto anche
    //    al ritorno dal detail.
    var routeBeforeSectionReturn by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(currentRoute) {
        val previousRoute = routeBeforeSectionReturn
        routeBeforeSectionReturn = currentRoute
        if (currentRoute == null ||
            currentRoute !in sectionRoutes ||
            previousRoute == null ||
            previousRoute in sectionRoutes
        ) {
            return@LaunchedEffect
        }
        pendingContentFocusTransfer = true
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

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onPreviewKeyEvent { keyEvent ->
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
                                Key.DirectionUp -> isFloatingPillIconOnly = false
                                else -> Unit
                            }
                        }
                        // SU non consumato dal contenuto = siamo in cima alla
                        // pagina: il focus sale alla tab di sezione attiva
                        // nella barra alta (come Prime). Se le righe hanno
                        // gestito SU spostandosi verso l'alto, questo non
                        // scatta mai — il tasto è già stato consumato.
                        if (keyEvent.key == Key.DirectionUp &&
                            topBarTabs.isNotEmpty() &&
                            currentRoute in sectionRoutes
                        ) {
                            val target = topBarTabFocusRequesters[selectedDrawerRoute]
                                ?: topBarTabFocusRequesters[topBarTabs.first().route]
                            if (target != null &&
                                runCatching { target.requestFocus() }.getOrDefault(false)
                            ) {
                                return@onKeyEvent true
                            }
                        }
                        val openKey = if (isRtl) Key.DirectionRight else Key.DirectionLeft
                        if (keyEvent.key == openKey) {
                            if (focusManager.moveFocus(if (isRtl) FocusDirection.Right else FocusDirection.Left)) {
                                true
                            } else {
                                // Rail stile Prime: se a sinistra non c'è nulla il
                                // focus cade sul rail, senza pannello con le etichette.
                                focusPrimeRail()
                            }
                        } else {
                            false
                        }
                    } else {
                        false
                    }
                }
        ) {
            // Piazza il focus sulla tab di sezione attiva (o alla prima). Le
            // schermate lo invocano quando SU dalla riga in cima non può
            // avanzare: MainWindow intercetta solo SU non consumato, ma dentro
            // rowsListUpRecovery il tasto è già stato consumato.
            val focusSectionTopBar: () -> Boolean = {
                if (topBarTabs.isNotEmpty() && currentRoute in sectionRoutes) {
                    val target = topBarTabFocusRequesters[selectedDrawerRoute]
                        ?: topBarTabFocusRequesters[topBarTabs.first().route]
                    target != null &&
                        runCatching { target.requestFocus() }.getOrDefault(false)
                } else {
                    false
                }
            }
            CompositionLocalProvider(
                LocalSidebarExpanded provides isSidebarExpanded,
                LocalContentFocusRequester provides contentFocusRequester,
                LocalPendingContentFocusTransfer provides pendingContentFocusTransferState,
                LocalFocusSectionTopBar provides focusSectionTopBar
            ) {
                NuvioNavHost(
                    navController = navController,
                    startDestination = startDestination,
                    hideBuiltInHeaders = hideBuiltInHeaders
                )
            }
        }

        // La barra sezioni vive sulle sezioni (Home, Anime, Extra, Calendario);
        // nelle schermate strumentali (Ricerca, Scopri, Libreria, Live TV,
        // Impostazioni...) sparisce per non sovrapporsi ai contenuti.
        if (showSidebar &&
            currentRoute in sectionRoutes &&
            // La barra compare solo quando la schermata di sezione è davvero
            // composta: altrimenti commuta subito mentre la schermata vecchia
            // è ancora visibile e si vedono due barre sovrapposte (issue #11).
            currentRoute == SectionRouteProbeState.composedRoute &&
            topBarTabs.isNotEmpty()
        ) {
            NuvioSectionTopBar(
                tabs = topBarTabs,
                selectedRoute = selectedDrawerRoute,
                tabFocusRequesters = topBarTabFocusRequesters,
                onNavigate = { route ->
                    // Come nel pannello laterale: rotta ottimistica per l'highlight
                    // IMMEDIATO + navigazione Vera sul NavController, altrimenti la
                    // tab cambia logo ma il contenuto resta fermo.
                    onNavigate(route)
                    navigateToDrawerRoute(
                        navController = navController,
                        currentRoute = currentRoute,
                        targetRoute = route
                    )
                    // Anime, Extra e Calendario ora registrano il contentFocusRequester
                    // (come la Home): il focus atterra direttamente sulla prima riga.
                    pendingContentFocusTransfer = true
                },
                startPadding = 66.dp, // Marchio subito dopo la colonna nera del rail (allargata)
                topPadding = NuvioTheme.spacing.sm,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    // GIÙ dalla barra alta = contenuto. Senza questo handler il
                    // focus search geometrico vede prima il rail laterale (più
                    // vicino in basso a sinistra) e il focus resta "bloccato"
                    // sulla barra laterale invece di scendere sulle righe.
                    .onPreviewKeyEvent { keyEvent ->
                        if (keyEvent.type != KeyEventType.KeyDown ||
                            keyEvent.key != Key.DirectionDown
                        ) {
                            return@onPreviewKeyEvent false
                        }
                        pendingContentFocusTransfer = true
                        true
                    }
            )
        }

        if (showSidebar && (sidebarVisible || sidebarWidth > NuvioTheme.spacing.none)) {
            val panelShape = RoundedCornerShape(sidebarTokens.panelRadius)
            val showExpandedPanel = isSidebarExpanded || sidebarShowExpandedPanel

            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxHeight()
                    .width(sidebarWidth)
                    .padding(start = NuvioTheme.spacing.lg - NuvioTheme.spacing.xxs, top = NuvioTheme.spacing.lg, bottom = NuvioTheme.spacing.md, end = NuvioTheme.spacing.sm)
                    .offset {
                        IntOffset(
                            (sidebarSlideX + sidebarDeflateOffsetX).roundToPx(),
                            sidebarDeflateOffsetY.roundToPx()
                        )
                    }
                    .graphicsLayer {
                        alpha = sidebarSurfaceAlpha
                        scaleX = sidebarBloomScale
                        scaleY = sidebarBloomScale
                        transformOrigin = TransformOrigin(0f, 0f)
                    }
                    .selectableGroup()
                    .onPreviewKeyEvent { keyEvent ->
                        if (!isSidebarExpanded || keyEvent.type != KeyEventType.KeyDown) {
                            return@onPreviewKeyEvent false
                        }
                        when (keyEvent.key) {
                            Key.DirectionUp -> {
                                focusedDrawerIndex == sidebarTopBoundaryIndex
                            }

                            Key.DirectionDown -> {
                                focusedDrawerIndex == drawerItems.lastIndex
                            }

                            Key.DirectionRight, Key.DirectionLeft -> {
                                val collapseKey = if (isRtl) Key.DirectionLeft else Key.DirectionRight
                                if (keyEvent.key == collapseKey) {
                                    pendingContentFocusTransfer = true
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
                        drawerItems = primeRailItems,
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
                            pendingContentFocusTransfer = true
                        },
                        activeProfileName = activeProfileName,
                        activeProfileColorHex = activeProfileColorHex,
                        activeProfileAvatarImageUrl = activeProfileAvatarImageUrl,
                        showProfileSelector = showProfileSelector,
                        onSwitchProfile = onSwitchProfile
                    )
                }
            }

            if (sidebarShowCollapsedPill) {
                // Colonna a tutta altezza sotto le icone (stile Prime Video):
                // chiude il lato sinistro e tiene le icone nude leggibili su
                // ogni backdrop. Pieno nero per i primi ~50dp (sotto le icone),
                // poi dissolvenza orizzontale che è completa a 64dp: le card
                // luminose che scorrono dentro la colonna sbiadiscono invece
                // di essere sezionate dal bordo. I testi delle schermate
                // partono da ~64dp, quindi la dissolvenza termina prima delle
                // lettere (la colonna nera è stata allargata su richiesta:
                // "più spazio nero" per tab e icone, a livello di sistema).
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .fillMaxHeight()
                        .width(64.dp)
                        .background(
                            Brush.horizontalGradient(
                                0f to Color.Black,
                                0.78f to Color.Black,
                                0.94f to Color.Transparent,
                                1f to Color.Transparent
                            )
                        )
                        .graphicsLayer {
                            alpha = 1f - sidebarExpandProgress
                        }
                )
                // Rail stile Prime Video: colonna di sole icone nude
                // (Cerca, Libreria, Live TV, Impostazioni), sempre visibile,
                // senza etichette né pannello espanso.
                PrimeRailStrip(
                    items = primeRailItems,
                    selectedRoute = selectedDrawerRoute,
                    focusRequesters = drawerItemFocusRequesters,
                    onItemFocused = { index, focused ->
                        primeRailHasFocus = focused
                        if (focused) focusedDrawerIndex = index
                    },
                    onItemClick = { targetRoute ->
                        keyboardController?.hide()
                        onNavigate(targetRoute)
                        navigateToDrawerRoute(
                            navController = navController,
                            currentRoute = currentRoute,
                            targetRoute = targetRoute
                        )
                        pendingContentFocusTransfer = true
                    },
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .offset {
                            IntOffset(
                                (NuvioTheme.spacing.xl - NuvioTheme.spacing.xs).roundToPx(),
                                sidebarDeflateOffsetY.roundToPx()
                            )
                        }
                        .graphicsLayer {
                            val progress = sidebarExpandProgress
                            alpha = 1f - progress
                            transformOrigin = TransformOrigin(0f, 0f)
                        }
                )
            }
        }
    }
}

@Composable
private fun PrimeRailStrip(
    items: List<DrawerItem>,
    selectedRoute: String?,
    focusRequesters: Map<String, FocusRequester>,
    onItemFocused: (Int, Boolean) -> Unit,
    onItemClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        items.forEachIndexed { index, item ->
            key(item.route) {
                PrimeRailItem(
                    iconRes = item.iconRes,
                    icon = item.icon,
                    selected = selectedRoute == item.route,
                    focusRequester = focusRequesters[item.route],
                    onFocusChanged = { focused -> onItemFocused(index, focused) },
                    onClick = { onItemClick(item.route) }
                )
            }
        }
    }
}

@Composable
private fun PrimeRailItem(
    iconRes: Int?,
    icon: ImageVector?,
    selected: Boolean,
    focusRequester: FocusRequester?,
    onFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val colors = NuvioTheme.colors
    val shape = RoundedCornerShape(NuvioRadii.tokens.full)
    val itemSize = NuvioTheme.sizes.sidebar.leadingVisual + NuvioTheme.spacing.xs
    val background = when {
        isFocused -> colors.text.onOverlay.copy(alpha = NuvioTheme.effects.glowSoftAlpha)
        selected -> colors.SurfaceVariant
        else -> Color.Transparent
    }
    val borderColor = if (isFocused) {
        colors.text.onOverlay.copy(alpha = NuvioTheme.effects.glowStrongAlpha)
    } else {
        Color.Transparent
    }
    Card(
        onClick = onClick,
        modifier = modifier
            .size(itemSize)
            // Con il focus nel dettaglio Impostazioni le icone del rail escono
            // dal focus search geometrico: altrimenti GIÙ da una riga le
            // aggancia (più vicine in Y) invece dell'elemento sotto ("Esci").
            .focusProperties { canFocus = !PrimeRailFocusGuard.railFocusSearchExcluded.value }
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged {
                isFocused = it.hasFocus
                onFocusChanged(it.hasFocus)
            },
        colors = CardDefaults.colors(
            containerColor = background,
            focusedContainerColor = background
        ),
        border = CardDefaults.border(
            border = androidx.tv.material3.Border.None,
            focusedBorder = androidx.tv.material3.Border(
                border = androidx.compose.foundation.BorderStroke(
                    NuvioStrokes.tokens.thin,
                    borderColor
                ),
                shape = shape
            )
        ),
        shape = CardDefaults.shape(shape = shape)
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            DrawerItemIcon(
                iconRes = iconRes,
                icon = icon,
                modifier = Modifier.size(itemSize - NuvioTheme.spacing.md),
                tint = colors.text.onOverlay
            )
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
    modifier: Modifier = Modifier,
    onExpand: () -> Unit
) {
    val pillShape = RoundedCornerShape(NuvioRadii.tokens.full)
    val colors = NuvioTheme.colors
    val bgElevated = colors.BackgroundElevated
    val bgCard = colors.BackgroundCard
    val borderBase = colors.Border
    val mediaColors = colors.media
    val pillBackgroundBrush = remember(blurEnabled, bgElevated, bgCard, mediaColors) {
        if (blurEnabled) {
            Brush.verticalGradient(listOf(mediaColors.glassPanelTop, mediaColors.glassPanelBottom))
        } else {
            Brush.verticalGradient(listOf(bgElevated, bgCard))
        }
    }
    val pillBorderColor = remember(blurEnabled, borderBase) {
        if (blurEnabled) NuvioPrimitives.white.copy(alpha = 0.14f) else borderBase.copy(alpha = 0.9f)
    }

    Row(
        modifier = modifier
            .focusProperties { canFocus = false }
            .animateContentSize()
            .clickable(onClick = onExpand)
            .padding(horizontal = NuvioTheme.spacing.hairline, vertical = NuvioTheme.spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(0.25.dp)
    ) {
        if (!iconOnly) {
            Image(
                painter = painterResource(id = R.drawable.ic_chevron_compact_left),
                contentDescription = stringResource(R.string.cd_expand_sidebar),
                modifier = Modifier
                    .width(8.5.dp)
                    .height(NuvioTheme.spacing.lg)
                    .offset(y = (-0.5).dp)
            )
        }

        Box(
            modifier = Modifier
                .height(NuvioTheme.sizes.player.control)
                .graphicsLayer {
                    shape = pillShape
                    clip = true
                }
                .clip(pillShape)
                .background(brush = pillBackgroundBrush, shape = pillShape)
                .border(width = NuvioStrokes.tokens.hairline, color = pillBorderColor, shape = pillShape)
        ) {
            Row(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight()
                    .padding(start = 5.dp, end = if (iconOnly) 5.dp else NuvioTheme.spacing.md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (iconOnly) NuvioTheme.spacing.none else 9.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(NuvioTheme.sizes.sidebar.leadingVisual)
                        .clip(CircleShape)
                        .background(NuvioTheme.colors.SurfaceVariant),
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

                if (!iconOnly) {
                    Text(
                        text = label,
                        color = NuvioTheme.colors.text.onOverlay,
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
