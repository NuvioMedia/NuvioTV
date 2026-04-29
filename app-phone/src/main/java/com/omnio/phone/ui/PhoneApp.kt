package com.omnio.phone.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
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
import com.omnio.phone.ui.screens.profiles.ProfilesScreen
import com.omnio.phone.ui.screens.splash.SplashScreen
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object PhoneRoutes {
    const val AUTH = "auth"
    const val HOME = "home"
    const val PROFILES = "profiles"
    const val ADDONS = "addons"
    const val DETAIL = "detail/{contentId}/{contentType}"

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
                                PhoneRoutes.PROFILES -> stringResource(R.string.profiles_tab_label)
                                PhoneRoutes.ADDONS -> stringResource(R.string.addons_tab_label)
                                else -> "OmnioTV"
                            }
                        )
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
                    }
                )
            }
            composable(PhoneRoutes.ADDONS) {
                AddonsScreen()
            }
            composable(
                route = PhoneRoutes.DETAIL,
                arguments = listOf(
                    navArgument("contentId") { type = NavType.StringType },
                    navArgument("contentType") { type = NavType.StringType }
                )
            ) {
                DetailScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}

@Composable
private fun BottomTabBar(navController: NavHostController, currentRoute: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        TabBarButton(
            label = stringResource(R.string.home_tab_label),
            isCurrent = currentRoute == PhoneRoutes.HOME,
            onClick = {
                navController.navigate(PhoneRoutes.HOME) {
                    popUpTo(PhoneRoutes.PROFILES) { inclusive = false }
                    launchSingleTop = true
                }
            }
        )
        TabBarButton(
            label = stringResource(R.string.profiles_tab_label),
            isCurrent = currentRoute == PhoneRoutes.PROFILES,
            onClick = {
                navController.navigate(PhoneRoutes.PROFILES) {
                    popUpTo(PhoneRoutes.PROFILES) { inclusive = true }
                    launchSingleTop = true
                }
            }
        )
        TabBarButton(
            label = stringResource(R.string.addons_tab_label),
            isCurrent = currentRoute == PhoneRoutes.ADDONS,
            onClick = {
                navController.navigate(PhoneRoutes.ADDONS) {
                    popUpTo(PhoneRoutes.PROFILES) { inclusive = false }
                    launchSingleTop = true
                }
            }
        )
    }
}

@Composable
private fun TabBarButton(label: String, isCurrent: Boolean, onClick: () -> Unit) {
    TextButton(onClick = { if (!isCurrent) onClick() }) { Text(label) }
}
