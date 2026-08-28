package com.nutrilens.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.nutrilens.core.designsystem.R
import com.nutrilens.feature.analysis.AnalysisRoute
import com.nutrilens.feature.analytics.AnalyticsRoute
import com.nutrilens.feature.auth.AuthRoute
import com.nutrilens.feature.auth.OnboardingRoute
import com.nutrilens.feature.capture.CaptureRoute
import com.nutrilens.feature.home.HomeRoute
import com.nutrilens.feature.settings.SettingsRoute
import com.nutrilens.feature.timeline.MealDetailRoute
import com.nutrilens.feature.timeline.TimelineRoute

/**
 * The navigation graph.
 *
 * Feature modules expose one route composable each and know nothing about each
 * other; every transition between them is expressed here. That keeps features
 * independently buildable and means adding a screen touches one file plus its
 * own module.
 */
@Composable
fun NutriLensApp(
    startDestination: String,
    navController: NavHostController = rememberNavController(),
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val showChrome = currentDestination?.route in Destination.topLevel.map { it.route }

    Scaffold(
        bottomBar = {
            if (showChrome) {
                NutriLensBottomBar(
                    currentRoute = currentDestination?.route,
                    onNavigate = navController::navigateToTopLevel,
                )
            }
        },
        floatingActionButton = {
            if (showChrome) {
                ExtendedFloatingActionButton(
                    onClick = { navController.navigate(Destination.Capture.route) },
                    icon = {
                        Icon(
                            imageVector = Icons.Filled.PhotoCamera,
                            contentDescription = null,
                        )
                    },
                    text = { Text(stringResource(R.string.home_capture_meal)) },
                )
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            NutriLensNavHost(navController = navController, startDestination = startDestination)
        }
    }
}

@Composable
private fun NutriLensNavHost(
    navController: NavHostController,
    startDestination: String,
) {
    NavHost(navController = navController, startDestination = startDestination) {

        composable(Destination.Onboarding.route) {
            OnboardingRoute(
                onGetStarted = { navController.navigate(Destination.Auth.route) },
                onSignIn = { navController.navigate(Destination.Auth.route) },
            )
        }

        composable(Destination.Auth.route) {
            AuthRoute(
                onAuthenticated = {
                    navController.navigate(Destination.Home.route) {
                        // Clear the auth flow so back does not return to a
                        // sign-in form the user has already passed.
                        popUpTo(0) { inclusive = true }
                    }
                },
            )
        }

        composable(Destination.Home.route) {
            HomeRoute(
                onCaptureMeal = { navController.navigate(Destination.Capture.route) },
                onOpenMeal = { mealId ->
                    navController.navigate(Destination.MealDetail.createRoute(mealId))
                },
                onViewTimeline = { navController.navigateToTopLevel(Destination.Timeline.route) },
            )
        }

        composable(Destination.Capture.route) {
            CaptureRoute(
                onImageCaptured = { imagePath ->
                    navController.navigate(Destination.Analysis.createRoute(imagePath)) {
                        // Replace the camera in the stack: backing out of the
                        // results should return to the timeline, not reopen the
                        // viewfinder on the meal just photographed.
                        popUpTo(Destination.Capture.route) { inclusive = true }
                    }
                },
                onCancel = navController::popBackStack,
            )
        }

        composable(
            route = Destination.Analysis.route,
            arguments = listOf(
                navArgument(Destination.ARG_IMAGE_PATH) { type = NavType.StringType },
            ),
        ) {
            AnalysisRoute(
                onMealSaved = {
                    navController.navigate(Destination.Home.route) {
                        popUpTo(Destination.Home.route) { inclusive = true }
                    }
                },
                onCancel = navController::popBackStack,
            )
        }

        composable(Destination.Timeline.route) {
            TimelineRoute(
                onOpenMeal = { mealId ->
                    navController.navigate(Destination.MealDetail.createRoute(mealId))
                },
            )
        }

        composable(
            route = Destination.MealDetail.route,
            arguments = listOf(
                navArgument(Destination.ARG_MEAL_ID) { type = NavType.StringType },
            ),
        ) {
            MealDetailRoute(onBack = navController::popBackStack)
        }

        composable(Destination.Analytics.route) {
            AnalyticsRoute()
        }

        composable(Destination.Settings.route) {
            SettingsRoute(
                onSignedOut = {
                    navController.navigate(Destination.Onboarding.route) {
                        popUpTo(0) { inclusive = true }
                    }
                },
            )
        }
    }
}

@Composable
private fun NutriLensBottomBar(
    currentRoute: String?,
    onNavigate: (String) -> Unit,
) {
    NavigationBar {
        Destination.topLevel.forEach { destination ->
            val selected = currentRoute == destination.route
            NavigationBarItem(
                selected = selected,
                onClick = { onNavigate(destination.route) },
                icon = {
                    Icon(
                        imageVector = destination.icon(),
                        contentDescription = null,
                    )
                },
                label = { Text(stringResource(destination.labelRes())) },
                // The label is the accessible name; the icon is decorative, so
                // a screen reader announces "Home" rather than "Home, Home".
                alwaysShowLabel = true,
            )
        }
    }
}

private fun Destination.icon(): ImageVector = when (this) {
    Destination.Home -> Icons.Filled.Restaurant
    Destination.Timeline -> Icons.Filled.Schedule
    Destination.Analytics -> Icons.Filled.Insights
    Destination.Settings -> Icons.Filled.Person
    else -> Icons.Filled.Restaurant
}

private fun Destination.labelRes(): Int = when (this) {
    Destination.Home -> R.string.nav_home
    Destination.Timeline -> R.string.nav_timeline
    Destination.Analytics -> R.string.nav_analytics
    Destination.Settings -> R.string.nav_profile
    else -> R.string.nav_home
}

/**
 * Switch top-level tabs without growing the back stack.
 *
 * `launchSingleTop` plus `popUpTo(start)` means tapping between tabs cannot
 * stack a dozen copies of Home behind the user, and `restoreState` keeps each
 * tab's scroll position where they left it.
 */
private fun NavHostController.navigateToTopLevel(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
