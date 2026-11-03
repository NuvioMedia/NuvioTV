package com.nuvio.tv.ui.reshaped.livetv

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavHostController
import com.nuvio.tv.DrawerItem
import com.nuvio.tv.R
import com.nuvio.tv.reshaped.livetv.LIVE_TV_ROUTE
import com.nuvio.tv.reshaped.livetv.rememberLiveTvEnabled
import com.nuvio.tv.ui.navigation.Screen

/**
 * The Live TV menu entry, or null while Live TV is off in Nuvio Reshaped settings. Turning it off
 * while on the Live TV screen goes back to Home.
 */
@Composable
fun rememberLiveTvMenuItem(currentRoute: String?, navController: NavHostController): DrawerItem? {
    val enabled = rememberLiveTvEnabled()
    LaunchedEffect(enabled, currentRoute) {
        if (!enabled && currentRoute == LIVE_TV_ROUTE) {
            navController.navigate(Screen.Home.route) {
                popUpTo(navController.graph.startDestinationId) { saveState = false }
                launchSingleTop = true
            }
        }
    }
    val label = stringResource(R.string.live_tv_title)
    return remember(enabled, label) {
        if (enabled) DrawerItem(route = LIVE_TV_ROUTE, label = label, icon = Icons.Default.LiveTv) else null
    }
}
