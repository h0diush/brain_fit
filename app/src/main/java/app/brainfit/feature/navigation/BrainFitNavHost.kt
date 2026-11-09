package app.brainfit.feature.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.brainfit.core.designsystem.Dimens
import app.brainfit.feature.games.GamesScreen
import app.brainfit.domain.model.GameId
import app.brainfit.feature.home.HomeScreen
import app.brainfit.feature.play.PlayRoute
import app.brainfit.feature.play.PlayViewModel
import app.brainfit.feature.progress.ProgressScreen
import app.brainfit.feature.settings.SettingsScreen

object Routes {
    const val HOME = "home"
    const val GAMES = "games"
    const val PROGRESS = "progress"
    const val SETTINGS = "settings"
    const val PLAY = "play/{${PlayViewModel.ARG_GAME}}"

    fun play(game: GameId) = "play/${game.name}"

    /** Три вкладки нижней панели (ТЗ, раздел 8). Настройки — иконкой в шапке. */
    val tabs = listOf(
        Tab(HOME, "Главная", Icons.Rounded.Home),
        Tab(GAMES, "Игры", Icons.Rounded.GridView),
        Tab(PROGRESS, "Прогресс", Icons.Rounded.BarChart),
    )
}

data class Tab(val route: String, val title: String, val icon: ImageVector)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrainFitNavHost() {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val currentTab = Routes.tabs.firstOrNull { it.route == route }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            // На экране игры своей шапки у приложения нет: её рисует хост игры.
            if (currentTab != null || route == Routes.SETTINGS) TopAppBar(
                title = { Text(currentTab?.title ?: if (route == Routes.SETTINGS) "Настройки" else "") },
                navigationIcon = {
                    if (route == Routes.SETTINGS) {
                        IconButton(onClick = { nav.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Назад")
                        }
                    }
                },
                actions = {
                    if (currentTab != null) {
                        IconButton(onClick = { nav.navigate(Routes.SETTINGS) { launchSingleTop = true } }) {
                            Icon(Icons.Rounded.Settings, contentDescription = "Настройки")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            if (currentTab != null) {
                NavigationBar {
                    Routes.tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = tab == currentTab,
                            onClick = {
                                nav.navigate(tab.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(tab.title) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = Routes.HOME,
            modifier = Modifier.padding(padding),
            enterTransition = { fadeIn(tween(Dimens.ANIM_MS)) },
            exitTransition = { fadeOut(tween(Dimens.ANIM_MS)) },
            popEnterTransition = { fadeIn(tween(Dimens.ANIM_MS)) },
            popExitTransition = { fadeOut(tween(Dimens.ANIM_MS)) },
        ) {
            composable(Routes.HOME) { HomeScreen() }
            composable(Routes.GAMES) { GamesScreen(onPlay = { nav.navigate(Routes.play(it)) { launchSingleTop = true } }) }
            composable(Routes.PROGRESS) { ProgressScreen() }
            composable(Routes.SETTINGS) { SettingsScreen() }
            composable(Routes.PLAY) { PlayRoute(onClose = { nav.popBackStack() }) }
        }
    }
}
