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
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.brainfit.feature.games.GamesScreen
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.navigation.NavBackStackEntry
import androidx.compose.ui.unit.dp
import app.brainfit.core.designsystem.BrainTheme
import app.brainfit.core.designsystem.HairlineDivider
import app.brainfit.core.designsystem.Motion
import app.brainfit.domain.model.GameId
import app.brainfit.domain.model.SessionMode
import app.brainfit.feature.workout.WorkoutScreen
import app.brainfit.feature.workout.WorkoutViewModel
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
    const val PLAY = "play/{${PlayViewModel.ARG_GAME}}" +
        "?${PlayViewModel.ARG_WORKOUT}={${PlayViewModel.ARG_WORKOUT}}&${PlayViewModel.ARG_MODE}={${PlayViewModel.ARG_MODE}}"
    const val WORKOUT = "workout/{${WorkoutViewModel.ARG_WORKOUT}}"

    /** Свободная игра или игра тренировки [workoutId]. */
    fun play(game: GameId, workoutId: Long? = null, mode: SessionMode = SessionMode.FREE) =
        "play/${game.name}?${PlayViewModel.ARG_WORKOUT}=${workoutId ?: -1}&${PlayViewModel.ARG_MODE}=${mode.name}"

    fun workout(id: Long) = "workout/$id"

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
    // Экраны со своей шапкой «назад»; у экрана игры шапки приложения нет — её рисует хост игры.
    val innerTitle = when (route) {
        Routes.SETTINGS -> "Настройки"
        Routes.WORKOUT -> "Тренировка"
        else -> null
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            if (currentTab != null || innerTitle != null) TopAppBar(
                title = {
                    Text(
                        currentTab?.title ?: innerTitle.orEmpty(),
                        style = if (currentTab != null) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge,
                    )
                },
                navigationIcon = {
                    if (innerTitle != null) {
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
                HairlineDivider()
                NavigationBar(containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
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
                                selectedIconColor = BrainTheme.colors.textPrimary,
                                selectedTextColor = BrainTheme.colors.textPrimary,
                                unselectedIconColor = BrainTheme.colors.textSecondary,
                                unselectedTextColor = BrainTheme.colors.textSecondary,
                                indicatorColor = BrainTheme.colors.surfaceSecondary,
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
            // Вкладки сменяются «через затухание» на месте; вложенный экран въезжает справа и уходит вправо.
            enterTransition = {
                if (isTabSwitch()) tabEnter() else slideInHorizontally(tween(Motion.BASE, easing = Motion.Enter)) { it / 8 } + tabEnter()
            },
            exitTransition = { fadeOut(tween(Motion.EXIT, easing = Motion.Exit)) },
            popEnterTransition = { tabEnter() },
            popExitTransition = {
                slideOutHorizontally(tween(Motion.EXIT, easing = Motion.Exit)) { it / 8 } + fadeOut(tween(Motion.EXIT, easing = Motion.Exit))
            },
        ) {
            composable(Routes.HOME) {
                HomeScreen(onOpenWorkout = { nav.navigate(Routes.workout(it)) { launchSingleTop = true } })
            }
            composable(Routes.GAMES) { GamesScreen(onPlay = { nav.navigate(Routes.play(it)) { launchSingleTop = true } }) }
            composable(Routes.PROGRESS) { ProgressScreen() }
            composable(Routes.SETTINGS) { SettingsScreen() }
            composable(
                Routes.WORKOUT,
                arguments = listOf(navArgument(WorkoutViewModel.ARG_WORKOUT) { type = NavType.LongType }),
            ) {
                WorkoutScreen(
                    onPlay = { game, workoutId, mode -> nav.navigate(Routes.play(game, workoutId, mode)) { launchSingleTop = true } },
                    onClose = { nav.popBackStack() },
                )
            }
            composable(
                Routes.PLAY,
                arguments = listOf(
                    navArgument(PlayViewModel.ARG_WORKOUT) {
                        type = NavType.LongType
                        defaultValue = -1L
                    },
                    navArgument(PlayViewModel.ARG_MODE) {
                        type = NavType.StringType
                        defaultValue = SessionMode.FREE.name
                    },
                ),
            ) { PlayRoute(onClose = { nav.popBackStack() }) }
        }
    }
}

private val tabRoutes = Routes.tabs.map { it.route }.toSet()

/** Переход между двумя вкладками нижней панели. */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.isTabSwitch(): Boolean =
    initialState.destination.route in tabRoutes && targetState.destination.route in tabRoutes

private fun tabEnter(): EnterTransition =
    fadeIn(tween(Motion.BASE, easing = Motion.Enter)) + scaleIn(tween(Motion.BASE, easing = Motion.Enter), initialScale = 0.98f)
