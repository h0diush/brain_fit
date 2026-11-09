package app.brainfit.feature.play

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.brainfit.core.designsystem.BrainTheme
import app.brainfit.core.designsystem.Dimens
import app.brainfit.core.designsystem.MutedText
import app.brainfit.core.designsystem.PrimaryButton
import app.brainfit.core.designsystem.SecondaryButton
import kotlin.math.roundToInt

@Composable
fun PlayRoute(onClose: () -> Unit, vm: PlayViewModel = hiltViewModel()) {
    val binding = vm.binding
    if (binding == null) {
        // Сюда можно попасть только по неверной ссылке: карточки неготовых игр не нажимаются.
        Column(Modifier.fillMaxSize().padding(Dimens.screenPadding)) {
            Text("Эта игра ещё не готова", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.weight(1f))
            PrimaryButton("Назад", onClick = onClose)
        }
    } else {
        binding.Screen(onClose)
    }
}

/**
 * Экран-хост: общий для всех игр. Сам рисует шапку, паузу, переходы между раундами, результат
 * и ошибки сохранения; игра рисует только свой раунд через [content].
 */
@Composable
fun <S : Any, I : Any> PlayScreen(
    controller: PlayController<*, S, I>,
    title: String,
    content: @Composable (state: S, onInput: (I) -> Unit) -> Unit,
    onClose: () -> Unit,
) {
    val ui by controller.state.collectAsStateWithLifecycle()
    val running = ui.phase == PlayPhase.RUNNING && ui.error == null

    // Время игры идёт по кадрам экрана.
    if (running) {
        LaunchedEffect(controller) {
            while (true) withFrameNanos { controller.frame(it) }
        }
    }

    // Автопауза: приложение свернули, экран погас, поверх открылся звонок или шторка.
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { controller.pause() }
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(windowFocused) { if (!windowFocused) controller.pause() }

    BackHandler {
        when {
            ui.error != null -> controller.forceClose()
            ui.phase == PlayPhase.RUNNING -> controller.pause()
            else -> controller.exit()
        }
    }

    // Посреди раунда экран не гаснет сам.
    val view = LocalView.current
    DisposableEffect(running) {
        view.keepScreenOn = running
        onDispose { view.keepScreenOn = false }
    }

    LaunchedEffect(ui.closed) { if (ui.closed) onClose() }

    Column(Modifier.fillMaxSize().padding(horizontal = Dimens.screenPadding)) {
        Header(title, ui, onPause = controller::pause)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val error = ui.error
            when {
                error != null -> Panel(
                    title = "Ошибка сохранения",
                    text = error,
                    textColor = BrainTheme.colors.error,
                ) {
                    PrimaryButton("Повторить", onClick = controller::retry)
                    SecondaryButton("Выйти без сохранения", onClick = controller::forceClose)
                }

                ui.phase == PlayPhase.LOADING || ui.phase == PlayPhase.SAVING -> Unit

                ui.phase == PlayPhase.READY -> Panel(
                    title = "Раунд ${ui.round + 1} из ${ui.totalRounds}",
                    text = if (ui.restored) "Продолжаем с того раунда, на котором остановились." else null,
                ) {
                    PrimaryButton(if (ui.round == 0 && !ui.restored) "Начать" else "Дальше", onClick = controller::startRound)
                    SecondaryButton("Выйти", onClick = controller::exit)
                }

                ui.phase == PlayPhase.RUNNING -> ui.gameState?.let { content(it, controller::input) }

                // На паузе игра скрыта: иначе в играх на память можно подсматривать.
                ui.phase == PlayPhase.PAUSED -> Panel(
                    title = "Пауза",
                    text = if (ui.restartOnResume) "После паузы раунд начнётся заново." else null,
                ) {
                    PrimaryButton(if (ui.restartOnResume) "Начать раунд заново" else "Продолжить", onClick = controller::resume)
                    if (!ui.restartOnResume) SecondaryButton("Заново", onClick = controller::restartRound)
                    SecondaryButton("Выйти", onClick = controller::exit)
                }

                ui.phase == PlayPhase.FINISHED -> Panel(
                    title = ui.result?.let { "Результат: ${(it.score * 100).roundToInt()} %" } ?: "Сессия завершена",
                    text = if (ui.shakySession) {
                        "Сильно трясло. Сессия сохранена в истории, но на уровень и рекорды скорости не повлияет."
                    } else {
                        null
                    },
                ) {
                    PrimaryButton("Готово", onClick = controller::exit)
                }
            }
        }
    }
}

@Composable
private fun Header(title: String, ui: PlayUiState<*>, onPause: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            if (ui.totalRounds > 0 && ui.phase != PlayPhase.FINISHED) {
                MutedText("Раунд ${ui.round + 1} из ${ui.totalRounds} · уровень ${ui.level}")
            }
        }
        if (ui.phase == PlayPhase.RUNNING && ui.error == null) {
            IconButton(onClick = onPause) {
                Icon(Icons.Rounded.Pause, contentDescription = "Пауза")
            }
        }
    }
}

/** Текст сверху, кнопки внизу — под большой палец. */
@Composable
private fun Panel(
    title: String,
    text: String?,
    textColor: androidx.compose.ui.graphics.Color = BrainTheme.colors.textSecondary,
    buttons: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(vertical = Dimens.screenPadding)) {
        Spacer(Modifier.weight(1f))
        Text(title, style = MaterialTheme.typography.headlineMedium)
        if (text != null) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                color = textColor,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        Column(verticalArrangement = Arrangement.spacedBy(Dimens.gap)) { buttons() }
    }
}
