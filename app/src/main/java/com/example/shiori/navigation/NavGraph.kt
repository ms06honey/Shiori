package com.example.shiori.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.shiori.feature.bookmark.presentation.BookmarkDetailScreen
import com.example.shiori.feature.bookmark.presentation.BookmarkListScreen
import com.example.shiori.feature.settings.presentation.SettingsScreen

sealed class Screen(val route: String) {
    data object BookmarkList : Screen("bookmark_list")
    data object BookmarkDetail : Screen("bookmark_detail/{id}") {
        fun createRoute(id: Long) = "bookmark_detail/$id"
    }
    data object Settings : Screen("settings")
}

@Composable
fun ShioriNavGraph(
    navController: NavHostController = rememberNavController()
) {
    NavHost(
        navController = navController,
        startDestination = Screen.BookmarkList.route
    ) {
        composable(Screen.BookmarkList.route) {
            BookmarkListScreen(
                onNavigateToDetail = { id ->
                    navController.navigate(Screen.BookmarkDetail.createRoute(id))
                },
                onNavigateToSettings = { navController.navigate(Screen.Settings.route) }
            )
        }

        composable(
            route = Screen.BookmarkDetail.route,
            arguments = listOf(navArgument("id") { type = NavType.LongType })
        ) { backStack ->
            val id = backStack.arguments?.getLong("id") ?: return@composable
            BookmarkDetailScreen(
                bookmarkId = id,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Settings.route) {
            SettingsScreen(onNavigateBack = { navController.popBackStack() })
        }
    }
}
