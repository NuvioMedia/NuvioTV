package com.nuvio.tv.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import com.nuvio.tv.ui.screens.CatalogSeeAllScreen
import com.nuvio.tv.ui.screens.ServerCatalogViewModel
import com.nuvio.tv.ui.screens.home.HomeViewModel

@Composable
internal fun ServerCatalogDestination(
    navController: NavHostController,
    backStackEntry: NavBackStackEntry
) {
    val homeBackStackEntry = remember {
        runCatching { navController.getBackStackEntry(Screen.Home.route) }.getOrNull()
    }
    val homeViewModel: HomeViewModel = hiltViewModel(homeBackStackEntry ?: backStackEntry)
    val serverCatalog: ServerCatalogViewModel = hiltViewModel(backStackEntry)
    CatalogSeeAllScreen(
        catalogId = "",
        addonId = "",
        type = "",
        serverCatalog = serverCatalog,
        viewModel = homeViewModel,
        onNavigateToDetail = { itemId, itemType, addonBaseUrl ->
            navController.navigate(Screen.Detail.createRoute(itemId, itemType, addonBaseUrl))
        },
        onBackPress = { navController.popBackStack() }
    )
}
