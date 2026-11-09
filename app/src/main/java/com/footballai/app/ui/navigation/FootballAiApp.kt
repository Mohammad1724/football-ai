package com.footballai.app.ui.navigation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.material.icons.filled.StackedLineChart
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
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.footballai.app.R
import com.footballai.app.data.settings.AppSettings
import com.footballai.app.ui.LocalAppContainer
import com.footballai.app.ui.components.LoadingState
import com.footballai.app.ui.screens.CompetitionScreen
import com.footballai.app.ui.screens.DashboardScreen
import com.footballai.app.ui.screens.DataSourcesScreen
import com.footballai.app.ui.screens.MatchDetailScreen
import com.footballai.app.ui.screens.MatchesScreen
import com.footballai.app.ui.screens.OnboardingScreen
import com.footballai.app.ui.screens.PerformanceScreen
import com.footballai.app.ui.screens.PredictionsScreen
import com.footballai.app.ui.screens.SettingsScreen
import com.footballai.app.ui.screens.TeamScreen

object Routes {
    const val HOME = "home"
    const val MATCHES = "matches"
    const val PREDICTIONS = "predictions"
    const val PERFORMANCE = "performance"
    const val DATA_SOURCES = "data-sources"
    const val SETTINGS = "settings"
    const val MATCH_DETAIL = "match/{matchId}"
    const val TEAM = "team/{teamId}"
    const val COMPETITION = "competition/{competitionId}"

    fun matchDetail(matchId: String) = "match/$matchId"
    fun team(teamId: String) = "team/$teamId"
    fun competition(competitionId: String) = "competition/$competitionId"
}

private data class BottomDestination(
    val route: String,
    val labelRes: Int,
    val icon: ImageVector,
)

private val bottomDestinations = listOf(
    BottomDestination(Routes.HOME, R.string.nav_home, Icons.Default.Home),
    BottomDestination(Routes.MATCHES, R.string.nav_matches, Icons.Default.SportsSoccer),
    BottomDestination(Routes.PREDICTIONS, R.string.nav_predictions, Icons.Default.QueryStats),
    BottomDestination(Routes.PERFORMANCE, R.string.nav_performance, Icons.Default.StackedLineChart),
)

@Composable
fun FootballAiApp() {
    val container = LocalAppContainer.current
    val settingsState = container.settingsRepository.settings
        .collectAsStateWithLifecycle<AppSettings?>(initialValue = null)
    val settings = settingsState.value
    when {
        // The stored preferences decide whether the first-run flow is still pending;
        // showing the app shell before that value is known would flash an empty state.
        settings == null -> LoadingState(modifier = Modifier.fillMaxSize())
        !settings.onboarded -> OnboardingScreen()
        else -> MainNavigation()
    }
}

/** The regular app shell, shown once onboarding has been completed or skipped. */
@Composable
private fun MainNavigation() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = bottomDestinations.any { it.route == currentRoute }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    bottomDestinations.forEach { destination ->
                        NavigationBarItem(
                            selected = currentRoute == destination.route,
                            onClick = {
                                if (currentRoute != destination.route) {
                                    navController.navigate(destination.route) {
                                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            },
                            icon = { Icon(destination.icon, contentDescription = null) },
                            label = { Text(stringResource(destination.labelRes)) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding()),
        ) {
            composable(Routes.HOME) {
                DashboardScreen(
                    onMatchClick = { navController.navigate(Routes.matchDetail(it)) },
                    onOpenDataSources = { navController.navigate(Routes.DATA_SOURCES) },
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                    onOpenMatches = { navController.navigate(Routes.MATCHES) },
                )
            }
            composable(Routes.MATCHES) {
                MatchesScreen(onMatchClick = { navController.navigate(Routes.matchDetail(it)) })
            }
            composable(Routes.PREDICTIONS) {
                PredictionsScreen(onMatchClick = { navController.navigate(Routes.matchDetail(it)) })
            }
            composable(Routes.PERFORMANCE) {
                PerformanceScreen()
            }
            composable(Routes.DATA_SOURCES) {
                DataSourcesScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(onBack = { navController.popBackStack() })
            }
            composable(
                route = Routes.MATCH_DETAIL,
                arguments = listOf(navArgument("matchId") { type = NavType.StringType }),
            ) { entry ->
                val matchId = entry.arguments?.getString("matchId").orEmpty()
                MatchDetailScreen(
                    matchId = matchId,
                    onBack = { navController.popBackStack() },
                    onTeamClick = { navController.navigate(Routes.team(it)) },
                )
            }
            composable(
                route = Routes.TEAM,
                arguments = listOf(navArgument("teamId") { type = NavType.StringType }),
            ) { entry ->
                TeamScreen(
                    teamId = entry.arguments?.getString("teamId").orEmpty(),
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                route = Routes.COMPETITION,
                arguments = listOf(navArgument("competitionId") { type = NavType.StringType }),
            ) { entry ->
                CompetitionScreen(
                    competitionId = entry.arguments?.getString("competitionId").orEmpty(),
                    onBack = { navController.popBackStack() },
                    onMatchClick = { navController.navigate(Routes.matchDetail(it)) },
                )
            }
        }
    }
}
