package com.omnio.phone.ui

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.omnio.phone.R
import com.omnio.phone.ui.screens.addons.AddonsScreen
import com.omnio.phone.ui.screens.auth.AuthScreen
import com.omnio.phone.ui.screens.detail.DetailScreen
import com.omnio.phone.ui.screens.home.HomeScreen
import com.omnio.phone.ui.screens.library.PhoneLibraryScreen
import com.omnio.phone.ui.screens.player.PhonePlayerRoute
import com.omnio.phone.ui.screens.player.PhonePlayerScreen
import com.omnio.phone.ui.screens.profiles.ProfilesScreen
import com.omnio.phone.ui.screens.search.PhoneSearchScreen
import com.omnio.phone.ui.screens.seeall.PhoneSeeAllRoute
import com.omnio.phone.ui.screens.seeall.PhoneSeeAllScreen
import com.omnio.phone.ui.screens.settings.PhoneSettingsScreen
import com.omnio.phone.ui.screens.splash.SplashScreen
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object PhoneRoutes {
    const val AUTH = "auth"
    const val HOME = "home"
    const val LIBRARY = "library"
    const val PROFILES = "profiles"
    const val ADDONS = "addons"
    const val SEARCH = "search"
    const val SETTINGS = "settings"
    const val DETAIL = "detail/{contentId}/{contentType}"
    val PLAYER = PhonePlayerRoute.ROUTE
    val SEE_ALL = PhoneSeeAllRoute.ROUTE

    fun detail(contentId: String, contentType: String): String {
        val encodedId = URLEncoder.encode(contentId, StandardCharsets.UTF_8.name())
        val encodedType = URLEncoder.encode(contentType, StandardCharsets.UTF_8.name())
        return "detail/$encodedId/$encodedType"
    }
}

@Composable
fun PhoneApp(viewModel: AppViewModel = hiltViewModel()) {
    val gate by viewModel.gate.collectAsStateWithLifecycle()

    when (gate) {
        AppGate.Initializing -> SplashScreen(message = "Loading…")
        AppGate.SignedOut -> AuthScreen()
        AppGate.PreparingLibrary -> SplashScreen()
        AppGate.Ready -> SignedInNav()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SignedInNav() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: PhoneRoutes.PROFILES
    val isTabRoute = currentRoute in setOf(
        PhoneRoutes.HOME,
        PhoneRoutes.LIBRARY,
        PhoneRoutes.PROFILES,
        PhoneRoutes.ADDONS
    )

    Scaffold(
        topBar = {
            if (isTabRoute) {
                TopAppBar(
                    title = {
                        Text(
                            text = when (currentRoute) {
                                PhoneRoutes.HOME -> stringResource(R.string.home_tab_label)
                                PhoneRoutes.LIBRARY -> stringResource(R.string.library_tab_label)
                                PhoneRoutes.PROFILES -> stringResource(R.string.profiles_tab_label)
                                PhoneRoutes.ADDONS -> stringResource(R.string.addons_tab_label)
                                else -> "OmnioTV"
                            }
                        )
                    },
                    actions = {
                        if (currentRoute == PhoneRoutes.HOME) {
                            IconButton(onClick = {
                                navController.navigate(PhoneRoutes.SEARCH) {
                                    launchSingleTop = true
                                }
                            }) {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = stringResource(R.string.cd_search)
                                )
                            }
                        }
                        if (currentRoute == PhoneRoutes.PROFILES) {
                            IconButton(onClick = {
                                navController.navigate(PhoneRoutes.SETTINGS) {
                                    launchSingleTop = true
                                }
                            }) {
                                Icon(
                                    imageVector = Icons.Default.Settings,
                                    contentDescription = stringResource(R.string.cd_settings)
                                )
                            }
                        }
                    }
                )
            }
        },
        bottomBar = {
            if (isTabRoute) BottomTabBar(navController, currentRoute)
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = PhoneRoutes.PROFILES,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            composable(PhoneRoutes.PROFILES) {
                ProfilesScreen(onProfileSelected = {
                    navController.navigate(PhoneRoutes.HOME) {
                        popUpTo(PhoneRoutes.PROFILES) { inclusive = false }
                        launchSingleTop = true
                    }
                })
            }
            composable(PhoneRoutes.HOME) {
                HomeScreen(
                    onNavigateToAddons = {
                        navController.navigate(PhoneRoutes.ADDONS) {
                            launchSingleTop = true
                        }
                    },
                    onItemClick = { item ->
                        navController.navigate(
                            PhoneRoutes.detail(
                                contentId = item.id,
                                contentType = item.apiType
                            )
                        )
                    },
                    onContinueWatchingClick = { progress ->
                        navController.navigate(
                            PhoneRoutes.detail(
                                contentId = progress.contentId,
                                contentType = progress.contentType
                            )
                        )
                    },
                    onSeeAllClick = { row ->
                        navController.navigate(PhoneSeeAllRoute.create(row))
                    }
                )
            }
            composable(
                route = PhoneRoutes.SEE_ALL,
                arguments = PhoneSeeAllRoute.navArguments()
            ) {
                PhoneSeeAllScreen(
                    onBack = { navController.popBackStack() },
                    onItemClick = { item ->
                        navController.navigate(
                            PhoneRoutes.detail(
                                contentId = item.id,
                                contentType = item.apiType
                            )
                        )
                    }
                )
            }
            composable(PhoneRoutes.LIBRARY) {
                PhoneLibraryScreen(
                    onItemClick = { item ->
                        navController.navigate(
                            PhoneRoutes.detail(
                                contentId = item.id,
                                contentType = item.apiType
                            )
                        )
                    }
                )
            }
            composable(PhoneRoutes.ADDONS) {
                AddonsScreen()
            }
            composable(PhoneRoutes.SETTINGS) {
                PhoneSettingsScreen(onBack = { navController.popBackStack() })
            }
            composable(PhoneRoutes.SEARCH) {
                PhoneSearchScreen(
                    onBack = { navController.popBackStack() },
                    onItemClick = { item ->
                        navController.navigate(
                            PhoneRoutes.detail(
                                contentId = item.id,
                                contentType = item.apiType
                            )
                        )
                    }
                )
            }
            composable(
                route = PhoneRoutes.DETAIL,
                arguments = listOf(
                    navArgument("contentId") { type = NavType.StringType },
                    navArgument("contentType") { type = NavType.StringType }
                )
            ) {
                DetailScreen(
                    onBack = { navController.popBackStack() },
                    onPlayRequest = { request ->
                        val streamUrl = request.stream.getStreamUrl() ?: return@DetailScreen
                        navController.navigate(
                            PhonePlayerRoute.create(
                                streamUrl = streamUrl,
                                title = request.title,
                                streamName = request.stream.getDisplayName(),
                                year = request.year,
                                headers = request.stream.behaviorHints?.proxyHeaders?.request,
                                contentId = request.contentId,
                                contentType = request.contentType,
                                contentName = request.contentName,
                                poster = request.poster,
                                backdrop = request.backdrop,
                                logo = request.logo,
                                videoId = request.videoId,
                                season = request.season,
                                episode = request.episode,
                                episodeTitle = request.episodeTitle,
                                bingeGroup = request.stream.behaviorHints?.bingeGroup,
                                filename = request.stream.behaviorHints?.filename,
                                videoHash = request.stream.behaviorHints?.videoHash,
                                videoSize = request.stream.behaviorHints?.videoSize,
                                addonName = request.stream.addonName,
                                addonLogo = request.stream.addonLogo,
                                streamDescription = request.stream.description,
                                sourceProvider = request.stream.sourceProvider,
                                providerItemId = request.stream.providerItemId,
                                providerMediaSourceId = request.stream.providerMediaSourceId
                            )
                        )
                    }
                )
            }
            composable(
                route = PhoneRoutes.PLAYER,
                arguments = PhonePlayerRoute.navArguments()
            ) {
                PhonePlayerScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}

@Composable
private fun BottomTabBar(navController: NavHostController, currentRoute: String) {
    NavigationBar {
        BottomTabItem(
            navController = navController,
            currentRoute = currentRoute,
            route = PhoneRoutes.HOME,
            icon = Icons.Default.Home,
            label = stringResource(R.string.home_tab_label)
        )
        BottomTabItem(
            navController = navController,
            currentRoute = currentRoute,
            route = PhoneRoutes.LIBRARY,
            icon = Icons.Default.BookmarkBorder,
            label = stringResource(R.string.library_tab_label)
        )
        BottomTabItem(
            navController = navController,
            currentRoute = currentRoute,
            route = PhoneRoutes.PROFILES,
            icon = Icons.Default.Person,
            label = stringResource(R.string.profiles_tab_label),
            popInclusive = true
        )
        BottomTabItem(
            navController = navController,
            currentRoute = currentRoute,
            route = PhoneRoutes.ADDONS,
            icon = Icons.Default.Extension,
            label = stringResource(R.string.addons_tab_label)
        )
    }
}

@Composable
private fun RowScope.BottomTabItem(
    navController: NavHostController,
    currentRoute: String,
    route: String,
    icon: ImageVector,
    label: String,
    popInclusive: Boolean = false
) {
    val selected = currentRoute == route
    NavigationBarItem(
        selected = selected,
        onClick = {
            if (!selected) {
                navController.navigate(route) {
                    popUpTo(PhoneRoutes.PROFILES) { inclusive = popInclusive }
                    launchSingleTop = true
                }
            }
        },
        icon = { Icon(imageVector = icon, contentDescription = label) },
        label = { Text(label) }
    )
}
