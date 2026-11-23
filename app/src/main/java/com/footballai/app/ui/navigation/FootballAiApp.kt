package com.footballai.app.ui.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import com.footballai.app.ui.theme.GlassLightInkFaint
import com.footballai.app.ui.theme.GlassLightInkSecondary
import com.footballai.app.ui.theme.GlassViolet
import com.footballai.app.ui.theme.Spacing
import com.footballai.app.ui.theme.FootballAiThemeExtra
import com.footballai.app.ui.glass.LightGlassFill
import com.footballai.app.ui.glass.LocalScrollLight
import com.footballai.app.ui.glass.ScrollLight
import com.footballai.app.ui.glass.scrollSpecular
import com.footballai.app.ui.glass.trackScrollLight
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.material.icons.filled.StackedLineChart
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavBackStackEntry
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
import com.footballai.app.ui.LocalMatchesSearchSignal
import com.footballai.app.ui.MatchesSearchSignal
import com.footballai.app.ui.components.LoadingState
import com.footballai.app.ui.screens.CompetitionOrderScreen
import com.footballai.app.ui.screens.CompetitionScreen
import com.footballai.app.ui.screens.CompetitionTableScreen
import com.footballai.app.ui.screens.DataSourcesScreen
import com.footballai.app.ui.screens.MatchDetailScreen
import com.footballai.app.ui.screens.MatchesScreen
import com.footballai.app.ui.screens.OnboardingScreen
import com.footballai.app.ui.screens.PerformanceScreen
import com.footballai.app.ui.screens.PredictionsScreen
import com.footballai.app.ui.screens.SettingsScreen
import com.footballai.app.ui.screens.TeamScreen

object Routes {
    const val MATCHES = "matches"
    const val PREDICTIONS = "predictions"
    const val PERFORMANCE = "performance"
    const val DATA_SOURCES = "data-sources"
    const val COMPETITION_ORDER = "competition-order"
    const val SETTINGS = "settings"
    const val MATCH_DETAIL = "match/{matchId}"
    const val TEAM = "team/{teamId}"
    const val COMPETITION = "competition/{competitionId}"
    const val COMPETITION_TABLE = "competition-table/{competitionId}"

    fun matchDetail(matchId: String) = "match/$matchId"
    fun team(teamId: String) = "team/$teamId"
    fun competition(competitionId: String) = "competition/$competitionId"
    fun competitionTable(competitionId: String) = "competition-table/$competitionId"
}

private data class BottomDestination(
    val route: String,
    val labelRes: Int,
    val icon: ImageVector,
)

private val bottomDestinations = listOf(
    BottomDestination(Routes.MATCHES, R.string.nav_matches, Icons.Default.SportsSoccer),
    BottomDestination(Routes.PREDICTIONS, R.string.nav_predictions, Icons.Default.QueryStats),
    BottomDestination(Routes.PERFORMANCE, R.string.nav_performance, Icons.Default.StackedLineChart),
    BottomDestination(Routes.SETTINGS, R.string.nav_settings, Icons.Default.Settings),
)

/**
 * Screens that are pushed on top of a tab. They slide in from the side; tabs cross-fade
 * into each other. Both are short and both are animated: switching used to replace the
 * whole screen in one frame, which read as a flicker rather than a move.
 */
private val pushedRoutes = setOf(
    Routes.MATCH_DETAIL,
    Routes.TEAM,
    Routes.COMPETITION,
    Routes.DATA_SOURCES,
    Routes.SETTINGS,
    Routes.COMPETITION_ORDER,
)

private fun isPushed(entry: NavBackStackEntry): Boolean = entry.destination.route in pushedRoutes

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
    // The glass theme's one fixed light lives here, above every destination, so the
    // home feed, the match page and the navigation pill all reflect the same lamp.
    // So does the shell's search signal: the pill's search circle is counted here
    // and the matches feed, deep in the NavHost, watches the count.
    val scrollLight = remember { ScrollLight() }
    val searchSignal = remember { MatchesSearchSignal() }
    CompositionLocalProvider(
        LocalScrollLight provides scrollLight,
        LocalMatchesSearchSignal provides searchSignal,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .trackScrollLight(scrollLight),
        ) {
            MainScaffold()
        }
    }
}

@Composable
private fun MainScaffold() {
    val scrollLight = LocalScrollLight.current
    val searchSignal = LocalMatchesSearchSignal.current
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = bottomDestinations.any { it.route == currentRoute }
    // Destinations that live in the glass world (pastel ground, light glass). The
    // pill over them must be light glass too, with the search circle beside it;
    // the light pill below is for the rest of the app, unchanged.
    val glassBar = currentRoute == Routes.MATCHES || currentRoute == Routes.MATCH_DETAIL
    val themeColors = FootballAiThemeExtra.colors
    // Forward is to the left when the interface is right-to-left, so the slide follows
    // the direction the user reads in.
    val forward = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1 else 1
    val openTab: (String) -> Unit = { route ->
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    // The destinations row, shared by both pill finishes: the same four tabs, the
    // same selected-pill language, only the colours change with the world below.
    val pillContent: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.sm, vertical = Spacing.sm),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            bottomDestinations.forEach { destination ->
                val selected = currentRoute == destination.route
                Surface(
                    onClick = {
                        if (currentRoute != destination.route) openTab(destination.route)
                    },
                    shape = RoundedCornerShape(22.dp),
                    color = if (selected) {
                        if (glassBar) {
                            GlassViolet.copy(alpha = 0.13f)
                        } else {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                        }
                    } else {
                        Color.Transparent
                    },
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            imageVector = destination.icon,
                            contentDescription = null,
                            tint = if (selected) {
                                if (glassBar) GlassViolet else MaterialTheme.colorScheme.primary
                            } else {
                                if (glassBar) GlassLightInkFaint else themeColors.iconMuted
                            },
                            modifier = Modifier.size(23.dp),
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = stringResource(destination.labelRes),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (selected) {
                                if (glassBar) GlassViolet else MaterialTheme.colorScheme.primary
                            } else {
                                if (glassBar) GlassLightInkFaint else themeColors.iconMuted
                            },
                        )
                    }
                }
            }
        }
    }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                // A floating pill instead of a bar glued to the edge, edged and lit by
                // the same fixed light as the glass cards. Two finishes. Over the
                // glass destinations the pill is light glass — the shared translucent
                // fill under the shared specular, the selected tab a whisper of
                // violet — and it no longer spans the screen: a separate glass
                // circle beside it carries search, the way the reference wallet app
                // floats its own search beside its pill. Over the light destinations
                // the light pill stays exactly as it was.
                if (glassBar) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.page, vertical = Spacing.sm),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .shadow(
                                    elevation = 4.dp,
                                    shape = RoundedCornerShape(30.dp),
                                    clip = false,
                                    ambientColor = GlassViolet.copy(alpha = 0.18f),
                                    spotColor = GlassViolet.copy(alpha = 0.18f),
                                )
                                .clip(RoundedCornerShape(30.dp))
                                .background(LightGlassFill)
                                .scrollSpecular(RoundedCornerShape(30.dp), scrollLight),
                        ) {
                            pillContent()
                        }
                        // Search, as its own floating circle. It counts a request on
                        // the shared signal; the matches feed — the only glass
                        // destination with a bar — opens its search dialog.
                        Box(
                            modifier = Modifier
                                .size(52.dp)
                                .shadow(
                                    elevation = 4.dp,
                                    shape = CircleShape,
                                    clip = false,
                                    ambientColor = GlassViolet.copy(alpha = 0.18f),
                                    spotColor = GlassViolet.copy(alpha = 0.18f),
                                )
                                .clip(CircleShape)
                                .background(LightGlassFill)
                                .clickable { searchSignal.request() }
                                .scrollSpecular(CircleShape, scrollLight),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Search,
                                contentDescription = stringResource(R.string.search_matches),
                                tint = GlassLightInkSecondary,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.xl, vertical = Spacing.sm)
                            .shadow(elevation = 14.dp, shape = RoundedCornerShape(30.dp), clip = false)
                            .clip(RoundedCornerShape(30.dp))
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
                                        MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
                                    ),
                                ),
                            )
                            .scrollSpecular(RoundedCornerShape(30.dp), scrollLight),
                    ) {
                        pillContent()
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.MATCHES,
            modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding()),
            enterTransition = {
                if (isPushed(targetState)) {
                    slideInHorizontally(animationSpec = tween(300)) { width -> forward * width } +
                        fadeIn(animationSpec = tween(200))
                } else {
                    fadeIn(animationSpec = tween(200)) +
                        scaleIn(initialScale = 0.97f, animationSpec = tween(260))
                }
            },
            exitTransition = {
                if (isPushed(targetState)) {
                    fadeOut(animationSpec = tween(150)) +
                        slideOutHorizontally(animationSpec = tween(300)) { width -> -forward * width / 4 }
                } else {
                    fadeOut(animationSpec = tween(150))
                }
            },
            popEnterTransition = {
                fadeIn(animationSpec = tween(200)) + scaleIn(initialScale = 0.99f, animationSpec = tween(240))
            },
            popExitTransition = {
                slideOutHorizontally(animationSpec = tween(280)) { width -> forward * width / 3 } +
                    fadeOut(animationSpec = tween(200))
            },
        ) {
            composable(Routes.MATCHES) {
                MatchesScreen(
                    onMatchClick = { navController.navigate(Routes.matchDetail(it)) },
                    onCompetitionClick = { navController.navigate(Routes.competitionTable(it)) },
                )
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
                SettingsScreen(
                    onOpenDataSources = { navController.navigate(Routes.DATA_SOURCES) },
                    onOpenCompetitionOrder = { navController.navigate(Routes.COMPETITION_ORDER) },
                )
            }
            composable(Routes.COMPETITION_ORDER) {
                CompetitionOrderScreen(onBack = { navController.popBackStack() })
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
                    onCompetitionClick = { navController.navigate(Routes.competitionTable(it)) },
                )
            }
            composable(
                route = Routes.TEAM,
                arguments = listOf(navArgument("teamId") { type = NavType.StringType }),
            ) { entry ->
                TeamScreen(
                    teamId = entry.arguments?.getString("teamId").orEmpty(),
                    onBack = { navController.popBackStack() },
                    onCompetitionClick = { navController.navigate(Routes.competitionTable(it)) },
                    onTeamClick = { navController.navigate(Routes.team(it)) },
                )
            }
            composable(
                route = Routes.COMPETITION_TABLE,
                arguments = listOf(navArgument("competitionId") { type = NavType.StringType }),
            ) { entry ->
                CompetitionTableScreen(
                    competitionId = entry.arguments?.getString("competitionId").orEmpty(),
                    onBack = { navController.popBackStack() },
                    onTeamClick = { navController.navigate(Routes.team(it)) },
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
                    onTableClick = {
                        navController.navigate(
                            Routes.competitionTable(entry.arguments?.getString("competitionId").orEmpty()),
                        )
                    },
                    onTeamClick = { navController.navigate(Routes.team(it)) },
                )
            }
        }
    }
}
