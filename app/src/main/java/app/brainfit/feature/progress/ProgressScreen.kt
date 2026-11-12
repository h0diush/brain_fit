package app.brainfit.feature.progress

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.brainfit.core.designsystem.ActivityGrid
import app.brainfit.core.designsystem.AnimatedValue
import app.brainfit.core.designsystem.BrainCard
import app.brainfit.core.designsystem.BrainTheme
import app.brainfit.core.designsystem.Delta
import app.brainfit.core.designsystem.DeltaBadge
import app.brainfit.core.designsystem.Dimens
import app.brainfit.core.designsystem.HairlineDivider
import app.brainfit.core.designsystem.LevelBadge
import app.brainfit.core.designsystem.Motion
import app.brainfit.core.designsystem.MutedText
import app.brainfit.core.designsystem.SectionTitle
import app.brainfit.core.designsystem.SegmentedControl
import app.brainfit.core.designsystem.StatRow
import app.brainfit.core.designsystem.StatTile
import app.brainfit.core.designsystem.appear
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
import app.brainfit.domain.progress.Trend
import app.brainfit.domain.repository.DifficultyRepository
import app.brainfit.domain.repository.ProgressRepository
import app.brainfit.games.engine.Fmt
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

/** Рекорды одной метрики по уровням. */
data class MetricRecords(val metric: MetricSpec, val records: List<Record>)

/** Сводка за период по всем играм. */
data class Overview(
    val sessions: Int,
    val activeMs: Long,
    val activeDays: Int,
    val periodDays: Int,
    val activity: ActivityLayout,
)

data class ProgressUiState(
    val game: GameId = GameId.SCHULTE,
    val period: ProgressPeriod = ProgressPeriod.WEEK,
    val metricIndex: Int = 0,
    /** false — данные ещё читаются из базы. */
    val loaded: Boolean = false,
    val streak: Streak? = null,
    val overview: Overview? = null,
    val difficulty: GameDifficulty? = null,
    /** Сколько всего завершённых сессий у этой игры. */
    val totalSessions: Int = 0,
    val summary: PeriodSummary? = null,
    /** Тренд выбранной метрики к прошлому периоду; null — для сравнения мало данных. */
    val trend: Trend? = null,
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
                // Период — целые местные сутки: сегодня и ещё (days − 1) дней назад. Прошлый период — столько же до него.
                val firstDay = today.minusDays(sel.period.days - 1L)
                val from = days.startOf(firstDay)
                val previousFrom = days.startOf(firstDay.minusDays(sel.period.days.toLong()))
                val sessions = all.filter { it.gameId == sel.game }
                val metrics = ProgressMetrics.of(sel.game)
                val metricIndex = sel.metricIndex.coerceIn(metrics.indices)
                val allDays = all.map { days.dayOf(it.startedAt).toEpochDay() }
                val counts = Progress.dailyCounts(allDays, firstDay.toEpochDay(), today.toEpochDay())
                val inPeriod = all.filter { it.startedAt >= from }
                ProgressUiState(
                    game = sel.game,
                    period = sel.period,
                    metricIndex = metricIndex,
                    loaded = true,
                    streak = Progress.streak(allDays, today.toEpochDay()),
                    overview = Overview(
                        sessions = inPeriod.size,
                        activeMs = inPeriod.sumOf { it.activeMs },
                        activeDays = counts.count { it > 0 },
                        periodDays = sel.period.days,
                        activity = activityLayout(firstDay, today, counts, transpose = sel.period == ProgressPeriod.QUARTER),
                    ),
                    difficulty = states[sel.game],
                    totalSessions = sessions.size,
                    summary = Progress.period(sessions, metrics[metricIndex], from),
                    trend = Progress.trend(sessions, metrics[metricIndex], from, previousFrom),
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

private fun Long.date(zone: ZoneId): String = Instant.ofEpochMilli(this).atZone(zone).format(dateFormat)

@Composable
fun ProgressScreen(vm: ProgressViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val zone = ZoneId.systemDefault()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Dimens.screenPadding, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(Dimens.gap),
    ) {
        SegmentedControl(
            options = ProgressPeriod.entries,
            selected = state.period,
            label = { "${it.days} дней" },
            onSelect = vm::selectPeriod,
        )
        state.error?.let { Text(it, color = BrainTheme.colors.error, style = MaterialTheme.typography.bodyLarge) }
        if (!state.loaded || state.error != null) return@Column

        state.overview?.let { OverviewCard(it, state.streak, Modifier.appear(0)) }

        ChipRow(Modifier.appear(1)) {
            GameCatalog.all.forEach { game ->
                FilterChip(
                    selected = state.game == game.id,
                    onClick = { vm.selectGame(game.id) },
                    label = { Text(game.title) },
                    shape = MaterialTheme.shapes.small,
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = BrainTheme.colors.textPrimary,
                        selectedLabelColor = BrainTheme.colors.background,
                    ),
                )
            }
        }

        // Смена игры — короткая смена кадра, а не прыжок.
        AnimatedContent(
            targetState = state.game,
            transitionSpec = { fadeIn(tween(Motion.BASE, easing = Motion.Enter)) togetherWith fadeOut(tween(Motion.EXIT, easing = Motion.Exit)) },
            label = "game",
        ) { game ->
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.gap)) {
                if (game != state.game) return@Column
                if (state.totalSessions == 0) {
                    BrainCard {
                        SectionTitle(GameCatalog.get(game).title)
                        Text("Недостаточно данных", style = MaterialTheme.typography.titleMedium)
                        MutedText("В эту игру ещё не играли. Графики и рекорды появятся после первых сессий.")
                    }
                    return@Column
                }
                MetricCard(state, zone, vm::selectMetric, Modifier.appear(2))
                LevelCard(state, zone, Modifier.appear(2))
                RecordsCard(state, zone, Modifier.appear(2))
            }
        }
    }
}

@Composable
private fun ChipRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

@Composable
private fun OverviewCard(o: Overview, streak: Streak?, modifier: Modifier = Modifier) {
    BrainCard(modifier) {
        SectionTitle("Обзор · ${o.periodDays} дней")
        StatRow {
            StatTile("Сессий", "${o.sessions}", Modifier.weight(1f))
            StatTile("Дней", "${o.activeDays} из ${o.periodDays}", Modifier.weight(1f))
            StatTile("Активно", minutes(o.activeMs), Modifier.weight(1f))
        }
        HairlineDivider(Modifier.padding(vertical = 4.dp))
        ActivityGrid(
            rows = o.activity.rows,
            columnLabels = o.activity.columnLabels,
            rowLabels = o.activity.rowLabels,
            // Недели строками — клетки пониже, чтобы месяц не занимал пол-экрана.
            cellAspect = if (o.activity.rowLabels == null) 2f else 1f,
            description = "Календарь занятий: занимались ${Fmt.days(o.activeDays)} из ${o.periodDays}, всего ${Fmt.sessions(o.sessions)}.",
        )
        if (streak != null && streak.best > 0) {
            MutedText(
                if (streak.current > 0) {
                    "Серия — ${Fmt.days(streak.current)} подряд, лучшая — ${Fmt.days(streak.best)}."
                } else {
                    "Серии сейчас нет, лучшая — ${Fmt.days(streak.best)}."
                },
            )
        }
    }
}

/** «12 мин» или «45 с»: для сводки минут достаточно. */
private fun minutes(ms: Long): String = if (ms < 60_000) "${(ms + 500) / 1000} с" else "${(ms + 30_000) / 60_000} мин"

@Composable
private fun MetricCard(state: ProgressUiState, zone: ZoneId, onMetric: (Int) -> Unit, modifier: Modifier = Modifier) {
    val metric = state.metric
    val summary = state.summary ?: return
    BrainCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                GameCatalog.get(state.game).title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            state.difficulty?.let { LevelBadge(it.level, calibration = !it.calibrated) }
        }
        if (state.metrics.size > 1) {
            ChipRow {
                state.metrics.forEachIndexed { i, m ->
                    FilterChip(
                        selected = i == state.metricIndex,
                        onClick = { onMetric(i) },
                        label = { Text(m.title) },
                        shape = MaterialTheme.shapes.small,
                    )
                }
            }
        }
        val last = summary.points.lastOrNull()
        if (last != null) {
            SectionTitle("Последняя сессия")
            AnimatedValue(metric.format(last.value), MaterialTheme.typography.displaySmall, BrainTheme.colors.textPrimary)
            MutedText("${last.at.date(zone)} · уровень ${last.level} · ${if (metric.better == Better.LOWER) "меньше" else "больше"} — лучше")
        }
        TrendLine(state)
        if (summary.enoughData) {
            MetricChart(summary.points, metric, zone)
            if (summary.points.any { it.calibration }) MutedText("Полые точки — калибровка: уровень в ней менялся на ходу.")
            val best = summary.points.reduce { b, p -> if (metric.isBetter(p.value, b.value)) p else b }
            HairlineDivider()
            StatRow {
                StatTile("Лучшая", metric.format(best.value), Modifier.weight(1f), caption = "уровень ${best.level}", accent = true)
                StatTile("Первая", metric.format(summary.points.first().value), Modifier.weight(1f), caption = summary.points.first().at.date(zone))
                StatTile("Сессий", "${summary.sessions}", Modifier.weight(1f), caption = "за ${state.period.days} дней")
            }
        } else {
            HairlineDivider()
            Text("Недостаточно данных для графика", style = MaterialTheme.typography.titleMedium)
            MutedText(
                "Сессий за ${state.period.days} дней: ${summary.sessions}" +
                    (if (summary.points.size < summary.sessions) ", на графике из них ${summary.points.size}" else "") +
                    ". Нужно хотя бы $MIN_SESSIONS_FOR_CHART.",
            )
        }
        if (summary.shaky > 0) {
            MutedText("С тряской: ${Fmt.sessions(summary.shaky)}" + if (metric.speed) " — на графике скорости их нет." else ".")
        }
    }
}

/** Тренд к прошлому периоду: значок, слово и цифра, а не только цвет. */
@Composable
private fun TrendLine(state: ProgressUiState) {
    val trend = state.trend
    val days = state.period.days
    if (trend == null) {
        MutedText("Сравнение с прошлыми $days днями — когда в обоих периодах наберётся по $MIN_SESSIONS_FOR_CHART обычные сессии на одном уровне.")
        return
    }
    val (text, better) = Progress.trendText(state.metric, trend)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DeltaBadge(text, if (better == null) Delta.SAME else if (better) Delta.BETTER else Delta.WORSE)
        MutedText("к прошлым $days дням · медиана, уровень ${trend.level}")
    }
}

/** История уровней — вертикальная шкала: точка на каждую смену, новые сверху. */
@Composable
private fun LevelCard(state: ProgressUiState, zone: ZoneId, modifier: Modifier = Modifier) {
    val colors = BrainTheme.colors
    BrainCard(modifier) {
        SectionTitle("История уровней")
        val d = state.difficulty
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AnimatedValue(d?.let { "${it.level}" } ?: "—", MaterialTheme.typography.displaySmall, colors.accent)
            MutedText(
                when {
                    d == null -> "уровня ещё нет"
                    d.calibrated -> "текущий уровень"
                    else -> "текущий уровень · идёт калибровка"
                },
                Modifier.padding(bottom = 8.dp),
            )
        }
        if (state.steps.isEmpty()) {
            MutedText("За ${state.period.days} дней уровень не менялся.")
        } else {
            state.steps.forEachIndexed { i, step ->
                val why = if (step.calibration) "калибровка" else if (step.toLevel > step.fromLevel) "повышен" else "понижен"
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(20.dp), contentAlignment = Alignment.Center) {
                        Box(
                            Modifier
                                .size(10.dp)
                                .let { if (i == 0) it.background(colors.accent, CircleShape) else it.border(2.dp, colors.outline, CircleShape) },
                        )
                    }
                    Text(
                        step.levels.joinToString(" → "),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(start = 8.dp).weight(1f),
                    )
                    MutedText("${step.at.date(zone)} · $why")
                }
                if (i < state.steps.lastIndex) {
                    Box(Modifier.padding(start = 9.dp).width(2.dp).height(12.dp).background(colors.surfaceSecondary))
                }
            }
        }
    }
}

/** Рекорды — таблица: уровень, значение, дата. Строка текущего уровня выделена. */
@Composable
private fun RecordsCard(state: ProgressUiState, zone: ZoneId, modifier: Modifier = Modifier) {
    val current = state.difficulty?.level
    BrainCard(modifier) {
        SectionTitle("Рекорды по уровням")
        state.records.forEach { (metric, records) ->
            Text(metric.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
            if (records.isEmpty()) {
                MutedText("Пока нет: рекорды считаются по сессиям после калибровки.")
            } else {
                records.sortedByDescending { it.level }.forEachIndexed { i, r ->
                    if (i > 0) HairlineDivider()
                    val isCurrent = r.level == current
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Уровень ${r.level}",
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (isCurrent) BrainTheme.colors.accent else BrainTheme.colors.textPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        Text(metric.format(r.value), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(end = 12.dp))
                        MutedText(r.at.date(zone))
                    }
                }
            }
        }
        MutedText("Калибровка не считается, а для скорости — и сессии с тряской.")
    }
}
