package com.nuvio.tv.ui.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.nuvio.tv.ui.screens.CatalogSeeAllScreen
import com.nuvio.tv.ui.screens.LayoutSelectionScreen
import com.nuvio.tv.ui.screens.detail.MetaDetailsScreen
import com.nuvio.tv.ui.screens.home.HomeScreen
import com.nuvio.tv.ui.screens.addon.AddonManagerScreen
import com.nuvio.tv.ui.screens.addon.CatalogOrderScreen
import com.nuvio.tv.ui.screens.library.LibraryScreen
import com.nuvio.tv.ui.screens.player.PlayerScreen
import com.nuvio.tv.ui.screens.plugin.PluginScreen
import com.nuvio.tv.ui.screens.search.DiscoverScreen
import com.nuvio.tv.ui.screens.search.SearchScreen
import com.nuvio.tv.ui.screens.settings.AboutScreen
import com.nuvio.tv.ui.screens.settings.LayoutSettingsScreen
import com.nuvio.tv.ui.screens.settings.PlaybackSettingsScreen
import com.nuvio.tv.ui.screens.settings.SettingsScreen
import com.nuvio.tv.ui.screens.settings.ThemeSettingsScreen
import com.nuvio.tv.ui.screens.settings.TraktScreen
import com.nuvio.tv.ui.screens.settings.TmdbSettingsScreen
import com.nuvio.tv.ui.screens.stream.StreamScreen
import com.nuvio.tv.ui.screens.home.ContinueWatchingItem
import com.nuvio.tv.ui.screens.account.AuthSignInScreen
import com.nuvio.tv.ui.screens.account.AuthQrSignInScreen
import com.nuvio.tv.ui.screens.cast.CastDetailScreen

@Composable
fun NuvioNavHost(
    navController: NavHostController,
    startDestination: String = Screen.Home.route,
    hideBuiltInHeaders: Boolean = false
) {
    fun isStreamToPlayer(from: String, to: String): Boolean {
        return from.startsWith("stream/") && to.startsWith("player/")
    }

    fun isPlayerToStream(from: String, to: String): Boolean {
        return from.startsWith("player/") && to.startsWith("stream/")
    }

    NavHost(
        navController = navController,
        startDestination = startDestination,
        enterTransition = {
            val from = initialState.destination.route.orEmpty()
            val to = targetState.destination.route.orEmpty()
            val isAutoPlayNav = targetState.arguments
                ?.getString("autoPlayNav")
                ?.toBooleanStrictOrNull() == true
            if (isStreamToPlayer(from, to) && isAutoPlayNav) {
                EnterTransition.None
            } else {
                fadeIn(animationSpec = tween(350))
            }
        },
        exitTransition = {
            val from = initialState.destination.route.orEmpty()
            val to = targetState.destination.route.orEmpty()
            val isAutoPlayNav = targetState.arguments
                ?.getString("autoPlayNav")
                ?.toBooleanStrictOrNull() == true
            if (isStreamToPlayer(from, to) && isAutoPlayNav) {
                ExitTransition.None
            } else {
                fadeOut(animationSpec = tween(350))
            }
        },
        popEnterTransition = {
            val from = initialState.destination.route.orEmpty()
            val to = targetState.destination.route.orEmpty()
            val isAutoPlayNav = initialState.arguments
                ?.getString("autoPlayNav")
                ?.toBooleanStrictOrNull() == true
            if (isPlayerToStream(from, to) && isAutoPlayNav) {
                EnterTransition.None
            } else {
                fadeIn(animationSpec = tween(350))
            }
        },
        popExitTransition = {
            val from = initialState.destination.route.orEmpty()
            val to = targetState.destination.route.orEmpty()
            val isAutoPlayNav = initialState.arguments
                ?.getString("autoPlayNav")
                ?.toBooleanStrictOrNull() == true
            if (isPlayerToStream(from, to) && isAutoPlayNav) {
                ExitTransition.None
            } else {
                fadeOut(animationSpec = tween(350))
            }
        }
    ) {
        composable(Screen.LayoutSelection.route) {
            LayoutSelectionScreen(
                onContinue = {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.LayoutSelection.route) { inclusive = true }
                    }
                }
            )
        }

        composable(Screen.Home.route) {
            HomeScreen(
                onNavigateToDetail = { itemId, itemType, addonBaseUrl ->
                    navController.navigate(Screen.Detail.createRoute(itemId, itemType, addonBaseUrl))
                },
                onContinueWatchingClick = { item ->
                    val route = when (item) {
                        is ContinueWatchingItem.InProgress -> Screen.Stream.createRoute(
                            videoId = item.progress.videoId,
                            contentType = item.progress.contentType,
                            title = item.progress.name,
                            poster = item.progress.poster,
                            backdrop = item.progress.backdrop,
                            logo = item.progress.logo,
                            season = item.progress.season,
                            episode = item.progress.episode,
                            episodeName = item.progress.episodeTitle,
                            genres = null,
                            year = null,
                            contentId = item.progress.contentId,
                            contentName = item.progress.name,
                            runtime = null,
                            returnToDetailOnBack = item.progress.contentType.equals("series", ignoreCase = true)
                        )
                        is ContinueWatchingItem.NextUp -> Screen.Stream.createRoute(
                            videoId = item.info.videoId,
                            contentType = item.info.contentType,
                            title = item.info.name,
                            poster = item.info.poster,
                            backdrop = item.info.backdrop,
                            logo = item.info.logo,
                            season = item.info.season,
                            episode = item.info.episode,
                            episodeName = item.info.episodeTitle,
                            genres = null,
                            year = null,
                            contentId = item.info.contentId,
                            contentName = item.info.name,
                            runtime = null,
                            returnToDetailOnBack = item.info.contentType.equals("series", ignoreCase = true)
                        )
                    }
                    navController.navigate(route)
                },
                onNavigateToCatalogSeeAll = { catalogId, addonId, type ->
                    navController.navigate(Screen.CatalogSeeAll.createRoute(catalogId, addonId, type))
                }
            )
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
                }
            )
        ) { backStackEntry ->
            val detailArgs = backStackEntry.arguments
            MetaDetailsScreen(
                returnFocusSeason = detailArgs?.getString("returnFocusSeason")?.toIntOrNull(),
                returnFocusEpisode = detailArgs?.getString("returnFocusEpisode")?.toIntOrNull(),
                onBackPress = { navController.popBackStack() },
                onNavigateToCastDetail = { personId, personName, preferCrew ->
                    navController.navigate(Screen.CastDetail.createRoute(personId, personName, preferCrew))
                },
                onNavigateToDetail = { itemId, itemType, addonBaseUrl ->
                    navController.navigate(Screen.Detail.createRoute(itemId, itemType, addonBaseUrl))
                },
                onPlayClick = { videoId, contentType, contentId, title, poster, backdrop, logo, season, episode, episodeName, genres, year, runtime ->
                    navController.navigate(
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
                            runtime = runtime
                        )
                    )
                }
            )
        }

        composable(
            route = Screen.Stream.route,
            arguments = listOf(
                navArgument("videoId") { type = NavType.StringType },
                navArgument("contentType") { type = NavType.StringType },
                navArgument("title") { type = NavType.StringType },
                navArgument("poster") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("backdrop") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("logo") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("season") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("episode") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("episodeName") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("genres") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("year") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("contentId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("contentName") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("runtime") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("manualSelection") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = "false"
                },
                navArgument("returnToDetailOnBack") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = "false"
                }
            )
        ) { backStackEntry ->
            val streamArgs = backStackEntry.arguments
            val returnToDetailOnBack = streamArgs
                ?.getString("returnToDetailOnBack")
                ?.toBooleanStrictOrNull() == true
            StreamScreen(
                onBackPress = {
                    val streamContentType = streamArgs?.getString("contentType").orEmpty()
                    val streamContentId = streamArgs?.getString("contentId").orEmpty()
                    if (
                        returnToDetailOnBack &&
                        streamContentType.equals("series", ignoreCase = true) &&
                        streamContentId.isNotBlank()
                    ) {
                        navController.navigate(
                            Screen.Detail.createRoute(
                                itemId = streamContentId,
                                itemType = streamContentType,
                                addonBaseUrl = null,
                                returnFocusSeason = streamArgs?.getString("season")?.toIntOrNull(),
                                returnFocusEpisode = streamArgs?.getString("episode")?.toIntOrNull()
                            )
                        ) {
                            popUpTo(Screen.Stream.route) { inclusive = true }
                            launchSingleTop = true
                        }
                    } else {
                        navController.popBackStack()
                    }
                },
                onStreamSelected = { playbackInfo ->
                    playbackInfo.url?.let { url ->
                        navController.navigate(
                            Screen.Player.createRoute(
                                streamUrl = url,
                                title = playbackInfo.title,
                                streamName = playbackInfo.streamName,
                                year = playbackInfo.year,
                                headers = playbackInfo.headers,
                                contentId = playbackInfo.contentId,
                                contentType = playbackInfo.contentType,
                                contentName = playbackInfo.contentName,
                                poster = playbackInfo.poster,
                                backdrop = playbackInfo.backdrop,
                                logo = playbackInfo.logo,
                                videoId = playbackInfo.videoId,
                                season = playbackInfo.season,
                                episode = playbackInfo.episode,
                                episodeTitle = playbackInfo.episodeTitle,
                                bingeGroup = playbackInfo.bingeGroup,
                                rememberedAudioLanguage = playbackInfo.rememberedAudioLanguage,
                                rememberedAudioName = playbackInfo.rememberedAudioName,
                                autoPlayNav = false,
                                returnToDetailOnBack = returnToDetailOnBack
                            )
                        )
                    }
                },
                onAutoPlayResolved = { playbackInfo ->
                    playbackInfo.url?.let { url ->
                        navController.navigate(
                            Screen.Player.createRoute(
                                streamUrl = url,
                                title = playbackInfo.title,
                                streamName = playbackInfo.streamName,
                                year = playbackInfo.year,
                                headers = playbackInfo.headers,
                                contentId = playbackInfo.contentId,
                                contentType = playbackInfo.contentType,
                                contentName = playbackInfo.contentName,
                                poster = playbackInfo.poster,
                                backdrop = playbackInfo.backdrop,
                                logo = playbackInfo.logo,
                                videoId = playbackInfo.videoId,
                                season = playbackInfo.season,
                                episode = playbackInfo.episode,
                                episodeTitle = playbackInfo.episodeTitle,
                                bingeGroup = playbackInfo.bingeGroup,
                                rememberedAudioLanguage = playbackInfo.rememberedAudioLanguage,
                                rememberedAudioName = playbackInfo.rememberedAudioName,
                                autoPlayNav = true,
                                returnToDetailOnBack = returnToDetailOnBack
                            )
                        ) {
                            popUpTo(Screen.Stream.route) { inclusive = true }
                        }
                    }
                }
            )
        }

        composable(
            route = Screen.Player.route,
            arguments = listOf(
                navArgument("streamUrl") { type = NavType.StringType },
                navArgument("title") { type = NavType.StringType },
                navArgument("streamName") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("year") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("headers") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("contentId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("contentType") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("contentName") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("poster") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("backdrop") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("logo") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("videoId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("season") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("episode") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("episodeTitle") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("bingeGroup") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("rememberedAudioLanguage") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("rememberedAudioName") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("autoPlayNav") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = "false"
                },
                navArgument("returnToDetailOnBack") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = "false"
                }
            )
        ) { backStackEntry ->
            PlayerScreen(
                onBackPress = {
                    val returnedToStream = navController.popBackStack(Screen.Stream.route, inclusive = false)
                    if (!returnedToStream) {
                        val args = backStackEntry.arguments
                        val returnToDetailOnBack = args?.getString("returnToDetailOnBack")
                            ?.toBooleanStrictOrNull() == true
                        val contentType = args?.getString("contentType").orEmpty()
                        val contentId = args?.getString("contentId").orEmpty()
                        if (
                            returnToDetailOnBack &&
                            contentType.equals("series", ignoreCase = true) &&
                            contentId.isNotBlank()
                        ) {
                            navController.navigate(
                                Screen.Detail.createRoute(
                                    itemId = contentId,
                                    itemType = contentType,
                                    addonBaseUrl = null,
                                    returnFocusSeason = args?.getString("season")?.toIntOrNull(),
                                    returnFocusEpisode = args?.getString("episode")?.toIntOrNull()
                                )
                            ) {
                                popUpTo(Screen.Player.route) { inclusive = true }
                                launchSingleTop = true
                            }
                        } else {
                            navController.popBackStack()
                        }
                    }
                },
                onPlaybackErrorBack = {
                    val returnedToStream = navController.popBackStack(Screen.Stream.route, inclusive = false)
                    if (!returnedToStream) {
                        val args = backStackEntry.arguments
                        val videoId = args?.getString("videoId").orEmpty()
                        val contentType = args?.getString("contentType").orEmpty()
                        val title = args?.getString("title").orEmpty()

                        if (videoId.isBlank() || contentType.isBlank() || title.isBlank()) {
                            navController.popBackStack()
                        } else {
                            val route = Screen.Stream.createRoute(
                                videoId = videoId,
                                contentType = contentType,
                                title = title,
                                poster = args?.getString("poster"),
                                backdrop = args?.getString("backdrop"),
                                logo = args?.getString("logo"),
                                season = args?.getString("season")?.toIntOrNull(),
                                episode = args?.getString("episode")?.toIntOrNull(),
                                episodeName = args?.getString("episodeTitle"),
                                genres = null,
                                year = args?.getString("year"),
                                contentId = args?.getString("contentId"),
                                contentName = args?.getString("contentName"),
                                runtime = null,
                                manualSelection = true,
                                returnToDetailOnBack = args?.getString("returnToDetailOnBack")
                                    ?.toBooleanStrictOrNull() == true
                            )

                            navController.navigate(route) {
                                popUpTo(Screen.Player.route) { inclusive = true }
                                launchSingleTop = true
                            }
                        }
                    }
                }
            )
        }

        composable(Screen.Search.route) {
            SearchScreen(
                onNavigateToDetail = { itemId, itemType, addonBaseUrl ->
                    navController.navigate(Screen.Detail.createRoute(itemId, itemType, addonBaseUrl))
                },
                onNavigateToSeeAll = { catalogId, addonId, type ->
                    navController.navigate(Screen.CatalogSeeAll.createRoute(catalogId, addonId, type))
                },
                onOpenDiscover = { navController.navigate(Screen.Discover.route) }
            )
        }

        composable(Screen.Discover.route) {
            DiscoverScreen(
                onNavigateToDetail = { itemId, itemType, addonBaseUrl ->
                    navController.navigate(Screen.Detail.createRoute(itemId, itemType, addonBaseUrl))
                }
            )
        }

        composable(Screen.Library.route) {
            LibraryScreen(
                showBuiltInHeader = !hideBuiltInHeaders,
                onNavigateToDetail = { itemId, itemType, addonBaseUrl ->
                    navController.navigate(Screen.Detail.createRoute(itemId, itemType, addonBaseUrl))
                }
            )
        }

        composable(Screen.Settings.route) {
            SettingsScreen(
                showBuiltInHeader = !hideBuiltInHeaders,
                onNavigateToTrakt = { navController.navigate(Screen.Trakt.route) },
                onNavigateToAuthQrSignIn = { navController.navigate(Screen.AuthQrSignIn.route) }
            )
        }

        composable(Screen.Trakt.route) {
            TraktScreen(
                onBackPress = { navController.popBackStack() }
            )
        }

        composable(Screen.TmdbSettings.route) {
            TmdbSettingsScreen(
                onBackPress = { navController.popBackStack() }
            )
        }

        composable(Screen.ThemeSettings.route) {
            ThemeSettingsScreen(
                onBackPress = { navController.popBackStack() }
            )
        }

        composable(Screen.PlaybackSettings.route) {
            PlaybackSettingsScreen(
                onBackPress = { navController.popBackStack() }
            )
        }

        composable(Screen.About.route) {
            AboutScreen(
                onBackPress = { navController.popBackStack() }
            )
        }

        composable(Screen.AddonManager.route) {
            AddonManagerScreen(
                showBuiltInHeader = !hideBuiltInHeaders,
                onNavigateToCatalogOrder = { navController.navigate(Screen.CatalogOrder.route) }
            )
        }

        composable(Screen.CatalogOrder.route) {
            CatalogOrderScreen(
                onBackPress = { navController.popBackStack() }
            )
        }

        composable(Screen.Plugins.route) {
            PluginScreen(
                onBackPress = { navController.popBackStack() }
            )
        }

        composable(Screen.Account.route) {
            AuthQrSignInScreen(
                onBackPress = { navController.popBackStack() }
            )
        }

        composable(Screen.AuthSignIn.route) {
            AuthSignInScreen(
                onBackPress = { navController.popBackStack() },
                onNavigateToQrSignIn = { navController.navigate(Screen.AuthQrSignIn.route) },
                onSuccess = { navController.popBackStack() }
            )
        }

        composable(Screen.AuthQrSignIn.route) {
            AuthQrSignInScreen(
                onBackPress = { navController.popBackStack() }
            )
        }

        composable(Screen.LayoutSettings.route) {
            LayoutSettingsScreen(
                onBackPress = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.CatalogSeeAll.route,
            arguments = listOf(
                navArgument("catalogId") { type = NavType.StringType },
                navArgument("addonId") { type = NavType.StringType },
                navArgument("type") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val catalogId = backStackEntry.arguments?.getString("catalogId") ?: ""
            val addonId = backStackEntry.arguments?.getString("addonId") ?: ""
            val type = backStackEntry.arguments?.getString("type") ?: ""
            CatalogSeeAllScreen(
                catalogId = catalogId,
                addonId = addonId,
                type = type,
                onNavigateToDetail = { itemId, itemType, addonBaseUrl ->
                    navController.navigate(Screen.Detail.createRoute(itemId, itemType, addonBaseUrl))
                },
                onBackPress = { navController.popBackStack() }
            )
        }

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
            CastDetailScreen(
                onBackPress = { navController.popBackStack() },
                onNavigateToDetail = { itemId, itemType, addonBaseUrl ->
                    navController.navigate(Screen.Detail.createRoute(itemId, itemType, addonBaseUrl))
                }
            )
        }
    }
}
