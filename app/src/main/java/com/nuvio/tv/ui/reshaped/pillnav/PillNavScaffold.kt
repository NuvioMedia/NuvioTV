package com.nuvio.tv.ui.reshaped.pillnav

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavHostController
import com.nuvio.tv.DrawerItem
import com.nuvio.tv.LocalContentFocusRequester
import com.nuvio.tv.LocalSidebarExpanded
import com.nuvio.tv.ui.navigation.NuvioNavHost
import com.nuvio.tv.ui.navigation.Screen
import com.nuvio.tv.ui.screens.home.HomeViewModel

private const val PROFILE_ENTRY_KEY = "pill_nav_profile"

/**
 * Third navigation layout next to the legacy and modern sidebars: the content fills the screen and a glass
 * pill menu floats at the top centre on root screens. D-pad Up from the content reaches the pill (always on
 * the selected item), Left/Right walk it, Center/Enter opens, Down returns to the content. Back on a root
 * screen moves focus to the pill, and Back on the pill exits, like the sidebar.
 *
 * TV layout: the pill floats in the header band root screens already keep free when their built-in headers are
 * hidden (the same band the modern sidebar's floating pill uses), so screens keep their normal size and nothing
 * is inset. It tucks away (translate + fade on a graphics layer, no relayout, no blur) when focus moves down into
 * scrolling content and comes back on the way up; on Search it stays tucked away unless it has focus, like the
 * modern sidebar's pill, so it never covers the search field. [topBannerVisible] lets D-pad Up leave the pill
 * for the update banner above the navigation area.
 */
@Composable
internal fun PillNavScaffold(
    longPressBackHeld: MutableState<Boolean>,
    navController: NavHostController,
    startDestination: String,
    currentRoute: String?,
    rootRoutes: Set<String>,
    drawerItems: List<DrawerItem>,
    selectedDrawerRoute: String?,
    blurEnabled: Boolean,
    activeProfileName: String,
    activeProfileColorHex: String,
    activeProfileAvatarImageUrl: String?,
    showProfileSelector: Boolean,
    onSwitchProfile: () -> Unit,
    onNavigate: (String) -> Unit,
    onExitApp: () -> Unit,
    topBannerVisible: Boolean = false,
) {
    val showBar = currentRoute in rootRoutes
    // Settings and Live TV keep the pill in their header band; every other root screen scrolls under it, so it tucks away.
    val autoHide = showBar && currentRoute != Screen.Settings.route &&
        currentRoute != com.nuvio.tv.reshaped.livetv.LIVE_TV_ROUTE
    val hiddenUnlessFocused = currentRoute == Screen.Search.route
    val state = remember { PillNavBarState() }
    // Liquid glass on capable TVs only. The screen is recorded only while the pill is on it, never during
    // playback; the pill slides out over the last recording.
    val glassBackdrop = rememberPillGlassBackdrop()
    val focusRequesters = remember { HashMap<String, FocusRequester>() }
    val requesterFor = remember<(String) -> FocusRequester> {
        { key: String -> focusRequesters.getOrPut(key) { FocusRequester() } }
    }
    val focusManager = LocalFocusManager.current
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val keyboardController = LocalSoftwareKeyboardController.current
    val contentFocusRequester = remember { FocusRequester() }
    var pendingBarFocus by remember { mutableStateOf(false) }
    var pendingContentFocus by remember { mutableStateOf(false) }

    val showProfile = showProfileSelector && activeProfileName.isNotEmpty()
    val entries = remember(drawerItems, showProfile, activeProfileName) {
        buildList {
            drawerItems.filter { it.route != Screen.Settings.route }.forEach { item ->
                add(PillNavEntry(item.route, PillNavEntryKind.Tab, item.route, item.label, item.iconRes, item.icon))
            }
            drawerItems.firstOrNull { it.route == Screen.Settings.route }?.let { item ->
                add(PillNavEntry(item.route, PillNavEntryKind.Settings, item.route, item.label, item.iconRes, item.icon))
            }
            if (showProfile) {
                add(PillNavEntry(PROFILE_ENTRY_KEY, PillNavEntryKind.Profile, null, activeProfileName))
            }
        }
    }
    val selectedKey = selectedDrawerRoute?.takeIf { route -> entries.any { it.key == route } }

    LaunchedEffect(showBar) {
        if (!showBar) {
            pendingBarFocus = false
            pendingContentFocus = false
        }
    }

    // A new root screen starts with the pill visible, like the phone on a tab change. Coming back to the same
    // root screen from a detail screen keeps it tucked away if it was, since that screen returns scrolled.
    val lastRootRoute = remember { arrayOfNulls<String>(1) }
    LaunchedEffect(currentRoute, showBar) {
        if (!showBar || currentRoute == lastRootRoute[0]) return@LaunchedEffect
        lastRootRoute[0] = currentRoute
        state.show()
    }

    val requestBarFocus = {
        state.show()
        pendingBarFocus = true
    }

    BackHandler(enabled = showBar && !state.hasFocus) {
        requestBarFocus()
    }

    BackHandler(enabled = showBar && state.hasFocus) {
        if (longPressBackHeld.value) return@BackHandler
        onExitApp()
    }

    LaunchedEffect(pendingBarFocus, showBar, selectedKey, entries) {
        if (!pendingBarFocus) return@LaunchedEffect
        if (!showBar) {
            pendingBarFocus = false
            return@LaunchedEffect
        }
        val key = selectedKey ?: entries.firstOrNull()?.key
        if (key == null) {
            pendingBarFocus = false
            return@LaunchedEffect
        }
        repeat(2) { withFrameNanos { } }
        runCatching { requesterFor(key).requestFocus() }
        pendingBarFocus = false
    }

    // Reselecting the current tab hands focus back to its content, like the sidebar does.
    LaunchedEffect(pendingContentFocus) {
        if (!pendingContentFocus) return@LaunchedEffect
        repeat(2) { withFrameNanos { } }
        runCatching { contentFocusRequester.requestFocus() }
        withFrameNanos { }
        if (state.hasFocus) focusManager.moveFocus(FocusDirection.Down)
        pendingContentFocus = false
    }

    // Focus recovery, the pill's counterpart of the sidebar's: if the focused item disappears (e.g. Discover
    // was removed from the navigation) while the pill owns focus, land on the selected or first item.
    LaunchedEffect(entries, state.hasFocus, selectedKey) {
        if (!showBar || !state.hasFocus) return@LaunchedEffect
        val focusedKey = state.focusedKey
        if (focusedKey != null && entries.any { it.key == focusedKey }) return@LaunchedEffect
        val fallback = selectedKey ?: entries.firstOrNull()?.key ?: return@LaunchedEffect
        repeat(2) { withFrameNanos { } }
        runCatching { requesterFor(fallback).requestFocus() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { keyEvent ->
                // Swallow the rest of a long-press Back until it is released so it cannot also exit the app.
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
                .pillGlassSource(glassBackdrop?.takeIf { showBar })
                .onPreviewKeyEvent { keyEvent ->
                    if (keyEvent.key == Key.Back) {
                        // Long-press Back on a root screen jumps straight to the pill, past the screens' own Back.
                        if (
                            keyEvent.type == KeyEventType.KeyDown &&
                            showBar &&
                            keyEvent.nativeKeyEvent.isLongPress
                        ) {
                            if (!longPressBackHeld.value) {
                                longPressBackHeld.value = true
                                requestBarFocus()
                            }
                            return@onPreviewKeyEvent true
                        }
                        return@onPreviewKeyEvent false
                    }
                    // Hide-on-scroll: moving down into the content tucks the pill away, moving up brings it back.
                    if (autoHide && keyEvent.type == KeyEventType.KeyDown) {
                        when (keyEvent.key) {
                            Key.DirectionDown -> state.hide()
                            Key.DirectionUp -> state.show()
                            else -> Unit
                        }
                    }
                    false
                }
                .onKeyEvent { keyEvent ->
                    // Up that nothing in the content used: go to the pill (focus search lands on the selected
                    // item, the only one focusable from outside); fall back to requesting it directly.
                    if (
                        showBar &&
                        keyEvent.type == KeyEventType.KeyDown &&
                        keyEvent.key == Key.DirectionUp
                    ) {
                        if (!focusManager.moveFocus(FocusDirection.Up)) requestBarFocus()
                        true
                    } else {
                        false
                    }
                }
        ) {
            CompositionLocalProvider(
                LocalSidebarExpanded provides state.hasFocus,
                LocalContentFocusRequester provides contentFocusRequester,
            ) {
                NuvioNavHost(
                    navController = navController,
                    startDestination = startDestination,
                    // Only root screens keep their header band free for the pill; others show their own titles.
                    hideBuiltInHeaders = showBar,
                )
            }
        }

        // Slides in from the top edge when a root screen appears and out when a detail screen opens; the
        // enter/exit only moves and fades the pill's layer.
        AnimatedVisibility(
            visible = showBar && entries.isNotEmpty(),
            modifier = Modifier.align(Alignment.TopCenter),
            enter = slideInVertically(
                animationSpec = tween(durationMillis = 320, easing = LinearOutSlowInEasing),
                initialOffsetY = { -it },
            ) + fadeIn(animationSpec = tween(durationMillis = 260)),
            exit = slideOutVertically(
                animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
                targetOffsetY = { -it },
            ) + fadeOut(animationSpec = tween(durationMillis = 160)),
        ) {
            PillNavigationBar(
                entries = entries,
                selectedKey = selectedKey,
                state = state,
                requesterFor = requesterFor,
                frosted = blurEnabled,
                // While it animates out on a detail screen the pill must not be a focus target.
                interactive = showBar,
                hidden = hiddenUnlessFocused || (autoHide && state.hiddenByScroll),
                canLeaveUp = topBannerVisible,
                isRtl = isRtl,
                activeProfileColorHex = activeProfileColorHex,
                activeProfileAvatarImageUrl = activeProfileAvatarImageUrl,
                onEntryClick = { entry ->
                    val route = entry.route
                    if (entry.kind == PillNavEntryKind.Profile || route == null) {
                        onSwitchProfile()
                    } else {
                        keyboardController?.hide()
                        val reselect = currentRoute == route
                        onNavigate(route)
                        navigateToPillRoute(navController, currentRoute, route)
                        pendingContentFocus = reselect
                    }
                },
                onExitDown = {
                    if (!focusManager.moveFocus(FocusDirection.Down)) {
                        runCatching { contentFocusRequester.requestFocus() }
                    }
                },
                onExitUp = { focusManager.moveFocus(FocusDirection.Up) },
                backdrop = glassBackdrop,
            )
        }
    }
}

/** Same navigation the sidebar performs (MainActivity.navigateToDrawerRoute), kept here so the hook stays small. */
private fun navigateToPillRoute(
    navController: NavHostController,
    currentRoute: String?,
    targetRoute: String,
) {
    if (currentRoute == targetRoute) {
        if (targetRoute == Screen.Home.route) {
            val homeEntry = try {
                navController.getBackStackEntry(Screen.Home.route)
            } catch (_: IllegalArgumentException) {
                return
            }
            ViewModelProvider(homeEntry)[HomeViewModel::class.java].requestScrollToTop()
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
        Log.w("NuvioPillNav", "Route not found in nav graph: $targetRoute", e)
    }
}
