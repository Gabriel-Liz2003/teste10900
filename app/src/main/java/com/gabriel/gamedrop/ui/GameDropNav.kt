package com.gabriel.gamedrop.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.gabriel.gamedrop.AppContainer
import com.gabriel.gamedrop.BuildConfig
import com.gabriel.gamedrop.R
import com.gabriel.gamedrop.data.local.ThemeMode
import com.gabriel.gamedrop.ui.calendar.CalendarRoute
import com.gabriel.gamedrop.ui.detail.DetailRoute
import com.gabriel.gamedrop.ui.favorites.FavoritesRoute
import com.gabriel.gamedrop.ui.events.EventsRoute
import com.gabriel.gamedrop.ui.events.EventDetailRoute
import com.gabriel.gamedrop.ui.home.HomeRoute
import com.gabriel.gamedrop.ui.search.SearchRoute
import kotlinx.coroutines.launch

private data class BottomDestination(val route: String, val label: Int, val icon: ImageVector)
private val bottomDestinations = listOf(
    BottomDestination("home", R.string.nav_home, Icons.Default.Home),
    BottomDestination("calendar", R.string.nav_calendar, Icons.Default.CalendarMonth),
    BottomDestination("events", R.string.nav_events, Icons.Default.Event),
    BottomDestination("search", R.string.nav_search, Icons.Default.Search),
    BottomDestination("favorites", R.string.nav_favorites, Icons.Default.Favorite)
)

@Composable
fun GameDropNav(container: AppContainer, themeMode: ThemeMode, onCycleTheme: () -> Unit) {
    val savedApiKey by container.preferences.apiKey.collectAsStateWithLifecycle(initialValue = "")
    val scope = rememberCoroutineScope()
    if (savedApiKey.isBlank() && BuildConfig.RAWG_API_KEY.isBlank()) {
        ApiKeySetup(onSave = { value -> scope.launch { container.preferences.setApiKey(value) } })
        return
    }

    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val showBottom = currentRoute?.startsWith("detail/") != true && currentRoute?.startsWith("event/") != true

    Scaffold(
        bottomBar = {
            if (showBottom) {
                NavigationBar {
                    bottomDestinations.forEach { destination ->
                        NavigationBarItem(
                            selected = currentRoute == destination.route,
                            onClick = {
                                navController.navigate(destination.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(destination.icon, contentDescription = null) },
                            label = { Text(stringResource(destination.label)) }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(navController = navController, startDestination = "home") {
            composable("home") {
                HomeRoute(
                    repository = container.repository,
                    themeMode = themeMode,
                    onCycleTheme = onCycleTheme,
                    contentPadding = innerPadding,
                    onGameClick = { navController.navigate("detail/$it") },
                    onSeeAll = { navController.navigate("calendar") }
                )
            }
            composable("calendar") {
                CalendarRoute(container.repository, innerPadding, onGameClick = { navController.navigate("detail/$it") })
            }
            composable("events") {
                EventsRoute(container.eventRepository, innerPadding, onEventClick = { navController.navigate("event/$it") })
            }
            composable("search") {
                SearchRoute(container.repository, innerPadding, onGameClick = { navController.navigate("detail/$it") })
            }
            composable("favorites") {
                FavoritesRoute(container.repository, innerPadding, onGameClick = { navController.navigate("detail/$it") })
            }
            composable("event/{eventId}") { entry ->
                val id = entry.arguments?.getString("eventId")?.toLongOrNull() ?: return@composable
                EventDetailRoute(repository = container.eventRepository, eventId = id, onBack = navController::popBackStack)
            }
            composable("detail/{gameId}") { entry ->
                val id = entry.arguments?.getString("gameId")?.toIntOrNull() ?: return@composable
                DetailRoute(repository = container.repository, gameId = id, onBack = navController::popBackStack)
            }
        }
    }
}
