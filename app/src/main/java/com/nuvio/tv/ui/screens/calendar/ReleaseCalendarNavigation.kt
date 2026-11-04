package com.nuvio.tv.ui.screens.calendar

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavHostController
import com.nuvio.tv.DrawerItem
import com.nuvio.tv.R
import com.nuvio.tv.ui.navigation.Screen

@Composable
fun rememberReleaseCalendarMenuItem(currentRoute: String?, navController: NavHostController): DrawerItem? {
    val enabled = rememberReleaseCalendarEnabled()
    LaunchedEffect(enabled, currentRoute) {
        if (!enabled && currentRoute == RELEASE_CALENDAR_ROUTE) {
            navController.navigate(Screen.Home.route) {
                popUpTo(navController.graph.startDestinationId) { saveState = false }
                launchSingleTop = true
            }
        }
    }
    val label = stringResource(R.string.release_calendar_nav)
    return remember(enabled, label) {
        if (enabled) DrawerItem(route = RELEASE_CALENDAR_ROUTE, label = label, icon = Icons.Default.CalendarMonth) else null
    }
}
