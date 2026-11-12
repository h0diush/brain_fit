package app.brainfit.feature.progress

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.brainfit.core.designsystem.BrainCard
import app.brainfit.core.designsystem.BrainTheme
import app.brainfit.core.designsystem.Dimens
import app.brainfit.core.designsystem.MutedText
import app.brainfit.core.designsystem.SectionTitle
import app.brainfit.core.time.LocalDays
import app.brainfit.core.time.WallClock
import app.brainfit.domain.adaptive.Adaptation
import app.brainfit.domain.model.GameCatalog
import app.brainfit.domain.model.GameDifficulty
import app.brainfit.domain.model.GameId
import app.brainfit.domain.progress.Better
import app.brainfit.domain.progress.LevelStep
import app.brainfit.domain.progress.MIN_SESSIONS_FOR_CHART
import app.brainfit.domain.progress.MetricSpec
import app.brainfit.domain.progress.PeriodSummary
import app.brainfit.domain.progress.Progress
import app.brainfit.domain.progress.ProgressMetrics
import app.brainfit.domain.progress.ProgressPeriod
import app.brainfit.domain.progress.Record
import app.brainfit.domain.progress.Streak
import app.brainfit.domain.repository.DifficultyRepository
import app.brainfit.domain.repository.ProgressRepository
import app.brainfit.feature.home.StreakCard
import app.brainfit.games.engine.Fmt
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

/** Рекорды одной метрики по уровням. */
data class MetricRecords(val metric: MetricSpec, val records: List<Record>)

data class ProgressUiState(
    val game: GameId = GameId.SCHULTE,
    val period: ProgressPeriod = ProgressPeriod.WEEK,
    val metricIndex: Int = 0,
    /** false — данные ещё читаются из базы. */
    val loaded: Boolean = false,
    val streak: Streak? = null,
    val difficulty: GameDifficulty? = null,
    /** Сколько всего завершённых сессий у этой игры. */
    val totalSessions: Int = 0,
    val summary: PeriodSummary? = null,
    /** Шаги уровня за период, новые первыми. */
    val steps: List<LevelStep> = emptyList(),
    val records: List<MetricRecords> = emptyList(),
    val error: String? = null,
) {
    val metrics: List<MetricSpec> get() = ProgressMetrics.of(game)
    val metric: MetricSpec get() = metrics[metricIndex.coerceIn(metrics.indices)]
}

private data class Selection(val game: GameId, val period: ProgressPeriod, val metricIndex: Int)

@HiltViewModel
class ProgressViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    progress: ProgressRepository,
    difficulty: DifficultyRepository,
    wallClock: WallClock,
) : ViewModel() {

    private val days = LocalDays(wallClock)

    private val selection: Flow<Selection> = combine(
        savedState.getStateFlow(KEY_GAME, GameId.SCHULTE.name),
        savedState.getStateFlow(KEY_PERIOD, ProgressPeriod.WEEK.name),
        savedState.getStateFlow(KEY_METRIC, 0),
    ) { game, period, metric ->
        Selection(
            GameId.entries.firstOrNull { it.name == game } ?: GameId.SCHULTE,
            ProgressPeriod.entries.firstOrNull { it.name == period } ?: ProgressPeriod.WEEK,
            metric,
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<ProgressUiState> = combine(selection, days.observe(), ::Pair)
        .flatMapLatest { (sel, today) ->
            combine(
                progress.observeCompleted(),
                progress.observeLevelChanges(sel.game),
                difficulty.observeStates(),
            ) { all, changes, states ->
                // Период — целые местные сутки: сегодня и ещё (days − 1) дней назад.
                val from = days.startOf(today.minusDays(sel.period.days - 1L))
                val sessions = all.filter { it.gameId == sel.game }
                val metrics = ProgressMetrics.of(sel.game)
                val metricIndex = sel.metricIndex.coerceIn(metrics.indices)
                ProgressUiState(
                    game = sel.game,
                    period = sel.period,
                    metricIndex = metricIndex,
                    loaded = true,
                    streak = Progress.streak(all.map { days.dayOf(it.startedAt).toEpochDay() }, today.toEpochDay()),
                    difficulty = states[sel.game],
                    totalSessions = sessions.size,
                    summary = Progress.period(sessions, metrics[metricIndex], from),
                    steps = Progress.levelSteps(changes, Adaptation.REASON_CALIBRATION).filter { it.at >= from }.reversed(),
                    records = metrics.map { MetricRecords(it, Progress.records(sessions, it)) },
                )
            }.catch { e ->
                // Ошибку чтения не глотаем: показываем на экране.
                emit(ProgressUiState(sel.game, sel.period, loaded = true, error = "Не удалось прочитать историю: ${e.message ?: e.javaClass.simpleName}"))
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProgressUiState())

    fun selectGame(game: GameId) {
        savedState[KEY_GAME] = game.name
        savedState[KEY_METRIC] = 0
    }

    fun selectPeriod(period: ProgressPeriod) {
        savedState[KEY_PERIOD] = period.name
    }

    fun selectMetric(index: Int) {
        savedState[KEY_METRIC] = index
    }

    private companion object {
        const val KEY_GAME = "progressGame"
        const val KEY_PERIOD = "progressPeriod"
        const val KEY_METRIC = "progressMetric"
    }
}

private val dateFormat = DateTimeFormatter.ofPattern("d MMM", Locale.forLanguageTag("ru"))

@Composable
fun ProgressScreen(vm: ProgressViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val zone = ZoneId.systemDefault()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Dimens.screenPadding),
        verticalArrangement = Arrangement.spacedBy(Dimens.gap),
    ) {
        state.streak?.let { StreakCard(it) }
        ChipRow {
            GameCatalog.all.forEach { game ->
                FilterChip(
                    selected = state.game == game.id,
                    onClick = { vm.selectGame(game.id) },
                    label = { Text(game.title) },
                )
            }
        }
        ChipRow {
            ProgressPeriod.entries.forEach { period ->
                FilterChip(
                    selected = state.period == period,
                    onClick = { vm.selectPeriod(period) },
                    label = { Text("${period.days} дней") },
                )
            }
        }
        state.error?.let { Text(it, color = BrainTheme.colors.error, style = MaterialTheme.typography.bodyLarge) }
        if (!state.loaded || state.error != null) return@Column

        if (state.totalSessions == 0) {
            BrainCard {
                SectionTitle("Недостаточно данных")
                MutedText("В эту игру ещё не играли. Графики и рекорды появятся после первых сессий.")
            }
            return@Column
        }
        ChartCard(state, zone, vm::selectMetric)
        LevelCard(state, zone)
        RecordsCard(state, zone)
    }
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

@Composable
private fun ChartCard(state: ProgressUiState, zone: ZoneId, onMetric: (Int) -> Unit) {
    val metric = state.metric
    val summary = state.summary ?: return
    BrainCard {
        // Несколько метрик — название на чипах, одна — в заголовке.
        SectionTitle(if (state.metrics.size > 1) "График" else metric.title)
        if (state.metrics.size > 1) {
            ChipRow {
                state.metrics.forEachIndexed { i, m ->
                    FilterChip(selected = i == state.metricIndex, onClick = { onMetric(i) }, label = { Text(m.title) })
                }
            }
        }
        val periodText = "за ${state.period.days} дней"
        if (summary.enoughData) {
            MutedText(if (metric.better == Better.LOWER) "Меньше — лучше." else "Больше — лучше.")
            MetricChart(summary.points, metric, zone)
            val best = summary.points.reduce { b, p -> if (metric.isBetter(p.value, b.value)) p else b }
            MutedText(
                "Первая: ${metric.format(summary.points.first().value)} · последняя: ${metric.format(summary.points.last().value)} · " +
                    "лучшая: ${metric.format(best.value)} (уровень ${best.level}).",
            )
            if (summary.points.any { it.calibration }) MutedText("Полые точки — калибровка: уровень в ней менялся на ходу.")
        } else {
            Text("Недостаточно данных", style = MaterialTheme.typography.titleMedium)
            MutedText(
                "Сессий $periodText: ${summary.sessions}" +
                    (if (summary.points.size < summary.sessions) ", на графике из них ${summary.points.size}" else "") +
                    ". Для графика нужно хотя бы $MIN_SESSIONS_FOR_CHART.",
            )
        }
        if (summary.shaky > 0) {
            MutedText(
                "С тряской: ${Fmt.sessions(summary.shaky)}" +
                    if (metric.speed) " — на графике скорости их нет." else ".",
            )
        }
        if (summary.enoughData) MutedText("Всего $periodText: ${Fmt.sessions(summary.sessions)}.")
    }
}

@Composable
private fun LevelCard(state: ProgressUiState, zone: ZoneId) {
    BrainCard {
        SectionTitle("Уровень")
        val d = state.difficulty
        Text(
            text = when {
                d == null -> "Уровня ещё нет"
                d.calibrated -> "Сейчас уровень ${d.level}"
                else -> "Сейчас уровень ${d.level} · калибровка"
            },
            style = MaterialTheme.typography.titleMedium,
            color = BrainTheme.colors.accent,
        )
        if (state.steps.isEmpty()) {
            MutedText("За ${state.period.days} дней уровень не менялся.")
        } else {
            state.steps.forEach { step ->
                val date = Instant.ofEpochMilli(step.at).atZone(zone).format(dateFormat)
                val why = if (step.calibration) "калибровка" else if (step.toLevel > step.fromLevel) "повышен" else "понижен"
                MutedText("$date · ${step.levels.joinToString(" → ")} · $why")
            }
        }
    }
}

@Composable
private fun RecordsCard(state: ProgressUiState, zone: ZoneId) {
    BrainCard {
        SectionTitle("Рекорды")
        MutedText("У каждого уровня свой рекорд. Калибровка не считается, а для скорости — и сессии с тряской.")
        state.records.forEach { (metric, records) ->
            Text(metric.title, style = MaterialTheme.typography.titleSmall)
            if (records.isEmpty()) {
                MutedText("Пока нет.")
            } else {
                records.sortedByDescending { it.level }.forEach { r ->
                    val date = Instant.ofEpochMilli(r.at).atZone(zone).format(dateFormat)
                    MutedText("Уровень ${r.level} — ${metric.format(r.value)} · $date")
                }
            }
        }
    }
}
