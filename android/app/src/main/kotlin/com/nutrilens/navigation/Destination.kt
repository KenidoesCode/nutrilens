package com.nutrilens.navigation

/**
 * Every place the user can be.
 *
 * A sealed hierarchy rather than loose strings so a typo in a route is a
 * compile error, and so the set of destinations is enumerable for the bottom
 * bar and for tests.
 */
sealed class Destination(val route: String) {

    data object Onboarding : Destination("onboarding")

    data object Auth : Destination("auth")

    data object Home : Destination("home")

    data object Capture : Destination("capture")

    data object Timeline : Destination("timeline")

    data object Analytics : Destination("analytics")

    data object Settings : Destination("settings")

    /** Analysis of a freshly captured photograph, identified by its local path. */
    data object Analysis : Destination("analysis/{$ARG_IMAGE_PATH}") {
        fun createRoute(imagePath: String): String =
            "analysis/${java.net.URLEncoder.encode(imagePath, Charsets.UTF_8.name())}"
    }

    /** A stored meal's detail. */
    data object MealDetail : Destination("meal/{$ARG_MEAL_ID}") {
        fun createRoute(mealId: String): String = "meal/$mealId"
    }

    companion object {
        const val ARG_IMAGE_PATH = "imagePath"
        const val ARG_MEAL_ID = "mealId"

        /** Destinations reachable from the bottom bar, in display order. */
        val topLevel: List<Destination> = listOf(Home, Timeline, Analytics, Settings)
    }
}
