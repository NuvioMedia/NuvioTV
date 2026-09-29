package com.nuvio.tv.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.nuvio.tv.ui.screens.cast.CastDetailScreen
import java.util.WeakHashMap
import com.nuvio.tv.ui.screens.detail.MetaDetailsScreen
import com.nuvio.tv.ui.screens.tmdb.TmdbEntityBrowseScreen
import com.nuvio.tv.ui.theme.NuvioTheme

internal const val DETAIL_CHILD_IDLE = "detail_child_idle"

internal val LocalDetailChildClosedEpoch = staticCompositionLocalOf { 0 }

internal val LocalDetailChildOverlayVisible = staticCompositionLocalOf { false }

private const val DETAIL_CHILD_MAX_NESTED_DEPTH = 8

private val nestedDetailRebuildGates = WeakHashMap<NavHostController, NestedDetailRebuildGate>()

private class NestedDetailRebuildGate {
    var isRebuilding by mutableStateOf(false)
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun DetailChildHost(
    parentNavController: NavHostController,
    content: @Composable (childNav: NavHostController) -> Unit
) {
    val childNav = rememberNavController()
    val childBackStack by childNav.currentBackStack.collectAsState()
    val showingChild = childBackStack.any { it.toSavedChildRoute() != null }
    var savedChildRoutes by rememberSaveable { mutableStateOf(listOf<String>()) }
    var childRoutesRestored by remember { mutableStateOf(false) }
    var overlayOpen by rememberSaveable { mutableStateOf(false) }
    var closedEpoch by rememberSaveable { mutableIntStateOf(0) }
    val rebuildGate = remember { NestedDetailRebuildGate() }
    val overlayVisible = overlayOpen ||
        showingChild ||
        (!childRoutesRestored && savedChildRoutes.isNotEmpty()) ||
        rebuildGate.isRebuilding

    DisposableEffect(childNav) {
        nestedDetailRebuildGates[childNav] = rebuildGate
        val listener = NavController.OnDestinationChangedListener { _, destination, _ ->
            val nowOpen = destination.route.orEmpty().let { it.isNotEmpty() && it != DETAIL_CHILD_IDLE }
            if (overlayOpen && !nowOpen && !rebuildGate.isRebuilding) {
                closedEpoch += 1
            }
            overlayOpen = nowOpen
        }
        childNav.addOnDestinationChangedListener(listener)
        onDispose {
            childNav.removeOnDestinationChangedListener(listener)
            if (nestedDetailRebuildGates[childNav] === rebuildGate) {
                nestedDetailRebuildGates.remove(childNav)
            }
        }
    }

    LaunchedEffect(Unit) {
        childNav.popBackStack(DETAIL_CHILD_IDLE, inclusive = false)
        applyNestedDetailCap(savedChildRoutes).forEach { route ->
            childNav.navigate(route)
        }
        childRoutesRestored = true
    }
    LaunchedEffect(childBackStack, childRoutesRestored) {
        if (!childRoutesRestored) return@LaunchedEffect
        savedChildRoutes = childBackStack.mapNotNull { it.toSavedChildRoute() }
    }
    BackHandler(enabled = overlayVisible) {
        childNav.popBackStack()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        CompositionLocalProvider(
            LocalDetailChildClosedEpoch provides closedEpoch,
            LocalDetailChildOverlayVisible provides overlayVisible
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawWithContent {
                        if (!overlayVisible) drawContent()
                    }
                    .focusRestorer()
                    .focusGroup()
                    .focusProperties {
                        onEnter = {
                            if (overlayVisible) cancelFocusChange()
                        }
                    }
            ) {
                content(childNav)
            }
        }
        if (overlayVisible) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(NuvioTheme.colors.Background)
            )
        }
        CompositionLocalProvider(LocalDetailChildOverlayVisible provides false) {
            NavHost(
                navController = childNav,
                startDestination = DETAIL_CHILD_IDLE,
                modifier = if (overlayVisible) Modifier.fillMaxSize() else Modifier.size(0.dp),
                enterTransition = { EnterTransition.None },
                exitTransition = { ExitTransition.None },
                popEnterTransition = { EnterTransition.None },
                popExitTransition = { ExitTransition.None }
            ) {
                composable(DETAIL_CHILD_IDLE) {}
                composable(
                    route = Screen.CastDetail.route,
                    arguments = listOf(
                        navArgument("personId") { type = NavType.StringType },
                        navArgument("personName") { type = NavType.StringType },
                        navArgument("preferCrew") {
                            type = NavType.BoolType
                            defaultValue = false
                        }
                    )
                ) {
                    Box(modifier = Modifier.fillMaxSize().background(NuvioTheme.colors.Background)) {
                        CastDetailScreen(
                            onBackPress = { childNav.popBackStack() },
                            onNavigateToDetail = { itemId, itemType, addonBaseUrl ->
                                childNav.navigateNestedDetail(itemId, itemType, addonBaseUrl)
                            }
                        )
                    }
                }
                composable(
                    route = Screen.TmdbEntityBrowse.route,
                    arguments = listOf(
                        navArgument("entityKind") { type = NavType.StringType },
                        navArgument("entityId") { type = NavType.IntType },
                        navArgument("entityName") { type = NavType.StringType },
                        navArgument("sourceType") {
                            type = NavType.StringType
                            defaultValue = "tv"
                        }
                    )
                ) {
                    Box(modifier = Modifier.fillMaxSize().background(NuvioTheme.colors.Background)) {
                        TmdbEntityBrowseScreen(
                            onBackPress = { childNav.popBackStack() },
                            onNavigateToDetail = { itemId, itemType, addonBaseUrl ->
                                childNav.navigateNestedDetail(itemId, itemType, addonBaseUrl)
                            }
                        )
                    }
                }
                composable(
                    route = Screen.Detail.route,
                    arguments = listOf(
                        navArgument("itemId") { type = NavType.StringType },
                        navArgument("itemType") { type = NavType.StringType },
                        navArgument("addonBaseUrl") {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = null
                        },
                        navArgument("returnFocusSeason") {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = null
                        },
                        navArgument("returnFocusEpisode") {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = null
                        },
                        navArgument("returnToHomeOnBack") {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = "false"
                        },
                        navArgument("heroBackdropUrl") {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = null
                        },
                        navArgument("playOnLoad") {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = "false"
                        },
                        navArgument("manualSelection") {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = "false"
                        }
                    )
                ) { nestedEntry ->
                    val nestedArgs = nestedEntry.arguments
                    Box(modifier = Modifier.fillMaxSize().background(NuvioTheme.colors.Background)) {
                    MetaDetailsScreen(
                        viewModel = hiltViewModel(nestedEntry),
                        heroBackdropUrl = nestedArgs?.getString("heroBackdropUrl")?.takeIf { it.isNotBlank() },
                        playOnLoad = nestedArgs?.getString("playOnLoad")?.toBooleanStrictOrNull() == true,
                        playOnLoadManually = nestedArgs?.getString("manualSelection")?.toBooleanStrictOrNull() == true,
                        onBackPress = { childNav.popBackStack() },
                        onNavigateToCastDetail = { personId, personName, preferCrew ->
                            childNav.navigate(Screen.CastDetail.createRoute(personId, personName, preferCrew))
                        },
                        onNavigateToTmdbEntityBrowse = { entityKind, entityId, entityName, sourceType ->
                            childNav.navigate(
                                Screen.TmdbEntityBrowse.createRoute(
                                    entityKind = entityKind,
                                    entityId = entityId,
                                    entityName = entityName,
                                    sourceType = sourceType
                                )
                            )
                        },
                        onNavigateToDetail = { itemId, itemType, addonBaseUrl ->
                            childNav.navigateNestedDetail(itemId, itemType, addonBaseUrl)
                        },
                        onPlayClick = parentNavController::navigateToDetailStream,
                        onPlayManuallyClick = { videoId, contentType, contentId, title, poster, backdrop, logo, season, episode, episodeName, genres, year, runtime, contentLanguage ->
                            parentNavController.navigateToDetailStream(
                                videoId, contentType, contentId, title, poster, backdrop, logo,
                                season, episode, episodeName, genres, year, runtime, contentLanguage,
                                manualSelection = true
                            )
                        },
                        onPlayStartFromBeginningClick = { videoId, contentType, contentId, title, poster, backdrop, logo, season, episode, episodeName, genres, year, runtime, contentLanguage ->
                            parentNavController.navigateToDetailStream(
                                videoId, contentType, contentId, title, poster, backdrop, logo,
                                season, episode, episodeName, genres, year, runtime, contentLanguage,
                                startFromBeginning = true
                            )
                        }
                    )
                    }
                }
            }
        }
    }
}

private fun NavBackStackEntry.toSavedChildRoute(): String? {
    val route = destination.route ?: return null
    if (route == DETAIL_CHILD_IDLE) return null
    val args = arguments ?: return null
    return when (route) {
        Screen.CastDetail.route -> {
            val personId = args.getString("personId")?.toIntOrNull() ?: return null
            val personName = args.getString("personName") ?: return null
            Screen.CastDetail.createRoute(
                personId = personId,
                personName = personName,
                preferCrew = args.getBoolean("preferCrew")
            )
        }
        Screen.TmdbEntityBrowse.route -> {
            val entityKind = args.getString("entityKind") ?: return null
            val entityName = args.getString("entityName") ?: return null
            Screen.TmdbEntityBrowse.createRoute(
                entityKind = entityKind,
                entityId = args.getInt("entityId"),
                entityName = entityName,
                sourceType = args.getString("sourceType") ?: "tv"
            )
        }
        Screen.Detail.route -> {
            val itemId = args.getString("itemId") ?: return null
            val itemType = args.getString("itemType") ?: return null
            Screen.Detail.createRoute(
                itemId = itemId,
                itemType = itemType,
                addonBaseUrl = args.getString("addonBaseUrl")?.takeIf { it.isNotBlank() },
                returnFocusSeason = args.getString("returnFocusSeason")?.toIntOrNull(),
                returnFocusEpisode = args.getString("returnFocusEpisode")?.toIntOrNull(),
                returnToHomeOnBack = args.getString("returnToHomeOnBack")?.toBooleanStrictOrNull() == true,
                heroBackdropUrl = args.getString("heroBackdropUrl")?.takeIf { it.isNotBlank() },
                playOnLoad = args.getString("playOnLoad")?.toBooleanStrictOrNull() == true,
                manualSelection = args.getString("manualSelection")?.toBooleanStrictOrNull() == true
            )
        }
        else -> null
    }
}

internal fun NavHostController.navigateNestedDetail(
    itemId: String,
    itemType: String,
    addonBaseUrl: String? = null
) {
    val route = Screen.Detail.createRoute(itemId, itemType, addonBaseUrl)
    val detailCount = currentBackStack.value.count { it.destination.route == Screen.Detail.route }
    if (detailCount < DETAIL_CHILD_MAX_NESTED_DEPTH) {
        navigate(route)
        return
    }
    val rebuilt = applyNestedDetailCap(
        listOf(DETAIL_CHILD_IDLE) +
            currentBackStack.value.mapNotNull { it.toSavedChildRoute() } +
            route
    )
    val gate = nestedDetailRebuildGates[this]
    gate?.isRebuilding = true
    try {
        popBackStack(DETAIL_CHILD_IDLE, inclusive = false)
        rebuilt.filter { it != DETAIL_CHILD_IDLE }.forEach { navigate(it) }
    } finally {
        gate?.isRebuilding = false
    }
}

private fun isNestedDetailRoute(route: String): Boolean = route.startsWith("detail/")

private fun applyNestedDetailCap(stack: List<String>): List<String> {
    val detailIndices = stack.withIndex().filter { isNestedDetailRoute(it.value) }.map { it.index }
    if (detailIndices.size <= DETAIL_CHILD_MAX_NESTED_DEPTH) return stack
    // Keep the newest N Details as a suffix. Do not assume index 0 is idle —
    // savedChildRoutes (process/config replay) never includes DETAIL_CHILD_IDLE.
    val firstKept = detailIndices[detailIndices.size - DETAIL_CHILD_MAX_NESTED_DEPTH]
    return stack.filterIndexed { index, route ->
        route == DETAIL_CHILD_IDLE || index >= firstKept
    }
}

internal fun NavHostController.navigateToDetailStream(
    videoId: String,
    contentType: String,
    contentId: String,
    title: String,
    poster: String?,
    backdrop: String?,
    logo: String?,
    season: Int?,
    episode: Int?,
    episodeName: String?,
    genres: String?,
    year: String?,
    runtime: Int?,
    contentLanguage: String?,
    manualSelection: Boolean = false,
    startFromBeginning: Boolean = false
) {
    navigate(
        Screen.Stream.createRoute(
            videoId = videoId,
            contentType = contentType,
            title = title,
            poster = poster,
            backdrop = backdrop,
            logo = logo,
            season = season,
            episode = episode,
            episodeName = episodeName,
            genres = genres,
            year = year,
            contentId = contentId,
            contentName = title,
            runtime = runtime,
            manualSelection = manualSelection,
            startFromBeginning = startFromBeginning,
            returnToDetailOnBack = contentType.equals("series", ignoreCase = true),
            contentLanguage = contentLanguage
        )
    )
}
