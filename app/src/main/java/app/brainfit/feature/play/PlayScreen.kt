package app.brainfit.feature.play

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.brainfit.core.designsystem.HairlineDivider
import app.brainfit.core.designsystem.Motion
import app.brainfit.core.designsystem.SectionTitle
import app.brainfit.core.designsystem.appear
import app.brainfit.core.designsystem.BrainTheme
import app.brainfit.core.designsystem.Dimens
import app.brainfit.core.designsystem.MutedText
import app.brainfit.core.designsystem.PrimaryButton
import app.brainfit.core.designsystem.SecondaryButton
import kotlin.math.roundToInt
import kotlin.random.Random

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

/** Шаги перед первым раундом: текст инструкции, пробная попытка, игра. */
private const val STEP_HOW_TO = 0
private const val STEP_DEMO = 1
private const val STEP_GAME = 2

/**
 * Экран-хост: общий для всех игр. Сам рисует шапку, инструкцию, пробную попытку, паузу,
 * переходы между раундами, результат и ошибки сохранения; игра рисует только свой раунд через [content].
 *
 * Инструкция показывается перед каждой новой сессией, если [howTo] не пуст; пробная попытка — по кнопке.
 */
@Composable
fun <S : Any, I : Any> PlayScreen(
    controller: PlayController<*, S, I>,
    title: String,
    content: @Composable (state: S, onInput: (I) -> Unit) -> Unit,
    onClose: () -> Unit,
    howTo: List<String> = emptyList(),
    demo: Demo<S, I>? = null,
) {
    val ui by controller.state.collectAsStateWithLifecycle()
    val running = ui.phase == PlayPhase.RUNNING && ui.error == null
    var step by rememberSaveable { mutableIntStateOf(if (howTo.isEmpty()) STEP_GAME else STEP_HOW_TO) }
    val beforeFirstRound = ui.phase == PlayPhase.READY && ui.round == 0 && !ui.restored && ui.error == null
    val intro = beforeFirstRound && step != STEP_GAME
    val inDemo = intro && step == STEP_DEMO && demo != null

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
            inDemo -> step = STEP_HOW_TO
            ui.phase == PlayPhase.RUNNING -> controller.pause()
            else -> controller.exit()
        }
    }

    // Посреди раунда и пробной попытки экран не гаснет сам.
    val view = LocalView.current
    DisposableEffect(running, inDemo) {
        view.keepScreenOn = running || inDemo
        onDispose { view.keepScreenOn = false }
    }

    LaunchedEffect(ui.closed) { if (ui.closed) onClose() }

    val startGame = {
        step = STEP_GAME
        controller.startRound()
    }

    Column(Modifier.fillMaxSize().padding(horizontal = Dimens.screenPadding)) {
        Header(title, ui, onPause = controller::pause)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val error = ui.error
            when {
                error != null -> Panel(
                    title = "Ошибка сохранения",
                    lines = listOf(error),
                    textColor = BrainTheme.colors.error,
                ) {
                    PrimaryButton("Повторить", onClick = controller::retry)
                    SecondaryButton("Выйти без сохранения", onClick = controller::forceClose)
                }

                inDemo -> DemoRunner(
                    demo = demo!!,
                    content = content,
                    onSkip = { step = STEP_HOW_TO },
                    onStart = startGame,
                )

                intro -> Panel(title = "Как играть", lines = howTo, bullets = true) {
                    PrimaryButton("Начать", onClick = startGame)
                    if (demo != null) SecondaryButton("Попробовать", onClick = { step = STEP_DEMO })
                    SecondaryButton("Выйти", onClick = controller::exit)
                }

                ui.phase == PlayPhase.LOADING || ui.phase == PlayPhase.SAVING -> Unit

                ui.phase == PlayPhase.READY -> Panel(
                    title = "Раунд ${ui.round + 1} из ${ui.totalRounds}",
                    lines = listOfNotNull(
                        ui.roundSummary?.let { "Прошлый раунд: $it" },
                        ui.levelNote,
                        if (ui.restored) "Продолжаем с того раунда, на котором остановились." else null,
                    ),
                ) {
                    PrimaryButton(if (ui.round == 0 && !ui.restored) "Начать" else "Дальше", onClick = controller::startRound)
                    SecondaryButton("Выйти", onClick = controller::exit)
                }

                ui.phase == PlayPhase.RUNNING -> ui.gameState?.let { content(it, controller::input) }

                // На паузе игра скрыта: иначе в играх на память можно подсматривать.
                ui.phase == PlayPhase.PAUSED -> Panel(
                    title = "Пауза",
                    lines = listOfNotNull(if (ui.restartOnResume) "После паузы раунд начнётся заново." else null),
                ) {
                    PrimaryButton(if (ui.restartOnResume) "Начать раунд заново" else "Продолжить", onClick = controller::resume)
                    if (!ui.restartOnResume) SecondaryButton("Заново", onClick = controller::restartRound)
                    SecondaryButton("Выйти", onClick = controller::exit)
                }

                ui.phase == PlayPhase.FINISHED -> Panel(
                    title = if (ui.result != null) "Результат" else "Сессия завершена",
                    hero = ui.result?.let { (it.score * 100).roundToInt() },
                    highlights = ui.levelLines + ui.compareLines,
                    lines = ui.resultLines + listOfNotNull(
                        if (ui.shakySession) {
                            "Сильно трясло. Сессия сохранена в истории, но на уровень и рекорды скорости не повлияет."
                        } else {
                            null
                        },
                    ),
                ) {
                    PrimaryButton("Готово", onClick = controller::exit)
                }
            }
        }
    }
}

/**
 * Пробная попытка: игра на простом задании, без раундов и без записи. Время — от начала попытки
 * на той же шкале, что и кадры (System.nanoTime).
 */
@Composable
private fun <S : Any, I : Any> DemoRunner(
    demo: Demo<S, I>,
    content: @Composable (state: S, onInput: (I) -> Unit) -> Unit,
    onSkip: () -> Unit,
    onStart: () -> Unit,
) {
    var attempt by remember { mutableIntStateOf(0) }
    val startNanos = remember(attempt) { System.nanoTime() }
    var state by remember(attempt) { mutableStateOf(demo.initial(Random.Default)) }
    val finished = demo.isFinished(state)

    if (!finished) {
        LaunchedEffect(attempt) {
            while (true) {
                withFrameNanos { frame ->
                    if (!demo.isFinished(state)) state = demo.tick(state, (frame - startNanos).coerceAtLeast(0))
                }
            }
        }
    }

    if (finished) {
        Panel(
            title = "Пробная попытка закончена",
            lines = listOfNotNull(
                demo.summary(state)?.let { "Итог: $it" },
                "Дальше — по-настоящему: ответы будут записаны.",
            ),
        ) {
            PrimaryButton("Начать игру", onClick = onStart)
            SecondaryButton("Ещё раз", onClick = { attempt++ })
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            MutedText("Пробная попытка: ответы не записываются", Modifier.weight(1f))
            TextButton(onClick = onSkip, modifier = Modifier.heightIn(min = Dimens.minTouch)) {
                Text("Пропустить")
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            content(state) { input ->
                state = demo.reduce(state, input, (System.nanoTime() - startNanos).coerceAtLeast(0))
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
                val calibration = if (ui.calibration) " · калибровка" else ""
                MutedText("Раунд ${ui.round + 1} из ${ui.totalRounds} · уровень ${ui.level}$calibration")
            }
        }
        if (ui.phase == PlayPhase.RUNNING && ui.error == null) {
            IconButton(onClick = onPause) {
                Icon(Icons.Rounded.Pause, contentDescription = "Пауза")
            }
        }
    }
}

/** Текст посередине (прокручивается, если длинный), кнопки внизу — под большой палец. */
@Composable
private fun Panel(
    title: String,
    lines: List<String>,
    textColor: Color = BrainTheme.colors.textSecondary,
    bullets: Boolean = false,
    /** Крупный итог в процентах — отсчитывается от нуля при показе. */
    hero: Int? = null,
    /** Важные строки (уровень, сравнение) — над остальными, основным цветом. */
    highlights: List<String> = emptyList(),
    buttons: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(vertical = Dimens.screenPadding)) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = maxHeight)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.Center,
            ) {
                if (hero != null) {
                    SectionTitle(title)
                    ScoreHero(hero)
                } else {
                    Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.appear(0))
                }
                if (highlights.isNotEmpty()) {
                    Column(Modifier.padding(top = 12.dp).appear(1), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        highlights.forEach { Text(it, style = MaterialTheme.typography.titleMedium) }
                    }
                    HairlineDivider(Modifier.padding(top = 12.dp))
                }
                lines.forEach { line ->
                    Text(
                        text = if (bullets) "•  $line" else line,
                        style = MaterialTheme.typography.bodyLarge,
                        color = textColor,
                        modifier = Modifier.padding(top = 8.dp).appear(2),
                    )
                }
            }
        }
        Spacer(Modifier.heightIn(min = Dimens.gap))
        Column(verticalArrangement = Arrangement.spacedBy(Dimens.gap)) { buttons() }
    }
}

/** Итог сессии крупно: число отсчитывается от 0 до результата за 180 мс. */
@Composable
private fun ScoreHero(percent: Int) {
    val shown = remember { Animatable(0f) }
    LaunchedEffect(percent) { shown.animateTo(percent.toFloat(), tween(Motion.BASE, easing = Motion.Enter)) }
    Text(
        text = "${shown.value.roundToInt()} %",
        style = MaterialTheme.typography.displayLarge,
        color = BrainTheme.colors.accent,
        modifier = Modifier.semantics { contentDescription = "Результат $percent %" },
    )
}
